package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * iter60 §4 — durable TTL: фазы попытки, fence между вызовами и после restart, доведение до
 * последнего намерения владельца, раздельные v4/v6, атомарная запись и миграция.
 *
 * Тесты работают с реальными файлами (TemporaryFolder): «перезапуск» моделируется новым
 * чтением с диска, без общего состояния в памяти процесса.
 */
class Iter60TtlFenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var journal: File
    private lateinit var desired: File

    @Before
    fun setUp() {
        journal = File(tmp.root, "ttl_attempts.log")
        desired = File(tmp.root, "ttl_desired.txt")
    }

    private fun attempt(id: String, want: Boolean, gen: Long, phase: TtlState.Phase,
                        path: String = "/data/local/tmp/ttl_$id.done", rc: Int = Int.MIN_VALUE) =
        TtlState.Attempt(id, path, want, gen, phase, rc, ts = 1000L)

    // ─────────────── 1. адрес прошлой попытки не перезаписывается ───────────────

    @Test
    fun newAttemptDoesNotOverwriteTheEvidenceAddressOfThePreviousOne() {
        val a = attempt("a", true, 1, TtlState.Phase.PREPARED)
        assertTrue("запись A подтверждена", TtlState.record(journal, a))
        // Новая попытка (например OFF) — отдельная строка журнала.
        val b = attempt("b", false, 2, TtlState.Phase.PREPARED)
        assertTrue("запись B подтверждена", TtlState.record(journal, b))

        val all = TtlState.parseJournal(journal.readText())
        assertEquals("в журнале две попытки", 2, all.size)
        assertEquals("адрес A сохранён", a.evidencePath, all.first { it.id == "a" }.evidencePath)
        assertEquals("адрес B сохранён", b.evidencePath, all.first { it.id == "b" }.evidencePath)
        // Дефект iter59: `file.writeText` оставлял ровно одну запись — адрес A терялся.
    }

    // ─────────────── 2. fence: пока прошлая попытка не разрешена — мутации нет ───────────────

    @Test
    fun fenceBlocksANewMutationWhileThePreviousAttemptMayStillRun() {
        val a = attempt("a", true, 1, TtlState.Phase.MAY_HAVE_RUN)
        TtlState.record(journal, a)
        val f = TtlState.fence(TtlState.parseJournal(journal.readText()))
        assertTrue("новая мутация запрещена: $f", f is TtlState.Fence.Blocked)
        assertEquals("заблокирована именно A", "a", (f as TtlState.Fence.Blocked).attempt.id)
        assertEquals("причина названа", "may_have_run_terminal_unknown", f.why)

        // PREPARED блокирует так же: «подготовлено» ≠ «не исполнялось».
        val p = attempt("p", false, 2, TtlState.Phase.PREPARED)
        TtlState.record(journal, p)
        val f2 = TtlState.fence(TtlState.parseJournal(journal.readText()))
        assertTrue("prepared тоже блокирует", f2 is TtlState.Fence.Blocked)
    }

    @Test
    fun fenceIsCleanOnlyAfterTheTerminalOfThatExactAttemptIsDurable() {
        val a = attempt("a", true, 1, TtlState.Phase.MAY_HAVE_RUN)
        TtlState.record(journal, a)

        // Terminal ЧУЖОЙ попытки не разрешает нашу (иначе «.done» одного скрипта закрывал бы другой).
        // readback A без terminal ⇒ попытка остаётся незавершённой.
        val stillPending = TtlState.resolveByEvidence(a, terminalRc = null, now = 2000L)
        TtlState.record(journal, stillPending)
        assertTrue("без terminal fence не снят",
            TtlState.fence(TtlState.parseJournal(journal.readText())) is TtlState.Fence.Blocked)

        // Настоящий terminal этой попытки ⇒ fence снят, код payload сохранён.
        val done = TtlState.resolveByEvidence(a, terminalRc = 0, now = 3000L)
        TtlState.record(journal, done)
        val records = TtlState.parseJournal(journal.readText())
        assertTrue("после terminal fence чист",
            TtlState.fence(records) is TtlState.Fence.Clean)
        assertEquals("payload_rc сохранён", 0, records.last { it.id == "a" }.payloadRc)
        assertEquals("фаза terminal", TtlState.Phase.TERMINAL, records.last { it.id == "a" }.phase)
    }

    // ─────────────── 3. restart: состояние переживает процесс ───────────────

    @Test
    fun stateSurvivesRestartAndStillBlocksTheNextMutation() {
        TtlState.record(journal, attempt("a", true, 1, TtlState.Phase.MAY_HAVE_RUN))
        assertTrue(TtlState.saveDesired(desired, on = false, now = 1111L) != null)

        // «Перезапуск»: читаем только файлы, никакого состояния в памяти.
        val freshJournal = File(tmp.root, "ttl_attempts.log")
        val freshDesired = File(tmp.root, "ttl_desired.txt")
        val records = TtlState.parseJournal(freshJournal.readText())
        assertTrue("после restart незавершённая попытка видна",
            TtlState.fence(records) is TtlState.Fence.Blocked)
        val d = TtlState.loadDesired(freshDesired)
        assertEquals("намерение владельца пережило restart", false, d?.on)
        assertEquals("поколение сохранено", 1L, d?.generation)
    }

    @Test
    fun desiredIsWrittenAtomicallyAndItsGenerationGrowsMonotonically() {
        val d1 = TtlState.saveDesired(desired, on = true, now = 1L)!!
        val d2 = TtlState.saveDesired(desired, on = false, now = 2L)!!
        val d3 = TtlState.saveDesired(desired, on = true, now = 3L)!!
        assertTrue("поколение растёт", d1.generation < d2.generation && d2.generation < d3.generation)
        assertFalse("временный файл не остался", File(desired.parentFile, desired.name + ".tmp").exists())
        assertEquals("на диске последнее намерение", true, TtlState.loadDesired(desired)?.on)
        // Повреждённый файл намерения не превращается в «выключить»: неизвестно ⇒ null.
        desired.writeText("garbage")
        assertNull("повреждённое намерение = неизвестно, не OFF", TtlState.loadDesired(desired))
    }

    // ─────────────── 4. желание владельца независимо от применённого факта ───────────────

    @Test
    fun ownerDesireIsKeptWhileTheAttemptIsPendingAndAppliedAfterwards() {
        // ON в полёте (shell мог исполниться), владелец сразу просит OFF.
        TtlState.record(journal, attempt("a", true, 1, TtlState.Phase.MAY_HAVE_RUN))
        TtlState.saveDesired(desired, on = true, now = 100L)
        TtlState.saveDesired(desired, on = false, now = 200L)   // последнее намерение — OFF

        // Пока terminal A не прочитан, ничего не применяем, но намерение OFF сохранено.
        assertTrue(TtlState.fence(TtlState.parseJournal(journal.readText())) is TtlState.Fence.Blocked)
        assertEquals("намерение не потеряно", false, TtlState.desiredAfterResolve(TtlState.loadDesired(desired)))

        // Terminal A доказан (rc=0, ON применился) ⇒ доводим до ПОСЛЕДНЕГО намерения (OFF).
        TtlState.record(journal, attempt("a", true, 1, TtlState.Phase.TERMINAL, rc = 0))
        val want = TtlState.desiredAfterResolve(TtlState.loadDesired(desired))!!
        assertFalse("доводим до OFF, а не до применённого ON", want)
        assertEquals("применённый факт ON зафиксирован отдельно", 0,
            TtlState.parseJournal(journal.readText()).last().payloadRc)
        // Именно этот сценарий в iter59 давал «ON после OFF»: адрес A затирался B.
    }

    @Test
    fun lastDesireWinsAcrossManyTogglesAndIsSingleSourceOfTruth() {
        val seq = listOf(true, false, true, false, false, true)
        seq.forEachIndexed { i, on -> TtlState.saveDesired(desired, on, now = i.toLong()) }
        assertEquals("последнее намерение ON", true, TtlState.loadDesired(desired)?.on)
        assertEquals("поколение = число переключений", seq.size.toLong(),
            TtlState.loadDesired(desired)?.generation)
        assertNotEquals("поколение сменилось", 1L, TtlState.loadDesired(desired)?.generation)
    }

    // ─────────────── 5. v4/v6 раздельно ───────────────

    @Test
    fun v4AndV6AttemptsAreTrackedSeparately() {
        // Свои попытки на каждое семейство: terminal одного не закрывает fence другого.
        TtlState.record(journal, attempt("v4", true, 1, TtlState.Phase.PREPARED,
            path = "/data/local/tmp/ttl_v4.done"))
        TtlState.record(journal, attempt("v6", true, 2, TtlState.Phase.MAY_HAVE_RUN,
            path = "/data/local/tmp/ttl_v6.done"))
        val pending = TtlState.unresolved(TtlState.parseJournal(journal.readText()))
        assertEquals("обе попытки независимы", listOf("v4", "v6"), pending.map { it.id })
        assertNotEquals("адреса различны", pending[0].evidencePath, pending[1].evidencePath)

        TtlState.record(journal, attempt("v4", true, 1, TtlState.Phase.TERMINAL,
            path = "/data/local/tmp/ttl_v4.done", rc = 0))
        val after = TtlState.parseJournal(journal.readText())
        assertEquals("незавершённой осталась только v6", listOf("v6"),
            TtlState.unresolved(after).map { it.id })
        assertTrue("fence держится из-за v6", TtlState.fence(after) is TtlState.Fence.Blocked)
    }

    // ─────────────── 6. миграция iter59 → iter60 ───────────────

    @Test
    fun legacyRecordIsMigratedAsUnknownNotAsNotSent() {
        val legacy = "path=/data/local/tmp/ttl_old.done;want=on;ts=1234"
        val m = TtlState.migrateLegacy(legacy, generation = 5, idPrefix = "ttl")
        assertTrue("legacy мигрирована", m != null)
        assertEquals("адрес сохранён", "/data/local/tmp/ttl_old.done", m!!.evidencePath)
        assertEquals("желание сохранено", true, m.want)
        // Старый формат не различал «не отправлено» и «неизвестно» ⇒ считаем «могло исполниться».
        assertEquals("фаза — may_have_run, не terminal", TtlState.Phase.MAY_HAVE_RUN, m.phase)
        assertTrue("миграция блокирует новую мутацию до readback", m.unresolved)
        assertNull("мусор не мигрируется", TtlState.migrateLegacy("garbage", 1, "ttl"))
    }

    // ─────────────── 7. честность записи ───────────────

    @Test
    fun recordWithoutEvidencePathIsRejectedSoSendIsForbidden() {
        // Без адреса свидетельства отправлять нельзя: повтор после Unknown нечем проверить.
        assertFalse("пустой путь отвергнут",
            TtlState.record(journal, attempt("x", true, 1, TtlState.Phase.PREPARED, path = "")))
        assertFalse("файл не создан", journal.exists())
    }

    @Test
    fun fullReadbackCatchesSilentTruncation() {
        val a = attempt("a", true, 1, TtlState.Phase.PREPARED)
        assertTrue(TtlState.record(journal, a))
        // Имитация «запись без чтения»: файл обрезан ⇒ следующая сверка должна это увидеть.
        journal.writeText(TtlState.parseJournal(journal.readText()).last().wire().take(8))
        val back = TtlState.parseJournal(journal.readText())
        assertTrue("обрезанная запись не выдаёт себя за полную",
            back.isEmpty() || !TtlState.sameFields(a, back.last()))
    }

    @Test
    fun forceAbandonKeepsTheFactUnknownAndRecordsTheReason() {
        val a = attempt("a", true, 1, TtlState.Phase.MAY_HAVE_RUN)
        val ab = TtlState.forceAbandon(a, "owner_cancelled", now = 9L)
        assertFalse("попытка больше не блокирует", ab.unresolved)
        assertEquals("код НЕ выдуман", Int.MIN_VALUE, ab.payloadRc)
        assertTrue("причина записана в идентификатор", ab.id.contains("abandoned:owner_cancelled"))
        // Это видимый компромисс: содержимое не доказано, но и «применено» не заявляется.
    }

    // ─────────────── 8. свидетельство с устройства: что можно утверждать ───────────────

    @Test
    fun evidenceClassificationDistinguishesTerminalStartedAndNeverRan() {
        // terminal есть ⇒ доказанный исход (единственный источник «применено/не применено»).
        val done = TtlState.classifyEvidence("SH=0\nSTARTED=1\nDONE=rc=0\nPRESENT=0\n")
        assertEquals(TtlState.Evidence.Done(0), done)
        assertEquals("terminal закрывает попытку", TtlState.Phase.TERMINAL,
            TtlState.applyEvidence(attempt("a", true, 1, TtlState.Phase.MAY_HAVE_RUN), done, 5L)!!.phase)

        // Начал исполняться, terminal нет ⇒ исход НЕИЗВЕСТЕН, закрывать нельзя.
        val started = TtlState.classifyEvidence("SH=0\nSTARTED=1\nDONE=\nPRESENT=0\n")
        assertEquals(TtlState.Evidence.MayHaveRun, started)
        assertNull("«мог исполниться» не закрывается сам",
            TtlState.applyEvidence(attempt("a", true, 1, TtlState.Phase.MAY_HAVE_RUN), started, 5L))

        // Ни следа файла, ни .started, ни .done ⇒ доказанно не запускался.
        val never = TtlState.classifyEvidence("SH=0\nSTARTED=0\nDONE=\nPRESENT=0\n")
        assertEquals(TtlState.Evidence.NotStarted, never)
        val closed = TtlState.applyEvidence(attempt("a", true, 1, TtlState.Phase.MAY_HAVE_RUN), never, 5L)!!
        assertEquals("доказанное «не уходило»", TtlState.Phase.PROVEN_NOT_SENT, closed.phase)
        assertEquals("код не выдуман", Int.MIN_VALUE, closed.payloadRc)
        assertEquals("нет следа ⇒ не блокирует", TtlState.Fence.Clean,
            TtlState.fence(listOf(closed)))

        // Мусор/ошибка чтения ⇒ ничего не утверждаем.
        assertEquals(TtlState.Evidence.Unreadable, TtlState.classifyEvidence(null))
        assertEquals(TtlState.Evidence.Unreadable, TtlState.classifyEvidence("ERR_ADB timeout"))
    }
}
