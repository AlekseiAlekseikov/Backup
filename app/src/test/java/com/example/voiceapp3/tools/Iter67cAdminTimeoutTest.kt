package com.example.voiceapp3.tools

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * iter67c §4 — худший случай `claim` (живой владелец админ-секции) укладывается в таймаут доставки.
 *
 * Исполняется НАСТОЯЩИМ `/bin/sh` на production-тексте `JitOwn.claimCmd`. Чего тест НЕ доказывает:
 * скорость toybox/ext4 на ГУ — там одна итерация может быть дороже, чем на Linux-стенде.
 */
class Iter67cAdminTimeoutTest {

    private val base: File = File.createTempFile("jit67c", "").let { f -> f.delete(); f.mkdirs(); f }
    private val b: String get() = base.absolutePath
    private var sleeper: Process? = null
    private val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
    private fun log(line: String) { lines += line }

    @Before
    fun setUp() {
        JitOp.resetStateForTest()
        JitDev.reset()
        lines.clear()
    }

    @After
    fun tearDown() {
        sleeper?.destroyForcibly()
        base.deleteRecursively()
    }

    private fun bootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("")

    private fun pstartOf(pid: Long): String =
        File("/proc/" + pid + "/stat").readText().trim().split(" ")[21]

    /** Живой (не наш) владелец админ-секции: настоящий процесс с настоящими pid/pstart/boot. */
    private fun liveAdminOwner() {
        val p = ProcessBuilder("sleep", "60").redirectErrorStream(true).start()
        sleeper = p
        val dir = File(JitOwn.lockPath(b) + ".own")
        dir.mkdirs()
        File(dir, "o").writeText(
            "inst=FOREIGN;pid=" + p.pid() + ";pstart=" + pstartOf(p.pid()) + ";boot=" + bootId() + "\n"
        )
    }

    private fun op() = JitOwn.Op(
        "OP67C", 1_795_191_614_900L, true, JitOwn.State.CREATED,
        identity = JitOwn.ClaimIdentity(JitOwn.newInstance("OP67C", 1_795_191_614_900L, nonce = 3), "", "", ""),
        seq = 1
    )

    @Test
    fun `claim при живом владельце админ-секции укладывается в 6 секунд`() {
        liveAdminOwner()
        val f = File(base, "claim.sh")
        f.writeText(JitOwn.claimCmd(op(), b))
        val t0 = System.nanoTime()
        val p = ProcessBuilder("/bin/sh", f.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("ITER67C_WORST_CASE ms=" + ms + " out_tail=" + out.trim().lines().takeLast(4))
        assertTrue("исход должен быть busy (живой владелец не перехватывается): " + out,
            out.contains("CL_STATUS=busy") && out.contains("CL_END"))
        // Красный ДО (400 итераций по 0.02 с): ≥ 8000 мс при таймауте отправки 8000.
        assertTrue("худший случай claim занял " + ms + " мс, предел 6000", ms < 6_000)
        assertTrue("отказ обязан называть таймаут ожидания и число итераций: " + out,
            out.contains("CL_ADMIN_REFUSE=busy_timeout") && out.contains("iters="))
    }

    @Test
    fun `таймаут ожидания админ-секции печатается строкой JIT_CLAIM_TIMEOUT`() {
        val reply = "CL_BEGIN\nCL_ORDER seen_op=none\nCL_ADMIN_REFUSE=busy_timeout iters=100 max=100\n" +
            "CL_STATUS=busy\nCL_OWNER=admin_busy\nCL_END\n"
        val shell = object : JitRef.JitShell {
            override fun exec(command: String, timeoutMs: Int): Pair<Int, String> =
                JitDev.exec(command) { 0 to reply }
            override fun execScript(name: String, text: String, timeoutMs: Int): Pair<Int, String> =
                0 to reply
            override fun mapsExcerpt(): String? = null
        }
        JitOp.click(JitFake.Store(), JitFake.Net(wifi = true), shell, want = true, log = ::log)
        val t = lines.firstOrNull { it.startsWith("JIT_CLAIM_TIMEOUT ") }
        assertTrue("нет строки JIT_CLAIM_TIMEOUT: " + lines.filter { it.contains("CLAIM") }, t != null)
        assertTrue("не названо время: " + t, t!!.contains("waited_ms="))
        assertTrue("не названо число итераций: " + t, t.contains("iters=100"))
        assertTrue("не названо действие: " + t, t.contains("action=stop"))
    }

    @Test
    fun `обычная занятость не выдаётся за таймаут`() {
        val reply = "CL_BEGIN\nCL_STATUS=busy\nCL_OWNER=op=X;ts_ms=1;want=1;inst=Y;pid=1;pstart=1;boot=b\nCL_END\n"
        val shell = object : JitRef.JitShell {
            override fun exec(command: String, timeoutMs: Int): Pair<Int, String> =
                JitDev.exec(command) { 0 to reply }
            override fun execScript(name: String, text: String, timeoutMs: Int): Pair<Int, String> =
                0 to reply
            override fun mapsExcerpt(): String? = null
        }
        JitOp.click(JitFake.Store(), JitFake.Net(wifi = true), shell, want = true, log = ::log)
        assertTrue("busy_alive — не таймаут: " + lines.filter { it.contains("TIMEOUT") },
            lines.none { it.startsWith("JIT_CLAIM_TIMEOUT ") })
    }
}
