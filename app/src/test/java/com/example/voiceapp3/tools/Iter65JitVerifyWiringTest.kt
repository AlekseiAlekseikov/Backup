package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** iter65 — сверка `w0/bb` + `w0/r7` проведена через [JitMech.verify]; подсказка «не применилось». */
class Iter65JitVerifyWiringTest {

    private fun v(pedido: Boolean?, getprop: Boolean?, maps: Boolean?): JitMech.Verify {
        val ok = if (pedido != null && getprop != null) JitMech.verdictOk(pedido, getprop, maps) else null
        return JitMech.Verify(getprop, maps, pedido, ok, ok == false && pedido == true, true, null)
    }

    @Test
    fun hintOnlyWhenRequestedOnAndNotApplied() {
        assertNull(JitMech.notAppliedHint(v(true, true, true)))
        assertNull(JitMech.notAppliedHint(v(true, true, null)))
        assertNull("выключение без тоста (`if (ok || !z5) return`)", JitMech.notAppliedHint(v(false, true, null)))
        assertNull(JitMech.notAppliedHint(v(false, false, null)))
        assertEquals("JIT не применился: dalvik.vm.usejit=false, запрошено true",
            JitMech.notAppliedHint(v(true, false, null)))
        assertTrue(JitMech.notAppliedHint(v(true, true, false))!!.contains("JIT-кэша"))
    }

    private fun code(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readLines()
            .filterNot { val t = it.trim(); t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") }
            .joinToString("\n")

    @Test
    fun appPerformanceUsesReferenceVerifyOnly() {
        val ap = code("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        val i = ap.indexOf("fun verifyJitOnly(")
        val b = ap.substring(i, ap.indexOf("\n    private fun ", i))
        assertEquals(1, Regex("JitMechAndroid\\.verify\\(").findAll(b).count())
        assertTrue(b.contains("JitMech.notAppliedHint(v)"))
        assertTrue(ap.contains("JitMech.GETPROP_CMD, JitMech.GETPROP_TIMEOUT_MS"))
        assertTrue(ap.contains("JitMech.cacheExecutable(JitMechAndroid.selfMaps())"))
        for (old in listOf("JitNet.", "JitOp.", "JitUi.")) assertFalse(old, ap.contains(old))
    }
}
