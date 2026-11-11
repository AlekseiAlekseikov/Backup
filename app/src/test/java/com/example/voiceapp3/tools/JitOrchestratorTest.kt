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
        assertTrue(lines().contains("auto_key=untouched"))
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
    fun definiteReplyClearsUndefinedState() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val lost = JitFake.QueueShell(stand())
        JitOp.click(store, net, lost, want = true, log = ::log)
        assertTrue(JitOp.chainUndefined())

        val ok = JitFake.QueueShell(stand()).apply { runInline = 1 }
        val c = JitOp.click(store, net, ok, want = true, log = ::log)
        assertTrue(c.sent)
        assertEquals(JitRef.ChainOutcome.REPLY_RECEIVED, c.outcome)
        assertFalse("определённый ответ снимает неопределённость", JitOp.chainUndefined())
        assertTrue("прошлый неопределённый исход отмечен", c.previousUndefined)
    }

    // ─────────────────────────── 3. продолжение и старая задача ───────────────────────────

    @Test
    fun snapshotRemovedSynchronouslyAndRestoreScheduled15s() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()
        val out = JitOp.onProcessStart(store, net, sched, ::log)
        assertTrue(out.scheduled)
        assertEquals(listOf(15_000L), sched.delays())
        assertFalse(store.contains(JitRef.KEY_RESTORE))
        assertTrue(net.requests.isEmpty())
    }

    @Test
    fun oldTaskDoesNotDeleteNewerSnapshotWithSameValue() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)   // старый снимок снят, задача стоит

        // Новый клик записал ТАКОЙ ЖЕ снимок (Wi-Fi ещё включён) и новое намерение.
        net.wifi = true
        val shell = JitFake.QueueShell(stand()).apply { runInline = 1 }
        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertTrue(c.sent)

        sched.drain()   // старая задача исполнилась

        assertEquals("снимок нового клика не стёрт", "wifi=1", store.rawRestore())
        assertTrue("намерение нового клика на месте", store.contains(JitRef.KEY_PEDIDO))
    }

    @Test
    fun oldTaskDoesNotDeleteNewerSnapshotWithDifferentValue() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
        val net = JitFake.Net(wifi = true)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)

        val shell = JitFake.QueueShell(stand()).apply { runInline = 1 }
        JitOp.click(store, net, shell, want = false, log = ::log)
        // Снимок нового клика отличается: Wi-Fi уже выключен первым скриптом.
        store.setIn(0, JitRef.KEY_RESTORE, "wifi=0")
        store.setIn(1, JitRef.KEY_RESTORE, "wifi=0")

        sched.drain()

        assertEquals("чужой/новый снимок не подменён", "wifi=0", store.rawRestore())
    }

    @Test
    fun duplicatePreventionIsPerTaskNotPerProcess() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()

        val a = JitOp.onProcessStart(store, net, sched, ::log)
        val b = JitOp.onProcessStart(store, net, sched, ::log)
        assertTrue(a.scheduled)
        assertFalse(b.scheduled)
        assertEquals("duplicate", b.reason)
        assertEquals(1, sched.scheduled.size)

        // Задача выполнилась — и новый снимок в том же процессе снова планируется.
        sched.drain()
        store.set(JitRef.KEY_RESTORE, "wifi=1")
        val d = JitOp.onProcessStart(store, net, sched, ::log)
        assertTrue("флаг не «на весь процесс»", d.scheduled)
        assertEquals(1, sched.scheduled.size)
    }

    @Test
    fun duplicateWithUnconfirmedRemovalStillSchedulesOnce() {
        val store = JitFake.Store().apply {
            set(JitRef.KEY_RESTORE, "wifi=1")
            commitFails += listOf(0, 1)
        }
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()
        val a = JitOp.onProcessStart(store, net, sched, ::log)
        val b = JitOp.onProcessStart(store, net, sched, ::log)
        assertTrue(a.scheduled)
        assertFalse(b.scheduled)
        assertEquals("duplicate", b.reason)
        assertEquals(1, sched.scheduled.size)
        assertTrue(lines().contains("removal_unconfirmed=true"))
    }

    @Test
    fun schedulerFailureIsReportedHonestly() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler().apply { failWith = IllegalStateException("no scheduler") }
        val out = JitOp.onProcessStart(store, net, sched, ::log)
        assertFalse(out.scheduled)
        assertEquals("scheduler_failed", out.reason)
        assertTrue(lines().contains("scheduler=failed"))
        // Следующая попытка в том же процессе возможна: слот задачи освобождён.
        sched.failWith = null
        store.set(JitRef.KEY_RESTORE, "wifi=1")
        assertTrue(JitOp.onProcessStart(store, net, sched, ::log).scheduled)
    }

    @Test
    fun deathBeforeScheduledTaskLeavesNetworkUntouched() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        assertFalse(store.contains(JitRef.KEY_RESTORE))
        assertTrue(net.requests.isEmpty())
        assertTrue(logs.any { it.contains("смерть процесса") })
    }

    @Test
    fun brokenSnapshotIsNotExecuted() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1,ap=0,extra=1") }
        val net = JitFake.Net(wifi = true)
        val sched = JitFake.Scheduler()
        val out = JitOp.onProcessStart(store, net, sched, ::log)
        assertEquals("no_snapshot", out.reason)
        assertTrue(sched.scheduled.isEmpty())
        assertTrue(store.contains(JitRef.KEY_RESTORE))
        assertTrue(net.requests.isEmpty())
    }

    @Test
    fun legacySnapshotIsMigratedAndNeverTurnsWifiOnBlindly() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=0,ap=1") }
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
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
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
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
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
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
        val net = JitFake.Net(wifi = false, setWifiResult = null)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()
        assertTrue(lines().contains("returned=unknown"))
        assertTrue(lines().contains("результат вызова неизвестен"))
    }

    @Test
    fun restoreExceptionIsReportedAsException() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
        val net = JitFake.Net(wifi = false, setWifiThrows = true)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()
        assertTrue(lines().contains("returned=exception"))
        assertTrue(lines().contains("failure=set_wifi:IllegalStateException"))
    }

    @Test
    fun restoreSkipsWhenAlreadyOnOrWasOff() {
        val s1 = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
        val n1 = JitFake.Net(wifi = true)
        val sc1 = JitFake.Scheduler()
        JitOp.onProcessStart(s1, n1, sc1, ::log)
        sc1.drain()
        assertTrue("уже включён — запроса нет", n1.requests.isEmpty())

        val s2 = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=0") }
        val n2 = JitFake.Net(wifi = false)
        val sc2 = JitFake.Scheduler()
        JitOp.onProcessStart(s2, n2, sc2, ::log)
        sc2.drain()
        assertTrue("был выключен — не включаем", n2.requests.isEmpty())
    }

    @Test
    fun restoreWithUnreadableWifiDoesNotSendRequest() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
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
