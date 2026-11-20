package com.example.voiceapp3.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * iter67c §4.3 — выбор пути доставки claim ядром, ОБЕ стороны порога.
 *
 * После §2.2 настоящий claim ≈ 555 Б и всегда идёт inline; файловая ветка остаётся страховкой на
 * случай роста текста. Чтобы она не сгнила непроверенной, длинный текст подставляется через
 * [JitOp.claimCmdForTest]: настоящий claim + безобидная переменная-набивка в начале.
 */
class Iter67cClaimPathTest {

    private val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
    private fun log(line: String) { lines += line }

    /** Транспорт с настоящим пределом одного пакета (4096): длиннее — отбивает. */
    private class RecordingShell : JitRef.JitShell {
        val inline = java.util.Collections.synchronizedList(mutableListOf<String>())
        val scripts = java.util.Collections.synchronizedList(mutableListOf<String>())
        val rejected = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            inline += command
            val bytes = AdbClient.inlinePayloadBytes(command)
            if (bytes > 4_096) { rejected += command; return 1 to "ERR_ADB_TOO_LONG: cmd_len=$bytes > 4096" }
            return JitDev.exec(command) { 0 to "" }
        }
        override fun execScript(name: String, text: String, timeoutMs: Int): Pair<Int, String> {
            scripts += name
            return JitDev.exec(text) { 0 to "" }
        }
        override fun mapsExcerpt(): String? = null
    }

    @Before
    fun setUp() { JitOp.resetStateForTest(); JitDev.reset(); lines.clear() }

    @After
    fun tearDown() { JitOp.resetStateForTest() }

    @Test
    fun longClaimGoesByScriptPath() {
        JitOp.claimCmdForTest = { op -> "_jit_pad=" + "x".repeat(5_000) + "\n" + JitOwn.claimCmd(op) }
        val shell = RecordingShell()
        JitOp.click(JitFake.Store(), JitFake.Net(wifi = true), shell, want = true, log = ::log)
        val d = lines.firstOrNull { it.startsWith("JIT_CLAIM_DELIVERY ") }
        assertTrue("нет JIT_CLAIM_DELIVERY: $lines", d != null && d.contains("path=script"))
        assertEquals("claim ушёл файлом: ${shell.scripts}", 1,
            shell.scripts.count { it.startsWith("jit_claim_") })
        assertTrue("длинный claim не должен идти inline: ${shell.rejected.size}",
            shell.inline.none { it.contains("CL_BEGIN") })
        assertTrue("владение подтверждено: $lines", lines.none { it.contains("claim_unproven") })
        assertTrue("клик дошёл до цепочки: $lines", lines.any { it.startsWith("JIT_CHAIN_SENT ") })
    }

    @Test
    fun shortClaimStaysInline() {
        val shell = RecordingShell()
        JitOp.click(JitFake.Store(), JitFake.Net(wifi = true), shell, want = true, log = ::log)
        val d = lines.firstOrNull { it.startsWith("JIT_CLAIM_DELIVERY ") }
        assertTrue("нет JIT_CLAIM_DELIVERY: $lines", d != null && d.contains("path=inline"))
        assertEquals("claim не должен идти файлом: ${shell.scripts}", 0,
            shell.scripts.count { it.startsWith("jit_claim_") })
        assertEquals(1, shell.inline.count { it.contains("CL_BEGIN") && it.contains(JitOwn.CLAIM) })
        assertTrue("клик дошёл до цепочки: $lines", lines.any { it.startsWith("JIT_CHAIN_SENT ") })
    }
}
