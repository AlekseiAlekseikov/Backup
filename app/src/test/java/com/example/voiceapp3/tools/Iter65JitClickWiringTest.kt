package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** iter65 — клик тумблера JIT идёт в [JitMech.click] (через [JitMechAndroid]) и только туда. */
class Iter65JitClickWiringTest {

    private fun code(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readLines()
            .filterNot { val t = it.trim(); t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") }
            .joinToString("\n")

    private val ap get() = code("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")

    private fun body(src: String, fn: String): String {
        val i = src.indexOf("fun $fn(")
        assertTrue(fn, i > 0)
        val j = src.indexOf("\n    fun ", i + 1).let { if (it < 0) src.length else it }
        return src.substring(i, j)
    }

    @Test
    fun applyCallsReferenceClickOnce() {
        val b = body(ap, "jitApplyWithZygote")
        assertEquals(1, Regex("JitMechAndroid\\.click\\(").findAll(b).count())
        assertTrue(b.contains("BgWorker.assertNotMain"))
        for (old in listOf("JitNet.click", "JitOp.", "JitUi.", "JitRef.mountStepNote"))
            assertFalse(old, b.contains(old))
    }

    @Test
    fun setAutoOptimizeAppliesOnceAndWritesNoPrefsItself() {
        val b = body(ap, "setAutoOptimize")
        assertEquals(1, Regex("jitApplyWithZygote\\(").findAll(b).count())
        assertFalse("префы пишет JitMech.click", b.contains("edit()") || b.contains("Prefs2."))
        assertFalse("нет persist-свойств", b.contains("persist."))
    }

    @Test
    fun nothingElseSendsTheChain() {
        val dir = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val users = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("ctl.restart zygote") }
            .map { it.name }.toSet()
        assertTrue(users.toString(), "JitMech.kt" in users)
        // Остальные упоминания — только тексты/комментарии, не команды: проверяем, что `setprop
        // ctl.restart zygote` как исполняемая строка есть лишь в JitMech.
        val exec = dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "JitMech.kt" }
            .filter { f -> f.readLines().any { l -> val t = l.trim()
                !t.startsWith("*") && !t.startsWith("//") && Regex("execute\\([^)]*ctl\\.restart zygote").containsMatchIn(t) } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), exec)
    }
}
