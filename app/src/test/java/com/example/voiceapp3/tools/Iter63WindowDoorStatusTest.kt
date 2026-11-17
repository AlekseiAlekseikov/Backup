package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter63 §3.4 — `setWindowDriverOnDoor` / `setWindowOppositeOnDoor` не рапортуют `OK` без факта.
 *
 * Дефект iter62: обе функции писали `Prefs2.putBoolean` и возвращали `ToolsStep.Status.OK`
 * безусловно. Владелец видел «OK», хотя:
 *  • настройка могла не сохраниться (readback ≠ желаемого) — это доказанная неудача;
 *  • CarService мог быть не подключён — тогда включённая настройка не опустит стекло при
 *    открытии двери, т.е. эффекта нет. Это не «OK» и не «провал железа», а BLOCKED с причиной.
 *
 * Проверяется production-правило [windowToggleStatus] — ровно то, по которому строится
 * `ToolsStep.status` в обеих функциях (см. их вызовы в `WindowDoors.kt`).
 */
class Iter63WindowDoorStatusTest {

    @Test
    fun savedButCarServiceMissingIsBlockedNotOk() {
        val (status, reason) = windowToggleStatus(enabled = true, readback = true, carServiceReady = false)
        assertEquals("«сохранено» не равно «работает»", ToolsStep.Status.BLOCKED, status)
        assertEquals("причина названа", "car_service_unavailable", reason)
    }

    @Test
    fun savedAndCarServiceReadyOnIsOk() {
        val (status, reason) = windowToggleStatus(enabled = true, readback = true, carServiceReady = true)
        assertEquals(ToolsStep.Status.OK, status)
        assertTrue("при успехе причина пуста", reason.isEmpty())
    }

    @Test
    fun readbackMismatchIsFailedNotOk() {
        val (status, reason) = windowToggleStatus(enabled = true, readback = false, carServiceReady = true)
        assertEquals("настройка не сохранилась — это FAILED", ToolsStep.Status.FAILED, status)
        assertEquals("readback_mismatch", reason)
    }

    @Test
    fun turningOffIsOkWithoutCarServiceBecauseNothingToApplyNow() {
        val (status, reason) = windowToggleStatus(enabled = false, readback = false, carServiceReady = false)
        assertEquals("снятие настройки не требует CarService", ToolsStep.Status.OK, status)
        assertTrue(reason.isEmpty())
    }

    @Test
    fun offButReadbackSaysStillOnIsFailed() {
        val (status, reason) = windowToggleStatus(enabled = false, readback = true, carServiceReady = true)
        assertEquals(ToolsStep.Status.FAILED, status)
        assertEquals("readback_mismatch", reason)
    }

    @Test
    fun everyOutcomeNamesItsReason() {
        // Ни один исход не «молчит»: у BLOCKED/FAILED причина непустая, у OK — пустая.
        listOf(
            windowToggleStatus(true, true, false),
            windowToggleStatus(true, false, true),
            windowToggleStatus(false, true, true),
            windowToggleStatus(true, true, true),
            windowToggleStatus(false, false, false)
        ).forEach { (status, reason) ->
            if (status == ToolsStep.Status.OK) assertTrue("OK без причины: $status", reason.isEmpty())
            else assertTrue("исход $status без причины", reason.isNotEmpty())
        }
    }
}
