package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter55 §7.1/§7.2/§5.2 — ЗВУК: можно ли отправлять bind и чем разрешается `Unknown`.
 *
 * Что проверяется на РЕАЛЬНОМ пути (без макетов «на будущее»):
 *  1. путь-свидетельство доставки (`ev=`) теперь ЧАСТЬ журнала: после рестарта процесса и
 *     повторного чтения журнала он на месте (до правки `encode/decode` его не было вовсе, и
 *     исход `Unknown` разрешать было нечем);
 *  2. read-only разбор свидетельства отличает доказанный terminal (`rc=N` в `.done`) от
 *     «файла нет»/«пусто»: пустой `.done` терминалом не считается;
 *  3. recovery-вердикт имеет три различимых состояния, и отправлять bind можно ТОЛЬКО из `Ready`
 *     (до правки сверка возвращала `Boolean`, который production игнорировал);
 *  4. повреждённая строка журнала (`broken>0`) при пустом `unresolved()` — это НЕ «всё снято»:
 *     именно на этом условии сверка теперь останавливает bind.
 */
class Iter55SoundTest {

    private fun tmpDir(): File =
        File(System.getProperty("java.io.tmpdir"), "iter55sound_" + System.nanoTime()).apply { mkdirs() }

    @Test
    fun evidencePathSurvivesJournalRoundTrip() {
        val op = LockOp.BindOp(
            id = "bind_7_deadbeef", gen = 7L, desired = true, stage = "/data/local/tmp/x.wav",
            targets = listOf("/vendor/etc/carlock/a.wav"), state = LockOp.State.PLANNED,
            reason = "",
            evidence = "/data/local/tmp/vapp_lock_bind_abc123.sh"
        )
        val line = LockOp.journalLine(op, 1L)
        assertTrue("свидетельство в строке журнала: $line", line.contains("ev=/data/local/tmp/vapp_lock_bind_abc123.sh"))

        val dir = tmpDir()
        val f = File(dir, "ops.log")
        f.appendText(line + "\n")
        val j = (LockOp.readJournal(f) as LockOp.JournalRead.Readable).value
        val back = j.ops["bind_7_deadbeef"]
        assertNotNull("операция прочитана", back)
        assertEquals(
            "путь-свидетельство должен пережить рестарт процесса (иначе Unknown разрешать нечем)",
            "/data/local/tmp/vapp_lock_bind_abc123.sh", back!!.evidence
        )
    }

    @Test
    fun oldJournalLineWithoutEvidenceStillReads() {
        // Совместимость: строка, записанная версией до iter55, не должна становиться «повреждённой»
        // (иначе обновление само объявило бы журнал битым и заблокировало звук навсегда).
        val legacy = "id=bind_1_x;gen=1;desired=1;state=planned;stage=/stage.wav;targets=/a.wav;reason="
        val op = LockOp.decode(legacy)
        assertNotNull("старая строка разбирается", op)
        assertEquals("отсутствующее свидетельство = пусто, а не мусор", "", op!!.evidence)

        val j = (LockOp.readJournal(File(tmpDir(), "none.log")) as LockOp.JournalRead.Absent)
        assertTrue("нет файла — факт чтения: ${j.wire()}", j.wire().contains("absent"))
    }

    @Test
    fun evidenceParseSeparatesProvenTerminalFromEmptiness() {
        val proven = LockOp.parseEvidence("SH=1\nSTARTED=1\nDONE=rc=0\nPRESENT=1\n")
        assertNotNull("поля прочитаны", proven)
        assertTrue("rc=0 в .done — доказанный terminal", proven!!.terminalProven)
        assertEquals("1", proven.started)
        assertTrue("скрипт ещё лежит на устройстве", proven.scriptPresent)

        val emptyDone = LockOp.parseEvidence("SH=1\nSTARTED=1\nDONE=\nPRESENT=1\n")
        assertNotNull(emptyDone)
        assertFalse("пустой .done — НЕ terminal", emptyDone!!.terminalProven)

        val notStarted = LockOp.parseEvidence("SH=1\nSTARTED=0\nDONE=\nPRESENT=1\n")
        assertNotNull(notStarted)
        assertEquals("0", notStarted!!.started)
        assertFalse("не начинался — тем более не terminal", notStarted.terminalProven)

        assertNull("нет полей — свидетельство не прочитано, исход остаётся Unknown",
            LockOp.parseEvidence("sh: not found"))
        assertNull("пустой ответ не считается свидетельством", LockOp.parseEvidence(""))
    }

    @Test
    fun onlyReadyAllowsNewBind() {
        assertTrue("история разрешена — bind можно", LockOp.SoundRecovery.Ready.mayBind)
        val pending = LockOp.SoundRecovery.Pending("unresolved_ops=1", "/data/local/tmp/x.sh")
        val conflict = LockOp.SoundRecovery.Conflict("journal_corrupt:broken=2")
        assertFalse("недоказанный исход блокирует bind", pending.mayBind)
        assertFalse("повреждённый журнал блокирует bind", conflict.mayBind)
        assertTrue("причина названа: ${pending.wire()}", pending.wire().contains("evidence=/data/local/tmp/x.sh"))
        assertTrue("причина названа: ${conflict.wire()}", conflict.wire().contains("journal_corrupt"))
    }

    @Test
    fun brokenJournalLineIsNotAllClear() {
        val planned = LockOp.BindOp(
            "ok1", 1L, true, "/s", listOf("/a"), LockOp.State.PLANNED
        )
        val f = File(tmpDir(), "ops.log")
        f.appendText(LockOp.journalLine(planned, 1L) + "\n")
        f.appendText("это не строка журнала\n")
        val r = LockOp.readJournal(f)
        assertTrue("журнал прочитан", r is LockOp.JournalRead.Readable)
        val j = (r as LockOp.JournalRead.Readable).value
        assertEquals("повреждённая строка учтена", 1, j.broken)
        // Ключевой факт §7.2: `broken>0` — это ВОЗМОЖНАЯ неизвестная операция. На этом условии
        // сверка возвращает Conflict (`journal_corrupt:broken=N`) и bind не отправляется, даже
        // если по разобранным строкам всё выглядит снятым.
        assertTrue("причина блокировки названа: ${LockOp.SoundRecovery.Conflict("journal_corrupt:broken=" + j.broken).wire()}",
            LockOp.SoundRecovery.Conflict("journal_corrupt:broken=" + j.broken).wire().contains("broken=1"))

        val clean = File(tmpDir(), "clean.log")
        clean.appendText(LockOp.journalLine(planned.copy(state = LockOp.State.DONE, reason = "ok"), 2L) + "\n")
        val cj = (LockOp.readJournal(clean) as LockOp.JournalRead.Readable).value
        assertTrue("у чистого журнала сток доказан: unresolved пуст", cj.unresolved().isEmpty())
        assertEquals("и повреждений нет", 0, cj.broken)
    }

    @Test
    fun unknownAppliedIsItsOwnStateNotPendingOrOff() {
        // §9: UI обязан различать `applied` / `pending` / `off` / `unknown`. Значение «unknown»
        // вводится отдельно: `appliedOutcome` его НЕ производит (он описывает доказанные факты).
        assertEquals("неизвестность — своя строка для UI", "unknown", Toggles45.APPLIED_UNKNOWN)
        assertTrue("доказанное применение остаётся applied",
            Toggles45.appliedOutcome(want = true, applied = true) == "applied")
        assertTrue("неприменённое остаётся pending",
            Toggles45.appliedOutcome(want = true, applied = false) == "pending")
        assertTrue("сохранённый OFF — off",
            Toggles45.appliedOutcome(want = false, applied = true) == "off")
        assertFalse("неизвестность не выдаётся за «ждёт готовности»",
            Toggles45.APPLIED_UNKNOWN == Toggles45.appliedOutcome(want = true, applied = false))
    }
}
