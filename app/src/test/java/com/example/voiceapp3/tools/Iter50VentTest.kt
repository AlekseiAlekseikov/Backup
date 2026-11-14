package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter50 §9.1 — безопасность владельцев окна: единая точка мутации, ABA и честный readback.
 *
 * Проверяются ЧИСТЫЕ функции, которые вызывает боевой `WindowDoors` (он теперь компилируется в
 * локальном наборе, см. `runtests.sh`).
 *
 * Чего тест НЕ доказывает: реальный VHAL/`CarPropertyManager` на ГУ, физическое закрытие окна,
 * поведение асинхронного стека при задержке readback. Тест не двигает окно и не пишет команд.
 */
class Iter50VentTest {

    // ── поздний readback: снимать запись владельцев или нет ────────────────────────────────

    @Test
    fun lateReadbackDropsRecordOnlyWhenNothingChanged() {
        assertTrue(
            "набор пуст, не менялся, поколение то же — запись можно снять",
            ventLateReadbackMayDropOwners(emptyList(), emptyList(), 7L, 7L)
        )
    }

    @Test
    fun abaIsDetectedByGenerationNotOnlyByContent() {
        // A→B→A: содержимое совпало (пусто), но поколение другое — запись ЖИВАЯ.
        assertFalse(
            "равное содержимое при другом поколении — это ABA, запись не трогаем",
            ventLateReadbackMayDropOwners(emptyList(), emptyList(), 9L, 7L)
        )
    }

    @Test
    fun newOwnerBlocksDrop() {
        assertFalse(
            "появился владелец — окно снова в работе",
            ventLateReadbackMayDropOwners(listOf(0x4), emptyList(), 7L, 7L)
        )
        assertFalse(
            "набор изменился в другую сторону",
            ventLateReadbackMayDropOwners(listOf(0x1), listOf(0x2), 7L, 7L)
        )
    }

    // ── исход закрытия: команда принята ≠ VHAL подтвердил ─────────────────────────────────

    @Test
    fun missingReadbackIsNotClosed() {
        assertEquals(
            "нет readback — это НЕ подтверждение",
            "readback_missing", ventCloseOutcome(written = true, posAfter = null, closedPos = 0, tolerance = 2)
        )
    }

    @Test
    fun rejectedWriteIsReportedSeparately() {
        assertEquals(
            "команда не принята",
            "write_rejected", ventCloseOutcome(written = false, posAfter = 0, closedPos = 0, tolerance = 2)
        )
    }

    @Test
    fun confirmedReadbackCloses() {
        assertEquals("closed", ventCloseOutcome(true, 0, 0, 2))
        assertEquals("в пределах допуска", "closed", ventCloseOutcome(true, 1, 0, 2))
    }

    @Test
    fun staleValueIsLateReadback() {
        assertEquals(
            "значение ещё не обновилось — «не подтверждено», а не «не закрылось»",
            "late_readback", ventCloseOutcome(true, 40, 0, 2)
        )
    }
}
