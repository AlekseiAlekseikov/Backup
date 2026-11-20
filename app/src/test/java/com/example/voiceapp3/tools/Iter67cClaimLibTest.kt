package com.example.voiceapp3.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * iter67c §2.2 — тело claim и helper'ы живут в файле-хелпере на устройстве; по ADB уходит обвязка.
 *
 * Исполняется настоящим `/bin/sh` во временном каталоге. Чего тест НЕ доказывает: toybox `sh`,
 * `wc`, `tr` и SELinux на ГУ — это проверит только клик на машине.
 */
class Iter67cClaimLibTest {

    private val base: File = File.createTempFile("jit67lib", "").let { f -> f.delete(); f.mkdirs(); f }
    private val b: String get() = base.absolutePath
    private val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
    private fun log(line: String) { lines += line }

    @Before
    fun setUp() { JitOp.resetStateForTest(); JitDev.reset(); lines.clear() }

    @After
    fun tearDown() { base.deleteRecursively() }

    private fun sh(script: String, name: String): Pair<Int, String> {
        val f = File(base, name).apply { writeText(script) }
        val p = ProcessBuilder("/bin/sh", f.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    private fun op(id: String, ts: Long, seq: Long = 1) = JitOwn.Op(
        id, ts, true, JitOwn.State.CREATED,
        identity = JitOwn.ClaimIdentity(JitOwn.newInstance(id, ts, nonce = 5), "", "", ""), seq = seq
    )

    @Test
    fun `без библиотеки claim ничего не пишет и говорит lib_missing`() {
        val (rc, out) = sh(JitOwn.claimCmd(op("L1", 1_795_191_614_900L), b), "c1.sh")
        assertTrue("нет lib_missing: $out", JitOwn.libMissing(out))
        assertEquals(JitOwn.ClaimWrite.Failed("device_failed"), JitOwn.claimResult(rc, out))
        assertFalse("claim не должен появиться без библиотеки", File(JitOwn.claimPath(b)).exists())
        assertFalse("lock не должен появиться без библиотеки", File(JitOwn.lockPath(b)).exists())
    }

    @Test
    fun `установщик кладёт библиотеку, убирает старую версию, и claim проходит`() {
        val old = File(base, "voiceapp3_jitlib.deadbeef.sh").apply { writeText("echo old\n") }
        val (_, iout) = sh(JitOwn.libInstallScript(b), "inst.sh")
        assertTrue("установщик: $iout", iout.contains("JITLIB_OK bytes=" + JitOwn.libBytes(b)))
        val lib = File(JitOwn.libPath(b))
        assertTrue("файл библиотеки на месте", lib.exists())
        assertEquals("байт в байт", JitOwn.libText(b), lib.readText())
        assertFalse("старая версия убрана", old.exists())
        assertEquals("временных файлов не осталось: " + base.list()!!.toList(), 0,
            base.list()!!.count { it.contains(".tmp.") })
        val (rc, out) = sh(JitOwn.claimCmd(op("L2", 1_795_191_614_901L), b), "c2.sh")
        val w = JitOwn.claimResult(rc, out)
        assertTrue("claim после установки: $w / $out", w is JitOwn.ClaimWrite.Taken)
        assertTrue("claim записан этой операцией", File(JitOwn.claimPath(b)).readText().startsWith("op=L2;"))
    }

    @Test
    fun `обвязка с библиотекой и самодостаточный текст дают одинаковый исход`() {
        sh(JitOwn.libInstallScript(b), "inst.sh")
        val o1 = op("EQ1", 1_795_191_614_910L, seq = 7)
        val viaLib = sh(JitOwn.claimCmd(o1, b), "a.sh").second
        val b2 = File(base, "second").apply { mkdirs() }.absolutePath
        val inline = sh(JitOwn.claimScriptForTest(o1, b2), "b.sh").second
        fun norm(s: String) = s.lines().filter { it.startsWith("CL_") }
            .map { it.replace(Regex("pid=\\d+;pstart=\\d+"), "pid=P;pstart=S").replace(b2, b) }
        assertEquals(norm(inline), norm(viaLib))
    }

    @Test
    fun `библиотека с чужим размером не подключается`() {
        sh(JitOwn.libInstallScript(b), "inst.sh")
        File(JitOwn.libPath(b)).appendText("echo INJECTED\n")
        val (_, out) = sh(JitOwn.claimCmd(op("L3", 1_795_191_614_920L), b), "c3.sh")
        assertTrue("изменённая библиотека не исполняется: $out", JitOwn.libMissing(out))
        assertFalse(out.contains("INJECTED"))
    }

    @Test
    fun `ядро ставит библиотеку и повторяет claim ровно один раз`() {
        var claims = 0
        val shell = object : JitRef.JitShell {
            val scripts = mutableListOf<String>()
            override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
                if (command.contains("CL_BEGIN") && command.contains(JitOwn.CLAIM)) {
                    claims++
                    if (scripts.none { it == "jit_lib" })
                        return 0 to "CL_BEGIN\nCL_LIB_MISSING size=none want=1\nCL_STATUS=failed\nCL_WHY=lib_missing\nCL_END\n"
                }
                return JitDev.exec(command) { 0 to "" }
            }
            override fun execScript(name: String, text: String, timeoutMs: Int): Pair<Int, String> {
                scripts += name
                if (name == "jit_lib") return 0 to "JITLIB_OK bytes=${JitOwn.libBytes()}"
                return JitDev.exec(text) { 0 to "" }
            }
            override fun mapsExcerpt(): String? = null
        }
        JitOp.click(JitFake.Store(), JitFake.Net(wifi = true), shell, want = true, log = ::log)
        assertEquals("claim: первый — lib_missing, второй — после установки", 2, claims)
        val l = lines.firstOrNull { it.startsWith("JIT_CLAIM_LIB ") }
        assertTrue("нет строки JIT_CLAIM_LIB: $lines", l != null && l.contains("result=ok"))
        assertTrue("клик дошёл до цепочки: $lines", lines.any { it.startsWith("JIT_CHAIN_SENT ") })
    }
}
