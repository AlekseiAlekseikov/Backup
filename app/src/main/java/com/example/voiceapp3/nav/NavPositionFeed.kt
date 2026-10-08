package com.example.voiceapp3.nav

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Stage NAV-MAP (этап 4 переписи навигации).
 *
 * Источник позиции карты — ТОЛЬКО существующий контур телеметрии: 1 Гц опрос
 * TelemetryRuntime.locationStatusMap() (тот самый GT-шов над
 * LocationSignalProvider, что несёт live-фрейм под ключом "location").
 * Новых LocationManager-подписок здесь НЕ создаётся — GPS уже слушает
 * телеметрия (1 Гц, GPS→network), а правило «один слушатель геолокации»
 * не нарушается. Код в com.timhss.capyenergy.* этим классом не изменяется.
 *
 * Коллектор телеметрии поднимается так же, как это делает встроенная
 * телеметрия: TelemetryCollectorService.start() на старте фида. Если движок
 * ещё не готов — тик пропускается и пробует снова, карта живёт своей жизнью.
 *
 * Фид работает ровно пока открыта вкладка «Навигация» (show/hide хоста),
 * опрос — вне UI-потока, выдача снимка — на главный.
 */
class NavPositionFeed(
    context: Context,
    private val onFix: (PositionSnapshot) -> Unit
) {

    /**
     * Снимок состояния геолокации для слоя карты. Поля, которых датчик не дал,
     * — null; hasFix=false — позицию не рисуем вовсе (не «угадываем»).
     */
    data class PositionSnapshot(
        val latitude: Double?,
        val longitude: Double?,
        val accuracyM: Float?,
        val bearingDeg: Float?,
        val speedMps: Float?,
        val fixAgeMillis: Long?,
        val running: Boolean,
        val permissionGranted: Boolean,
        val providerEnabled: Boolean,
        val provider: String?
    ) {
        val hasFix: Boolean get() = latitude != null && longitude != null
    }

    private val appContext = context.applicationContext
    private val executor =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nav-pos-feed") }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var task: ScheduledFuture<*>? = null

    /** Первый тик — сразу, дальше каждую секунду (ровно частота фиксов провайдера). */
    fun start() {
        if (task != null) return
        // Без коллектора LocationSignalProvider не работает — фиксов не будет.
        com.timhss.capyenergy.service.TelemetryCollectorService.start(appContext)
        task = executor.scheduleAtFixedRate({ tick() }, 0, TICK_MILLIS, TimeUnit.MILLISECONDS)
        Log.i(TAG, "position feed started")
    }

    fun stop() {
        task?.cancel(false)
        task = null
        Log.i(TAG, "position feed stopped")
    }

    /** Полная остановка исполнителя — только из NavigationHostController.dispose(). */
    fun dispose() {
        stop()
        executor.shutdown()
    }

    private fun tick() {
        val snap = runCatching {
            val m = com.timhss.capyenergy.telemetry.TelemetryRuntime
                .get(appContext)
                .locationStatusMap()
            PositionSnapshot(
                latitude = m["latitude"] as? Double,
                longitude = m["longitude"] as? Double,
                accuracyM = (m["gpsAccuracyM"] as? Number)?.toFloat(),
                bearingDeg = (m["bearingDeg"] as? Number)?.toFloat(),
                speedMps = (m["speedMps"] as? Number)?.toFloat(),
                fixAgeMillis = (m["lastFixAgeMillis"] as? Number)?.toLong(),
                running = m["running"] as? Boolean == true,
                permissionGranted = m["permissionGranted"] as? Boolean == true,
                providerEnabled = (m["gpsProviderEnabled"] as? Boolean == true) ||
                    (m["networkProviderEnabled"] as? Boolean == true),
                provider = m["locationProvider"] as? String
            )
        }.getOrElse {
            Log.w(TAG, "telemetry runtime unavailable: ${it.message}")
            return
        }
        main.post { onFix(snap) }
    }

    companion object {
        private const val TAG = "NavPosFeed"
        private const val TICK_MILLIS = 1_000L
    }
}
