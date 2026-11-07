package com.example.voiceapp3.tools

/**
 * iter33k §3 — правила TTL/HL со СКОУПОМ (глобальное правило ломало CarPlay).
 *
 * Эталон самих правил — CentralEXAuto 3.3.9 (`iptables -t mangle -A POSTROUTING -j TTL --ttl-set 65`,
 * `ip6tables … -j HL --hl-set 65`), но у референса правило глобальное; у нас глобальное правило
 * рубит CarPlay (опыт владельца 06.11), поэтому применяем ТОЛЬКО со скоупом:
 *
 *  • вариант `uplink` — правило на исходящий интерфейс интернета/раздачи (`-o <uplink>`);
 *  • варианта «всё кроме CarPlay» (`! -o <carplay>`) БОЛЬШЕ НЕТ (iter33m §1.2): групповой
 *    интерфейс `p2p-p2p0-0` под `! -o p2p0` не попадал, и CarPlay отваливался.
 *    Нет uplink — правила нет вовсе (`TTL_TRY variant=none reason=no_uplink`), тумблер `✗`.
 *
 * Выключение — удаление ВСЕХ правил с `ttl-set`/`hl-set` из POSTROUTING (generic-проход по
 * `iptables -S`), плюс эталонный цикл `1..5` для глобальной формы из старых версий.
 * Всё чистыми функциями: покрыто `TtlRulesTest`.
 */
internal object TtlRules {

    /**
     * Интерфейс дефолтного маршрута из вывода `ip route get 1.1.1.1`
     * (строка вида `1.1.1.1 via 192.168.1.1 dev wlan0 src 192.168.1.5 uid 1000`).
     */
    fun uplinkOf(routeOut: String): String? {
        val tokens = routeOut.trim().split(Regex("\\s+"))
        val i = tokens.indexOf("dev")
        if (i < 0 || i + 1 >= tokens.size) return null
        val dev = tokens[i + 1].trim()
        return if (dev.isEmpty() || dev == "dev") null else dev
    }

    /** Интерфейсы CarPlay: USB-тетеринг (`rndis`/`ncm`/`usb`) и Wi-Fi P2P. */
    fun carplayIfaces(linkOut: String): List<String> =
        linkOut.lineSequence()
            .mapNotNull { line ->
                Regex("^\\d+:\\s+([A-Za-z0-9_.@-]+):").find(line.trim())?.groupValues?.get(1)
            }
            .map { it.substringBefore('@') }
            .filter { n ->
                val l = n.lowercase()
                l.startsWith("rndis") || l.startsWith("ncm") || l.startsWith("usb") ||
                    l.startsWith("p2p") || l.startsWith("ap0")
            }
            .distinct()
            .toList()

    /** Все интерфейсы (для строки `TTL_NET ifaces=…`). */
    fun allIfaces(linkOut: String): List<String> =
        linkOut.lineSequence()
            .mapNotNull { line ->
                Regex("^\\d+:\\s+([A-Za-z0-9_.@-]+):").find(line.trim())?.groupValues?.get(1)
            }
            .map { it.substringBefore('@') }
            .filter { it != "lo" }
            .distinct()
            .toList()

    /** Одно правило v4 (или v6) в форме `-A POSTROUTING …` без ведущего `-A`. */
    fun ruleV4(scope: String): String = "$scope -j TTL --ttl-set 65"
    fun ruleV6(scope: String): String = "$scope -j HL --hl-set 65"

    /**
     * Варианты скоупа: ТОЛЬКО «на uplink» (`-o <uplink>`). Нет uplink — пустой список,
     * т.е. правило не ставится вообще (глобальное запрещено — ломает CarPlay).
     * `carplay` остаётся параметром ради совместимости вызовов/диагностики и не влияет на скоуп.
     */
    @Suppress("UNUSED_PARAMETER")
    fun variants(uplink: String?, carplay: List<String>): List<Pair<String, String>> {
        if (uplink.isNullOrEmpty()) return emptyList()
        return listOf("uplink" to "-A POSTROUTING -o $uplink")
    }

    /**
     * Строка диагностики (мандат §3.2): `TTL_NET ifaces=… carplay=… routes=… uplink=… v4=… v6=…`.
     * Данные — read-only `ip -br addr` / `ip link` / `ip route` / счётчики правил.
     */
    fun netLine(ifaces: List<String>, carplay: List<String>, uplink: String?, v4: Int, v6: Int): String =
        "TTL_NET ifaces=${ifaces.joinToString(",").ifEmpty { "none" }} " +
            "carplay=${carplay.joinToString(",").ifEmpty { "none" }} " +
            "uplink=${uplink ?: "none"} v4=$v4 v6=$v6"

    /** Строка попытки варианта (мандат §3.3). */
    fun tryLine(variant: String, scope: String, v4: Int, v6: Int): String =
        "TTL_TRY variant=$variant scope=\"${scope.trim()}\" v4=$v4 v6=$v6 carplay=unknown"

    /** Строка снятия правил (выключение). */
    fun offLine(v4: Int, v6: Int): String = "TTL_OFF v4=$v4 v6=$v6 result=${if (v4 == 0 && v6 == 0) "OK" else "FAILED"}"

    /** При включённом тумблере восстанавливаем правила при загрузке (строка в лог). */
    fun bootLine(v4: Int, v6: Int): String =
        "TTL_BOOT rules_restored=${if (v4 > 0 && v6 > 0) "yes" else "no"} v4=$v4 v6=$v6 scoped=true"
}
