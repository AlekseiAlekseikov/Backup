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

    /** MAC — шесть групп РОВНО по два hex-символа (иначе это IPv6, а не MAC). */
    private val MAC = Regex("(?i)\\b[0-9a-f]{2}(?::[0-9a-f]{2}){5}\\b")

    /**
     * iter63 §3.3: IPv4 маскируется ВМЕСТЕ С ПОРТОМ.
     *
     * Дефект iter62: из `192.168.1.5:8080` вырезался адрес, а `:8080` оставался — порт вместе с
     * адресом и есть идентификатор узла. Скобочная запись `[<ip4>]:<port>` обрабатывается до
     * обычной, иначе от неё остался бы `]:443`.
     */
    private val IP4_BRACKET = Regex("\\[(?:\\d{1,3}\\.){3}\\d{1,3}\\](?::\\d{1,5})?")
    private val IP4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b")

    /**
     * iter63 §3.3: IPv6 — при ЛЮБОМ числе групп, в том числе сокращённая форма `fe80::1`
     * (в iter62 шаблон требовал ≥3 групп с двоеточиями и короткие адреса пропускал).
     *
     * Скобочная запись с портом `[<ip6>]:8080` маскируется целиком.
     */
    private val IP6_BRACKET = Regex("(?i)\\[[0-9a-f:]{2,45}\\](?::\\d{1,5})?")
    private val IP6_CAND = Regex("(?i)(?<![0-9a-z:\\[])[0-9a-f]{0,4}(?::[0-9a-f]{0,4}){2,7}(?![0-9a-z:])")

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
        // Порядок важен: MAC (ровно 6 групп по 2 символа) → IPv4 со скобками и портом → IPv6
        // со скобками и портом → свободный IPv6 по критерию [looksLikeIp6]. Время суток
        // (`12:34:56`) критерий не проходит и остаётся читаемым.
        s = SSID.replace(s) { it.groupValues[1] + "=<redacted>" }
        s = MAC.replace(s, "<mac>")
        s = IP4_BRACKET.replace(s, "<ip4>")
        s = IP4.replace(s, "<ip4>")
        s = IP6_BRACKET.replace(s, "<ip6>")
        s = IP6_CAND.replace(s) { if (looksLikeIp6(it.value)) "<ip6>" else it.value }
        return if (s.length <= maxLen) s else s.take(maxLen) + "…(len=${cmd.length})"
    }

    /**
     * iter63 §3.3 — отличить IPv6 от времени суток и пропорции.
     *
     * Признаки адреса: сокращённая форма с `::` либо ровно 8 групп по 1–4 hex-символа.
     * `12:34:56` (3 группы по 2 цифры) и `3:4` адресом не являются — время в журнале нужно,
     * иначе диагностика по времени события становится невозможной.
     */
    internal fun looksLikeIp6(token: String): Boolean {
        val s = token.removeSurrounding("[", "]")
        if (s.count { it == ':' } < 2) return false
        if (s.contains("::")) return true
        val groups = s.split(':')
        if (groups.size != 8) return false
        return groups.all { g ->
            g.length in 1..4 && g.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' }
        }
    }

    /** То же для тела лога: сохраняет полезные `key=value`, вырезает payload. */
    fun text(s: String, maxLen: Int = 300): String = cmd(s, maxLen)
}
