package com.example.voiceapp3.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33p (1.0.61) — на обычном старте/загрузке ГУ ничего не применяется.
 *
 * Проверяется ИСПОЛНЯЕМО ([JitOp.continueAfterBoot] без снимка) и статически по коду стартовых
 * путей: в них не должно остаться ни `bootRestoreJit`, ни повтора через 30 с, ни записи persist.
 */
class JitBootTest {

    private fun src(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readText()

    private fun code(rel: String): String = src(rel).lines()
        .filterNot {
            val t = it.trim()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }.joinToString("\n")

    @Test
    fun bootRestoreRemovedFromAllPaths() {
        for (f in listOf(
            "src/main/java/com/example/voiceapp3/tools/AppPerformance.kt",
            "src/main/java/com/example/voiceapp3/VoiceAssistantService.kt"
        )) {
            val c = code(f)
            assertFalse("$f: bootRestoreJit удалён", c.contains("bootRestoreJit"))
            assertFalse("$f: boot-backfill удалён", c.contains("jitPersistBackfill"))
            assertFalse("$f: нет повтора через 30 с", c.contains("30_000L"))
        }
    }

    @Test
    fun serviceStartupDoesNotRestartZygote() {
        val c = code("src/main/java/com/example/voiceapp3/VoiceAssistantService.kt")
        assertFalse("сервис не просит рестарт фреймворка", c.contains("ctl.restart zygote"))
        assertFalse("сервис не пишет dalvik.vm.usejit", c.contains("setprop dalvik.vm.usejit"))
    }

    @Test
    fun plainBootTouchesNoNetwork() {
        val stand = JitIsoStand(rules = 0)
        val store = object : JitStore {
            val m = LinkedHashMap<String, String?>()
            override fun get(key: String): String? = m[key]
            override fun put(key: String, value: String?): Boolean { m[key] = value; return true }
            override fun getBool(key: String, def: Boolean): Boolean = def
            override fun putBool(key: String, value: Boolean): Boolean { m[key] = value.toString(); return true }
        }
        val net = object : JitNetState {
            val requests = mutableListOf<String>()
            override fun wifiOn(): Boolean? = true
            override fun apUp(): Boolean? = false
            override fun setWifi(on: Boolean): Boolean { requests += "wifi"; return true }
            override fun startAp(): Boolean { requests += "ap"; return true }
        }
        val res = JitOp.continueAfterBoot(store, net, bootTag = "boot-1", sleep = {})
        assertTrue("обычный старт: снимка нет", res == JitOp.RestoreResult.NO_RESTORE)
        assertTrue("ноль запросов сети", net.requests.isEmpty())
        assertTrue("ноль shell-команд", stand.calls().isEmpty())
    }

    @Test
    fun bootProbeIsReadOnlyAndReportsLeftovers() {
        val c = code("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        assertTrue("проба читает runtime", c.contains("echo RT="))
        assertTrue("проба сообщает об остатках прежних версий",
            c.contains("JIT_BOOT_LEGACY") && c.contains("need=owner_decision"))
        assertTrue("проба прямо пишет, что ничего не пишет", c.contains("write=none"))
        assertFalse("проба не пишет persist.dalvik.vm.usejit",
            c.contains("setprop \$PROP_PERSIST_USEJIT_INIT"))
        assertFalse("проба не пишет маркер", c.contains("setprop \$PROP_PERSIST_USEJIT"))
    }

    @Test
    fun noOwnWatcherOrJournalMechanismsLeft() {
        for (f in listOf("JitNetCore.kt", "JitNet.kt", "AppPerformance.kt")) {
            val c = code("src/main/java/com/example/voiceapp3/tools/$f")
            for (bad in listOf("JITSTEP", "OPERATION_TTL", "watcher", "cancelFile", "discardNoEffect",
                "COMPENSATE_RESTORE", "HOLD_UNKNOWN", "sleep 8", "jit_pending_verify")) {
                assertFalse("$f: собственный механизм «$bad» удалён", c.contains(bad))
            }
        }
    }
}
