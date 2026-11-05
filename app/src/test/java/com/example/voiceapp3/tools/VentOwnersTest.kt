package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33h C1 — закрытие двери закрывает ТОЛЬКО своё окно и только если окно наше.
 * Три обязательных теста на `ventOwnersAfterClose` (чистая функция из WindowDoors.kt).
 */
class VentOwnersTest {

    private val doorToWindow = mapOf(0x1 to 0x11, 0x4 to 0x14)

    // 1. Своё окно: владельцем была только закрывшаяся дверь ⇒ владельцев не осталось,
    //    окно закрывается (иначе в 1.0.53 окно оставалось открытым из-за карты DOOR_TO_WINDOW).
    @Test
    fun ownWindowLosesLastOwner() {
        val after = com.example.voiceapp3.tools.ventOwnersAfterClose(
            door = 0x1, window = 0x11,
            owners = setOf(0x1),
            doorStates = mapOf(0x1 to false, 0x4 to true),
            doorToWindow = doorToWindow,
            legacy = false,
        )
        assertTrue("после закрытия своей двери владельцев быть не должно: $after", after.isEmpty())
    }

    // 2. Окно делят две двери: закрытие одной НЕ закрывает окно.
    @Test
    fun sharedWindowKeepsOwner() {
        val after = com.example.voiceapp3.tools.ventOwnersAfterClose(
            door = 0x1, window = 0x11,
            owners = setOf(0x1, 0x4),
            doorStates = mapOf(0x1 to false, 0x4 to true),
            doorToWindow = doorToWindow,
            legacy = false,
        )
        assertEquals(setOf(0x4), after)
    }

    // 3. legacy-окно: прежняя синхронизация по DOOR_TO_WINDOW сохраняется — открытая
    //    «парная» дверь становится владельцем.
    @Test
    fun legacyKeepsDoorToWindowSync() {
        // Для legacy-окна карта DOOR_TO_WINDOW связывает с ним ОБЕ двери (0x1 и 0x4) —
        // именно этот случай прежняя синхронизация держала открытым.
        val legacyMap = mapOf(0x1 to 0x11, 0x4 to 0x11)
        val legacy = com.example.voiceapp3.tools.ventOwnersAfterClose(
            door = 0x1, window = 0x11,
            owners = setOf(0x1),
            doorStates = mapOf(0x1 to false, 0x4 to true),
            doorToWindow = legacyMap,
            legacy = true,
        )
        assertEquals(setOf(0x4), legacy)
        val modern = com.example.voiceapp3.tools.ventOwnersAfterClose(
            door = 0x1, window = 0x11,
            owners = setOf(0x1),
            doorStates = mapOf(0x1 to false, 0x4 to true),
            doorToWindow = legacyMap,
            legacy = false,
        )
        assertFalse("не-legacy окно не должно получать владельца из карты", modern.contains(0x4))
    }
}
