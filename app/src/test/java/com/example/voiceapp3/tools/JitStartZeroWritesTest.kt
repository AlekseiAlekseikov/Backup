package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * «Обычный старт, 30 с и ребут — ноль записей и ноль рестартов» (мандат §1.2, §1.3).
 *
 * Проверяется поведением (счётчик записей хранилища) и кодом (в стартовых путях нет ни рестарта
 * фреймворка, ни записи свойств, ни собственных механизмов).
 */
class JitStartZeroWritesTest {

    private val BOOT = "8b3c9d10-4a5e-4f6b-8c7d-2e1f0a9b8c7d"
    private val OTHER = "1a2b3c4d-5e6f-4a7b-9c8d-0e1f2a3b4c5d"

    private fun stand() = JitIsoStand(rules = 0)

    @Test
    fun plainStartWritesNothing() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = true)
        val shell = JitFake.QueueShell(stand())
        val log = mutableListOf<String>()
        val out = JitOp.onProcessStart(store, net, shell, BOOT, JitFake.Waiter(), log = { log += it })
        assertEquals(JitRef.StartDecision.IDLE, out.decision)
        assertEquals("ноль записей в префы", 0, store.writeCount())
        assertTrue("ноль сетевых запросов", net.requests.isEmpty())
        assertTrue("ноль команд устройству", shell.pending.isEmpty() && shell.executed.isEmpty())
        assertTrue(log.any { it.contains("snapshot=none") })
        assertTrue("обычный старт не заявляет ни рестарта, ни применения",
            log.any { it.contains("restart=not_claimed") || it.contains("snapshot=none") })
    }

    @Test
    fun plainStartWithStaleIntentStillWritesNothing() {
        // Остаток прежних версий: `jit_pedido=true` лежит в префах, снимка нет.
        val store = JitFake.Store()
        store.putBoolConfirmed(JitRef.KEY_PEDIDO, true)
        val before = store.writeCount()
        val net = JitFake.Net(wifi = true, ap = false)
        val out = JitOp.onProcessStart(store, net, JitFake.QueueShell(stand()), BOOT, JitFake.Waiter())
        assertEquals(JitRef.StartDecision.IDLE, out.decision)
        assertEquals("мы не пишем даже «снять намерение» на обычном старте", before, store.writeCount())
        assertEquals("намерение осталось как было", true, store.getBool(JitRef.KEY_PEDIDO))
        assertTrue(net.requests.isEmpty())
    }

    @Test
    fun previousBootStartWritesNothingAndKeepsSnapshot() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.QueueShell(stand())
        JitOp.click(store, net, shell, true, BOOT, 5L)
        net.wifi = false
        val raw = store.rawOp()
        val before = store.writeCount()
        val out = JitOp.onProcessStart(store, net, shell, OTHER, JitFake.Waiter())
        assertEquals(JitRef.StartDecision.KEEP_PREVIOUS_BOOT, out.decision)
        assertEquals("ноль записей", before, store.writeCount())
        assertEquals("снимок не тронут", raw, store.rawOp())
        assertTrue("сеть не тронута", net.requests.isEmpty())
    }

    @Test
    fun noSnapshotNoRestartInCode() {
        fun code(rel: String): String =
            listOf(File(rel), File("app/$rel")).first { it.isFile }.readLines()
                .filterNot {
                    val t = it.trim()
                    t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
                }.joinToString("\n")
        val core = code("src/main/java/com/example/voiceapp3/tools/JitNetCore.kt")
        // рестарт фреймворка — единственная команда в тексте скрипта, и она уходит только из click()
        assertEquals("ровно одно упоминание команды рестарта", 1,
            Regex("ctl\\.restart zygote").findAll(core).count())
        val idxStart = core.indexOf("fun onProcessStart")
        assertTrue(idxStart > 0)
        assertFalse("в стартовом коде нет рестарта", core.substring(idxStart).contains("ctl.restart"))
    }

    @Test
    fun noOwnMechanismsInStartPath() {
        fun code(rel: String): String =
            listOf(File(rel), File("app/$rel")).first { it.isFile }.readText()
        val ap = code("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        for (bad in listOf("jitPersistBackfill", "bootRestoreJit", "jit_pending_verify",
            "jit_verified", "sleep 8", "WatchService", "FileObserver", "cancelFile", "op_ttl")) {
            assertFalse("в коде нет «$bad»", ap.contains(bad))
        }
        val core = code("src/main/java/com/example/voiceapp3/tools/JitNetCore.kt")
        for (bad in listOf("zygote_pid", "Process.myPid()", "pidof", "ps -A", "kill")) {
            assertFalse("ядро не опирается на PID: «$bad»", core.contains(bad))
        }
    }
}
