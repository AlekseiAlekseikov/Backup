package com.example.voiceapp3.nav.search

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stage NAV-SEARCH (этап 6): модель найденной точки.
 *
 * Источники результата:
 *  · ONLINE — Photon (онлайн-геокодер, эндпоинт конфигурится одной константой
 *    для переезда на будущий VPS, D2);
 *  · COORD  — ввод координат руками («53.90, 27.56»);
 *  · экземпляры истории/избранного не дублируются — это те же SearchResult,
 *    сохранённые в prefs (SearchHistoryStore).
 */
data class SearchResult(
    /** Стабильный ключ дедупликации/избранного: «osm:node:123» или «coord:lat,lon». */
    val key: String,
    val name: String,
    /** Вторая строка: «улица, дом · район · город · страна» (что есть). */
    val subtitle: String,
    val lat: Double,
    val lon: Double,
    /** osm_value из Photon («fuel», «restaurant»…): даёт честный emoji типа. */
    val osmValue: String = ""
) {
    /** Emoji аватар по типу объекта (макет § search items: 🛒/⛽/…). */
    val emoji: String get() = emojiForOsmValue(osmValue)

    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("key", key)
        .put("name", name)
        .put("subtitle", subtitle)
        .put("lat", lat)
        .put("lon", lon)
        .put("osmValue", osmValue)

    companion object {
        fun fromJson(o: org.json.JSONObject): SearchResult? = runCatching {
            SearchResult(
                key = o.getString("key"),
                name = o.getString("name"),
                subtitle = o.optString("subtitle"),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                osmValue = o.optString("osmValue")
            )
        }.getOrNull()

        /** Таблица типов → emoji (только факты из данных Photon; без демо-подписок). */
        fun emojiForOsmValue(v: String): String = when (v) {
            "fuel" -> "⛽"
            "charging_station" -> "⚡"
            "restaurant", "cafe", "fast_food", "bar", "pub" -> "🍔"
            "parking" -> "🅿️"
            "supermarket", "convenience", "mall", "department_store", "shop" -> "🛒"
            "hotel", "motel", "guest_house", "hostel" -> "🏨"
            "pharmacy" -> "🏥"
            "hospital", "clinic", "doctors", "dentist" -> "🏥"
            "car_repair", "car_wash", "tyres" -> "🔧"
            "bank", "atm" -> "🏦"
            "school", "university", "kindergarten" -> "🎓"
            "place_of_worship" -> "⛪"
            "cinema", "theatre" -> "🎭"
            "stadium", "sports_centre", "pitch" -> "🏟"
            "park", "playground", "garden" -> "🌳"
            "bus_station", "bus_stop", "station", "halt" -> "🚌"
            "city", "town" -> "🏙"
            "village", "hamlet" -> "🏡"
            "house", "building", "address" -> "🏠"
            else -> "📍"
        }

        /** Расстояние точка-точка (гаверсин, метры). */
        fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(bLat - aLat)
            val dLon = Math.toRadians(bLon - aLon)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) *
                sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * asin(sqrt(h))
        }

        /** «1,2 км» / «350 м» — русский формат одной значащей дробью. */
        fun formatDistance(meters: Double?): String {
            meters ?: return ""
            return if (meters >= 995) {
                val km = meters / 1000.0
                val txt = if (km < 10) String.format(Locale.US, "%.1f", km)
                else km.roundToInt().toString()
                txt.replace('.', ',') + " км"
            } else {
                (meters.roundToInt() / 10 * 10).toString() + " м"
            }
        }

        /**
         * Координатный ввод: «53.90, 27.56» / «53.90 27.56» → синтетический
         * результат. Семантика строго lat,lon (порядок фиксирован в UI-подсказке).
         */
        fun parseCoordinateQuery(q: String): SearchResult? {
            val m = COORD_RE.find(q.trim()) ?: return null
            val lat = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val lon = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return SearchResult(
                key = "coord:" + "%.5f".format(Locale.US, lat) + "," + "%.5f".format(Locale.US, lon),
                name = "Координаты",
                subtitle = String.format(Locale.US, "%.5f, %.5f", lat, lon),
                lat = lat,
                lon = lon
            )
        }

        private val COORD_RE =
            Regex("^\\s*(-?\\d{1,2}[.,]?\\d*)[\\s,;]+(-?\\d{1,3}[.,]?\\d*)\\s*$")
    }
}
