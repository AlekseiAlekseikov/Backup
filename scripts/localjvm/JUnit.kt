package org.junit
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class Test
object Assert {
    fun assertTrue(c: Boolean) { if (!c) throw AssertionError("expected true") }
    fun assertTrue(m: String, c: Boolean) { if (!c) throw AssertionError(m) }
    fun assertFalse(c: Boolean) { if (c) throw AssertionError("expected false") }
    fun assertFalse(m: String, c: Boolean) { if (c) throw AssertionError(m) }
    fun assertEquals(e: Any?, a: Any?) { if (e != a) throw AssertionError("expected $e got $a") }
    fun assertEquals(m: String, e: Any?, a: Any?) { if (e != a) throw AssertionError("$m: expected $e got $a") }
    fun assertEquals(e: Long, a: Long) { if (e != a) throw AssertionError("expected $e got $a") }
    fun assertArrayEquals(e: ByteArray, a: ByteArray) { if (!e.contentEquals(a)) throw AssertionError("arrays differ") }
    fun assertNull(a: Any?) { if (a != null) throw AssertionError("expected null got $a") }
    fun assertNull(m: String, a: Any?) { if (a != null) throw AssertionError("$m: expected null got $a") }
    fun assertNotNull(m: String, a: Any?) { if (a == null) throw AssertionError("$m: expected not null") }
    fun assertNotNull(a: Any?) { if (a == null) throw AssertionError("expected not null") }
}
