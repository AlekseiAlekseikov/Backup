package com.example.voiceapp3.nav.search

/**
 * Stage NAV-SEARCH (этап 6): единственная точка конфигурации поискового
 * эндпоинта (решение D2: до VPS — публичный инстанс; после — self-host
 * Photon; переезд = одна строка, весь остальной контур не меняется).
 *
 * Публичный photon.komoot.io: Photon API GeoJSON (`/api?q=…&limit=…`).
 * Дисциплина обращения (не злоупотребляем): debounce 350 мс, отмена
 * in-flight, LRU-кэш 10 мин, честный User-Agent — всё в PhotonSearchClient.
 */
object SearchEndpoints {
    const val SEARCH_URL = "https://photon.komoot.io/api"
}
