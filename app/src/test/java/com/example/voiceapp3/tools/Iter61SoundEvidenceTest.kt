package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * iter61 §5 — СВИДЕТЕЛЬСТВО ЗВУКА: от РЕАЛЬНОЙ команды чтения до агрегации.
 *
 * Дефект (аудит iter60):
 *  • `ScriptDelivery.evidenceCommand:542–546` всегда печатает `STARTED=0`, когда файла `.started`
 *    нет; `LockOp.parseEvidence:51–55` кладёт это в `started` строкой `"0"`;
 *  • а ветка восстановления (`LockOp.kt:457–461`) проверяла `prevFacts.started.isEmpty()` — условие,
 *    которое production-парсер НИКОГДА не выполняет;
 *  • следствие: запись `restore_prepared` (crash до отправки) навсегда оставалась
 *    `previous_restore_unresolved`, и заявленное восстановление звука не работало.
 *
 * Что сделано (и проверяется здесь):
 *  1. `EvidenceFacts.startedMark` — ТИПИЗИРОВАННЫЙ факт: PRESENT (`1`) / ABSENT (`0`) / UNKNOWN
 *     (строки нет либо значение не `0`/`1`). Простое сравнение с `"0"` решением не является.
 *  2. Доказательство «отправки не было» берётся ИЗ ЖУРНАЛА: `restore_prepared` (identity записана,
 *     передачи транспорту ещё не было) и `restore_not_sent:*`; для `restore_handoff:*` требуется
 *     terminal. Отсутствие следов — только сверка, противоречие блокирует.
 *  3. `LockSound.restore` пишет durable-фазу `restore_handoff:<path>` ДО вызова транспорта и не
 *     отправляет, если запись не подтверждена.
 *
 * Весь путь — production: `ScriptDelivery.evidenceCommand` исполняется настоящим `/bin/sh`,
 * ответ разбирает `LockOp.parseEvidence`, решение принимает `LockOp.reconStep`, итог — `recoveryGate`.
 *
 * Чего тест НЕ доказывает (в отчёте 🟡): реальный Android/toybox, настоящий `mount`/`umount` и
 * слышимость звука на ГУ; здесь Linux `/bin/sh` песочницы и temp-каталог вместо `/data/local/tmp`.
 */
class Iter61SoundEvidenceTest {

    private val t1 = "/vendor/etc/carlock/close.wav"
    private val t2 = "/vendor/etc/carlock/open.wav"
    private val bindEv = "/data/local/tmp/voiceapp3_lock_bind_61.sh"

    private val dir: File = File.createTempFile("sound61", "").let { f ->
        f.delete(); f.mkdirs(); f
    }

    private fun op(restoreEv: String = "", reason: String = "") = LockOp.BindOp(
        id = "bind_61_x", gen = 61L, desired = true,
        stage = "/data/local/tmp/vapp_stage",
        targets = listOf(t1, t2), state = LockOp.State.UNDEFINED,
        evidence = bindEv, restoreEv = restoreEv, reason = reason
    )

    /** Факты о пути: реальная команда [ScriptDelivery.evidenceCommand] + production-парсер. */
    private fun factsOf(path: String): LockOp.EvidenceFacts? {
        val cmd = ScriptDelivery.evidenceCommand(path)
        val p = ProcessBuilder("/bin/sh", "-c", cmd).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(30, TimeUnit.SECONDS)
        assertEquals("команда чтения завершилась: $out", 0, p.exitValue())
        return LockOp.parseEvidence(out)
    }

    private fun bindTerminal(): Map<String, LockOp.EvidenceFacts?> {
        File(dir, "bind_61.sh").writeText("#!/bin/sh\ntrue\n")
        File(dir, "bind_61.sh.started").writeText("started=1\n")
        File(dir, "bind_61.sh.done").writeText("rc=0\n")
        return mapOf(bindEv to factsOf("bind_61.sh"))
    }

    private class Ports(
        val facts: Map<String, LockOp.EvidenceFacts?>,
        var outcome: LockOp.RestoreOutcome = LockOp.RestoreOutcome(true, false, 0, "STOCK_OK", "")
    ) : LockOp.ReconPorts {
        var prepares = 0
        var restores = 0
        val sent = ArrayList<String>()
        override fun evidence(op: LockOp.BindOp, path: String) = facts[path]
        override fun prepareRestore(op: LockOp.BindOp): String {
            prepares++
            return "/data/local/tmp/voiceapp3_lock_restore_attempt_61.sh"
        }
        override fun restore(op: LockOp.BindOp, attempt: String): LockOp.RestoreOutcome {
            restores++
            sent.add(attempt)
            return outcome
        }
    }

    // ───────── 1. Реальная команда чтения: три РАЗНЫХ факта не смешиваются ─────────

    @Test
    fun absentStartedFileIsAbsentMarkNotUnknownAndNotProofOfAnything() {
        val f = factsOf("nothing_here_61.sh")
        assertNotNull("ответ команды разобран: ${ScriptDelivery.evidenceCommand("x")}", f)
        assertEquals("STARTED=0 → ABSENT", LockOp.StartedMark.ABSENT, f!!.startedMark)
        assertFalse("terminal не доказан", f.terminalProven)
        assertFalse("скрипта нет", f.scriptPresent)
        assertEquals("DONE пуст — terminal отсутствует", "none", f.done)
    }

    @Test
    fun presentStartedFileIsPresentMarkAndTerminalIsProven() {
        File(dir, "attempt_61.sh").writeText("#!/bin/sh\ntrue\n")
        File(dir, "attempt_61.sh.started").writeText("started=1\n")
        assertEquals("без .done — PRESENT без terminal", LockOp.StartedMark.PRESENT,
            factsOf("attempt_61.sh")!!.startedMark)
        assertFalse("terminal не доказан без .done", factsOf("attempt_61.sh")!!.terminalProven)

        File(dir, "attempt_61.sh.done").writeText("rc=0\n")
        val done = factsOf("attempt_61.sh")!!
        assertEquals("метка старта не изменилась", LockOp.StartedMark.PRESENT, done.startedMark)
        assertTrue("rc=<число> в .done — терминал доказан", done.terminalProven)
        assertEquals("код скрипта прочитан", "rc=0", done.done)
    }

    @Test
    fun missingOrGarbledStartedLineIsUnknownNotAbsent() {
        assertNull("пустой ответ — свидетельства нет", LockOp.parseEvidence(""))
        assertNull("ответ без STARTED/DONE — свидетельства нет",
            LockOp.parseEvidence("SH=0\nPRESENT=0"))
        assertEquals("пустое значение STARTED → UNKNOWN (а не ABSENT)",
            LockOp.StartedMark.UNKNOWN, LockOp.parseEvidence("STARTED= done=none")!!.startedMark)
        assertEquals("мусор в STARTED → UNKNOWN",
            LockOp.StartedMark.UNKNOWN, LockOp.parseEvidence("STARTED=maybe done=none")!!.startedMark)
    }

    // ───────── 2. Crash до отправки восстанавливается, после — нет ─────────

    @Test
    fun crashBeforeTransportIsRecoveredBecauseTheJournalProvesNoHandoff() {
        val noTraces = factsOf("never_sent_61.sh")!!
        val ports = Ports(bindTerminal() + ("/data/local/tmp/attempt_61.sh" to noTraces))
        val step = LockOp.reconStep(op("/data/local/tmp/attempt_61.sh", "restore_prepared"), ports)

        assertEquals("restore отправлен", 1, ports.restores)
        assertNotEquals("не бессрочный запрет: ${step.wire()}",
            "previous_restore_unresolved", step.reason)
        assertEquals("уходит РОВНО одна попытка — новая, с подготовленной identity",
            1, ports.sent.size)
        assertEquals("новая попытка подготовлена", 1, ports.prepares)
        assertEquals("отправлен именно подготовленный путь",
            listOf("/data/local/tmp/voiceapp3_lock_restore_attempt_61.sh"), ports.sent)
    }

    @Test
    fun crashAfterHandoffStaysFencedEvenWithoutDeviceTraces() {
        val attempt = "/data/local/tmp/attempt_61h.sh"
        val ports = Ports(bindTerminal() + (attempt to factsOf(attempt)))
        val step = LockOp.reconStep(op(attempt, "restore_handoff:$attempt"), ports)

        assertEquals("фенс: ${step.wire()}", "previous_restore_unresolved", step.reason)
        assertEquals("отправки нет", 0, ports.restores)
        assertTrue("операция не закрыта", step.unresolved)
        assertTrue("поздний shell возможен", step.lateShell)
        assertTrue("нужен read-only повтор", step.deferReread)
    }

    @Test
    fun journalSaysPreparedButDeviceShowsTracesIsAContradictionAndFences() {
        // Журнал утверждает «до транспорта не дошли», а на устройстве есть скрипт и `.started`.
        // Это не «NotSent»: чей-то запуск УЖЕ был. Блокируем и просим разбор.
        File(dir, "contra_61.sh").writeText("#!/bin/sh\ntrue\n")
        File(dir, "contra_61.sh.started").writeText("started=1\n")
        val ev = factsOf("contra_61.sh")!!
        val attempt = "/data/local/tmp/contra_61.sh"
        val ports = Ports(bindTerminal() + (attempt to ev))
        val step = LockOp.reconStep(op(attempt, "restore_prepared"), ports)

        assertEquals("противоречие не выдаётся за безопасный повтор",
            "previous_restore_unresolved", step.reason)
        assertEquals("отправки нет", 0, ports.restores)
    }

    @Test
    fun typedNotSentPhaseIsRetriedAndUnknownPhaseIsNot() {
        val attempt = "/data/local/tmp/attempt_61n.sh"
        val notSent = Ports(bindTerminal() + (attempt to factsOf(attempt)),
            LockOp.RestoreOutcome(true, false, 0, "STOCK_OK:$t1", ""))
        val stepA = LockOp.reconStep(op(attempt, "restore_not_sent:socket_closed"), notSent)
        assertEquals("NotSent — повтор разрешён", 1, notSent.restores)
        assertNotEquals("и это не фенс", "previous_restore_unresolved", stepA.reason)

        val unknown = Ports(bindTerminal() + (attempt to factsOf(attempt)))
        val stepB = LockOp.reconStep(op(attempt, "restore_outcome_unknown"), unknown)
        assertEquals("unknown — отправки нет", 0, unknown.restores)
        assertNotEquals("и операция не закрыта", LockOp.State.DONE, stepB.newState)
    }

    // ───────── 3. Агрегация: неразрешённая операция запрещает новый bind ─────────

    @Test
    fun aggregationGatesTheNextBindOnUnresolvedRestores() {
        val attempt = "/data/local/tmp/attempt_61agg.sh"
        val fenced = LockOp.reconStep(op(attempt, "restore_handoff:$attempt"),
            Ports(bindTerminal() + (attempt to factsOf(attempt))))
        val unresolved = ArrayList<String>()
        val lateShell = ArrayList<String>()
        var conflict: String? = null
        for (s in listOf(fenced)) {
            if (s.unresolved) unresolved.add(s.opId)
            if (s.lateShell) lateShell.add(s.opId)
            if (s.conflict != null && conflict == null) conflict = s.conflict
        }
        assertNotNull("пока исход не разрешён — side effect запрещён",
            LockOp.recoveryGate(unresolved, conflict, lateShell, readOnlyPass = false))
        assertNotNull("read-only проход тоже не даёт действовать",
            LockOp.recoveryGate(emptyList(), null, emptyList(), readOnlyPass = true))
        assertNull("всё разрешено — агрегатор возвращает null (можно сходиться к намерению)",
            LockOp.recoveryGate(emptyList(), null, emptyList(), readOnlyPass = false))
    }

    // ───────── 4. Production: фаза передачи пишется ДО транспорта ─────────

    @Test
    fun productionWritesTheHandoffPhaseBeforeCallingTheTransport() {
        val src = File("app/src/main/java/com/example/voiceapp3/tools/LockSound.kt").readText()
        val handoff = src.indexOf("reason = \"restore_handoff:\$attempt\"")
        val send = src.indexOf("AdbClient.executePrepared(prep, 30000)")
        assertTrue("фаза передачи объявлена", handoff > 0)
        assertTrue("фаза передачи записывается ДО вызова транспорта: handoff=$handoff send=$send",
            handoff in 1 until send)
        assertTrue("неподтверждённая запись останавливает отправку",
            src.contains("reason=handoff_not_persisted"))
        assertTrue("NotSent назван причиной, а не «успехом»",
            src.contains("attempt_not_prepared"))

        val op = File("app/src/main/java/com/example/voiceapp3/tools/LockOp.kt").readText()
        assertFalse("решение больше не опирается на пустую строку started",
            op.contains("prevFacts.started.isEmpty()"))
        assertTrue("решение опирается на типизированную метку",
            op.contains("StartedMark.ABSENT") && op.contains("journalProvesNoHandoff"))
        assertTrue("противоречие журнала и следов названо",
            op.contains("evidenceContradicts"))
    }
}
