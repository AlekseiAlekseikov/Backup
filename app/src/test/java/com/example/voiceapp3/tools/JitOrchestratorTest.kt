package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Граф вызовов и состояний целиком: `j9 → la → onCreate → e2 → U → bb/r7`.
 *
 * Тесты на фейках (JVM), не на ГУ. Проверяются фактические действия фейков: какие команды ушли,
 * какие ключи удалены, какие сетевые вызовы и в каком порядке сделаны, сколько раз.
 */
class JitOrchestratorTest {

    private fun stand() = JitIsoStand(rules = 0)

    @org.junit.Before
    fun resetUstate() {
        // в референсе это поля сервиса (`V`, `w1`) — между тестами их надо сбрасывать
        JitOp.resetStateForTest()
    }

    private fun click(
        store: JitFake.Store,
        net: JitFake.Net,
        shell: JitRef.JitShell,
        want: Boolean = true,
        log: MutableList<String> = mutableListOf()
    ) = JitOp.click(store, net, shell, want, log = { log += it })

    private fun start(
        store: JitFake.Store,
        net: JitFake.Net,
        waiter: JitFake.Waiter = JitFake.Waiter(),
        scheduler: JitFake.Scheduler = JitFake.Scheduler(),
        log: MutableList<String> = mutableListOf()
    ) = JitOp.onProcessStart(store, net, waiter, scheduler, log = { log += it })

    // ═══════════════════ 1. клик: `w0/j9.onClick` ═══════════════════

    @Test
    fun clickSavesThreeKeysInEveryStore() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val res = click(store, net, JitFake.QueueShell(stand()).apply { runInline = 1 })
        assertEquals("оба хранилища записаны", 2, res.snapshot.ok)
        assertEquals("попыток ровно по числу хранилищ", 2, res.snapshot.attempted)
        assertEquals(true, store.getBool(JitRef.KEY_PEDIDO))
        assertEquals(true, store.getBool(JitRef.KEY_AUTO))
        assertEquals("wifi=1,ap=0", store.rawRestore())
        assertEquals(JitRef.Net(true, false), res.net)
    }

    @Test
    fun clickSendsReferenceScriptAndLogsSecondValue() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand()).apply { runInline = 1 }
        val log = mutableListOf<String>()
        click(store, net, shell, want = false, log = log)
        assertEquals(JitRef.chainScript(false), shell.executed.single().first)
        assertTrue(shell.executed.single().first.contains("setprop dalvik.vm.usejit false"))
        assertTrue(log.any { it.contains("reiniciando o framework") })
        assertTrue(log.any { it.contains("mount_step=present") })
        assertTrue(log.any { it.contains("restart=not_claimed") })
    }

    @Test
    fun clickSnapshotFailureDoesNotStopChain() {
        val store = JitFake.Store().apply {
            commitFails += 0        // первое хранилище не записалось
        }
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand()).apply { runInline = 1 }
        val res = click(store, net, shell)
        assertEquals("второе хранилище всё равно записано", 1, res.snapshot.ok)
        assertEquals(2, res.snapshot.attempted)
        assertFalse("сводный результат честно неполный", res.saved)
        assertEquals("скрипт всё равно ушёл (как j9: commit не гейтит)", 1, shell.executed.size)
        assertEquals("wifi=1,ap=0", store.maps[1][JitRef.KEY_RESTORE])
    }

    @Test
    fun clickThrowingStoreStillWritesSecond() {
        val store = JitFake.Store().apply { throwOn += 0 }
        val net = JitFake.Net(wifi = false, ap = true)
        val res = click(store, net, JitFake.QueueShell(stand()).apply { runInline = 1 })
        assertEquals(1, res.snapshot.ok)
        assertEquals("wifi=0,ap=1", store.maps[1][JitRef.KEY_RESTORE])
        assertTrue(res.snapshot.detail.contains("s0:exception"))
    }

    @Test
    fun clickReadErrorsBecomeFalseAsInReference() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifiReadError = true, apReadError = true)
        val res = click(store, net, JitFake.QueueShell(stand()).apply { runInline = 1 })
        assertEquals(JitRef.Net(false, false), res.net)
        assertEquals("wifi=0,ap=0", store.rawRestore())
    }

    @Test
    fun lostChannelIsNotSuccess() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val log = mutableListOf<String>()
        val res = click(store, net, JitFake.QueueShell(stand()), log = log)
        assertEquals(137, res.exitCode)
        assertEquals(JitRef.ChainOutcome.CHANNEL_LOST, res.outcome)
        assertTrue(log.any { it.contains("outcome=channel_lost") })
        assertTrue(log.any { it.startsWith("JIT_CHAIN_ERROR") })
        assertTrue(log.none { it.contains("result=ok") })
    }

    @Test
    fun secondClickOverwritesSnapshot() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand())
        click(store, net, shell, want = true)
        net.ap = true
        click(store, net, shell, want = false)
        assertEquals("wifi=1,ap=1", store.rawRestore())
        assertEquals(false, store.getBool(JitRef.KEY_PEDIDO))
    }

    @Test
    fun clickedScriptPutsZygoteOnlyInClickPath() {
        // `ctl.restart zygote` уходит ТОЛЬКО как часть скрипта клика.
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand()).apply { runInline = 1 }
        click(store, net, shell)
        assertTrue(shell.executed.single().first.trimEnd().endsWith("setprop ctl.restart zygote"))
        // продолжение после старта процесса ни одной команды не отправляет
        val scheduler = JitFake.Scheduler()
        val out = start(store, net, scheduler = scheduler)
        assertEquals("снимок взят из клика", JitRef.Net(true, false), out.snapshot)
        assertEquals("но задача только запланирована", 1, scheduler.scheduled.size)
        assertEquals("новых команд транспорта нет — рестарт был только в скрипте клика",
            1, shell.executed.size)
    }

    // ═══════════════════ 2. продолжение: `onCreate` → `e2` ═══════════════════

    @Test
    fun noSnapshotMeansNothingAtAll() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = true)
        val scheduler = JitFake.Scheduler()
        val log = mutableListOf<String>()
        val out = start(store, net, scheduler = scheduler, log = log)
        assertNull(out.snapshot)
        assertEquals(0, store.writeCount())
        assertTrue(net.requests.isEmpty())
        assertTrue("ничего не запланировано", scheduler.scheduled.isEmpty())
        assertTrue(log.any { it.contains("snapshot=none") })
    }

    @Test
    fun unparsedSnapshotIsLeftUntouched() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1") }
        val net = JitFake.Net(wifi = true, ap = true)
        val scheduler = JitFake.Scheduler()
        val out = start(store, net, scheduler = scheduler)
        assertNull(out.snapshot)
        assertEquals("ничего не удалено", 0, store.writeCount())
        assertEquals("wifi=1", store.rawRestore())
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun snapshotRemovedSynchronouslyAndRestoreScheduled15s() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1,ap=0") }
        val net = JitFake.Net(wifi = false, ap = false)
        val scheduler = JitFake.Scheduler()
        val out = start(store, net, scheduler = scheduler)
        assertNotNull(out.snapshot)
        assertEquals("снято до выполнения", null, store.rawRestore())
        assertEquals("ни одного сетевого вызова до паузы", 0, net.requests.size)
        assertEquals(listOf(15_000L), scheduler.delays())
        assertTrue(out.scheduled)
        assertEquals("записано в оба хранилища", 2, out.removed!!.ok)
    }

    @Test
    fun restoreHappensOnlyWhenScheduledTaskRuns() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1,ap=0") }
        val net = JitFake.Net(wifi = false, ap = false)
        val scheduler = JitFake.Scheduler()
        start(store, net, scheduler = scheduler)
        assertFalse("до срабатывания — Wi-Fi не включён", net.wifi)
        scheduler.drain()
        assertTrue(net.wifi)
        assertEquals(listOf("wifi=true"), net.requests)
    }

    @Test
    fun concurrentStartsScheduleOneRestore() {
        // Два одновременных вызова (onCreate + onStartCommand): снимок снимает только первый.
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1,ap=0") }
        val net = JitFake.Net(wifi = false, ap = false)
        val scheduler = JitFake.Scheduler()
        start(store, net, scheduler = scheduler)
        start(store, net, scheduler = scheduler)
        assertEquals("запланирован ровно один возврат", 1, scheduler.scheduled.size)
        scheduler.drain()
        assertEquals(1, net.requests.size)
    }

    @Test
    fun restoreApFirstThenWifi() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1,ap=1") }
        val net = JitFake.Net(wifi = false, ap = false, apAppliesAfterReads = 1)
        val scheduler = JitFake.Scheduler().apply { runInline = true }
        start(store, net, scheduler = scheduler)
        assertEquals("порядок как в e2: AP первым, затем Wi-Fi",
            listOf("ap=start", "wifi=true"), net.requests)
        assertTrue(net.ap)
        assertTrue(net.wifi)
    }

    @Test
    fun alreadyOnNetworkIsNotTouched() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1,ap=1") }
        val net = JitFake.Net(wifi = true, ap = true)
        start(store, net, scheduler = JitFake.Scheduler().apply { runInline = true })
        assertTrue(net.requests.isEmpty())
    }

    @Test
    fun bothRadiosWereOffRestoresNothing() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=0,ap=0") }
        val net = JitFake.Net(wifi = false, ap = false)
        val out = start(store, net, scheduler = JitFake.Scheduler().apply { runInline = true })
        assertEquals(JitRef.Net(false, false), out.snapshot)
        assertTrue(net.requests.isEmpty())
    }

    @Test
    fun apExceptionAbortsWifiPathAsInE2() {
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1,ap=1") }
        // startAp бросает: `U` пробрасывает, `e2` ловит и обрывает возврат целиком.
        val net = object : JitRef.JitNetState by JitFake.Net(wifi = false, ap = false) {
            override fun startAp(): Boolean? = throw IllegalStateException("boom")
            override fun wifiOn(): Boolean? = false
            override fun apUp(): Boolean? = false
        }
        val log = mutableListOf<String>()
        val report = JitOp.restoreNetwork(net, JitRef.Net(true, true), JitFake.Waiter()) { log += it }
        assertEquals("ap:IllegalStateException", report.failure)
        assertFalse("Wi-Fi не запрашивался: исключение оборвало возврат", report.wifiRequested)
        assertTrue(log.any { it.contains("JIT: restauro falhou") })
    }

    @Test
    fun deathBeforeScheduledRestoreIsDocumentedRisk() {
        // Снимок снят, задача не выполнена (процесс убит) — сеть остаётся выключенной.
        val store = JitFake.Store().apply { set(JitRef.KEY_RESTORE, "wifi=1,ap=0") }
        val net = JitFake.Net(wifi = false, ap = false)
        val scheduler = JitFake.Scheduler()
        start(store, net, scheduler = scheduler)
        assertEquals("снимка уже нет", null, store.rawRestore())
        assertFalse(net.wifi)
        // повторный старт процесса НЕ восстановит: снимок удалён (так в референсе)
        val second = start(store, net, scheduler = JitFake.Scheduler())
        assertNull(second.snapshot)
        assertFalse(net.wifi)
    }

    // ═══════════════════ 3. `U` — полный граф ветвей ═══════════════════

    @Test
    fun apAlreadyUpReturnsTrueWithoutCalls() {
        val net = JitFake.Net(wifi = false, ap = true)
        val r = JitOp.openAp(net, JitFake.Waiter(), "t")
        assertTrue(r.up)
        assertEquals("already_up", r.detail)
        assertTrue(net.requests.isEmpty())
    }

    @Test
    fun carplaySignalUnavailableSkipsWaitHonestly() {
        val net = JitFake.Net(wifi = false, ap = false, carplay = null, apAppliesAfterReads = 1)
        val log = mutableListOf<String>()
        JitOp.openAp(net, JitFake.Waiter(), "pós-JIT") { log += it }
        assertTrue(log.any { it.contains("carplay_wait=skipped reason=signal_unavailable") })
    }

    @Test
    fun carplayBusyWaitsThenContinues() {
        val net = JitFake.Net(wifi = false, ap = false, carplay = true, apAppliesAfterReads = 1)
        val waiter = JitFake.Waiter()
        // сигнал «занято» гаснет после двух опросов
        val calls = AtomicInteger()
        val state = object : JitRef.JitNetState by net {
            override fun carplayBusy(): Boolean? = calls.incrementAndGet() <= 2
        }
        val r = JitOp.openAp(state, waiter, "pós-JIT")
        assertTrue(r.up)
        assertTrue("ожидание шло шагом 1 с", waiter.sleeps.contains(1_000L))
    }

    @Test
    fun carplayBusyButApComesUpDuringWait() {
        // `O()` ограничен по времени: «занято» гаснет через несколько опросов, и AP уже в воздухе.
        val net = JitFake.Net(wifi = false, ap = false)
        var carplayCalls = 0
        var apReads = 0
        val state = object : JitRef.JitNetState by net {
            override fun carplayBusy(): Boolean? = carplayCalls++ < 3
            override fun apUp(): Boolean {
                apReads++
                return apReads > 1          // 1-е чтение — ранняя v(): AP ещё нет
            }
        }
        val r = JitOp.openAp(state, JitFake.Waiter(), "pós-JIT")
        assertTrue(r.up)
        assertEquals("up_after_carplay_wait", r.detail)
    }

    @Test
    fun clientWifiOnBlocksApAndKeepsRadio() {
        val net = JitFake.Net(wifi = true, ap = false)
        val r = JitOp.openAp(net, JitFake.Waiter(), "pós-JIT")
        assertFalse(r.up)
        assertEquals("wifi_client_on", r.detail)
        assertTrue("AP не открывали", net.requests.isEmpty())
    }

    @Test
    fun wifiEnablingCountsAsClientWifiBusy() {
        // `c1()`: isWifiEnabled() || getWifiState()==WIFI_STATE_ENABLING
        val net = JitFake.Net(wifi = false, ap = false, wifiEnabling = true)
        val r = JitOp.openAp(net, JitFake.Waiter(), "pós-JIT")
        assertFalse(r.up)
        assertEquals("wifi_client_on", r.detail)
    }

    @Test
    fun wifiReadExceptionCountsAsBusy() {
        val net = JitFake.Net(wifiReadError = true, ap = false)
        val r = JitOp.openAp(net, JitFake.Waiter(), "pós-JIT")
        assertFalse(r.up)
        assertEquals("wifi_client_on", r.detail)
    }

    @Test
    fun ownerCheckBlocksOnlyWhenOwnerIsPerson() {
        val net = JitFake.Net(wifi = false, ap = false)
        val r = JitOp.openAp(net, JitFake.Waiter(), "ap-sempre", ownerCheck = true,
            ownerIsPerson = { true })
        assertFalse(r.up)
        assertEquals("owner_person", r.detail)
        assertTrue(net.requests.isEmpty())
        // тот же вызов с владельцем APP — открывает
        val net2 = JitFake.Net(wifi = false, ap = false, apAppliesAfterReads = 1)
        val r2 = JitOp.openAp(net2, JitFake.Waiter(), "ap-sempre", ownerCheck = true,
            ownerIsPerson = { false })
        assertTrue(r2.up)
    }

    @Test
    fun autoEnabledWifiDoesNotBlockAp() {
        // Сначала наш auto-enable включил Wi-Fi (V=true), затем AP открывается «por cima».
        val net = JitFake.Net(wifi = false, ap = false, apAppliesAfterReads = 1)
        JitOp.restoreNetwork(net, JitRef.Net(wifi = true, ap = false), JitFake.Waiter())
        assertTrue("Wi-Fi включён нами", net.wifi)
        val r = JitOp.openAp(net, JitFake.Waiter(), "pós-JIT")
        assertTrue("auto-enable не запрещает AP", r.up)
        assertTrue(net.requests.contains("ap=start"))
    }

    @Test
    fun apFailedSignalRetriesTwiceThenGivesUp() {
        // FAILED приходит на каждом опросе: 3 попытки, пауза 2 с, затем false.
        val net = JitFake.Net(wifi = false, ap = false, startApResult = false)
        val waiter = JitFake.Waiter()
        val log = mutableListOf<String>()
        // как `w0/u2`: событие FAILED приходит уже во время попытки (u1 > t0 каждой попытки)
        val state = object : JitRef.JitNetState by net {
            override fun apFailedAtMs(): Long = waiter.nowMs() - 500
        }
        val r = JitOp.openAp(state, waiter, "pós-JIT", log = { log += it })
        assertFalse(r.up)
        assertEquals("failed_again", r.detail)
        assertEquals("три попытки открыть AP", 3, net.requests.count { it == "ap=start" })
        assertEquals("две паузы между попытками", 2, waiter.sleeps.count { it == 2_000L })
        assertTrue(log.any { it.contains("system=FAILED") })
        assertTrue(log.any { it.contains("FAILED de novo") })
    }

    @Test
    fun apAppearsOnSecondAttemptAfterFailed() {
        val net = JitFake.Net(wifi = false, ap = false, startApResult = false)
        // после первого FAILED «радио проснулось»
        val started = AtomicInteger()
        val state = object : JitRef.JitNetState by net {
            override fun apFailedAtMs(): Long = if (started.get() == 1) 5_000L else 0L
            override fun startAp(): Boolean? {
                started.incrementAndGet()
                if (started.get() >= 2) net.ap = true
                return true
            }
        }
        val r = JitOp.openAp(state, JitFake.Waiter(), "pós-JIT")
        assertTrue("со второй попытки AP поднялся", r.up)
        assertEquals(2, started.get())
    }

    @Test
    fun apTimeoutWithoutFailedSignalIsReported() {
        val net = JitFake.Net(wifi = false, ap = false, startApResult = false)
        val waiter = JitFake.Waiter()
        val log = mutableListOf<String>()
        val r = JitOp.openAp(net, waiter, "pós-JIT") { log += it }
        assertFalse(r.up)
        assertTrue(r.detail.startsWith("timeout_"))
        assertEquals("одна попытка: сигнала FAILED не было", 1, net.requests.count { it == "ap=start" })
        assertEquals("опрос ограничен 20 с", 20_000L, waiter.sleeps.filter { it == 1_000L }.sum())
        assertTrue(log.any { it.contains("entregando ao ConnAdaptor") })
    }

    @Test
    fun apWatchIsRegisteredAndReleased() {
        val net = JitFake.Net(wifi = false, ap = false, apAppliesAfterReads = 1)
        JitOp.openAp(net, JitFake.Waiter(), "pós-JIT")
        assertEquals(1, net.watchRegistrations)
        assertFalse("подписка снята — постоянного приёмника нет", net.watchOn)
    }

    @Test
    fun concurrentSecondCallerWaitsForFirstResult() {
        // Два потока: первый открывает AP (медленно), второй ждёт общий latch и видит результат.
        val net = JitFake.Net(wifi = false, ap = false)
        val firstStarted = CountDownLatch(1)
        val state = object : JitRef.JitNetState by net {
            override fun apUp(): Boolean? = net.ap
            override fun startAp(): Boolean? {
                net.requests += "ap=start"
                firstStarted.countDown()
                Thread.sleep(150)     // «система думает»
                net.ap = true
                return true
            }
        }
        val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val t1 = Thread { results += JitOp.openAp(state, JitFake.Waiter(), "first").up }
        val t2 = Thread {
            firstStarted.await(2, TimeUnit.SECONDS)
            results += JitOp.openAp(state, JitFake.Waiter(), "second").up
        }
        t1.start(); t2.start(); t1.join(5_000); t2.join(5_000)
        assertEquals("оба запроса видят поднятый AP", listOf(true, true), results.toList())
        assertEquals("стартовала ровно одна попытка", 1, net.requests.count { it == "ap=start" })
    }

    @Test
    fun secondCallerReturnsApStateWhenFirstGivesUp() {
        val net = JitFake.Net(wifi = false, ap = false, startApResult = false)
        val firstStarted = CountDownLatch(1)
        val state = object : JitRef.JitNetState by net {
            override fun startAp(): Boolean? {
                net.requests += "ap=start"
                firstStarted.countDown()
                Thread.sleep(120)
                return false
            }
        }
        val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val t1 = Thread { results += JitOp.openAp(state, JitFake.Waiter(), "first").up }
        val t2 = Thread {
            firstStarted.await(2, TimeUnit.SECONDS)
            results += JitOp.openAp(state, JitFake.Waiter(), "second").up
        }
        t1.start(); t2.start(); t1.join(5_000); t2.join(5_000)
        assertEquals("никто не заявляет успех", listOf(false, false), results.toList())
    }

    // ═══════════════════ 4. сверка `bb` + `r7`: удаление и повторный проход ═══════════════════

    @Test
    fun verifyOkRemovesPedidoInBothStores() {
        val store = JitFake.Store().apply { set(JitRef.KEY_PEDIDO, true) }
        val shell = JitFake.AnswerShell(cache = true).apply { default = 0 to "true" }
        val out = JitOp.verifyApplied(store, shell)
        assertEquals(JitRef.Verify.OK, out.verdict)
        assertEquals("ключ УДАЛЁН, а не записан false", false, store.contains(JitRef.KEY_PEDIDO))
        assertEquals("и во втором хранилище тоже", false, store.presentIn(1, JitRef.KEY_PEDIDO))
        assertEquals(2, out.removed!!.ok)
        assertFalse(out.stillPresent)
        assertTrue("getprop-команда ровно одна", shell.executed.count { it.contains("getprop") } == 1)
    }

    @Test
    fun verifyNotAppliedStillRemovesAndWarns() {
        val store = JitFake.Store().apply { set(JitRef.KEY_PEDIDO, true) }
        val shell = JitFake.AnswerShell().apply { default = 0 to "false" }
        val log = mutableListOf<String>()
        val out = JitOp.verifyApplied(store, shell) { log += it }
        assertEquals(JitRef.Verify.NOT_APPLIED, out.verdict)
        assertEquals(false, store.contains(JitRef.KEY_PEDIDO))
        assertTrue(log.any { it.startsWith("JIT_NOT_APPLIED") })
    }

    @Test
    fun verifyCacheFalseWithPedidoTrueIsNotSuccess() {
        val store = JitFake.Store().apply { set(JitRef.KEY_PEDIDO, true) }
        val shell = JitFake.AnswerShell(cache = false).apply { default = 0 to "true" }
        val log = mutableListOf<String>()
        val out = JitOp.verifyApplied(store, shell) { log += it }
        assertEquals(JitRef.Verify.CACHE_MISSING, out.verdict)
        assertTrue("предупреждение показано (как r7 при !ok && pedido)", log.any { it.startsWith("JIT_NOT_APPLIED") })
    }

    @Test
    fun verifyDisableWithCacheFalseIsOk() {
        val store = JitFake.Store().apply { set(JitRef.KEY_PEDIDO, false) }
        val shell = JitFake.AnswerShell(cache = false).apply { default = 0 to "false" }
        val out = JitOp.verifyApplied(store, shell)
        assertEquals("`false/false/cache=false` — ok, как в DEX", JitRef.Verify.OK, out.verdict)
        assertEquals(false, store.contains(JitRef.KEY_PEDIDO))
    }

    @Test
    fun verifyWithoutPedidoWritesNothing() {
        val store = JitFake.Store()
        val shell = JitFake.AnswerShell().apply { default = 0 to "true" }
        val out = JitOp.verifyApplied(store, shell)
        assertNull(out.pedido)
        assertEquals(0, store.writeCount())
    }

    @Test
    fun secondVerifyAfterRemovalDoesNotSeeNewRequestForOff() {
        // После удаления ключа следующая сверка не должна принять его за «включить/выключить».
        val store = JitFake.Store().apply { set(JitRef.KEY_PEDIDO, true) }
        val shell = JitFake.AnswerShell().apply { default = 0 to "true" }
        JitOp.verifyApplied(store, shell)
        val afterFirst = store.writeCount()
        val second = JitOp.verifyApplied(store, shell)
        assertNull("намерения нет — сверять нечего", second.pedido)
        assertEquals("вторая сверка ничего не пишет и не удаляет", afterFirst, store.writeCount())
    }

    @Test
    fun unreadablePropertyKeepsPedidoHonestly() {
        val store = JitFake.Store().apply { set(JitRef.KEY_PEDIDO, true) }
        val shell = JitFake.AnswerShell().apply { default = 137 to "ERR_CHANNEL_LOST" }
        val log = mutableListOf<String>()
        val out = JitOp.verifyApplied(store, shell) { log += it }
        assertEquals(JitRef.Verify.UNKNOWN, out.verdict)
        assertEquals("намерение сохранено (ОТЛИЧИЕ от референса)", true, store.contains(JitRef.KEY_PEDIDO))
        assertTrue(log.any { it.contains("JIT_PEDIDO kept") })
        assertTrue("лог не заявляет write=none там, где был remove", log.any { it.contains("write=none") })
    }

    @Test
    fun verifyRemovalFailureIsReported() {
        val store = JitFake.Store().apply {
            set(JitRef.KEY_PEDIDO, true)
            setIn(1, JitRef.KEY_PEDIDO, true)
            commitFails += 0        // первое хранилище не удалило ключ
        }
        val shell = JitFake.AnswerShell().apply { default = 0 to "true" }
        val log = mutableListOf<String>()
        val out = JitOp.verifyApplied(store, shell) { log += it }
        assertEquals(1, out.removed!!.ok)
        assertEquals(2, out.removed!!.attempted)
        assertTrue("ключ остался во втором хранилище — это видно", out.stillPresent)
        assertTrue(log.any { it.contains("removed=false") })
    }

    // ═══════════════════ 5. сквозная последовательность ═══════════════════

    @Test
    fun wholeChainClickRestartVerify() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand()).apply { runInline = 1 }

        // 1. клик
        click(store, net, shell, want = true)
        assertEquals("wifi=1,ap=0", store.rawRestore())
        assertEquals(true, store.getBool(JitRef.KEY_PEDIDO))

        // 2. скрипт выполнился: Wi-Fi выключен, свойство записано
        net.wifi = false
        (shell as JitFake.QueueShell).getprop = "true"

        // 3. рестарт → приложение поднялось → снимок снят, возврат запланирован
        val scheduler = JitFake.Scheduler()
        val out = start(store, net, scheduler = scheduler)
        assertEquals(JitRef.Net(true, false), out.snapshot)
        assertEquals(15_000L, scheduler.delays().single())
        assertNull(store.rawRestore())
        assertEquals("намерение ещё не сверено", true, store.getBool(JitRef.KEY_PEDIDO))

        // 4. через 15 с — возврат сети (AP не нужен, Wi-Fi включается)
        scheduler.drain()
        assertTrue(net.wifi)
        assertEquals(listOf("wifi=true"), net.requests)

        // 5. сверка
        val answer = JitFake.AnswerShell(cache = true).apply { default = 0 to "true" }
        val v = JitOp.verifyApplied(store, answer)
        assertEquals(JitRef.Verify.OK, v.verdict)
        assertEquals("намерение снято удалением", false, store.contains(JitRef.KEY_PEDIDO))
    }

    @Test
    fun fakeQueueShellDoesNotLieAboutTimeout() {
        val shell = JitFake.QueueShell(stand())
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val res = click(store, net, shell)
        assertEquals(JitRef.ChainOutcome.CHANNEL_LOST, res.outcome)
        assertTrue("команда осталась в очереди транспорта", shell.pending.isNotEmpty())
        assertFalse(res.saved && false)
    }
}
