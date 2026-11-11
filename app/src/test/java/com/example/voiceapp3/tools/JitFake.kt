package com.example.voiceapp3.tools

import java.util.ArrayDeque

/**
 * Подставное устройство для тестов JIT-цепочки.
 *
 * Моделирует ровно то, что есть в референсе и в наших адаптерах: префы с одним `commit()` на
 * снимок, доступность `WifiManager`/`ap0` (включая «прочитать нельзя»), рефлексию `startSoftAp`
 * (успех/отказ/исключение), отложенное появление AP и Wi-Fi и транспорт с очередью.
 */
object JitFake {

    /**
     * Префы. Помимо значений считает ЗАПИСИ (`commits`): тесты проверяют, что клик пишет снимок
     * одним `commit()` и что обычный старт не пишет вообще ничего.
     */
    class Store : JitRef.JitStore {

        val values = LinkedHashMap<String, Any?>()
        val commits = mutableListOf<String>()

        /** Что вернёт `commit()` (в референсе результат не используется — мы тоже его не гоняем). */
        var commitResult: Boolean = true
        var throwOnWrite: Boolean = false

        @Synchronized
        fun writeCount(): Int = commits.size

        fun rawRestore(): String? = values[JitRef.KEY_RESTORE] as? String

        @Synchronized
        override fun getString(key: String): String? = values[key] as? String

        @Synchronized
        override fun getBool(key: String): Boolean? = values[key] as? Boolean

        @Synchronized
        override fun contains(key: String): Boolean = values.containsKey(key)

        @Synchronized
        override fun putSnapshot(want: Boolean, net: JitRef.Net): Boolean {
            if (throwOnWrite) throw IllegalStateException("store write failure")
            commits += "snapshot:${net.wire()}:$want"
            values[JitRef.KEY_PEDIDO] = want
            values[JitRef.KEY_AUTO] = want
            values[JitRef.KEY_RESTORE] = net.wire()
            return commitResult
        }

        @Synchronized
        override fun putBool(key: String, value: Boolean): Boolean {
            if (throwOnWrite) throw IllegalStateException("store write failure")
            commits += "bool:$key=$value"
            values[key] = value
            return commitResult
        }

        @Synchronized
        override fun putString(key: String, value: String?): Boolean {
            if (throwOnWrite) throw IllegalStateException("store write failure")
            commits += if (value == null) "remove:$key" else "string:$key=$value"
            if (value == null) values.remove(key) else values[key] = value
            return commitResult
        }
    }

    /**
     * `WifiManager` + `ap0` + рефлексия `startSoftAp`.
     *
     * `wifiReadError`/`apReadError` — «прочитать нельзя» (`null`), как исключение в референсе.
     * `*AppliesAfterReads` — AP/Wi-Fi поднимаются не сразу после запроса (как на устройстве).
     */
    class Net(
        var wifi: Boolean = true,
        var ap: Boolean = false,
        var wifiReadError: Boolean = false,
        var apReadError: Boolean = false,
        var wifiAppliesAfterReads: Int = 0,
        var apAppliesAfterReads: Int = 0
    ) : JitRef.JitNetState {

        val requests = mutableListOf<String>()

        /** `null` — вызов `setWifiEnabled` бросил исключение. */
        var setWifiResult: Boolean? = true
        /** `null` — рефлексия `startSoftAp` бросила исключение; `false` — вернула `false`. */
        var startApResult: Boolean? = true

        private var wifiPending = false
        private var apPending = false
        private var wifiReads = 0
        private var apReads = 0

        override fun wifiOn(): Boolean? {
            if (wifiReadError) return null
            if (wifiPending) {
                wifiReads++
                if (wifiReads > wifiAppliesAfterReads) {
                    wifi = true
                    wifiPending = false
                }
            }
            return wifi
        }

        override fun apUp(): Boolean? {
            if (apReadError) return null
            if (apPending) {
                apReads++
                if (apReads > apAppliesAfterReads) {
                    ap = true
                    apPending = false
                }
            }
            return ap
        }

        override fun setWifi(on: Boolean): Boolean? {
            requests += "wifi=$on"
            val r = setWifiResult
            if (r == null) return null
            if (on) wifiPending = true else {
                wifi = false
                wifiPending = false
            }
            return r
        }

        override fun startAp(): Boolean? {
            requests += "ap=start"
            val r = startApResult
            if (r == null) return null
            if (r) apPending = true
            return r
        }
    }

    /** Ожидание без реального сна: запоминает паузы, часы двигает сам. */
    class Waiter(start: Long = 1_000L) : JitRef.JitWaiter {
        val sleeps = mutableListOf<Long>()
        private var clock = start
        override fun sleep(ms: Long) {
            sleeps += ms
            clock += ms
        }

        override fun nowMs(): Long = clock
    }

    /**
     * Shell с очередью, как настоящий `AdbClient`: команда может уйти в устройство позже, а
     * вызывающий получит таймаут (`rc=137`, `ERR_ADB_TIMEOUT`). Задание при этом НЕ отменяется.
     */
    class QueueShell(private val stand: JitIsoStand? = null) : JitRef.JitShell {

        val pending = ArrayDeque<Pair<String, Int>>()
        val executed = mutableListOf<Triple<String, Int, String>>()

        var getprop: String = "true"
        var getpropRc: Int = 0
        var cache: Boolean? = true

        /** Сколько команд отдать устройству сразу (0 — всё в очередь). */
        var runInline = 0

        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            if (command.startsWith("getprop ")) {
                return if (getpropRc == 0) Pair(0, getprop)
                else Pair(getpropRc, "ERR_ROOT_CHANNEL_UNAVAILABLE after ${timeoutMs}ms")
            }
            if (executed.size < runInline) {
                val (rc, out) = stand?.exec(command, getprop) ?: Pair(0, "")
                executed += Triple(command, rc, out)
                return Pair(rc, out)
            }
            pending.addLast(Pair(command, timeoutMs))
            return Pair(137, "ERR_ADB_TIMEOUT after ${timeoutMs}ms")
        }

        override fun cacheVisible(): Boolean? = cache

        /** «Очередь наконец исполнилась» — то, что происходит на устройстве позже. */
        fun drain(onCommand: (String) -> Unit = {}): Int {
            var n = 0
            while (pending.isNotEmpty()) {
                val (cmd, _) = pending.removeFirst()
                val (rc, out) = stand?.exec(cmd, getprop) ?: Pair(0, "")
                executed += Triple(cmd, rc, out)
                onCommand(cmd)
                n++
            }
            return n
        }
    }
}
