package com.example.voiceapp3.tools

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Подставные реализации интерфейсов [JitRef] — для локальных тестов на JVM (не на ГУ).
 *
 * Все фейки фиксируют ФАКТИЧЕСКИЕ действия: какие команды ушли, какие ключи записаны/удалены,
 * какие сетевые вызовы сделаны, в каком порядке, сколько раз.
 */
object JitFake {

    private fun wire(want: Boolean, net: JitRef.Net) = "wifi=${if (net.wifi) 1 else 0},ap=${if (net.ap) 1 else 0}:$want"

    // ─────────────────────────── префы ───────────────────────────

    /**
     * Два хранилища проекта (CPS/DPS): каждое можно «сломать» отдельно.
     *
     * `commitFails` — индексы хранилищ, у которых `commit()` возвращает false;
     * `throwOn` — индексы, где `edit()` бросает исключение. Сводный результат обязан честно
     * показывать `attempted/ok`, а не обрываться на первом.
     */
    class Store(storeCount: Int = 2) : JitRef.JitStore {
        val maps: List<MutableMap<String, Any?>> = List(storeCount) { mutableMapOf() }
        val commitFails = mutableSetOf<Int>()
        val throwOn = mutableSetOf<Int>()

        /** Журнал операций: `snapshot:…`, `remove:key`, `commit:i`. */
        val ops = mutableListOf<String>()
        val commits = mutableListOf<String>()

        fun writeCount(): Int = ops.size

        fun rawRestore(): String? = getString(key = JitRef.KEY_RESTORE)

        fun presentIn(store: Int, key: String): Boolean = maps[store].containsKey(key)

        // JitStore

        override fun getString(key: String): String? =
            maps.firstNotNullOfOrNull { m -> if (m.containsKey(key)) m[key] as? String else null }

        override fun getBool(key: String): Boolean? =
            maps.firstNotNullOfOrNull { m -> if (m.containsKey(key)) m[key] as? Boolean else null }

        override fun contains(key: String): Boolean = maps.any { it.containsKey(key) }

        override fun putSnapshot(want: Boolean, net: JitRef.Net): JitRef.WriteResult {
            var ok = 0
            val failed = mutableListOf<String>()
            for ((i, m) in maps.withIndex()) {
                if (i in throwOn) { failed += "s$i:exception"; continue }
                if (i in commitFails) { failed += "s$i:commit_false"; continue }
                m[JitRef.KEY_PEDIDO] = want
                m[JitRef.KEY_AUTO] = want
                m[JitRef.KEY_RESTORE] = net.wire()
                ok++
                ops += "snapshot:$i"
                commits += wire(want, net)
            }
            return JitRef.WriteResult(maps.size, ok, failed.joinToString(",").ifEmpty { "all_ok" })
        }

        override fun remove(key: String): JitRef.WriteResult {
            var ok = 0
            val failed = mutableListOf<String>()
            for ((i, m) in maps.withIndex()) {
                if (i in throwOn) { failed += "s$i:exception"; continue }
                if (i in commitFails) { failed += "s$i:commit_false"; continue }
                m.remove(key)
                ok++
                ops += "remove:$key:$i"
            }
            return JitRef.WriteResult(maps.size, ok, failed.joinToString(",").ifEmpty { "all_ok" })
        }

        /** Прямая установка значения в указанное хранилище — для подготовки состояния. */
        fun setIn(store: Int, key: String, value: Any?) {
            if (value == null) maps[store].remove(key) else maps[store][key] = value
        }

        /** Прямая установка значения в первое хранилище — для подготовки состояния в тесте. */
        fun set(key: String, value: Any?) {
            if (value == null) maps[0].remove(key) else maps[0][key] = value
        }
    }

    // ─────────────────────────── радио ───────────────────────────

    /**
     * `WifiManager`/`ap0`/`startSoftAp` + сигнал FAILED (`u1`) и CarplayMonitor (`O()`).
     *
     * - `wifi`, `ap` — наблюдаемое состояние; `wifiEnabling` — `getWifiState()==ENABLING`;
     * - `wifiReadError`/`apReadError` — чтение бросает (в референсе `false`, в `c1()` — `true`);
     * - `apAppliesAfterReads` — сколько чтений `apUp()` вернут `false` до появления AP;
     * - `setWifiResult` — что вернул `setWifiEnabled` (`null` = исключение);
     * - `startApResult` — что вернул `startSoftAp` (`null` = исключение);
     * - `apFailsAtRead` — на каком чтении `apUp()` «прилетает» FAILED (событие broadcast);
     * - `carplay` — значение `carplayBusy()` (`null` = сигнала нет).
     */
    class Net(
        var wifi: Boolean = false,
        var ap: Boolean = false,
        var wifiEnabling: Boolean = false,
        var wifiReadError: Boolean = false,
        var apReadError: Boolean = false,
        var apAppliesAfterReads: Int = 0,
        var setWifiResult: Boolean? = true,
        var startApResult: Boolean? = true,
        var setWifiThrows: Boolean = false,
        var apFailedAtRead: Int? = null,
        var carplay: Boolean? = null,
    ) : JitRef.JitNetState {

        val requests = mutableListOf<String>()
        var apReads = 0
        var apFailedAt: Long = 0L
        var watchOn = false
        var watchRegistrations = 0

        override fun wifiOn(): Boolean? {
            if (wifiReadError) throw IllegalStateException("wifi read failed")
            return wifi
        }

        override fun wifiEnabling(): Boolean? {
            if (wifiReadError) throw IllegalStateException("wifi read failed")
            return wifiEnabling
        }

        override fun apUp(): Boolean? {
            if (apReadError) throw IllegalStateException("ap read failed")
            apReads++
            apFailedAtRead?.let { if (apReads == it) apFailedAt = 1_000L + apReads }
            if (apAppliesAfterReads > 0) {
                apAppliesAfterReads--
                return false
            }
            return ap
        }

        override fun setWifi(on: Boolean): Boolean? {
            if (setWifiThrows) throw IllegalStateException("setWifiEnabled failed")
            requests += "wifi=$on"
            if (setWifiResult == true && on) wifi = true
            return setWifiResult
        }

        override fun startAp(): Boolean? {
            requests += "ap=start"
            if (startApResult == null) return null
            if (startApResult == true) ap = true
            return startApResult
        }

        override fun apFailedAtMs(): Long = apFailedAt

        override fun beginApWatch() { watchOn = true; watchRegistrations++ }

        override fun endApWatch() { watchOn = false }

        override fun carplayBusy(): Boolean? = carplay
    }

    // ─────────────────────────── время и планировщик ───────────────────────────

    class Waiter : JitRef.JitWaiter {
        val sleeps = mutableListOf<Long>()
        private var t = 1_000L

        override fun sleep(ms: Long) {
            sleeps += ms
            t += ms
        }

        override fun nowMs(): Long = t
    }

    /** Планировщик как `postDelayed`: ничего не выполняет сам, задачи складывает в очередь. */
    class Scheduler(clock: Waiter? = null) : JitRef.JitScheduler {
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        private val waiter = clock

        /** `true`, если выполнять сразу при планировании (для тестов «как будто 15 с прошли»). */
        var runInline = false

        override fun postDelayed(delayMs: Long, task: () -> Unit) {
            scheduled += delayMs to task
            if (runInline) task()
        }

        /** Выполнить всё запланированное (в порядке постановки). */
        fun drain() {
            val copy = scheduled.toList()
            scheduled.clear()
            copy.forEach { (_, t) -> t() }
        }

        fun delays(): List<Long> = scheduled.map { it.first }
    }

    // ─────────────────────────── shell ───────────────────────────

    /**
     * Транспорт: очередь команд с настраиваемым поведением.
     *
     * `runInline = 1` — команда «выполнилась и ответила»; `0` — ответа нет (таймаут вызывающего,
     * как потеря канала). `getprop`/`cache` задают ответ сверки.
     */
    class QueueShell(var stand: JitIsoStand?) : JitRef.JitShell {
        val pending = mutableListOf<String>()
        val executed = mutableListOf<Triple<String, Int, String>>()
        var runInline = 0
        var getprop: String = "true"
        var getpropRc: Int = 0
        var cache: Boolean? = true

        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            pending += command
            if (runInline > 0 && command.contains("zygote")) {
                runInline--
                val (rc, out) = stand?.exec(command) ?: Pair(0, "")
                executed += Triple(command, rc, out)
                pending.remove(command)
                return rc to out
            }
            return 137 to "ERR_CHANNEL_LOST"
        }

        override fun cacheVisible(): Boolean? = cache

        fun getpropAnswers(): Pair<Int, String> = getpropRc to getprop
    }

    /**
     * Shell, который отвечает на `getprop` значениями из очереди, а на скрипт — «ок».
     * Нужен для проверки сверки без выполнения цепочки.
     */
    class AnswerShell(
        private val answers: MutableList<Pair<Int, String>> = mutableListOf(),
        var cache: Boolean? = true,
    ) : JitRef.JitShell {
        val executed = mutableListOf<String>()
        var default: Pair<Int, String> = 0 to "true"

        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            executed += command
            if (!command.contains("getprop")) return 0 to ""
            return if (answers.isEmpty()) default else answers.removeAt(0)
        }

        override fun cacheVisible(): Boolean? = cache

        fun queueGetprop(rc: Int, out: String) { answers += rc to out }

        /** Команды, которые не являются чтением `getprop` (то есть изменения устройства). */
        fun scriptCommands(): List<String> = executed.filterNot { it.contains("getprop") }
    }
}
