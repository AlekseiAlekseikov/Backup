package com.example.voiceapp3.tools

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Живой детектор темы головного устройства (день/ночь).
 *
 * Баг предыдущей реализации: «Следовать теме ГУ» читало значение из
 * SharedPreferences (`ui_head_unit_theme_mode`), которое устанавливал
 * СЕГМЕНТНЫЙ ПЕРЕКЛЮЧАТЕЛЬ на экране, а не фактическую ночь/день ГУ.
 * В результате при смене фар (день↔ночь) тема приложения НЕ менялась сама.
 *
 * В оригинале CentralEXAuto используется `(resources.configuration.uiMode
 * & UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES` (w0.m.b) — там нет отдельного
 * системного свойства для темы, вся индикация «ночи» завязана на
 * Configuration.UI_MODE_NIGHT_YES, который Android обновляет при переходе
 * автомобиля в ночной режим (фары / туннель / расписание). На Geely EX2 с
 * Flyme SystemUI эта конфигурация меняется в реальном времени и рассылается
 * широковещательно ACTION_CONFIGURATION_CHANGED.
 *
 * Этот объект:
 *  1. Всегда отвечает правду через [isNight] (uiMode приложения).
 *  2. Хранит список слушателей и оповещает их ПРИ КАЖДОЙ СМЕНЕ конфигурации
 *     (не только на старте Activity).
 *  3. Автоматически регистрирует один BroadcastReceiver на контекст приложения
 *     и один раз — после первого подписчика.
 */
object HuNightMode {

    private const val TAG = "HuNightMode"

    /** Чтение без подписки: текущее значение дня/ночи ГУ. */
    fun isNight(ctx: Context): Boolean {
        val uiMode = ctx.resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Строковый псевдоним как в CentralEXAuto: "dark" / "light". Используется
     * там, где в префах ожидалось «тёмная/светлая», а также для отладки.
     */
    fun modeName(ctx: Context): String = if (isNight(ctx)) "dark" else "light"

    private val listeners = LinkedHashSet<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var registered = false
    private var appContext: Context? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_CONFIGURATION_CHANGED) return
            val now = isNight(context)
            if (now == lastValue) return
            lastValue = now
            Log.i(TAG, "HU night mode changed → $now (mode=${modeName(context)})")
            val copy = ArrayList(listeners)
            mainHandler.post {
                copy.forEach { listener ->
                    runCatching { listener() }
                        .onFailure { Log.w(TAG, "night-mode listener threw", it) }
                }
            }
        }
    }

    @Volatile private var lastValue: Boolean? = null

    /** Подписаться на живые смены темы ГУ. Слушатель вызывается на UI-потоке. */
    fun observe(ctx: Context, listener: () -> Unit) {
        ensureRegistered(ctx.applicationContext)
        listeners.add(listener)
    }

    /** Отписать — например, при уничтожении Activity, чтобы не держать утечку. */
    fun remove(listener: () -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    private fun ensureRegistered(ctx: Context) {
        if (registered) return
        appContext = ctx
        lastValue = isNight(ctx)
        val filter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
        // Регистрируем на контексте приложения — приёмник живёт пока процесс жив.
        runCatching {
            ctx.registerReceiver(receiver, filter)
            registered = true
            Log.i(TAG, "Registered ACTION_CONFIGURATION_CHANGED receiver (initial night=$lastValue)")
        }.onFailure {
            // Фолбэк: опрашиваем раз в 30 секунд.
            Log.w(TAG, "Failed to register configuration receiver, polling as fallback", it)
            mainHandler.postDelayed(pollTick, 30_000L)
            registered = true
        }
    }

    private val pollTick = object : Runnable {
        override fun run() {
            val ctx = appContext ?: return
            val now = isNight(ctx)
            if (now != lastValue) {
                lastValue = now
                listeners.forEach { runCatching { it() } }
            }
            mainHandler.postDelayed(this, 30_000L)
        }
    }
}
