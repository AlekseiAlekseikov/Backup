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

/**
 * iter39: критерий удаления снимка (§2.1), согласованное условное удаление (§2.2),
 * неопределённость через смерть процесса (§2.3), один протокол клик/возврат (§2.4),
 * «ключа нет» ≠ «чтение неизвестно» (§2.5).
 *
 * Это тесты ЛОГИКИ на JVM с подставными `JitNetState`/`JitShell`/`JitStore`
 * ([JitFake.Store]) и, где важно «что останется после перезапуска», с НАСТОЯЩИМ
 * `JitNet.AndroidStore` на реалистичной модели `SharedPreferences` ([JitFake.Prefs]).
 * На ГУ ничего не запускалось; `WifiManager` ГУ здесь не участвует.
 */
class JitIter39Test {

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

    /** Опознанный снимок прошлой операции (`jit_restaurar` + наша отметка). */
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

    private fun assertDataKept(store: JitFake.Store, why: String) {
        assertTrue("$why: снимок сохранён", store.contains(JitRef.KEY_RESTORE))
        assertTrue("$why: отметка сохранена", store.contains(JitRef.KEY_MARK))
    }

    private fun assertDataRemoved(store: JitFake.Store, why: String) {
        assertFalse("$why: снимок удалён", store.contains(JitRef.KEY_RESTORE))
        assertFalse("$why: отметка удалена", store.contains(JitRef.KEY_MARK))
    }

    // ─────────────────── §2.1 критерий удаления снимка ───────────────────

    @Test
    fun acceptedButNotConfirmedByStateKeepsOperationData() {
        val store = JitFake.Store().seedOp()
        // API вернул true, но Wi-Fi так и не включился (setWifiApplies=false) — успеха нет.
        val net = JitFake.Net(wifi = false, setWifiResult = true, setWifiApplies = false)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertDataKept(store, "accepted без подтверждения чтением — не успех")
        assertTrue(lines().contains("JIT_RESTORE_PENDING"))
        assertTrue(lines().contains("completion=pending"))
        assertTrue(lines().contains("data=kept"))
        assertFalse(lines().contains("data=removed"))
    }

    @Test
    fun refusedSetWifiEnabledKeepsOperationData() {
        val store = JitFake.Store().seedOp()
        val net = JitFake.Net(wifi = false, setWifiResult = false)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertDataKept(store, "отказ setWifiEnabled — не успех")
        assertTrue(lines().contains("completion=refused"))
        assertTrue(lines().contains("data=kept"))
    }

    @Test
    fun unknownSetWifiReturnKeepsOperationData() {
        val store = JitFake.Store().seedOp()
        val net = JitFake.Net(wifi = false, setWifiResult = null)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertDataKept(store, "неизвестный результат вызова — не успех")
        assertTrue(lines().contains("returned=unknown"))
        assertTrue(lines().contains("data=kept"))
    }

    @Test
    fun exceptionInSetWifiEnabledKeepsOperationData() {
        val store = JitFake.Store().seedOp()
        val net = JitFake.Net(wifi = false, setWifiThrows = true)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertDataKept(store, "исключение — не успех")
        assertTrue(lines().contains("completion=exception"))
    }

    @Test
    fun unreadableWifiStateDuringRestoreKeepsOperationData() {
        val store = JitFake.Store().seedOp()
        val net = JitFake.Net(wifi = false, wifiUnreadable = true)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertDataKept(store, "чтение состояния сорвалось — не успех")
        assertTrue(lines().contains("failure=read:IllegalStateException"))
        assertTrue(lines().contains("completion=exception"))
    }

    @Test
    fun absentWifiManagerKeepsOperationData() {
        val store = JitFake.Store().seedOp()
        // `null` из isWifiEnabled — «прочитать нельзя» (нет WifiManager), а не «выключен».
        val net = object : JitRef.JitNetState {
            override fun wifiOn(): Boolean? = null
            override fun setWifi(on: Boolean): Boolean? = true
            override fun wifiAddress(): Boolean? = null
        }
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertDataKept(store, "WifiManager недоступен — не успех")
        assertTrue(lines().contains("failure=wifi_manager_absent"))
        assertTrue(lines().contains("completion=unavailable"))
    }

    @Test
    fun wifiWasOffSnapshotIsNotTurnedOnAndCompletes() {
        val store = JitFake.Store().seedOp(snap = "wifi=0", want = false)
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertTrue("wifi=0 не включаем", net.requests.isEmpty())
        assertDataRemoved(store, "возврат по снимку wifi=0 корректно завершён")
        assertTrue(lines().contains("completion=complete_was_off"))
        assertTrue(lines().contains("data=removed"))
    }

    @Test
    fun confirmedRestoreRemovesDataWithRealStoreAndSurvivesNewProcess() {
        val cpsDisk = mutableMapOf<String, Any?>()
        val dpsDisk = mutableMapOf<String, Any?>()
        val cps = JitFake.Prefs(cpsDisk)
        val dps = JitFake.Prefs(dpsDisk)
        val store = JitNet.AndroidStore({ cps }, { dps })
        val mark = JitRef.OpMark.of("op-real", true, JitRef.OpMark.REPLIED, 0L)
        store.putSnapshot(true, JitRef.WifiSnapshot(true), mark)
        assertEquals("wifi=1", cpsDisk[JitRef.KEY_RESTORE])

        val net = JitFake.Net(wifi = false, setWifiResult = true, setWifiApplies = true)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        // Настоящий cleanup: на «диске» не осталось ни снимка, ни отметки — ни в CPS, ни в DPS.
        for (disk in listOf(cpsDisk, dpsDisk)) {
            assertFalse("снимок удалён на диске", disk.containsKey(JitRef.KEY_RESTORE))
            assertFalse("отметка удалена на диске", disk.containsKey(JitRef.KEY_MARK))
        }
        // Новый процесс после подтверждённого возврата ничего не продолжает.
        val next = JitNet.AndroidStore({ cps.newProcess() }, { dps.newProcess() })
        val again = JitOp.onProcessStart(next, net, JitFake.Scheduler(), ::log)
        assertFalse(again.scheduled)
        assertEquals("no_snapshot", again.reason)
        assertTrue(lines().contains("data=removed"))
    }

    // ─────────────────── §2.2 согласованное условное удаление ───────────────────

    @Test
    fun ownedRemovalSkipsStoreWithForeignMark() {
        val cpsDisk = mutableMapOf<String, Any?>()
        val dpsDisk = mutableMapOf<String, Any?>()
        val cps = JitFake.Prefs(cpsDisk)
        val dps = JitFake.Prefs(dpsDisk)
        val store = JitNet.AndroidStore({ cps }, { dps })
        // В CPS — наша операция, в DPS уже лежит другая (новая).
        store.putSnapshot(true, JitRef.WifiSnapshot(true),
            JitRef.OpMark.of("op-mine", true, JitRef.OpMark.REPLIED, 0L))
        dps.edit()
            .putString(JitRef.KEY_RESTORE, "wifi=0")
            .putString(JitRef.KEY_MARK,
                JitRef.OpMark.of("op-new", false, JitRef.OpMark.REPLIED, 0L).wire())
            .commit()

        val rm = store.removeOwned("op-mine")

        assertEquals(1, rm.clean)
        assertEquals(1, rm.skippedOtherOp)
        assertFalse("чужая запись не удалена", rm.consistent)
        assertFalse("CPS очищен", cpsDisk.containsKey(JitRef.KEY_RESTORE))
        assertEquals("запись новой операции не тронута", "wifi=0", dpsDisk[JitRef.KEY_RESTORE])
        assertNotNull("отметка новой операции не тронута", dpsDisk[JitRef.KEY_MARK])
        assertTrue("чужая отметка названа по имени", rm.detail.contains("dps:skip(op-new)"))
    }

    @Test
    fun ownedRemovalUsesOneCommitPerStore() {
        val cpsDisk = mutableMapOf<String, Any?>()
        val dpsDisk = mutableMapOf<String, Any?>()
        val cps = JitFake.Prefs(cpsDisk)
        val dps = JitFake.Prefs(dpsDisk)
        val store = JitNet.AndroidStore({ cps }, { dps })
        store.putSnapshot(true, JitRef.WifiSnapshot(true),
            JitRef.OpMark.of("op-one", true, JitRef.OpMark.REPLIED, 0L))
        val cpsBefore = cps.writes.get()
        val dpsBefore = dps.writes.get()

        val rm = store.removeOwned("op-one")

        assertEquals("снимок и отметка удаляются одним commit на хранилище", 1, cps.writes.get() - cpsBefore)
        assertEquals(1, dps.writes.get() - dpsBefore)
        assertTrue(rm.consistent)
        assertEquals("all_clean", rm.detail)
    }

    @Test
    fun partialOwnedRemovalIsReportedHonestlyAndKeepsRemainingData() {
        val cpsDisk = mutableMapOf<String, Any?>()
        val dpsDisk = mutableMapOf<String, Any?>()
        val cps = JitFake.Prefs(cpsDisk)
        val dps = JitFake.Prefs(dpsDisk)
        val store = JitNet.AndroidStore({ cps }, { dps })
        store.putSnapshot(true, JitRef.WifiSnapshot(true),
            JitRef.OpMark.of("op-part", true, JitRef.OpMark.REPLIED, 0L))
        dps.commitOk = false            // DPS отказывает: частичный успех

        val rm = store.removeOwned("op-part")

        assertFalse("согласованность не заявляется", rm.consistent)
        assertTrue(rm.snapshotLeft)
        assertTrue(rm.partial)
        assertTrue("остаток виден в detail", rm.detail.contains("dps:commit=false"))
        assertTrue("остаток остался на диске", dpsDisk.containsKey(JitRef.KEY_RESTORE))
    }

    @Test
    fun restoreWithPartialCleanupSaysDataKeptAndPromisesNothing() {
        val store = JitFake.Store().seedOp(op = "op-x")
        store.commitFails += listOf(0, 1)   // удаление не подтвердится
        val net = JitFake.Net(wifi = false, setWifiResult = true, setWifiApplies = true)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertDataKept(store, "частичная очистка не выдаётся за успех")
        val l = lines()
        assertTrue(l.contains("JIT_RESTORE_DONE"))
        assertTrue(l.contains("data=kept_or_partial"))
        assertTrue("нет обещания продолжения по остатку", l.contains("гарантии продолжения по нему нет"))
        assertFalse("атомарность CPS↔DPS не заявляется", l.contains("atomic=true"))
    }

    // ─────────────────── §2.3 неопределённость через смерть процесса ───────────────────

    @Test
    fun sendingMarkBlocksNewClickAfterProcessRestart() {
        val store = JitFake.Store().seedOp(op = "op-dead", state = JitRef.OpMark.SENDING)
        val net = JitFake.Net(wifi = false)
        // «Процесс умер и запустился заново»: сброс процессного состояния, данные — с диска.
        JitOp.resetStateForTest()
        val out = JitOp.onProcessStart(store, net, JitFake.Scheduler(), ::log)
        assertTrue("возврат по снимку планируется", out.scheduled)
        assertTrue(lines().contains("outcome=unknown action=restore_state_only"))

        val c = JitOp.click(store, net, JitFake.QueueShell(), want = true, log = ::log)
        assertEquals("новый клик заблокирован после рестарта", "chain_undefined", c.blocked)
        assertFalse(c.sent)
    }

    @Test
    fun undefinedMarkBlocksNewClick() {
        val store = JitFake.Store().seedOp(op = "op-lost", state = JitRef.OpMark.UNDEFINED)
        val net = JitFake.Net(wifi = false)
        JitOp.resetStateForTest()
        JitOp.onProcessStart(store, net, JitFake.Scheduler(), ::log)
        val c = JitOp.click(store, net, JitFake.QueueShell(), want = false, log = ::log)
        assertEquals("chain_undefined", c.blocked)
    }

    @Test
    fun undefinedIsNotClearedByNetworkRestoreOnly() {
        val store = JitFake.Store().seedOp(op = "op-lost", state = JitRef.OpMark.UNDEFINED)
        val net = JitFake.Net(wifi = false, setWifiResult = false)   // отказ: восстановления нет
        JitOp.resetStateForTest()
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        val stillBlocked = JitOp.click(store, net, JitFake.QueueShell(), want = true, log = ::log)
        assertEquals("отказ setWifiEnabled не снимает блокировку", "chain_undefined", stillBlocked.blocked)
        assertTrue(JitOp.chainUndefined())
        assertFalse("событие снятия не заявлялось", lines().contains("clicks=allowed"))

        // Следующий обычный запуск: сеть действительно восстановлена чтением состояния.
        net.setWifiResult = true
        net.setWifiApplies = true
        val sched2 = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched2, ::log)
        sched2.drain()

        // iter40 §2.1: восстановление СЕТИ не разрешает неопределённую операцию — старая команда
        // могла остаться в очереди и выключить Wi-Fi позже.
        assertTrue("флаг не снят восстановлением сети", JitOp.chainUndefined())
        assertTrue(lines().contains("outcome=still_unknown"))
        assertTrue(lines().contains("network_state=restored operation=unresolved"))
        val next = JitOp.click(store, net, JitFake.QueueShell(), want = false, log = ::log)
        assertEquals("клик по-прежнему заблокирован", "chain_undefined", next.blocked)
    }

    @Test
    fun recordedIdAndNewProcessAreNotTreatedAsProof() {
        val store = JitFake.Store().seedOp(op = "op-dead", state = JitRef.OpMark.SENDING)
        val net = JitFake.Net(wifi = false, setWifiResult = true, setWifiApplies = true)
        JitOp.resetStateForTest()
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        val l = lines()
        assertTrue("исход остаётся неизвестным", l.contains("outcome=unknown"))
        for (bad in listOf("resolved=true", "executed=true", "не выполнено=true", "chain_applied")) {
            assertFalse("нет утверждения «$bad»", l.contains(bad))
        }
        sched.drain()
        assertTrue("состояние сети восстановлено — но исход по-прежнему unknown",
            lines().contains("outcome=still_unknown"))
        assertTrue("данные удалены только после подтверждённого восстановления",
            !store.contains(JitRef.KEY_RESTORE))
    }

    // ─────────────────── §2.4 один протокол ───────────────────

    @Test
    fun staleTaskDoesNotTouchNetworkAtAll() {
        val store = JitFake.Store().seedOp(op = "op-old")
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)

        // Пока задача стояла, записалась другая операция.
        val newMark = JitRef.OpMark.of("op-new", false, JitRef.OpMark.REPLIED, 0L).wire()
        store.setIn(0, JitRef.KEY_RESTORE, "wifi=0")
        store.setIn(1, JitRef.KEY_RESTORE, "wifi=0")
        store.setIn(0, JitRef.KEY_MARK, newMark)
        store.setIn(1, JitRef.KEY_MARK, newMark)

        sched.drain()

        assertTrue("сеть не менялась", net.requests.isEmpty())
        assertFalse(net.wifi)
        assertEquals("чужая запись не тронута", "wifi=0", store.rawRestore())
        assertEquals(newMark, store.getString(JitRef.KEY_MARK))
    }

    @Test
    fun clickIsRejectedWhileRestoreIsInFlight() {
        val store = JitFake.Store().seedOp(op = "op-slow")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val net = object : JitRef.JitNetState {
            @Volatile var wifi = false
            override fun wifiOn(): Boolean? = wifi
            override fun setWifi(on: Boolean): Boolean? {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                wifi = true
                return true
            }
            override fun wifiAddress(): Boolean? = true
        }
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)

        val t = Thread { sched.drain() }
        t.start()
        assertTrue("возврат действительно идёт", entered.await(5, TimeUnit.SECONDS))

        val c = JitOp.click(store, net, JitFake.QueueShell(), want = false, log = ::log)
        assertFalse("клик во время возврата не отправлен", c.sent)
        assertEquals("restore_pending", c.blocked)
        assertFalse("скрипт не отправлен", lines().contains("JIT_CHAIN_SENT"))

        release.countDown()
        t.join(5000)
        assertFalse("после завершения возврата данные удалены", store.contains(JitRef.KEY_RESTORE))
    }

    @Test
    fun schedulerRefusalNeverClaimsGuarantee() {
        val store = JitFake.Store().seedOp(op = "op-s")
        val sched = JitFake.Scheduler().apply { failWith = IllegalStateException("no scheduler") }
        val out = JitOp.onProcessStart(store, JitFake.Net(wifi = false), sched, ::log)
        assertFalse(out.scheduled)
        assertEquals("scheduler_failed", out.reason)
        assertDataKept(store, "отказ планировщика не теряет данные")
    }

    // ─────────────────── §2.5 «ключа нет» ≠ «чтение неизвестно» ───────────────────

    @Test
    fun unreadablePreviousStateChangesNothingAtAll() {
        val store = JitFake.Store()
        store.readStateFails += listOf(0, 1)     // чтение прежних значений не удалось
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell()

        val c = JitOp.click(store, net, shell, want = false, log = ::log)

        assertEquals("prev_unknown", c.blocked)
        assertFalse(c.sent)
        assertTrue("сеть не менялась", net.requests.isEmpty())
        assertTrue("shell не запускался", shell.pending.isEmpty())
        assertEquals("ничего не записано", 0, c.write!!.attempted)
        assertTrue("Prefs2 (оба хранилища) не тронуты",
            store.maps.all { it.isEmpty() })
        assertTrue(lines().contains("blocked=prev_unknown"))
    }

    @Test
    fun oneUnreadableStoreIsEnoughToBlock() {
        val store = JitFake.Store()
        store.readStateFails += 1               // второе по порядку хранилище не читается
        val c = JitOp.click(store, JitFake.Net(wifi = true), JitFake.QueueShell(),
            want = true, log = ::log)
        assertEquals("prev_unknown", c.blocked)
        assertTrue(store.maps.all { it.isEmpty() })
    }

    @Test
    fun missingKeyIsNotUnreadableState() {
        val store = JitFake.Store()             // ключей нет вовсе — это достоверное «нет»
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell()
        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertNull("отсутствие ключа не блокирует клик", c.blocked)
        assertTrue(c.sent)
        assertFalse("в логе нет prev_unknown", lines().contains("prev_unknown"))
    }
    // ─────────────────── §4 обновление 1.0.60 → 1.0.61 ───────────────────

    @Test
    fun legacySnapshotOf1060IsNeitherExecutedNorDeleted() {
        val cpsDisk = mutableMapOf<String, Any?>()
        val dpsDisk = mutableMapOf<String, Any?>()
        // Так выглядят данные 1.0.60: снимок есть, отметки операции нет (пишем в «диск» до
        // создания объектов — «новый процесс» читает именно диск).
        cpsDisk[JitRef.KEY_RESTORE] = "wifi=1"
        dpsDisk[JitRef.KEY_RESTORE] = "wifi=1"
        val cps = JitFake.Prefs(cpsDisk)
        val dps = JitFake.Prefs(dpsDisk)
        val store = JitNet.AndroidStore({ cps }, { dps })
        val net = JitFake.Net(wifi = false)
        val shell = JitFake.QueueShell()

        val out = JitOp.onProcessStart(store, net, JitFake.Scheduler(), ::log)

        assertEquals("stored_ambiguous", out.reason)
        assertFalse("снимок не исполняется", out.scheduled)
        assertTrue("сеть не менялась", net.requests.isEmpty())
        assertTrue("ни одной shell-команды (нет пробных umount/zygote)", shell.pending.isEmpty())
        assertEquals("снимок 1.0.60 сохранён на диске", "wifi=1", cpsDisk[JitRef.KEY_RESTORE])
        assertEquals("второе хранилище не тронуто", "wifi=1", dpsDisk[JitRef.KEY_RESTORE])
        assertTrue(lines().contains("reason=stored_ambiguous"))
    }
}
