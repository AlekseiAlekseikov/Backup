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
import java.util.concurrent.TimeUnit

/**
 * iter61 §4 — TTL: отсутствие файлов ≠ NotSent, миграция ДО fence, журнал не «пустой по ошибке»,
 * у каждой повторной попытки свои фазы, и РАБОТАЮЩИЙ механизм снятия fence без вечной блокировки.
 *
 * Что именно проверяется:
 *  1. `TracesAbsent` не закрывает попытку → сценарий «ON Unknown → пустой readback → OFF» остаётся
 *     fenced (иначе поздний ON применялся бы после OFF).
 *  2. Ограждение поколением: production-скрипт [TtlScript.build] с реальным `/bin/sh` отказывается
 *     менять таблицу, если durable-поколение на устройстве уже другое (`exit 90`). Это и есть
 *     безопасный способ снять fence: старая попытка физически не может применить свои правила.
 *  3. Миграция legacy выполняется ДО fence и подтверждается записью.
 *  4. Повреждённый/нечитаемый журнал не считается «попыток не было».
 *  5. `forceAbandon` не выдаёт отказ за NotSent и не меняет id.
 *
 * Чего тесты НЕ доказывают (⛔): реальные `iptables`/`ip rule` на ГУ и фактический поздний shell
 * в прошивке — это проверяется только на машине с согласия владельца.
 */
class Iter61TtlFenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var journal: File
    private lateinit var desired: File
    private lateinit var genFile: File

    @Before
    fun setUp() {
        journal = File(tmp.root, "ttl_attempts.log")
        desired = File(tmp.root, "ttl_desired.txt")
        genFile = File(tmp.root, "ttl_gen")
    }

    private fun attempt(id: String, want: Boolean, gen: Long, phase: TtlState.Phase,
                        path: String = "/data/local/tmp/ttl_$id.done") =
        TtlState.Attempt(id, path, want, gen, phase, Int.MIN_VALUE, ts = 1000L)

    // ─────────── 1. Пустой readback не разрешает конфликтующую мутацию ───────────

    @Test
    fun absentFilesNeverReleaseTheFenceSoLateOnCannotLandAfterOff() {
        // A = ON отправлен, ответ потерян; его скрипт мог ещё не стартовать.
        val a = attempt("a", true, 1, TtlState.Phase.MAY_HAVE_RUN)
        TtlState.record(journal, a)
        val empty = TtlState.classifyEvidence("SH=0\nSTARTED=0\nDONE=\nPRESENT=0\n")
        assertEquals("отсутствие следов — своя категория", TtlState.Evidence.TracesAbsent, empty)
        assertNull("попытка НЕ закрывается", TtlState.applyEvidence(a, empty, 2L))

        val records = TtlState.parseJournal(journal.readText())
        assertTrue("B=OFF не может отправиться, пока A не разрешена",
            TtlState.fence(records) is TtlState.Fence.Blocked)

        // Снимается только ограждением поколением — и это фаза, отличная от NotSent.
        // iter62 §A2.3: снятие требует доказательства — подтверждённого чтением поколения.
        val sup = TtlState.supersede(records.first(),
            TtlState.SupersedeProof(9L, true, "test"), 3L)!!
        TtlState.record(journal, sup)
        val after = TtlState.parseJournal(journal.readText())
        assertTrue("после ограждения мутация разрешена", TtlState.fence(after) is TtlState.Fence.Clean)
        assertEquals("фаза названа честно", TtlState.Phase.SUPERSEDED, after.last().phase)
        assertNotEquals("это не «не отправлено»", TtlState.Phase.PROVEN_NOT_SENT, after.last().phase)
    }

    // ─────────── 2. Ограждение поколением работает в РЕАЛЬНОМ shell ───────────

    private fun runScript(script: String, timeoutSec: Long = 30): Pair<Int, String> {
        val f = File(tmp.root, "guard_${System.nanoTime()}.sh").apply { writeText(script) }
        val p = ProcessBuilder("/bin/sh", f.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(timeoutSec, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    @Test
    fun productionScriptRefusesToApplyWhenTheSlotGenerationIsNewer() {
        // device-side поколение уже 5, а скрипт относится к поколению 4 ⇒ он обязан отступить.
        genFile.writeText("5")
        val stale = TtlScript.build(
            enable = true, gen = "dev", ownChain = TtlScript.OUR_CHAIN,
            legacyChain = TtlOwnership.LEGACY_CHAIN,
            guardGen = "4", genFile = genFile.absolutePath
        )
        // Вырезаем ограждение и исполняем настоящим `/bin/sh`: iptables в стенде нет, но guard
        // обязан завершить скрипт ДО любой мутации.
        val (rc, out) = runScript(guardOnly(stale))
        assertEquals("старый скрипт вышел с кодом superseded", 90, rc)
        assertTrue("причина названа: $out", out.contains("VAPP_TTL_SUPERSEDED"))
        assertFalse("мутации не было: $out", out.contains("TTL_APPLY") || out.contains("iptables"))

        // Тот же скрипт с актуальным поколением проходит ограждение.
        genFile.writeText("4")
        val (rc2, out2) = runScript(guardOnly(stale))
        assertEquals("актуальное поколение проходит", 0, rc2)
        assertTrue("подтверждение поколения: $out2", out2.contains("VAPP_TTL_GEN_OK"))

        // Совсем без ограждения (старые вызовы) скрипт не содержит guard-блока вовсе.
        val plain = TtlScript.build(enable = true, gen = "dev")
        assertFalse("без параметров ограждения блок не добавляется",
            plain.contains("VAPP_TTL_SUPERSEDED"))
    }

    @Test
    fun lateAttemptCannotApplyItsRulesAfterTheGenerationMovedOn() {
        // Сценарий §4.1 целиком: A(ON) ушёл, ответ потерян; владелец просит OFF; приложение
        // фиксирует поколение 2; ПОЗДНИЙ скрипт A (поколение 1) стартует и обязан отступить.
        genFile.writeText("2")
        val lateA = TtlScript.build(
            enable = true, gen = "dev", guardGen = "1", genFile = genFile.absolutePath
        )
        val (rc, out) = runScript(guardOnly(lateA))
        assertEquals("поздний ON не применяется", 90, rc)
        assertTrue(out.contains("VAPP_TTL_SUPERSEDED"))
        // А новый OFF (поколение 2) проходит ограждение — то есть OFF реально может примениться.
        val freshB = TtlScript.build(
            enable = false, gen = "dev", guardGen = "2", genFile = genFile.absolutePath
        )
        val (rcB, outB) = runScript(guardOnly(freshB))
        assertEquals("новый OFF проходит ограждение", 0, rcB)
        assertTrue(outB.contains("VAPP_TTL_GEN_OK"))
    }

    /** Первая часть скрипта до `ENABLE=` — ровно ограждение (без iptables-логики). */
    private fun guardOnly(script: String): String {
        val end = script.indexOf("ENABLE=")
        val head = if (end > 0) script.substring(0, end) else script
        return head + "\nexit 0\n"
    }

    // ─────────── 3. Миграция ДО fence (порядок на production-пути) ───────────

    @Test
    fun legacyMigrationIsConfirmedBeforeTheFenceIsEvaluated() {
        val src = File("app/src/main/java/com/example/voiceapp3/tools/NetworkTtl.kt").readText()
        val migrate = src.indexOf("migrateLegacyBeforeFence(desire.generation)")
        val fence = src.indexOf("resolvePreviousAttempts(desire.generation)")
        assertTrue("вызовы найдены", migrate > 0 && fence > 0)
        assertTrue("миграция ДО fence: migrate=$migrate fence=$fence", migrate < fence)
        assertTrue("перенос подтверждается записью",
            src.contains("if (!TtlState.record(journalFile, migrated))"))
        assertTrue("при неподтверждённом переносе отправки нет",
            src.contains("legacy_migration_not_persisted"))
        // Миграция идемпотентна: повтор не размножает попытку.
        assertTrue("проверка «уже перенесено»", src.contains("any { it.id == migrated.id }"))
    }

    @Test
    fun legacyRecordBecomesUnknownAndBlocksUntilSuperseded() {
        val migrated = TtlState.migrateLegacy(
            "path=/data/local/tmp/ttl_old.done;want=on;ts=1234", generation = 1, idPrefix = "ttl"
        )!!
        assertEquals("старый формат = «могло исполниться»", TtlState.Phase.MAY_HAVE_RUN, migrated.phase)
        assertTrue("блокирует", TtlState.fence(listOf(migrated)) is TtlState.Fence.Blocked)
    }

    // ─────────── 4. Журнал: отсутствие ≠ порча ≠ нечитаемость ───────────

    @Test
    fun journalStatesDistinguishAbsentFromCorruptFromUnreadable() {
        val absent = TtlState.readJournal(null, exists = false)
        assertEquals("первый запуск", TtlState.JournalState.ABSENT, absent.state)
        assertTrue("в безопасном первом запуске мутировать можно", absent.mutationAllowed)

        val unreadable = TtlState.readJournal(null, exists = true)
        assertEquals("файл есть, но не прочитан", TtlState.JournalState.UNREADABLE, unreadable.state)
        assertFalse("это НЕ пустой журнал", unreadable.mutationAllowed)

        val good = TtlState.readJournal(attempt("a", true, 1, TtlState.Phase.TERMINAL, ).wire() + "\n", true)
        assertEquals(TtlState.JournalState.OK, good.state)
        assertTrue(good.mutationAllowed)

        val corrupt = TtlState.readJournal("мусор вместо записи\n", true)
        assertEquals("битая строка", TtlState.JournalState.CORRUPT, corrupt.state)
        assertFalse("мутации нет", corrupt.mutationAllowed)

        val truncated = TtlState.readJournal(
            attempt("a", true, 1, TtlState.Phase.PREPARED).wire() + "\n" + "id=b;path=/x;want=o", true
        )
        assertEquals("оборванная запись", TtlState.JournalState.CORRUPT, truncated.state)
        assertFalse("мутации нет", truncated.mutationAllowed)
    }

    @Test
    fun unresolvedRecordInAValidJournalStillBlocks() {
        val text = attempt("a", true, 1, TtlState.Phase.MAY_HAVE_RUN).wire() + "\n"
        val j = TtlState.readJournal(text, true)
        assertTrue("журнал валиден", j.mutationAllowed)
        assertTrue("но попытка не разрешена", TtlState.fence(j.records) is TtlState.Fence.Blocked)
    }

    // ─────────── 5. Каждая повторная попытка — своя фаза и свой исход ───────────

    @Test
    fun eachRetryAttemptCarriesItsOwnPhaseAndTerminal() {
        val a = attempt("a", true, 1, TtlState.Phase.MAY_HAVE_RUN, path = "/data/local/tmp/ttl_a.done")
        val b = attempt("b", true, 1, TtlState.Phase.PREPARED, path = "/data/local/tmp/ttl_b.done")
        TtlState.record(journal, a)
        TtlState.record(journal, b)

        // terminal A не закрывает B, и наоборот: адреса и id различны.
        val closedA = TtlState.applyEvidence(a, TtlState.Evidence.Done(0), 10L)!!
        TtlState.record(journal, closedA)
        val after = TtlState.parseJournal(journal.readText())
        assertEquals("A закрыта", TtlState.Phase.TERMINAL, after.first { it.id == "a" }.phase)
        assertEquals("B осталась незавершённой", TtlState.Phase.PREPARED,
            after.first { it.id == "b" }.phase)
        assertTrue("fence держится из-за B", TtlState.fence(after) is TtlState.Fence.Blocked)
        assertNotEquals("коды не перепутаны", 0, after.first { it.id == "b" }.payloadRc)

        // B получает свой собственный terminal — только тогда fence снимается.
        TtlState.record(journal, TtlState.applyEvidence(b, TtlState.Evidence.Done(7), 11L)!!)
        val done = TtlState.parseJournal(journal.readText())
        assertEquals("B закрыта своим кодом", 7, done.first { it.id == "b" }.payloadRc)
        assertTrue("fence снят", TtlState.fence(done) is TtlState.Fence.Clean)
    }

    @Test
    fun productionBlocksSendWhenThePhaseCannotBePersisted() {
        val src = File("app/src/main/java/com/example/voiceapp3/tools/NetworkTtl.kt").readText()
        assertTrue("markAttempt возвращает результат",
            src.contains("private fun markAttempt(") && src.contains("): Boolean {"))
        val mark = src.indexOf("if (!markAttempt(prep.evidencePath, want, desire.generation, TtlState.Phase.MAY_HAVE_RUN))")
        val send = src.indexOf("val detailed = AdbClient.executePrepared(prep, 40000)")
        assertTrue("проверка фазы ПЕРЕД отправкой: $mark < $send", mark in 1 until send)
        assertTrue("отказ назван", src.contains("phase_not_persisted"))
        assertTrue("у retry своя фаза", src.contains(
            "if (!markAttempt(nextPrep.evidencePath, want, desire.generation,"))
        assertTrue("terminal ставится попытке, которая завершилась",
            src.contains("markAttempt(lastFinishedPath, want, desire.generation, TtlState.Phase.TERMINAL"))
    }

    @Test
    fun desireLagSchedulesARealNextPass() {
        val src = File("app/src/main/java/com/example/voiceapp3/tools/NetworkTtl.kt").readText()
        assertTrue("доведение последнего намерения ставится, а не только логируется",
            src.contains("scheduleDesiredConvergence(\"desire_lag\")"))
        assertTrue("через существующий ограниченный механизм",
            src.contains("networkRecheckOnce(ctx, TTL_DESIRE_LAG_DELAY_MS)"))
        assertTrue("дедупликация сохранена", src.contains("if (recheckScheduled) return"))
    }
}
