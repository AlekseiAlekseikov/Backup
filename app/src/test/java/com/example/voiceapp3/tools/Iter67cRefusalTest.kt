package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter67c §5 — штатный отказ JIT не красный и говорит по-русски.
 *
 * `AppLog`/`AppPerformance` зависят от Android и в JVM-стенд не входят: их проводка проверяется
 * по исходнику, а само правило (отметка, уровень, текст) — на чистых `ToolsResult`/`JitUi`.
 */
class Iter67cRefusalTest {
    private val m = "app/src/main/java/com/example/voiceapp3/tools/"

    private fun result(status: ToolsStep.Status, stdout: String = "") = ToolsResult(
        "perfJit", "JIT",
        listOf(ToolsStep("perf_zygote", "нет", 0, 0, 1, stdout = stdout, status = status))
    )


    @Test
    fun `настоящая ошибка остаётся ошибкой`() {
        val r = result(ToolsStep.Status.FAILED)
        assertEquals("✗ FAILED", r.verdictMark)
        assertEquals("ERROR", r.logLevel)
        assertEquals("✓ SUCCESS", result(ToolsStep.Status.OK).verdictMark)
        assertEquals("INFO", result(ToolsStep.Status.PARTIAL).logLevel)
    }


    @Test
    fun `журнал и панель проведены через новое правило`() {
        val log = File(m + "AppLog.kt").readText()
        val toggle = log.substringAfter("fun logToggle").substringBefore("// ───────────── internal")
        assertFalse("в logToggle/logAction остался безусловный «✗ FAILED»: ",
            toggle.contains("else\"✗ FAILED\""))
        assertTrue("TOGGLE берёт отметку из ToolsResult.verdictMark", toggle.contains("\${r.verdictMark}"))
        assertFalse("любой не-ok больше не пишется через e()", toggle.contains("else e(\"CENTRAL_Tools\""))
        assertEquals("обе строки (TOGGLE и ACTION) пишутся по уровню результата", 2,
            Regex("logByLevel\\(r\\.logLevel").findAll(toggle).count())
        // iter65: проверка `JitUi.refusalText(blocked)` в AppPerformance снята — отказы claim/lock
        // удалены вместе со старым кликом; у клика референса единственный отказ — `busy`.
    }
}
