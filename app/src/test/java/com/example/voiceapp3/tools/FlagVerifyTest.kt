package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33l §1.1 — после каждой recarga мы обязаны СВЕРИТЬ, что лежит в `persist.sys.hvacdigital.*`.
 * Лог 07.11: `HVACDIGITAL_PUSH … out=FLAGS_OK` → `RECARGA_OK`, а слоты на экране не изменились;
 * возможная причина — SystemUI переписывает пропы своими значениями (гипотеза §0.1.2).
 *
 * Тест держит: формат `FLAG_VERIFY`, оба исхода (`match=ok|rewritten`), повторный push
 * `FLAG_REWRITE`, строки `EXT_PROBE` и строку успеха/провала тумблера.
 */
class FlagVerifyTest {

    // 1. Совпадение факта с записанным.
    @Test
    fun verifyMatchOk() {
        val line = HvacFlagsApply.verifyLine("temp:1 batt:0 ext:0", "bind", "temp:1 batt:0 ext:0", "bind")
        assertEquals("FLAG_VERIFY wrote=temp:1 batt:0 ext:0 via=bind found=temp:1 batt:0 ext:0 " +
            "via_found=bind match=ok", line)
        assertTrue(HvacFlagsApply.matches("temp:1 batt:0 ext:0", "bind", "temp:1 batt:0 ext:0", "bind"))
    }

    // 2. SystemUI переписал флаги — это `rewritten`, не успех.
    @Test
    fun verifyRewritten() {
        val line = HvacFlagsApply.verifyLine("temp:1 batt:0 ext:0", "bind", "temp:0 batt:0 ext:0", "nenhum")
        assertTrue(line, line.endsWith("match=rewritten"))
        assertFalse(HvacFlagsApply.matches("temp:1 batt:0 ext:0", "bind", "temp:0 batt:0 ext:0", "nenhum"))
    }

    // 3. Разбор 4 строк getprop: три флага + via.
    @Test
    fun parseFlagsAndVia() {
        val (f, via) = HvacFlagsApply.parseFlagsAndVia("1\n0\n0\nbind\n")
        assertEquals(Triple(true, false, false), f)
        assertEquals("bind", via)
        val (f2, via2) = HvacFlagsApply.parseFlagsAndVia("0\n1\n1")
        assertEquals(Triple(false, true, true), f2)
        assertEquals("", via2)
    }

    // 4. Эксперимент с именем пропа наружней.
    @Test
    fun extProbe() {
        assertEquals("EXT_PROBE prop=temp_ext wrote=1 found=1 result=ok",
            HvacFlagsApply.extProbeLine("temp_ext", "1", "1"))
        assertEquals("EXT_PROBE prop=exttemp wrote=1 found=empty result=missing",
            HvacFlagsApply.extProbeLine("exttemp", "1", ""))
        val s = HvacFlagsApply.extProbeScript()
        assertTrue(s, s.contains("setprop persist.sys.hvacdigital.exttemp 1"))
        assertTrue(s, s.contains("setprop persist.sys.hvacdigital.temp_ext 1"))
        assertFalse("эксперимент ничего не удаляет: $s", s.contains("setprop persist.sys.hvacdigital.temp_ext \"\""))
    }

    // 5. Строка клика: `✓` только при result=ok И verify=ok (§1.4).
    @Test
    fun toggleSuccessRequiresVerify() {
        val ok = HvacFlagsApply.toggleLine("hvacTemp", true, "temp:1 batt:0 ext:0", "bind", "bar_recarga", "ok", "ok")
        assertTrue(ok, ok.endsWith("✓"))
        assertTrue(ok, ok.contains("verify=ok"))

        val lie = HvacFlagsApply.toggleLine("hvacTemp", true, "temp:1 batt:0 ext:0", "bind", "bar_recarga", "ok", "rewritten")
        assertTrue("переписанные пропы — не успех: $lie", lie.endsWith("✗"))

        val fail = HvacFlagsApply.toggleLine("hvacTemp", true, "temp:1 batt:0 ext:0", "bind", "bar_recarga", "fail", "none")
        assertTrue(fail.endsWith("✗"))
    }
}
