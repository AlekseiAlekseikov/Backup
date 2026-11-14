package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter51 §3.2/§3.3 — доказательство «применено» ПО КАЖДОЙ цели и журнал операции до side effect.
 *
 * Дефект на HEAD: `LockSound` требовал лишь «есть хотя бы один `MOUNTED_OK`» и сравнивал число
 * фактов (`MOUNT_FACT` печатается и для `unverified`/`unknown`) с числом целей. Две цели, из
 * которых одна не проверена, давали `applied=true`. Здесь проверяется ровно production-функция,
 * которой теперь пользуется `LockSound`.
 */
class Iter51LockOpTest {

    private fun fact(
        target: String,
        state: MountInfo.FactState,
        source: String = "/data/local/tmp/carlock_custom_g1_abcd.wav"
    ) = MountInfo.MountFact(
        target = target,
        source = source,
        state = state,
        mid = "1002",
        root = "/tmp/carlock_custom_g1_abcd.wav",
        devIno = "8:111",
        reason = if (state == MountInfo.FactState.VERIFIED) "none" else "dev_ino_mismatch",
        mountRc = "0"
    )

    private val a = "/vendor/etc/carlock/carlock_close_1.wav"
    private val b = "/vendor/etc/carlock/carlock_close_2.wav"

    @Test
    fun oneVerifiedOneUnknownIsNotComplete() {
        val ev = LockOp.evidence(listOf(a, b), listOf(
            fact(a, MountInfo.FactState.VERIFIED),
            fact(b, MountInfo.FactState.UNKNOWN)
        ), targetsReadable = true)
        assertFalse("«одна verified + одна unknown» — НЕ применено: ${ev.wire()}", ev.complete)
        assertEquals(1, ev.verified)
        assertEquals(1, ev.unknown)
        assertEquals(listOf(b), ev.missing)
        assertEquals("unknown_targets", ev.reason)
    }

    @Test
    fun oneVerifiedOneUnverifiedIsNotComplete() {
        val ev = LockOp.evidence(listOf(a, b), listOf(
            fact(a, MountInfo.FactState.VERIFIED),
            fact(b, MountInfo.FactState.UNVERIFIED)
        ), targetsReadable = true)
        assertFalse(ev.complete)
        assertEquals("unverified_targets", ev.reason)
        assertEquals(1, ev.unverified)
    }

    @Test
    fun duplicateFactForSameTargetIsNotComplete() {
        val ev = LockOp.evidence(listOf(a, b), listOf(
            fact(a, MountInfo.FactState.VERIFIED),
            fact(a, MountInfo.FactState.VERIFIED),
            fact(b, MountInfo.FactState.VERIFIED)
        ), targetsReadable = true)
        assertFalse("дублированная строка не доказывает применение: ${ev.wire()}", ev.complete)
        assertEquals(listOf(a), ev.duplicates)
        assertEquals("duplicate_targets", ev.reason)
    }

    @Test
    fun allVerifiedIsComplete() {
        val ev = LockOp.evidence(listOf(a, b), listOf(
            fact(a, MountInfo.FactState.VERIFIED),
            fact(b, MountInfo.FactState.VERIFIED)
        ), targetsReadable = true)
        assertTrue("обе цели подтверждены: ${ev.wire()}", ev.complete)
        assertEquals(2, ev.verified)
    }

    @Test
    fun unreadableTargetListProvesNothing() {
        val ev = LockOp.evidence(emptyList(), listOf(fact(a, MountInfo.FactState.VERIFIED)),
            targetsReadable = false)
        assertFalse("список целей не прочитан — доказать нечего", ev.complete)
        assertEquals("targets_unreadable", ev.reason)
    }

    @Test
    fun emptyTargetListIsNotSuccess() {
        val ev = LockOp.evidence(emptyList(), emptyList(), targetsReadable = true)
        assertFalse("целей нет — это не «применено»", ev.complete)
        assertEquals("no_targets", ev.reason)
    }

    @Test
    fun targetListChangedIsNotComplete() {
        // Цель появилась после чтения списка — её нет в ожидаемом наборе.
        val ev = LockOp.evidence(listOf(a), listOf(
            fact(a, MountInfo.FactState.VERIFIED),
            fact(b, MountInfo.FactState.VERIFIED)
        ), targetsReadable = true)
        assertFalse("лишняя цель не доказана ожидаемым набором: ${ev.wire()}", ev.complete)
        assertEquals(listOf(b), ev.extra)
        assertEquals("unexpected_targets", ev.reason)
    }

    @Test
    fun mountSuccessWithoutAnyFactIsNotComplete() {
        val ev = LockOp.evidence(listOf(a, b), emptyList(), targetsReadable = true)
        assertFalse(ev.complete)
        assertEquals("not_mounted", ev.reason)
        assertEquals(2, ev.missing.size)
    }

    // ── журнал операции ──────────────────────────────────────────────────────────────────

    @Test
    fun journalRoundTripsExactly() {
        val op = LockOp.BindOp("bind_3_0a1b2c3d", 3L, true, "/data/local/tmp/carlock_custom_g3_aa.wav",
            listOf(a, b), LockOp.State.PLANNED)
        val back = LockOp.decode(op.encode())
        assertEquals(op, back)
        assertTrue("в журнале нет содержимого WAV", !op.encode().contains("RIFF"))
        assertTrue("в строке журнала нет длинных путей целей",
            op.wire().length < 200)
    }

    @Test
    fun brokenJournalIsNullNotEmptyOperation() {
        assertNull("повреждённая запись — «неизвестно», не «операции не было»", LockOp.decode("мусор"))
        assertNull(LockOp.decode(""))
        assertNull(LockOp.decode(null))
        assertNull(LockOp.decode("id=x;gen=notanumber;desired=1;state=planned"))
    }

    @Test
    fun bindIsNotSentWithoutConfirmedJournal() {
        assertFalse(LockOp.maySendBind(true, listOf(a), journalConfirmed = false).first)
        assertEquals("journal_not_confirmed",
            LockOp.maySendBind(true, listOf(a), journalConfirmed = false).second)
        assertTrue(LockOp.maySendBind(true, listOf(a), journalConfirmed = true).first)
        assertFalse("OFF не монтирует", LockOp.maySendBind(false, listOf(a), true).first)
        assertFalse("без целей не монтируем", LockOp.maySendBind(true, emptyList(), true).first)
    }

    @Test
    fun stateTransitionsAreEncodedHonestly() {
        val op = LockOp.BindOp("id1", 1L, true, "/data/local/tmp/x.wav", listOf(a), LockOp.State.PLANNED)
        for (st in LockOp.State.values()) {
            assertEquals(st, LockOp.decode(op.copy(state = st).encode())?.state)
        }
    }
}
