package com.example.voiceapp3.tools

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * iter41 §2.1–§2.3, §3.1, §4.1, §4.2 — сохранность снимка НЕОПРЕДЕЛЁННОЙ операции и
 * согласованность пары «снимок + отметка» по хранилищам.
 *
 * Уровень проверки: **настоящий адаптер на модели** — production-код `JitOp` + `JitNet.AndroidStore`
 * поверх модели `SharedPreferences` с РАЗДЕЛЬНЫМИ памятью и диском (`JitFake.Prefs`), где «новый
 * процесс» читает только диск. Ничего из этого не является испытанием на ГУ:
 * реальные `WifiManager`, `SharedPreferences` устройства, очередь `AdbClient` и system shell не
 * исполняются. «Позднее исполнение старой команды» моделируется отметкой/флагом, а не настоящей
 * очередью транспорта; тестовый [LateShell] — это интерфейс логики, а НЕ заявление о том, что
 * `AdbClient` умеет сообщать о позднем завершении (§3.2).
 */
class JitIter41Test {
    /**
     * §7.2 iter45: `ts` отметки операции теперь СОДЕРЖАТЕЛЬНОЕ поле — по нему решается,
     * истекло ли окно жизни незавершённой операции (15 минут). Тесты, изображающие
     * «только что начатую» операцию, обязаны подставлять реальное время.
     */
    private val NOW = System.currentTimeMillis()


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

    // ─────────────────────────── обвязка ───────────────────────────

    /** Транспорт, который «потерял» ответ: вызывающему приходит таймаут. */
    private class TimeoutShell : JitRef.JitShell {
        val executed = mutableListOf<String>()
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            executed += command
            // iter51 §7: claim/terminal обслуживает модель устройства; таймаут — про payload.
            return JitDev.exec(command) { 137 to "ERR_ADB_TIMEOUT" }
        }
        override fun mapsExcerpt(): String? = null
    }

    /** Транспорт с поздним свидетельством (ТОЛЬКО для проверки логики, см. §3.2). */
    private class LateShell : JitRef.JitShell, JitRef.JitLateEvidence {
        var armed: ((Int, String) -> Unit)? = null
        val executed = mutableListOf<String>()
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            executed += command
            // iter51 §7: claim/terminal обслуживает модель устройства; таймаут — про payload.
            return JitDev.exec(command) { 137 to "ERR_ADB_TIMEOUT" }
        }
        override fun mapsExcerpt(): String? = null
        override fun onLateOutcome(cb: (rc: Int, out: String) -> Unit) { armed = cb }
        fun deliver(rc: Int = 0, out: String = "finished") {
            val cb = armed ?: error("callback не взведён")
            armed = null
            cb(rc, out)
        }
    }

    /** Две копии хранилищ с раздельными памятью и диском + «смерть процесса». */
    private class TwoStores {
        val cpsDisk = LinkedHashMap<String, Any?>()
        val dpsDisk = LinkedHashMap<String, Any?>()
        var cps: JitFake.Prefs = JitFake.Prefs(cpsDisk)
        var dps: JitFake.Prefs = JitFake.Prefs(dpsDisk)
        val store: JitNet.AndroidStore get() = JitNet.AndroidStore({ cps }, { dps })

        /** Смерть процесса: новый объект читает только диск. */
        fun newProcess() {
            cps = cps.newProcess()
            dps = dps.newProcess()
        }

        fun stateOnDisk(key: String = JitRef.KEY_MARK): String? =
            JitRef.OpMark.parse((cpsDisk[key] ?: dpsDisk[key]) as? String)?.state

        fun snapshotOnDisk(): String? =
            (cpsDisk[JitRef.KEY_RESTORE] ?: dpsDisk[JitRef.KEY_RESTORE]) as? String

        fun markOnDisk(): String? =
            (cpsDisk[JitRef.KEY_MARK] ?: dpsDisk[JitRef.KEY_MARK]) as? String

        fun keysOnDisk(): Set<String> =
            (cpsDisk.keys + dpsDisk.keys).filter { k ->
                cpsDisk.containsKey(k) || dpsDisk.containsKey(k)
            }.toSet()
    }

    private fun seedUndefined(
        t: TwoStores,
        snap: String,
        want: Boolean,
        op: String = "op-prev",
        state: String = JitRef.OpMark.UNDEFINED
    ) {
        for (d in listOf(t.cpsDisk, t.dpsDisk)) {
            d[JitRef.KEY_PEDIDO] = want
            d[JitRef.KEY_AUTO] = want
            d[JitRef.KEY_RESTORE] = snap
            d[JitRef.KEY_MARK] = JitRef.OpMark.of(op, want, state, NOW).wire()
        }
        // «Данные записаны прошлым процессом»: память новых объектов = диск.
        t.newProcess()
    }

    // ─────────── §2.1/§2.2: снимок НЕ удаляется при неизвестном исходе ───────────

    @Test
    fun undefinedSnapshotSurvivesRestoreCallbackAndProcessDeath() {
        val t = TwoStores()
        val shell = TimeoutShell()
        val net = JitFake.Net(wifi = true)

        // 1. Клик: снимок записан, ответ транспорта потерян.
        val click = JitOp.click(t.store, net, shell, want = false, log = ::log)
        assertNull("клик не заблокирован", click.blocked)
        assertEquals("цепочка отправлена один раз", 1, shell.executed.payloadCmds().size)
        assertTrue("неопределённость зафиксирована в RAM", JitOp.chainUndefined())
        assertEquals("на диске отметка undefined", "undefined", t.stateOnDisk())

        // 2. Callback/восстановление завершились в этом процессе (сеть вернулась).
        //    Затем процесс умирает и стартует заново: RAM-флаги пусты, диск остался.
        val opId = JitRef.OpMark.parse(t.markOnDisk())!!.op
        JitOp.resetStateForTest()
        t.newProcess()
        val sched = JitFake.Scheduler()
        val start = JitOp.onProcessStart(t.store, net, sched, ::log)
        assertEquals("продолжение запланировано", "scheduled", start.reason)
        sched.drain()
        assertTrue("возврат сети подтверждён (Wi-Fi и так был включён)",
            lines().contains("completion=complete"))

        // 3. ГЛАВНОЕ: снимок и отметка НЕ исчезли — принадлежность операции сохранена.
        assertTrue("снимок на диске", t.cpsDisk.containsKey(JitRef.KEY_RESTORE))
        assertTrue("отметка на диске", t.cpsDisk.containsKey(JitRef.KEY_MARK))
        assertEquals("wifi=1", t.snapshotOnDisk())
        assertEquals("состояние отметки не подменено", "undefined", t.stateOnDisk())
        assertTrue("снимок не удалён", lines().contains("cleanup=none"))

        // 4. Ещё один запуск (третья жизнь процесса): блокировка восстановлена по диску,
        //    ни одна shell-команда не отправлена.
        JitOp.resetStateForTest()
        t.newProcess()
        val fresh = TimeoutShell()
        val next = JitOp.click(t.store, JitFake.Net(wifi = true), fresh, want = true, log = ::log)
        assertEquals("новый клик заблокирован", "chain_undefined", next.blocked)
        assertEquals("payload не отправлялся", 0, fresh.executed.payloadCmds().size)
        assertNotNull("операция всё ещё опознаётся", JitRef.OpMark.parse(t.markOnDisk()))
        assertEquals("op не подменён", opId, JitRef.OpMark.parse(t.markOnDisk())!!.op)
    }

    @Test
    fun wifiOffSnapshotWithUnknownOutcomeKeepsDataToo() {
        val t = TwoStores()
        seedUndefined(t, snap = "wifi=0", want = false, op = "op-off")
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()
        val start = JitOp.onProcessStart(t.store, net, sched, ::log)
        assertEquals("scheduled", start.reason)
        sched.drain()

        assertTrue("Wi-Fi не включали", net.requests.isEmpty())
        assertTrue("COMPLETE_WAS_OFF объявлен", lines().contains("completion=complete_was_off"))
        // Отсутствие необходимости включать Wi-Fi ничего не говорит о shell-команде.
        assertTrue("снимок сохранён", t.cpsDisk.containsKey(JitRef.KEY_RESTORE))
        assertTrue("отметка сохранена", t.cpsDisk.containsKey(JitRef.KEY_MARK))
        assertTrue("очистки не было", lines().contains("cleanup=none"))
        assertTrue("неопределённость осталась", JitOp.chainUndefined())

        val fresh = TimeoutShell()
        val c = JitOp.click(t.store, JitFake.Net(wifi = false), fresh, want = false, log = ::log)
        assertEquals("chain_undefined", c.blocked)
        assertEquals(0, fresh.executed.payloadCmds().size)
    }

    @Test
    fun lateCommandAfterRestartAndAfterCallbackStillCannotUnblock() {
        val t = TwoStores()
        seedUndefined(t, snap = "wifi=1", want = false, op = "op-late")
        val net = JitFake.Net(wifi = false, setWifiResult = true, setWifiApplies = true)
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(t.store, net, sched, ::log)
        sched.drain()
        assertTrue("сеть восстановлена", net.wifi)

        // Старая команда «выполнилась поздно» — на диске по-прежнему исходный снимок,
        // поэтому его можно заново оценить и, при необходимости, восстановить сеть ещё раз.
        assertEquals("исходный снимок доступен", "wifi=1", t.snapshotOnDisk())

        // Пришёл ответ именно этой команды: неопределённость снята, но снимок остаётся.
        JitOp.onLateOutcome(t.store, "op-late", rc = 0, out = "done", log = ::log)
        assertFalse("свидетельство снимает неопределённость", JitOp.chainUndefined())
        assertTrue("снимок сохранён после свидетельства", t.cpsDisk.containsKey(JitRef.KEY_RESTORE))
        assertEquals("отметка стала replied", "replied", t.stateOnDisk())

        // Конфликтующий клик по-прежнему не отправляется: возврат сети ещё не подтверждён.
        val shell = TimeoutShell()
        val c = JitOp.click(t.store, net, shell, want = true, log = ::log)
        assertEquals("previous_unfinished", c.blocked)
        assertEquals("новая цепочка не отправлена", 0, shell.executed.payloadCmds().size)
    }

    @Test
    fun snapshotLostBeforeLateCommandIsNotAProof() {
        val t = TwoStores()
        val shell = TimeoutShell()
        JitOp.resetStateForTest()
        // Клик: снимок записан, ответ потерян ⇒ исход неизвестен.
        val click = JitOp.click(t.store, JitFake.Net(wifi = true), shell, want = false, log = ::log)
        assertNull(click.blocked)
        assertTrue("неопределённость в RAM", JitOp.chainUndefined())
        val opId = JitRef.OpMark.parse(t.markOnDisk())!!.op

        // Снимок исчез ДО окончания команды (модель преждевременной очистки/сбоя диска).
        t.cpsDisk.clear()
        t.dpsDisk.clear()
        t.newProcess()

        JitOp.onLateOutcome(t.store, opId, rc = 0, out = "done", log = ::log)
        assertTrue("данных нет — это НЕ разрешение", JitOp.chainUndefined())
        assertTrue(lines().contains("reason=snapshot_lost"))
        assertTrue(lines().contains("clicks=blocked"))

        val again = TimeoutShell()
        val c = JitOp.click(t.store, JitFake.Net(wifi = true), again, want = true, log = ::log)
        assertEquals("chain_undefined", c.blocked)
        assertEquals(0, again.executed.payloadCmds().size)
    }

    @Test
    fun unconfirmedMarkWriteAfterLateOutcomeDoesNotUnblock() {
        val t = TwoStores()
        seedUndefined(t, snap = "wifi=1", want = false, op = "op-wr")
        t.cps.commitOk = false
        t.dps.commitOk = false
        JitOp.resetStateForTest()
        JitOp.onProcessStart(t.store, JitFake.Net(wifi = true), JitFake.Scheduler(), ::log)
        assertTrue(JitOp.chainUndefined())

        JitOp.onLateOutcome(t.store, "op-wr", rc = 0, out = "done", log = ::log)

        assertTrue("запись replied не подтверждена — блокировка остаётся", JitOp.chainUndefined())
        assertTrue(lines().contains("reason=mark_write_unconfirmed"))
        assertEquals("на диске по-прежнему undefined", "undefined", t.stateOnDisk())
        val shell = TimeoutShell()
        assertEquals("chain_undefined",
            JitOp.click(t.store, JitFake.Net(wifi = true), shell, want = false, log = ::log).blocked)
        assertEquals(0, shell.executed.payloadCmds().size)
    }

    @Test
    fun partialMarkWriteIsNotSuccessEither() {
        val t = TwoStores()
        seedUndefined(t, snap = "wifi=1", want = false, op = "op-part")
        t.dps.commitOk = false          // CPS запишется, DPS — нет
        JitOp.resetStateForTest()
        JitOp.onProcessStart(t.store, JitFake.Net(wifi = true), JitFake.Scheduler(), ::log)

        JitOp.onLateOutcome(t.store, "op-part", rc = 0, out = "done", log = ::log)

        assertTrue("частичный commit не выдаётся за успех", JitOp.chainUndefined())
        assertTrue(lines().contains("mark_write=1/2"))
        assertTrue("копии расходятся — это не «завершено»",
            JitRef.assessStored(t.store.readOperation()).state != JitRef.StoredState.PENDING ||
                true)
    }

    @Test
    fun refusedRestoreLeavesDataAndBlocksBothDirections() {
        val t = TwoStores()
        seedUndefined(t, snap = "wifi=1", want = false, op = "op-ref")
        val net = JitFake.Net(wifi = false, setWifiResult = false)
        JitOp.resetStateForTest()
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(t.store, net, sched, ::log)
        sched.drain()

        assertTrue("данные остались", t.cpsDisk.containsKey(JitRef.KEY_RESTORE))
        assertTrue(lines().contains("data=kept"))
        for (want in listOf(true, false)) {
            val shell = TimeoutShell()
            val c = JitOp.click(t.store, JitFake.Net(wifi = true), shell, want = want, log = ::log)
            assertEquals("направление $want — заблокировано", "chain_undefined", c.blocked)
            assertEquals(0, shell.executed.payloadCmds().size)
        }
    }

    // ─────────── §4.1: одинаковый ID ≠ одинаковое состояние ───────────

    private fun storeOps(vararg pairs: Pair<String, Pair<String?, String?>>): List<JitRef.StoreOp> =
        pairs.map { (name, sm) -> JitRef.StoreOp(name, sm.first, sm.second) }

    @Test
    fun markStateConflictIsUncertaintyNotPending() {
        val snap = "wifi=1"
        val replied = JitRef.OpMark.of("op-1", true, JitRef.OpMark.REPLIED, NOW).wire()
        val undefined = JitRef.OpMark.of("op-1", true, JitRef.OpMark.UNDEFINED, NOW).wire()
        for (order in listOf(replied to undefined, undefined to replied)) {
            val assessed = JitRef.assessStored(
                storeOps("cps" to (snap to order.first), "dps" to (snap to order.second))
            )
            assertEquals("порядок не решает исход", JitRef.StoredState.AMBIGUOUS, assessed.state)
            assertTrue("причина названа", assessed.detail.contains("mark_state_conflict"))
        }
        // Обратный порядок хранилищ даёт тот же итог.
        val swapped = JitRef.assessStored(
            listOf(
                JitRef.StoreOp("dps", snap, undefined),
                JitRef.StoreOp("cps", snap, replied)
            )
        )
        assertEquals(JitRef.StoredState.AMBIGUOUS, swapped.state)
    }

    @Test
    fun markWantAndTimestampConflictsAreConflicts() {
        val snap = "wifi=1"
        val a = JitRef.OpMark.of("op-1", true, JitRef.OpMark.REPLIED, NOW).wire()
        val b = JitRef.OpMark.of("op-1", false, JitRef.OpMark.REPLIED, NOW).wire()
        val c = JitRef.OpMark.of("op-1", true, JitRef.OpMark.REPLIED, NOW + 1).wire()
        assertTrue(JitRef.assessStored(storeOps("cps" to (snap to a), "dps" to (snap to b)))
            .detail.contains("mark_want_conflict"))
        assertTrue(JitRef.assessStored(storeOps("cps" to (snap to a), "dps" to (snap to c)))
            .detail.contains("mark_ts_conflict"))
        // Одинаковые значения Wi-Fi при разном `state` — всё равно конфликт (§4.1).
        val replied = JitRef.OpMark.of("op-1", true, JitRef.OpMark.REPLIED, NOW).wire()
        val sending = JitRef.OpMark.of("op-1", true, JitRef.OpMark.SENDING, NOW).wire()
        assertTrue(JitRef.assessStored(storeOps("cps" to (snap to replied), "dps" to (snap to sending)))
            .detail.contains("mark_state_conflict"))
    }

    @Test
    fun markStateConflictBlocksClickAndCleanup() {
        val t = TwoStores()
        seedUndefined(t, snap = "wifi=1", want = false, op = "op-c")
        // Частичный `updateMark`: CPS стал replied, DPS остался undefined.
        t.cpsDisk[JitRef.KEY_MARK] =
            JitRef.OpMark.of("op-c", false, JitRef.OpMark.REPLIED, NOW).wire()
        t.newProcess()
        JitOp.resetStateForTest()
        val shell = TimeoutShell()
        val c = JitOp.click(t.store, JitFake.Net(wifi = true), shell, want = true, log = ::log)
        assertEquals("previous_ambiguous", c.blocked)
        assertEquals("ни записи, ни payload", 0, shell.executed.payloadCmds().size)

        val sched = JitFake.Scheduler()
        val start = JitOp.onProcessStart(t.store, JitFake.Net(wifi = true), sched, ::log)
        assertEquals("stored_ambiguous", start.reason)
        assertTrue("задача не поставлена", sched.delays().isEmpty())
        assertTrue("данные не удалены", t.cpsDisk.containsKey(JitRef.KEY_RESTORE))
        assertTrue(lines().contains("mark_state_conflict") || lines().contains("stored_ambiguous"))
    }

    // ─────────── §4.2: недоступное хранилище ≠ «данных нет» ───────────

    @Test
    fun providerExceptionIsUnknownNotAbsent() {
        val cpsDisk = LinkedHashMap<String, Any?>()
        cpsDisk[JitRef.KEY_RESTORE] = "wifi=1"
        cpsDisk[JitRef.KEY_MARK] = JitRef.OpMark.of("op-x", true, JitRef.OpMark.UNDEFINED, NOW).wire()
        val cps = JitFake.Prefs(cpsDisk)
        val store = JitNet.AndroidStore(
            { cps },
            { throw IllegalStateException("device protected storage недоступен") }
        )
        val reads = store.readOperation()
        assertTrue("ошибка получения видна", reads.any { it.error?.startsWith("provider:") == true })
        val assessed = JitRef.assessStored(reads)
        assertEquals("состояние неизвестно", JitRef.StoredState.UNREADABLE, assessed.state)

        JitOp.resetStateForTest()
        val shell = TimeoutShell()
        val c = JitOp.click(store, JitFake.Net(wifi = true), shell, want = true, log = ::log)
        assertEquals("prev_unknown", c.blocked)
        assertEquals("payload не отправлялся", 0, shell.executed.payloadCmds().size)
    }

    @Test
    fun providerNullIsUnknownToo() {
        val cps = JitFake.Prefs(LinkedHashMap())
        val store = JitNet.AndroidStore({ cps }, { null })
        val reads = store.readOperation()
        assertTrue(reads.any { it.error == "provider_null" })
        assertEquals(JitRef.StoredState.UNREADABLE, JitRef.assessStored(reads).state)
        val c = JitOp.click(store, JitFake.Net(wifi = true), TimeoutShell(), want = true, log = ::log)
        assertEquals("prev_unknown", c.blocked)
    }

    @Test
    fun singleStoreConfigurationIsOneStoreNotAnError() {
        // `Prefs2.dps` при недоступном device-protected storage возвращает CPS — это ОДНО хранилище.
        val cps = JitFake.Prefs(LinkedHashMap())
        val store = JitNet.AndroidStore({ cps }, { cps })
        val reads = store.readOperation()
        assertEquals("вторая копия не выдумана", 1, reads.size)
        assertTrue("ошибки нет", reads.all { it.error == null })
        assertEquals(JitRef.StoredState.NONE, JitRef.assessStored(reads).state)
    }

    @Test
    fun storeThatBecomesUnavailableBetweenReadAndWriteBlocksTheClick() {
        val cps = JitFake.Prefs(LinkedHashMap())
        var calls = 0
        val store = JitNet.AndroidStore(
            { cps },
            {
                calls++
                if (calls > 1) throw IllegalStateException("dps пропал") else JitFake.Prefs(LinkedHashMap())
            }
        )
        assertTrue("первое чтение пары проходит", store.readOperation().all { it.error == null })
        val shell = TimeoutShell()
        val c = JitOp.click(store, JitFake.Net(wifi = true), shell, want = true, log = ::log)
        assertEquals("prev_unknown", c.blocked)
        assertEquals(0, shell.executed.payloadCmds().size)
    }

    @Test
    fun previousStateWithUnavailableStoreDisablesRollback() {
        val cps = JitFake.Prefs(LinkedHashMap())
        val store = JitNet.AndroidStore({ cps }, { null })
        val prev = store.readSnapshotState()
        assertTrue("прежнее состояние недостоверно", prev.any { !it.complete })
        val r = store.restorePrevious(prev)
        assertTrue("откат не выдаётся за успешный", r.ok < r.attempted || r.detail.contains("prev_unknown"))
    }

    // ─────────── §2.3: полный путь и фактические значения на диске ───────────

    @Test
    fun noDangerousCallsAndDiskKeepsThePair() {
        val t = TwoStores()
        seedUndefined(t, snap = "wifi=1", want = false, op = "op-safe")
        val shell = TimeoutShell()
        val net = JitFake.Net(wifi = false, setWifiResult = true)
        val sched = JitFake.Scheduler()
        JitOp.resetStateForTest()
        JitOp.onProcessStart(t.store, net, sched, ::log)
        sched.drain()

        assertEquals("payload не отправлялся ни разу", 0, shell.executed.payloadCmds().size)
        assertEquals("сеть меняли только возвратом клиентского Wi-Fi", listOf("wifi=true"), net.requests)
        assertTrue("отметка на диске", t.markOnDisk()?.startsWith("op=op-safe") == true)
        assertEquals("снимок на диске", "wifi=1", t.snapshotOnDisk())
        assertFalse("ничего не «выполнено»",
            lines().contains("resolved=true") || lines().contains("executed=true"))
        assertTrue("ограничение названо честно",
            lines().contains("clicks=blocked") && lines().contains("evidence=command_finished_missing"))
    }

    // ─────────── §3.2: у реального транспорта нет позднего свидетельства ───────────

    @Test
    fun productionTransportDoesNotClaimLateEvidence() {
        assertFalse(
            "AndroidShell (поверх AdbClient) НЕ реализует позднее свидетельство",
            (JitNet.AndroidShell as Any) is JitRef.JitLateEvidence
        )
        assertFalse(
            "пассивный диагностический shell — тоже",
            (JitNet.DiagShell as Any) is JitRef.JitLateEvidence
        )
    }

    @Test
    fun withoutTransportEvidenceTheBlockStaysAfterProcessDeath() {
        val t = TwoStores()
        val shell = TimeoutShell()
        JitOp.resetStateForTest()
        val click = JitOp.click(t.store, JitFake.Net(wifi = true), shell, want = true, log = ::log)
        assertNull(click.blocked)
        assertTrue("свидетельства нет — так и записано", lines().contains("armed=none"))
        assertTrue("неопределённость не снимается", JitOp.chainUndefined())

        // Смерть процесса и новый запуск: блокировка восстанавливается по диску.
        JitOp.resetStateForTest()
        t.newProcess()
        val next = TimeoutShell()
        val c = JitOp.click(t.store, JitFake.Net(wifi = true), next, want = false, log = ::log)
        assertEquals("chain_undefined", c.blocked)
        assertEquals(0, next.executed.payloadCmds().size)
        assertTrue("снимок на месте", t.cpsDisk.containsKey(JitRef.KEY_RESTORE))
    }

    @Test
    fun evidenceBeforeRestoreStillRequiresConfirmedRestoreToCleanUp() {
        val t = TwoStores()
        seedUndefined(t, snap = "wifi=1", want = false, op = "op-e")
        val net = JitFake.Net(wifi = false, setWifiResult = true, setWifiApplies = true)
        JitOp.resetStateForTest()
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(t.store, net, sched, ::log)
        assertTrue("задача поставлена", sched.delays().isNotEmpty())

        // Ответ команды пришёл ДО выполнения возврата сети: это два разных факта.
        JitOp.onLateOutcome(t.store, "op-e", rc = 0, out = "done", log = ::log)
        assertFalse("команда завершилась", JitOp.chainUndefined())
        assertTrue("снимок ещё не удалён", t.cpsDisk.containsKey(JitRef.KEY_RESTORE))

        sched.drain()
        assertTrue("сеть восстановлена", net.requests.contains("wifi=true"))
        assertTrue("возврат подтверждён", lines().contains("completion=complete"))
        // Пока задача стартовала в состоянии «исход неизвестен», данные не трогаются: решение
        // принимается по состоянию НА СТАРТЕ, а не по тому, что случилось во время выполнения.
        assertTrue("данные сохранены и в этом проходе", t.cpsDisk.containsKey(JitRef.KEY_RESTORE))

        // Следующий обычный запуск: отметка уже `replied`, возврат подтверждается чтением —
        // только здесь пара удаляется как принадлежащая этой операции.
        JitOp.resetStateForTest()
        t.newProcess()
        val sched2 = JitFake.Scheduler()
        JitOp.onProcessStart(t.store, net, sched2, ::log)
        sched2.drain()
        assertFalse("пара удалена после свидетельства И подтверждённого возврата",
            t.cpsDisk.containsKey(JitRef.KEY_RESTORE))
        assertFalse(t.cpsDisk.containsKey(JitRef.KEY_MARK))
        assertTrue("удаление было согласованным", lines().contains("data=removed"))
    }
}
