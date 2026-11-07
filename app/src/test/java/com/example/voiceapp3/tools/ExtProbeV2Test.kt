package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33m §3 — `EXT_PROBE_V2`: поочерёдный перебор ТРЁХ имён слота наружней.
 *
 * Лог 07.11 (1.0.57): `EXT_PROBE prop=exttemp wrote=1 found=1 result=ok` и то же для `temp_ext`
 * — панель не изменилась, потому что оба имени стояли `=1` одновременно, и было непонятно,
 * какое из них читает SystemUI. Теперь: одно имя `=1`, остальные `=0` ОДНОЙ командой,
 * полный цикл recarga, пауза 15 с, и только потом следующее имя.
 *
 * Плюс третье имя из `getprop.txt` — `persist.sys.hvacdigital.ext_temp`.
 */
class ExtProbeV2Test {

    private fun src(rel: String): String {
        val f = listOf(File(rel), File("app/$rel")).firstOrNull { it.isFile }
            ?: error("не найден $rel (cwd=${File(".").absolutePath})")
        return f.readText()
    }

    @Test
    fun threeNamesInFixedOrder() {
        assertEquals(
            listOf(
                "persist.sys.hvacdigital.exttemp",
                "persist.sys.hvacdigital.temp_ext",
                "persist.sys.hvacdigital.ext_temp"),
            HvacFlagsApply.EXT_NAMES
        )
        assertEquals("ext_temp — третье имя из getprop.txt",
            "persist.sys.hvacdigital.ext_temp", HvacFlagsApply.PROP_EXT_THIRD)
    }

    @Test
    fun writeScriptSetsOneAndClearsOthersInOneCommand() {
        val s = HvacFlagsApply.extProbeV2WriteScript("persist.sys.hvacdigital.temp_ext")
        assertEquals(
            "setprop persist.sys.hvacdigital.exttemp 0" +
                " && setprop persist.sys.hvacdigital.temp_ext 1" +
                " && setprop persist.sys.hvacdigital.ext_temp 0" +
                " && echo FLAGS_OK",
            s
        )
        // одна команда: ни одной точки с запятой, только &&
        assertFalse("шаг обязан быть одной командой (&&, не ;)", s.contains(";"))
        // на каждое из трёх имён ровно один setprop
        for (n in HvacFlagsApply.EXT_NAMES) assertEquals(1, Regex("setprop $n ").findAll(s).count())
        // ровно одна единица
        assertEquals(1, Regex(" 1\\b").findAll(s).count())
    }

    @Test
    fun readScriptReadsAllThreeInOrder() {
        val s = HvacFlagsApply.extProbeV2ReadScript()
        val i1 = s.indexOf(HvacDigitalProps.PROP_EXT)
        val i2 = s.indexOf(HvacFlagsApply.PROP_EXT_LEGACY)
        val i3 = s.indexOf(HvacFlagsApply.PROP_EXT_THIRD)
        assertTrue("порядок чтения: exttemp → temp_ext → ext_temp", i1 in 0 until i2 && i2 < i3)
        assertEquals(3, Regex("getprop ").findAll(s).count())
    }

    @Test
    fun stepLineFormat() {
        assertEquals(
            "EXT_PROBE_V2 step=1/3 prop=persist.sys.hvacdigital.exttemp wrote=1 found=1 result=ok owner_look=wait15s",
            HvacFlagsApply.extProbeV2StepLine(1, 3, "persist.sys.hvacdigital.exttemp", "1", "1", "wait15s")
        )
        assertEquals(
            "EXT_PROBE_V2 step=2/3 prop=persist.sys.hvacdigital.temp_ext wrote=1 found=0 result=missing owner_look=wait15s",
            HvacFlagsApply.extProbeV2StepLine(2, 3, "persist.sys.hvacdigital.temp_ext", "1", "0", "wait15s")
        )
        assertTrue("пустое значение показывается как empty",
            HvacFlagsApply.extProbeV2StepLine(3, 3, "x", "1", "", "wait15s").contains("found=empty"))
    }

    @Test
    fun pauseIsFifteenSecondsAndNothingIsDeleted() {
        assertEquals(15_000L, HvacFlagsApply.EXT_PROBE_V2_PAUSE_MS)
        val s = src("src/main/java/com/example/voiceapp3/tools/HvacFlagsApply.kt")
        assertTrue("пауза между шагами", s.contains("Thread.sleep(EXT_PROBE_V2_PAUSE_MS)"))
        assertTrue("полный цикл recarga на каждом шаге", s.contains("EXT_PROBE_V2 recarga step="))
        assertTrue("в конце флаги возвращаются по префам", s.contains("EXT_PROBE_V2 props_follow_prefs="))
        // ничего не удаляем: ни unset, ни setprop в пусто
        for (n in HvacFlagsApply.EXT_NAMES) {
            assertFalse("нельзя сбрасывать имя в пусто: $n", s.contains("setprop $n \\\"\\\""))
        }
        assertFalse("свойства не удаляются", s.contains("resetprop -d"))
    }
}
