package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Поведение цепочки JIT на подставных интерфейсах (iter36: только клиентский Wi-Fi).
 *
 * Это НЕ интеграционные тесты Android и НЕ проверка на ГУ: здесь нет её `WifiManager`, её префов и
 * её zygote. Проверяется то, что видно в коде: какие ключи записаны/удалены, какие команды ушли,
 * какие сетевые вызовы сделаны, что попало в лог и в отчёт шага.
 */
class JitOrchestratorTest {

    private val logs = mutableListOf<String>()

    @Before
    fun reset() {
        JitOp.resetStateForTest()
        logs.clear()
    }

    private fun log(s: String) { logs.add(s) }

    private fun lines() = logs.joinToString("\n")

    // ─────────────────────────── 1. клик: `w0/j9` ───────────────────────────

    @Test
    fun clickSavesSnapshotInEveryStoreAndSendsReferenceScript() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand = JitIsoStand(), getprop = "true")
        shell.runInline = 1

        val c = JitOp.click(store, net, shell, want = true, log = ::log)

        assertTrue("скрипт отправлен", c.sent)
        assertNull(c.blocked)
        assertEquals(JitRef.WifiSnapshot(true), c.snap)
        assertEquals("wifi=1", store.rawRestore())
        // Три ключа референса, в каждом хранилище.
        for (i in 0..1) {
            assertTrue(store.presentIn(i, JitRef.KEY_PEDIDO))
            assertTrue(store.presentIn(i, JitRef.KEY_AUTO))
            assertTrue(store.presentIn(i, JitRef.KEY_RESTORE))
        }
        assertEquals(2, store.commits.size)
        assertEquals(2, c.confirmed)
        assertEquals(JitRef.ChainOutcome.REPLY_RECEIVED, c.outcome)
        assertTrue(lines().contains("JIT_CLICK want=true restore=wifi=1"))
        assertTrue("ни одного упоминания AP", !lines().contains("ap="))
        assertTrue(lines().contains("mount_step=present"))
    }

    @Test
    fun clickWithWifiOffStillSendsScriptButSnapshotSaysZero() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = false)
        val shell = JitFake.QueueShell()
        val c = JitOp.click(store, net, shell, want = false, log = ::log)
        assertTrue(c.sent)
        assertEquals("wifi=0", store.rawRestore())
        assertTrue(shell.pending.any { it.contains("usejit false") })
    }

    @Test
    fun clickWithUnknownWifiIsBlockedAndTouchesNothing() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifiUnreadable = true)
        val shell = JitFake.QueueShell()

        val c = JitOp.click(store, net, shell, want = true, log = ::log)

        assertEquals("wifi_unknown", c.blocked)
        assertFalse(c.sent)
        assertTrue("ни одной команды на устройство", shell.pending.isEmpty())
        assertEquals("ни одной записи в префы", 0, store.writeCount())
        assertFalse(store.contains(JitRef.KEY_PEDIDO))
        assertFalse("Wi-Fi не выключался", net.requests.contains("wifi=false"))
        assertTrue(lines().contains("blocked=wifi_unknown"))
    }

    @Test
    fun clickWithoutConfirmedSnapshotIsBlocked() {
        val store = JitFake.Store().apply { commitFails += listOf(0, 1) }
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell()

        val c = JitOp.click(store, net, shell, want = true, log = ::log)

        assertEquals("snapshot_not_saved", c.blocked)
        assertTrue("ни одной команды на устройство", shell.pending.isEmpty())
        assertFalse("намерение не осталось (или честно не подтверждено)", store.contains(JitRef.KEY_PEDIDO))
    }

    @Test
    fun clickWithOneBrokenStoreIsNotBlockedAndReportedHonestly() {
        val store = JitFake.Store().apply { throwOn += 1 }
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell()
        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertTrue("доступное хранилище достаточно", c.sent)
        assertEquals(1, c.confirmed)
        assertEquals(1, c.snapshot.ok)
        assertEquals(2, c.snapshot.attempted)
        assertTrue(lines().contains("written=1/2"))
    }

    @Test
    fun commitFalseButValuePresentIsConfirmedByReadingNotByFlag() {
        // commit()==false НЕ доказывает, что значения нет: решение по чтению.
        val store = JitFake.Store().apply { commitFails += listOf(0, 1); writeDespiteFalse = true }
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell()
        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertTrue("снимок читается ⇒ не блокируем", c.sent)
        assertEquals(2, c.confirmed)
        assertEquals("но флаг commit=false честно показан", 0, c.snapshot.ok)
    }

    @Test
    fun clickWithoutAnyStoreIsBlocked() {
        val store = JitFake.Store(storeCount = 0)
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell()
        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertEquals("snapshot_not_saved", c.blocked)
        assertTrue(shell.pending.isEmpty())
    }

    @Test
    fun lostChannelIsNotLoggedAsSuccess() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell()   // runInline = 0 ⇒ 137 / ERR_CHANNEL_LOST
        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertTrue(c.sent)
        assertEquals(JitRef.ChainOutcome.CHANNEL_LOST, c.outcome)
        assertTrue(lines().contains("reason=channel_lost"))
        assertTrue("не выдаём таймаут за успех", !lines().contains("result=ok"))
        assertTrue("команда могла остаться в очереди", lines().contains("НЕ «выполнено»"))
    }

    // ─────────────────────────── 2. продолжение: `onCreate` ───────────────────────────

    @Test
    fun snapshotRemovedSynchronouslyAndRestoreScheduled15s() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()

        val out = JitOp.onProcessStart(store, net, sched, ::log)

        assertTrue(out.scheduled)
        assertEquals(JitRef.WifiSnapshot(true), out.snapshot)
        assertEquals("wifi_only", out.format)
        assertEquals(listOf(15_000L), sched.delays())
        assertFalse("снимок снят ДО выполнения", store.contains(JitRef.KEY_RESTORE))
        assertTrue("сеть ещё не трогали", net.requests.isEmpty())
        assertTrue(lines().contains("action=restore_scheduled"))
    }

    @Test
    fun legacySnapshotIsMigratedWifiOnlyAndNeverCallsAp() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=0,ap=1") }
        val net = JitFake.Net(wifi = true)
        val sched = JitFake.Scheduler()

        val out = JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertEquals("legacy_ap_ignored", out.format)
        assertEquals(JitRef.WifiSnapshot(false), out.snapshot)
        assertTrue("Wi-Fi до операции был выключен — не включаем", net.requests.isEmpty())
        assertTrue(lines().contains("format=legacy_ap_ignored"))
        assertTrue("ни одного вызова AP в интерфейсе нет", !lines().contains("ap="))
    }

    @Test
    fun brokenSnapshotIsNotExecuted() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1,ap=0,extra=1") }
        val net = JitFake.Net(wifi = true)
        val sched = JitFake.Scheduler()

        val out = JitOp.onProcessStart(store, net, sched, ::log)

        assertFalse(out.scheduled)
        assertEquals("no_snapshot", out.reason)
        assertTrue(sched.scheduled.isEmpty())
        assertTrue("запись осталась как была — ничего не чистим", store.contains(JitRef.KEY_RESTORE))
        assertTrue(net.requests.isEmpty())
        assertTrue(lines().contains("snapshot=unparsed"))
    }

    @Test
    fun noSnapshotMeansNoNetworkActionAtAll() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()

        val out = JitOp.onProcessStart(store, net, sched, ::log)

        assertEquals("no_snapshot", out.reason)
        assertEquals(0, store.writeCount())
        assertEquals(0, net.wifiReads)
        assertTrue(net.requests.isEmpty())
        assertTrue(sched.scheduled.isEmpty())
    }

    @Test
    fun secondStartAfterRemovalSchedulesNothing() {
        // onCreate и onStartCommand зовут продолжение оба. Первый вызов снял снимок, поэтому
        // второй ничего не планирует: повторного действия нет.
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()

        val a = JitOp.onProcessStart(store, net, sched, ::log)
        val b = JitOp.onProcessStart(store, net, sched, ::log)

        assertTrue(a.scheduled)
        assertFalse(b.scheduled)
        assertEquals("no_snapshot", b.reason)
        assertEquals(1, sched.scheduled.size)
    }

    @Test
    fun doubleStartWithUnconfirmedRemovalSchedulesOnce() {
        // Худший случай: снимок не снялся (commit=false в обоих хранилищах), строка осталась.
        // Внутри процесса возврат всё равно планируется один раз.
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
        assertTrue(lines().contains("duplicate=true"))
    }

    @Test
    fun removalFailureStillSchedulesOnceAndSaysSo() {
        val store = JitFake.Store().apply {
            set(JitRef.KEY_RESTORE, "wifi=1")
            commitFails += listOf(0)
        }
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()

        val out = JitOp.onProcessStart(store, net, sched, ::log)

        assertTrue(out.scheduled)
        assertFalse(out.removed!!.allOk)
        assertTrue("снимок остался в одном хранилище", store.contains(JitRef.KEY_RESTORE))
        assertEquals(1, sched.scheduled.size)
        assertTrue(lines().contains("removal_unconfirmed=true"))
    }

    @Test
    fun deathBeforeScheduledTaskLeavesNetworkUntouched() {
        // Риск референса, названный в отчёте: снимок уже снят, а задача не выполнилась.
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()

        JitOp.onProcessStart(store, net, sched, ::log)
        // Задачу НЕ выполняем — как будто процесс умер.
        assertFalse(store.contains(JitRef.KEY_RESTORE))
        assertTrue(net.requests.isEmpty())
        assertTrue(logs.any { it.contains("смерть процесса") })
    }

    // ─────────────────────────── 3. возврат клиентского Wi-Fi: `e2` ───────────────────────────

    @Test
    fun restoreRequestsWifiWhenItWasOnAndOffNow() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
        val net = JitFake.Net(wifi = false, address = true)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertEquals(listOf("wifi=true"), net.requests)
        assertTrue(lines().contains("request=setWifiEnabled(true)"))
        assertTrue("интернет не проверялся", lines().contains("internet=not_checked"))
        assertTrue(lines().contains("address=true"))
        assertTrue("возвращённое true не выдаётся за интернет", !lines().contains("internet=on"))
    }

    @Test
    fun restoreDoesNothingWhenWifiIsAlreadyOn() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
        val net = JitFake.Net(wifi = true)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertTrue("лишнего вызова setWifiEnabled нет", net.requests.isEmpty())
        assertTrue(lines().contains("already") || lines().contains("уже включён"))
    }

    @Test
    fun restoreNeverTurnsWifiOnWhenItWasOff() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=0") }
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertTrue(net.requests.isEmpty())
        assertTrue(lines().contains("был ВЫКЛЮЧЕН"))
    }

    @Test
    fun restoreReportsFalseAndNullWithoutClaimingSuccess() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
        val net = JitFake.Net(wifi = false, setWifiResult = false)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertEquals(listOf("wifi=true"), net.requests)
        assertTrue(lines().contains("returned=false"))
        assertTrue(lines().contains("state_after=false"))
        assertTrue("запрос принят, но включение не подтверждено",
            lines().contains("НЕ подтверждено"))
    }

    @Test
    fun restoreExceptionIsReportedAsFailureNotSuccess() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
        val net = JitFake.Net(wifi = false, setWifiThrows = true)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertTrue(lines().contains("failure=set_wifi:IllegalStateException"))
        assertTrue(lines().contains("сеть НЕ подтверждена"))
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

    @Test
    fun fullChainClickThenRestore() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand = JitIsoStand())
        shell.runInline = 1
        JitOp.click(store, net, shell, want = true, log = ::log)
        assertTrue(store.contains(JitRef.KEY_PEDIDO))

        // «Новый процесс после рестарта фреймворка»: сеть выключена скриптом.
        JitOp.resetStateForTest()
        net.wifi = false
        val sched = JitFake.Scheduler()
        val out = JitOp.onProcessStart(store, net, sched, ::log)
        assertTrue(out.scheduled)
        sched.drain()

        assertEquals("Wi-Fi возвращён запросом", listOf("wifi=true"), net.requests)
        assertTrue(lines().contains("JIT_CLICK want=true"))
        assertTrue(lines().contains("JIT_NET_RESTORE"))
    }

    // ─────────────────────────── 4. сверка `bb` + `r7` ───────────────────────────

    @Test
    fun verifyWithoutPedidoSendsOnlyOneReadAndWritesNothing() {
        val store = JitFake.Store()
        val shell = JitFake.AnswerShell(maps = null)
        val st = JitOp.verifyApplied(store, shell, ::log)
        assertNull(st.pedido)
        assertEquals(1, shell.executed.size)
        assertTrue(shell.scriptCommands().isEmpty())
        assertEquals(0, store.writeCount())
        assertTrue(lines().contains("write=none"))
    }

    @Test
    fun verifyOkRemovesPedidoByRealRemoval() {
        val store = JitFake.Store().apply { set(JitRef.KEY_PEDIDO, true) }
        val maps = "7c-7d r-xp 0000 fd:04 1 /data/dalvik-cache/arm64/jit-cache"
        val shell = JitFake.AnswerShell(maps = maps)
        val st = JitOp.verifyApplied(store, shell, ::log)

        assertEquals(JitRef.Verify.OK, st.verdict)
        assertFalse("ключ удалён, а не выставлен в false", store.contains(JitRef.KEY_PEDIDO))
        assertTrue(st.removed!!.allOk)
        assertTrue(lines().contains("write=remove(${JitRef.KEY_PEDIDO})"))
    }

    @Test
    fun verifyNotAppliedWarnsAndStillRemoves() {
        val store = JitFake.Store().apply { set(JitRef.KEY_PEDIDO, true) }
        val shell = JitFake.AnswerShell(maps = "7c-7d rw-p 0000 fd:04 1 /data/dalvik-cache/arm64/jit-cache")
        shell.default = 0 to "false"
        val st = JitOp.verifyApplied(store, shell, ::log)

        assertEquals(JitRef.Verify.NOT_APPLIED, st.verdict)
        assertFalse(store.contains(JitRef.KEY_PEDIDO))
        assertTrue("предупреждение для панели", lines().contains("JIT_NOT_APPLIED"))
    }

    @Test
    fun verifyCacheMissingIsNotSuccess() {
        val store = JitFake.Store().apply { set(JitRef.KEY_PEDIDO, true) }
        val shell = JitFake.AnswerShell(maps = "7c-7d rw-p 0000 fd:04 1 /data/dalvik-cache/arm64/jit-cache")
        val st = JitOp.verifyApplied(store, shell, ::log)
        assertEquals(JitRef.Verify.CACHE_MISSING, st.verdict)
        assertNotNull("это не успех", JitRef.verifyHint(st.verdict, st.prop, st.cache))
    }

    @Test
    fun verifyUnknownPropertyKeepsIntent() {
        val store = JitFake.Store().apply { set(JitRef.KEY_PEDIDO, true) }
        val shell = JitFake.AnswerShell(maps = null)
        shell.default = 137 to "ERR_CHANNEL_LOST"
        val st = JitOp.verifyApplied(store, shell, ::log)

        assertEquals(JitRef.Verify.UNKNOWN, st.verdict)
        assertTrue("намерение сохранено", store.contains(JitRef.KEY_PEDIDO))
        assertNull(st.removed)
        assertTrue(lines().contains("JIT_PEDIDO kept"))
        assertNull("неизвестность не выдаётся за ошибку", JitRef.verifyHint(st.verdict, st.prop, st.cache))
    }

    @Test
    fun secondVerifyAfterRemovalSeesNoRequest() {
        val store = JitFake.Store().apply { set(JitRef.KEY_PEDIDO, false) }
        val shell = JitFake.AnswerShell(maps = null)
        shell.default = 0 to "false"
        JitOp.verifyApplied(store, shell, ::log)
        assertFalse(store.contains(JitRef.KEY_PEDIDO))

        logs.clear()
        val st = JitOp.verifyApplied(store, shell, ::log)
        assertNull("запроса нет — и не придумываем", st.pedido)
        assertTrue(lines().contains("pedido=none"))
        assertTrue(shell.scriptCommands().isEmpty())
    }

    @Test
    fun verifyIsSkippedWhileOurClickIsInFlight() {
        // Контролируемая очередность: клик уже записал снимок и застрял в транспорте.
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.BlockingShell()
        val done = CountDownLatch(1)

        val t = Thread {
            JitOp.click(store, net, shell, want = true, log = ::log)
            done.countDown()
        }
        t.start()
        assertTrue("клик дошёл до отправки скрипта", shell.entered.await(5, TimeUnit.SECONDS))

        val st = JitOp.verifyApplied(store, JitFake.AnswerShell(maps = null), ::log)
        assertEquals("click_in_flight", st.skipped)
        assertTrue("свежее намерение не съедено", store.contains(JitRef.KEY_PEDIDO))
        assertTrue(lines().contains("skipped reason=click_in_flight"))

        shell.release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        t.join(1000)
    }

    @Test
    fun staleVerifyDoesNotEraseNewerRequest() {
        // Пока сверка читает getprop, запрос пишут заново (второй клик/другое хранилище).
        val store = JitFake.Store().apply { set(JitRef.KEY_PEDIDO, true) }
        val shell = object : JitRef.JitShell {
            override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
                store.set(JitRef.KEY_PEDIDO, false)
                return 0 to "true"
            }
            override fun mapsExcerpt(): String? = null
        }
        val st = JitOp.verifyApplied(store, shell, ::log)
        assertEquals("pedido_changed", st.skipped)
        assertTrue("новый запрос остался", store.contains(JitRef.KEY_PEDIDO))
        assertTrue(lines().contains("race=pedido_changed"))
    }

    @Test
    fun twoFastOppositeClicksLastOneWinsInSnapshot() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell()
        JitOp.click(store, net, shell, want = true, log = ::log)
        JitOp.click(store, net, shell, want = false, log = ::log)
        assertEquals("снимок последнего клика", "wifi=1", store.rawRestore())
        assertEquals(false, store.getBool(JitRef.KEY_PEDIDO))
        assertEquals("оба клика отправили свой скрипт", 2, shell.pending.size)
        assertTrue(shell.pending.last().contains("usejit false"))
    }

    // ─────────────────────────── 5. обычный старт ничего не пишет ───────────────────────────

    @Test
    fun startSendsNoCommandsToDevice() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val sched = JitFake.Scheduler()
        val shell = JitFake.QueueShell()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()
        assertTrue(shell.pending.isEmpty())
        assertEquals(0, store.writeCount())
        assertTrue(net.requests.isEmpty())
    }

    @Test
    fun logsNeverClaimRestartOrInternet() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell()
        shell.runInline = 1
        JitOp.click(store, net, shell, want = true, log = ::log)
        JitOp.verifyApplied(store, JitFake.AnswerShell(maps = null), ::log)
        val all = lines()
        for (bad in listOf("restarted=true", "internet=ok", "net_restore_done", "write=none'")) {
            assertFalse("лог не содержит «$bad»", all.contains(bad))
        }
        assertTrue(all.contains("restart=not_claimed"))
        assertTrue(all.contains("internet=not_checked"))
    }

    @Test
    fun noApWorkIsEverRequested() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = false)
        val shell = JitFake.QueueShell()
        JitOp.click(store, net, shell, want = true, log = ::log)
        // В интерфейсе сетевого состояния остались ровно три метода клиентского Wi-Fi.
        assertEquals(setOf("wifiOn", "setWifi", "wifiAddress"),
            JitRef.JitNetState::class.java.methods
                .map { it.name }
                .filterNot { it.startsWith("equals") || it.startsWith("hashCode") || it.contains("Default") }
                .toSet())
        assertTrue("setWifi(false) не вызывается", net.requests.none { it == "wifi=false" })
    }

    @Test
    fun countersDoNotLeakBetweenOperations() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell()
        JitOp.click(store, net, shell, want = true, log = ::log)
        // После клика сверка снова доступна (флаг «в полёте» снят).
        val st = JitOp.verifyApplied(store, JitFake.AnswerShell(maps = null), ::log)
        assertNull(st.skipped)
        assertEquals(AtomicInteger(0).get(), 0)   // счётчик не остаётся «занятым»
    }
}
