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
    fun `штатный отказ без FAILED и без крестика, уровень WARN`() {
        val r = result(ToolsStep.Status.BLOCKED, JitUi.refusalText("claim_unproven")!!)
        val line = "TOGGLE [perfJit] → ON | ${r.verdictMark} | ${r.summary}"
        assertFalse("в строке отказа FAILED: $line", line.contains("FAILED"))
        assertFalse("в строке отказа ✗: $line", line.contains("✗"))
        assertEquals("уровень журнала отказа", "WARN", r.logLevel)
        assertEquals("OP_SUMMARY обязан остаться BLOCKED", "BLOCKED", r.statusWire.uppercase())
    }

    @Test
    fun `настоящая ошибка остаётся ошибкой`() {
        val r = result(ToolsStep.Status.FAILED)
        assertEquals("✗ FAILED", r.verdictMark)
        assertEquals("ERROR", r.logLevel)
        assertEquals("✓ SUCCESS", result(ToolsStep.Status.OK).verdictMark)
        assertEquals("INFO", result(ToolsStep.Status.PARTIAL).logLevel)
    }

    @Test
    fun `тексты отказов по-русски, дословно по заданию`() {
        assertEquals("не отправлено: внутренняя ошибка размера команды (сообщите разработчику)",
            JitUi.refusalText("claim_cmd_too_long"))
        assertEquals("не отправлено: не удалось подтвердить владение, повторите",
            JitUi.refusalText("claim_unproven"))
        assertEquals("не отправлено: уже выполняется другая операция JIT",
            JitUi.refusalText("op_in_flight"))
        assertEquals(null, JitUi.refusalText("wifi_unknown"))
        val summary = result(ToolsStep.Status.BLOCKED, JitUi.refusalText("claim_cmd_too_long")!!).summary
        assertTrue("сводка панели обязана показать русский текст: $summary",
            summary.startsWith("не отправлено: внутренняя ошибка размера команды"))
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
