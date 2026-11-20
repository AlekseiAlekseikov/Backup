package com.example.voiceapp3.tools

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** iter67c §7 — отсутствующий ключ не выдаётся за ошибку чтения; строка не повторяется по таймеру. */
class Iter67cPrefsTest {

    /** Только чтение: запись в этих тестах не нужна. `broken` — копия, чтение которой падает. */
    private class ReadPrefs(val map: Map<String, Any?> = emptyMap(), val broken: Boolean = false) : SharedPreferences {
        private fun chk() { if (broken) throw IllegalStateException("io") }
        override fun getAll(): MutableMap<String, *> { chk(); return LinkedHashMap(map) }
        override fun getString(key: String, defValue: String?): String? { chk(); return map[key] as String? ?: defValue }
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int { chk(); return map[key] as Int? ?: defValue }
        override fun getLong(key: String, defValue: Long): Long { chk(); return map[key] as Long? ?: defValue }
        override fun getFloat(key: String, defValue: Float): Float = defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean { chk(); return map[key] as Boolean? ?: defValue }
        override fun contains(key: String): Boolean { chk(); return map.containsKey(key) }
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Before
    fun setUp() = Prefs2.resetPrefNotesForTest()

    @Test
    fun `ключа нет в обоих хранилищах — absent, а не unreadable`() {
        val p = Prefs2.probeBooleanCore("keep_awake_never_sleep", ReadPrefs(), ReadPrefs(), default = true)
        assertEquals(Prefs2.PrefRead.Absent("true"), p.read)
        val notes = Prefs2.prefNotes(p)
        assertEquals("одна строка", 1, notes.size)
        val (lvl, line) = notes[0]
        assertEquals("INFO", lvl)
        assertTrue("строка PREFS_ABSENT: $line", line.startsWith("PREFS_ABSENT key=keep_awake_never_sleep using_default=true"))
        assertTrue("слово unreadable недопустимо: $line", !line.contains("unreadable"))
    }

    @Test
    fun `ключ только в cps — present, used=cps и PREF_SPLIT`() {
        val p = Prefs2.probeBooleanCore("master_remember_brightness",
            ReadPrefs(mapOf("master_remember_brightness" to true)), ReadPrefs(), default = false)
        assertEquals(Prefs2.PrefRead.Present("true", "cps"), p.read)
        val line = Prefs2.prefNotes(p).single().second
        assertEquals("PREF_SPLIT key=master_remember_brightness cps=true dps= used=cps", line)
    }

    @Test
    fun `файл недоступен — unreadable с err`() {
        val p = Prefs2.probeBooleanCore("window_driver_on_door", ReadPrefs(broken = true), ReadPrefs(), default = false)
        assertTrue("ожидался Unreadable: ${p.read}", p.read is Prefs2.PrefRead.Unreadable)
        val (lvl, line) = Prefs2.prefNotes(p).single()
        assertEquals("WARN", lvl)
        assertTrue("нет err=: $line", line.startsWith("PREFS_UNREADABLE key=window_driver_on_door err=cps:IllegalStateException"))
    }

    @Test
    fun `та же строка не повторяется на каждом проходе, а смена состояния печатается`() {
        val probe = { m: Map<String, Any?> -> Prefs2.probeBooleanCore("k", ReadPrefs(m), ReadPrefs(), false) }
        val first = (1..60).flatMap { Prefs2.prefNotes(probe(emptyMap())) }
        assertEquals("за 60 проходов ровно одна строка: $first", 1, first.size)
        val changed = Prefs2.prefNotes(probe(mapOf("k" to true)))
        assertEquals("смена состояния печатается: $changed", 1, changed.size)
        assertTrue(changed[0].second.startsWith("PREF_SPLIT key=k cps=true dps= used=cps"))
    }

    @Test
    fun `снимок тумблеров и getBoolean проведены через новое правило`() {
        val m = "app/src/main/java/com/example/voiceapp3/tools/"
        val tb = File(m + "ToolsBackend.kt").readText()
        assertTrue("снимок не печатает PREFS_UNKNOWN безусловно", !tb.contains("\"PREFS_UNKNOWN key=\${t.key}"))
        assertTrue("снимок зовёт noteBoolean", tb.contains("Prefs2.noteBoolean(appContext, t.pref, t.default, t.key)"))
        val gb = File(m + "Prefs2.kt").readText().substringAfter("fun getBoolean(").substringBefore("// ───────────")
        assertTrue("getBoolean больше не печатает reason=unreadable", !gb.contains("reason=unreadable"))
        assertTrue("getBoolean зовёт noteBoolean", gb.contains("noteBoolean(c, key, default)"))
    }
}
