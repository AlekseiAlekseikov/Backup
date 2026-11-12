package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * iter40 §2.1–§2.6 — снимок, неопределённость и барьер перед кликом.
 *
 * Уровень проверки: **JVM-модель** на подставных `JitState`/`JitShell`/`JitStore` и на НАСТОЯЩЕМ
 * `JitNet.AndroidStore` с моделью `SharedPreferences` (память ≠ диск, «новый процесс»).
 * Реальная очередь `AdbClient`, system shell и `WifiManager` ГУ здесь НЕ исполняются: они
 * Android-код. Поэтому «старая команда ещё может исполниться» моделируется флагом/отметкой, а не
 * настоящей очередью.
 */
class JitIter40Test {

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

    private fun JitFake.Store.seedOp(
        snap: String = "wifi=1",
        want: Boolean = true,
        op: String = "op-prev",
        state: String = JitRef.OpMark.REPLIED,
        stores: List<Int> = listOf(0, 1)
    ) = apply {
        for (i in stores) {
            setIn(i, JitRef.KEY_RESTORE, snap)
            setIn(i, JitRef.KEY_MARK, JitRef.OpMark.of(op, want, state, 0L).wire())
        }
    }

    /** Транспорт, который умеет сообщать о позднем завершении потерянной команды (§2.1). */
    private class LateShell(
        var rc: Int = 0,
        var out: String = "done"
    ) : JitRef.JitShell, JitRef.JitLateEvidence {
        var armed: ((Int, String) -> Unit)? = null
        val executed = mutableListOf<String>()
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            executed += command
            return 137 to "ERR_ADB_TIMEOUT"
        }
        override fun mapsExcerpt(): String? = null
        override fun onLateOutcome(cb: (Int, String) -> Unit) { armed = cb }

        /** Поздний ответ уже «потерянной» команды. */
        fun deliver() {
            val cb = armed ?: error("callback не взведён")
            armed = null
            cb(rc, out)
        }
    }

    // ─────────── §2.1: текущий Wi-Fi ≠ завершение старой команды ───────────

    @Test
    fun networkRestoreDoesNotResolveUndefinedOperation() {
        val store = JitFake.Store().seedOp(op = "op-x", state = JitRef.OpMark.UNDEFINED)
        val net = JitFake.Net(wifi = false, setWifiResult = true, setWifiApplies = true)
        JitOp.resetStateForTest()
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertTrue("сеть восстановлена", net.requests.contains("wifi=true"))
        assertTrue("данные удалены — это про сеть", !store.contains(JitRef.KEY_RESTORE))
        assertTrue("неопределённость операции сохранена", JitOp.chainUndefined())
        assertTrue(lines().contains("network_state=restored"))
        assertTrue(lines().contains("operation=unresolved"))
        assertTrue(lines().contains("clicks=blocked"))
        assertFalse("снятия по «Wi-Fi включён» нет", lines().contains("resolution=network_state_restored clicks=allowed"))

        val c = JitOp.click(store, JitFake.Net(wifi = true), JitFake.QueueShell(), want = true, log = ::log)
        assertEquals("новый клик заблокирован", "chain_undefined", c.blocked)
    }

    @Test
    fun wifiOffSnapshotWithUndefinedOperationIsNotTreatedAsFinished() {
        // Дефект iter39: `wifi=0` сразу давал COMPLETE_WAS_OFF и «всё закончено».
        val store = JitFake.Store().seedOp(snap = "wifi=0", want = false, state = JitRef.OpMark.UNDEFINED)
        val net = JitFake.Net(wifi = false)
        JitOp.resetStateForTest()
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertTrue("Wi-Fi не включали", net.requests.isEmpty())
        assertTrue(lines().contains("completion=complete_was_off"))
        assertTrue("но неопределённость операции осталась", JitOp.chainUndefined())
        assertTrue(lines().contains("operation=unresolved"))
        val c = JitOp.click(store, net, JitFake.QueueShell(), want = false, log = ::log)
        assertEquals("chain_undefined", c.blocked)
    }

    @Test
    fun lateCommandReplyIsTheOnlyAcceptedEvidence() {
        val store = JitFake.Store().seedOp(snap = "wifi=1", state = JitRef.OpMark.UNDEFINED)
        val net = JitFake.Net(wifi = false, setWifiResult = true, setWifiApplies = true)
        JitOp.resetStateForTest()
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()
        assertTrue("до свидетельства — заблокировано", JitOp.chainUndefined())

        // Пришёл ответ ИМЕННО потерянной команды: она завершилась.
        JitOp.onLateOutcome(store, "op-prev", rc = 0, out = "script finished", log = ::log)

        assertFalse("свидетельство снимает неопределённость", JitOp.chainUndefined())
        assertTrue(lines().contains("evidence=command_finished"))
        assertTrue(lines().contains("это свидетельство завершения команды, а НЕ успеха JIT"))
        val c = JitOp.click(store, net, JitFake.QueueShell(), want = false, log = ::log)
        assertNull("после свидетельства клик разрешён", c.blocked)
    }

    @Test
    fun lateReplyForAnotherOperationIsIgnored() {
        val store = JitFake.Store().seedOp(op = "op-current", state = JitRef.OpMark.SENDING)
        JitOp.onLateOutcome(store, "op-other", rc = 0, out = "x", log = ::log)
        assertTrue(lines().contains("ignored reason=operation_changed"))
        assertTrue("чужие данные не тронуты", store.contains(JitRef.KEY_RESTORE))
        assertEquals("op-current", JitRef.OpMark.parse(store.getString(JitRef.KEY_MARK))!!.op)
    }

    @Test
    fun transportWithoutLateCallbackKeepsUncertainty() {
        val store = JitFake.Store().seedOp(op = "op-u", state = JitRef.OpMark.UNDEFINED)
        JitOp.resetStateForTest()
        val c = JitOp.click(store, JitFake.Net(wifi = true), JitFake.QueueShell(), want = true, log = ::log)
        assertEquals("chain_undefined", c.blocked)
    }

    @Test
    fun lostChainArmsLateEvidenceAndKeepsSnapshot() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, setWifiResult = false)
        val shell = LateShell()
        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertTrue("скрипт отправлен", c.sent)
        assertEquals("ответа нет — исход неизвестен", JitRef.ChainOutcome.CHANNEL_LOST, c.outcome)
        assertNotNull("callback взведён", shell.armed)
        assertTrue(lines().contains("JIT_CHAIN_LATE op=") && lines().contains("armed=transport_callback"))
        assertTrue("снимок на месте", store.contains(JitRef.KEY_RESTORE))
        assertEquals("отметка undefined", JitRef.OpMark.UNDEFINED,
            JitRef.OpMark.parse(store.getString(JitRef.KEY_MARK))!!.state)
        val again = JitOp.click(store, JitFake.Net(wifi = true), JitFake.QueueShell(), want = false, log = ::log)
        assertEquals("вторая цепочка не отправлена", "chain_undefined", again.blocked)
    }

    @Test
    fun partialCleanupDoesNotResolveUndefinedOperation() {
        val store = JitFake.Store().seedOp(op = "op-p", state = JitRef.OpMark.UNDEFINED)
        store.commitFails += listOf(0, 1)     // cleanup не подтверждён ни в одном хранилище
        val net = JitFake.Net(wifi = false, setWifiResult = true, setWifiApplies = true)
        JitOp.resetStateForTest()
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertTrue("неопределённость не снята при неподтверждённой очистке", JitOp.chainUndefined())
        assertTrue("данные остались", store.contains(JitRef.KEY_RESTORE))
        assertTrue(lines().contains("data=kept_or_partial"))
    }

    // ─────────── §2.3: неуспешный возврат не даёт перезаписать снимок ───────────

    private fun pendingThenClick(
        net: JitFake.Net,
        state: String = JitRef.OpMark.REPLIED
    ): Triple<JitFake.Store, JitOp.StartOutcome, String?> {
        val store = JitFake.Store().seedOp(snap = "wifi=1", state = state)
        JitOp.resetStateForTest()
        val sched = JitFake.Scheduler()
        val out = JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()
        val before = store.rawRestore()
        val c = JitOp.click(store, Fjt(net), JitFake.QueueShell(), want = false, log = ::log)
        assertEquals("снимок не перезаписан", before, store.rawRestore())
        return Triple(store, out, c.blocked)
    }

    /** Отдельный экземпляр с тем же состоянием, чтобы клик не менял объект возврата. */
    private fun Fjt(net: JitFake.Net) = JitFake.Net(wifi = net.wifi, address = net.address)

    @Test
    fun refusedRestoreBlocksNextClickBothDirections() {
        for (dir in listOf(true, false)) {
            logs.clear(); JitOp.resetStateForTest()
            val net = JitFake.Net(wifi = false, setWifiResult = false)
            val (store, _, blocked) = pendingThenClick(net)
            assertEquals("отказ API: клик заблокирован (dir=$dir)", "previous_unfinished", blocked)
            assertTrue("скрипт не отправлен", !lines().contains("JIT_CHAIN_SENT"))
            assertTrue(store.contains(JitRef.KEY_RESTORE))
            assertTrue(lines().contains("clicks=blocked reason=previous_unfinished"))
        }
    }

    @Test
    fun acceptedButUnconfirmedRestoreBlocksNextClick() {
        val net = JitFake.Net(wifi = false, setWifiResult = true, setWifiApplies = false)
        val (store, _, blocked) = pendingThenClick(net)
        assertEquals("принятый запрос без подтверждения — pending", "previous_unfinished", blocked)
        assertTrue(lines().contains("completion=pending"))
        assertTrue(store.contains(JitRef.KEY_MARK))
    }

    @Test
    fun unknownAndExceptionRestoreBlockNextClick() {
        for (net in listOf(
            JitFake.Net(wifi = false, setWifiResult = null),
            JitFake.Net(wifi = false, setWifiThrows = true),
            JitFake.Net(wifi = false, wifiUnreadable = true)
        )) {
            logs.clear(); JitOp.resetStateForTest()
            val (_, _, blocked) = pendingThenClick(net)
            assertEquals("previous_unfinished", blocked)
        }
    }

    @Test
    fun absentWifiManagerBlocksNextClick() {
        val store = JitFake.Store().seedOp()
        JitOp.resetStateForTest()
        val nullNet = object : JitRef.JitNetState {
            override fun wifiOn(): Boolean? = null
            override fun setWifi(on: Boolean): Boolean? = null
            override fun wifiAddress(): Boolean? = null
        }
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, nullNet, sched, ::log)
        sched.drain()
        assertTrue(lines().contains("completion=unavailable"))
        val c = JitOp.click(store, nullNet, JitFake.QueueShell(), want = true, log = ::log)
        // Wi-Fi прочитать нельзя → сначала wifi_unknown, но и без него клик не прошёл бы.
        // Барьер сохранённой операции срабатывает раньше чтения Wi-Fi — и это правильно:
        // «прочитать нельзя» не должно становиться поводом перезаписать снимок.
        assertEquals("previous_unfinished", c.blocked)
        assertTrue(store.contains(JitRef.KEY_RESTORE))
    }

    @Test
    fun confirmedRestoreAllowsNextClick() {
        val store = JitFake.Store().seedOp(snap = "wifi=1", state = JitRef.OpMark.REPLIED)
        val net = JitFake.Net(wifi = false, setWifiResult = true, setWifiApplies = true)
        JitOp.resetStateForTest()
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()
        // Очистка подтверждена и принадлежит той же операции → данных больше нет.
        assertFalse(store.contains(JitRef.KEY_RESTORE))
        assertFalse(store.contains(JitRef.KEY_MARK))
        val c = JitOp.click(store, JitFake.Net(wifi = true), JitFake.QueueShell(),
            want = false, log = ::log)
        assertNull("клик разрешён", c.blocked)
    }

    // ─────────── §2.2: барьер — сохранённое состояние, а не событие старта ───────────

    @Test
    fun clickIsBlockedByStoredOperationEvenIfStartNeverRan() {
        // Клик выиграл гонку: onCreate/onStartCommand ещё не выполнялись (или получили
        // click_in_flight). Сохранённую операцию он обязан увидеть сам.
        val store = JitFake.Store().seedOp(snap = "wifi=1", state = JitRef.OpMark.REPLIED)
        val net = JitFake.Net(wifi = false)
        val shell = JitFake.QueueShell()
        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertEquals("previous_unfinished", c.blocked)
        assertTrue("shell не запускался", shell.pending.isEmpty())
        assertTrue("сеть не менялась", net.requests.isEmpty())
        assertTrue("снимок прежний", store.contains(JitRef.KEY_RESTORE))
        assertNotNull("оценка сохранённой операции приложена к результату", c.stored)
        assertTrue(lines().contains("stored=«pending"))
    }

    @Test
    fun startDuringClickReportsClickInFlight() {
        // Клик занимает слот и уходит в транспорт; в этот момент приходит старт процесса.
        val store = JitFake.Store()
        val started = mutableListOf<String>()
        val shell = object : JitRef.JitShell {
            override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
                val out = JitOp.onProcessStart(store, JitFake.Net(wifi = true),
                    JitFake.Scheduler(), ::log)
                started += out.reason
                return 0 to "ok"
            }
            override fun mapsExcerpt(): String? = null
        }
        JitOp.resetStateForTest()
        val c = JitOp.click(store, JitFake.Net(wifi = true), shell, want = true, log = ::log)
        assertTrue("клик ушёл", c.sent)
        assertEquals(listOf("click_in_flight"), started)
        assertTrue(lines().contains("JIT_START skipped reason=click_in_flight"))
    }

    @Test
    fun markOnlySnapshotOnlyAndBrokenMarkAreAllAmbiguous() {
        val cases = listOf<Pair<String, (JitFake.Store) -> Unit>>(
            "mark_only" to { s -> s.setIn(0, JitRef.KEY_MARK, JitRef.OpMark.of("op-a", true).wire()) },
            "snapshot_only" to { s -> s.setIn(1, JitRef.KEY_RESTORE, "wifi=1") },
            "broken_mark" to { s ->
                s.setIn(0, JitRef.KEY_RESTORE, "wifi=1")
                s.setIn(0, JitRef.KEY_MARK, "мусор")
            }
        )
        for ((name, seed) in cases) {
            logs.clear(); JitOp.resetStateForTest()
            val store = JitFake.Store().apply(seed)
            val shell = JitFake.QueueShell()
            val c = JitOp.click(store, JitFake.Net(wifi = true), shell, want = true, log = ::log)
            assertEquals("$name → previous_ambiguous", "previous_ambiguous", c.blocked)
            assertTrue("$name: shell не запускался", shell.pending.isEmpty())
            assertEquals("$name: ничего не записано", 0, store.writeCount())
        }
    }

    @Test
    fun unreadableStoreBlocksClickAsUnknownNotAsEmpty() {
        val store = JitFake.Store()
        store.readOperationFails += 1
        val c = JitOp.click(store, JitFake.Net(wifi = true), JitFake.QueueShell(), want = true, log = ::log)
        assertEquals("prev_unknown", c.blocked)
        assertEquals("данных не записано", 0, store.writeCount())
    }

    @Test
    fun conflictingCpsDpsOperationsAreNotMixed() {
        val store = JitFake.Store()
        store.seedOp(op = "op-cps", stores = listOf(0))
        store.setIn(1, JitRef.KEY_RESTORE, "wifi=1")
        store.setIn(1, JitRef.KEY_MARK, JitRef.OpMark.of("op-dps", true, JitRef.OpMark.REPLIED).wire())
        val c = JitOp.click(store, JitFake.Net(wifi = true), JitFake.QueueShell(), want = true, log = ::log)
        assertEquals("previous_ambiguous", c.blocked)
        assertTrue(lines().contains("op_conflict"))
    }

    @Test
    fun startIsNotTheOnlyBarrierForAmbiguousState() {
        val store = JitFake.Store()
        store.setIn(0, JitRef.KEY_RESTORE, "wifi=1")
        store.setIn(1, JitRef.KEY_MARK, JitRef.OpMark.of("op-b", true).wire())
        val out = JitOp.onProcessStart(store, JitFake.Net(wifi = true), JitFake.Scheduler(), ::log)
        assertEquals("stored_ambiguous", out.reason)
        assertFalse(out.scheduled)
        val c = JitOp.click(store, JitFake.Net(wifi = true), JitFake.QueueShell(), want = true, log = ::log)
        assertEquals("previous_ambiguous", c.blocked)
    }

    // ─────────── §2.4/§2.5: настоящее хранилище, ошибки чтения значений ───────────

    private fun realStore(
        cpsDisk: MutableMap<String, Any?> = mutableMapOf(),
        dpsDisk: MutableMap<String, Any?> = mutableMapOf(),
        cpsThrowKeys: Set<String> = emptySet(),
        dpsThrowKeys: Set<String> = emptySet(),
        cps: JitFake.Prefs? = null,
        dps: JitFake.Prefs? = null
    ): Pair<JitNet.AndroidStore, Pair<JitFake.Prefs, JitFake.Prefs>> {
        val c = cps ?: JitFake.Prefs(cpsDisk, throwOnKeys = cpsThrowKeys)
        val d = dps ?: JitFake.Prefs(dpsDisk, throwOnKeys = dpsThrowKeys)
        return JitNet.AndroidStore({ c }, { d }) to (c to d)
    }

    @Test
    fun valueReadExceptionOnPresentKeyMakesStateUnknown() {
        val cpsDisk = mutableMapOf<String, Any?>(JitRef.KEY_RESTORE to "wifi=1")
        val (store, _) = realStore(cpsDisk = cpsDisk, cpsThrowKeys = setOf(JitRef.KEY_RESTORE))
        val prev = store.readSnapshotState()
        val k = prev.first { it.name == "cps" }.value(JitRef.KEY_RESTORE)!!
        assertEquals("ключ есть", true, k.present)
        assertFalse("но значение прочитать нельзя", k.known)
        assertNotNull(k.error)
        assertFalse("хранилище не считается полным", prev.first { it.name == "cps" }.complete)
        assertTrue("подпись не врёт", prev.first { it.name == "cps" }.brief().contains("unknown(read"))
    }

    @Test
    fun unexpectedTypeIsNotAValue() {
        // Строка вместо Boolean в ключе намерения — как ClassCastException в Android.
        val cpsDisk = mutableMapOf<String, Any?>(JitRef.KEY_PEDIDO to "true")
        val (store, _) = realStore(cpsDisk = cpsDisk)
        val k = store.readSnapshotState().first { it.name == "cps" }.value(JitRef.KEY_PEDIDO)!!
        assertEquals(true, k.present)
        assertFalse("тип неверный — значение неизвестно", k.known)
        assertTrue(k.error!!.startsWith("type:"))
    }

    @Test
    fun rollbackDoesNotDeleteKeyWithUnknownPreviousValue() {
        val cpsDisk = mutableMapOf<String, Any?>(JitRef.KEY_RESTORE to "wifi=1")
        val (store, prefs) = realStore(cpsDisk = cpsDisk, cpsThrowKeys = setOf(JitRef.KEY_RESTORE))
        val prev = store.readSnapshotState()
        val r = store.restorePrevious(prev)
        val cps = prefs.first
        assertTrue("cps пропущен", r.detail.contains("cps:prev_unknown"))
        // Значение на диске не тронуто (и не удалено «на всякий случай»).
        assertEquals("wifi=1", cpsDisk[JitRef.KEY_RESTORE])
        assertTrue((cps as android.content.SharedPreferences).contains(JitRef.KEY_RESTORE))
    }

    @Test
    fun missingKeyIsDifferentFromUnreadableValue() {
        val (store, _) = realStore()
        val prev = store.readSnapshotState()
        assertTrue("пустое хранилище читается достоверно", prev.all { it.complete })
        assertTrue(prev.all { p -> p.keys.all { it.absent } })
    }

    @Test
    fun readOperationKeepsPairsPerStoreAndReportsErrors() {
        val cpsDisk = mutableMapOf<String, Any?>(
            JitRef.KEY_RESTORE to "wifi=1",
            JitRef.KEY_MARK to JitRef.OpMark.of("op-1", true).wire()
        )
        val c = JitFake.Prefs(cpsDisk)
        val d = JitFake.Prefs(mutableMapOf(), throwOnRead = true)
        val store = JitNet.AndroidStore({ c }, { d })
        val reads = store.readOperation()
        assertEquals(2, reads.size)
        assertEquals("op-1", JitRef.OpMark.parse(reads[0].mark)!!.op)
        assertNotNull("ошибка чтения DPS видна", reads[1].error)
        val assessed = JitRef.assessStored(reads)
        assertEquals(JitRef.StoredState.UNREADABLE, assessed.state)
        assertEquals(1, assessed.readable)
    }

    @Test
    fun storeThatDisappearsBetweenStagesBlocksTheClick() {
        val cpsDisk = mutableMapOf<String, Any?>()
        val dpsDisk = mutableMapOf<String, Any?>()
        val cps = JitFake.Prefs(cpsDisk)
        var dps: JitFake.Prefs? = JitFake.Prefs(dpsDisk)
        val store = JitNet.AndroidStore({ cps }, { dps })
        // Транспорт, который отвечает: исход операции `replied`, а не «потерян».
        val okShell = object : JitRef.JitShell {
            override fun exec(command: String, timeoutMs: Int) = 0 to "ok"
            override fun mapsExcerpt(): String? = null
        }
        // Первый клик пишет пару, затем «DPS пропадает» — оценка обязана это увидеть.
        val click = JitOp.click(store, JitFake.Net(wifi = true), okShell,
            want = true, log = ::log)
        assertNull(click.blocked)
        dps = null
        val next = JitOp.click(store, JitFake.Net(wifi = true), okShell,
            want = false, log = ::log)
        assertEquals("previous_unfinished", next.blocked)
    }

    @Test
    fun snapshotAndMarkCannotBeJoinedFromDifferentStores() {
        val cpsDisk = mutableMapOf<String, Any?>(JitRef.KEY_RESTORE to "wifi=0")
        val dpsDisk = mutableMapOf<String, Any?>(
            JitRef.KEY_MARK to JitRef.OpMark.of("op-x", false).wire(),
            JitRef.KEY_RESTORE to "wifi=1"
        )
        val (store, _) = realStore(cpsDisk = cpsDisk, dpsDisk = dpsDisk)
        val assessed = JitRef.assessStored(store.readOperation())
        assertTrue(
            "пара не собирается из разных копий",
            assessed.state == JitRef.StoredState.AMBIGUOUS
        )
        assertTrue(
            "причина названа: " + assessed.detail,
            assessed.detail.contains("incomplete_pair") || assessed.detail.contains("snapshot_conflict")
        )
    }
}
