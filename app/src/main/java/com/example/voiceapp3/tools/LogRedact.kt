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
 *  • iter62 §B2: SSID/BSSID, MAC и IP-адреса тоже маскируются. Раньше IPv4 намеренно
 *    оставались «для диагностики сети», но владелец запретил печатать IP/MAC/SSID где бы то ни
 *    было; для диагностики достаточно ФАКТА адреса и семейства (`<ip4>`, `<ip6>`), а не его
 *    значения. Имена хостов (`example.invalid`) остаются — это не секрет.
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

    /** iter62 §B2: сетевые идентификаторы, которые нельзя печатать. */
    private val SSID = Regex("(?i)\\b(ssid|bssid|wifi_ssid|wifi_bssid)\\s*=\\s*\\S+")
    private val MAC = Regex("(?i)\\b[0-9a-f]{2}(:[0-9a-f]{2}){5}\\b")
    private val IP4 = Regex("\\b(\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val IP6 = Regex("(?i)\\b([0-9a-f]{1,4}:){2,7}[0-9a-f]{0,4}\\b")

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
        // Маскирование сетевых идентификаторов — ПОСЛЕ blob/литералов, чтобы не мешать им.
        s = SSID.replace(s) { it.groupValues[1] + "=<redacted>" }
        s = MAC.replace(s, "<mac>")
        s = IP4.replace(s, "<ip4>")
        s = IP6.replace(s, "<ip6>")
        return if (s.length <= maxLen) s else s.take(maxLen) + "…(len=${cmd.length})"
    }

    /** То же для тела лога: сохраняет полезные `key=value`, вырезает payload. */
    fun text(s: String, maxLen: Int = 300): String = cmd(s, maxLen)
}
