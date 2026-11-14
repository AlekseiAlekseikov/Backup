package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter53 §7 — ЗВУК ЗАМКА: сверка по КАЖДОЙ цели и миграция старой записи.
 *
 * Дефекты (аудит §7):
 *  1. `reconcileVerdict` возвращал `STOCK_RESTORED` при наличии ХОТЯ БЫ ОДНОГО маркера успеха,
 *     не сверяя покрытие всех ожидаемых целей, и проверял `rc` ПОСЛЕ поиска успеха → частичный
 *     ответ давал DONE;
 *  2. `UMOUNT_DETACHED` (lazy) считался доказанным штатным состоянием;
 *  3. старый `locksound_op.txt` в production-пути не читался вовсе, а «файл есть, но не
 *     разобран» не отличался от «файла нет».
 */
class Iter53SoundTest {

    private fun op(id: String, targets: List<String>, state: LockOp.State = LockOp.State.PLANNED) =
        LockOp.BindOp(id, 7L, true, "/stage/x", targets, state, "")

    // ───────────── §7.2: покрытие всех целей ─────────────

    @Test
    fun oneSuccessfulTargetIsNotProofForAllTargets() {
        val out = "STOCK_OK:/t1\n"
        val d = LockOp.reconcileDetail(0, out, listOf("/t1", "/t2"))
        assertEquals("частичный ответ — НЕ восстановлено", LockOp.Reconcile.UNPROVEN, d.verdict)
        assertEquals(listOf("/t2"), d.missing)
        assertEquals(listOf("/t1"), d.proven)
    }

    @Test
    fun allTargetsProvenGivesStockRestored() {
        // Реальный вывод restore-скрипта: шаг снятия + ИТОГ по каждой цели.
        val out = "STOCK_OK:/t1\nUMOUNTED_OK:/t2\nSTOCK_OK:/t2\n"
        val d = LockOp.reconcileDetail(0, out, listOf("/t1", "/t2"))
        assertEquals(LockOp.Reconcile.STOCK_RESTORED, d.verdict)
        assertTrue(d.missing.isEmpty())
    }

    @Test
    fun nonzeroRcWinsOverSuccessMarkers() {
        // iter52: rc проверялся ПОСЛЕ маркеров → «umount не удался, но успех напечатан» давал DONE.
        val out = "STOCK_OK:/t1\nUMOUNT_FAILED:/t2\nSTOCK_OK:/t2\n"
        val d = LockOp.reconcileDetail(1, out, listOf("/t1", "/t2"))
        assertEquals("rc≠0 не может означать «снято»", LockOp.Reconcile.UNPROVEN, d.verdict)
    }

    @Test
    fun lazyDetachIsNotProvenStockState() {
        val d = LockOp.reconcileDetail(0, "UMOUNT_DETACHED:/t1\n", listOf("/t1"))
        assertEquals("lazy detach ≠ доказанное штатное состояние", LockOp.Reconcile.UNPROVEN, d.verdict)
        assertEquals(listOf("/t1"), d.detached)
        assertFalse(d.verdict == LockOp.Reconcile.STOCK_RESTORED)
    }

    @Test
    fun extraTargetAndDuplicatesAreRejected() {
        val extra = LockOp.reconcileDetail(0, "STOCK_OK:/t1\nSTOCK_OK:/t9\n", listOf("/t1"))
        assertEquals(LockOp.Reconcile.UNPROVEN, extra.verdict)
        assertEquals(listOf("/t9"), extra.extra)
        val dup = LockOp.reconcileDetail(0, "STOCK_OK:/t1\nSTOCK_OK:/t1\n", listOf("/t1"))
        assertEquals("два маркера на одну цель — не доказательство", LockOp.Reconcile.UNPROVEN, dup.verdict)
        assertEquals(listOf("/t1"), dup.duplicates)
    }

    @Test
    fun emptyExpectedSetNeverProvesAnything() {
        assertEquals("доказать нечего — значит НЕ доказано",
            LockOp.Reconcile.UNPROVEN, LockOp.reconcileVerdict(0, "STOCK_OK:/t1"))
    }

    @Test
    fun foreignAndUnprovenMarkersKeepPrecedence() {
        assertEquals(LockOp.Reconcile.FOREIGN_LEFT,
            LockOp.reconcileDetail(0, "FOREIGN_MOUNT:/t1 mid=1\nSTOCK_OK:/t2\n", listOf("/t1", "/t2")).verdict)
        assertEquals(LockOp.Reconcile.UNPROVEN,
            LockOp.reconcileDetail(0, "RESTORE_BLOCKED reason=namespace_not_proven\n", listOf("/t1")).verdict)
    }

    // ───────────── §7.1: миграция старой записи ─────────────

    @Test
    fun legacyValidRecordIsAdoptedAsUnresolved() {
        val raw = op("old-1", listOf("/a", "/b"), LockOp.State.PLANNED).encode()
        val read = LockOp.parseLegacyOp(raw)
        assertTrue("старая запись ДОЛЖНА читаться: $read", read is LockOp.LegacyRead.Parsed)
        val parsed = (read as LockOp.LegacyRead.Parsed).op
        assertEquals("old-1", parsed.id)
        assertEquals(listOf("/a", "/b"), parsed.targets)
        assertEquals("незавершённая запись остаётся незавершённой",
            LockOp.State.UNDEFINED, parsed.state)
    }

    @Test
    fun legacyDoneRecordKeepsItsProvenState() {
        val raw = op("old-2", listOf("/a"), LockOp.State.DONE).encode()
        val parsed = (LockOp.parseLegacyOp(raw) as LockOp.LegacyRead.Parsed).op
        assertEquals("доказанное DONE не переписываем в неизвестность", LockOp.State.DONE, parsed.state)
    }

    @Test
    fun legacyJournalLineIsAccepted() {
        val raw = "1700000000000|" + op("old-3", listOf("/a")).encode()
        assertTrue(LockOp.parseLegacyOp(raw) is LockOp.LegacyRead.Parsed)
    }

    @Test
    fun absentAndCorruptLegacyAreDifferentFacts() {
        assertEquals(LockOp.LegacyRead.Absent, LockOp.parseLegacyOp(null))
        assertEquals(LockOp.LegacyRead.Absent, LockOp.parseLegacyOp("   "))
        val bad = LockOp.parseLegacyOp("мусор без полей")
        assertTrue("нечитаемый файл — НЕ «операций не было»", bad is LockOp.LegacyRead.Unknown)
        // и он остаётся в журнале как неизвестная операция, требующая разбора
        val j = LockOp.parseJournal("1|" + op("x", emptyList(), LockOp.State.UNDEFINED).copy(targets = emptyList()).encode())
        assertEquals("операция без целей остаётся unresolved (ручной разбор)", 1, j.unresolved().size)
    }

    @Test
    fun migrationRenamesOnlyAfterConfirmedAppend() {
        val read = LockOp.parseLegacyOp(op("old-4", listOf("/a")).encode())
        assertEquals("append не подтверждён — оригинал НЕ трогаем",
            LockOp.MigrationAction.KeepLegacy, LockOp.migrationStep(read, appended = false))
        assertEquals("append подтверждён — можно переименовать",
            LockOp.MigrationAction.RenameLegacy, LockOp.migrationStep(read, appended = true))
        assertEquals("файла нет — нечего переименовывать",
            LockOp.MigrationAction.Nothing, LockOp.migrationStep(LockOp.LegacyRead.Absent, appended = true))
    }

    @Test
    fun repeatedMigrationIsIdempotentInJournal() {
        // Сбой между append и rename: та же запись переносится второй раз.
        val op = op("old-5", listOf("/a"))
        val once = LockOp.journalLine(op, 1L)
        val twice = once + "\n" + LockOp.journalLine(op, 2L)
        val j = LockOp.parseJournal(twice)
        assertEquals("дубля операции нет: последняя строка по id побеждает", 1, j.ops.size)
        assertEquals("/a", j.ops["old-5"]!!.targets.single())
    }
}
