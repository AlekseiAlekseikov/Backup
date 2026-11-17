package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter63 §P0-1 — ЖУРНАЛ: ДУБЛИ УБИРАЮТСЯ ПО ИДЕНТИЧНОСТИ СОБЫТИЯ, А НЕ ПО ТЕКСТУ.
 *
 * История дефекта:
 *  • iter55 §8 переносил граничную запись дважды — владелец видел ДУБЛЬ одного события;
 *  • iter62 §B2 убрал дубль, выбрасывая любую запись `.new` с совпавшим ТЕКСТОМ — то есть стал
 *    ТЕРЯТЬ реальные события (аудит iter62: «исправление дубля стало потерей записи»).
 *
 * Здесь проверяется контракт iter63: каждая запись несёт `rid` события (суффикс ` [r=…]`), и
 * слияние `.new` отбрасывает ровно ту запись, чьё событие уже есть в активном файле.
 *
 * L1 — главный тест: две НАСТОЯЩИЕ одинаковые записи (разные `rid`) сохраняются обе. На коде iter62
 * он падает: там сравнивался текст, и одна из записей исчезала.
 */
class Iter63LogIdentityTest {

    private val dir: File = File.createTempFile("log63", "").let { it.delete(); it.mkdirs(); it }

    private fun lines(f: File): List<String> = if (f.isFile) f.readLines().filter { it.isNotEmpty() } else emptyList()

    private fun bodies(f: File): List<String> = lines(f).map { LogRecordId.parse(it).body }

    // ───────────── L1: две настоящие одинаковые записи ─────────────

    @Test
    fun l1_twoRealIdenticalEventsAreBothKept() {
        val name = "app.log"
        val a1 = FileLogCore.stamp("TTL_TRY action=skip")
        val a2 = FileLogCore.stamp("TTL_TRY action=skip")   // ТО ЖЕ событие? нет — новый вызов = новое
        assertNotEquals("два разных события получили одинаковый rid", LogRecordId.parse(a1).rid,
            LogRecordId.parse(a2).rid)

        // Активный файл содержит первое событие.
        File(dir, name).writeText(a1)
        // `.new` содержит ВТОРОЕ событие с тем же текстом (как при падении после следующей записи).
        File(dir, "$name.new").writeText(a2)

        val note = FileLogCore.recoverPendingNew(dir, name)
        val result = bodies(File(dir, name))
        assertEquals("обе записи сохранены (потеря события — дефект iter62): $note / $result",
            listOf("TTL_TRY action=skip", "TTL_TRY action=skip"), result)
        assertEquals("перенесена ровно одна новая запись: $note", 1,
            Regex("new_merged=(\\d+)").find(note)?.groupValues?.get(1)?.toInt() ?: -1)
    }

    // ───────────── L2: одно событие, разорванное падением ─────────────

    @Test
    fun l2_oneEventInterruptedBetweenNewAndMergeAppearsOnce() {
        val name = "voiceapp3.log"
        val one = FileLogCore.stamp("LOCKSOUND_RESTORE_RESULT result=ok")
        File(dir, name).writeText(one)
        // То же событие (тот же rid, другой `try`) осталось в `.new` — падение между записью в
        // `.new` и её переносом.
        File(dir, "$name.new").writeText(FileLogCore.stamp(one))

        val note = FileLogCore.recoverPendingNew(dir, name)
        assertEquals("событие ровно один раз: $note", 1, bodies(File(dir, name)).size)
        assertTrue("факт пропуска назван: $note", note.contains("skipped_as_duplicate=1"))
    }

    // ───────────── L3: ротация — целые строки, без дублей, по порядку ─────────────

    @Test
    fun l3_rotationKeepsWholeLinesWithoutDuplicates() {
        val name = "rotate.log"
        for (i in 1..80) FileLogCore.appendStamped(dir, name, "M%03d payload_%s".format(i, "x".repeat(50)), maxBytes = 900L)
        val kept = listOf(File(dir, name), File(dir, "$name.1"), File(dir, "$name.new"))
            .flatMap { lines(it) }
        assertTrue("журнал писался", kept.isNotEmpty())
        kept.forEach { line ->
            val p = LogRecordId.parse(line)
            assertTrue("строка разорвана: <$line>", Regex("^M\\d{3} payload_x{50}$").matches(p.body))
            assertTrue("нет идентификатора события: <$line>", line.endsWith("]") && line.contains(" [r="))
        }
        val ids = kept.map { LogRecordId.parse(it).rid }
        assertEquals("дубли идентификаторов событий: ${ids.size - ids.toSet().size}", ids.size, ids.toSet().size)
        lines(File(dir, name)).zipWithNext().forEach { (x, y) ->
            assertTrue("порядок нарушен: $x / $y", LogRecordId.parse(x).rid < LogRecordId.parse(y).rid)
        }
    }

    // ───────────── L4: отказ fsync → запись остаётся в `.new` ─────────────

    @Test
    fun l4_eventSurvivesWhenTheActiveFileIsMissing() {
        val name = "crash.log"
        val ev = FileLogCore.stamp("TOGGLE_HOLD key=ttl65")
        File(dir, "$name.new").writeText(ev)
        // Активного файла нет (падение между move(active→.1) и повышением `.new`): запись обязана
        // остаться на месте, а не исчезнуть.
        val note = FileLogCore.recoverPendingNew(dir, name)
        assertTrue("причина названа: $note", note.isNotEmpty())
        assertTrue("событие не потеряно", File(dir, "$name.new").readText().contains("TOGGLE_HOLD"))
        assertEquals("идентификатор сохранён",
            LogRecordId.parse(ev.trimEnd('\n')).rid,
            LogRecordId.parse(File(dir, "$name.new").readText().trimEnd('\n')).rid)
    }

    // ───────────── старые строки без идентификатора тоже не дублируются ─────────────

    @Test
    fun legacyJournalLosesNothing() {
        val name = "legacy.log"
        // Журнал ПРЕЖНЕЙ версии: суффиксов идентификатора нет.
        File(dir, name).writeText("old line one\nold line two\n")
        File(dir, "$name.new").writeText("old line two\nold line three\n")
        FileLogCore.recoverPendingNew(dir, name)
        val out = lines(File(dir, name))
        // Идентичности события в таком журнале НЕТ, а выводить её из текста — это ровно ошибка
        // iter62 (потеря настоящих записей). Поэтому для журнала прежней версии действует правило
        // iter55: переносится всё, потерь нет; возможный дубль — названная цена.
        assertEquals("ни одна запись прежнего журнала не потеряна: $out", 4, out.size)
        assertTrue(out.any { it.contains("old line three") })
        assertTrue("дубль назван как цена отсутствия идентификатора", out.count { it == "old line two" } == 2)
    }
}
