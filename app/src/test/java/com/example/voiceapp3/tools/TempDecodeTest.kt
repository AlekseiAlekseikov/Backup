package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33j P1 — decode наружной температуры по эталону владельца
 * (`icar-dev/geely_ex2_tools/TemperatureReader.kt`, `decodeFlymeTemp`):
 *
 *     celsius = (raw − 80) / 2      // AC_AMBIENT_TEMP 0x2140A377, int, area 0
 *
 * Контрольные числа — из мандата: `86 → 3.0`, `95 → 7.5`, `93 → 6.5`, `0 → −40.0`.
 * Плюс правило «ENV_OUTSIDE — только fallback»: при живом ambient он не используется.
 */
class TempDecodeTest {

    @Test
    fun ownerReferenceNumbers() {
        assertEquals(3.0f, decodeFlymeTemp(86)!!, 0.001f)
        assertEquals(7.5f, decodeFlymeTemp(95)!!, 0.001f)
        assertEquals(6.5f, decodeFlymeTemp(93)!!, 0.001f)
        assertEquals(-40.0f, decodeFlymeTemp(0)!!, 0.001f)
    }

    @Test
    fun nullRawGivesNull() {
        assertNull(decodeFlymeTemp(null))
    }

    @Test
    fun plausibleRangeMatchesReference() {
        assertTrue(plausibleOutsideC(3.0f))
        assertTrue(plausibleOutsideC(-40.0f))
        assertFalse(plausibleOutsideC(25.0f * 30))   // 750 °C — мусор
        assertFalse(plausibleOutsideC(null))
    }

    @Test
    fun decodeOfGarbageIsNotPlausible() {
        // VHAL-мусор не должен попасть в UI: 300 → 110 °C, 0 → −40 (валидно, но это «нет данных»).
        assertFalse(plausibleOutsideC(decodeFlymeTemp(300)))
        assertTrue(plausibleOutsideC(decodeFlymeTemp(0)))
    }

    @Test
    fun fallbackConstantIsEnvOutside() {
        // 0x11600703 — ENV_OUTSIDE_TEMPERATURE остаётся в коде только как fallback.
        val src = java.io.File("src/main/java/com/example/voiceapp3/tools/StatusIcons.kt")
        assertTrue("StatusIcons.kt не найден: ${src.absolutePath}", src.isFile)
        val text = src.readText()
        assertTrue("есть строка TEMP_DECODE по эталону", text.contains("formula (raw-80)/2"))
        assertTrue("ambient — основной источник", text.contains("source=AC_AMBIENT_TEMP"))
        assertTrue("факт игнора ENV_OUTSIDE фиксируется в логе",
            text.contains("ENV_OUTSIDE_TEMPERATURE ignored:"))
        assertTrue("fallback подписан явно", text.contains("fallback ENV_OUTSIDE_TEMPERATURE"))
        assertFalse("старого порта зон {0,1,4,7} больше нет", text.contains("ENV_OUTSIDE_AREAS"))
    }
}
