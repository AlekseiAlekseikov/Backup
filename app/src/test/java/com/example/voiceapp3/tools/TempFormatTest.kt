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
        assertEquals("25.0", formatOutsideTempC(25.0f))
        assertEquals("9.5", formatOutsideTempC(9.46f))
        assertEquals("-3.0", formatOutsideTempC(-3.0f))
        assertEquals("н/д", formatOutsideTempC(null))
    }

    // 2. Float — как есть (в пределах −55…70), int — по правилам референса.
    @Test
    fun normalizeLikeReference() {
        assertEquals(25.0f, envOutsideToCelsius(25.0f, null)!!, 0.001f)
        assertEquals(9.5f, envOutsideToCelsius(null, 95)!!, 0.001f)   // 95 при факте ~9.5 °C
        assertEquals(5.5f, envOutsideToCelsius(null, 55)!!, 0.001f)   // −550…700 ⇒ /10
        assertEquals(-3.0f, envOutsideToCelsius(null, -30)!!, 0.001f)  // −550…700 ⇒ /10
        assertEquals(20.0f, envOutsideToCelsius(null, 200)!!, 0.001f)  // 200 ⇒ 20.0 °C — валидно
    }

    // 3. Мусор/вне диапазона — значения нет (никаких подстановок из ambient).
    @Test
    fun rejectsOutOfRange() {
        assertNull(envOutsideToCelsius(99.0f, null))
        assertNull(envOutsideToCelsius(null, 5000))
        assertNull(envOutsideToCelsius(null, 800))   // 800: ни −550…700, ни −45…65
        assertNull(envOutsideToCelsius(null, -600))  // −600: ниже нижней границы обеих ветвей
        assertNull(envOutsideToCelsius(Float.NaN, null))
        assertNull(envOutsideToCelsius(null, null))
    }
}
