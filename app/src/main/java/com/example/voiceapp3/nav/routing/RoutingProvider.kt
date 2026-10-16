package com.example.voiceapp3.nav.routing

/**
 * Provider-neutral интерфейс маршрутизации (§13 отчёта переоценки).
 * Реализации: GraphHopperRoutingProvider (on-device, этап 7) → позже
 * ValhallaRoutingProvider (self-host VPS, если/когда решено). Хост знает
 * только этот интерфейс, замена движка = новая реализация, не перепись.
 */
interface RoutingProvider {
    /**
     * Построить маршрут (СИНХРОННО — вызывать на рабочем потоке).
     * Бросает RoutingError: GraphNotReady, NoRoute, Engine.
     */
    @Throws(RoutingError::class)
    fun route(from: RoutePoint, to: RoutePoint): RouteResult

    /** Готов ли движок строить прямо сейчас (без блокировок). */
    fun isReady(): Boolean

    /** Освободить ресурсы движка (закрыть граф). */
    fun close()
}
