package com.example.voiceapp3.tools

import java.util.ArrayDeque

/**
 * Подставное устройство для тестов оркестратора: префы, состояние сети и shell.
 *
 * Специально моделирует то, что REVIEW §0.1(1),(2),(5) требует проверять:
 *  • очередь транспорта с ОТЛОЖЕННЫМ исполнением (`AdbClient.kt:417-425` — Job остаётся в
 *    `LinkedBlockingQueue`, таймаут вызывающего его не отменяет);
 *  • запись префов, которая может «дойти» только в одно хранилище и не подтвердиться чтением;
 *  • отложенное (асинхронное) появление AP/Wi-Fi после запроса и полный отказ API.
 */
object JitFake {

    /** Префы с двумя «хранилищами» и управляемыми отказами записи. */
    class Store : JitStore {
        val cps = LinkedHashMap<String, String?>()
        val dps = LinkedHashMap<String, String?>()
        val booleanCps = LinkedHashMap<String, Boolean>()
        val booleanDps = LinkedHashMap<String, Boolean>()

        /** Запретить запись в CPS (как будто оно недоступно). */
        var cpsFails = false
        /** Запретить запись в DPS. */
        var dpsFails = false
        /** Записать, но не подтвердить (эмуляция «commit=false»). */
        var commitReportsFalse = false
        /** Сколько записей выполнить успешно, затем начать «падать» (смерть на шаге). */
        var writesUntilFailure = Int.MAX_VALUE
        /** Исключение на записи (адаптер обязан это пережить). */
        var throwOnWrite = false

        /** Сколько записей реально пытались сделать (для проверки «на старте — ноль записей»). */
        @Synchronized
        fun writeCount(): Int = writes

        private var writes = 0

        private fun mayWrite(): Boolean {
            if (throwOnWrite) throw IllegalStateException("store write failure")
            writes++
            return writes <= writesUntilFailure
        }

        @Synchronized
        override fun getString(key: String): String? = cps[key] ?: dps[key]

        @Synchronized
        override fun putStringConfirmed(key: String, value: String?): Boolean {
            val allowed = mayWrite()
            if (allowed && !cpsFails) cps[key] = value
            if (allowed && !dpsFails) dps[key] = value
            val back = getString(key)
            return allowed && !commitReportsFalse && back == value
        }

        @Synchronized
        override fun getBool(key: String): Boolean? {
            val c = booleanCps[key]
            val d = booleanDps[key]
            if (c == null && d == null) return null
            return (c == true) || (d == true)
        }

        @Synchronized
        override fun putBoolConfirmed(key: String, value: Boolean): Boolean {
            val allowed = mayWrite()
            if (allowed && !cpsFails) booleanCps[key] = value
            if (allowed && !dpsFails) booleanDps[key] = value
            val back = getBool(key)
            return allowed && !commitReportsFalse && back == value
        }

        fun op(): JitRef.Op? = JitRef.decodeOp(getString(JitRef.KEY_OP))
        fun rawOp(): String? = getString(JitRef.KEY_OP)
    }

    /**
     * Сеть. `applyDelayCalls` — сколько обращений к состоянию должно пройти до того, как запрос
     * «сработает» (0 = сразу). `apiBroken` — `setWifi`/`startAp` возвращают отказ.
     */
    class Net(
        var wifi: Boolean = true,
        var ap: Boolean = false,
        var stateErrors: Boolean = false,
        var apiBroken: Boolean = false,
        var wifiAppliesAfterReads: Int = 0,
        var apAppliesAfterReads: Int = 0
    ) : JitNetState {
        val requests = mutableListOf<String>()
        private var wifiPending = false
        private var apPending = false
        private var wifiReadsAfterRequest = 0
        private var apReadsAfterRequest = 0

        override fun wifiOn(): Boolean? {
            if (stateErrors) return null
            if (wifiPending) {
                wifiReadsAfterRequest++
                if (wifiReadsAfterRequest > wifiAppliesAfterReads) { wifi = true; wifiPending = false }
            }
            return wifi
        }

        override fun apUp(): Boolean? {
            if (stateErrors) return null
            if (apPending) {
                apReadsAfterRequest++
                if (apReadsAfterRequest > apAppliesAfterReads) { ap = true; apPending = false }
            }
            return ap
        }

        override fun setWifi(on: Boolean): Pair<Boolean, String> {
            requests += "wifi=$on"
            if (apiBroken) return Pair(false, "throw:SecurityException")
            if (on) wifiPending = true else { wifi = false; wifiPending = false }
            return Pair(true, "ok")
        }

        override fun startAp(): Pair<Boolean, String> {
            requests += "ap=start"
            if (apiBroken) return Pair(false, "throw:NoSuchMethodException")
            apPending = true
            return Pair(true, "reflection_invoked")
        }
    }

    /** Ожидание без реального сна: запоминает паузы, часы двигает сам. */
    class Waiter(start: Long = 1_000L) : JitWaiter {
        val sleeps = mutableListOf<Long>()
        private var clock = start
        override fun sleep(ms: Long) { sleeps += ms; clock += ms }
        override fun nowMs(): Long = clock
    }

    /**
     * Shell с ОЧЕРЕДЬЮ, как у настоящего `AdbClient`: команда может быть отдана в устройство
     * позже; вызывающий получает таймаут (rc=137, `ERR_ADB_TIMEOUT`), а работа продолжается.
     */
    class QueueShell(private val stand: JitIsoStand? = null) : JitShell {
        /** Ещё не выполненные команды (в порядке постановки). */
        val pending = ArrayDeque<Pair<String, Int>>()
        /** Уже выполненные — с результатом. */
        val executed = mutableListOf<Triple<String, Int, String>>()
        /** Ответ на `getprop` в этом «устройстве». */
        var getprop: String = "true"
        var getpropRc: Int = 0
        var cache: Boolean? = true
        /** Сколько команд отдать устройству синхронно (0 — всё в очередь). */
        var runInline = 0

        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            if (command.startsWith("getprop ")) {
                // как настоящий канал: при rc≠0 вывода значения нет, есть только код ошибки
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

        /** Сценарий «очередь наконец исполнилась» — то, что происходит на устройстве позже. */
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
