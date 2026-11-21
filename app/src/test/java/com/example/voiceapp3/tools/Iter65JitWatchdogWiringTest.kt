package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter65 — проводка возврата Wi-Fi: `JitWatchdogService` (аналог `CentralWatchdogService`),
 * объявлен в манифесте, стартует из `BootReceiver` (BOOT_COMPLETED) и
 * `VoiceAssistantService.onCreate`; старый возврат на старте процесса (`JitNet.onProcessStart`)
 * больше не вызывается.
 */
class Iter65JitWatchdogWiringTest {

    private fun file(rel: String): File =
        listOf(File(rel), File("app/$rel")).first { it.isFile }

    private fun code(rel: String): String = file(rel).readLines()
        .filterNot { val t = it.trim(); t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") }
        .joinToString("\n")

    private val pkg = "src/main/java/com/example/voiceapp3"

    @Test
    fun manifestDeclaresPrivateService() {
        val m = file("src/main/AndroidManifest.xml").readText()
        val i = m.indexOf("android:name=\".JitWatchdogService\"")
        assertTrue("сервис объявлен", i > 0)
        val decl = m.substring(m.lastIndexOf("<service", i), m.indexOf("/>", i))
        assertTrue(decl, decl.contains("android:exported=\"false\""))
    }

    @Test
    fun serviceRunsReferenceRestoreAndNothingElse() {
        val s = code("$pkg/JitWatchdogService.kt")
        assertTrue(s.contains("JitMechAndroid.watchdogStart(applicationContext) { alive }"))
        assertTrue("путь inline при отказе старта", s.contains("JIT_WATCHDOG path=inline"))
        for (bad in listOf("setWifiApEnabled", "startSoftAp", "ap0", "setprop", "AdbClient", "kill"))
            assertFalse(bad, s.contains(bad))
    }

    @Test
    fun startedFromBootReceiverAndVoiceService() {
        val boot = code("$pkg/BootReceiver.kt")
        val i = boot.indexOf("JitWatchdogService.start(app, \"boot\")")
        assertTrue(i > 0)
        assertTrue("только после разблокировки",
            boot.substring(0, i).trimEnd().endsWith("if (action == Intent.ACTION_BOOT_COMPLETED) {\n                runCatching {"))
        val svc = code("$pkg/VoiceAssistantService.kt")
        val onCreate = svc.substring(svc.indexOf("override fun onCreate()"), svc.indexOf("override fun onStartCommand"))
        assertEquals(1, Regex("JitWatchdogService\\.start\\(").findAll(onCreate).count())
    }

    @Test
    fun processStartNoLongerRestoresNetwork() {
        val ap = code("$pkg/tools/AppPerformance.kt")
        assertFalse(ap.contains("JitNet.onProcessStart"))
        assertFalse(code("$pkg/VoiceAssistantService.kt").contains("onProcessStart"))
    }
}
