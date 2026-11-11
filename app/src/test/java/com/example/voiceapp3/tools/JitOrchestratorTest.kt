package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Оркестратор целиком: клик → снимок → транспорт (с очередью!) → старт процесса → возврат сети →
 * сверка. Подставные адаптеры — [JitFake], исполнение скрипта — настоящий `sh` ([JitIsoStand]).
 *
 * Границы честно: это НЕ прогон на ГУ. `WifiManager`, `startSoftAp`, adbd и zygote здесь
 * подставные; проверяется поведение нашего кода и обработка отказов (REVIEW §0.1(10)).
 */
class JitOrchestratorTest {

    private val BOOT = "8f1c2b44-1c2d-4e5f-9a0b-11c2d3e4f5a6"
    private val OTHER_BOOT = "0a9b8c7d-6e5f-4a3b-2c1d-0e9f8a7b6c5d"

    private fun stand() = JitIsoStand(rules = 0)

    private fun click(
        store: JitFake.Store,
        net: JitFake.Net,
        shell: JitShell,
        want: Boolean = true,
        boot: String = BOOT,
        log: MutableList<String> = mutableListOf()
    ): JitOp.Click = JitOp.click(store, net, shell, want, boot, 1_000L, log = { log += it })

    private fun start(
        store: JitFake.Store,
        net: JitFake.Net,
        shell: JitShell,
        boot: String = BOOT,
        waiter: JitFake.Waiter = JitFake.Waiter(),
        log: MutableList<String> = mutableListOf()
    ): JitOp.StartOutcome = JitOp.onProcessStart(store, net, shell, boot, waiter, log = { log += it })

    // ─────────────────────────── 1. снимок и порядок ───────────────────────────

    @Test
    fun clickSavesSnapshotThenRunsReferenceChain() {
        val stand = stand()
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val log = mutableListOf<String>()
        val res = click(store, net, stand.Shell(), log = log)

        assertEquals(JitOp.ClickResult.STARTED, res.result)
        val op = store.op()!!
        assertEquals("снимок ДО изменений", JitRef.Net(true, false), op.net)
        assertEquals("фаза после ответа транспорта", JitRef.Phase.SENT, op.phase)
        assertEquals("ключ референса тоже записан", "wifi=1,ap=0", store.getString(JitRef.KEY_RESTORE))
        assertEquals(true, store.getBool(JitRef.KEY_AUTO))

        val calls = stand.calls()
        assertEquals("svc wifi disable", calls.first())
        assertEquals("setprop ctl.restart zygote", calls.last())
        assertTrue(calls.contains("setprop dalvik.vm.usejit true"))
        assertFalse("никаких persist", stand.callsText().contains("persist."))
    }

    @Test
    fun snapshotIsNotAcceptedWithoutReadback() {
        val stand = stand()
        val store = JitFake.Store().apply { commitReportsFalse = true }
        val net = JitFake.Net()
        val log = mutableListOf<String>()
        val res = click(store, net, stand.Shell(), log = log)
        assertEquals(JitOp.ClickResult.SNAPSHOT_FAILED, res.result)
        assertTrue("ни одной команды устройству", stand.calls().isEmpty())
        assertNull("запрос не выставлен", store.getBool(JitRef.KEY_PEDIDO))
        assertTrue(log.any { it.contains("JIT_SNAPSHOT failed") })
    }

    @Test
    fun partialStoreWritesAreDetected() {
        // «Дошло только в одно хранилище» — безопасно: снимок подтверждён обратным чтением.
        for (variant in listOf("cps", "dps")) {
            val stand = stand()
            val store = JitFake.Store()
            if (variant == "cps") store.cpsFails = true else store.dpsFails = true
            val res = click(store, JitFake.Net(wifi = true, ap = false), stand.Shell())
            assertEquals("вариант $variant: снимок подтверждён чтением", JitOp.ClickResult.STARTED, res.result)
            assertEquals("и скрипт отправлен", "setprop ctl.restart zygote", stand.calls().last())
        }
        // «commit вернул false» и «снимок не подтверждён обратным чтением» — Wi-Fi НЕ выключаем.
        run {
            val stand = stand()
            val store = JitFake.Store().apply { commitReportsFalse = true }
            val res = click(store, JitFake.Net(), stand.Shell())
            assertEquals(JitOp.ClickResult.SNAPSHOT_FAILED, res.result)
            assertTrue("ни одной команды устройству", stand.calls().isEmpty())
        }
        // Отказ записи на самом первом ключе (жизнь процесса кончилась на записи снимка).
        run {
            val stand = stand()
            val store = JitFake.Store().apply { writesUntilFailure = 0 }
            val res = click(store, JitFake.Net(), stand.Shell())
            assertEquals(JitOp.ClickResult.SNAPSHOT_FAILED, res.result)
            assertTrue(stand.calls().isEmpty())
        }
        // Исключение из хранилища не должно выключать Wi-Fi (адаптер обязан его пережить).
        run {
            val stand = stand()
            val store = JitFake.Store().apply { throwOnWrite = true }
            runCatching { click(store, JitFake.Net(), stand.Shell()) }
            assertTrue("исключение хранилища не запускает скрипт", stand.calls().isEmpty())
        }
    }

    @Test
    fun unknownNetworkStateMeansNoChain() {
        val stand = stand()
        val store = JitFake.Store()
        val res = click(store, JitFake.Net(stateErrors = true), stand.Shell())
        assertEquals(JitOp.ClickResult.REFUSED_STATE_UNKNOWN, res.result)
        assertTrue(stand.calls().isEmpty())
        assertNull(store.op())
    }

    // ─────────────────────────── 2. очередь транспорта и поздний shell ───────────────────────────

    @Test
    fun timeoutWithQueuedShellDoesNotTriggerEarlyRestore() {
        val stand = stand()
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand)   // всё уходит в очередь, вызывающий получает таймаут

        val res = click(store, net, shell)
        assertEquals(JitOp.ClickResult.STARTED, res.result)
        assertEquals(
            "транспорт потерян — и это всё, что известно",
            JitRef.ChainOutcome.CHANNEL_LOST, res.run!!.outcome
        )
        assertTrue("скрипт ещё в очереди", shell.pending.isNotEmpty())
        assertTrue("Wi-Fi ещё включён", net.wifi)

        // процесс перезапустился (по любой причине), загрузка та же, сеть ещё не менялась
        val log = mutableListOf<String>()
        val before = store.rawOp()
        val writes = store.writeCount()
        val out = start(store, net, shell, log = log)
        assertEquals(
            "нет ответа транспорта ⇒ НЕ возвращаем сеть и ничего не пишем",
            JitRef.StartDecision.HOLD_UNSAFE_PHASE, out.decision
        )
        assertTrue("ни одного сетевого запроса", net.requests.isEmpty())
        assertEquals("снимок сохранён и не переписан", before, store.rawOp())
        assertEquals("ноль записей в префы", writes, store.writeCount())
        assertTrue(log.any { it.contains("reason=unsafe_phase") })
        // поздняя команда всё-таки доехала: сеть выключена, но повторного возврата не будет,
        // пока владелец не разберётся с фазой — это и есть честный блок вместо фиктивного успеха
        shell.drain()
        net.wifi = false
        val again = start(store, net, shell)
        assertEquals(JitRef.StartDecision.HOLD_UNSAFE_PHASE, again.decision)
        assertTrue(net.requests.isEmpty())
    }

    @Test
    fun repliedChainThenRestoreOnNextStart() {
        val stand = stand()
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand).apply { runInline = 1 }  // транспорт ОТВЕТИЛ

        val res = click(store, net, shell)
        assertEquals(JitOp.ClickResult.STARTED, res.result)
        assertEquals("скрипт дошёл до конца", JitRef.ChainOutcome.REPLY_RECEIVED, res.run!!.outcome)
        assertEquals("фаза записана с доказанным завершением",
            JitRef.Shell.REPLIED, store.op()!!.shell)

        // скрипт выполнился: Wi-Fi выключен (это наблюдение, а не доказательство)
        net.wifi = false

        val waiter = JitFake.Waiter()
        val out = start(store, net, shell, waiter = waiter)
        assertEquals(JitRef.StartDecision.RESTORE, out.decision)
        assertEquals("пауза ровно postDelayed(0x3a98)", listOf(JitRef.RESTORE_DELAY_MS), waiter.sleeps.take(1))
        assertTrue("Wi-Fi возвращён", net.wifi)
        assertEquals(listOf("wifi=true"), net.requests)
        assertEquals(JitRef.Phase.RESTORED, store.op()!!.phase)
    }

    @Test
    fun lostChainNeverRestoresEvenWhenNetworkIsDown() {
        // Ровно сценарий REVIEW: снимок → `svc wifi disable` → shell задержан → процесс погиб →
        // старт → сеть выключена. Раньше это давало RESTORE; теперь — честный блок.
        val stand = stand()
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand)          // всё в очередь: ответа нет
        click(store, net, shell)
        net.wifi = false                                // «сеть уже выключена» — не доказательство
        val log = mutableListOf<String>()
        val out = start(store, net, shell, log = log)
        assertEquals(JitRef.StartDecision.HOLD_UNSAFE_PHASE, out.decision)
        assertTrue("ни одного сетевого запроса", net.requests.isEmpty())
        assertTrue("снимок сохранён как есть", store.op()!!.shell == JitRef.Shell.LOST)
        assertTrue(log.any { it.contains("write=none need=owner_decision") })
    }

    @Test
    fun bothRadiosOffWaitsForProvenCompletion() {
        val stand = stand()
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = false, ap = false)
        val shell = JitFake.QueueShell(stand)          // нет ответа транспорта
        val res = click(store, net, shell)
        assertEquals(JitOp.ClickResult.STARTED, res.result)
        assertEquals("операция НЕ объявлена завершённой", JitRef.Phase.SENT, store.op()!!.phase)
        val again = click(store, net, shell)
        assertEquals("второй клик запрещён: shell мог остаться в очереди",
            JitOp.ClickResult.REFUSED_PENDING, again.result)
        assertEquals("в очередь ушёл ровно один скрипт", 1, shell.pending.size)
        val out = start(store, net, shell)
        assertEquals(JitRef.StartDecision.HOLD_UNSAFE_PHASE, out.decision)
        assertTrue(net.requests.isEmpty())
    }

    @Test
    fun bothRadiosOffWithRepliedChainIsDoneAndNextClickAllowed() {
        val stand = stand()
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = false, ap = false)
        val shell = JitFake.QueueShell(stand).apply { runInline = 1 }
        click(store, net, shell)
        assertEquals("завершение объявляется только на разборе старта",
            JitRef.Phase.SENT, store.op()!!.phase)
        val out = start(store, net, shell)
        assertEquals(JitRef.StartDecision.NOTHING_TO_RESTORE, out.decision)
        assertEquals("завершение зафиксировано по ответу транспорта", JitRef.Phase.DONE, store.op()!!.phase)
        assertTrue("сеть не трогали", net.requests.isEmpty())
        val again = click(store, net, shell, want = false)
        assertEquals("новый клик разрешён только теперь", JitOp.ClickResult.STARTED, again.result)
    }

    @Test
    fun secondClickWhileOperationPendingIsRefusedAndKeepsSnapshot() {
        val stand = stand()
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = true)
        val shell = JitFake.QueueShell(stand)
        click(store, net, shell)                       // операция в очереди
        val before = store.rawOp()

        val log = mutableListOf<String>()
        val again = click(store, net, shell, want = false, log = log)
        assertEquals(JitOp.ClickResult.REFUSED_PENDING, again.result)
        assertEquals("снимок первой операции не перезаписан", before, store.rawOp())
        assertEquals("вторая команда в очередь не попала", 1, shell.pending.size)
        assertTrue(log.any { it.contains("reason=pending_operation") })
    }

    // ─────────────────────────── 3. чужая загрузка / неизвестность ───────────────────────────

    @Test
    fun previousBootSnapshotIsNeitherAppliedNorDeleted() {
        val stand = stand()
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand)
        click(store, net, shell)
        net.wifi = false
        val raw = store.rawOp()

        val out = start(store, net, shell, boot = OTHER_BOOT)
        assertEquals(JitRef.StartDecision.KEEP_PREVIOUS_BOOT, out.decision)
        assertTrue("сеть не трогаем", net.requests.isEmpty())
        assertEquals("снимок не удалён", raw, store.rawOp())
        assertFalse("и не применён", net.wifi)
    }

    @Test
    fun unknownBootTagBlocksRestore() {
        val stand = stand()
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand)
        click(store, net, shell)                    // снимок с boot_id
        net.wifi = false
        // устройство не отдаёт boot_id → наш тег стал epoch…: доказательства нет
        val out = start(store, net, shell, boot = "epoch1700000000")
        assertEquals(JitRef.StartDecision.KEEP_UNKNOWN_BOOT, out.decision)
        assertTrue(net.requests.isEmpty())
        assertNotNull(store.op())
    }

    @Test
    fun corruptSnapshotIsNotTouched() {
        val stand = stand()
        val store = JitFake.Store()
        store.cps[JitRef.KEY_OP] = "v=1;phase=ARMED;want=" // частичная запись
        val net = JitFake.Net()
        val log = mutableListOf<String>()
        val clickRes = click(store, net, stand.Shell(), log = log)
        assertEquals(JitOp.ClickResult.REFUSED_UNRESOLVED, clickRes.result)
        assertEquals("v=1;phase=ARMED;want=", store.getString(JitRef.KEY_OP))
        assertTrue(stand.calls().isEmpty())

        val out = start(store, net, stand.Shell(), log = log)
        assertEquals(JitRef.StartDecision.KEEP_UNKNOWN_BOOT, out.decision)
        assertTrue(net.requests.isEmpty())
        assertTrue(log.any { it.contains("need=owner_decision") })
    }

    @Test
    fun plainStartTouchesNothing() {
        val stand = stand()
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand)
        val out = start(store, net, shell)
        assertEquals(JitRef.StartDecision.IDLE, out.decision)
        assertTrue(net.requests.isEmpty())
        assertTrue(stand.calls().isEmpty())
    }

    @Test
    fun nothingToRestoreNeverTouchesNetwork() {
        val stand = stand()
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = false, ap = false)
        val shell = JitFake.QueueShell(stand).apply { runInline = 1 }
        val res = click(store, net, shell)
        assertEquals(JitOp.ClickResult.STARTED, res.result)
        assertEquals("сразу после клика операция НЕ завершена", JitRef.Phase.SENT, store.op()!!.phase)
        val out = start(store, net, shell)
        assertEquals(JitRef.StartDecision.NOTHING_TO_RESTORE, out.decision)
        assertEquals(JitRef.Phase.DONE, store.op()!!.phase)
        assertTrue("сеть не трогали ни разу", net.requests.isEmpty())
    }

    // ─────────────────────────── 4. асинхронный возврат сети ───────────────────────────

    @Test
    fun apAndWifiAppliedLateAreStillConfirmed() {
        val stand = stand()
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = true, wifiAppliesAfterReads = 2, apAppliesAfterReads = 3)
        val shell = JitFake.QueueShell(stand).apply { runInline = 1 }
        click(store, net, shell)
        net.wifi = false
        net.ap = false

        val waiter = JitFake.Waiter()
        val out = start(store, net, shell, waiter = waiter)
        assertEquals(JitRef.StartDecision.RESTORE, out.decision)
        val rep = out.restore!!
        assertTrue("AP подтверждён после ожидания", rep.apConfirmed)
        assertTrue("Wi-Fi подтверждён после ожидания", rep.wifiConfirmed)
        assertEquals("AP первым, затем Wi-Fi", listOf("ap=start", "wifi=true"), net.requests)
        assertEquals("ожидание AP шагом 1 с (как U)", 1_000L, waiter.sleeps[1])
        assertEquals(JitRef.Phase.RESTORED, store.op()!!.phase)
    }

    @Test
    fun apiRefusalIsNotReportedAsRestored() {
        val stand = stand()
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = true, apiRejects = true)
        val shell = JitFake.QueueShell(stand).apply { runInline = 1 }
        click(store, net, shell)
        net.wifi = false
        net.ap = false
        val log = mutableListOf<String>()
        val out = start(store, net, shell, log = log)
        val rep = out.restore!!
        assertFalse("API отказал — состояние не подтверждено", rep.confirmed)
        assertEquals("вызов прошёл, но запрос отклонён — и состояние не подтверждено",
            "request_rejected_not_up_20s", rep.apReason)
        assertEquals("не подтверждён Wi-Fi", "request_rejected_still_off_10s", rep.wifiReason)
        assertEquals("снимок остаётся незавершённым", JitRef.Phase.SENT, store.op()!!.phase)
        assertTrue(log.any { it.contains("JIT_NET_RESTORE result ap=false") })
    }

    @Test
    fun secondStartInSameProcessRetriesRestore() {
        val stand = stand()
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false, apiBroken = true)
        val shell = JitFake.QueueShell(stand).apply { runInline = 1 }
        click(store, net, shell)
        net.wifi = false
        val first = start(store, net, shell)
        assertFalse("первая попытка не подтвердилась", first.restore!!.confirmed)
        // сервис перезапустился в том же процессе: блокировки «уже пробовали» больше нет
        net.apiBroken = false
        val second = start(store, net, shell)
        assertEquals(JitRef.StartDecision.RESTORE, second.decision)
        assertTrue(second.restore!!.confirmed)
        assertEquals(JitRef.Phase.RESTORED, store.op()!!.phase)
    }

    @Test
    fun restoreNotRepeatedAfterSuccess() {
        val stand = stand()
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand).apply { runInline = 1 }
        click(store, net, shell)
        net.wifi = false
        start(store, net, shell)
        net.requests.clear()
        val again = start(store, net, shell)
        assertEquals("повторного возврата нет", JitRef.StartDecision.DONE, again.decision)
        assertTrue(net.requests.isEmpty())
    }

    // ─────────────────────────── 5. сверка ───────────────────────────

    @Test
    fun verifyOutcomes() {
        val stand = stand()
        val store = JitFake.Store()
        store.putStringConfirmed(JitRef.KEY_OP,
            JitRef.Op(JitRef.Phase.SENT, true, JitRef.Net(true, false), BOOT, 5L).encode())

        var shell = JitFake.QueueShell(stand).apply { getprop = "true"; cache = true }
        var v = JitOp.verifyApplied(store, shell, null)
        assertEquals(JitRef.Verify.OK, v.verdict)
        assertNotEquals("при определённом результате намерение снимается", true,
            store.getBool(JitRef.KEY_PEDIDO))

        store.putBoolConfirmed(JitRef.KEY_PEDIDO, true)
        shell = JitFake.QueueShell(stand).apply { getprop = "true"; cache = false }
        v = JitOp.verifyApplied(store, shell, null)
        assertEquals(JitRef.Verify.CACHE_MISSING, v.verdict)

        store.putBoolConfirmed(JitRef.KEY_PEDIDO, true)
        shell = JitFake.QueueShell(stand).apply { getpropRc = 1 }
        v = JitOp.verifyApplied(store, shell, null)
        assertEquals("ошибка канала — unknown", JitRef.Verify.UNKNOWN, v.verdict)
        assertEquals("намерение сохранено", true, store.getBool(JitRef.KEY_PEDIDO))

        shell = JitFake.QueueShell(stand).apply { getprop = "нет такого свойства" }
        v = JitOp.verifyApplied(store, shell, null)
        assertEquals(JitRef.Verify.UNKNOWN, v.verdict)

        shell = JitFake.QueueShell(stand).apply { getprop = "false" }
        v = JitOp.verifyApplied(store, shell, null)
        assertEquals(JitRef.Verify.NOT_APPLIED, v.verdict)
    }

    @Test
    fun wantFalseWithBrokenChannelIsNotSuccess() {
        val stand = stand()
        val store = JitFake.Store()
        store.putStringConfirmed(JitRef.KEY_OP,
            JitRef.Op(JitRef.Phase.SENT, false, JitRef.Net(true, false), BOOT, 5L).encode())
        val shell = JitFake.QueueShell(stand).apply { getpropRc = 1 }
        val v = JitOp.verifyApplied(store, shell, null)
        assertEquals("раньше здесь был ложный OK", JitRef.Verify.UNKNOWN, v.verdict)
        assertEquals("unknown", JitRef.verifyLabel(v.verdict))
    }

    // ─────────────────────────── 6. одновременные клики ───────────────────────────

    @Test
    fun concurrentClicksStartExactlyOneOperation() {
        val stand = stand()
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = true)
        val shell = JitFake.QueueShell(stand)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        // Барьер на 1: все шесть потоков стартуют одновременно (никакой опоры на планировщик).
        val barrier = java.util.concurrent.CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<JitOp.ClickResult>())
        repeat(6) {
            pool.submit {
                barrier.await()
                results += JitOp.click(store, net, shell, true, BOOT, 10L).result
            }
        }
        barrier.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, results.count { it == JitOp.ClickResult.STARTED })
        assertEquals("все остальные отказаны (PENDING или BUSY), ни один не начал операцию",
            5, results.count {
                it == JitOp.ClickResult.REFUSED_PENDING || it == JitOp.ClickResult.BUSY
            })
        assertEquals("в очередь ушёл ровно один скрипт", 1, shell.pending.size)
        assertEquals("снимок один", JitRef.Net(true, true), store.op()!!.net)
    }

    @Test
    fun concurrentProcessStartsDoNotRestoreTwice() {
        val stand = stand()
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand).apply { runInline = 1 }
        click(store, net, shell)
        net.wifi = false
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        repeat(4) { pool.submit { JitOp.onProcessStart(store, net, shell, BOOT, JitFake.Waiter()) } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("возврат Wi-Fi запрошен один раз", 1, net.requests.count { it == "wifi=true" })
    }
}
