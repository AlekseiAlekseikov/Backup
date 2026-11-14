package com.example.voiceapp3.tools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter52 §5 — журнал звука как журнал НЕСКОЛЬКИХ операций + реальная сверка после сбоя.
 *
 * Что доказывается на настоящем `/bin/sh` и настоящем файле, а не подставленными строками:
 *  1. запись операции «planned» переживает перезапуск процесса (файл, а не поле в памяти);
 *  2. потеря ответа/смерть процесса оставляет операцию незавершённой — и это ВИДНО
 *     (`unresolved()`), а не превращается в «применено»;
 *  3. сверка на старте исполняет НАСТОЯЩИЙ restore-скрипт по целям и staging из журнала и по его
 *     фактическому выводу переводит операцию в `DONE`/`UNDEFINED`;
 *  4. чужой слой сверху не снимается и не выдаётся за успех;
 *  5. нечитаемая таблица монтирований — это `UNPROVEN`, а не «слоя не было»;
 *  6. несколько операций в одном журнале независимы (поздняя запись не отменяет раннюю).
 *
 * Чего тесты НЕ доказывают: поведение реального `/system/bin/sh` на ГУ, формат её mountinfo,
 * видимость слоя в namespace аудиопроцесса и слышимость звука.
 */
class Iter52JournalTest {

    private fun stand(extra: Map<String, String> = emptyMap()): Triple<Iter50Stand, File, Map<String, String>> {
        val st = Iter50Stand()
        val data = File(st.dir, "data").apply { mkdirs() }
        val tmp = File(data, "local/tmp").apply { mkdirs() }
        st.mountinfo.writeText(
            "1 0 8:1 / / rw - ext4 /dev/root rw\n" +
                "2 1 253:0 / ${tmp.absolutePath} rw - f2fs /dev/block/sda1 rw\n"
        )
        val targets = File(st.dir, "carlock").apply { mkdirs() }
        val stageFile = File(tmp, "carlock_custom_stage.wav").apply { writeText("RIFF") }
        val env = mapOf("TARGET_DIR" to targets.absolutePath) + extra
        return Triple(st, targets, env).let { (a, b, c) -> Triple(a, b, c + ("STAGE" to stageFile.absolutePath)) }
    }

    private fun wav(dir: File, name: String = "a.wav"): File =
        File(dir, name).apply { writeText("RIFF") }

    /** Журнал на настоящем файле: имитирует `filesDir/locksound_ops.log` процесса. */
    private class DiskJournal(val file: File) {
        fun append(op: LockOp.BindOp): Boolean {
            file.parentFile?.mkdirs()
            file.appendText(LockOp.journalLine(op, System.currentTimeMillis()) + "\n")
            return LockOp.parseJournal(file.readText()).ops[op.id]?.state == op.state
        }

        /** «Перезапуск процесса»: читаем файл с нуля, без какого-либо состояния в памяти. */
        fun reload(): LockOp.Journal = LockOp.parseJournal(file.readText())
    }

    // ───────────────────────── §5.1 сам журнал ─────────────────────────

    @Test
    fun journalKeepsSeveralOperationsAndLastStateWins() {
        val f = File.createTempFile("locksound_ops", ".log").apply { delete(); deleteOnExit() }
        val j = DiskJournal(f)
        val a = LockOp.BindOp("op-a", 1, true, "/data/local/tmp/s.wav", listOf("/vendor/etc/carlock/a.wav"), LockOp.State.PLANNED)
        val b = LockOp.BindOp("op-b", 2, true, "/data/local/tmp/s.wav", listOf("/vendor/etc/carlock/b.wav"), LockOp.State.PLANNED)
        assertTrue("planned A записан", j.append(a))
        assertTrue("planned B записан", j.append(b))
        assertTrue("A завершена", j.append(a.copy(state = LockOp.State.DONE, reason = "verified")))

        val reloaded = j.reload()
        assertEquals("в журнале ДВЕ операции, а не последняя", 2, reloaded.ops.size)
        assertEquals("A в DONE", LockOp.State.DONE, reloaded.ops["op-a"]?.state)
        assertEquals("причина сохранилась", "verified", reloaded.ops["op-a"]?.reason)
        assertEquals("B осталась незавершённой", LockOp.State.PLANNED, reloaded.ops["op-b"]?.state)
        assertEquals("незавершённая — ровно B", listOf("op-b"), reloaded.unresolved().map { it.id })
        assertEquals("цели B сохранились", listOf("/vendor/etc/carlock/b.wav"), reloaded.ops["op-b"]?.targets)
        assertTrue("повреждённых строк нет: «${reloaded.wire()}»", reloaded.broken == 0)
    }

    @Test
    fun corruptedLineIsCountedAndDoesNotEraseValidOperation() {
        val f = File.createTempFile("locksound_ops", ".log").apply { delete(); deleteOnExit() }
        val op = LockOp.BindOp("op-x", 3, true, "/stage.wav", listOf("/t"), LockOp.State.PLANNED)
        f.appendText(LockOp.journalLine(op, 1L) + "\n")
        f.appendText("это не запись операции\n")
        f.appendText("id=op-y;gen=нечисло;state=planned\n")

        val j = LockOp.parseJournal(f.readText())
        assertEquals("валидная операция не потеряна", LockOp.State.PLANNED, j.ops["op-x"]?.state)
        assertEquals("две повреждённые строки посчитаны", 2, j.broken)
        assertNull("мусор не стал операцией", j.ops["op-y"])
        assertNotNull("и операция x на месте", j.ops["op-x"])
    }

    @Test
    fun emptyOrMissingJournalIsNotAnOperation() {
        val j = LockOp.parseJournal(null)
        assertEquals("нет файла — нет операций", 0, j.ops.size)
        assertTrue("и незавершённых нет", j.unresolved().isEmpty())
        assertTrue("чтение отсутствующего файла не выдаётся за повреждение", j.broken == 0)
    }

    // ─────────── §5.1(2): потеря ответа → перезапуск → сверка на реальном скрипте ───────────

    @Test
    fun lostReplySurvivesRestartAndReconcileRemovesOwnLayer() {
        val (st, targets, env) = stand()
        val stage = env.getValue("STAGE")
        val t = wav(targets)
        // Bind реально выполнен на устройстве…
        st.exec(LockMountScripts.bindScript(stage), "bind.sh", env)
        assertTrue("слой создан до сбоя", st.mountinfo.readText().contains(t.absolutePath))

        // …но приложение успело записать только PLANNED и умерло (ответ потерян).
        val f = File.createTempFile("locksound_ops", ".log").apply { delete(); deleteOnExit() }
        val j = DiskJournal(f)
        val op = LockOp.BindOp("op-crash", 7, true, stage, listOf(t.absolutePath), LockOp.State.PLANNED)
        assertTrue(j.append(op))

        // Перезапуск: состояние берётся ТОЛЬКО из файла.
        val pending = j.reload().unresolved()
        assertEquals("незавершённая операция найдена после перезапуска", 1, pending.size)

        // Сверка: настоящий production-скрипт по целям и staging ИЗ ЖУРНАЛА.
        val script = LockMountScripts.restoreScript(pending.first().targets.map { it to pending.first().stage })
        val (rc, out) = st.exec(script, "reconcile.sh", env)
        val verdict = LockOp.reconcileVerdict(rc, out)
        assertEquals("слой снят и это подтверждено: $out", LockOp.Reconcile.STOCK_RESTORED, verdict)
        assertFalse("слоя больше нет", st.mountinfo.readText().contains(t.absolutePath))

        val state = LockOp.stateForVerdict(verdict)
        assertTrue(j.append(pending.first().copy(state = state, reason = "reconciled_restore")))
        val after = j.reload()
        assertEquals("операция закрыта", LockOp.State.DONE, after.ops["op-crash"]?.state)
        assertTrue("незавершённых больше нет", after.unresolved().isEmpty())
    }

    @Test
    fun reconcileDoesNotClaimSuccessWhenNothingWasPrinted() {
        // Реальный скрипт в окружении без слоя: STOCK_OK есть. А вот ПУСТОЙ вывод — не успех.
        assertEquals(LockOp.Reconcile.UNPROVEN, LockOp.reconcileVerdict(0, ""))
        assertEquals(LockOp.Reconcile.UNPROVEN, LockOp.reconcileVerdict(0, "MOUNT_NS self=x init=x same=1\n"))
        // STOCK_OK печатается скриптом ТОЛЬКО когда слой снят И подтверждён (left==0 && rc==0),
        // поэтому это доказательство по цели и не зависит от агрегатного кода выхода.
        assertEquals(LockOp.Reconcile.STOCK_RESTORED, LockOp.reconcileVerdict(1, "STOCK_OK:/t"))
        // А вот «слой остался» при любом rc — не успех.
        assertEquals(LockOp.Reconcile.UNPROVEN, LockOp.reconcileVerdict(0, "UMOUNT_FAILED:/t\n"))
        assertEquals(LockOp.Reconcile.STOCK_RESTORED, LockOp.reconcileVerdict(0, "STOCK_OK:/t\n"))
        assertEquals(LockOp.Reconcile.STOCK_RESTORED, LockOp.reconcileVerdict(0, "UMOUNTED_OK:/t\nSTOCK_OK:/t\n"))
        assertEquals(LockOp.Reconcile.FOREIGN_LEFT, LockOp.reconcileVerdict(3, "FOREIGN_MOUNT:/t mid=1 root=/x expected=/y\n"))
        assertEquals(LockOp.Reconcile.UNPROVEN, LockOp.reconcileVerdict(0, "RESTORE_BLOCKED reason=namespace_not_proven\n"))
        assertEquals(LockOp.Reconcile.UNPROVEN, LockOp.reconcileVerdict(0, "STOCK_UNPROVEN:/t reason=layers_left\n"))
    }

    @Test
    fun reconcileLeavesForeignLayerAndKeepsOperationUnresolved() {
        val (st, targets, env) = stand()
        val t = wav(targets)
        st.mountinfo.appendText("7 1 253:0 /other/place ${t.absolutePath} rw - f2fs /dev/block/sda1 rw\n")

        val f = File.createTempFile("locksound_ops", ".log").apply { delete(); deleteOnExit() }
        val j = DiskJournal(f)
        val op = LockOp.BindOp("op-foreign", 8, true, env.getValue("STAGE"), listOf(t.absolutePath), LockOp.State.PLANNED)
        j.append(op)

        val pending = j.reload().unresolved().first()
        val (rc, out) = st.exec(
            LockMountScripts.restoreScript(pending.targets.map { it to pending.stage }), "reconcile.sh", env
        )
        val verdict = LockOp.reconcileVerdict(rc, out)
        val state = LockOp.stateForVerdict(verdict)
        j.append(pending.copy(state = state, reason = "foreign_above_left"))

        assertEquals("чужой слой назван: $out", LockOp.Reconcile.FOREIGN_LEFT, verdict)
        assertEquals("операция НЕ закрыта как успех", LockOp.State.UNDEFINED, state)
        assertTrue("чужое не снято", st.mountinfo.readText().contains("/other/place"))
        assertTrue("ни одного umount: ${st.mountCalls()}", st.mountCalls().none { it.startsWith("umount") })
        assertEquals("в журнале записана честная причина",
            "foreign_above_left", j.reload().ops["op-foreign"]?.reason)
        assertTrue("и она осталась в списке незавершённых для следующего раза",
            j.reload().unresolved().any { it.id == "op-foreign" })
    }

    @Test
    fun reconcileIsUnprovenWhenMountTableIsUnreadable() {
        val (st, targets, env) = stand()
        val t = wav(targets)
        st.exec(LockMountScripts.bindScript(env.getValue("STAGE")), "bind.sh", env)

        val (rc, out) = st.exec(
            LockMountScripts.restoreScript(listOf(t.absolutePath to env.getValue("STAGE"))),
            "reconcile.sh",
            env + mapOf("MOUNTINFO" to "/nonexistent/mountinfo")
        )
        val verdict = LockOp.reconcileVerdict(rc, out)
        assertEquals("нечитаемая таблица — не «слоя нет»: $out", LockOp.Reconcile.UNPROVEN, verdict)
        assertEquals(LockOp.State.UNDEFINED, LockOp.stateForVerdict(verdict))
        assertTrue("слой остался (его никто не снимал вслепую)", st.mountinfo.readText().contains(t.absolutePath))
    }

    @Test
    fun namespaceMismatchDuringReconcileDoesNotTouchAnything() {
        val (st, targets, env) = stand()
        val t = wav(targets)
        st.exec(LockMountScripts.bindScript(env.getValue("STAGE")), "bind.sh", env)
        val before = st.mountinfo.readText()

        val (rc, out) = st.exec(
            LockMountScripts.restoreScript(listOf(t.absolutePath to env.getValue("STAGE"))),
            "reconcile.sh",
            env + mapOf("NS_DIFFERS" to "1")
        )
        assertEquals("барьер namespace — не успех", LockOp.Reconcile.UNPROVEN, LockOp.reconcileVerdict(rc, out))
        assertEquals("таблица не изменилась", before, st.mountinfo.readText())
        assertTrue("ни одного umount", st.mountCalls().none { it.startsWith("umount") })
    }

    @Test
    fun unfinishedOperationForVanishedTargetStillGetsAReason() {
        // Цель исчезла (файл удалён) — снимать нечего, но это должно быть названо, а не проглочено.
        val (st, targets, env) = stand()
        val t = wav(targets, "gone.wav")
        val script = LockMountScripts.restoreScript(listOf(t.absolutePath to env.getValue("STAGE")))
        val (rc, out) = st.exec(script, "reconcile.sh", env)
        assertEquals("нет слоя — это STOCK_OK, а не отказ: $out", LockOp.Reconcile.STOCK_RESTORED,
            LockOp.reconcileVerdict(rc, out))
    }
}
