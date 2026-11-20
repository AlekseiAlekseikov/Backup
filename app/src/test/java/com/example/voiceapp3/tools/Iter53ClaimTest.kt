package com.example.voiceapp3.tools

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter53 §4.1–§4.2 — claim JIT: РЕАЛЬНЫЙ вывод команды чтения и строгий разбор.
 *
 * Что чинится (аудит iter52, §4.1):
 *  • `JitOwn.readCmd` печатал `echo CLAIM="$(cat …)"`. Кавычки — синтаксис shell, в stdout их
 *    НЕТ: реальный вывод `CLAIM=op=abc;ts=123;want=1`. Парсер `JitNet` требовал ЗАКРЫВАЮЩУЮ
 *    кавычку → совпадений никогда не было → `null` → `claim_absent` → возврат Wi-Fi РАЗРЕШАЛСЯ
 *    при чужом claim.
 *  • `rc` транспорта не проверялся: таймаут/ошибка канала давали тот же `null`, что и «файла нет».
 *  • «Файла нет», «файл пуст» и «файл не читается» были ОДНИМ фактом.
 *
 * Метод проверки: команда [JitOwn.readCmd] исполняется НАСТОЯЩИМ `/bin/sh` над настоящими
 * файлами, разбор идёт по её фактическому stdout. Тест, который сам печатает кавычки, заменён:
 * он «доказывал» работу парсера на выводе, которого устройство не производит.
 *
 * Чего тесты НЕ доказывают: поведение `/system/bin/sh` и toybox на конкретной ГУ; фактический
 * возврат Wi-Fi в системе (`NetState.setWifi`).
 */
class Iter53ClaimTest {

    private val dev = RealShellDevice()

    /** `/data/local/tmp` «устройства» — [RealShellDevice] переписывает префикс в локальный путь. */
    private val base: String = JitOwn.BASE

    private fun claimFile() = dev.file("voiceapp3_jit.claim")
    private fun termFile(opId: String) = dev.file("voiceapp3_jit_$opId.term")

    private fun read(opId: String): Pair<Int, String> = dev.sh(JitOwn.readCmd(opId, base))

    private fun reply(opId: String): JitOwn.ReadReply {
        val (rc, out) = read(opId)
        return JitOwn.parseReadReply(rc, out)
    }

    @After
    fun tearDown() = dev.cleanup()

    // ───────────────────────── §4.1: протокол ответа, а не догадка о нём ─────────────────────────

    @Test
    fun realReadCommandUsesExplicitProtocolWithoutQuotes() {
        val (rc, out) = read("a")
        assertEquals("команда чтения должна завершаться успешно", 0, rc)
        assertTrue("маркер начала секции", out.contains("CS_BEGIN"))
        assertTrue("маркер конца секции", out.contains("CS_END"))
        assertTrue("статус claim напечатан явно", out.contains("CLAIM_STATUS=absent"))
        assertTrue("статус terminal напечатан явно", out.contains("TERM_STATUS=absent"))
        // Регрессия: в stdout не должно быть кавычек вокруг значения — именно из-за них
        // прежний regex `CLAIM=\"?([^\"\n]*)\"` не находил совпадения.
        assertFalse("значение claim не обрамляется кавычками в stdout", out.contains("CLAIM_VALUE=\""))
        assertFalse("и не печатается в старом формате `CLAIM=`", out.contains("\nCLAIM=op="))
    }

    @Test
    fun realClaimWrittenByClaimCommandIsReadAsPresent() {
        val op = JitOwn.Op("a1b2", 1_700_000_000_000L, want = true, state = JitOwn.State.CLAIMED)
        val (wrc, wout) = dev.sh(JitOwn.claimScriptForTest(op, base))
        assertEquals("запись claim через реальный shell", 0, wrc)
        assertTrue("claimCmd подтверждает запись чтением", wout.contains("op=a1b2"))
        val r = reply("a1b2")
        val present = r.claim as? JitOwn.ClaimRead.Present
        assertNotNull("claim, записанный настоящей командой, ОБЯЗАН разбираться", present)
        assertEquals("a1b2", present!!.op.id)
        assertEquals(1_700_000_000_000L, present.op.tsMs)
        assertTrue(present.op.want)
    }

    @Test
    fun confirmedAbsentIsNotTheSameAsUnknown() {
        val r = reply("a")
        assertEquals("файла действительно нет", JitOwn.ClaimRead.ConfirmedAbsent, r.claim)
    }

    @Test
    fun emptyClaimFileIsUnknownNotAbsent() {
        claimFile().writeText("")
        val r = reply("a")
        assertTrue("пустой файл — это НЕИЗВЕСТНОСТЬ, а не «claim отсутствует»",
            r.claim is JitOwn.ClaimRead.Unknown)
    }

    @Test
    fun garbageClaimFileIsUnknownNotAbsent() {
        claimFile().writeText("op=a;ts=not_a_number;want=1\n")
        val r = reply("a")
        assertTrue("нечитаемый формат — неизвестность",
            r.claim is JitOwn.ClaimRead.Unknown)
    }

    @Test
    fun missingDirectoryIsUnknownNotAbsent() {
        // Каталога нет: `[ -e ]` ложно, но и `[ -d ]` ложно — доказать отсутствие claim нельзя.
        val ghost = "/data/local/tmp/iter53_nodir_${System.nanoTime()}"
        val (rc, out) = dev.sh(JitOwn.readCmd("a", ghost))
        val claim = JitOwn.parseReadReply(rc, out).claim
        assertTrue("нет каталога — неизвестность, а не «claim отсутствует»",
            claim is JitOwn.ClaimRead.Unknown)
    }

    // ───────────────────────── §4.2: строгий разбор (rc, маркеры, дубликаты) ─────────────────────────

    @Test
    fun transportFailureIsUnknown() {
        claimFile().writeText("op=a;ts=1700000000;want=1\n")
        dev.denyBefore += (dev.calls + 1)
        val (rc, out) = read("a")
        assertEquals(137, rc)
        val claim = JitOwn.parseClaimReply(rc, out)
        assertTrue("rc≠0 не может означать «claim отсутствует»",
            claim is JitOwn.ClaimRead.Unknown)
    }

    @Test
    fun truncatedOrUnframedOutputIsUnknown() {
        claimFile().writeText("op=a;ts=1700000000;want=1\n")
        val (_, good) = read("a")
        val truncated = good.substringBefore("CS_END")
        assertTrue("обрезанный вывод", JitOwn.parseClaimReply(0, truncated) is JitOwn.ClaimRead.Unknown)
        val wrapped = good + "CS_END\n"
        assertTrue("двойной конец секции", JitOwn.parseClaimReply(0, wrapped) is JitOwn.ClaimRead.Unknown)
        assertTrue("пустой вывод", JitOwn.parseClaimReply(0, "") is JitOwn.ClaimRead.Unknown)
    }

    @Test
    fun oldFormatOutputIsUnknownAndNeverAllowsRestore() {
        // Ровно то, что печатала команда iter52 (кавычки в stdout не попадают).
        val old = "CLAIM=op=a;ts=1700000000;want=1\nTERM="
        assertTrue("старый формат не разбирается как «claim отсутствует»",
            JitOwn.parseClaimReply(0, old) is JitOwn.ClaimRead.Unknown)
        assertFalse("и не разрешает включение Wi-Fi",
            JitOwn.restoreDecision("a", JitOwn.parseClaimReply(0, old)).first)
    }

    @Test
    fun duplicateStatusLinesAreRejected() {
        val out = """
            CS_BEGIN
            CLAIM_STATUS=present
            CLAIM_STATUS=absent
            CLAIM_VALUE=op=a;ts=1;want=1
            TERM_STATUS=absent
            CS_END
        """.trimIndent()
        assertTrue("две строки статуса — подмена, а не факт",
            JitOwn.parseClaimReply(0, out) is JitOwn.ClaimRead.Unknown)
    }

    @Test
    fun presentWithoutValueIsUnknown() {
        val out = "CS_BEGIN\nCLAIM_STATUS=present\nTERM_STATUS=absent\nCS_END"
        assertTrue(JitOwn.parseClaimReply(0, out) is JitOwn.ClaimRead.Unknown)
    }

    // ───────────────────────── §4.2: решение о возврате Wi-Fi ─────────────────────────

    @Test
    fun wifiRestoreRequiresOwnClaim() {
        val ours = JitOwn.ClaimRead.Present(JitOwn.Op("a", 1L, true, JitOwn.State.CLAIMED))
        val foreign = JitOwn.ClaimRead.Present(JitOwn.Op("b", 1L, true, JitOwn.State.CLAIMED))
        assertEquals(true to "claim_ours", JitOwn.restoreDecision("a", ours))
        assertEquals(false to "claim_foreign:b", JitOwn.restoreDecision("a", foreign))
        assertEquals(false to "claim_confirmed_absent_payload_refused",
            JitOwn.restoreDecision("a", JitOwn.ClaimRead.ConfirmedAbsent))
        val unk = JitOwn.restoreDecision("a", JitOwn.ClaimRead.Unknown("transport_rc=137"))
        assertFalse("неизвестность нельзя трактовать как «конкурирующих нет»", unk.first)
        assertEquals("claim_unknown:transport_rc=137", unk.second)
    }

    @Test
    fun endToEndOwnClaimAllowsRestoreForeignDoesNot() {
        val op = JitOwn.Op("own", 1_700_000_000_000L, true, JitOwn.State.CLAIMED)
        dev.sh(JitOwn.claimScriptForTest(op, base))
        val (rc, out) = read("own")
        assertTrue("claim нашей операции разрешает возврат",
            JitOwn.restoreDecision("own", JitOwn.parseClaimReply(rc, out)).first)
        val (rc2, out2) = read("other")
        assertFalse("другой opId при нашем claim — отказ",
            JitOwn.restoreDecision("other", JitOwn.parseClaimReply(rc2, out2)).first)
    }

    // ───────────────────────── terminal: тот же протокол ─────────────────────────

    @Test
    fun terminalIsReadThroughSameProtocol() {
        File(dev.root, "data/local/tmp").mkdirs()
        termFile("t1").writeText(
            "START ts=1700000001 op=t1\n" +
                "END rc=7 op=t1\n"
        )
        claimFile().writeText("op=t1;ts=1700000000;want=1\n")
        val (rc, out) = read("t1")
        val t = JitOwn.parseTerminal("t1", out, rc)
        assertNotNull("terminal ОБЯЗАН читаться по реальному протоколу (в iter52 не читался)", t)
        assertTrue(t!!.started)
        assertTrue(t.finished)
        assertEquals(7, t.rc)
        assertTrue("claim наш", t.note.contains("claim_ours"))
    }

    @Test
    fun terminalOfForeignOperationIsNotCountedAsOurs() {
        termFile("t2").writeText("START ts=1 op=other\nEND rc=0 op=other\n")
        claimFile().writeText("op=other;ts=1700000000;want=1\n")
        val (rc, out) = read("t2")
        val t = JitOwn.parseTerminal("t2", out, rc)!!
        assertFalse("чужой END не считается нашим завершением", t.finished)
        assertTrue(t.claimOther)
        assertEquals(JitOwn.State.UNKNOWN, JitOwn.classify(sentAck = false, term = t))
    }

    @Test
    fun unreadableReplyKeepsTerminalUnknown() {
        dev.denyBefore += (dev.calls + 1)
        val (rc, out) = read("t3")
        assertNull("не прочитали — значит не знаем, а не «ничего не было»",
            JitOwn.parseTerminal("t3", out, rc))
    }
}
