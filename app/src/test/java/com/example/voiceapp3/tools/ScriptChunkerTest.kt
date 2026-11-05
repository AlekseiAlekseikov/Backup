package com.example.voiceapp3.tools

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * iter33 A0.3 — JVM-тест чанкера скриптов (без устройства).
 *
 * Регресс 1.0.52: `executeScriptMultipart` резал скрипт ПО БАЙТАМ
 * (`String(bytes, offset, end - offset, UTF_8)`), разрез попадал внутрь
 * многобайтового символа кириллицы → символы превращались в `U+FFFD` (3 байта
 * вместо 2), из-за чего файл на ГУ получался на 83 байта больше (5046 вместо 4963)
 * и предохранитель размера не пускал скрипт на исполнение.
 *
 * Тест проверяет ровно то, что должен гарантировать чанкер: после нарезки и
 * обратной сборки байты совпадают БАЙТ-В-БАЙТ с исходником.
 */
class ScriptChunkerTest {

    /** Тот же алгоритм, что в `AdbClient.scriptChunks` (единственный источник истины — ASCII base64). */
    private fun chunks(script: String, chunk: Int = 2500): List<String> =
        ScriptChunker.chunks(script, chunk)

    private fun reassembleByBase64(script: String, chunk: Int = 2500): ByteArray {
        val sb = StringBuilder()
        for (c in chunks(script, chunk)) sb.append(c)
        return ScriptChunker.decode(listOf(sb.toString()))
    }

    /**
     * Скрипт «как настоящий»: кириллица в комментариях + латиница в коде, РОВНО [target] байт.
     * Хвост добивается ASCII-символами, поэтому точный размер гарантирован при любой длине
     * многобайтовой части (именно на ней ломался старый байтовый разрез).
     */
    private fun scriptOfExactly(target: Int): String {
        val head = "# РЕФЕРЕНС: план А эталона — иначе установка «затенят» /system …\n" +
            "# если окно не около POS 10 — не мешаю; ключ vent_owners=10=1,4;40=4\n"
        val body = "echo HVAC_DIGITAL_OK; id; getenforce; pm path com.flyme.auto.systemuiplugin\n"
        val sb = StringBuilder(head).append(body)
        var i = 0
        // оставляем 120 байт под точную ASCII-подгонку
        while (sb.toString().toByteArray(StandardCharsets.UTF_8).size < target - 120) {
            sb.append("# шаг ").append(i++).append(": кириллица для проверки границ символов\n")
        }
        sb.append("#")
        var cur = sb.toString().toByteArray(StandardCharsets.UTF_8).size
        while (cur < target) { sb.append("x"); cur += 1 }
        return sb.toString()
    }

    @Test
    fun chunkerKeepsBytesForCyrillicScript() {
        val script = scriptOfExactly(4963)
        val bytes = script.toByteArray(StandardCharsets.UTF_8)
        assertEquals("исходник должен быть ровно 4963 байта", 4963, bytes.size)
        assertArrayEquals("после нарезки+сборки байты обязаны совпасть", bytes, reassembleByBase64(script))
    }

    @Test
    fun chunkerProducesAsciiOnly() {
        val script = scriptOfExactly(4963)
        for (c in chunks(script)) {
            for (ch in c) {
                assertTrue("чанк обязан быть ASCII (символ '$ch' вне ASCII)", ch.code in 32..126)
            }
            assertTrue("чанк не должен превышать лимит payload", c.length <= 2500)
        }
    }

    /**
     * Старый байтовый разрез — доказательство, что он ломал кириллицу (регресс не вернётся).
     * Строка построена ДЕТЕРМИНИРОВАННО: 1999 ASCII-символов, затем кириллица, поэтому
     * граница 2000 обязательно попадает ВНУТРЬ двухбайтового символа — ровно как в логе 1.0.52.
     */
    @Test
    fun oldByteSlicingWasBroken() {
        val script = "a".repeat(1999) + "бвгд".repeat(800) + "\n"
        val bytes = script.toByteArray(StandardCharsets.UTF_8)
        val broken = StringBuilder()
        var off = 0
        while (off < bytes.size) {
            val end = minOf(off + 2000, bytes.size)
            broken.append(String(bytes, off, end - off, StandardCharsets.UTF_8))
            off = end
        }
        val brokenBytes = broken.toString().toByteArray(StandardCharsets.UTF_8)
        assertTrue(
            "байтовая нарезка обязана искажать кириллицу (иначе тест бессмыслен)",
            brokenBytes.size != bytes.size || broken.contains('\uFFFD')
        )
        // а новый чанкер (base64) обязан вернуть байт-в-байт
        assertArrayEquals(bytes, ScriptChunker.decode(ScriptChunker.chunks(script)))
    }

    /** Короткий ASCII-скрипт тоже проходит (границ нет, но контракт тот же). */
    @Test
    fun asciiOnlyRoundTrip() {
        val script = "id\n echo hello\n"
        assertArrayEquals(script.toByteArray(StandardCharsets.UTF_8), reassembleByBase64(script))
    }
}
