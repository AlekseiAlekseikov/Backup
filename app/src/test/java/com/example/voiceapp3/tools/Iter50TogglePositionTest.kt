package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * iter50 §6.3 — положение тумблера при неизвестном состоянии.
 *
 * Дефект: UI брал `raw ?: def`, а backend при неизвестности кладёт в `key` именно `default`,
 * поэтому при конфликте копий CPS/DPS тумблер вставал в default и это выглядело как
 * сохранённый выбор владельца. Здесь проверяется чистая функция, которой пользуется [ToolsPanel].
 */
class Iter50TogglePositionTest {

    @Test
    fun confirmedStatesAreFacts() {
        assertEquals(true, Toggles45.switchPosition("on", null, false))
        assertEquals(false, Toggles45.switchPosition("off", true, true))
    }

    @Test
    fun unknownUsesLastKnownNotDefault() {
        // Последнее подтверждённое было ON, сейчас неизвестно → показываем ON, а не default=false.
        assertEquals(true, Toggles45.switchPosition("unknown", true, false))
        assertEquals(false, Toggles45.switchPosition("unknown", false, true))
    }

    @Test
    fun withoutAnyKnowledgeDefaultIsOnlyAPlaceholder() {
        assertEquals(false, Toggles45.switchPosition("unknown", null, false))
        assertEquals(true, Toggles45.switchPosition("unknown", null, true))
        assertEquals(false, Toggles45.switchPosition(null, null, false))
    }

    @Test
    fun garbageIsTreatedAsUnknown() {
        assertEquals(true, Toggles45.switchPosition("maybe", true, false))
        assertEquals(false, Toggles45.switchPosition("", null, false))
    }

    @Test
    fun onlyConfirmedStatesBecomeLastKnown() {
        assertEquals(true, Toggles45.knownPosition("on"))
        assertEquals(false, Toggles45.knownPosition("OFF"))
        assertNull("unknown не должен затирать последнее известное", Toggles45.knownPosition("unknown"))
        assertNull(Toggles45.knownPosition(null))
        assertNull(Toggles45.knownPosition("maybe"))
    }
}
