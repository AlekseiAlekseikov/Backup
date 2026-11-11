package com.example.voiceapp3.tools

import android.content.SharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Подставные реализации для локальных JVM-тестов.
 *
 * ВАЖНО: это НЕ Android-интеграционные тесты. На ГУ ничего не запускалось; `WifiManager` и
 * `/proc/self/maps` ГУ этими тестами не проверяются.
 *
 * Отдельно есть [Prefs] — реалистичная модель `SharedPreferences` (память отдельно от «диска»,
 * «новый процесс» читает только «диск»), чтобы проверять настоящий `JitNet.AndroidStore`, а не
 * только удобный фейк.
 */
object JitFake {

    // ─────────────────────────── реалистичные SharedPreferences ───────────────────────────

    /**
     * Модель Android `SharedPreferences`:
     *  • `commit()` сразу меняет ПАМЯТЬ (как в Android), а на «диск» пишет только при успехе;
     *  • `commitOk=false` — значение видно в памяти, но после «смерти процесса» пропадает;
     *  • `throwOnEdit` — `edit()` бросает;
     *  • `partialKeys` — ключи, которые не записываются (проверка согласованности трёх ключей);
     *  • [newProcess] создаёт объект, читающий только «диск» — как после перезапуска.
     */
    class Prefs(
        private val disk: MutableMap<String, Any?>,
        var commitOk: Boolean = true,
        var throwOnEdit: Boolean = false,
        var partialKeys: Set<String> = emptySet(),
        var throwOnRead: Boolean = false,
    ) : SharedPreferences {

        /** Текущая память процесса: стартует с того, что лежит на «диске». */
        private val mem = LinkedHashMap<String, Any?>().apply { putAll(disk) }

        val writes = AtomicInteger(0)
        val commits = mutableListOf<Map<String, Any?>>()

        /** «Новый процесс»: память = диск. */
        fun newProcess() = Prefs(disk, commitOk, throwOnEdit, partialKeys, throwOnRead)

        fun diskKeys(): Set<String> = disk.keys.toSet()
        fun memKeys(): Set<String> = mem.keys.toSet()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(mem)

        override fun getString(key: String, defValue: String?): String? {
            if (throwOnRead) throw IllegalStateException("read failed")
            return mem[key] as? String ?: defValue
        }

        override fun getStringSet(key: String, defValues: MutableSet<String>?) =
            @Suppress("UNCHECKED_CAST")
            (mem[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String, defValue: Int): Int = mem[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = mem[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = mem[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            if (throwOnRead) throw IllegalStateException("read failed")
            return mem[key] as? Boolean ?: defValue
        }

        override fun contains(key: String): Boolean = mem.containsKey(key)

        override fun edit(): SharedPreferences.Editor {
            if (throwOnEdit) throw IllegalStateException("edit failed")
            return Editor()
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        inner class Editor : SharedPreferences.Editor {
            private val puts = LinkedHashMap<String, Any?>()
            private val removes = LinkedHashSet<String>()

            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { removes += key }
            override fun clear() = apply { removes += mem.keys.toList() }

            override fun commit(): Boolean {
                writes.incrementAndGet()
                // Память меняется всегда (как в Android): commit=false НЕ значит «значения нет».
                // `partialKeys` моделирует ключ, который не записался вовсе (ни память, ни диск).
                val effective = LinkedHashMap<String, Any?>()
                puts.forEach { (k, v) -> if (k !in partialKeys) effective[k] = v }
                effective.forEach { (k, v) -> if (v == null) mem.remove(k) else mem[k] = v }
                removes.forEach { mem.remove(it) }
                commits += effective
                if (commitOk) {
                    effective.forEach { (k, v) -> if (v == null) disk.remove(k) else disk[k] = v }
                    removes.forEach { disk.remove(it) }
                }
                return commitOk
            }

            override fun apply() { commit() }
        }
    }

    // ─────────────────────────── префы (фейк) ───────────────────────────

    /**
     * Два хранилища проекта (CPS/DPS): каждое можно «сломать» отдельно.
     *
     * `commitFails` — индексы, у которых `commit()` возвращает false и значение НЕ пишется;
     * `writeDespiteFalse` — записать, но вернуть false (значение видно в памяти);
     * `throwOn` — `edit()` бросает; `skipKey` — ключ, который «не записался» (частичная запись).
     */
    class Store(storeCount: Int = 2) : JitRef.JitStore {
        val maps: List<MutableMap<String, Any?>> = List(storeCount) { mutableMapOf() }
        val commitFails = mutableSetOf<Int>()
        val throwOn = mutableSetOf<Int>()
        var writeDespiteFalse = false
        var skipKey: String? = null

        /** Порядок чтения (по умолчанию — как CPS→DPS). */
        var readOrder: List<Int> = maps.indices.toList()

        val ops = mutableListOf<String>()

        fun writeCount(): Int = ops.size

        fun rawRestore(): String? = getString(JitRef.KEY_RESTORE)

        fun presentIn(store: Int, key: String): Boolean = maps[store].containsKey(key)

        fun name(i: Int) = "s$i"

        override fun getString(key: String): String? =
            readOrder.firstNotNullOfOrNull { i -> maps[i][key] as? String }

        override fun getBool(key: String): Boolean? =
            readOrder.firstNotNullOfOrNull { i -> maps[i][key] as? Boolean }

        override fun contains(key: String): Boolean = maps.any { it.containsKey(key) }

        override fun putSnapshot(want: Boolean, snap: JitRef.WifiSnapshot): JitRef.SnapshotWrite {
            val reports = mutableListOf<JitRef.StoreReport>()
            for ((i, m) in maps.withIndex()) {
                if (i in throwOn) {
                    reports += JitRef.StoreReport(name(i), "exception:IllegalStateException", false)
                    continue
                }
                if (i in commitFails && !writeDespiteFalse) {
                    reports += JitRef.StoreReport(name(i), "false", false)
                    continue
                }
                m[JitRef.KEY_PEDIDO] = want
                m[JitRef.KEY_AUTO] = want
                m[JitRef.KEY_RESTORE] = snap.wire()
                skipKey?.let { m.remove(it) }
                ops += "snapshot:$i"
                val readback = m[JitRef.KEY_PEDIDO] == want && m[JitRef.KEY_AUTO] == want &&
                    m[JitRef.KEY_RESTORE] == snap.wire()
                val commit = if (i in commitFails) "false" else "ok"
                reports += JitRef.StoreReport(name(i), commit, readback)
            }
            return JitRef.SnapshotWrite(want, snap, reports)
        }

        override fun readRestore(): JitRef.SnapshotRead {
            val conflicts = mutableListOf<String>()
            for (key in JitRef.SNAPSHOT_KEYS) {
                val per = maps.mapIndexedNotNull { i, m ->
                    if (!m.containsKey(key)) null else name(i) to m[key].toString()
                }
                if (per.size > 1 && per.map { it.second }.distinct().size > 1) {
                    conflicts += "$key(${per.joinToString("/") { "${it.first}=${it.second}" }})"
                }
            }
            val value = readOrder.firstNotNullOfOrNull { i -> maps[i][JitRef.KEY_RESTORE] as? String }
            return JitRef.SnapshotRead(value, conflicts)
        }

        override fun remove(key: String, onlyStores: Set<String>?): JitRef.WriteResult {
            var ok = 0
            var attempted = 0
            val failed = mutableListOf<String>()
            for ((i, m) in maps.withIndex()) {
                if (onlyStores != null && name(i) !in onlyStores) continue
                attempted++
                if (i in throwOn) { failed += "${name(i)}:exception"; continue }
                if (i in commitFails && !writeDespiteFalse) { failed += "${name(i)}:commit_false"; continue }
                m.remove(key)
                ok++
                ops += "remove:$key:$i"
            }
            return JitRef.WriteResult(attempted, ok, failed.joinToString(",").ifEmpty { "all_ok" })
        }

        fun setIn(store: Int, key: String, value: Any?) {
            if (value == null) maps[store].remove(key) else maps[store][key] = value
        }

        fun set(key: String, value: Any?) = setIn(0, key, value)
    }

    // ─────────────────────────── клиентский Wi-Fi ───────────────────────────

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

    class Scheduler : JitRef.JitScheduler {
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        var runInline = false

        /** Бросать исключение при планировании (проверка честного `scheduler_failed`). */
        var failWith: Throwable? = null

        override fun postDelayed(delayMs: Long, task: () -> Unit) {
            failWith?.let { throw it }
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
            return 137 to "ERR_ADB_TIMEOUT"
        }

        override fun mapsExcerpt(): String? = maps
    }

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

        fun scriptCommands(): List<String> = executed.filterNot { it.contains("getprop") }
    }

    /** Shell, который блокируется внутри `exec`, пока тест не отпустит: гонки и параллельность. */
    class BlockingShell(var maps: String? = null) : JitRef.JitShell {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger(0)
        var result: Pair<Int, String> = 0 to "ok"

        /** Действие, выполняемое, пока «команда в транспорте» (для проверки гонок). */
        var whileBlocked: (() -> Unit)? = null

        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            calls.incrementAndGet()
            entered.countDown()
            whileBlocked?.invoke()
            release.await(5, TimeUnit.SECONDS)
            return result
        }

        override fun mapsExcerpt(): String? = maps
    }
}
