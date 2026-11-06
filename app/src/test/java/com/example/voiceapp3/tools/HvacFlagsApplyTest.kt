package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33k §1–§2 — клик тумблера = флаги + мягкий apply плагина, без `plugin.enable`.
 * Тест держит строки лога, `via`, разбор факта из `getprop` и классификацию apply.
 */
class HvacFlagsApplyTest {

    // 1. via: хоть один включён → bind; все выключены → nenhum.
    @Test
    fun via() {
        assertEquals("nenhum", HvacFlagsApply.viaFor(false, false, false))
        assertEquals("bind", HvacFlagsApply.viaFor(false, true, false))
        assertEquals("bind", HvacFlagsApply.viaFor(true, true, true))
    }

    // 2. Флаги в формате референса x7.i(): temp/batt/ext.
    @Test
    fun flagsSegment() {
        assertEquals("temp:0 batt:1 ext:0", HvacFlagsApply.flagsSegment(false, true, false))
        assertEquals("temp:1 batt:1 ext:1", HvacFlagsApply.flagsSegment(true, true, true))
    }

    // 3. Разбор факта из getprop (три строки, пустое = 0).
    @Test
    fun parse() {
        assertEquals(Triple(true, false, true), HvacFlagsApply.parseFlags("1\n0\n1\n"))
        assertEquals(Triple(false, false, false), HvacFlagsApply.parseFlags(""))
        assertEquals(Triple(false, true, false), HvacFlagsApply.parseFlags("0\n1\n"))
    }

    // 4. Строка TOGGLE мандата §1.4 — со статусом ✓/✗, без красных слов.
    @Test
    fun toggleLine() {
        val ok = HvacFlagsApply.toggleLine("hvacBatt", true, "temp:0 batt:1 ext:0", "bind", "plugin_restart", "ok")
        assertEquals(
            "TOGGLE [hvacBatt] → ON | flags=temp:0 batt:1 ext:0 | via=bind | apply=plugin_restart result=ok | ✓",
            ok)
        finalAssert { assertFalse(ok, ok.contains("ошибка")) }
        finalAssert {
            assertTrue(HvacFlagsApply.toggleLine("hvacTemp", false, "temp:0 batt:0 ext:0", "nenhum", "plugin_restart", "failed")
                .endsWith("✗"))
        }
        finalAssert {
            assertTrue(HvacFlagsApply.toggleLine("hvacTemp", false, "temp:0 batt:0 ext:0", "nenhum", "plugin_restart", "noop")
                .endsWith("✓"))
        }
    }

    // 5. Строка FLAG_APPLY.
    @Test
    fun applyLine() {
        assertEquals("FLAG_APPLY method=plugin_restart flags=temp:0 batt:1 ext:0 result=ok",
            HvacFlagsApply.applyLine("plugin_restart", "temp:0 batt:1 ext:0", "ok"))
        assertTrue(HvacFlagsApply.applyLine("sysui_restart", "temp:1 batt:0 ext:0", "noop")
            .startsWith("FLAG_APPLY method=sysui_restart "))
    }

    // 6. Скрипт мягкого apply: только процесс плагина, SystemUI не трогаем.
    @Test
    fun pluginRestartScript() {
        val s = HvacFlagsApply.pluginRestartScript()
        assertTrue(s, s.contains("pidof com.flyme.auto.systemuiplugin"))
        assertTrue(s, s.contains("APPLY_KILLED="))
        assertTrue(s, s.contains("APPLY_NOOP="))
        assertFalse("SystemUI в авто-apply запрещён: $s", s.contains("com.android.systemui"))
        assertFalse("plugin.enable из тумблера запрещён: $s", s.contains("enable"))
    }

    // 7. Классификация результата скрипта.
    @Test
    fun applyResult() {
        assertEquals("ok", HvacFlagsApply.applyResult("APPLY_KILLED=1234"))
        assertEquals("noop", HvacFlagsApply.applyResult("APPLY_NOOP=no_process"))
        assertEquals("denied", HvacFlagsApply.applyResult("kill: Permission denied"))
        assertEquals("failed", HvacFlagsApply.applyResult("  "))
    }

    private fun finalAssert(block: () -> Unit) = block()
}
