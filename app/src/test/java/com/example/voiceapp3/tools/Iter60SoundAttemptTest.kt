package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter60 §5 — ЗВУК: фазы попытки restore, различимость crash-до/после send, ограниченный reread
 * и честный `finishOpJournal`.
 *
 * Приложение A (аудит iter59) назвало дефекты; здесь каждый проверяется на РЕАЛЬНОМ решателе
 * [LockOp.reconStep] и на production-коде [LockSound] (чтение файла, а не пересказ).
 *
 *  1. `LockOp.kt:451–460` — `NotSent` с непустым `restoreEv` превращался в
 *     `previous_restore_unresolved`: у такой попытки terminal НЕ БУДЕТ никогда, значит звук
 *     восстанавливался бы «никогда» (бессрочный запрет).
 *  2. crash ДО send и crash ПОСЛЕ send различимы: `restore_prepared` + отсутствие следов скрипта —
 *     доказанное «не начиналось»; `restore_outcome_unknown` — «могло исполниться».
 *  3. `LockSound.kt` — reread ограничен и дедуплицирован (предел на операцию, без цепочки задач).
 *  4. `finishOpJournal` возвращает результат: DONE без durable-записи не объявляется.
 *
 * Чего эти тесты НЕ доказывают (честно, ⛔): реальный crash ОС между инструкциями, фактический
 * `umount` в прошивке ГУ, слышимость звука после восстановления.
 */
class Iter60SoundAttemptTest {

    private val t1 = "/vendor/etc/carlock/close.wav"
    private val t2 = "/vendor/etc/carlock/open.wav"
    private val bindEv = "/data/local/tmp/voiceapp3_lock_bind_abc.sh"
    private val attemptA = "/data/local/tmp/voiceapp3_lock_restore_attempt_a.sh"

    private fun op(
        restoreEv: String = "",
        reason: String = "",
        evidence: String = bindEv
    ) = LockOp.BindOp(
        id = "bind_60_x", gen = 60L, desired = true,
        stage = "/data/local/tmp/vapp_stage",
        targets = listOf(t1, t2), state = LockOp.State.UNDEFINED,
        evidence = evidence, restoreEv = restoreEv, reason = reason
    )

    private fun facts(started: String, done: String, present: Boolean = true) =
        LockOp.EvidenceFacts(started, done, present, Regex("rc=-?\\d+").containsMatchIn(done))

    private class Ports(
        val facts: Map<String, LockOp.EvidenceFacts?> = emptyMap(),
        var restoreOutcome: LockOp.RestoreOutcome =
            LockOp.RestoreOutcome(true, false, 0, "STOCK_OK", ""),
        var readOnlyPass: Boolean = false
    ) : LockOp.ReconPorts {
        var prepares = 0
        var restores = 0
        val sent = ArrayList<String>()
        var preparedPath = "/data/local/tmp/voiceapp3_lock_restore_attempt_a.sh"
        override fun readOnly(): Boolean = readOnlyPass
        override fun evidence(op: LockOp.BindOp, path: String) = facts[path]
        override fun prepareRestore(op: LockOp.BindOp): String {
            prepares++
            return preparedPath
        }
        override fun restore(op: LockOp.BindOp, attempt: String): LockOp.RestoreOutcome {
            restores++
            sent.add(attempt)
            return restoreOutcome
        }
    }

    private fun bindTerminal() = mapOf(bindEv to facts(started = "1", done = "rc=0"))

    // ─────────────── 1. NotSent не превращается в бессрочный запрет ───────────────

    @Test
    fun provenNotSentAttemptIsRetriedInsteadOfBlockingForever() {
        // Прошлая попытка: restore доказанно не уходил, `restoreEv` сохранён (дефект iter59:
        // именно это состояние делало `previous_restore_unresolved` и запрещало восстановление).
        val ports = Ports(facts = bindTerminal() + (attemptA to facts("", "", present = false)),
            restoreOutcome = LockOp.RestoreOutcome(true, false, 0, "STOCK_OK:$t1", ""))
        val blocked = LockOp.reconStep(op(restoreEv = attemptA, reason = "restore_not_sent:socket_closed"), ports)

        assertNotEquals("НЕ previous_restore_unresolved: ${blocked.wire()}",
            "previous_restore_unresolved", blocked.reason)
        assertEquals("новая попытка подготовлена", 1, ports.prepares)
        assertEquals("новая попытка отправлена", 1, ports.restores)
        assertEquals("отправлена именно новая identity", listOf(attemptA), ports.sent)
    }

    @Test
    fun crashBeforeSendIsRecoveredAsNotStartedButCrashAfterSendIsNot() {
        // (а) crash ДО send: запись `restore_prepared`, следов скрипта нет ⇒ исполнение не
        // начиналось ⇒ безопасный повтор (иначе одна неудачная попытка запрещала бы всё навсегда).
        val beforeSend = Ports(
            facts = bindTerminal() + (attemptA to facts("", "", present = false)),
            restoreOutcome = LockOp.RestoreOutcome(true, false, 0, "STOCK_OK:$t1", "")
        )
        val stepA = LockOp.reconStep(op(restoreEv = attemptA, reason = "restore_prepared"), beforeSend)
        assertEquals("crash до send не блокирует", 1, beforeSend.restores)
        assertNotEquals("не previous_restore_unresolved: ${stepA.wire()}",
            "previous_restore_unresolved", stepA.reason)

        // (б) тот же журнал, но скрипт НАЧАЛ исполняться (`started=1`, terminal нет) ⇒ исход
        // неизвестен, новая отправка запрещена. Это и есть различие «до» и «после» send.
        val afterSend = Ports(
            facts = bindTerminal() + (attemptA to facts("1", done = "", present = false)),
            restoreOutcome = LockOp.RestoreOutcome(true, false, 0, "STOCK_OK:$t1", "")
        )
        val stepB = LockOp.reconStep(op(restoreEv = attemptA, reason = "restore_prepared"), afterSend)
        assertEquals("после send — previous_restore_unresolved",
            "previous_restore_unresolved", stepB.reason)
        assertEquals("новая отправка запрещена", 0, afterSend.restores)
        ensureTrue("операция не закрыта", stepB.unresolved)

        // (в) явная фаза «исход неизвестен» блокирует так же, даже если следов нет:
        // отправитель не может доказать, что shell не стартовал.
        val unknown = Ports(facts = bindTerminal(),
            restoreOutcome = LockOp.RestoreOutcome(true, false, 0, "STOCK_OK:$t1", ""))
        val stepC = LockOp.reconStep(op(restoreEv = attemptA, reason = "restore_outcome_unknown"), unknown)
        assertEquals("unknown блокирует", 0, unknown.restores)
        assertNotEquals("и не закрывается", LockOp.State.DONE, stepC.newState)
    }

    private fun ensureTrue(message: String, value: Boolean) = assertTrue(message, value)

    // ─────────────── 2. NotSent у новой попытки сохраняет фазу, а не теряет её ───────────────

    @Test
    fun notSentKeepsThePhaseInTheDurableReasonSoNextPassCanRetry() {
        val ports = Ports(facts = bindTerminal(),
            restoreOutcome = LockOp.RestoreOutcome(false, false, -1, "", "", "socket_closed"))
        val step = LockOp.reconStep(op(), ports)
        assertEquals("причина называет «не отправлено»", "restore_not_sent:socket_closed", step.reason)
        val persist = step.persist ?: error("persist обязателен")
        assertEquals("фаза сохранена в журнале", "restore_not_sent:socket_closed", persist.reason)
        assertEquals("identity попытки сохранена", attemptA, persist.restoreEv)
        assertTrue("операция не закрыта", step.unresolved)

        // Следующий проход видит ЭТУ ЖЕ запись и обязан повторить, а не заблокироваться.
        val next = Ports(facts = bindTerminal(),
            restoreOutcome = LockOp.RestoreOutcome(true, false, 0, "STOCK_OK:$t1", ""))
        val step2 = LockOp.reconStep(persist, next)
        assertEquals("повтор разрешён", 1, next.restores)
        assertNotEquals("и это не previous_restore_unresolved",
            "previous_restore_unresolved", step2.reason)
    }

    @Test
    fun lateBindStillForbidsRestoreEvenWhenThePreviousAttemptWasNotSent() {
        // Ослабление §5 не должно открыть дверь позднему bind: он проверяется ПЕРВЫМ.
        val ports = Ports(facts = mapOf(bindEv to facts("1", done = "")),
            restoreOutcome = LockOp.RestoreOutcome(true, false, 0, "", ""))
        val step = LockOp.reconStep(op(restoreEv = attemptA, reason = "restore_not_sent:x"), ports)
        assertEquals("late_shell_unproven", step.reason)
        assertEquals("restore не отправлен", 0, ports.restores)
        assertTrue("помечено как поздний shell", step.lateShell)
    }

    @Test
    fun anotherAttemptsTerminalNeverClosesThisAttempt() {
        // terminal ЧУЖОГО пути не является terminal нашей попытки.
        val other = "/data/local/tmp/voiceapp3_lock_restore_other.sh"
        val ports = Ports(
            facts = bindTerminal() + (other to facts("1", done = "rc=0")),
            restoreOutcome = LockOp.RestoreOutcome(true, false, 0, "STOCK_OK:$t1", "")
        )
        val step = LockOp.reconStep(op(restoreEv = attemptA, reason = "restore_outcome_unknown"), ports)
        assertEquals("previous_restore_unresolved", step.reason)
        assertEquals("новая отправка запрещена", 0, ports.restores)
    }

    // ─────────────── 3. production: ограниченный и дедуплицированный reread ───────────────

    @Test
    fun productionRereadIsBoundedAndDeduplicated() {
        val src = File("app/src/main/java/com/example/voiceapp3/tools/LockSound.kt").readText()
        assertTrue("предел объявлен", src.contains("REREAD_MAX_PER_OP"))
        assertTrue("очередь чтений — по операциям, а не глобальный флаг",
            src.contains("rereadState[it]"))
        assertTrue("дубль отложенного прохода отсекается", src.contains("action=dedup"))
        assertTrue("исчерпание предела названо, а не замаскировано",
            src.contains("action=exhausted"))
        assertTrue("флаг снимается в finally (иначе dedup стал бы вечным)",
            src.contains("finally {\n                    synchronized(rereadState) { rereadScheduled = false }"))
        assertTrue("пауза растёт с номером попытки", src.contains("REREAD_DELAY_MS * attempt"))
    }

    // ─────────────── 4. production: finishOpJournal не Unit, DONE требует записи ───────────────

    @Test
    fun finishOpJournalReportsWhetherDoneIsDurable() {
        val src = File("app/src/main/java/com/example/voiceapp3/tools/LockSound.kt").readText()
        val i = src.indexOf("internal fun finishOpJournal(")
        assertTrue("функция найдена", i > 0)
        val head = src.substring(i, src.indexOf('\n', src.indexOf(')', src.indexOf("reason: String", i))))
        assertTrue("возвращает Boolean, а не Unit: $head", head.contains("): Boolean"))
        assertTrue("вызывающий проверяет результат", src.contains("val journalOk = finishOpJournal("))
        assertTrue("незакреплённый DONE делает операцию незавершённой",
            src.contains("journal_not_persisted:"))
        assertFalse("нет молчаливого «готово» без записи",
            Regex("finishOpJournal\\([^)]*\\)\\s*\\n\\s*AppLog\\.i\\(appCtx, TAG, \"LOCKSOUND_RECONCILE")
                .containsMatchIn(src))
    }

    // ─────────────── 5. §5.4 матрица: caller → фаза → исход → действие ───────────────

    @Test
    fun soundRecoveryMatrixGatesOnUnresolvedOperations() {
        // Пока хоть одна операция не разрешена, агрегатор не объявляет готовность и не шлёт bind.
        val pending = LockOp.recoveryGate(listOf("bind_60_x"), null, emptyList(), false)
        assertTrue("есть незавершённая операция ⇒ не Ready: $pending",
            pending !is LockOp.SoundRecovery.Ready)
        val lateShell = LockOp.recoveryGate(emptyList(), null, listOf("bind_60_x"), false)
        assertTrue("поздний shell ⇒ не Ready: $lateShell", lateShell !is LockOp.SoundRecovery.Ready)
        val clean = LockOp.recoveryGate(emptyList(), null, emptyList(), false)
        // `null` — это и есть «шлюз открыт» (можно планировать bind); Ready объявляет проход,
        // который прочитает факт применения, а не решатель по журналу.
        assertNull("без претензий шлюз открыт", clean)
    }
}
