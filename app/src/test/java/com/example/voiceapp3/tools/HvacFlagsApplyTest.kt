package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33k fix1 — клик тумблера = флаги (`x7.i`) + recarga панели (`x7.g("tela")`), как в референсе.
 * Тест держит строки лога, `via`, разбор факта из `getprop`, recarga-скрипт и отсутствие
 * собственных механизмов (kill процесса плагина, отдельная кнопка применения).
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

    // 5. Строка FLAG_APPLY (формат fix1: method=bar_recarga tries=… result=… dt=…ms).
    @Test
    fun applyLine() {
        assertEquals("FLAG_APPLY method=bar_recarga tries=3 result=ok dt=1620ms",
            HvacFlagsApply.applyLine("bar_recarga", 3, "ok", 1620L))
        assertEquals("FLAG_APPLY method=bar_recarga tries=20 result=fail dt=20010ms",
            HvacFlagsApply.applyLine("bar_recarga", 20, "fail", 20010L))
        assertEquals("bar_recarga fail (barra NAO reiniciou) tries=20", HvacFlagsApply.recargaFailLine(20))
        for (l in listOf(
            HvacFlagsApply.applyLine("bar_recarga", 1, "ok", 520L),
            HvacFlagsApply.recargaFailLine(20))) {
            finalAssert { assertFalse(l, l.contains("ошибка")) }
        }
    }

    // 6. Скрипт recarga — 1:1 с референсом `x7.g("tela")`: панель, не плагин.
    @Test
    fun recargaScript() {
        val s = HvacFlagsApply.recargaScript()
        assertTrue("нет команды референса:\n$s", s.contains("PB=\$(pidof com.android.systemui)"))
        assertTrue("нет условного kill ([ -z ] || kill):\n$s", s.contains("[ -z \"\$PB\" ] || kill \$PB"))
        assertTrue("нет ожидания подъёма", s.contains("sleep 0.5"))
        assertTrue("нет проверки смены pid", s.contains("[ \"\$PA\" != \"\$PB\" ]"))
        assertTrue("нет строки успеха", s.contains("RECARGA_OK pid_before="))
        assertTrue("нет строки провала словами референса", s.contains("barra NAO reiniciou"))
        assertTrue("ретраи должны быть 20", s.contains("-lt 20"))
        assertFalse("процесс плагина референс не убивает: $s", s.contains("com.flyme.auto.systemuiplugin"))
        assertFalse("plugin.enable из тумблера запрещён: $s", s.contains("enable"))
    }

    // 7. Разбор вывода recarga.
    @Test
    fun recargaResult() {
        assertEquals("ok" to 3, HvacFlagsApply.recargaResult("RECARGA_OK pid_before=1174 pid_after=20740 tries=3"))
        assertEquals("fail" to 20, HvacFlagsApply.recargaResult("RECARGA_FAIL pid_before=1174 pid_after=none tries=20 (barra NAO reiniciou)"))
        assertEquals("fail" to 0, HvacFlagsApply.recargaResult("ERR_ROOT_CHANNEL_UNAVAILABLE"))
        assertEquals("fail" to 0, HvacFlagsApply.recargaResult(""))
    }

    private fun finalAssert(block: () -> Unit) = block()
}
