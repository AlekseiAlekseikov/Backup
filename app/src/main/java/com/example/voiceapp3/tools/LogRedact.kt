package com.example.voiceapp3.tools

/**
 * iter43 §1.4 — БЕЗОПАСНОЕ представление произвольного текста для журнала.
 *
 * Отдельный чистый объект (без Android-зависимостей), чтобы правило можно было проверить
 * JVM-тестом на тех же строках, которыми пользуется `AdbClient`/`LogKit`.
 *
 * Зачем: в логе владельца 12.11 встречались фрагменты payload (`echo '…'`, base64-чанки
 * скрипта, `cmd=` целиком). Само наличие фрагмента не доказывает наличия ключа, но хранить
 * содержимое в журнале нельзя.
 *
 * Что делается (и чего НЕ делается):
 *  • схлопывание пробелов + ограничение длины — виден ТИП команды, не содержимое;
 *  • тела литералов `'…'`/`"…"` → `'<N bytes>'`;
 *  • длинные base64/hex-подобные токены (≥ [BLOB_MIN]) → `<blob:N>`;
 *  • значения `key|token|secret|password|…=…` → `<redacted>`;
 *  • IPv4 — НЕ маскируются здесь (нужны в диагностике сети, см. предупреждение в отчёте).
 *
 * Это уменьшение утечки, а не криптографическая гарантия: ни одна регулярка не даёт
 * обещания «секретов в логе не будет».
 */
internal object LogRedact {

    /** Порог «похоже на blob»: base64/hex-строка такой длины почти наверняка не имя. */
    internal const val BLOB_MIN = 32

    /** Секретные ключи: значение вырезается целиком. */
    private val SECRET_KEYS =
        Regex("(?i)\\b(key|token|secret|password|passwd|pwd|auth|bearer|pin|sig|signature|apikey|api_key)\\s*=\\s*\\S+")

    /** Строковые литералы: остаётся только размер (в байтах UTF-8 — как в файле). */
    private val SINGLE_QUOTED = Regex("'[^']*'", RegexOption.DOT_MATCHES_ALL)
    private val DOUBLE_QUOTED = Regex("\"[^\"]*\"", RegexOption.DOT_MATCHES_ALL)

    private val BLOB = Regex("[A-Za-z0-9+/=_-]{$BLOB_MIN,}")

    /** Сколько байт UTF-8 занимает тело литерала (для отчёта важнее байтов, чем символов). */
    internal fun utf8Len(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /** Безопасная форма команды/строки вывода. Детерминированная (тестируемая). */
    fun cmd(cmd: String, maxLen: Int = 120): String {
        var s = cmd.replace(Regex("\\s+"), " ").trim()
        s = SINGLE_QUOTED.replace(s) { "'<" + utf8Len(it.value) + "B>'" }
        s = DOUBLE_QUOTED.replace(s) { "\"<" + utf8Len(it.value) + "B>\"" }
        s = SECRET_KEYS.replace(s) { it.groupValues[1] + "=<redacted>" }
        s = BLOB.replace(s) { "<blob:" + it.value.length + ">" }
        return if (s.length <= maxLen) s else s.take(maxLen) + "…(len=${cmd.length})"
    }

    /** То же для тела лога: сохраняет полезные `key=value`, вырезает payload. */
    fun text(s: String, maxLen: Int = 300): String = cmd(s, maxLen)
}
