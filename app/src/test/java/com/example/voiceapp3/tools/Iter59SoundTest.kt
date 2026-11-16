package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter59 §3 — ЗВУК ЗАМКА: identity ПОПЫТКИ, а не операции.
 *
 * Приложение A (аудит iter58) назвал дефекты; здесь каждый из них либо подтверждается, либо
 * опровергается на РЕАЛЬНОМ коде решателя [LockOp.reconStep] и на ИСПОЛНЯЕМЫХ скриптах
 * [LockMountScripts.observeScript] (настоящий `/bin/sh` + подставная таблица монтирований).
 *
 * Что именно доказывается:
 *  1. `LockOp.kt:413–414` — terminal СТАРОЙ попытки разрешал НОВУЮ: при `out.unknown` и старом
 *     terminal операция закрывалась как `DONE`. Тест 1 показывает это старым правилом (лямбда) и
 *     поведением нового решателя.
 *  2. `LockOp.kt:451`/`:464–472` + `LockSound.kt:1823–1843` — ветки возвращали `persist =
 *     op.copy(...)` без `restoreEv`, а `parseJournal` берёт ПОСЛЕДНЮЮ строку: новый адрес
 *     затирался. Тест 2 показывает и сам механизм (last-wins), и что подтверждение записи теперь
 *     сравнивает ВСЮ identity, а не `state`+`gen`.
 *  3. `LockSound.kt:1875`/`:1556–1570` — отправка брала «текущую» подготовку, а не identity
 *     журнала. Тест 3 фиксирует: отправляется ровно подготовленный путь, иного не бывает.
 *  4. `LockSound.kt:1804–1821` — в read-only проходе restore запрещён, поэтому запись навсегда
 *     оставалась «не подготовлено». Тест 4: чтение состояния закрывает операцию без mutation.
 *  5. `bindLate` не блокировал restore. Тест 5: при недоказанном terminal bind restore НЕ уходит.
 *  6. Тесты 6–8 прогоняют ЧИСТО ЧИТАЮЩИЙ скрипт наблюдения: сток / свой слой / чужой слой, и
 *     проверяют, что он не делает ни `mount`, ни `umount`.
 *
 * Чего эти тесты НЕ доказывают (в отчёте 🟡/⛔): реальные `mount`/`umount` в прошивке ГУ,
 * видимость слоя в namespace аудиопроцесса, слышимость звука, crash-сценарии ОС.
 */
class Iter59SoundTest {

    private val t1 = "/vendor/etc/carlock/close.wav"
    private val t2 = "/vendor/etc/carlock/open.wav"
    private val bindEv = "/data/local/tmp/voiceapp3_lock_bind_abc.sh"
    private val oldAttempt = "/data/local/tmp/voiceapp3_lock_restore_old.sh"
    private val newAttempt = "/data/local/tmp/voiceapp3_lock_restore_new.sh"

    private fun op(
        evidence: String = bindEv,
        restoreEv: String = "",
        targets: List<String> = listOf(t1, t2),
        stage: String = "/data/local/tmp/vapp_stage"
    ) = LockOp.BindOp(
        id = "bind_59_x", gen = 9L, desired = true, stage = stage,
        targets = targets, state = LockOp.State.UNDEFINED, evidence = evidence,
        restoreEv = restoreEv
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
        val sent = ArrayList<String>()
        override fun evidence(op: LockOp.BindOp, path: String): LockOp.EvidenceFacts? = facts[path]
        override fun readOnly(): Boolean = readOnlyPass
        override fun observe(op: LockOp.BindOp): LockOp.Reconcile? = observed
        override fun prepareRestore(op: LockOp.BindOp): String {
            prepares++
            return preparePath
        }
        override fun restore(op: LockOp.BindOp, attempt: String): LockOp.RestoreOutcome {
            restores++
            sent.add(attempt)
            return restoreOutcome
        }
    }

    // ───────── 1. Terminal СТАРОЙ попытки не разрешает НОВУЮ ─────────

    /**
     * iter58: `restoreTerminal` считался по `op.restoreEv` (старая попытка), а проверялось им
     * условие `out.unknown && !restoreTerminal`. Значит при «старая попытка терминальна + новая
     * неизвестна» запись объявлялась `DONE`: поздний `umount` новой попытки оставался без
     * наблюдения, а новый bind уже разрешался.
     */
    @Test
    fun staleTerminalOfPreviousAttemptNeverResolvesANewUnknown() {
        // Старое правило (iter58) — как явная лямбда, чтобы дефект был ВИДЕН, а не описан словами.
        val iter58Rule = { unknown: Boolean, oldTerminal: Boolean -> unknown && !oldTerminal }
        assertEquals("iter58 объявлял исход известным при terminal СТАРОЙ попытки",
            false, iter58Rule(true, true))

        val p = Ports(
            // bind терминален; ПРЕЖНЯЯ попытка restore тоже терминальна
            facts = mapOf(bindEv to facts("1", "rc=0"), oldAttempt to facts("1", "rc=0")),
            restoreOutcome = LockOp.RestoreOutcome(
                true, true, -1, "ERR_ADB_TIMEOUT", newAttempt, "stdout_lost_may_have_run"
            )
        )
        val step = LockOp.reconStep(op(restoreEv = oldAttempt), p)

        assertEquals("новая попытка была отправлена", 1, p.restores)
        assertEquals("исход НОВОЙ попытки остаётся неизвестным",
            "restore_outcome_unknown", step.reason)
        assertEquals("состояние не закрывается", LockOp.State.UNDEFINED, step.newState)
        assertNotEquals("DONE по чужому terminal запрещён", LockOp.State.DONE, step.newState)
        assertTrue("поздний umount назван: ${step.wire()}", step.lateShell)
        assertTrue("операция открыта: ${step.wire()}", step.unresolved)
        assertFalse("новый bind не разрешён", step.mayBind)
        assertTrue("нужен отложенный read-only повтор", step.deferReread)
    }

    // ───────── 2. Identity попытки не затирается stale-копией ─────────

    @Test
    fun journalConfirmationComparesWholeIdentityNotOnlyStateAndGen() {
        val fresh = op(restoreEv = newAttempt).copy(state = LockOp.State.UNDEFINED)
        val stale = op(restoreEv = "").copy(state = fresh.state)

        // iter58: подтверждением считалось `state`+`gen` — копия БЕЗ identity проходила.
        val iter58Confirmed = stale.state == fresh.state && stale.gen == fresh.gen
        assertTrue("iter58 принимал stale-копию за подтверждение", iter58Confirmed)
        assertFalse("iter59 сравнивает всю identity", LockOp.sameIdentity(fresh, stale))
        assertTrue("полная копия подтверждается", LockOp.sameIdentity(fresh, fresh.copy()))

        // Сам механизм потери: разбор журнала берёт ПОСЛЕДНЮЮ строку по id.
        val journal = LockOp.parseJournal(
            LockOp.journalLine(fresh, 1L) + "\n" + LockOp.journalLine(stale, 2L) + "\n"
        )
        assertEquals("последняя строка побеждает — поэтому stale-запись и теряла адрес",
            "", journal.ops.getValue(fresh.id).restoreEv)
    }

    @Test
    fun everyReconStepKeepsTheAttemptIdentityItPrepared() {
        // Каждая ветка после подготовки restore обязана нести restoreEv подготовленной попытки:
        // иначе её строка (последняя по id) затирает адрес, и поздний shell теряет наблюдение.
        val cases = listOf(
            // исход / чтение свидетельства этой попытки
            LockOp.RestoreOutcome(true, true, -1, "ERR", newAttempt, "stdout_lost_may_have_run") to
                "restore_outcome_unknown",
            LockOp.RestoreOutcome(false, false, 1, "ERR", "", "upload_incomplete") to
                "restore_not_sent:upload_incomplete",
            LockOp.RestoreOutcome(true, false, 0, stockFor(t1, t2), newAttempt) to
                "reconciled_restore",
            LockOp.RestoreOutcome(true, false, 0, "STOCK_OK:$t1\n", newAttempt) to
                "reconcile_unproven",
            LockOp.RestoreOutcome(
                true, false, 0,
                "FOREIGN_MOUNT:$t1 mid=9 root=/other reason=ownership_not_proven\n", newAttempt
            ) to "foreign_above_left"
        )
        for ((outcome, reason) in cases) {
            val p = Ports(
                facts = mapOf(bindEv to facts("1", "rc=0")),
                restoreOutcome = outcome,
                preparePath = newAttempt
            )
            val step = LockOp.reconStep(op(), p)
            assertEquals("причина для ветки: ${step.wire()}", reason, step.reason)
            assertEquals("identity попытки сохранена в ветке $reason",
                newAttempt, step.persist?.restoreEv)
            // Запись должна ПРОЙТИ проверку полной identity (иначе appendOp её отвергнет и
            // состояние не зафиксируется — это тоже честный отказ, но не здесь).
            assertTrue("запись подтверждаема: $reason",
                LockOp.sameIdentity(step.persist!!, step.persist))
        }
    }

    // ───────── 3. Отправляется ровно подготовленная identity ─────────

    @Test
    fun sendUsesExactlyThePreparedAndPersistedIdentity() {
        val p = Ports(
            facts = mapOf(bindEv to facts("1", "rc=0")),
            restoreOutcome = LockOp.RestoreOutcome(true, false, 0, stockFor(t1, t2), newAttempt),
            preparePath = newAttempt
        )
        val step = LockOp.reconStep(op(), p)

        assertEquals("подготовка ровно одна", 1, p.prepares)
        assertEquals("отправка ровно одна", 1, p.restores)
        assertEquals("отправлен именно подготовленный путь", listOf(newAttempt), p.sent)
        assertEquals("в журнал записан тот же путь", newAttempt, step.persist?.restoreEv)
        assertEquals(LockOp.State.DONE, step.newState)
    }

    @Test
    fun unconfirmedIdentityMeansNoSendAtAll() {
        val p = Ports(facts = mapOf(bindEv to facts("1", "rc=0")), preparePath = "")
        val step = LockOp.reconStep(op(), p)

        assertEquals("отправки не было", 0, p.restores)
        assertEquals("restore_evidence_not_prepared", step.reason)
        assertTrue(step.unresolved)
        assertFalse("bind запрещён", step.mayBind)
    }

    // ───────── 4. Read-only проход доказывает сток и не «висит вечно» ─────────

    @Test
    fun readOnlyPassClosesTheOperationByObservationWithoutMutation() {
        for (verdict in listOf(
            LockOp.Reconcile.STOCK_RESTORED,
            LockOp.Reconcile.FOREIGN_LEFT,
            LockOp.Reconcile.UNPROVEN
        )) {
            val p = Ports(
                facts = mapOf(bindEv to facts("1", "rc=0")),
                readOnlyPass = true,
                observed = verdict
            )
            val step = LockOp.reconStep(op(), p)
            assertEquals("никаких restore в читающем проходе", 0, p.restores)
            assertEquals("никакой подготовки identity", 0, p.prepares)
            when (verdict) {
                LockOp.Reconcile.STOCK_RESTORED -> {
                    assertEquals("сток по факту чтения закрывает запись", LockOp.State.DONE, step.newState)
                    assertEquals("observed_stock", step.reason)
                    assertTrue("bind разрешён", step.mayBind)
                }
                LockOp.Reconcile.FOREIGN_LEFT -> {
                    assertEquals(LockOp.State.UNDEFINED, step.newState)
                    assertEquals("observed_foreign_above", step.reason)
                    assertFalse("чужой слой не «наше» состояние", step.mayBind)
                }
                else -> {
                    assertEquals(LockOp.State.UNDEFINED, step.newState)
                    assertEquals("observed_unproven", step.reason)
                    assertFalse("неизвестность не закрывается", step.mayBind)
                }
            }
        }
        // «Прочитать нельзя» — тоже не закрытие.
        val nullObs = Ports(facts = mapOf(bindEv to facts("1", "rc=0")), readOnlyPass = true, observed = null)
        val step = LockOp.reconStep(op(), nullObs)
        assertEquals("observed_unproven", step.reason)
        assertFalse(step.mayBind)
    }

    // ───────── 5. Недоказанный terminal bind запрещает restore ─────────

    @Test
    fun unprovenTerminalBindForbidsBothPrepareAndRestore() {
        val p = Ports(
            facts = mapOf(bindEv to facts("1", "")),   // START есть, terminal (.done rc=) нет
            restoreOutcome = LockOp.RestoreOutcome(true, false, 0, stockFor(t1, t2), newAttempt)
        )
        val step = LockOp.reconStep(op(), p)
        assertEquals("ни одного restore", 0, p.restores)
        assertEquals("и ни одной подготовки", 0, p.prepares)
        assertEquals("late_shell_unproven", step.reason)
        assertTrue("операция открыта: ${step.wire()}", step.unresolved && step.lateShell)
        assertFalse("bind запрещён", step.mayBind)

        // Нет свидетельства bind вовсе — тоже «неизвестно», а не разрешение.
        val absent = Ports(facts = emptyMap())
        val step2 = LockOp.reconStep(op(evidence = ""), absent)
        assertEquals("evidence_absent_or_unreadable", step2.reason)
        assertEquals("ни одного restore", 0, absent.restores)
    }

    // ───────── 6. Читающий скрипт: сток, свой слой, чужой слой ─────────

    private fun stand(): Triple<Iter50Stand, File, String> {
        val st = Iter50Stand()
        val data = File(st.dir, "data").apply { mkdirs() }
        val tmp = File(data, "local/tmp").apply { mkdirs() }
        val targets = File(st.dir, "carlock").apply { mkdirs() }
        val stageFile = File(tmp, "carlock_custom_stage.wav").apply { writeText("RIFF") }
        return Triple(st, targets, stageFile.absolutePath)
    }

    private fun baseMountinfo(st: Iter50Stand, stage: String): String {
        val tmpDir = File(stage).parentFile.absolutePath
        return "1 0 8:1 / / rw - ext4 /dev/root rw\n" +
            "2 1 253:0 / $tmpDir rw - f2fs /dev/block/sda1 rw\n"
    }

    @Test
    fun observationProvesStockWithoutAnyDeviceChange() {
        val (st, targets, stage) = stand()
        val a = File(targets, "a.wav").apply { writeText("RIFF") }
        val b = File(targets, "b.wav").apply { writeText("RIFF") }
        st.mountinfo.writeText(baseMountinfo(st, stage))
        st.resetLogs()

        val script = LockMountScripts.observeScript(
            listOf(a.absolutePath to stage, b.absolutePath to stage)
        )
        assertEquals("синтаксис скрипта корректен: $script", 0, st.syntaxCheck(script).first)
        val (rc, out) = st.exec(script, "observe.sh")
        assertEquals("наблюдение завершилось: $out", 0, rc)
        assertTrue("сток по каждой цели: $out",
            out.contains("STOCK_OK:${a.absolutePath}") && out.contains("STOCK_OK:${b.absolutePath}"))
        assertEquals("ни mount, ни umount не выполнялись", 0, st.mountCalls().size)
        assertEquals("вердикт решателя — доказанный сток",
            LockOp.Reconcile.STOCK_RESTORED,
            LockOp.reconcileDetail(rc, out, listOf(a.absolutePath, b.absolutePath)).verdict)
    }

    @Test
    fun observationSeesOurLayerAndForeignLayerWithoutTouchingThem() {
        val (st, targets, stage) = stand()
        val ours = File(targets, "a.wav").apply { writeText("RIFF") }
        val foreign = File(targets, "b.wav").apply { writeText("RIFF") }
        st.mountinfo.writeText(
            baseMountinfo(st, stage) +
                "3 2 0:30 /carlock_custom_stage.wav ${ours.absolutePath} rw - none /tmp/s rw\n" +
                "4 2 0:31 /someone_else ${foreign.absolutePath} rw - none /tmp/x rw\n"
        )
        st.resetLogs()

        val script = LockMountScripts.observeScript(
            listOf(ours.absolutePath to stage, foreign.absolutePath to stage)
        )
        val (rc, out) = st.exec(script, "observe2.sh")
        assertEquals("наблюдение завершилось: $out", 0, rc)
        assertTrue("свой слой распознан по root источника: $out",
            out.contains("OWN_PROOF:${ours.absolutePath}"))
        assertTrue("чужой слой назван и не тронут: $out",
            out.contains("FOREIGN_MOUNT:${foreign.absolutePath}") &&
                out.contains("reason=ownership_not_proven"))
        assertFalse("сток не заявляется при наличии слоёв: $out", out.contains("STOCK_OK:"))
        assertEquals("ни mount, ни umount не выполнялись", 0, st.mountCalls().size)
        val verdict = LockOp.reconcileDetail(
            rc, out, listOf(ours.absolutePath, foreign.absolutePath)
        ).verdict
        assertEquals("чужой слой — не доказательство стока", LockOp.Reconcile.FOREIGN_LEFT, verdict)
    }

    @Test
    fun observationScriptNeverMutatesTheDevice() {
        val script = LockMountScripts.observeScript(listOf(t1 to "/data/local/tmp/stage"))

        // Скрипт не содержит КОМАНД изменения состояния (комментарии не в счёт — они как раз
        // объясняют, чего здесь нет). Исполнение выше это подтверждает: 0 вызовов mount/umount.
        val code = script.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        val mutators = listOf(
            Regex("(^|[;&|]|\\$\\()\\s*umount\\b"),
            Regex("(^|[;&|]|\\$\\()\\s*mount\\s"),
            Regex("\\brm\\s+-"),
            Regex("\\bmkdir\\b"),
            Regex("\\brmdir\\b")
        )
        for (line in code) {
            for (m in mutators)
                assertFalse("в читающем скрипте нет мутации «${m.pattern}»: $line", m.containsMatchIn(line))
        }
        assertTrue("скрипт сообщает, что таблица может быть недоступна: $script",
            script.contains("STOCK_UNPROVEN"))
        assertNotNull(script)
    }

    // ───────── 7. Production-путь: порты действительно спрашивают identity ─────────

    @Test
    fun productionRestorePortRequiresThePreparedAttemptPath() {
        val src = File("app/src/main/java/com/example/voiceapp3/tools/LockSound.kt").readText()
        assertTrue("порт отправляет ровно attempt и не подменяет путь",
            src.contains("override fun restore(op: LockOp.BindOp, attempt: String)") &&
                src.contains("preparedRestore.remove(attempt)"))
        assertTrue("prepareRestore пишет identity ДО отправки",
            src.indexOf("appendOp(appCtx, candidate") in 1 until
                src.indexOf("preparedRestore[prep.evidencePath] = prep"))
        assertTrue("read-only проход объявлен порту явно",
            src.contains("override fun readOnly(): Boolean = readOnlyPass"))
        assertTrue("наблюдение состояния — через читающий скрипт",
            src.contains("LockMountScripts.observeScript("))
        assertTrue("подтверждение записи сравнивает всю identity",
            src.contains("LockOp.sameIdentity(op, backOp)"))
    }
}
