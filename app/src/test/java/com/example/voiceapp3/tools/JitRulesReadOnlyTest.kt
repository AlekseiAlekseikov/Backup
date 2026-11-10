package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33p (1.0.61) — `ip rule` только ЧИТАЕТСЯ.
 *
 * В референсе (`w0/la.run`, `w0/e2.run`) правил никто не удаляет — там есть только `grep -c`.
 * Наша диагностика тоже считает правила и печатает число: ни `del`, ни `flush`, ни `ndc`,
 * ни `settings` в JIT-коде нет (мандат §1.2).
 */
class JitRulesReadOnlyTest {

    private fun code(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readLines()
            .filterNot { l ->
                val t = l.trim()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") || t.startsWith("#")
            }.joinToString("\n")

    @Test
    fun rulesAreOnlyCounted() {
        val c = code("src/main/java/com/example/voiceapp3/tools/JitNet.kt")
        assertTrue("правило только считается: grep -c 'oif wlan0'", c.contains("grep -c 'oif wlan0'"))
        for (bad in listOf("ip rule del", "ip rule flush", "ndc ", "settings put", "setenforce")) {
            assertFalse("JitNet не должен содержать «$bad»", c.contains(bad))
        }
    }

    @Test
    fun countIsDiagnosticsNotVerdict() {
        val stand = JitIsoStand(rules = 3)
        stand.exec(JitRef.chainScript(want = true))
        val calls = stand.calls()
        assertEquals("правила считаются трижды (3 → 0)", 4, calls.count { it == "ip rule show" })
        assertFalse("ни одного удаления", calls.any { it.contains("del") || it.contains("flush") })
        assertEquals("setprop ctl.restart zygote", calls.last())
    }
}
