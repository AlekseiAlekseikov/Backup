package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Чтение `/proc/self/maps` как в `w0/bb`: реальный файл, реальные строки.
 *
 * Здесь проверяется ФАЙЛОВОЕ чтение (а не подставная строка) и то, что разбор идёт по правам:
 *  • temp-файл с настоящим форматом `/proc/self/maps` → `true`/`false`/`null` по содержимому;
 *  • отсутствующий файл → `null` (ошибка чтения, как `catchall_67`), а не `false`;
 *  • собственный `/proc/self/maps` процесса JVM читается и даёт `null` (строк jit-cache нет).
 */
class JitMapsTest {

    private fun read(path: File): String? = runCatching { path.readText() }.getOrNull()

    private fun parsed(path: File): Boolean? =
        JitRef.cacheExecutableFromMaps(read(path))

    private fun tmp(content: String): File =
        File.createTempFile("maps", ".txt").apply { writeText(content); deleteOnExit() }

    private val exec = "7f2a000000-7f2a040000 r-xp 00000000 fd:04 4242 /data/dalvik-cache/arm64/jit-cache"
    private val noExec = "7f2a040000-7f2a080000 rw-p 00000000 fd:04 4242 /data/dalvik-cache/arm64/jit-cache"

    @Test
    fun realFileWithExecutableCacheLineIsTrue() {
        assertEquals(true, parsed(tmp("$noExec\n$exec\n")))
    }

    @Test
    fun realFileWithoutExecutablePermissionIsFalse() {
        assertEquals("строка есть, но без x — это НЕ успех", false, parsed(tmp("$noExec\n")))
    }

    @Test
    fun realFileWithoutCacheLinesIsNull() {
        assertEquals(null, parsed(tmp("7f2b000000-7f2b001000 r--p 0 fd:00 1 /system/lib64/libfoo.so\n")))
    }

    @Test
    fun missingFileIsNullNotFalse() {
        val gone = File("/nonexistent/for/tests/maps")
        assertNull("нет доступа/нет файла ⇒ unknown", read(gone))
        assertNull(parsed(gone))
        assertTrue("но разбор пустой строки тоже null", parsed(tmp("")) == null)
    }

    @Test
    fun ownProcessMapsIsReallyRead() {
        val own = read(File("/proc/self/maps"))
        assertTrue("карта процесса читается", own != null && own.contains(" r-xp "))
        // В процессе JVM строк jit-cache нет ⇒ null по семантике j5.d (не false).
        assertNull(parsed(File("/proc/self/maps")))
    }
}
