package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter45 §4/§5/§6/§7/§10 — правила, на которых держатся исправления.
 *
 * Уровень: JVM-модель (production-функции [Toggles45], которые вызывают NetworkTtl, LockSound,
 * JitOp, ToolsPanel и AppFileLogger). Android-интеграция и ГУ здесь не проверяются.
 */
class Toggles45Test {

    // ───────────────────────── §5/§6: намерение ≠ применение ─────────────────────────

    @Test
    fun failedApplyKeepsIntentAndReportsPending() {
        // Владелец включил TTL, правило не встало (нет uplink): намерение остаётся ON.
        assertEquals("pending", Toggles45.appliedOutcome(want = true, applied = false))
    }

    @Test
    fun successfulApplyIsAppliedOnlyWhenIntentOn() {
        assertEquals("applied", Toggles45.appliedOutcome(want = true, applied = true))
        // Выключение подтверждено — «off», а не «pending».
        assertEquals("off", Toggles45.appliedOutcome(want = false, applied = true))
    }

    @Test
    fun disablingThatFailedIsPendingNotOff() {
        // Владелец выключил, но снять правило не удалось: нельзя писать «off».
        assertEquals("pending", Toggles45.appliedOutcome(want = false, applied = false))
    }

    @Test
    fun pendingIsNeverTreatedAsOff() {
        assertTrue(Toggles45.appliedOutcome(true, false) != "off")
    }

    // ───────────────────────── §7.2: окно жизни операции ─────────────────────────




    // ───────────────────────── §4: значения тумблеров ─────────────────────────

    @Test
    fun stringFalseIsOffNotOn() {
        assertEquals(false, Toggles45.boolOrNull("false"))
        assertEquals(false, Toggles45.boolOrNull("off"))
        assertEquals(false, Toggles45.boolOrNull("0"))
        // iter62 §B1: пустая строка — это ОТСУТСТВИЕ значения, а не «выключено». Прежнее
        // ожидание (`false`) и было причиной жалобы «работает, а визуально OFF».
        assertNull(Toggles45.boolOrNull(""))
    }

    @Test
    fun truthyStringsAreOn() {
        assertEquals(true, Toggles45.boolOrNull("true"))
        assertEquals(true, Toggles45.boolOrNull("1"))
        assertEquals(true, Toggles45.boolOrNull(" YES "))
    }

    @Test
    fun unparsableValueIsUnknownNotOff() {
        assertNull("непонятное значение нельзя показывать как выключено",
            Toggles45.boolOrNull("pending"))
        assertNull(Toggles45.boolOrNull(null))
    }

    // ───────────────────────── §4: порядок снимков состояния ─────────────────────────

    @Test
    fun olderSnapshotDoesNotRollbackNewer() {
        assertFalse(Toggles45.acceptsSeq(5, 7))
        assertFalse(Toggles45.acceptsSeq(7, 7))
        assertTrue(Toggles45.acceptsSeq(8, 7))
    }

    @Test
    fun snapshotWithoutSeqIsAccepted() {
        assertTrue(Toggles45.acceptsSeq(null, 42))
    }

    // ───────────────────────── §10: повреждённый хвост файла ─────────────────────────

    @Test
    fun trailingNulRunIsCounted() {
        val ok = "line1\nline2\n".toByteArray()
        assertEquals(0, Toggles45.trailingNulBytes(ok))
        val bad = "line1\n".toByteArray() + ByteArray(3296)
        assertEquals(3296, Toggles45.trailingNulBytes(bad))
        assertEquals(0, Toggles45.trailingNulBytes(ByteArray(0)))
        assertEquals(4, Toggles45.trailingNulBytes(ByteArray(4)))
    }

    @Test
    fun nulInsideLineIsNotTreatedAsTailDamage() {
        val mixed = "a\u0000b\n".toByteArray(Charsets.UTF_8)
        assertEquals(0, Toggles45.trailingNulBytes(mixed))
    }
}
