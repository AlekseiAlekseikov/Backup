package com.example.voiceapp3.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Границы старта по коду: обычная загрузка и старт «через 30 с» не применяют JIT и не рестартуют
 * zygote; в стартовых путях нет собственных механизмов (boot-backfill, повтор 30 с, файл-намерение).
 */
class JitBootBoundaryTest {

    private fun code(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readLines()
            .filterNot {
                val t = it.trim()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
            }.joinToString("\n")

    @Test
    fun noBootBackfillAnywhere() {
        for (f in listOf(
            "src/main/java/com/example/voiceapp3/tools/AppPerformance.kt",
            "src/main/java/com/example/voiceapp3/VoiceAssistantService.kt",
            "src/main/java/com/example/voiceapp3/App.kt"
        )) {
            val c = code(f)
            assertFalse("$f: нет boot-backfill", c.contains("jitPersistBackfill") || c.contains("bootRestoreJit"))
            assertFalse("$f: нет повтора через 30 с", c.contains("30_000L"))
            assertFalse("$f: нет записи persist-свойств JIT",
                c.contains("setprop \$PROP_PERSIST") || c.contains("setprop persist.dalvik.vm.usejit"))
        }
    }

    @Test
    fun restartOnlyFromClickPath() {
        val ap = code("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        val svc = code("src/main/java/com/example/voiceapp3/VoiceAssistantService.kt")
        val jit = code("src/main/java/com/example/voiceapp3/tools/JitNetCore.kt")
        val jitNet = code("src/main/java/com/example/voiceapp3/tools/JitNet.kt")
        assertFalse("сервис не рестартует фреймворк", svc.contains("ctl.restart zygote"))
        assertFalse("адаптер не собирает команд", jitNet.contains("ctl.restart zygote"))
        // единственное место — литерал скрипта в ядре, и он попадает только в click()
        assertTrue(jit.contains("setprop ctl.restart zygote"))
        assertFalse("AppPerformance не отправляет команду сам", ap.contains("AdbClient.execute(\"setprop ctl.restart"))
    }

    @Test
    fun startPathIsReadOnly() {
        val jit = code("src/main/java/com/example/voiceapp3/tools/JitNetCore.kt")
        // Ядро не выдаёт устройству ни одной команды само: `setprop`/`svc` есть только как ТЕКСТ
        // скрипта референса, который уходит транспорту из click(); сеть меняется через адаптер.
        assertFalse("ядро не зовёт shell", jit.contains("shell.exec(\"setprop") || jit.contains("shell.exec(\"svc"))
        assertFalse("ядро не пишет свойства напрямую", jit.contains("Runtime.getRuntime"))
        assertTrue("сеть меняется только через адаптер",
            jit.contains("netState.setWifi") && jit.contains("netState.wifiOn"))
        assertFalse("точек доступа в JIT нет", jit.contains("startAp"))
    }

    @Test
    fun legacyPersistIsOnlyRead() {
        val ap = code("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        assertTrue("остатки читаются", ap.contains("getprop \$PROP_PERSIST_USEJIT_INIT"))
        assertTrue("и помечены как решение владельца", ap.contains("need=owner_decision"))
        assertTrue("и явно read-only", ap.contains("write=none"))
    }
}
