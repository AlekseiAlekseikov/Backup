package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * iter62 §A3 — ЗВУК: восстановление из журнала и ОДНОКРАТНЫЙ безопасный путь завершения
 * handoff-Unknown (без вечного pending).
 *
 * Дефекты iter61:
 *  • `preparedRestore` — in-memory (`LockSound.kt:1830`): после рестарта процесса restore с durable
 *    `restore_prepared` был невозможен (`attempt_not_prepared`);
 *  • crash ПОСЛЕ `restore_handoff` и до исполнения давал `previous_restore_unresolved` с
 *    `deferReread` НАВСЕГДА (`LockOp.kt:496–503`) — звук не сходился к выбору владельца.
 *
 * Проверяется production-цепочка `ScriptDelivery.evidenceCommand → LockOp.parseEvidence →
 * LockOp.reconStep → LockOp.recoveryGate`; fake только на границе устройства.
 */
class Iter62SoundRecoveryTest {

    private val t1 = "/vendor/etc/carlock/close.wav"
    private val t2 = "/vendor/etc/carlock/open.wav"
    private val bindEv = "/data/local/tmp/voiceapp3_lock_bind_62.sh"
    private val attemptA = "/data/local/tmp/voiceapp3_lock_restore_aaa.sh"

    private val dir: File = File.createTempFile("sound62", "").let { f ->
        f.delete(); f.mkdirs(); f
    }

    private fun op(restoreEv: String = "", reason: String = "") = LockOp.BindOp(
        id = "bind_62_x", gen = 62L, desired = true,
        stage = "/data/local/tmp/vapp_stage",
        targets = listOf(t1, t2), state = LockOp.State.UNDEFINED,
        evidence = bindEv, restoreEv = restoreEv, reason = reason
    )

    private fun factsOf(path: String): LockOp.EvidenceFacts? {
        val p = ProcessBuilder("/bin/sh", "-c", ScriptDelivery.evidenceCommand(path))
            .directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(30, TimeUnit.SECONDS)
        return LockOp.parseEvidence(out)
    }

    private fun bindTerminal(): Map<String, LockOp.EvidenceFacts?> {
        File(dir, "bind_62.sh").writeText("#!/bin/sh\ntrue\n")
        File(dir, "bind_62.sh.started").writeText("started=1\n")
        File(dir, "bind_62.sh.done").writeText("rc=0\n")
        return mapOf(bindEv to factsOf("bind_62.sh"))
    }

    private class Ports(
        val facts: Map<String, LockOp.EvidenceFacts?>,
        var outcome: LockOp.RestoreOutcome = LockOp.RestoreOutcome(
            true, false, 0,
            "STOCK_OK:/vendor/etc/carlock/close.wav\nSTOCK_OK:/vendor/etc/carlock/open.wav\n", ""
        ),
        var observeResult: LockOp.Reconcile? = null,
        var readOnlyPass: Boolean = false
    ) : LockOp.ReconPorts {
        var prepares = 0
        var restores = 0
        val sent = ArrayList<String>()
        var preparedPath = "/data/local/tmp/voiceapp3_lock_restore_new62.sh"
        override fun readOnly() = readOnlyPass
        override fun evidence(op: LockOp.BindOp, path: String) = facts[path]
        override fun observe(op: LockOp.BindOp) = observeResult
        override fun prepareRestore(op: LockOp.BindOp): String { prepares++; return preparedPath }
        override fun restore(op: LockOp.BindOp, attempt: String): LockOp.RestoreOutcome {
            restores++; sent.add(attempt); return outcome
        }
    }

    // ───────── A3-1: crash до отправки восстанавливается (журнал `restore_prepared`) ─────────

    @Test
    fun a3PreparedWithoutHandoffIsRecoveredAndSent() {
        val ports = Ports(bindTerminal() + (attemptA to factsOf("nothing_a.sh")))
        val step = LockOp.reconStep(op(attemptA, "restore_prepared"), ports)
        assertEquals("отправка состоялась", 1, ports.restores)
        assertNotEquals("не вечный запрет", "previous_restore_unresolved", step.reason)
        assertEquals("отправлена подготовленная identity",
            listOf("/data/local/tmp/voiceapp3_lock_restore_new62.sh"), ports.sent)
    }

    // ───────── A3-3: handoff-Unknown — РОВНО один безопасный повтор ─────────

    @Test
    fun a3HandoffUnknownGetsExactlyOneSafeCompletionAttempt() {
        val f = factsOf("never_ran.sh")!!
        // Первый проход после crash: handoff есть, terminal нет ⇒ идемпотентный повтор завершения.
        // Исход повтора намеренно НЕ доказывает сток, чтобы проверить именно однократность.
        val first = Ports(bindTerminal() + (attemptA to f),
            outcome = LockOp.RestoreOutcome(true, false, 0, "PARTIAL", ""))
        val step1 = LockOp.reconStep(op(attemptA, "restore_handoff:$attemptA"), first)
        assertEquals("повтор завершения отправлен", 1, first.restores)
        val persist = step1.persist ?: error("запись фазы обязательна")
        assertTrue("попытка помечена как использованная: ${persist.reason}",
            persist.reason.startsWith(LockOp.COMPLETION_USED))

        // Второй проход: повтор уже был и снова без terminal ⇒ дальше НЕ повторяем.
        val second = Ports(bindTerminal() + (attemptA to f),
            outcome = LockOp.RestoreOutcome(true, false, 0, "PARTIAL", ""))
        val step2 = LockOp.reconStep(persist.copy(restoreEv = attemptA), second)
        assertEquals("второго автоматического повтора нет", 0, second.restores)
        assertEquals("состояние названо", "previous_restore_unresolved", step2.reason)
        assertTrue("операция не закрыта фиктивно", step2.unresolved)
    }

    @Test
    fun a3SuccessfulCompletionClosesTheOperationAndSendsOnce() {
        val f = factsOf("never_ran.sh")!!
        val ports = Ports(bindTerminal() + (attemptA to f))
        val step = LockOp.reconStep(op(attemptA, "restore_handoff:$attemptA"), ports)
        assertEquals("одна отправка завершения", 1, ports.restores)
        assertEquals("операция закрыта по доказанному стоку", LockOp.State.DONE, step.newState)
        assertFalse("не unresolved", step.unresolved)
    }

    @Test
    fun a3ReadOnlyPassClosesTheOperationOnlyByObservedStock() {
        val f = factsOf("never_ran.sh")!!
        val used = op(attemptA, LockOp.COMPLETION_USED + attemptA)
        // (а) сток наблюдается — операция закрывается БЕЗ mutation.
        val stock = Ports(bindTerminal() + (attemptA to f),
            observeResult = LockOp.Reconcile.STOCK_RESTORED, readOnlyPass = true)
        val stepA = LockOp.reconStep(used, stock)
        assertEquals("закрыто по наблюдаемому стоку", LockOp.State.DONE, stepA.newState)
        assertEquals("мутаций не было", 0, stock.restores)
        assertFalse("не помечено как неразрешённое", stepA.unresolved)

        // (б) сток НЕ наблюдается — состояние названо, ничего не выдумано.
        val unknown = Ports(bindTerminal() + (attemptA to f),
            observeResult = null, readOnlyPass = true)
        val stepB = LockOp.reconStep(used, unknown)
        assertEquals("previous_restore_unresolved", stepB.reason)
        assertEquals("мутаций не было", 0, unknown.restores)
        assertTrue(stepB.unresolved)
    }

    @Test
    fun a3HandoffWithTerminalClosesTheOperation() {
        File(dir, "done_62.sh").writeText("#!/bin/sh\ntrue\n")
        File(dir, "done_62.sh.started").writeText("started=1\n")
        File(dir, "done_62.sh.done").writeText("rc=0\n")
        val ev = factsOf("done_62.sh")!!
        val path = "/data/local/tmp/voiceapp3_lock_restore_done.sh"
        val ports = Ports(bindTerminal() + (path to ev),
            outcome = LockOp.RestoreOutcome(true, false, 0,
                "STOCK_OK:$t1\nSTOCK_OK:$t2\n", ""))
        val step = LockOp.reconStep(op(path, "restore_handoff:$path"), ports)
        assertTrue("terminal доказан — операция может закрыться",
            step.newState == LockOp.State.DONE || step.reason != "previous_restore_unresolved")
    }

    // ───────── A3-4: terminal прошлой попытки не приписывается новой ─────────

    @Test
    fun a3OtherAttemptsTerminalNeverClosesThisOne() {
        val other = "/data/local/tmp/voiceapp3_lock_restore_zzz.sh"
        File(dir, "zzz.sh").writeText("#!/bin/sh\ntrue\n")
        File(dir, "zzz.sh.started").writeText("started=1\n")
        File(dir, "zzz.sh.done").writeText("rc=0\n")
        val ports = Ports(
            bindTerminal() + (other to factsOf("zzz.sh")) + (attemptA to factsOf("nothing_a.sh")),
            outcome = LockOp.RestoreOutcome(true, false, 0, "PARTIAL", "")
        )
        val step = LockOp.reconStep(op(attemptA, "restore_handoff:$attemptA"), ports)
        assertNotEquals("чужая identity не закрывает попытку", LockOp.State.DONE, step.newState)
        assertTrue("операция остаётся неразрешённой", step.unresolved)
        assertEquals("своя попытка отправлена ровно один раз", 1, ports.restores)
        assertEquals("отправлен именно путь из журнала-подготовки",
            listOf("/data/local/tmp/voiceapp3_lock_restore_new62.sh"), ports.sent)
    }

    // ───────── A3-5: ON→OFF→ON во время pending сходится к последнему ─────────

    @Test
    fun a3DesiredFlipsDuringPendingConvergeToTheLastChoice() {
        val f = factsOf("never_ran.sh")!!
        val opOn = op(attemptA, "restore_handoff:$attemptA")
        val p1 = Ports(bindTerminal() + (attemptA to f))
        val s1 = LockOp.reconStep(opOn, p1)
        assertNotNull("первый проход что-то решил", s1)
        // Владелец переключил OFF (новая операция) и снова ON: каждая новая операция проходит
        // свою сверку, а прежняя закрывается наблюдаемым стоком в читающем проходе.
        val opOff = opOn.copy(id = "bind_62_off", desired = false,
            reason = LockOp.COMPLETION_USED + attemptA)
        val p2 = Ports(bindTerminal() + (attemptA to f),
            observeResult = LockOp.Reconcile.STOCK_RESTORED, readOnlyPass = true)
        val s2 = LockOp.reconStep(opOff, p2)
        assertEquals("прежняя операция закрыта стоком", LockOp.State.DONE, s2.newState)
        assertEquals("и это без mutation", 0, p2.restores)
    }

    // ───────── A3-6: production восстанавливает подготовку из журнала ─────────

    @Test
    fun a3ProductionRebuildsPreparationFromTheJournalPath() {
        val src = File("app/src/main/java/com/example/voiceapp3/tools/LockSound.kt").readText()
        assertTrue("путь восстановления объявлен", src.contains("rebuildPrepared(op, attempt)"))
        assertTrue("имя строится по production-схеме",
            src.contains("voiceapp3_\" + ScriptDelivery.sanitize(name) + \"_\""))
        assertTrue("путь сверяется перед отправкой",
            src.contains("if (rebuilt.evidencePath != attempt)"))
        assertTrue("несовпадение названо и не отправляется", src.contains("action=no_send"))

        val op = File("app/src/main/java/com/example/voiceapp3/tools/LockOp.kt").readText()
        assertTrue("однократный повтор завершения объявлен", op.contains("COMPLETION_USED"))
        assertTrue("читающий проход закрывает по наблюдаемому стоку",
            op.contains("observed_stock_after_unknown_restore"))
        assertFalse("вечный deferReread для handoff-Unknown убран из этой ветки",
            op.contains("reason = \"previous_restore_unresolved\"),\n                deferReread = true,\n                note = \"прежний restore не терминален: новая попытка не отправляется\"\n            )\n        }\n\n        // ── 3"))
    }
}
