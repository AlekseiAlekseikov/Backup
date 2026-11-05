package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33d A4 / iter33e A6+B — регресс-тесты опознания загрузки и намерения сверки.
 *
 * Главный тест — [differentBootDoesNotBlock]: он ловит дефект третьей пересборки,
 * где «метка не больше uptime» выдавалось за попытку в текущей загрузке.
 * Тесты epoch-допуска гоняют ТОЛЬКО прод-функции ([JitBootMark.epochTag], [JitBootMark.format]) —
 * голых чисел в тестах нет, иначе проверялось бы не то, что производит `currentBootTag()`.
 */
class JitBootMarkTest {

    // 1. Регресс-тест главного дефекта: разные загрузки НЕ блокируют попытку.
    @Test
    fun differentBootDoesNotBlock() {
        assertFalse(JitBootMark.sameLoad("boot-A:40000", "boot-B"))
        assertFalse(JitBootMark.sameLoad(JitBootMark.format("boot-A", 40_000), "boot-B"))
    }

    // 2. Та же загрузка — попытка уже была.
    @Test
    fun sameBootBlocks() {
        assertTrue(JitBootMark.sameLoad("boot-A:40000", "boot-A"))
        assertTrue(JitBootMark.sameLoad(JitBootMark.format("boot-A", 40_000), "boot-A"))
    }

    // 3. iter33e C2: опознание НЕ зависит от uptime — проверяем оба направления:
    //    метка с меньшим uptime (10 с) при текущем 55 с и наоборот.
    @Test
    fun sameBootIgnoresUptime() {
        assertTrue(JitBootMark.sameLoad(JitBootMark.format("boot-A", 10_000), "boot-A"))   // меньше
        assertTrue(JitBootMark.sameLoad(JitBootMark.format("boot-A", 55_000), "boot-A"))   // больше
        assertTrue(JitBootMark.sameLoad(JitBootMark.format("boot-A", 10_000), "boot-A"))
        assertEquals(10_000L, JitBootMark.uptimeOf(JitBootMark.format("boot-A", 10_000)))
    }

    // 4. Старый формат (число без `:`) — консервативно «эта загрузка», плюс признак legacy.
    @Test
    fun legacyNumericIsSameLoad() {
        assertTrue(JitBootMark.sameLoad("40000", "boot-any"))
        assertTrue(JitBootMark.isLegacy("40000"))
        assertFalse(JitBootMark.isLegacy("boot-A:40000"))
        assertEquals(40_000L, JitBootMark.uptimeOf("40000"))
    }

    // 5. Пустое/мусорное — не падает, результат определён (см. «Честные пробелы» в отчёте).
    @Test
    fun emptyOrGarbageIsNotSameLoad() {
        for (m in listOf("", "   ", ":", ":40000", "abc", "boot-A", "::")) {
            assertFalse("метка '$m'", JitBootMark.sameLoad(m, "boot-A"))
        }
        assertFalse(JitBootMark.sameLoad("boot-A:1", ""))
        assertFalse(JitBootMark.isLegacy("abc"))
        assertFalse(JitBootMark.isLegacy("boot-A:"))
        assertTrue(JitBootMark.sameLoad("boot-A:", "boot-A"))
        assertTrue(JitBootMark.sameLoad("boot-A:not-a-number", "boot-A"))
        assertEquals(-1L, JitBootMark.uptimeOf("boot-A:"))
    }

    // 6. iter33e B: epoch-допуск проверяется ПРОД-функциями (epochTag + format), не голыми числами.
    @Test
    fun epochTolerance() {
        // t — в миллисекундах, как `System.currentTimeMillis() - elapsedRealtime()` в проде.
        val t = 1_700_000_000_000L
        // ±30 с — та же загрузка (подвод часов/NTP не ломает опознание).
        assertTrue(JitBootMark.sameLoad(JitBootMark.format(JitBootMark.epochTag(t), 40_000),
            JitBootMark.epochTag(t + 30_000)))
        assertTrue(JitBootMark.sameLoad(JitBootMark.format(JitBootMark.epochTag(t), 40_000),
            JitBootMark.epochTag(t - 30_000)))
        // 5 минут — уже другая загрузка (300 000 мс = 300 с > допуска 60 с).
        assertFalse(JitBootMark.sameLoad(JitBootMark.format(JitBootMark.epochTag(t), 40_000),
            JitBootMark.epochTag(t + 300_000)))
        assertFalse(JitBootMark.sameLoad(JitBootMark.format(JitBootMark.epochTag(t), 40_000),
            JitBootMark.epochTag(t + 90_000)))
        // boot_id-тег и epoch-тег — разные пространства имён.
        assertFalse(JitBootMark.sameLoad(JitBootMark.format(JitBootMark.epochTag(t), 40_000), "boot-A"))
        // Допуск можно сузить явно.
        assertFalse(JitBootMark.sameLoad(JitBootMark.format(JitBootMark.epochTag(t), 40_000),
            JitBootMark.epochTag(t + 30_000), toleranceMs = 1_000L))
    }

    // 6b. iter33e B: формат epoch-тега — прод-функция, префикс снимается и значение парсится.
    @Test
    fun epochTagFormatIsProductionLike() {
        val tag = JitBootMark.epochTag(1_700_000_000_000L)
        assertTrue(tag.startsWith(JitBootMark.EPOCH_PREFIX))
        assertEquals("1700000000", JitBootMark.stripEpochPrefix(tag))
        assertTrue(JitBootMark.stripEpochPrefix(tag).toLongOrNull() != null)
        // boot_id-тег префикса не имеет и не портится.
        assertEquals("boot-A", JitBootMark.stripEpochPrefix("boot-A"))
        // Старая метка `epoch…:40000` этой итерации тоже распознаётся.
        assertTrue(JitBootMark.sameLoad(JitBootMark.format(tag, 40_000), tag))
    }

    // 7. iter33e A6: классификация намерения сверки после перезапуска zygote.
    @Test
    fun pendingDecisionNoneWhenEmpty() {
        assertEquals(JitBootMark.PendingDecision.NONE, JitBootMark.pendingDecision("", "boot-A"))
        assertEquals(JitBootMark.PendingDecision.NONE, JitBootMark.pendingDecision("   ", "boot-A"))
    }

    @Test
    fun pendingDecisionStaleForOtherBoot() {
        val other = JitBootMark.format("boot-OLD", 40_000)
        assertEquals(JitBootMark.PendingDecision.STALE, JitBootMark.pendingDecision(other, "boot-NEW"))
        // epoch-намерение прошлой загрузки (разница 5 минут) тоже STALE.
        assertEquals(JitBootMark.PendingDecision.STALE,
            JitBootMark.pendingDecision(JitBootMark.format(JitBootMark.epochTag(1_700_000_000_000L), 40_000),
                JitBootMark.epochTag(1_700_000_300_000L)))
    }

    @Test
    fun pendingDecisionVerifyForSameBoot() {
        assertEquals(JitBootMark.PendingDecision.VERIFY,
            JitBootMark.pendingDecision(JitBootMark.format("boot-A", 40_000), "boot-A"))
        assertEquals(JitBootMark.PendingDecision.VERIFY,
            JitBootMark.pendingDecision(JitBootMark.format(JitBootMark.epochTag(1_700_000_000_000L), 40_000),
                JitBootMark.epochTag(1_700_000_030_000L)))
        // legacy-намерение (число без ':') — консервативно VERIFY.
        assertEquals(JitBootMark.PendingDecision.VERIFY, JitBootMark.pendingDecision("40000", "boot-A"))
    }

    // 9. iter33f A3/B1: три исхода сверки после рестарта zygote.
    @Test
    fun verifyOutcomeOk() {
        assertEquals(JitBootMark.VerifyOutcome.OK,
            JitBootMark.verifyOutcome("true", "true"))
        // persist роли не играет: важен только runtime.
        assertEquals(JitBootMark.VerifyOutcome.OK,
            JitBootMark.verifyOutcome("true", ""))
    }

    @Test
    fun verifyOutcomeFailed() {
        // Ответ канала определённый: свойство прочитано, но JIT не поднят.
        assertEquals(JitBootMark.VerifyOutcome.FAILED,
            JitBootMark.verifyOutcome("false", "true"))
        assertEquals(JitBootMark.VerifyOutcome.FAILED,
            JitBootMark.verifyOutcome("unknown", "true"))
    }

    @Test
    fun verifyOutcomeUnknownWhenEmpty() {
        // Канал не ответил — это НЕ FAILED: намерение сжигать нельзя.
        assertEquals(JitBootMark.VerifyOutcome.UNKNOWN,
            JitBootMark.verifyOutcome("", ""))
        // БЫЛО (до iter33g, ошибочное ожидание):
        //   assertEquals(JitBootMark.VerifyOutcome.UNKNOWN, JitBootMark.verifyOutcome("", "true"))
        // СТАЛО: persist пришёл из того же ответа, значит канал ответил — это честный FAILED
        // (маршрут 3), а не «канала нет». Тест изменён вместе с правилом, а не подогнан под него:
        // именно эта строка и была дефектом `verifyOutcome`.
        assertEquals(JitBootMark.VerifyOutcome.FAILED,
            JitBootMark.verifyOutcome("", "true"))
    }

    // 10. iter33g A1: пустой runtime при непустом persist — определённый отказ, не «канал молчит».
    @Test
    fun verifyOutcomeEmptyRuntimeWithPersistIsFailed() {
        assertEquals(JitBootMark.VerifyOutcome.FAILED,
            JitBootMark.verifyOutcome("", "1"))
        assertEquals(JitBootMark.VerifyOutcome.FAILED,
            JitBootMark.verifyOutcome("", "false"))
    }

    // 11. iter33g A2: у двух видов FAILED разные `reason`.
    @Test
    fun verifyOutcomeFailedKeepsRuntimeReason() {
        val notTrue = JitBootMark.VerifyOutcome.FAILED
        assertEquals("runtime_not_true", JitBootMark.outcomeReason(notTrue, "false", "true"))
        assertEquals("runtime_empty", JitBootMark.outcomeReason(notTrue, "", "true"))
        assertEquals("no_channel", JitBootMark.outcomeReason(JitBootMark.VerifyOutcome.UNKNOWN, "", ""))
        assertEquals("", JitBootMark.outcomeReason(JitBootMark.VerifyOutcome.OK, "true", "true"))
    }

    // 12. iter33g B1/B2: счётчик цикла блокирует повтор в ТОЙ ЖЕ загрузке и не мешает новой.
    @Test
    fun cycleDecisionSkipsOnlySameBoot() {
        val t = 1_700_000_000_000L
        // Файла ещё нет — цикл разрешён.
        assertEquals(JitBootMark.CycleDecision.RUN, JitBootMark.cycleDecision("", JitBootMark.epochTag(t)))
        // Тот же bootTag — цикл в этой загрузке уже был.
        assertEquals(JitBootMark.CycleDecision.SKIP,
            JitBootMark.cycleDecision(JitBootMark.epochTag(t), JitBootMark.epochTag(t)))
        // Другая загрузка (через 5 минут) — счётчик не блокирует.
        assertEquals(JitBootMark.CycleDecision.RUN,
            JitBootMark.cycleDecision(JitBootMark.epochTag(t), JitBootMark.epochTag(t + 300_000)))
    }
}
