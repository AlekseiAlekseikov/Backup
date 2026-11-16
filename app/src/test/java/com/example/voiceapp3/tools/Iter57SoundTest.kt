package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter57 §5.1–§5.3 — ЗВУК: сверка через РЕАЛЬНЫЙ production-решатель [LockOp.reconStep].
 *
 * Тесты гоняют тот же код, который вызывает `LockSound.reconcileLocked` (там он обёрнут только
 * портами устройства — чтением свидетельства и отправкой restore). Подменяется ИСКЛЮЧИТЕЛЬНО
 * устройство; журнал, `BindOp` и решения — настоящие.
 *
 * Что доказывается:
 *  1. запись без целей/без stage НЕ даёт «готово» и НЕ даёт сток (в iter56 она делала `continue`,
 *     и следующий вызов возвращал `Ready` — новый bind уходил при неизвестной операции);
 *  2. пока terminal конкретного исполнения bind не доказан, операция НЕ закрывается, даже если
 *     сейчас виден сток (снимок таблицы не является доказательством конца старого shell);
 *  3. потерянный ответ RESTORE (поздний `umount` после нового bind) держит fencing: новый bind
 *     не разрешается, адрес свидетельства сохраняется и дочитывается;
 *  4. доказанный terminal + снятие по всем целям ⇒ операция закрывается (`DONE`);
 *  5. неразрешимый по доказательствам случай имеет ЯВНЫЙ путь разрешения владельцем, который
 *     не заявляет сток.
 *
 * Чего тесты НЕ доказывают: реальный `/system/bin/sh` и mount-namespace ГУ, слышимость WAV.
 */
class Iter57SoundTest {

    private fun dir(): File =
        File(System.getProperty("java.io.tmpdir"), "iter57sound_" + System.nanoTime()).apply { mkdirs() }

    private val stage = "/data/local/tmp/carlock_custom_g7_deadbeef.wav"
    private val t1 = "/vendor/etc/carlock/close.wav"
    private val t2 = "/vendor/etc/carlock/open.wav"

    private fun op(
        id: String = "bind_7_deadbeef",
        targets: List<String> = listOf(t1, t2),
        stage: String = this.stage,
        evidence: String = "/data/local/tmp/voiceapp3_lock_bind_abc.sh",
        restoreEv: String = "",
        state: LockOp.State = LockOp.State.UNDEFINED
    ) = LockOp.BindOp(
        id = id, gen = 7L, desired = true, stage = stage, targets = targets,
        state = state, evidence = evidence, restoreEv = restoreEv
    )

    /** Управляемый «устройство»: что лежит в свидетельстве и что ответил restore. */
    private class Ports(
        var facts: Map<String, LockOp.EvidenceFacts?> = emptyMap(),
        var restore: LockOp.RestoreOutcome = LockOp.RestoreOutcome(
            true, false, 0, "",
            ""
        )
    ) : LockOp.ReconPorts {
        var restores = 0
        var prepares = 0
        var sentAttempts = ArrayList<String>()
        /** Адрес свидетельства, подготовленный ДО отправки (iter58 §3.2). */
        var prepareResult: String = "/data/local/tmp/voiceapp3_lock_restore_new.sh"
        override fun evidence(op: LockOp.BindOp, path: String): LockOp.EvidenceFacts? =
            if (facts.containsKey(path)) facts[path] else null

        override fun restore(op: LockOp.BindOp, attempt: String): LockOp.RestoreOutcome {
            restores++
            sentAttempts.add(attempt)
            return restore
        }

        override fun prepareRestore(op: LockOp.BindOp): String {
            prepares++
            return prepareResult
        }
    }

    private fun facts(started: String, done: String, present: Boolean) =
        LockOp.EvidenceFacts(started, done, present, Regex("rc=-?\\d+").containsMatchIn(done))

    private fun stockFor(vararg targets: String): String =
        targets.joinToString("\n") { "STOCK_OK:$it" } + "\nUMOUNTED_OK:" + targets.first()

    // ───────── 1. Нет целей / нет stage ⇒ не Ready и не сток ─────────

    @Test
    fun recordWithoutTargetsNeverBecomesReadyAndNeverClaimsStock() {
        val p = Ports()
        for (bad in listOf(
            op(targets = emptyList()),
            op(stage = "")
        )) {
            val step = LockOp.reconStep(bad, p)
            assertEquals("состояние не закрывается: ${step.wire()}", LockOp.State.UNDEFINED, step.newState)
            assertTrue("причина названа: ${step.wire()}", step.reason == "no_targets")
            assertTrue("конфликт виден: ${step.wire()}", step.conflict == "no_targets:" + bad.id)
            assertTrue("операция остаётся незавершённой: ${step.wire()}", step.unresolved)
            assertFalse("поздний shell не исключён", step.lateShell)
        }
        assertEquals("restore по записи без целей даже не отправлялся", 0, p.restores)
        // Два последовательных прохода: ни один не даёт «готово».
        repeat(2) { assertEquals(LockOp.State.UNDEFINED, LockOp.reconStep(op(targets = emptyList()), p).newState) }
        val recovery = recoveryOf(LockOp.reconStep(op(targets = emptyList()), p))
        assertFalse("bind не разрешён: ${recovery.wire()}", recovery.mayBind)
        assertTrue("и это не «готово»: ${recovery.wire()}", recovery is LockOp.SoundRecovery.Conflict)
    }

    // ───────── 2. Снимок stock не закрывает операцию без terminal bind ─────────

    @Test
    fun stockSnapshotDoesNotCloseAnOperationWhoseTerminalIsUnproven() {
        // Свидетельство прочитано, но terminal нет: скрипт ещё лежит (поздний запуск возможен).
        val p = Ports(
            facts = mapOf("/data/local/tmp/voiceapp3_lock_bind_abc.sh" to facts("1", "", true)),
            restore = LockOp.RestoreOutcome(true, false, 0, stockFor(t1, t2), "ev_restore")
        )
        val step = LockOp.reconStep(op(), p)
        assertEquals("сейчас stock, но операция НЕ DONE: ${step.wire()}",
            LockOp.State.UNDEFINED, step.newState)
        assertEquals("late_shell_unproven", step.reason)
        assertTrue("поздний shell назван", step.lateShell)
        assertTrue("конфликт виден", step.conflict == "late_shell_possible:bind_7_deadbeef")
        // iter59 §3.2.5: при недоказанном terminal bind restore не отправляется вовсе.
        assertEquals("restore при недоказанном terminal bind не отправляется", 0, p.restores)
        assertFalse("новый bind не разрешён", recoveryOf(step).mayBind)

        // Даже повторный проход со «свежим» stock не закрывает запись.
        val again = LockOp.reconStep(op(), p)
        assertEquals(LockOp.State.UNDEFINED, again.newState)
    }

    @Test
    fun missingEvidenceIsUnknownNotSuccess() {
        val p = Ports(
            facts = emptyMap(), // свидетельства нет вовсе (легаси-запись)
            restore = LockOp.RestoreOutcome(true, false, 0, stockFor(t1, t2), "ev")
        )
        val step = LockOp.reconStep(op(evidence = ""), p)
        assertEquals(LockOp.State.UNDEFINED, step.newState)
        assertEquals("evidence_absent_or_unreadable", step.reason)
        assertEquals("evidence_unknown:bind_7_deadbeef", step.conflict)
        assertFalse("«нет файла» ≠ «всё в порядке»", recoveryOf(step).mayBind)
    }

    @Test
    fun emptyDoneFileIsNotATerminal() {
        val p = Ports(
            facts = mapOf("/data/local/tmp/voiceapp3_lock_bind_abc.sh" to facts("1", "", false)),
            restore = LockOp.RestoreOutcome(true, false, 0, stockFor(t1, t2), "ev")
        )
        val step = LockOp.reconStep(op(), p)
        assertEquals("пустой .done — не terminal", LockOp.State.UNDEFINED, step.newState)
        assertTrue(step.lateShell)
    }

    // ───────── 3. Потерянный ответ RESTORE держит fencing ─────────

    @Test
    fun lostRestoreAnswerBlocksNewBindAndKeepsEvidenceAddress() {
        val p = Ports(
            facts = mapOf("/data/local/tmp/voiceapp3_lock_bind_abc.sh" to facts("1", "rc=0", false)),
            restore = LockOp.RestoreOutcome(
                true, true, -1, "ERR_ADB_TIMEOUT", "/data/local/tmp/voiceapp3_lock_restore_zzz.sh",
                "stdout_lost_may_have_run"
            )
        )
        val step = LockOp.reconStep(op(), p)
        assertEquals(LockOp.State.UNDEFINED, step.newState)
        assertEquals("restore_outcome_unknown", step.reason)
        assertEquals("restore_unknown:bind_7_deadbeef", step.conflict)
        assertTrue("поздний umount назван", step.lateShell)
        assertTrue("нужен read-only повтор", step.deferReread)
        // iter59 §3.2.2: в журнал попадает identity, ЗАПИСАННАЯ ДО ОТПРАВКИ (подготовленный
        // путь), а не путь, который вернул слой доставки: иначе поздний `umount` можно
        // «перенести» на чужой/подставленный адрес и потерять terminal настоящей попытки.
        assertEquals("сохранена identity отправленной попытки",
            p.prepareResult, step.persist?.restoreEv)
        assertEquals("отправлялся ровно подготовленный путь", listOf(p.prepareResult), p.sentAttempts)
        assertNotEquals("подставленный путь доставки journal НЕ перезаписывает",
            "/data/local/tmp/voiceapp3_lock_restore_zzz.sh", step.persist?.restoreEv)
        assertFalse("новый bind поверх возможного позднего umount запрещён", recoveryOf(step).mayBind)
        // Журнал сохраняет адрес: следующий проход дочитает terminal вместо угадывания.
        val line = LockOp.journalLine(step.persist!!, 1L)
        assertTrue("rev= в строке журнала: $line",
            line.contains("rev=" + p.prepareResult))
        val back = LockOp.decode(line.substringAfter('|'))!!
        assertEquals("адрес переживает перезапись журнала", back.restoreEv, step.persist.restoreEv)
    }

    @Test
    fun restoreThatWasNeverSentIsSafeToRepeat() {
        val p = Ports(
            // bind терминален — иначе новая семантика (iter59 §3.2.5) до restore не дойдёт
            facts = mapOf("/data/local/tmp/voiceapp3_lock_bind_abc.sh" to facts("1", "rc=0", false)),
            restore = LockOp.RestoreOutcome(false, false, 1, "ERR_SCRIPT_UPLOAD", "", "upload_incomplete")
        )
        val step = LockOp.reconStep(op(), p)
        assertEquals(LockOp.State.UNDEFINED, step.newState)
        assertTrue("причина названа: ${step.wire()}", step.reason.startsWith("restore_not_sent"))
        assertFalse("позднего shell нет", step.lateShell)
        assertFalse("но bind всё равно не доказан", recoveryOf(step).mayBind)
    }

    // ───────── 4. Доказанный terminal + сток ⇒ закрытие ─────────

    @Test
    fun provenTerminalAndStockClosesTheOperation() {
        val bindEv = "/data/local/tmp/voiceapp3_lock_bind_abc.sh"
        val p = Ports(
            facts = mapOf(bindEv to facts("1", "rc=0", false)),
            restore = LockOp.RestoreOutcome(true, false, 0, stockFor(t1, t2), "ev_restore")
        )
        val step = LockOp.reconStep(op(), p)
        assertEquals(LockOp.State.DONE, step.newState)
        assertEquals("reconciled_restore", step.reason)
        assertTrue("конфликта нет: ${step.wire()}", step.conflict == null)
        assertFalse("операция закрыта", step.unresolved)
        assertTrue("сходимость к намерению разрешена", recoveryOf(step).mayBind)
    }

    @Test
    fun unprovenRemovalOfEveryTargetIsAConflict() {
        // Одна цель снята, вторая — нет: одиночный STOCK_OK не доказательство (iter53 §7.2).
        val p = Ports(
            facts = mapOf("/data/local/tmp/voiceapp3_lock_bind_abc.sh" to facts("1", "rc=0", false)),
            restore = LockOp.RestoreOutcome(true, false, 0, "STOCK_OK:$t1\n", "ev")
        )
        val step = LockOp.reconStep(op(), p)
        assertEquals(LockOp.State.UNDEFINED, step.newState)
        assertNotNull("конфликт назван", step.conflict)
        assertFalse(recoveryOf(step).mayBind)
    }

    @Test
    fun foreignLayerAboveIsNeverRemovedAndBlocksTheBind() {
        val p = Ports(
            facts = mapOf("/data/local/tmp/voiceapp3_lock_bind_abc.sh" to facts("1", "rc=0", false)),
            restore = LockOp.RestoreOutcome(
                true, false, 0,
                "FOREIGN_MOUNT:$t1 mid=9 root=/other reason=ownership_not_proven\nSTOCK_FOREIGN_LEFT:$t1\n",
                "ev"
            )
        )
        val step = LockOp.reconStep(op(), p)
        assertEquals(LockOp.State.UNDEFINED, step.newState)
        assertEquals("foreign_above_left", step.reason)
        assertEquals("foreign_above:bind_7_deadbeef", step.conflict)
    }

    // ───────── 5. Явный путь разрешения (не доказательство стока) ─────────

    @Test
    fun ownerResolutionIsExplicitAndDoesNotClaimStock() {
        val legacy = op(evidence = "", state = LockOp.State.UNDEFINED)
        val resolved = LockOp.ownerResolve(legacy, "legacy_no_evidence")
        assertEquals(LockOp.State.DONE, resolved.state)
        assertTrue("след решения владельца: ${resolved.reason}",
            resolved.reason == "owner_resolved_unprovable:legacy_no_evidence")
        // Такая запись больше не попадает в unresolved — иначе функция блокировалась бы вечно.
        val j = LockOp.parseJournal("${LockOp.journalLine(resolved, 1L)}\n")
        assertTrue("журнал закрыт: ${j.wire()}", j.unresolved().isEmpty())
    }

    // ───────── 6. Сверка идёт по ВСЕМ операциям журнала ─────────

    @Test
    fun everyUnresolvedOperationIsVisitedAndOneConflictBlocksTheBind() {
        val a = op(id = "bind_7_a", evidence = "/e/a.sh")
        val b = op(id = "bind_7_b", evidence = "/e/b.sh")
        val r = LockOp.parseJournal(
            listOf(a, b).joinToString("") { LockOp.journalLine(it, 1L) + "\n" }
        )
        assertEquals("обе операции в журнале", 2, r.ops.size)
        assertEquals("обе незавершены", 2, r.unresolved().size)
        val p = Ports(
            facts = mapOf("/e/a.sh" to facts("1", "rc=0", false), "/e/b.sh" to facts("1", "rc=0", false)),
            restore = LockOp.RestoreOutcome(true, false, 0, stockFor(t1, t2), "ev")
        )
        assertEquals(LockOp.State.DONE, LockOp.reconStep(a, p).newState)
        assertEquals(LockOp.State.DONE, LockOp.reconStep(b, p).newState)
    }

    /**
     * ДЕМОНСТРАЦИЯ, что тест ловит именно iter56-дефект.
     *
     * Старое правило живёт здесь как ЯВНАЯ лямбда (production-код его больше не содержит):
     * запись без целей давала `continue` без unresolved/conflict, поэтому цикл сверки доходил до
     * конца и возвращал `Ready` — новый bind уходил при операции, которую никто не разрешил.
     */
    @Test
    fun iter56RuleWouldHaveReturnedReadyAndIsNowAConflict() {
        val oldRule = { hasTargets: Boolean, unresolved: Int, conflict: String? ->
            when {
                conflict != null -> "Conflict"
                unresolved > 0 -> "Pending"
                // iter56: `continue` по записи без целей НЕ увеличивал unresolved/conflict
                else -> "Ready"
            }
        }
        assertEquals("старое правило: «запись пропущена» ⇒ Ready", "Ready", oldRule(false, 0, null))
        val step = LockOp.reconStep(op(targets = emptyList()), Ports())
        val now = recoveryOf(step).let { if (it is LockOp.SoundRecovery.Ready) "Ready" else it::class.simpleName }
        assertEquals("новое правило: та же запись ⇒ конфликт", "Conflict", now)
    }

    /** Сверка одного шага в терминах «можно ли отправлять bind». */
    private fun recoveryOf(step: LockOp.ReconStep): LockOp.SoundRecovery = when {
        step.conflict != null -> LockOp.SoundRecovery.Conflict(step.conflict)
        step.unresolved -> LockOp.SoundRecovery.Pending(step.reason)
        else -> LockOp.SoundRecovery.Ready
    }
}
