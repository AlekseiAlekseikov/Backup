package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Поведение цепочки JIT на подставных интерфейсах (iter37: сохранность снимка, параллельные клики,
 * сверка против нового намерения).
 *
 * Это НЕ интеграционные тесты Android и НЕ проверка на ГУ. Настоящий `AndroidStore` проверяется
 * отдельно — `JitPersistenceTest` (на реалистичной модели `SharedPreferences`).
 */
class JitOrchestratorTest {

    private val logs = mutableListOf<String>()

    @Before
    fun reset() {
        JitOp.resetStateForTest()
        logs.clear()
    }

    private fun log(s: String) {
        synchronized(logs) { logs.add(s) }
    }

    private fun lines(): String = synchronized(logs) { logs.joinToString("\n") }

    private fun stand() = JitIsoStand(rules = 0)

    /**
     * Опознанный снимок прошлой операции: `jit_restaurar` + наша отметка `jit_op_mark`.
     * Без отметки снимок считается неопознанным (legacy) — см. `legacyOrUnknownSnapshotIsNotExecuted`.
     */
    private fun JitFake.Store.seedOp(
        snap: String = "wifi=1",
        want: Boolean = true,
        op: String = "op-prev",
        state: String = JitRef.OpMark.REPLIED
    ) = apply {
        setIn(0, JitRef.KEY_RESTORE, snap)
        setIn(1, JitRef.KEY_RESTORE, snap)
        val mark = JitRef.OpMark.of(op, want, state, 0L).wire()
        setIn(0, JitRef.KEY_MARK, mark)
        setIn(1, JitRef.KEY_MARK, mark)
    }

    // ─────────────────────────── 1. сохранность снимка ───────────────────────────

    @Test
    fun committedAndReadableSnapshotSendsReferenceScript() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        // runInline=0: команда уходит в транспорт и остаётся там (ответа нет — как на реальном
        // канале с таймаутом); важно, что отправлена РОВНО одна строка-скрипт.
        val shell = JitFake.QueueShell(stand(), getprop = "true").apply { runInline = 0 }

        val c = JitOp.click(store, net, shell, want = true, log = ::log)

        assertTrue(c.sent)
        assertNull(c.blocked)
        assertEquals(JitRef.WifiSnapshot(true), c.snap)
        assertEquals(2, c.write!!.attempted)
        assertEquals(2, c.write!!.committed)
        assertEquals(2, c.confirmed)
        assertEquals("wifi=1", c.read!!.value)
        assertTrue(c.read!!.conflicts.isEmpty())
        for (i in 0..1) {
            assertTrue(store.presentIn(i, JitRef.KEY_PEDIDO))
            assertTrue(store.presentIn(i, JitRef.KEY_AUTO))
            assertTrue(store.presentIn(i, JitRef.KEY_RESTORE))
        }
        assertEquals(1, shell.pending.size)
        assertTrue(shell.pending.first().contains("ctl.restart zygote"))
    }

    @Test
    fun allCommitsFalseButValueVisibleInMemoryIsBlocked() {
        // commit=false в обоих хранилищах, но значение видно в памяти: раньше это считалось
        // достаточным и скрипт уходил, хотя после смерти процесса снимок мог пропасть.
        val store = JitFake.Store().apply { commitFails += listOf(0, 1); writeDespiteFalse = true }
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand())

        val c = JitOp.click(store, net, shell, want = true, log = ::log)

        assertEquals("snapshot_not_saved", c.blocked)
        assertEquals(0, c.write!!.committed)
        assertEquals(0, c.confirmed)
        assertTrue("shell не вызван ни разу", shell.pending.isEmpty())
        assertFalse("сеть не выключалась", net.requests.contains("wifi=false"))
        assertTrue(lines().contains("blocked=snapshot_not_saved"))
        assertTrue("видно восстановление прежних значений", lines().contains("prev_restore="))
    }

    @Test
    fun oneStoreCommittedAndReadableIsEnough() {
        val store = JitFake.Store().apply { throwOn += 1 }
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand())
        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertTrue(c.sent)
        assertEquals(1, c.confirmed)
        assertEquals(2, c.write!!.attempted)
        assertTrue(c.write!!.stores.any { it.commit.startsWith("exception") })
        assertEquals("wifi=1", c.read!!.value)
    }

    @Test
    fun singleAvailableStoreReportsOneOfOneNotTwoOfTwo() {
        val store = JitFake.Store(storeCount = 1)
        val net = JitFake.Net(wifi = false)
        val shell = JitFake.QueueShell(stand())
        val c = JitOp.click(store, net, shell, want = false, log = ::log)
        assertTrue(c.sent)
        assertEquals(1, c.write!!.attempted)
        assertEquals("1/1, а не 2/2", 1, c.confirmed)
    }

    @Test
    fun staleCpsBlocksBecauseFutureReadWouldReturnOldValue() {
        // CPS держит старое значение, запись туда не прошла, DPS получил новое.
        val store = JitFake.Store().apply {
            setIn(0, JitRef.KEY_RESTORE, "wifi=0")
            commitFails += 0
        }
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand())

        val c = JitOp.click(store, net, shell, want = true, log = ::log)

        assertTrue("будущее чтение вернуло бы CPS", c.read!!.value == "wifi=0")
        assertEquals("первое хранилище не подтвердило запись — блок до любых команд",
            "snapshot_not_saved", c.blocked)
        assertTrue(shell.pending.isEmpty())
        assertTrue("противоречие видно в логе", lines().contains("conflicts="))
    }

    @Test
    fun staleDpsAlsoBlocksBecauseSnapshotIsAmbiguous() {
        // Обратный случай: CPS новый, DPS старый — значение читается правильное, но снимок
        // неоднозначен (после падения CPS прочитается старое), поэтому отправлять нельзя.
        val store = JitFake.Store().apply {
            setIn(1, JitRef.KEY_RESTORE, "wifi=0")
            commitFails += 1
        }
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand())

        val c = JitOp.click(store, net, shell, want = true, log = ::log)

        assertEquals("wifi=1", c.read!!.value)
        assertEquals("snapshot_conflict", c.blocked)
        assertTrue(c.read!!.conflicts.isNotEmpty())
        assertTrue(shell.pending.isEmpty())
    }

    @Test
    fun partialKeyWriteIsNotConfirmed() {
        // Два ключа записались, третий — нет: чтения одного Wi-Fi-ключа недостаточно.
        val store = JitFake.Store().apply { skipKey = JitRef.KEY_AUTO }
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand())
        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertEquals("snapshot_not_saved", c.blocked)
        assertEquals(0, c.confirmed)
        assertTrue(shell.pending.isEmpty())
    }

    @Test
    fun blockedClickDoesNotErasePreviousUserPreference() {
        val store = JitFake.Store().apply {
            setIn(0, JitRef.KEY_AUTO, false)   // прежняя настройка драйвера
            commitFails += 0
            commitFails += 1
        }
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand())
        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertEquals("snapshot_not_saved", c.blocked)
        assertEquals("perf_auto_otimizar не тронут", false, store.getBool(JitRef.KEY_AUTO))
    }

    @Test
    fun cleanupOnlyTouchesStoresWhereOurWriteSucceeded() {
        // Ни одна запись не прошла: наш `remove` не должен попасть ни в одно хранилище, иначе
        // прежний снимок DPS («wifi=0») будет стёрт неудачной попыткой.
        val store = JitFake.Store().apply {
            setIn(1, JitRef.KEY_RESTORE, "wifi=0")  // прежние данные в DPS
            commitFails += 0
            commitFails += 1
        }
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand())
        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertEquals("snapshot_not_saved", c.blocked)
        assertEquals("прежнее значение DPS на месте", "wifi=0", store.maps[1][JitRef.KEY_RESTORE])
        assertFalse("наш pedido в CPS не остался", store.maps[0].containsKey(JitRef.KEY_PEDIDO))
        assertFalse("наш pedido в DPS не остался", store.maps[1].containsKey(JitRef.KEY_PEDIDO))
        assertTrue("ничего не отправлено", shell.pending.isEmpty())
    }

    @Test
    fun cleanupRemovesOnlyInStoresWhereOurWriteWasDurable() {
        // CPS не записался, DPS записался: чистим только DPS.
        val store = JitFake.Store().apply {
            setIn(0, JitRef.KEY_RESTORE, "wifi=1")  // прежний/чужой снимок в CPS
            commitFails += 0
        }
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand())
        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertEquals("первое по порядку чтения хранилище не подтвердило запись → блок",
            "snapshot_not_saved", c.blocked)
        assertEquals("неудавшаяся запись в CPS не стёрла прежнее там", "wifi=1",
            store.maps[0][JitRef.KEY_RESTORE])
        assertFalse("наша запись повторно снята только в DPS", store.maps[1].containsKey(JitRef.KEY_PEDIDO))
        assertFalse("и снимок в DPS тоже снят", store.maps[1].containsKey(JitRef.KEY_RESTORE))
    }

    @Test
    fun unknownWifiIsBlockedAndNothingIsWritten() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifiUnreadable = true)
        val shell = JitFake.QueueShell(stand())
        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertEquals("wifi_unknown", c.blocked)
        assertEquals(0, store.writeCount())
        assertTrue(shell.pending.isEmpty())
    }

    @Test
    fun lostChannelIsNotLoggedAsSuccess() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand())   // runInline = 0 → 137 / ERR_ADB_TIMEOUT
        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertTrue(c.sent)
        assertEquals(JitRef.ChainOutcome.CHANNEL_LOST, c.outcome)
        assertTrue(lines().contains("reason=channel_lost"))
        assertFalse(lines().contains("result=ok"))
        assertTrue("исход неопределён", JitOp.chainUndefined())
    }

    // ─────────────────────────── 2. параллельные клики ───────────────────────────

    @Test
    fun parallelSecondClickIsRejectedAndOriginalSnapshotKept() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.BlockingShell()
        val done = CountDownLatch(1)
        val second = AtomicReference<JitOp.Click?>(null)

        val t = Thread {
            JitOp.click(store, net, shell, want = true, log = ::log)
            done.countDown()
        }
        t.start()
        assertTrue("первый клик дошёл до транспорта", shell.entered.await(5, TimeUnit.SECONDS))

        // К этому моменту «скрипт уже выключил Wi-Fi» на устройстве.
        net.wifi = false
        second.set(JitOp.click(store, net, JitFake.QueueShell(stand()), want = false, log = ::log))

        assertEquals("busy", second.get()!!.blocked)
        assertFalse("вторая цепочка не отправлена", second.get()!!.sent)
        assertEquals("снимок остался исходный", "wifi=1", store.rawRestore())

        shell.release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        t.join(1000)
        assertEquals("после освобождения слот свободен", false, JitOp.clickBusy())
    }

    @Test
    fun parallelClicksWithOppositeValuesOnlyOneChainStarts() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.BlockingShell()
        val barrier = CyclicBarrier(2)
        val results = arrayOfNulls<JitOp.Click>(2)

        val threads = listOf(true, false).mapIndexed { i, want ->
            Thread {
                barrier.await(5, TimeUnit.SECONDS)
                results[i] = JitOp.click(store, net, shell, want = want, log = ::log)
            }.also { it.start() }
        }
        assertTrue(shell.entered.await(5, TimeUnit.SECONDS))
        Thread.sleep(150)   // второй успевает попробовать слот
        shell.release.countDown()
        threads.forEach { it.join(2_000) }

        val blockedCount = results.count { it?.blocked == "busy" }
        val sentCount = results.count { it?.sent == true }
        assertEquals("ровно один отклонён", 1, blockedCount)
        assertEquals("ровно одна цепочка", 1, sentCount)
        assertEquals("снимков записано один раз", 2, store.ops.count { it.startsWith("snapshot") })
    }

    @Test
    fun timeoutKeepsIntentUntilOutcomeIsDefinite() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand())   // без ответа
        JitOp.click(store, net, shell, want = true, log = ::log)
        assertTrue(JitOp.chainUndefined())
        assertTrue("намерение на месте", store.contains(JitRef.KEY_PEDIDO))

        // Поздняя сверка не снимает намерение, пока исход неопределён.
        val st = JitOp.verifyApplied(store, JitFake.AnswerShell(maps = null), ::log)
        assertEquals("chain_undefined", st.skipped)
        assertTrue(store.contains(JitRef.KEY_PEDIDO))
        assertTrue(lines().contains("kept reason=chain_undefined"))
    }

    @Test
    fun undefinedOutcomeBlocksSecondClickInBothDirections() {
        // Первая команда удерживается в транспорте, ответа нет (таймаут), и Wi-Fi уже изменён ею.
        // Второй клик — в ОБЕ стороны — не отправляет вторую цепочку и не перезаписывает снимок
        // состоянием после `svc wifi disable`.
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val held = JitFake.BlockingShell()
        var first: JitOp.Click? = null
        val done = CountDownLatch(1)
        Thread {
            first = JitOp.click(store, net, held, want = true, log = ::log)
            done.countDown()
        }.start()
        assertTrue(held.entered.await(5, TimeUnit.SECONDS))
        net.wifi = false                        // первая команда уже выключила Wi-Fi
        held.result = 137 to "ERR_ADB_TIMEOUT"  // ответа транспорта нет
        held.release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue("исход неопределён", JitOp.chainUndefined())
        assertNotNull(first)
        assertTrue("первая цепочка ушла", first!!.sent)
        val snapshotAfterFirst = store.rawRestore()
        val writesAfterFirst = store.writeCount()

        val a = JitOp.click(store, net, JitFake.QueueShell(stand()), want = false, log = ::log)
        assertEquals("вторая цепочка отклонена (want=false)", "chain_undefined", a.blocked)
        val b = JitOp.click(store, net, JitFake.QueueShell(stand()), want = true, log = ::log)
        assertEquals("вторая цепочка отклонена (want=true)", "chain_undefined", b.blocked)
        assertEquals("ни одной записи вторым кликом", writesAfterFirst, store.writeCount())
        assertEquals("снимок не перезаписан", snapshotAfterFirst, store.rawRestore())
        assertTrue(lines().contains("blocked=chain_undefined"))
        assertTrue(lines().contains("blocked=chain_undefined action=none commands=0"))
    }

    @Test
    fun lateExecutionOfFirstCommandDoesNotOverwriteOriginalSnapshot() {
        // Позднее исполнение: команда, чей ответ потерян, всё равно отключает Wi-Fi уже после
        // таймаута. Снимок обязан остаться состоянием ДО операции.
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val held = JitFake.BlockingShell()
        val done = CountDownLatch(1)
        Thread {
            JitOp.click(store, net, held, want = true, log = ::log)
            done.countDown()
        }.start()
        assertTrue(held.entered.await(5, TimeUnit.SECONDS))
        held.result = 137 to "ERR_ADB_TIMEOUT"
        held.release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))

        net.wifi = false                      // «позднее исполнение» первой команды
        val c = JitOp.click(store, net, JitFake.QueueShell(stand()), want = true, log = ::log)
        assertEquals("chain_undefined", c.blocked)
        assertEquals("снимок остался исходным", "wifi=1", store.rawRestore())
    }

    @Test
    fun blockedClickRollsBackPreviousValuesOfAllKeys() {
        // §1.3: неудачная запись не должна оставлять за собой изменённые пользовательские ключи.
        val store = JitFake.Store().apply {
            setIn(0, JitRef.KEY_AUTO, false)       // прежняя настройка драйвера
            setIn(0, JitRef.KEY_PEDIDO, true)
            setIn(0, JitRef.KEY_RESTORE, "wifi=0")
            commitFails += 0
            commitFails += 1
            writeDespiteFalse = true               // commit=false, но память изменилась
        }
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand())
        val c = JitOp.click(store, net, shell, want = true, log = ::log)

        assertEquals("snapshot_not_saved", c.blocked)
        assertEquals("прежняя настройка восстановлена", false, store.maps[0][JitRef.KEY_AUTO])
        assertEquals("прежний снимок восстановлен", "wifi=0", store.maps[0][JitRef.KEY_RESTORE])
        assertTrue("прежнее намерение восстановлено", store.maps[0][JitRef.KEY_PEDIDO] == true)
        assertFalse("нашей отметки не осталось", store.maps[0].containsKey(JitRef.KEY_MARK))
        assertTrue("shell не вызван", shell.pending.isEmpty())
    }

    @Test
    fun failedRollbackIsReportedAndClickStaysBlocked() {
        val store = JitFake.Store().apply {
            setIn(0, JitRef.KEY_AUTO, false)
            commitFails += 0
            commitFails += 1
            writeDespiteFalse = true
            rollbackFails += 0
        }
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand())
        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertEquals("snapshot_not_saved", c.blocked)
        assertTrue("отказ восстановления виден в логе", lines().contains("prev_restore=1/2"))
        assertTrue(lines().contains("prev_restore_detail="))
        assertTrue("shell не вызван", shell.pending.isEmpty())
    }

    // ─────────────────────────── 3. продолжение и старая задача ───────────────────────────

    @Test
    fun snapshotKeptUntilRestoreRunsThenRemoved() {
        val store = JitFake.Store().seedOp("wifi=1")
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()
        val out = JitOp.onProcessStart(store, net, sched, ::log)
        assertTrue(out.scheduled)
        assertEquals(listOf(15_000L), sched.delays())
        assertTrue("снимок НЕ удалён до фактического возврата", store.contains(JitRef.KEY_RESTORE))
        assertTrue("отметка операции на месте", store.contains(JitRef.KEY_MARK))
        assertTrue(net.requests.isEmpty())

        sched.drain()
        assertEquals(listOf("wifi=true"), net.requests)
        assertFalse("после возврата снимок снят", store.contains(JitRef.KEY_RESTORE))
        assertFalse("и отметка снята", store.contains(JitRef.KEY_MARK))
    }

    @Test
    fun clickDuringScheduledRestoreIsRejectedInBothDirections() {
        // §1.2: старая отложенная задача не должна выполняться посреди новой цепочки, а новый клик
        // не должен перезаписывать снимок, который эта задача ещё не отработала.
        val store = JitFake.Store().seedOp("wifi=1")
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        val snapshotBefore = store.rawRestore()

        val a = JitOp.click(store, net, JitFake.QueueShell(stand()), want = false, log = ::log)
        assertEquals("restore_pending", a.blocked)
        val b = JitOp.click(store, net, JitFake.QueueShell(stand()), want = true, log = ::log)
        assertEquals("restore_pending", b.blocked)
        assertEquals("снимок не перезаписан", snapshotBefore, store.rawRestore())
        assertTrue("и цепочка не отправлялась", b.exitCode == -1)
        assertTrue(lines().contains("blocked=restore_pending"))

        sched.drain()
        // После выполнения отложенного возврата клик снова возможен.
        val c = JitOp.click(store, net, JitFake.QueueShell(stand()).apply { runInline = 1 },
            want = false, log = ::log)
        assertNull(c.blocked)
    }

    @Test
    fun oldRestoreCallbackDoesNotTouchSnapshotOfNewOperation() {
        // Пока задача стояла, записалась ДРУГАЯ (новая) операция со своим снимком и отметкой.
        val store = JitFake.Store().seedOp("wifi=1", op = "op-old")
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)

        val newMark = JitRef.OpMark.of("op-new", want = false, state = JitRef.OpMark.REPLIED).wire()
        store.setIn(0, JitRef.KEY_RESTORE, "wifi=0")
        store.setIn(1, JitRef.KEY_RESTORE, "wifi=0")
        store.setIn(0, JitRef.KEY_MARK, newMark)
        store.setIn(1, JitRef.KEY_MARK, newMark)

        sched.drain()

        assertEquals("снимок новой операции не удалён", "wifi=0", store.rawRestore())
        assertEquals("отметка новой операции не удалена", newMark, store.getString(JitRef.KEY_MARK))
        assertTrue(lines().contains("cleanup=skipped reason=snapshot_belongs_to_other_op"))
    }

    @Test
    fun duplicatePreventionIsPerTaskNotPerProcess() {
        val store = JitFake.Store().seedOp("wifi=1")
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()

        val a = JitOp.onProcessStart(store, net, sched, ::log)
        val b = JitOp.onProcessStart(store, net, sched, ::log)
        assertTrue(a.scheduled)
        assertFalse(b.scheduled)
        assertEquals("duplicate", b.reason)
        assertEquals(1, sched.scheduled.size)

        // Задача выполнилась (снимок снят) — новый снимок в том же процессе снова планируется.
        sched.drain()
        store.seedOp("wifi=1", op = "op-next")
        val d = JitOp.onProcessStart(store, net, sched, ::log)
        assertTrue("флаг не «на весь процесс»", d.scheduled)
        assertEquals(1, sched.scheduled.size)
    }

    @Test
    fun duplicateWhileTaskPendingKeepsSnapshot() {
        val store = JitFake.Store().seedOp("wifi=1")
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()
        val a = JitOp.onProcessStart(store, net, sched, ::log)
        val b = JitOp.onProcessStart(store, net, sched, ::log)
        assertTrue(a.scheduled)
        assertEquals("duplicate", b.reason)
        assertEquals(1, sched.scheduled.size)
        assertTrue("снимок всё ещё на месте до выполнения", store.contains(JitRef.KEY_RESTORE))
    }

    @Test
    fun removalRefusedKeepsSnapshotForNextStart() {
        val store = JitFake.Store().seedOp("wifi=1")
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        store.commitFails += listOf(0, 1)   // удаление не подтвердится
        sched.drain()
        assertTrue("снимок остался (удаление не подтверждено)", store.contains(JitRef.KEY_RESTORE))
        assertTrue(lines().contains("still_present=true"))
        // Следующий старт увидит тот же опознанный снимок и повторит возврат (setWifiEnabled идемпотентен).
        store.commitFails.clear()
        val sched2 = JitFake.Scheduler()
        val again = JitOp.onProcessStart(store, net, sched2, ::log)
        assertTrue(again.scheduled)
    }

    @Test
    fun schedulerFailureIsReportedHonestlyAndSnapshotSurvives() {
        val store = JitFake.Store().seedOp("wifi=1")
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler().apply { failWith = IllegalStateException("no scheduler") }
        val out = JitOp.onProcessStart(store, net, sched, ::log)
        assertFalse(out.scheduled)
        assertEquals("scheduler_failed", out.reason)
        assertTrue(lines().contains("scheduler=failed"))
        assertTrue("снимок сохранён — продолжение не потеряно", store.contains(JitRef.KEY_RESTORE))
        // Следующая попытка в том же процессе возможна и продолжит ту же операцию.
        sched.failWith = null
        val again = JitOp.onProcessStart(store, net, sched, ::log)
        assertTrue(again.scheduled)
        assertEquals("op-prev", again.mark?.op)
    }

    @Test
    fun processDeathBeforeCallbackIsRecoveredOnNextStart() {
        // Смерть процесса после планирования, но до выполнения: «новый процесс» видит тот же
        // опознанный снимок и продолжает операцию.
        val store = JitFake.Store().seedOp("wifi=1")
        val net = JitFake.Net(wifi = false)
        JitOp.onProcessStart(store, net, JitFake.Scheduler(), ::log)   // задача не выполнилась
        JitOp.resetStateForTest()                                      // «процесс умер»
        val sched2 = JitFake.Scheduler()
        val out = JitOp.onProcessStart(store, net, sched2, ::log)
        assertTrue(out.scheduled)
        assertEquals("op-prev", out.mark?.op)
        sched2.drain()
        assertEquals(listOf("wifi=true"), net.requests)
    }

    @Test
    fun legacyOrUnknownSnapshotIsNotExecutedAndKept() {
        // §4: снимок 1.0.60 (без нашей отметки) не выполняется и НЕ удаляется.
        val store = JitFake.Store().apply { setIn(0, JitRef.KEY_RESTORE, "wifi=1") }
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()
        val out = JitOp.onProcessStart(store, net, sched, ::log)
        assertEquals("legacy_or_unknown", out.reason)
        assertFalse(out.scheduled)
        assertTrue(sched.scheduled.isEmpty())
        assertEquals("данные сохранены", "wifi=1", store.rawRestore())
        assertEquals("ни одной записи/удаления", 0, store.writeCount())
        assertTrue(net.requests.isEmpty())
        assertTrue(lines().contains("reason=legacy_or_unknown"))
    }

    @Test
    fun brokenSnapshotIsNotExecuted() {
        val store = JitFake.Store().apply { setIn(0, JitRef.KEY_RESTORE, "wifi=1,ap=0,extra=1") }
        val net = JitFake.Net(wifi = true)
        val sched = JitFake.Scheduler()
        val out = JitOp.onProcessStart(store, net, sched, ::log)
        assertEquals("no_snapshot", out.reason)
        assertTrue(sched.scheduled.isEmpty())
        assertTrue(store.contains(JitRef.KEY_RESTORE))
        assertTrue(net.requests.isEmpty())
    }

    @Test
    fun legacyFormatIsReadOnlyByWifiAndNeverTurnsWifiOnBlindly() {
        val store = JitFake.Store().apply {
            setIn(0, JitRef.KEY_RESTORE, "wifi=0,ap=1")
            setIn(0, JitRef.KEY_MARK, JitRef.OpMark.of("op-legacy", want = false, state = JitRef.OpMark.REPLIED).wire())
        }
        val net = JitFake.Net(wifi = true)
        val sched = JitFake.Scheduler()
        val out = JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()
        assertEquals("legacy_ap_ignored", out.format)
        assertTrue("Wi-Fi не включаем: до операции он был выключен", net.requests.isEmpty())
    }

    // ─────────────────────────── 4. возврат Wi-Fi: четыре разных исхода ───────────────────────────

    @Test
    fun restoreAcceptedIsNotProofOfInternet() {
        val store = JitFake.Store().seedOp("wifi=1")
        val net = JitFake.Net(wifi = false, setWifiResult = true, address = true)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()
        assertEquals(listOf("wifi=true"), net.requests)
        assertTrue(lines().contains("returned=accepted"))
        assertTrue(lines().contains("internet=not_checked"))
        assertFalse(lines().contains("internet=on"))
    }

    @Test
    fun restoreRefusedIsNotCalledAccepted() {
        val store = JitFake.Store().seedOp("wifi=1")
        val net = JitFake.Net(wifi = false, setWifiResult = false)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()
        assertTrue(lines().contains("returned=refused"))
        assertFalse("не пишем «запрос принят» при false", lines().contains("запрос принят"))
        assertTrue(lines().contains("ОТКАЗАЛ"))
    }

    @Test
    fun restoreUnknownReturnValueIsUnknownNotAccepted() {
        val store = JitFake.Store().seedOp("wifi=1")
        val net = JitFake.Net(wifi = false, setWifiResult = null)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()
        assertTrue(lines().contains("returned=unknown"))
        assertTrue(lines().contains("результат вызова неизвестен"))
    }

    @Test
    fun restoreExceptionIsReportedAsException() {
        val store = JitFake.Store().seedOp("wifi=1")
        val net = JitFake.Net(wifi = false, setWifiThrows = true)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()
        assertTrue(lines().contains("returned=exception"))
        assertTrue(lines().contains("failure=set_wifi:IllegalStateException"))
    }

    @Test
    fun restoreSkipsWhenAlreadyOnOrWasOff() {
        val s1 = JitFake.Store().seedOp("wifi=1")
        val n1 = JitFake.Net(wifi = true)
        val sc1 = JitFake.Scheduler()
        JitOp.onProcessStart(s1, n1, sc1, ::log)
        sc1.drain()
        assertTrue("уже включён — запроса нет", n1.requests.isEmpty())

        val s2 = JitFake.Store().seedOp("wifi=0", want = false)
        val n2 = JitFake.Net(wifi = false)
        val sc2 = JitFake.Scheduler()
        JitOp.onProcessStart(s2, n2, sc2, ::log)
        sc2.drain()
        assertTrue("был выключен — не включаем", n2.requests.isEmpty())
    }

    @Test
    fun restoreWithUnreadableWifiDoesNotSendRequest() {
        val store = JitFake.Store().seedOp("wifi=1")
        val net = JitFake.Net(wifiUnreadable = true)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()
        assertTrue(net.requests.isEmpty())
        assertTrue(lines().contains("read:IllegalStateException"))
    }

    // ─────────────────────────── 5. сверка против нового намерения ───────────────────────────

    @Test
    fun verifySkippedWhileOurClickIsInFlight() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.BlockingShell()
        val done = CountDownLatch(1)
        val t = Thread {
            JitOp.click(store, net, shell, want = true, log = ::log)
            done.countDown()
        }
        t.start()
        assertTrue(shell.entered.await(5, TimeUnit.SECONDS))

        val st = JitOp.verifyApplied(store, JitFake.AnswerShell(maps = null), ::log)
        assertEquals("click_in_flight", st.skipped)
        assertTrue(store.contains(JitRef.KEY_PEDIDO))

        shell.release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        t.join(1000)
    }

    @Test
    fun newSameRequestDuringReadIsNotErased() {
        // Пока сверка читает getprop, записывается ТАКОЙ ЖЕ запрос (значение то же — по Boolean
        // они неразличимы, поэтому сравнивается поколение).
        val store = JitFake.Store().apply { set(JitRef.KEY_PEDIDO, true) }
        val net = JitFake.Net(wifi = true)
        val quick = JitFake.QueueShell(stand()).apply { runInline = 1 }
        val shell = JitFake.BlockingShell(maps = "7c-7d r-xp 0 fd:04 1 /data/dalvik-cache/arm64/jit-cache")
        shell.result = 0 to "true"   // getprop отвечает: сверка доходит до удаления
        shell.whileBlocked = {
            JitOp.click(store, net, quick, want = true, log = ::log)
        }

        val st = JitOp.verifyApplied(store, shell, ::log)

        assertEquals("pedido_changed", st.skipped)
        assertTrue("новый (такой же) запрос не снят", store.contains(JitRef.KEY_PEDIDO))
        assertTrue(lines().contains("race=pedido_changed"))
    }

    @Test
    fun clickStartingDuringVerifyBlocksRemoval() {
        // Клик начинается уже после первой проверки (слот свободен), но до удаления.
        val store = JitFake.Store().apply { set(JitRef.KEY_PEDIDO, true) }
        val net = JitFake.Net(wifi = true)
        val slow = JitFake.BlockingShell()
        val clickDone = CountDownLatch(1)
        slow.whileBlocked = {
            Thread {
                JitOp.click(store, net, JitFake.QueueShell(stand()).apply { runInline = 1 },
                    want = true, log = ::log)
                clickDone.countDown()
            }.start()
            Thread.sleep(200)   // клик успевает записать снимок и занять слот
        }
        Thread {
            JitOp.verifyApplied(store, slow, ::log)
        }.start()
        assertTrue(slow.entered.await(5, TimeUnit.SECONDS))
        slow.release.countDown()
        assertTrue(clickDone.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertTrue("намерение нового клика сохранено", store.contains(JitRef.KEY_PEDIDO))
    }

    @Test
    fun verifyOkRemovesIntentByRealRemoval() {
        val store = JitFake.Store().apply { set(JitRef.KEY_PEDIDO, true) }
        val maps = "7c-7d r-xp 0 fd:04 1 /data/dalvik-cache/arm64/jit-cache"
        val st = JitOp.verifyApplied(store, JitFake.AnswerShell(maps = maps), ::log)
        assertEquals(JitRef.Verify.OK, st.verdict)
        assertFalse("ключ удалён, а не выставлен в false", store.contains(JitRef.KEY_PEDIDO))
    }

    @Test
    fun verifyUnknownPropertyKeepsIntent() {
        val store = JitFake.Store().apply { set(JitRef.KEY_PEDIDO, true) }
        val shell = JitFake.AnswerShell(maps = null).apply { default = 137 to "ERR_ADB_TIMEOUT" }
        val st = JitOp.verifyApplied(store, shell, ::log)
        assertEquals(JitRef.Verify.UNKNOWN, st.verdict)
        assertTrue(store.contains(JitRef.KEY_PEDIDO))
        assertNull("неизвестность не выдаётся за ошибку", JitRef.verifyHint(st.verdict, st.prop, st.cache))
    }

    @Test
    fun verifyNotAppliedWarnsAndRemoves() {
        val store = JitFake.Store().apply { set(JitRef.KEY_PEDIDO, true) }
        val shell = JitFake.AnswerShell(maps = "7c-7d rw-p 0 fd:04 1 /data/dalvik-cache/arm64/jit-cache")
        shell.default = 0 to "false"
        val st = JitOp.verifyApplied(store, shell, ::log)
        assertEquals(JitRef.Verify.NOT_APPLIED, st.verdict)
        assertFalse(store.contains(JitRef.KEY_PEDIDO))
        assertTrue(lines().contains("JIT_NOT_APPLIED"))
        assertNotNull(JitRef.verifyHint(st.verdict, st.prop, st.cache))
    }

    @Test
    fun verifyWithoutIntentWritesNothing() {
        val store = JitFake.Store()
        val shell = JitFake.AnswerShell(maps = null)
        val st = JitOp.verifyApplied(store, shell, ::log)
        assertNull(st.pedido)
        assertEquals(1, shell.executed.size)
        assertEquals(0, store.writeCount())
    }

    // ─────────────────────────── 6. прочее ───────────────────────────

    @Test
    fun clickWithWifiOffSendsScriptAndSnapshotSaysZero() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = false)
        val shell = JitFake.QueueShell(stand())
        val c = JitOp.click(store, net, shell, want = false, log = ::log)
        assertTrue(c.sent)
        assertEquals("wifi=0", store.rawRestore())
        assertTrue(shell.pending.any { it.contains("usejit false") })
    }

    @Test
    fun noApWorkIsEverRequested() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = false)
        val shell = JitFake.QueueShell(stand())
        JitOp.click(store, net, shell, want = true, log = ::log)
        assertEquals(
            setOf("wifiOn", "setWifi", "wifiAddress"),
            JitRef.JitNetState::class.java.methods.map { it.name }
                .filterNot { it.contains("equals") || it.contains("hashCode") || it.contains("Default") }
                .toSet()
        )
        assertTrue("setWifi(false) не вызывается", net.requests.none { it == "wifi=false" })
        assertFalse(lines().contains("ap="))
    }

    @Test
    fun startSendsNoCommandsWhenNoSnapshot() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()
        assertEquals(0, store.writeCount())
        assertTrue(net.requests.isEmpty())
        assertTrue(sched.scheduled.isEmpty())
    }

    @Test
    fun logsNeverClaimRestartOrInternet() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand()).apply { runInline = 1 }
        JitOp.click(store, net, shell, want = true, log = ::log)
        JitOp.verifyApplied(store, JitFake.AnswerShell(maps = null), ::log)
        val all = lines()
        for (bad in listOf("restarted=true", "internet=ok", "net_restore_done")) {
            assertFalse("лог не содержит «$bad»", all.contains(bad))
        }
        assertTrue(all.contains("restart=not_claimed"))
        assertTrue(all.contains("internet=not_checked"))
    }
}
