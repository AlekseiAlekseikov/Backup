package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Опознание загрузки для снимка операции: без него нельзя отличить «продолжение этой операции»
 * от «остатка прошлой загрузки» (мандат §1.3). Чистая арифметика строк, без Android.
 */
class JitBootMarkTest {

    @Test
    fun exactTagMatches() {
        assertTrue(JitBootMark.sameLoad("boot-1:12345", "boot-1"))
        assertFalse(JitBootMark.sameLoad("boot-2:12345", "boot-1"))
    }

    @Test
    fun emptyOrMalformedIsForeignLoad() {
        assertFalse(JitBootMark.sameLoad("", "boot-1"))
        assertFalse(JitBootMark.sameLoad("boot-1:1", ""))
        assertFalse(JitBootMark.sameLoad(":1", "boot-1"))
        assertFalse("нет разделителя ⇒ тег не опознан", JitBootMark.sameLoad("12345", "boot-1"))
    }

    @Test
    fun epochTagsUseTolerance() {
        val now = JitBootMark.epochTag(1_700_000_000_000L)
        assertTrue(JitBootMark.sameLoad("$now:1000", now))
        val later = JitBootMark.epochTag(1_700_000_030_000L)   // +30 с
        assertTrue("часы/NTP в пределах минуты — та же загрузка", JitBootMark.sameLoad("$later:1", now))
        val muchLater = JitBootMark.epochTag(1_700_009_000_000L) // +150 мин
        assertFalse(JitBootMark.sameLoad("$muchLater:1", now))
    }

    @Test
    fun tagFormat() {
        assertEquals("epoch1700000000", JitBootMark.epochTag(1_700_000_000_999L))
        assertEquals("boot-1:42", JitBootMark.format("boot-1", 42L))
        assertEquals(42L, JitBootMark.uptimeOf("boot-1:42"))
        assertEquals(-1L, JitBootMark.uptimeOf(""))
    }
}
