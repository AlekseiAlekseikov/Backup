package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter66 — доказательство доставки цепочки JIT: adbd ответил `OKAY` на `OPEN shell:<цепочка>`
 * ([AdbOpenHook]) → строка `JIT_CHAIN_SENT` до гибели процесса (ответа `CLSE` может не быть).
 */
class Iter66ChainSentTest {

    private val dir = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private fun src(name: String) = File(dir, "com/example/voiceapp3/tools/$name").readText()

    @Test
    fun hookFiresOnceAndOnlyForItsOwnCommand() {
        val h = AdbOpenHook()
        var n = 0
        h.arm("CHAIN") { n++ }
        assertFalse("проба `id` внутри той же задачи — не доставка цепочки", h.opened("id"))
        assertEquals(0, n)
        assertTrue(h.opened("CHAIN"))
        assertFalse("второй OKAY той же команды — уже не событие", h.opened("CHAIN"))
        assertEquals(1, n)
    }

    @Test
    fun disarmedOrNullHookNeverFires() {
        val h = AdbOpenHook()
        var n = 0
        h.arm("CHAIN") { n++ }
        h.disarm()
        assertFalse(h.opened("CHAIN"))
        h.arm("CHAIN", null)
        assertFalse(h.opened("CHAIN"))
        assertEquals(0, n)
    }

    @Test
    fun throwingHookDoesNotBreakTheTransport() {
        val h = AdbOpenHook()
        h.arm("CHAIN") { throw IllegalStateException("x") }
        assertTrue(h.opened("CHAIN"))
    }

    @Test
    fun transportArmsPerJobAndFiresOnOkayOfBothChannels() {
        val a = src("AdbClient.kt")
        assertTrue("Job несёт onOpened", Regex("val onOpened: \\(\\(\\) -> Unit\\)\\? = null").containsMatchIn(a))
        assertTrue("worker взводит перед runCommand", a.contains("openHook.arm(job.cmd, job.onOpened)"))
        assertTrue("и снимает после", a.contains("openHook.disarm()"))
        assertEquals("OKAY на TCP 5555 и на unix-канале", 2,
            Regex("OKAY -> \\{ localId = (msg|m)\\.arg0; openHook\\.opened\\(command\\) \\}").findAll(a).count())
        assertTrue(a.contains("fun executeNotifyOpened(command: String, timeoutMs: Int, onOpened: () -> Unit)"))
    }

    @Test
    fun androidShellUsesTheConfirmingTransportForTheChain() {
        val m = src("JitMechAndroid.kt")
        assertTrue(m.contains("override fun execNotify("))
        assertTrue(m.contains("AdbClient.executeNotifyOpened(command, timeoutMs, onSent)"))
    }
}
