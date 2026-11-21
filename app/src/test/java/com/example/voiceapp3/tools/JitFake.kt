package com.example.voiceapp3.tools

import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicInteger

/**
 * Подставные реализации для локальных JVM-тестов.
 *
 * ВАЖНО: это НЕ Android-интеграционные тесты. На ГУ ничего не запускалось; `WifiManager` и
 * `/proc/self/maps` ГУ этими тестами не проверяются.
 *
 * Отдельно есть [Prefs] — реалистичная модель `SharedPreferences` (память отдельно от «диска»,
 * «новый процесс» читает только «диск»). iter65: фейки старого ядра JIT (Store/Net/Scheduler/
 * shell-и claim-терминала) удалены вместе с ядром.
 */
object JitFake {

    /**
     * §5.1 iter41 — подставной приёмник диагностических строк.
     *
     * Уровень проверки: **JVM-модель приёмника**. `outcome`/`loc` управляются тестом, поэтому можно
     * проверить, как сводка блока реагирует на «записано», «пропущено бюджетом», «не записалось» и
     * на смену файла посреди блока. Реальный `AppLog`/`LogFiles` на ГУ этим не проверяется.
     */
    internal class TestSink(
        val lines: MutableList<String>,
        var outcome: (String) -> LogBudget.WriteOutcome = { LogBudget.WriteOutcome.WRITTEN },
        var loc: String = "/test/voiceapp3-01.01.2026.log"
    ) : LogBudget.Sink {
        val independent = mutableListOf<String>()
        val locations = LinkedHashSet<String>()

        override fun write(line: String): LogBudget.LogWrite {
            locations += loc
            val o = outcome(line)
            if (o == LogBudget.WriteOutcome.WRITTEN) lines += line
            return LogBudget.LogWrite(o, if (o == LogBudget.WriteOutcome.WRITTEN) loc else "")
        }

        override fun location(): String = loc

        override fun independent(line: String) {
            independent += line
        }
    }


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
        /** §2.4 iter40: чтение ИМЕННО этих ключей бросает (ошибка значения, не «ключа нет»). */
        var throwOnKeys: Set<String> = emptySet(),
    ) : SharedPreferences {

        /** Текущая память процесса: стартует с того, что лежит на «диске». */
        private val mem = LinkedHashMap<String, Any?>().apply { putAll(disk) }

        val writes = AtomicInteger(0)
        val commits = mutableListOf<Map<String, Any?>>()

        /** «Новый процесс»: память = диск. */
        fun newProcess() = Prefs(disk, commitOk, throwOnEdit, partialKeys, throwOnRead, throwOnKeys)

        fun diskKeys(): Set<String> = disk.keys.toSet()
        fun memKeys(): Set<String> = mem.keys.toSet()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(mem)

        override fun getString(key: String, defValue: String?): String? {
            if (throwOnRead || key in throwOnKeys) throw IllegalStateException("read failed: $key")
            val v = mem[key] ?: return defValue
            // Как в Android: неожиданный тип — ClassCastException, а не «значение по умолчанию».
            return v as String
        }

        override fun getStringSet(key: String, defValues: MutableSet<String>?) =
            @Suppress("UNCHECKED_CAST")
            (mem[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String, defValue: Int): Int = mem[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = mem[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = mem[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            if (throwOnRead || key in throwOnKeys) throw IllegalStateException("read failed: $key")
            val v = mem[key] ?: return defValue
            // Как в Android: String вместо Boolean — ClassCastException.
            return v as Boolean
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
}
