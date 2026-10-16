package com.example.voiceapp3.nav.routing

/**
 * Этап 7 / стратегическая переоценка 2026-10-06 (решение: ОСТАВИТЬ ТЕКУЩИЙ
 * СТЕК; роутинг — on-device GraphHopper за интерфейсом RoutingProvider).
 *
 * ДОМЕННЫЕ модели маршрутизации GeelyTools. Они НЕ зависят от классов
 * GraphHopper/Valhalla/любого провайдера — движок конвертирует в них свой
 * ответ. Только честные поля: без трафика, без выдуманных значений.
 */

data class RoutePoint(val lat: Double, val lon: Double)

/** Один манёвр по ходу маршрута. */
data class Maneuver(
    /** Угол для стрелки хрома: 0=прямо, +направо, −налево (градусы). */
    val arrowDeg: Float,
    /** Готовый текст инструкции (из перевода движка, ru). */
    val text: String,
    /** Точка, к которой привязан манёвр. */
    val point: RoutePoint,
    /** Длина участка, который ведёт к СЛЕДУЮЩЕМУ манёвру (метры). */
    val legDistanceM: Double
)

/** Итог успешного построения. */
data class RouteResult(
    /** Вся геометрия (полилиния снапнута к дорогам движка). */
    val points: List<RoutePoint>,
    val distanceM: Double,
    val timeMs: Long,
    val maneuvers: List<Maneuver>,
    /** Сколько альтернатив вернул движок помимо основной (честно, ≥0). */
    val alternativesCount: Int
)

/** Причины, по которым маршрут честно не построился. */
sealed class RoutingError(message: String) : Exception(message) {
    class GraphNotReady(detail: String) : RoutingError("граф маршрутов не готов: $detail")
    class NoRoute(detail: String) : RoutingError("маршрут не найден: $detail")
    class Engine(detail: String, cause: Throwable? = null) :
        RoutingError("ошибка движка: $detail") { init { cause?.let { initCause(it) } } }
}
