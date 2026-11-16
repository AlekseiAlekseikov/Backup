package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter58 §3.1/§3.2 — ЗВУК ЗАМКА: решение агрегатора и границы restore.
 *
 * Дефекты iter57 (независимый аудит, приложение A):
 *  1. `LockSound.kt:1823–1865` — `unresolvedNow`/`conflictReason`/`lateShell` вычислялись, а
 *     затем ИГНОРИРОВАЛИСЬ: при включённом звуке планировался новый bind (с
 *     `reconcileFirst=false`, то есть в обход проверки), при выключенном ставилась отметка
 *     «применено OFF», и функция ВСЕГДА возвращала `Ready`;
 *  2. `LockOp.kt:366–413` — `ports.restore` вызывался ДО проверок «прежний restore не
 *     терминален» и «адрес свидетельства подготовлен», поэтому новый `umount` уходил, пока
 *     прошлый мог ещё исполняться;
 *  3. `LockSound.kt:1804–1821` — restore отправлялся при пустом `prepareRestore`.
 *
 * Проверяется: чистое правило [LockOp.recoveryGate], то, что production спрашивает ИМЕННО его
 * (по порядку вызовов в `LockSound.reconcileLocked`), и поведение [LockOp.reconStep] на границах
 * restore — с настоящей моделью портов.
 *
 * Чего тесты НЕ доказывают: реальные `mount`/`umount`, Android-сервис замка и живой UI (в отчёте
 * это 🟡: нет стенда с root и Android runtime).
 */
class Iter58SoundTest {

    private val t1 = "/vendor/etc/carlock/close.wav"
    private val t2 = "/vendor/etc/carlock/open.wav"
    private val bindEv = "/data/local/tmp/voiceapp3_lock_bind_abc.sh"
    private val oldRestoreEv = "/data/local/tmp/voiceapp3_lock_restore_old.sh"

    private fun op(
        evidence: String = bindEv,
        restoreEv: String = "",
        targets: List<String> = listOf(t1, t2)
    ) = LockOp.BindOp(
        id = "bind_58_x", gen = 8L, desired = true, stage = "/data/local/tmp/vapp_stage",
        targets = targets, state = LockOp.State.UNDEFINED, evidence = evidence, restoreEv = restoreEv
    )

    private fun facts(started: String, done: String, present: Boolean = true) =
        LockOp.EvidenceFacts(started, done, present, Regex("rc=-?\\d+").containsMatchIn(done))

    private fun stockFor(vararg targets: String): String =
        targets.joinToString("\n") { "STOCK_OK:$it" } + "\nUMOUNTED_OK:" + targets.first()

    private class Ports(
        var facts: Map<String, LockOp.EvidenceFacts?> = emptyMap(),
        var restoreOutcome: LockOp.RestoreOutcome =
            LockOp.RestoreOutcome(true, false, 0, "", ""),
        var preparePath: String = "/data/local/tmp/voiceapp3_lock_restore_new.sh",
        var readOnlyPass: Boolean = false,
        var observed: LockOp.Reconcile? = null
    ) : LockOp.ReconPorts {
        var restores = 0
        var prepares = 0
        var sentAttempts = ArrayList<String>()
        override fun evidence(op: LockOp.BindOp, path: String): LockOp.EvidenceFacts? = facts[path]
        override fun readOnly(): Boolean = readOnlyPass
        override fun observe(op: LockOp.BindOp): LockOp.Reconcile? = observed
        override fun restore(op: LockOp.BindOp, attempt: String): LockOp.RestoreOutcome {
            restores++
            sentAttempts.add(attempt)
            return restoreOutcome
        }
        override fun prepareRestore(op: LockOp.BindOp): String {
            prepares++
            return preparePath
        }
    }

    // ───────── 1. Правило агрегата: без разрешения — ни bind, ни «применено OFF» ─────────

    @Test
    fun unresolvedBlocksEverythingEvenWhenOwnerWantsItOn() {
        val g = LockOp.recoveryGate(listOf("bind_1"), null, emptyList(), readOnlyPass = false)
        assertTrue("итог не Ready: $g", g is LockOp.SoundRecovery.Pending)
        assertFalse("bind запрещён", g!!.mayBind)
        assertTrue("причина названа: ${g.wire()}", g.wire().contains("unresolved:bind_1"))
    }

    @Test
    fun conflictWinsOverPendingAndIsNamed() {
        val g = LockOp.recoveryGate(
            listOf("bind_1", "bind_2"), "late_shell_possible:bind_2", listOf("bind_2"),
            readOnlyPass = false
        )
        assertTrue("конфликт важнее ожидания: $g", g is LockOp.SoundRecovery.Conflict)
        assertTrue("поздний shell назван: ${g!!.wire()}",
            g.wire().contains("late_shell_possible:bind_2"))
        assertFalse("bind запрещён", g.mayBind)
    }

    @Test
    fun lateShellAloneIsAlreadyAConflict() {
        val g = LockOp.recoveryGate(emptyList(), null, listOf("bind_9"), readOnlyPass = false)
        assertTrue("поздний shell без явного conflict тоже блокирует: $g",
            g is LockOp.SoundRecovery.Conflict)
        assertTrue("причина названа: ${g!!.wire()}", g.wire().contains("late_shell_possible:bind_9"))
    }

    @Test
    fun readOnlyPassNeverPermitsSideEffects() {
        val g = LockOp.recoveryGate(emptyList(), null, emptyList(), readOnlyPass = true)
        assertTrue("читающий проход не разрешает действия: $g", g is LockOp.SoundRecovery.Pending)
        assertTrue(g!!.wire().contains("read_only_pass"))
        assertFalse(g.mayBind)
    }

    @Test
    fun cleanPassAllowsConvergence() {
        assertNull("всё разрешено — агрегатор продолжает",
            LockOp.recoveryGate(emptyList(), null, emptyList(), readOnlyPass = false))
    }

    // ───────── 2. Production действительно спрашивает правило и не обходит его ─────────

    @Test
    fun productionAggregatorAsksTheGateBeforeAnySideEffect() {
        val src = File("app/src/main/java/com/example/voiceapp3/tools/LockSound.kt").readText()
        val gateAt = src.indexOf("val gate = LockOp.recoveryGate(")
        assertTrue("агрегатор вызывает правило", gateAt > 0)
        val rejectAt = src.indexOf("return gate", gateAt)
        val bindAt = src.indexOf("applySystemLockSoundBind(appCtx, File(path)", gateAt)
        val offAt = src.indexOf("markApplied(appCtx, false, \"reconciled_off\")", gateAt)
        assertTrue("ранний выход по правилу есть", rejectAt in (gateAt + 1) until bindAt)
        assertTrue("bind идёт ПОСЛЕ проверки правила", bindAt > rejectAt)
        assertTrue("отметка OFF идёт ПОСЛЕ проверки правила", offAt > rejectAt)
        // Единственный допустимый «ранний» Ready — ветка «незавершённых операций нет»: это
        // доказательство, а не обход сверки.
        assertTrue("ранний Ready только в ветке «операций нет»",
            src.substring(0, gateAt).contains("pending.isEmpty()"))
        assertTrue("правило — чистый код в LockOp, а не копия в UI",
            File("app/src/main/java/com/example/voiceapp3/tools/LockOp.kt").readText()
                .contains("fun recoveryGate("))
    }

    @Test
    fun rereadPassIsReadOnlyAndCannotSendRestore() {
        val src = File("app/src/main/java/com/example/voiceapp3/tools/LockSound.kt").readText()
        val reread = src.substringAfter("BgWorker.postDelayed(\"LockSound.reread\"").take(400)
        assertTrue("отложенный проход объявлен read-only: $reread",
            reread.contains("allowRestore = false"))
        assertTrue("порты чтения не готовят restore",
            src.contains("if (readOnlyPass) return \"\""))
    }

    // ───────── 3. Границы restore в reconStep (решение ДО отправки) ─────────

    @Test
    fun previousUnknownRestoreBlocksANewOne() {
        // bind терминален: проверка «прежний restore не разрешён» идёт ПОСЛЕ него (iter59 §3.2.5).
        val p = Ports(facts = mapOf(bindEv to facts("1", "rc=0")))
        // порты restore/prepare не должны даже вызваться
        val step = LockOp.reconStep(op(restoreEv = oldRestoreEv), p)

        assertEquals("повторный umount не отправлен", 0, p.restores)
        assertEquals("новый адрес не готовился", 0, p.prepares)
        assertEquals(LockOp.State.UNDEFINED, step.newState)
        assertEquals("previous_restore_unresolved", step.reason)
        assertTrue("операция остаётся открытой: ${step.wire()}", step.unresolved && step.lateShell)
        assertTrue("конфликт назван: ${step.conflict}", step.conflict != null)
        assertFalse("bind запрещён", step.mayBind)
        assertTrue("нужен read-only повтор", step.deferReread)
    }

    @Test
    fun missingRestoreEvidenceAddressBlocksTheSend() {
        val p = Ports(facts = mapOf(bindEv to facts("1", "rc=0")), preparePath = "")
        val step = LockOp.reconStep(op(), p)

        assertEquals("restore не отправлялся", 0, p.restores)
        assertEquals("подготовка вызывалась ровно один раз", 1, p.prepares)
        assertEquals("restore_evidence_not_prepared", step.reason)
        assertTrue(step.unresolved)
        assertFalse("bind запрещён", step.mayBind)
    }

    @Test
    fun provenBindAndPreparedEvidenceAllowExactlyOneRestore() {
        val p = Ports(
            facts = mapOf(bindEv to facts("1", "rc=0")),
            restoreOutcome = LockOp.RestoreOutcome(
                true, false, 0, stockFor(t1, t2),
                "/data/local/tmp/voiceapp3_lock_restore_new.sh"
            )
        )
        val step = LockOp.reconStep(op(), p)

        assertEquals("ровно один restore", 1, p.restores)
        assertEquals("адрес подготовлен до отправки", 1, p.prepares)
        assertEquals(LockOp.State.DONE, step.newState)
        assertEquals("reconciled_restore", step.reason)
        assertTrue("только теперь bind разрешён: ${step.wire()}", step.mayBind)
    }

    @Test
    fun unprovenBindNeverClosesTheOperationAndForbidsRestore() {
        val p = Ports(
            facts = mapOf(bindEv to facts("1", "")),   // START есть, terminal (.done rc=) нет
            restoreOutcome = LockOp.RestoreOutcome(
                true, false, 0, stockFor(t1, t2),
                "/data/local/tmp/voiceapp3_lock_restore_new.sh"
            )
        )
        val step = LockOp.reconStep(op(), p)

        // iter59 §3.2.5 меняет поведение iter58 осознанно: пока terminal bind не доказан,
        // НИКАКОЙ restore не отправляется (поздний bind мог бы наложиться сверху уже после
        // снятия). В iter58 здесь отправлялся restore — это и был дефект аудита.
        assertEquals("restore при недоказанном terminal bind не отправляется", 0, p.restores)
        assertEquals(LockOp.State.UNDEFINED, step.newState)
        assertEquals("late_shell_unproven", step.reason)
        assertTrue("операция остаётся открытой: ${step.wire()}", step.unresolved && step.lateShell)
        assertFalse("bind запрещён", step.mayBind)
    }

    @Test
    fun restoreThatWasNotSentIsRepeatableWithoutSideEffectClaim() {
        val p = Ports(
            facts = mapOf(bindEv to facts("1", "rc=0")),
            restoreOutcome = LockOp.RestoreOutcome(
                false, false, 1, "ERR_ADB_TIMEOUT", "", "timeout"
            )
        )
        val step = LockOp.reconStep(op(), p)

        assertEquals(LockOp.State.UNDEFINED, step.newState)
        assertTrue("причина названа: ${step.reason}", step.reason.startsWith("restore_not_sent"))
        assertFalse("закрывать нечего", step.mayBind)
        assertFalse("и «позднего shell» не заявляем", step.lateShell)
        assertTrue("повтор разрешён (побочных действий не было)", step.unresolved)
    }
}
