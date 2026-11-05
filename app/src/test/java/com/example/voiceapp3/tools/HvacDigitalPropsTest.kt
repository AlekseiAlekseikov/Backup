package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33i P0-a — «состояние → строка setprop».
 *
 * Фиксирует контракт референса (`w0/x7.java:137-147`): в `persist.sys.hvacdigital.*` уходят
 * ФЛАГИ `0|1`, порядок `temp → batt → exttemp`, хвост `&& echo FLAGS_OK`.
 * Значения (°C / %) в эти свойства попадать не должны — именно они в 1.0.53 ложились в слот
 * климата поверх стандартной полоски.
 */
class HvacDigitalPropsTest {

    @Test
    fun allStatesMappingToFlags() {
        val cases = listOf(
            // tempOn, battOn, extOn, ожидаемая строка
            Triple(false, false, false) to
                "setprop persist.sys.hvacdigital.temp 0 && setprop persist.sys.hvacdigital.batt 0 && " +
                "setprop persist.sys.hvacdigital.exttemp 0 && echo FLAGS_OK",
            Triple(true, false, false) to
                "setprop persist.sys.hvacdigital.temp 1 && setprop persist.sys.hvacdigital.batt 0 && " +
                "setprop persist.sys.hvacdigital.exttemp 0 && echo FLAGS_OK",
            Triple(false, true, true) to
                "setprop persist.sys.hvacdigital.temp 0 && setprop persist.sys.hvacdigital.batt 1 && " +
                "setprop persist.sys.hvacdigital.exttemp 1 && echo FLAGS_OK",
            Triple(true, true, true) to
                "setprop persist.sys.hvacdigital.temp 1 && setprop persist.sys.hvacdigital.batt 1 && " +
                "setprop persist.sys.hvacdigital.exttemp 1 && echo FLAGS_OK",
        )
        for ((state, expected) in cases) {
            val actual = HvacDigitalProps.flagsCommand(state.first, state.second, state.third)
            assertEquals("state=${HvacDigitalProps.flagsFingerprint(state.first, state.second, state.third)}",
                expected, actual)
        }
    }

    @Test
    fun noTemperatureValueEverWritten() {
        // «Мусорные» значения, которые приложение писало раньше: уставка 9.5, батарея 71.
        val cmd = HvacDigitalProps.flagsCommand(true, true, true)
        assertFalse("в строке не должно быть значений уставки", cmd.contains("9.5"))
        assertFalse("в строке не должно быть значения батареи", cmd.contains("71"))
        // Каждый setprop оканчивается ровно одним символом 0|1.
        val ops = Regex("setprop persist\\.sys\\.hvacdigital\\.([a-z]+) (\\S+?) &&|setprop persist\\.sys\\.hvacdigital\\.([a-z]+) (\\S+?) && echo")
        val values = Regex("setprop persist\\.sys\\.hvacdigital\\.[a-z]+ ([^ ]+)").findAll(cmd).map { it.groupValues[1] }.toList()
        assertEquals(listOf("1", "1", "1"), values)
        assertTrue("все значения — флаги", values.all { HvacDigitalProps.isFlagValue(it) })
    }

    @Test
    fun fingerprintIsStableAndFlagBased() {
        assertEquals("temp=1 batt=0 ext=1",
            HvacDigitalProps.flagsFingerprint(true, false, true))
        assertEquals("temp=0 batt=0 ext=0",
            HvacDigitalProps.flagsFingerprint(false, false, false))
        // dirty-флаг не должен «схлопывать» разные состояния.
        assertTrue(HvacDigitalProps.flagsFingerprint(true, false, false) !=
            HvacDigitalProps.flagsFingerprint(false, true, false))
    }

    @Test
    fun isFlagValueRejectsNumbers() {
        assertTrue(HvacDigitalProps.isFlagValue("0"))
        assertTrue(HvacDigitalProps.isFlagValue("1"))
        for (bad in listOf("9.5", "71", "0.0", "", "true", "app", "bind", "Домой")) {
            assertFalse("не флаг: '$bad'", HvacDigitalProps.isFlagValue(bad))
        }
    }

    @Test
    fun viaIsNeverWrittenByApp() {
        // P0-a.3: `persist.sys.hvacdigital.via` — prop установщика плагина (bind|install, w0/o.java:73).
        assertEquals("persist.sys.hvacdigital.via", HvacDigitalProps.PROP_VIA)
        val cmd = HvacDigitalProps.flagsCommand(true, true, true)
        assertFalse("маршрут/`via` в строку setprop не попадает", cmd.contains(HvacDigitalProps.PROP_VIA))
        assertFalse(cmd.contains("Домой"))
    }
}
