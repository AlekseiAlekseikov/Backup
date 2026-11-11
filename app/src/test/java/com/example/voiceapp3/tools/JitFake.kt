package com.example.voiceapp3.tools

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Подставные реализации интерфейсов [JitRef] — для локальных тестов на JVM.
 *
 * Все фейки фиксируют ФАКТИЧЕСКИЕ действия: какие команды ушли, какие ключи записаны/удалены,
 * какие сетевые вызовы сделаны, в каком порядке, сколько раз.
 *
 * ВАЖНО: это НЕ Android-интеграционные тесты. На ГУ они не запускались, `WifiManager` и
 * `/proc/self/maps` ГУ ими не проверяются (для карты памяти есть отдельный тест на реальном файле
 * процесса JVM — см. `JitMapsTest`).
 */
object JitFake {

    private fun wire(snap: JitRef.WifiSnapshot) = "wifi=${if (snap.wifi) 1 else 0}:"

    // ─────────────────────────── префы ───────────────────────────

    /**
     * Два хранилища проекта (CPS/DPS): каждое можно «сломать» отдельно.
     *
     * `commitFails` — индексы, у которых `commit()` возвращает false И значение НЕ записывается
     * (обычный случай); `writeDespiteFalse` — записать, но вернуть false (поведение, при котором
     * «commit=false» не доказывает отсутствие значения); `throwOn` — `edit()` бросает исключение.
     */
    class Store(storeCount: Int = 2) : JitRef.JitStore {
        val maps: List<MutableMap<String, Any?>> = List(storeCount) { mutableMapOf() }
        val commitFails = mutableSetOf<Int>()
        val throwOn = mutableSetOf<Int>()
        var writeDespiteFalse = false

        /** Журнал операций: `snapshot:i`, `remove:key:i`. */
        val ops = mutableListOf<String>()
        val commits = mutableListOf<String>()

        fun writeCount(): Int = ops.size

        fun rawRestore(): String? = getString(JitRef.KEY_RESTORE)

        fun presentIn(store: Int, key: String): Boolean = maps[store].containsKey(key)

        override fun getString(key: String): String? =
            maps.firstNotNullOfOrNull { m -> if (m.containsKey(key)) m[key] as? String else null }

        override fun getBool(key: String): Boolean? =
            maps.firstNotNullOfOrNull { m -> if (m.containsKey(key)) m[key] as? Boolean else null }

        override fun contains(key: String): Boolean = maps.any { it.containsKey(key) }

        override fun putSnapshot(want: Boolean, snap: JitRef.WifiSnapshot): JitRef.WriteResult {
            if (maps.isNotEmpty() && throwOn.size == maps.size) throw IllegalStateException("all stores broken")
            var ok = 0
            val failed = mutableListOf<String>()
            for ((i, m) in maps.withIndex()) {
                if (i in throwOn) { failed += "s$i:exception"; continue }
                if (i in commitFails && !writeDespiteFalse) { failed += "s$i:commit_false"; continue }
                m[JitRef.KEY_PEDIDO] = want
                m[JitRef.KEY_AUTO] = want
                m[JitRef.KEY_RESTORE] = snap.wire()
                ops += "snapshot:$i"
                commits += wire(snap)
                if (i in commitFails) failed += "s$i:commit_false" else ok++
            }
            return JitRef.WriteResult(maps.size, ok, failed.joinToString(",").ifEmpty { "all_ok" })
        }

        override fun confirmSnapshot(expected: String): Int =
            maps.count { m -> m[JitRef.KEY_RESTORE] == expected }

        override fun remove(key: String): JitRef.WriteResult {
            var ok = 0
            val failed = mutableListOf<String>()
            for ((i, m) in maps.withIndex()) {
                if (i in throwOn) { failed += "s$i:exception"; continue }
                if (i in commitFails && !writeDespiteFalse) { failed += "s$i:commit_false"; continue }
                m.remove(key)
                ok++
                ops += "remove:$key:$i"
                if (i in commitFails) failed += "s$i:commit_false"
            }
            return JitRef.WriteResult(maps.size, ok, failed.joinToString(",").ifEmpty { "all_ok" })
        }

        /** Прямая установка значения в указанное хранилище — для подготовки состояния. */
        fun setIn(store: Int, key: String, value: Any?) {
            if (value == null) maps[store].remove(key) else maps[store][key] = value
        }

        /** Прямая установка значения в первое хранилище — для подготовки состояния в тесте. */
        fun set(key: String, value: Any?) = setIn(0, key, value)
    }

    // ─────────────────────────── клиентский Wi-Fi ───────────────────────────

    /**
     * Только клиентский Wi-Fi — точек доступа в интерфейсе больше нет (решение владельца).
     *
     * - `wifi` — наблюдаемое состояние; `wifiUnreadable` — чтение бросает исключение;
     * - `setWifiResult` — что вернул `setWifiEnabled` (`null` = «не удалось/неизвестно»);
     * - `setWifiThrows` — исключение из `setWifiEnabled`;
     * - `setWifiApplies` — менять ли `wifi` при успешном `setWifiEnabled(true)`;
     * - `address` — read-only признак адреса на `wlan0`.
     */
    class Net(
        var wifi: Boolean = true,
        var wifiUnreadable: Boolean = false,
        var setWifiResult: Boolean? = true,
        var setWifiThrows: Boolean = false,
        var setWifiApplies: Boolean = true,
        var address: Boolean? = true,
    ) : JitRef.JitNetState {

        val requests = mutableListOf<String>()
        var wifiReads = 0

        override fun wifiOn(): Boolean? {
            wifiReads++
            if (wifiUnreadable) throw IllegalStateException("wifi read failed")
            return wifi
        }

        override fun setWifi(on: Boolean): Boolean? {
            if (setWifiThrows) throw IllegalStateException("setWifiEnabled failed")
            requests += "wifi=$on"
            if (setWifiResult == true && on && setWifiApplies) wifi = true
            return setWifiResult
        }

        override fun wifiAddress(): Boolean? = address
    }

    // ─────────────────────────── планировщик ───────────────────────────

    /** Планировщик как `postDelayed`: ничего не выполняет сам, задачи складывает в очередь. */
    class Scheduler : JitRef.JitScheduler {
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()

        /** `true` — выполнять сразу при планировании (для тестов «как будто 15 с прошли»). */
        var runInline = false

        override fun postDelayed(delayMs: Long, task: () -> Unit) {
            scheduled += delayMs to task
            if (runInline) task()
        }

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
     * `runInline` — сколько команд «выполнятся и ответят»; остальные дают `137 / ERR_CHANNEL_LOST`
     * (ответа нет). `maps` — содержимое `/proc/self/maps` (`null` = прочитать нельзя).
     */
    class QueueShell(
        var stand: JitIsoStand? = null,
        var getprop: String = "true",
        var getpropRc: Int = 0,
        var maps: String? = null,
    ) : JitRef.JitShell {
        val pending = mutableListOf<String>()
        val executed = mutableListOf<Triple<String, Int, String>>()
        var runInline = 0

        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            pending += command
            if (runInline > 0 && command.contains("zygote")) {
                runInline--
                val (rc, out) = stand?.exec(command, getprop) ?: Pair(0, "")
                executed += Triple(command, rc, out)
                pending.remove(command)
                return rc to out
            }
            return 137 to "ERR_CHANNEL_LOST"
        }

        override fun mapsExcerpt(): String? = maps

        fun getpropAnswers(): Pair<Int, String> = getpropRc to getprop
    }

    /**
     * Shell, который отвечает на `getprop` значениями из очереди, а на скрипт — «ок».
     * Нужен для проверки сверки без выполнения цепочки.
     */
    class AnswerShell(
        private val answers: MutableList<Pair<Int, String>> = mutableListOf(),
        var maps: String? = null,
    ) : JitRef.JitShell {
        val executed = mutableListOf<String>()
        var default: Pair<Int, String> = 0 to "true"

        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            executed += command
            if (!command.contains("getprop")) return 0 to ""
            return if (answers.isEmpty()) default else answers.removeAt(0)
        }

        override fun mapsExcerpt(): String? = maps

        fun queueGetprop(rc: Int, out: String) { answers += rc to out }

        /** Команды, которые не являются чтением `getprop` (то есть изменения устройства). */
        fun scriptCommands(): List<String> = executed.filterNot { it.contains("getprop") }
    }

    /**
     * Shell, который блокируется внутри `exec`, пока тест не отпустит: нужен, чтобы проверить
     * гонку «сверка во время клика».
     */
    class BlockingShell(var maps: String? = null) : JitRef.JitShell {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger(0)

        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            calls.incrementAndGet()
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            return 0 to "ok"
        }

        override fun mapsExcerpt(): String? = maps
    }
}
