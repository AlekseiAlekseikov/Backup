package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Цепочка целиком — как в референсе:
 *
 * 1. **клик** `w0/j9.onClick`: `isWifiEnabled()`/`ap0` → один `commit()` трёх ключей →
 *    `w0/la.run` (скрипт символ в символ, `ctl.restart zygote` последней командой);
 * 2. **продолжение** `CentralWatchdogService.onCreate`: строка `jit_restaurar` совпала с
 *    регуляркой → снять строку → 15 000 мс → `w0/e2` (AP первым, затем Wi-Fi);
 * 3. **сверка** `w0/bb` + `w0/r7`: `getprop dalvik.vm.usejit` + `/proc/self/maps`,
 *    `ok = (pedido == getprop) && (!pedido || maps != FALSE)`, снятие `jit_pedido`.
 *
 * Тесты на фейках (локально), не на ГУ. Отличия от референса перечислены в отчёте §3; здесь
 * проверяется, что цепочка ведёт себя как оригинал, включая её собственные ограничения.
 */
class JitOrchestratorTest {

    private fun stand() = JitIsoStand(rules = 0)

    private fun click(
        store: JitFake.Store,
        net: JitFake.Net,
        shell: JitRef.JitShell,
        want: Boolean = true,
        log: MutableList<String> = mutableListOf()
    ) = JitOp.click(store, net, shell, want, JitFake.Waiter(), log = { log += it })

    private fun start(
        store: JitFake.Store,
        net: JitFake.Net,
        waiter: JitFake.Waiter = JitFake.Waiter(),
        log: MutableList<String> = mutableListOf()
    ) = JitOp.onProcessStart(store, net, waiter, log = { log += it })

    // ═══════════════════ 1. клик: `w0/j9.onClick` ═══════════════════

    @Test
    fun clickSavesThreeKeysInOneCommitBeforeScript() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand()).apply { runInline = 1 }
        val res = click(store, net, shell)
        assertEquals("один commit (как j9)", 1, store.writeCount())
        assertEquals("snapshot:wifi=1,ap=0:true", store.commits.single())
        assertEquals(true, store.getBool(JitRef.KEY_PEDIDO))
        assertEquals(true, store.getBool(JitRef.KEY_AUTO))
        assertEquals("wifi=1,ap=0", store.rawRestore())
        assertEquals(JitRef.Net(true, false), res.net)
        assertEquals("скрипт ушёл в транспорт", 1, shell.executed.size)
    }

    @Test
    fun clickSendsReferenceScriptVerbatim() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand()).apply { runInline = 1 }
        click(store, net, shell, want = true)
        val (cmd, rc, _) = shell.executed.single()
        assertEquals(JitRef.chainScript(true), cmd)
        assertEquals(0, rc)
        assertTrue(cmd.trimEnd().endsWith("setprop ctl.restart zygote"))
    }

    @Test
    fun toggleOffIsSameChainWithFalse() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = false, ap = true)
        val shell = JitFake.QueueShell(stand()).apply { runInline = 1 }
        click(store, net, shell, want = false)
        assertEquals("snapshot:wifi=0,ap=1:false", store.commits.single())
        assertEquals(false, store.getBool(JitRef.KEY_PEDIDO))
        assertEquals(JitRef.chainScript(false), shell.executed.single().first)
        assertTrue(shell.executed.single().first.contains("setprop dalvik.vm.usejit false"))
    }

    @Test
    fun clickReadErrorBecomesFalseAsInReference() {
        // В `j9` исключение чтения Wi-Fi/AP даёт `false` (catchall_48 / catchall_64).
        val store = JitFake.Store()
        val net = JitFake.Net(wifiReadError = true, apReadError = true)
        val shell = JitFake.QueueShell(stand()).apply { runInline = 1 }
        val res = click(store, net, shell)
        assertEquals(JitRef.Net(false, false), res.net)
        assertEquals("wifi=0,ap=0", store.rawRestore())
        // и это честно видно в логе: снимок ≠ «мы знаем, что сеть была выключена»
        assertTrue(JitOp.click(store, net, shell, true, JitFake.Waiter(),
            log = { }).net == JitRef.Net(false, false))
    }

    @Test
    fun lostChannelIsNotSuccess() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand()) // runInline = 0 ⇒ таймаут вызывающего
        val log = mutableListOf<String>()
        val res = click(store, net, shell, log = log)
        assertEquals(137, res.exitCode)
        assertEquals(JitRef.ChainOutcome.CHANNEL_LOST, res.outcome)
        assertTrue(log.any { it.contains("outcome=channel_lost") })
        assertTrue("команда осталась в очереди, а не отменена", shell.pending.isNotEmpty())
        assertTrue("нет заявки на успех", log.none { it.contains("result=ok") })
    }

    @Test
    fun secondClickOverwritesSnapshotAsInReference() {
        // В референсе второй клик не проверяет незавершённую операцию: снимок перезаписывается.
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand())
        click(store, net, shell, want = true)
        net.ap = true
        click(store, net, shell, want = false)
        assertEquals("последний снимок — по последнему клику", "wifi=1,ap=1", store.rawRestore())
        assertEquals(false, store.getBool(JitRef.KEY_PEDIDO))
        assertEquals("два commit'а, как два клика", 2, store.writeCount())
    }

    @Test
    fun commitResultDoesNotStopChain() {
        // `j9` результат commit() не проверяет и скрипт всё равно отправляет.
        val store = JitFake.Store().apply { commitResult = false }
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand()).apply { runInline = 1 }
        val res = click(store, net, shell)
        assertEquals("saved=false честно возвращается", false, res.saved)
        assertEquals("но цепочка отправлена", 1, shell.executed.size)
    }

    // ═══════════════════ 2. продолжение: `onCreate` → `e2` ═══════════════════

    @Test
    fun noSnapshotMeansNothingAtAll() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = true)
        val log = mutableListOf<String>()
        val out = start(store, net, log = log)
        assertNull(out.snapshot)
        assertEquals("ноль записей", 0, store.writeCount())
        assertTrue("ноль сетевых запросов", net.requests.isEmpty())
        assertTrue(log.any { it.contains("snapshot=none") })
    }

    @Test
    fun unparsedSnapshotIsLeftUntouched() {
        val store = JitFake.Store()
        store.values[JitRef.KEY_RESTORE] = "wifi=1"        // не совпадает с регуляркой
        val net = JitFake.Net(wifi = true, ap = true)
        val log = mutableListOf<String>()
        val out = start(store, net, log = log)
        assertNull(out.snapshot)
        assertEquals("референс ничего не трогает", 0, store.writeCount())
        assertEquals("wifi=1", store.rawRestore())
        assertTrue(net.requests.isEmpty())
        assertTrue(log.any { it.contains("snapshot=unparsed") })
    }

    @Test
    fun snapshotIsRemovedBeforeDelayAndDelayIs15s() {
        val store = JitFake.Store()
        store.values[JitRef.KEY_RESTORE] = "wifi=1,ap=0"
        val net = JitFake.Net(wifi = false, ap = false)
        val waiter = JitFake.Waiter()
        start(store, net, waiter)
        assertEquals("строка снята (reиференс: remove до выполнения)", null, store.rawRestore())
        assertEquals("первая пауза — 15 000 мс", 15_000L, waiter.sleeps.first())
        assertTrue("Wi-Fi включён", net.wifi)
    }

    @Test
    fun restoreApFirstThenWifi() {
        val store = JitFake.Store()
        store.values[JitRef.KEY_RESTORE] = "wifi=1,ap=1"
        val net = JitFake.Net(wifi = false, ap = false)
        start(store, net)
        assertEquals("порядок как в e2: AP первым, затем Wi-Fi",
            listOf("ap=start", "wifi=true"), net.requests)
        assertTrue("AP поднят", net.ap)
        assertTrue("Wi-Fi включён", net.wifi)
    }

    @Test
    fun alreadyOnNetworkIsNotTouched() {
        val store = JitFake.Store()
        store.values[JitRef.KEY_RESTORE] = "wifi=1,ap=1"
        val net = JitFake.Net(wifi = true, ap = true)
        start(store, net)
        assertTrue("ничего не запрашивали: сеть уже такая, как в снимке", net.requests.isEmpty())
    }

    @Test
    fun apGuardKeepsRadioWhenClientWifiIsOn() {
        // `U`: «AP fechado, mas o WiFi cliente está ligado — não vou disputar o rádio».
        val store = JitFake.Store()
        store.values[JitRef.KEY_RESTORE] = "wifi=1,ap=1"
        val net = JitFake.Net(wifi = true, ap = false)
        val log = mutableListOf<String>()
        val out = start(store, net, log = log)
        assertFalse("AP не открывали", net.requests.contains("ap=start"))
        assertFalse("AP остался закрыт", net.ap)
        assertEquals("Wi-Fi уже был включён — запрос не нужен", 0, net.requests.size)
        assertTrue(log.any { it.contains("wifi_client=on") })
        assertEquals(false, out.report?.apUp)
    }

    @Test
    fun apAppearsLateButIsConfirmed() {
        val store = JitFake.Store()
        store.values[JitRef.KEY_RESTORE] = "wifi=0,ap=1"
        val net = JitFake.Net(wifi = false, ap = false, apAppliesAfterReads = 2)
        val waiter = JitFake.Waiter()
        val out = start(store, net, waiter)
        assertEquals(true, out.report?.apUp)
        assertTrue("опрос шёл шагом 1 с", waiter.sleeps.contains(1_000L))
        assertEquals("Wi-Fi в снимке был выключен — не включаем", 0, net.requests.count { it.startsWith("wifi=") })
    }

    @Test
    fun apTimeoutIsReportedHonestly() {
        val store = JitFake.Store()
        store.values[JitRef.KEY_RESTORE] = "wifi=0,ap=1"
        // `startApResult = false` — вызов прошёл, но система ответила «нет»; AP не поднимается.
        val net = JitFake.Net(wifi = false, ap = false).apply { startApResult = false }
        val log = mutableListOf<String>()
        val out = start(store, net, log = log)
        assertEquals(false, out.report?.apUp)
        assertTrue("опрос ограничен 20 с", log.any { it.contains("poll=20s") })
        assertTrue(log.any { it.contains("state_after=false") })
    }

    @Test
    fun apRequestThrowsIsStillNotFailureOfTheWholeChain() {
        // `d()` в референсе ловит исключение и пишет «startSoftAp FALHOU»; сеть дальше не трогаем.
        val store = JitFake.Store()
        store.values[JitRef.KEY_RESTORE] = "wifi=1,ap=1"
        val net = JitFake.Net(wifi = false, ap = false).apply { startApResult = null }
        val log = mutableListOf<String>()
        val out = start(store, net, log = log)
        assertEquals(false, out.report?.apUp)
        assertTrue("Wi-Fi всё равно возвращается (как e2: AP не поднялся — идём дальше)", net.wifi)
        assertEquals(true, out.report?.wifiOn)
    }

    @Test
    fun bothRadiosWereOffRestoresNothing() {
        // Снимок «wifi=0,ap=0»: возвращать нечего — ни одного запроса (референс тоже ничего не делает).
        val store = JitFake.Store()
        store.values[JitRef.KEY_RESTORE] = "wifi=0,ap=0"
        val net = JitFake.Net(wifi = false, ap = false)
        val out = start(store, net)
        assertEquals(JitRef.Net(false, false), out.snapshot)
        assertTrue(net.requests.isEmpty())
        assertEquals(null, store.rawRestore())
    }

    @Test
    fun restartOfProcessDoesNotRestoreTwice() {
        val store = JitFake.Store()
        store.values[JitRef.KEY_RESTORE] = "wifi=1,ap=0"
        val net = JitFake.Net(wifi = false, ap = false)
        start(store, net)
        val first = net.requests.size
        start(store, net)                       // второй «старт процесса»
        assertEquals("второго возврата нет: строка уже снята", first, net.requests.size)
    }

    // ═══════════════════ 3. сверка: `w0/bb` + `w0/r7` ═══════════════════

    @Test
    fun verifyOkClearsPedido() {
        val store = JitFake.Store()
        store.values[JitRef.KEY_PEDIDO] = true
        val shell = JitFake.QueueShell(stand()).apply { getprop = "true"; cache = true }
        val log = mutableListOf<String>()
        val out = JitOp.verifyApplied(store, shell, log = { log += it })
        assertEquals(JitRef.Verify.OK, out.verdict)
        assertEquals("jit_pedido снят (как r7)", false, store.getBool(JitRef.KEY_PEDIDO))
        assertTrue(log.any { it.contains("JIT pedido=true getprop=true") })
        assertTrue("рестарт не заявляется", log.any { it.contains("restart=not_claimed") })
    }

    @Test
    fun verifyNotAppliedReportsAndStillClears() {
        val store = JitFake.Store()
        store.values[JitRef.KEY_PEDIDO] = true
        val shell = JitFake.QueueShell(stand()).apply { getprop = "false"; cache = true }
        val log = mutableListOf<String>()
        val out = JitOp.verifyApplied(store, shell, log = { log += it })
        assertEquals(JitRef.Verify.NOT_APPLIED, out.verdict)
        assertEquals(false, store.getBool(JitRef.KEY_PEDIDO))
        assertTrue(log.any { it.contains("JIT_NOT_APPLIED") })
    }

    @Test
    fun verifyDisableWithCacheGoneIsCacheMissing() {
        val store = JitFake.Store()
        store.values[JitRef.KEY_PEDIDO] = false
        val shell = JitFake.QueueShell(stand()).apply { getprop = "false"; cache = false }
        val log = mutableListOf<String>()
        val out = JitOp.verifyApplied(store, shell, log = { log += it })
        assertEquals(JitRef.Verify.CACHE_MISSING, out.verdict)
        // в r7 сообщение показывается только при `pedido=true`; при выключении это просто факт
        assertTrue(log.none { it.contains("JIT_NOT_APPLIED") })
    }

    @Test
    fun verifyWithoutPedidoDoesNothing() {
        val store = JitFake.Store()
        val shell = JitFake.QueueShell(stand())
        val out = JitOp.verifyApplied(store, shell)
        assertNull(out.pedido)
        assertEquals("ничего не пишем", 0, store.writeCount())
    }

    @Test
    fun unreadablePropertyKeepsPedido() {
        // Отличие от референса (см. §3 отчёта): при недоступном канале намерение сохраняется,
        // чтобы следующая сверка не потеряла запрос. Команда цепочки от этого не меняется.
        val store = JitFake.Store()
        store.values[JitRef.KEY_PEDIDO] = true
        val shell = JitFake.QueueShell(stand()).apply { getpropRc = 137 }
        val log = mutableListOf<String>()
        val out = JitOp.verifyApplied(store, shell, log = { log += it })
        assertEquals(JitRef.Verify.UNKNOWN, out.verdict)
        assertEquals("намерение сохранено", true, store.getBool(JitRef.KEY_PEDIDO))
        assertFalse("ничего не записано", store.commits.any { it.startsWith("bool:") })
        assertTrue(log.any { it.contains("JIT_PEDIDO kept") })
    }

    // ═══════════════════ 4. полная последовательность ═══════════════════

    @Test
    fun clickThenRestartThenVerifyIsTheWholeReferenceChain() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand()).apply { runInline = 1 }

        // 1. клик
        click(store, net, shell, want = true)
        assertEquals("wifi=1,ap=0", store.rawRestore())

        // 2. скрипт выполнился на устройстве: Wi-Fi выключен, свойство записано
        net.wifi = false
        shell.getprop = "true"

        // 3. рестарт фреймворка → приложение поднялось → продолжение
        val waiter = JitFake.Waiter()
        val out = start(store, net, waiter)
        assertEquals(JitRef.Net(true, false), out.snapshot)
        assertEquals(15_000L, waiter.sleeps.first())
        assertEquals(listOf("wifi=true"), net.requests)
        assertTrue(net.wifi)

        // 4. сверка
        val v = JitOp.verifyApplied(store, shell)
        assertEquals(JitRef.Verify.OK, v.verdict)
        assertEquals("намерение снято", false, store.getBool(JitRef.KEY_PEDIDO))
        assertEquals("снимок снят ещё на продолжении", null, store.rawRestore())
    }
}
