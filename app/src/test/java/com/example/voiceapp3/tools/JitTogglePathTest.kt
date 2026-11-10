package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33p (1.0.61) — путь клика тумблера в боевом коде: `setAutoOptimize` → `jitApplyWithZygote`
 * → [JitNet.click] → [JitOp.click] → один скрипт референса.
 *
 * Тест статический (Gradle-модуль без Android SDK здесь не собрать), но проверяет именно то, что
 * требовал мандат §4: в этом пути нет собственных механизмов и нет persist-записей, а рестарт
 * фреймворка не отправляется ниоткуда, кроме скрипта `JitRef.chainScript`.
 */
class JitTogglePathTest {

    /** Без комментариев: в них мы ОБЪЯСНЯЕМ, чего в коде нет (иначе тест ловит сам себя). */
    private fun strip(text: String): String = text.lines()
        .filterNot {
            val t = it.trim()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }.joinToString("\n")

    private fun perf(): String =
        listOf(
            File("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt"),
            File("app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        ).first { it.isFile }.readText()

    @Test
    fun toggleCallsZygotePathWithoutBackfill() {
        val s = perf()
        val from = s.indexOf("fun setAutoOptimize")
        val to = s.indexOf("fun ", from + 10)
        val block = strip(s.substring(from, if (to > from) to else s.length))
        assertTrue("тумблер вызывает путь рестарта", block.contains("jitApplyWithZygote(context, enabled)"))
        assertFalse("boot-backfill удалён", block.contains("jitPersistBackfill"))
        assertFalse("persist-записи в тумблере нет", block.contains("persist."))
        assertFalse("AVAS не участвует в JIT-клике", block.contains("Avas"))
    }

    @Test
    fun clickDelegatesToNetAndDoesNotRunItsOwnScript() {
        val s = perf()
        val from = s.indexOf("fun jitApplyWithZygote")
        val to = s.indexOf("// ═══════════ JIT_ZYGOTE_END")
        val block = strip(s.substring(from, to))
        assertTrue("клик идёт через JitNet", block.contains("JitNet.click(app, enabled, bootTag, t0)"))
        assertFalse("своей сборки скрипта нет", block.contains("chainScript("))
        // Свойства выставляет ТОЛЬКО скрипт референса: здесь нет ни chainScript, ни запуска
        // команд, а строка с `setprop …` — подпись шага для UI (проверяется ниже отдельным тестом).
        assertFalse("своего запуска AdbClient нет", block.contains("AdbClient.execute"))
        assertFalse("своей проверки zygote-PID нет", block.contains("zygote_pid"))
    }

    @Test
    fun restartCommandLivesOnlyInReferenceScript() {
        val s = perf()
        // В AppPerformance `setprop ctl.restart zygote` встречается только в тексте для UI.
        val lines = s.lines().filter { it.contains("ctl.restart zygote") }
        assertEquals("одно упоминание — подпись шага", 1, lines.size)
        assertTrue("это подпись, а не команда", lines.first().trimStart().startsWith("\"setprop ctl.restart zygote"))
        assertTrue("сама команда есть в скрипте референса",
            JitRef.chainScript(true).contains("setprop ctl.restart zygote"))
    }

    @Test
    fun serviceStillDrivesOnlyReadOnlyProbe() {
        val svc = listOf(
            File("src/main/java/com/example/voiceapp3/VoiceAssistantService.kt"),
            File("app/src/main/java/com/example/voiceapp3/VoiceAssistantService.kt")
        ).first { it.isFile }.readText()
        assertTrue("сервис планирует сверку/продолжение", svc.contains("checkPendingZygoteVerify"))
        assertFalse("сервис не пишет JIT-свойства", svc.contains("setprop dalvik.vm.usejit"))
        assertFalse("сервис не перезапускает zygote", svc.contains("ctl.restart zygote"))
        assertFalse("второго проходa через 30 с нет", svc.contains("30_000L"))
    }
}
