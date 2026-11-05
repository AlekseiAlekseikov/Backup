package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * iter33h F1/F2 — наружная температура: нормализация ровно как в референсе
 * (`TempStepperService.i(int)`: int −550…700 ⇒ /10, иначе −45…65 как есть; диапазон −55…70)
 * и единый формат `%.1f`. Значение float из VHAL-дампа — 25.0 (`0x11600703`).
 */
class TempFormatTest {

    // 1. Формат: одна цифра после запятой, и честное «н/д» вместо подстановки.
    @Test
    fun formatOneDecimal() {
        assertEquals("25.0", StatusIcons.formatOutsideTempC(25.0f))
        assertEquals("9.5", StatusIcons.formatOutsideTempC(9.46f))
        assertEquals("-3.0", StatusIcons.formatOutsideTempC(-3.0f))
        assertEquals("н/д", StatusIcons.formatOutsideTempC(null))
    }

    // 2. Float — как есть (в пределах −55…70), int — по правилам референса.
    @Test
    fun normalizeLikeReference() {
        assertEquals(25.0f, StatusIcons.envOutsideToCelsius(25.0f, null)!!, 0.001f)
        assertEquals(9.5f, StatusIcons.envOutsideToCelsius(null, 95)!!, 0.001f)   // 95 при факте ~9.5 °C
        assertEquals(5.5f, StatusIcons.envOutsideToCelsius(null, 55)!!, 0.001f)   // −550…700 ⇒ /10
        assertEquals(-3.0f, StatusIcons.envOutsideToCelsius(null, -3)!!, 0.001f)  // −45…65 как есть
    }

    // 3. Мусор/вне диапазона — значения нет (никаких подстановок из ambient).
    @Test
    fun rejectsOutOfRange() {
        assertNull(StatusIcons.envOutsideToCelsius(99.0f, null))
        assertNull(StatusIcons.envOutsideToCelsius(null, 5000))
        assertNull(StatusIcons.envOutsideToCelsius(null, 200))
        assertNull(StatusIcons.envOutsideToCelsius(Float.NaN, null))
        assertNull(StatusIcons.envOutsideToCelsius(null, null))
    }
}
