package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter67 §B — яркость: не писать, когда писать нечего, и назвать настоящую причину.
 *
 * Числа взяты с живой ГУ: узел `/sys/class/leds/lcd-backlight/brightness` и `Settings.System`
 * показывают 67, а приложение печатало `settings=true sysfs=false` и писало три значения каждые
 * 2–3 секунды.
 */
class Iter67BrightSyncTest {

    /** Лог и счётчики записи — то, что в бою уходит в `AppLog`/shell. */
    private class FakeWriters(
        var settings: Int?,
        var sysfs: Int?,
        private val sysfsReadable: Boolean = true
    ) : BrightWriters {
        val calls = ArrayList<String>()
        val lines = ArrayList<String>()
        val total: Int get() = calls.size
        override fun writeSettings(value: Int) { calls += "settings"; settings = value }
        override fun writeSysfs(value: Int) { calls += "sysfs"; if (sysfsReadable) sysfs = value }
        override fun log(line: String) { lines += line }
    }

    @Test
    fun `в синхронизме — ноль записей и ноль строк`() {
        val w = FakeWriters(settings = 67, sysfs = 67)
        val out = BrightRestorer().runOnce(want = 67, settings = w.settings, sysfs = w.sysfs, writers = w)
        assertEquals(SyncState.IN_SYNC, out.state)
        assertEquals("записей быть не должно", 0, w.total)
        assertEquals("строк BRIGHT_SYNC быть не должно: ${w.lines}", 0, w.lines.size)
    }

    @Test
    fun `sysfs не читается — не пишем ничего`() {
        // Живой случай: `settings=67`, узел не читается.
        val w = FakeWriters(settings = 67, sysfs = null)
        val out = BrightRestorer().runOnce(want = 67, settings = w.settings, sysfs = w.sysfs, writers = w)
        assertEquals(SyncState.SYSFS_UNREADABLE, out.state)
        assertEquals("fail-before: 3 записи (settings+sysfs+setprop)", 0, w.total)
        assertTrue("нет строки о причине: ${w.lines}", w.lines.isEmpty())
    }

    @Test
    fun `расходится только sysfs — пишем только sysfs`() {
        val w = FakeWriters(settings = 67, sysfs = 50)
        val out = BrightRestorer().runOnce(want = 67, settings = 67, sysfs = 50, writers = w)
        assertEquals(SyncState.SYSFS_DIFF, out.state)
        assertEquals("fail-before: settings+sysfs+prop", listOf("sysfs"), w.calls)
    }

    @Test
    fun `расходится только settings — пишем только settings`() {
        val w = FakeWriters(settings = 50, sysfs = 67)
        BrightRestorer().runOnce(want = 67, settings = 50, sysfs = 67, writers = w)
        assertEquals(listOf("settings"), w.calls)
    }

    @Test
    fun `расходятся оба — пишем оба, setprop не пишем никогда`() {
        val w = FakeWriters(settings = 50, sysfs = 40)
        val out = BrightRestorer().runOnce(want = 67, settings = 50, sysfs = 40, writers = w)
        assertEquals(SyncState.BOTH_DIFF, out.state)
        assertEquals(listOf("settings", "sysfs"), w.calls)
        assertFalse("setprop в плане яркости остался", w.calls.contains("prop"))
    }

    @Test
    fun `BRIGHT_SYNC печатает состояние строкой`() {
        val w = FakeWriters(settings = 67, sysfs = 50)
        BrightRestorer().runOnce(want = 67, settings = 67, sysfs = 50, writers = w)
        assertTrue("нет строки состояния: ${w.lines}",
            w.lines.any { it.startsWith("BRIGHT_SYNC state=sysfs_diff") })
    }

    @Test
    fun `три подряд SYSFS_UNREADABLE — одна строка и запрет записи в узел`() {
        val r = BrightRestorer(limit = 3)
        val w = FakeWriters(settings = 67, sysfs = null)
        repeat(3) { r.runOnce(want = 67, settings = 67, sysfs = null, writers = w, path = "/sys/class/leds/lcd-backlight/brightness", errno = "EACCES") }
        val unreadable = w.lines.filter { it.startsWith("BRIGHT_SYSFS_UNREADABLE") }
        assertEquals("строка должна быть одна: ${w.lines}", 1, unreadable.size)
        assertTrue(unreadable[0].contains("action=no_writes"))
        assertTrue(r.sysfsWriteForbidden)
        // После запрета узел опять читается, но расходится — писать в него всё равно нельзя.
        val w2 = FakeWriters(settings = 67, sysfs = 50)
        val out = r.runOnce(want = 67, settings = 67, sysfs = 50, writers = w2)
        assertFalse("запись в узел после вето", out.applied.writeSysfs)
        assertEquals("после вето пишем только settings", 0, w2.calls.size)
    }

    @Test
    fun `арифметика состояний на значениях с машины`() {
        assertEquals(SyncState.SYSFS_UNREADABLE, syncState(67, null, 67))
        assertEquals(SyncState.BOTH_DIFF, syncState(50, null, 67))
        assertEquals(SyncState.BOTH_DIFF, syncState(50, 40, 67))
        assertEquals(SyncState.SETTINGS_DIFF, syncState(50, 67, 67))
        assertEquals(SyncState.SYSFS_DIFF, syncState(67, 40, 67))
        assertEquals(SyncState.IN_SYNC, syncState(67, 67, 67))
        assertEquals(SyncState.SETTINGS_DIFF, syncState(null, 67, 67))
    }

    @Test
    fun `restoreIfEnabled не запускает setprop`() {
        // fail-before: `writeSystemInt(...); writeSysfs(lvl); writeProp(lvl)` — три записи на тик.
        val src = java.io.File("app/src/main/java/com/example/voiceapp3/tools/ScreenBrightness.kt").readText()
        val body = src.substringAfter("fun restoreIfEnabled(ctx: Context)").substringBefore("private fun sysfsErrno")
        assertFalse("в теле восстановления остался writeProp", body.contains("writeProp"))
        assertTrue("нет разбора состояния", src.contains("BrightRestorer"))
        assertTrue("разбор состояния вынесен в BrightSync", src.contains("BrightRestorer()"))
    }

    @Test
    fun `settings читается ContentResolver-ом, а не settings get`() {
        val src = java.io.File("app/src/main/java/com/example/voiceapp3/tools/ScreenBrightness.kt").readText()
        val body = src.substringAfter("fun restoreIfEnabled(ctx: Context)").substringBefore("private fun sysfsErrno")
        // fail-before: в задании зафиксировано, что доказательством брали `settings get` под root.
        assertFalse("settings get как доказательство", body.contains("settings get"))
        assertTrue("нет чтения через ContentResolver", body.contains("readSystem(ctx, SETTINGS_KEY)"))
    }
}
