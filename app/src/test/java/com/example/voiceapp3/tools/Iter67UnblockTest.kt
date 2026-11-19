package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter67 §C — отметка операции: `ts` = время ПОСЛЕДНЕЙ попытки, решения всегда логируются.
 *
 * Живые числа: `JIT_PEDIDO kept` 147 и 279 раз при `JIT_OP_ABANDONED = 0`; `ts` не обновлялся,
 * поэтому окно `STALE_MARK_MS` не наступало никогда.
 */
class Iter67UnblockTest {

    private val limit = 15 * 60_000L

    @Test
    fun `ts обновляется при новой попытке`() {
        val (ts, att) = JitUnblock.nextAttempt(tsMs = 1_000, attempt = 1, nowMs = 5_000)
        assertEquals(5_000L, ts)
        assertEquals(2, att)
    }

    @Test
    fun `истечение окна даёт exit=window_expired и allowed`() {
        // fail-before: возраст считался от ПЕРВОЙ попытки, а строки решения не было вовсе.
        // Возраст 20 мин при окне 15 мин: окно истекло.
        val d = JitUnblock.decide(markTsMs = 1L, markBoot = "a", currentBootId = "a",
            nowMs = 20 * 60_000L, limitMs = limit)
        assertEquals("window_expired", d.exit)
        assertTrue(d.clicksAllowed)
    }

    @Test
    fun `возраст внутри окна не даёт выхода`() {
        val d = JitUnblock.decide(markTsMs = 1L, markBoot = "a", currentBootId = "a",
            nowMs = 10 * 60_000L, limitMs = limit)
        assertEquals("none", d.exit)
        assertFalse(d.clicksAllowed)
        assertEquals(599_999L, d.ageMs)
    }

    @Test
    fun `другая загрузка даёт exit=boot_changed и снимок не применяется`() {
        val d = JitUnblock.decide(markTsMs = 1L, markBoot = "a", currentBootId = "b",
            nowMs = 2_000L, limitMs = limit)
        assertEquals("boot_changed", d.exit)
        assertFalse(d.snapshotApplied)
        assertTrue(d.clicksAllowed)
    }

    @Test
    fun `свежая отметка той же загрузки блокирует без выхода`() {
        val d = JitUnblock.decide(markTsMs = 1_000L, markBoot = "a", currentBootId = "a",
            nowMs = 2_000L, limitMs = limit)
        assertEquals("none", d.exit)
        assertFalse(d.clicksAllowed)
    }

    @Test
    fun `zygote стартовал после отметки — exit=zygote_after_mark`() {
        val d = JitUnblock.decide(markTsMs = 1_000L, markBoot = "a", currentBootId = "a",
            nowMs = 2_000L, limitMs = limit, zygoteStartMs = 1_500L)
        assertEquals("zygote_after_mark", d.exit)
        assertTrue(d.clicksAllowed)
    }

    @Test
    fun `terminal закончился — exit=terminal_finished`() {
        val d = JitUnblock.decide(markTsMs = 1_000L, markBoot = "a", currentBootId = "a",
            nowMs = 2_000L, limitMs = limit, terminalFinished = true)
        assertEquals("terminal_finished", d.exit)
    }

    @Test
    fun `решение всегда логируется строкой JIT_UNBLOCK с exit=`() {
        val d = JitUnblock.decide(markTsMs = 1L, markBoot = "a", currentBootId = "b",
            nowMs = 2_000L, limitMs = limit)
        val line = d.line("op-1", limit, bootSame = false)
        assertTrue("нет префикса: $line", line.startsWith("JIT_UNBLOCK "))
        assertTrue("нет exit=: $line", line.contains("exit=boot_changed"))
        assertTrue("нет clicks=: $line", line.contains("clicks=allowed"))
        assertTrue("нет snapshot_applied=: $line", line.contains("snapshot_applied=no"))
    }

    @Test
    fun `отрицательный и секундный ts не считаются свежими`() {
        // Живой случай: `ts=-196980728` → `Toggles45.isStale` вернул false → блокировка 6 суток.
        val neg = JitUnblock.decide(markTsMs = -196_980_728L, markBoot = null, currentBootId = "a",
            nowMs = 1_790_000_000_000L, limitMs = limit)
        assertEquals("mark_invalid_negative", neg.exit)
        assertTrue("иначе клик заблокирован навсегда", neg.clicksAllowed)

        val sec = JitUnblock.decide(markTsMs = 1_790_000_000L, markBoot = null, currentBootId = "a",
            nowMs = 1_790_000_000_000L, limitMs = limit)
        assertEquals("mark_invalid_unit_mismatch", sec.exit)
        assertTrue(sec.clicksAllowed)

        assertEquals(MarkVerdict.ZERO, JitUnblock.classifyMark(0L, 1_790_000_000_000L))
        assertEquals(MarkVerdict.FUTURE,
            JitUnblock.classifyMark(1_790_000_000_000L + 60 * 60_000L, 1_790_000_000_000L))
        assertEquals(MarkVerdict.OK, JitUnblock.classifyMark(1_790_000_000_000L, 1_790_000_000_000L))
    }

    @Test
    fun `отметка прежних версий без boot не объявляет смену загрузки`() {
        assertTrue(JitUnblock.bootSame(null, "b"))
        assertTrue(JitUnblock.bootSame("b", null))
        assertTrue(JitUnblock.bootSame("b", "b"))
        assertFalse(JitUnblock.bootSame("a", "b"))
    }

    @Test
    fun `отметка несёт attempt и boot, старый формат читается`() {
        val wire = JitRef.OpMark("op-1", 1_790_000_000_000L, true, JitRef.OpMark.SENDING,
            attempt = 3, boot = "1f2e3d").wire()
        assertTrue("нет attempt в записи: $wire", wire.contains(";attempt=3"))
        assertTrue("нет boot в записи: $wire", wire.contains(";boot=1f2e3d"))
        val back = JitRef.OpMark.parse(wire)
        assertEquals(3, back?.attempt)
        assertEquals("1f2e3d", back?.boot)

        // Отметка прежних версий (без attempt/boot) обязана читаться как раньше.
        val legacy = JitRef.OpMark("op-1", 1_790_000_000_000L, true, JitRef.OpMark.SENDING).wire()
        assertFalse(legacy.contains("attempt="))
        assertEquals(0, JitRef.OpMark.parse(legacy)?.attempt)
    }

    @Test
    fun `клик обновляет отметку до JIT_CHAIN_SENT и в оба хранилища`() {
        val src = java.io.File("app/src/main/java/com/example/voiceapp3/tools/JitNetCore.kt").readText()
        val iAttempt = src.indexOf("JIT_ATTEMPT op=")
        val iSent = src.indexOf("\"JIT_CHAIN_SENT op=")
        // fail-before: перед отправкой отметка не обновлялась вообще.
        assertTrue("нет обновления отметки", iAttempt > 0)
        assertTrue("обновление отметки должно быть ДО JIT_CHAIN_SENT", iAttempt < iSent)
        assertTrue("отметка не пишется в хранилища", src.contains("store.updateMark(sentMark)"))
        assertTrue("строка решения о разблокировке не пишется", src.contains("dec.line("))
    }
}
