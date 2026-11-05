package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33d A4 — регресс-тесты опознания загрузки для метки `jit_boot_attempt`.
 *
 * Главный тест — [differentBootDoesNotBlock]: именно он ловит дефект третьей пересборки,
 * где `prev in 1..up` выдавало метку прошлой загрузки за попытку в текущей.
 */
class JitBootMarkTest {

    // 1. Регресс-тест главного дефекта: разные загрузки НЕ блокируют попытку.
    @Test
    fun differentBootDoesNotBlock() {
        assertFalse(JitBootMark.sameLoad("boot-A:40000", "boot-B"))
        // «Было» при этом же входе: prev=40000 в диапазоне 1..up=55000 ⇒ SKIP. «Стало»: попытка разрешена.
        assertFalse(JitBootMark.sameLoad(JitBootMark.format("boot-A", 40_000), "boot-B", ))
    }

    // 2. Та же загрузка — попытка уже была.
    @Test
    fun sameBootBlocks() {
        assertTrue(JitBootMark.sameLoad("boot-A:40000", "boot-A"))
        assertTrue(JitBootMark.sameLoad(JitBootMark.format("boot-A", 40_000), "boot-A"))
    }

    // 3. Та же загрузка и БОЛЬШИЙ uptime — всё равно «попытка была» (второй проход через 30 с).
    @Test
    fun sameBootHigherUptimeStillBlocks() {
        assertTrue(JitBootMark.sameLoad("boot-A:40000", "boot-A"))
        assertEquals(40_000L, JitBootMark.uptimeOf("boot-A:40000"))
    }

    // 4. Старый формат (число без `:`) — консервативно «эта загрузка», плюс признак legacy.
    @Test
    fun legacyNumericIsSameLoad() {
        assertTrue(JitBootMark.sameLoad("40000", "boot-any"))
        assertTrue(JitBootMark.isLegacy("40000"))
        assertFalse(JitBootMark.isLegacy("boot-A:40000"))
        assertEquals(40_000L, JitBootMark.uptimeOf("40000"))
    }

    // 5. Пустое/мусорное — не падает, результат ОПРЕДЕЛЁН (описан в отчёте):
    //    нет тега или он не разбирается ⇒ «попытки не было» (false, попытка разрешена);
    //    тег разобрался и совпал с текущим ⇒ true, даже если uptime в метке мусорный —
    //    для опознания загрузки uptime не нужен, он только для диагностики.
    @Test
    fun emptyOrGarbageIsNotSameLoad() {
        for (m in listOf("", "   ", ":", ":40000", "abc", "boot-A", "::")) {
            assertFalse("метка '$m'", JitBootMark.sameLoad(m, "boot-A"))
        }
        assertFalse(JitBootMark.sameLoad("boot-A:1", ""))          // нет текущего тега — опознать нельзя
        assertFalse(JitBootMark.isLegacy("abc"))
        assertFalse(JitBootMark.isLegacy("boot-A:"))
        // Тег совпал — это та же загрузка, чем бы ни был хвост после ':'.
        assertTrue(JitBootMark.sameLoad("boot-A:", "boot-A"))
        assertTrue(JitBootMark.sameLoad("boot-A:not-a-number", "boot-A"))
        assertEquals(-1L, JitBootMark.uptimeOf("boot-A:"))
    }

    // 6. epoch-теги: допуск ±60 с.
    @Test
    fun epochTolerance() {
        val bootEpoch = 1_700_000_000_000L
        // Подвод часов на 30 с — всё ещё та же загрузка.
        assertTrue(JitBootMark.sameLoad("${bootEpoch}:40000", "${bootEpoch + 30_000}"))
        assertTrue(JitBootMark.sameLoad("${bootEpoch}:40000", "${bootEpoch - 30_000}"))
        // Разница 5 минут — уже другая загрузка.
        assertFalse(JitBootMark.sameLoad("${bootEpoch}:40000", "${bootEpoch + 300_000}"))
        // Допуск можно сузить явно.
        assertFalse(JitBootMark.sameLoad("${bootEpoch}:40000", "${bootEpoch + 30_000}", toleranceMs = 1_000L))
        // Числовой тег против boot_id-тега — разные пространства имён ⇒ другая загрузка.
        assertFalse(JitBootMark.sameLoad("boot-A:40000", "$bootEpoch"))
    }
}
