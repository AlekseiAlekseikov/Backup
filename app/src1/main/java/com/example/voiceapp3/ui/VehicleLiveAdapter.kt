package com.example.voiceapp3.ui

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.timhss.capyenergy.telemetry.TelemetryRuntime
import io.flutter.plugin.common.EventChannel
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * GeelyTools deviation (зафиксировано в отчёте миграции).
 *
 * Тонкий live-фид движка телеметрии для WebView-шелла GeelyTools. Полный
 * WebView-порт экранов телеметрии (TelemetryUiAdapter + DevLabController +
 * tel-*.js) удалён — экраны живут в нативном Flutter-приложении (точный порт
 * capy), чтобы существовал один источник истины.
 *
 * Шелл при этом остаётся потребителем ЖИВЫХ данных движка для собственных,
 * не-телеметрийных функций:
 *  - навигация: GPS-позиция, курс, скорость, запас хода, SoC;
 *  - карточка батареи и выноски кузова (капот/багажник/замок) — кадры
 *    telemetry.live (тот же payload, что у Flutter-канала …/telemetry/live);
 *  - вкладка CarPlay/AA: тумблер projectionBetaEnabled движка.
 *
 * Поток кадров идёт через отдельный экземпляр LiveTelemetryPublisher поверх
 * того же SignalStateStore (см. TelemetryRuntime.newLivePublisher) — подписка
 * Flutter-приложения не затрагивается. Коалесценция всплесков кадров — как у
 * прежнего моста: окно LIVE_FLUSH_MS на стороне адаптера, потому что дорогая
 * граница здесь — JSON-строка + evaluateJavascript.
 */
class VehicleLiveAdapter(
    context: Context,
    private val push: (name: String, json: String) -> Unit
) {

    private val appContext = context.applicationContext
    private val executor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "vehicle-live") }
    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "vehicle-live-flush") }

    @Volatile
    private var liveActive = false

    /** Последний кадр, ждущий отправки (коалесценция всплесков). */
    private val pendingFrame = AtomicReference<Any?>(null)

    @Volatile
    private var flushScheduled = false

    /** 1 Гц обновление пакета telemetry.data (gauges/range/status). */
    private var dataFuture: ScheduledFuture<*>? = null

    private val liveGson = GsonBuilder().serializeNulls().create()

    /** Собственный издатель поверх общего store (не трогает Flutter-канал). */
    @Volatile
    private var publisher: com.timhss.capyenergy.telemetry.LiveTelemetryPublisher? = null

    private val liveSink = object : EventChannel.EventSink {
        override fun success(event: Any?) {
            pendingFrame.set(event)
            if (!flushScheduled) {
                flushScheduled = true
                runCatching {
                    scheduler.schedule(::flushLive, LIVE_FLUSH_MS, TimeUnit.MILLISECONDS)
                }
            }
        }

        override fun error(errorCode: String?, errorMessage: String?, errorDetails: Any?) {
            Log.w(TAG, "live sink error: $errorCode $errorMessage")
        }

        override fun endOfStream() = Unit
    }

    private fun flushLive() {
        flushScheduled = false
        if (!liveActive) {
            pendingFrame.set(null)
            return
        }
        val frame = pendingFrame.getAndSet(null) ?: return
        runCatching { push("telemetry.live", liveGson.toJson(frame)) }
            .onFailure { Log.e(TAG, "live frame push failed", it) }
    }

    /** Приложение на экране: подписка на кадры + 1 Гц пакет данных. */
    fun startLive() {
        executor.execute {
            if (liveActive) return@execute
            val pub = runCatching {
                publisher ?: TelemetryRuntime.get(appContext).newLivePublisher()
                    .also { publisher = it }
            }.getOrElse {
                Log.e(TAG, "live publisher unavailable", it)
                return@execute
            }
            runCatching { pub.onListen(null, liveSink) }.onFailure {
                Log.e(TAG, "live subscribe failed", it)
                return@execute
            }
            liveActive = true
            dataFuture = scheduler.scheduleAtFixedRate({
                if (liveActive) {
                    executor.execute {
                        runCatching { pushData() }
                            .onFailure { Log.d(TAG, "data refresh failed: ${it.message}") }
                    }
                }
            }, DATA_INTERVAL_S, DATA_INTERVAL_S, TimeUnit.SECONDS)
            Log.i(TAG, "vehicle live feed started")
        }
    }

    /** Приложение ушло с экрана: отписка (не сэмплить шину в никуда). */
    fun stopLive() {
        executor.execute {
            if (!liveActive) return@execute
            liveActive = false
            dataFuture?.cancel(false)
            dataFuture = null
            pendingFrame.set(null)
            runCatching { publisher?.onCancel(null) }
            Log.i(TAG, "vehicle live feed stopped")
        }
    }

    /** UI запросил снимок (telemetry.request при загрузке страницы). */
    fun request() {
        executor.execute {
            runCatching { pushData() }
                .onFailure { Log.e(TAG, "telemetry.data build failed", it) }
        }
    }

    /**
     * Тумблер «CarPlay / Android Auto (бета)» вкладки «Инструменты»:
     * настройка живёт в движке (TelemetrySettings.projectionBetaEnabled),
     * её же читает ProjectionPresenceMonitor — один источник истины.
     */
    fun setProjectionBeta(enabled: Boolean) {
        executor.execute {
            runCatching {
                com.timhss.capyenergy.telemetry.TelemetrySettings(appContext)
                    .setProjectionBetaEnabled(enabled)
                // Как Flutter-путь (TelemetryBridge.setProjectionBetaEnabled):
                // фоновое отслеживание подключения телефона включается и
                // выключается живьём, а не после перезапуска сервиса.
                com.timhss.capyenergy.service.ProjectionAutoOpenSupervisor
                    .get(appContext).setEnabled(enabled)
                pushData()
            }.onFailure { Log.w(TAG, "setProjectionBeta failed", it) }
        }
    }

    fun dispose() {
        stopLive()
        executor.shutdown()
        scheduler.shutdown()
    }

    /**
     * Тонкий пакет telemetry.data: status / range / gauges / settings —
     * ровно те поля, которые читает оставшийся JS (навигация, быстрый
     * доступ, карточка батареи). Поездки/зарядки/серии/история — только
     * во Flutter-приложении.
     */
    private fun pushData() {
        val runtime = TelemetryRuntime.get(appContext)
        val root = JsonObject()

        val status = runtime.status()
        root.add("status", JsonObject().apply {
            addProperty("running", status.running)
            addProperty("activity", status.vehicleActivity.name)
            addProperty("tripState", status.tripState.name)
            addProperty("chargeState", status.chargeState.name)
            addProperty("signals", status.signalCount)
            addProperty("lastUpdate", status.lastUpdateMillis)
        })

        val range = runtime.rangeEstimate()
        root.add("range", JsonObject().apply {
            addNullable("carKm", range.carRangeKm)
            addNullable("soc", range.socPercent)
            addNullable("effKmPerKwh", range.efficiencyKmPerKwh)
            addNullable("capacityKwh", range.capacityKwh)
            addNullable("fullKm", range.fullRangeKm)
        })

        val signals = (runtime.latestSnapshotMap()["signals"] as? List<*>)
            ?.filterIsInstance<Map<*, *>>()
            ?.associate { (it["signalId"] as? String) to it["value"] }
            ?: emptyMap()

        fun dbl(x: Any?): Double? = (x as? Number)?.toDouble()
        val heading = runtime.heading()
        root.add("gauges", JsonObject().apply {
            addNullable("gradient", dbl(signals["ROAD_INCLINE"]))
            addNullable("heading", heading.bearingDeg?.toDouble())
            addNullable(
                "speedKmh",
                heading.speedMps?.let { it * 3.6 } ?: dbl(signals["VEHICLE_SPEED"])
            )
            runCatching {
                val loc = runtime.locationStatusMap()
                addNullable("lat", loc["latitude"] as? Double)
                addNullable("lon", loc["longitude"] as? Double)
                (loc["lastFixAgeMillis"] as? Long)?.let { addProperty("fixAgeMs", it) }
            }
            addNullable(
                "outsideTemp",
                dbl(signals["OUTSIDE_TEMPERATURE"]) ?: dbl(signals["AMBIENT_AIR_TEMPERATURE"])
            )
            addNullable("eff", range.efficiencyKmPerKwh)
        })

        val settings = runtime.settingsMap()
        root.add("settings", JsonObject().apply {
            addNullable("packWh", (settings["packCapacityWh"] as? Double))
            addProperty(
                "projectionBetaEnabled",
                settings["projectionBetaEnabled"] as? Boolean ?: false
            )
        })

        push("telemetry.data", root.toString())
    }

    private fun JsonObject.addNullable(key: String, value: Double?) {
        if (value != null) addProperty(key, value) else add(key, com.google.gson.JsonNull.INSTANCE)
    }

    private companion object {
        const val TAG = "VehicleLiveAdapter"
        const val LIVE_FLUSH_MS = 200L
        const val DATA_INTERVAL_S = 1L
    }
}
