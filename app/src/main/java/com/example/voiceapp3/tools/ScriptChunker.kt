package com.example.voiceapp3.tools

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * iter33 A0 — ЕДИНСТВЕННЫЙ способ залить скрипт на ГУ.
 *
 * Почему так (корень бага 1.0.52): в `AdbClient.executeScriptMultipart` скрипт резался
 * **по байтам** —
 *
 * ```
 * val chunk = String(bytes, offset, end - offset, StandardCharsets.UTF_8)
 * ```
 *
 * Разрез 2000/4000 байт попадал внутрь многобайтового символа кириллицы (комментарии
 * `# РЕФЕРЕНС …`, `# план А эталона`, `# иначе установка «затенят» /system`), обрывок
 * декодировался в `U+FFFD` (3 байта вместо 2), и файл на устройстве получался
 * **5046 байт вместо 4963** (+83). Предохранитель `if [ "$SZ" != "4963" ]` не пускал
 * скрипт на исполнение — `sh $path` не выполнялся ни разу (18 строк `ERR_SCRIPT_UPLOAD`
 * в логе 1.0.52).
 *
 * Теперь скрипт кодируется в base64 (ASCII: границы символов безопасны по определению)
 * и льётся чанками `echo '<b64>' | base64 -d >> $path`. Ни одного «сырого» байта
 * UTF-8 через shell не проходит.
 *
 * Файл намеренно не имеет Android-зависимостей: его проверяет JVM-тест
 * `app/src/test/java/…/ScriptChunkerTest.kt` в CI.
 */
object ScriptChunker {

    /** Размер base64-чанка в символах (умещается в ADB payload 4096 с запасом). */
    const val CHUNK = 2500

    /** base64-чанки скрипта: только печатный ASCII. */
    fun chunks(script: String, chunk: Int = CHUNK): List<String> {
        val b64 = Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_8))
        val out = ArrayList<String>((b64.length / chunk) + 1)
        var pos = 0
        while (pos < b64.length) {
            val end = minOf(pos + chunk, b64.length)
            out.add(b64.substring(pos, end))
            pos = end
        }
        return out
    }

    /** Обратная операция (для теста и для диагностики). */
    fun decode(chunks: List<String>): ByteArray =
        Base64.getDecoder().decode(chunks.joinToString(""))

    /** Байты скрипта — то, что должно оказаться в файле на устройстве. */
    fun bytesOf(script: String): ByteArray = script.toByteArray(StandardCharsets.UTF_8)

    /** Очистка файла перед заливкой (одна короткая команда). */
    fun clearCommand(path: String): String = "rm -f $path; : > $path; echo CLEARED"

    /** Один чанк = одна ADB-команда (каждый ≤ 4096 байт payload, включая обёртку rc). */
    fun chunkCommand(path: String, chunk: String): String =
        // base64 — печатный ASCII, кавычка в нём невозможна, экранирование не нужно
        "echo '$chunk' | base64 -d >> $path"

    /**
     * Финальная команда: права, ЖЁСТКАЯ сверка размера (предохранитель сохранён) и запуск.
     * `exit 99` при расхождении теперь доезжает до приложения настоящим `rc` (A1).
     */
    fun verifyAndRunCommand(path: String, expectedBytes: Int): String =
        "chmod 700 $path; SZ=\$(wc -c < $path); " +
            "echo \"SCRIPT_BYTES=\$SZ EXPECTED=$expectedBytes\"; " +
            "if [ \"\$SZ\" != \"$expectedBytes\" ]; then " +
            "echo \"ERR_SCRIPT_UPLOAD expected=$expectedBytes actual=\$SZ\"; rm -f $path; exit 99; fi; " +
            "sh $path; RC=\$?; rm -f $path; exit \$RC"
}
