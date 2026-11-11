package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** `ip rule` только читается: ни `del`, ни `flush`, ни `ndc`, ни `settings` (§1.2). */
class JitRulesReadOnlyTest {

    private fun code(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readLines()
            .filterNot {
                val t = it.trim()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") || t.startsWith("#")
            }.joinToString("\n")

    @Test
    fun codeOnlyCountsRules() {
        val c = code("src/main/java/com/example/voiceapp3/tools/JitNetCore.kt")
        assertTrue("правило только считается", c.contains("grep -c 'oif wlan0'"))
        for (bad in listOf("ip rule del", "ip rule flush", "ndc ", "settings put", "setenforce", "route add")) {
            assertFalse("JitNetCore не содержит «$bad»", c.contains(bad))
        }
    }

    @Test
    fun scriptOnlyCountsRules() {
        val stand = JitIsoStand(rules = 3)
        stand.exec(JitRef.chainScript(true))
        assertEquals("правила считаются до нуля", 4, stand.calls().count { it == "ip rule show" })
        assertFalse(stand.calls().any { it.contains("del") || it.contains("flush") })
        assertEquals("setprop ctl.restart zygote", stand.calls().last())
    }
}
