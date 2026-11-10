package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * iter33p (1.0.61) — СКВОЗНАЯ операция: клик тумблера → ядро [JitOp] → настоящий `sh` на стенде
 * [JitIsoStand] → продолжение после подъёма фреймворка → сверка. Проверяется то, что нельзя
 * проверить «по модели»: фактический порядок команд, отсутствие второго клика, поведение при
 * отказе записи снимка, при чужом снимке, при потере канала и при одновременных кликах.
 */
class JitChainEndToEndTest {

    // ─────────────────────────── подставное устройство ───────────────────────────

    private class MemStore : JitStore {
        val map = LinkedHashMap<String, String?>()
        var failRestoreWrite = false
        var failBootWrite = false
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?): Boolean {
            if (key == JitRef.KEY_RESTORE && failRestoreWrite) return false
            if (key == JitRef.KEY_RESTORE_BOOT && failBootWrite) return false
            map[key] = value
            return true
        }
        override fun getBool(key: String, def: Boolean): Boolean =
            when (map[key]) { "true" -> true; "false" -> false; else -> def }
        override fun putBool(key: String, value: Boolean): Boolean { map[key] = value.toString(); return true }
    }

    private class FakeNet(var wifi: Boolean = true, var ap: Boolean = false) : JitNetState {
        val requests = mutableListOf<String>()
        var stateUnknown = false
        override fun wifiOn(): Boolean? = if (stateUnknown) null else wifi
        override fun apUp(): Boolean? = if (stateUnknown) null else ap
        override fun setWifi(on: Boolean): Boolean { requests += "wifi=$on"; wifi = on; return true }
        override fun startAp(): Boolean { requests += "ap=start"; ap = true; return true }
    }

    private fun stand() = JitIsoStand(rules = 0)

    // ─────────────────────────── клик + продолжение ───────────────────────────

    @Test
    fun clickThenContinuationWithoutSecondClick() {
        val stand = stand()
        val store = MemStore()
        val net = FakeNet(wifi = true, ap = false)
        val shell = stand.Shell(getprop = "true", cache = true)
        val log = mutableListOf<String>()

        val click = JitOp.click(store, net, shell, want = true, bootTag = "boot-1", nowMs = 111L, log = { log += it })
        assertEquals(JitOp.ClickResult.STARTED, click.result)
        assertEquals("снимок записан ДО изменений", "wifi=1,ap=0", store.get(JitRef.KEY_RESTORE))
        assertEquals("1", store.get(JitRef.KEY_PEDIDO))
        assertEquals("boot-1", store.get(JitRef.KEY_RESTORE_BOOT))
        assertEquals("тумблер отражён в том же ключе референса", "true", store.get(JitRef.KEY_AUTO))

        // фактический shell-вызов: выключение Wi-Fi, ожидание правил, usejit, zygote последним
        val calls = stand.calls()
        assertEquals("svc wifi disable", calls.first())
        assertEquals("setprop ctl.restart zygote", calls.last())
        assertTrue(calls.contains("setprop dalvik.vm.usejit true"))
        assertFalse("никаких persist-записей", stand.callsText().contains("persist."))

        // фреймворк поднялся: сеть возвращает продолжение ЭТОЙ операции, второго клика нет
        net.wifi = false
        stand.resetCalls()
        val sleeps = mutableListOf<Long>()
        val res = JitOp.continueAfterBoot(store, net, bootTag = "boot-1", sleep = { sleeps += it }, log = { log += it })
        assertEquals(JitOp.RestoreResult.RESTORED, res)
        assertEquals("пауза как postDelayed(15000)", listOf(JitRef.RESTORE_DELAY_MS), sleeps)
        assertTrue("Wi-Fi поднят обратно", net.wifi)
        assertNull("снимок снят после подтверждения", store.get(JitRef.KEY_RESTORE))
        assertTrue("в логе видно, что это продолжение операции",
            log.any { it.contains("JIT_NET_CONT start") } && log.any { it.contains("JIT_NET_CONT done") })

        // и только теперь сверка: getprop + кэш
        val st = JitOp.verifyApplied(store, shell, wantFallback = true)
        assertEquals(JitRef.Verify.OK, st.verdict)
        assertNull("jit_pedido снят", store.get(JitRef.KEY_PEDIDO))
    }

    @Test
    fun continuationRestoresApFirstThenWifi() {
        val stand = stand()
        val store = MemStore()
        val net = FakeNet(wifi = true, ap = true)
        val shell = stand.Shell()
        JitOp.click(store, net, shell, want = true, bootTag = "boot-1", nowMs = 1L)
        // после рестарта zygote: Wi-Fi выключен скриптом, AP тоже упал
        net.wifi = false
        net.ap = false
        val sleeps = mutableListOf<Long>()
        val res = JitOp.continueAfterBoot(store, net, "boot-1", sleep = { sleeps += it })
        assertEquals(JitOp.RestoreResult.RESTORED, res)
        assertEquals("пауза ровно как postDelayed(15000)", listOf(JitRef.RESTORE_DELAY_MS), sleeps)
        assertEquals("AP первым, затем Wi-Fi", listOf("ap=start", "wifi=true"), net.requests)
        assertNull("снимок снят после подтверждения", store.get(JitRef.KEY_RESTORE))
        assertNull(store.get(JitRef.KEY_RESTORE_BOOT))
    }

    @Test
    fun secondClickDoesNotOverwriteUnfinishedOperation() {
        val stand = stand()
        val store = MemStore()
        val net = FakeNet(wifi = true, ap = true)
        val shell = stand.Shell()
        JitOp.click(store, net, shell, want = true, bootTag = "boot-1", nowMs = 1L)
        stand.resetCalls()
        val again = JitOp.click(store, net, shell, want = false, bootTag = "boot-1", nowMs = 2L)
        assertEquals(JitOp.ClickResult.REFUSED_PENDING, again.result)
        assertNull("сеть не тронута повторным кликом", again.run)
        assertTrue("ни одной команды устройству", stand.calls().isEmpty())
        assertEquals("снимок прежней операции сохранён", "wifi=1,ap=1", store.get(JitRef.KEY_RESTORE))
    }

    @Test
    fun concurrentClicksShareOneStoreAndStartOnce() {
        val stand = stand()
        val store = MemStore()
        val net = FakeNet(wifi = true, ap = true)
        val shell = stand.Shell()
        val pool = Executors.newFixedThreadPool(4)
        val barrier = CountDownLatch(4)
        val results = java.util.Collections.synchronizedList(mutableListOf<JitOp.ClickResult>())
        repeat(4) {
            pool.submit {
                barrier.await(5, TimeUnit.SECONDS)
                results += JitOp.click(store, net, shell, want = true, bootTag = "boot-1", nowMs = 1L).result
            }
        }
        barrier.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
        assertEquals("начался ровно один", 1, results.count { it == JitOp.ClickResult.STARTED })
        assertEquals("остальные отказаны", 3, results.count { it == JitOp.ClickResult.REFUSED_PENDING })
        assertEquals("один рестарт zygote в shell", 1, stand.calls().count { it == "setprop ctl.restart zygote" })
    }

    @Test
    fun failedSnapshotWriteMeansNoChainAtAll() {
        val stand = stand()
        val store = MemStore().apply { failRestoreWrite = true }
        val net = FakeNet()
        val shell = stand.Shell()
        val res = JitOp.click(store, net, shell, want = true, bootTag = "boot-1", nowMs = 1L)
        assertEquals(JitOp.ClickResult.SNAPSHOT_WRITE_FAILED, res.result)
        assertNull(res.run)
        assertTrue("сеть не выключалась", stand.calls().isEmpty())
        assertNull("запрос снят", store.get(JitRef.KEY_PEDIDO))
    }

    @Test
    fun unknownNetworkStateMeansNoChainAtAll() {
        val stand = stand()
        val store = MemStore()
        val net = FakeNet().apply { stateUnknown = true }
        val res = JitOp.click(store, net, stand.Shell(), want = true, bootTag = "boot-1", nowMs = 1L)
        assertEquals(JitOp.ClickResult.STATE_UNKNOWN, res.result)
        assertTrue(stand.calls().isEmpty())
        assertNull("снимка нет", store.get(JitRef.KEY_RESTORE))
    }

    // ─────────────────────────── старт: чужие и старые снимки ───────────────────────────

    @Test
    fun foreignSnapshotIsNeitherAppliedNorDeleted() {
        val stand = stand()
        val store = MemStore()
        store.put(JitRef.KEY_RESTORE, "wifi=1,ap=1")
        store.put(JitRef.KEY_RESTORE_BOOT, "boot-OLD")
        store.put(JitRef.KEY_RESTORE_TS, "5")
        val net = FakeNet(wifi = false, ap = false)
        val res = JitOp.continueAfterBoot(store, net, bootTag = "boot-NEW", sleep = {})
        assertEquals(JitOp.RestoreResult.FOREIGN_BOOT, res)
        assertTrue("ни одного запроса сети", net.requests.isEmpty())
        assertEquals("снимок на месте", "wifi=1,ap=1", store.get(JitRef.KEY_RESTORE))
        assertEquals("boot-OLD", store.get(JitRef.KEY_RESTORE_BOOT))
        assertEquals("сеть не тронута", 0, stand.calls().size)
    }

    @Test
    fun plainBootTouchesNothing() {
        val stand = stand()
        val store = MemStore()
        val net = FakeNet(wifi = true, ap = false)
        val res = JitOp.continueAfterBoot(store, net, bootTag = "boot-1", sleep = {})
        assertEquals(JitOp.RestoreResult.NO_RESTORE, res)
        assertTrue(net.requests.isEmpty())
        assertTrue("ноль команд устройству", stand.calls().isEmpty())
    }

    @Test
    fun unparsableSnapshotIsLeftAlone() {
        val store = MemStore()
        store.put(JitRef.KEY_RESTORE, "wifi=on;ap=maybe")
        store.put(JitRef.KEY_RESTORE_BOOT, "boot-1")
        val net = FakeNet(wifi = false)
        val res = JitOp.continueAfterBoot(store, net, bootTag = "boot-1", sleep = {})
        assertEquals(JitOp.RestoreResult.UNPARSABLE, res)
        assertTrue(net.requests.isEmpty())
        assertEquals("не разобрали — не удаляем", "wifi=on;ap=maybe", store.get(JitRef.KEY_RESTORE))
    }

    @Test
    fun unconfirmedRestoreKeepsSnapshotForNextStart() {
        val store = MemStore()
        store.put(JitRef.KEY_RESTORE, "wifi=1,ap=0")
        store.put(JitRef.KEY_RESTORE_BOOT, "boot-1")
        val net = FakeNet(wifi = false).apply { stateUnknown = true }
        val res = JitOp.continueAfterBoot(store, net, bootTag = "boot-1", sleep = {})
        assertEquals(JitOp.RestoreResult.UNCONFIRMED, res)
        assertEquals("снимок сохранён: подтверждения нет", "wifi=1,ap=0", store.get(JitRef.KEY_RESTORE))
    }

    // ─────────────────────────── сверка `bb`/`r7` ───────────────────────────

    @Test
    fun verifySucceedsOnlyWithPropertyAndCache() {
        val stand = stand()
        val store = MemStore()
        store.put(JitRef.KEY_PEDIDO, "1")
        val st = JitOp.verifyApplied(store, stand.Shell(getprop = "true", cache = true), wantFallback = true)
        assertEquals(JitRef.Verify.OK, st.verdict)
        assertNull("jit_pedido снят после сверки", store.get(JitRef.KEY_PEDIDO))
        assertTrue("команда ровно getprop dalvik.vm.usejit",
            stand.calls().any { it == "getprop dalvik.vm.usejit" })
    }

    @Test
    fun verifyWithoutCacheIsNotSuccess() {
        val stand = stand()
        val store = MemStore()
        store.put(JitRef.KEY_PEDIDO, "1")
        val st = JitOp.verifyApplied(store, stand.Shell(getprop = "true", cache = false), wantFallback = true)
        assertEquals(JitRef.Verify.CACHE_MISSING, st.verdict)
        assertFalse("не ok", JitRef.verifyLabel(st.verdict) == "ok")
    }

    @Test
    fun verifyWithoutDataIsUnknown() {
        val stand = stand()
        val store = MemStore()
        val st = JitOp.verifyApplied(store, stand.Shell(getprop = "", cache = null), wantFallback = true)
        assertEquals(JitRef.Verify.NO_DATA, st.verdict)
        assertEquals("unknown", JitRef.verifyLabel(st.verdict))
    }

    @Test
    fun transportLossIsNotRestartProof() {
        val store = MemStore()
        val net = FakeNet()
        val shell = object : JitShell {
            override fun exec(command: String, timeoutMs: Int) = Pair(137, "ERR_ADB_TIMEOUT")
            override fun cacheVisible(): Boolean? = null
        }
        val click = JitOp.click(store, net, shell, want = true, bootTag = "boot-1", nowMs = 1L)
        assertEquals(JitOp.ClickResult.STARTED, click.result)
        assertEquals("канал потерян — и это всё, что известно",
            JitRef.ChainOutcome.CHANNEL_LOST, click.run!!.outcome)
        assertTrue("снимок остался: сеть вернуть всё ещё нужно", store.get(JitRef.KEY_RESTORE) == "wifi=1,ap=0")
    }
}
