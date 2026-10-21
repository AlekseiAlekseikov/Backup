package com.example.voiceapp3.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import com.example.voiceapp3.MainActivity
import com.example.voiceapp3.R
import java.lang.ref.WeakReference

/**
 * Статус-иконки на панели уведомлений и в строке состояния —
 * функциональный порт CentralEXAuto (класс `w0.kg`, тег лога `KmIconProbe`,
 * плюс рабочие циклы `CentralWatchdogService` / `w0.r2`).
 *
 * Переносимо (§5, §6 ТЗ):
 * - Панель уведомлений: Батарея (SOC), Наружная температура, Wi‑Fi.
 * - Строка состояния: hvac_digital_batt / hvac_digital_temp / hvac_digital_ext_temp
 *   через `persist.sys.hvacdigital.*` (Flyme SystemUI plugin — если есть).
 *
 * НЕ переносится: «Сигнал на панели» (id 15030) — ключ и его обработка в срез не вошли.
 *
 * Канал записи: settings.change → ToolsBackend → persist + apply + readback
 * → tools.state → cxNote. Откат оптимизма при FAILED.
 */
object StatusIcons {

    private const val TAG = "StatusIcons"

    // ──────────────────────────────────────────────────────────────
    // Константы, восстановленные из APK (w0.kg / CentralWatchdogService)
    // ──────────────────────────────────────────────────────────────

    /** ID нотификации Батарея (kg.j -> 15020, icon 97, rank 10). */
    const val ID_BATTERY = 15020
    /** ID нотификации Наружная температура (kg.j -> 15021, icon 97, rank 20). */
    const val ID_EXT_TEMP = 15021
    /** ID нотификации Wi‑Fi (CentralWatchdogService.u0 -> 15024, icon 98, rank 31). */
    const val ID_WIFI = 15024

    /** ID яркости (для полноты — kg.j -> 15022, icon 99, rank 30). */
    const val ID_BRIGHTNESS = 15022

    /** Канал Батарея/Температура (central_ex_status_temp). */
    const val CHANNEL_TEMP = "central_ex_status_temp"
    /** Канал Wi‑Fi (central_ex_status_wifi). */
    const val CHANNEL_WIFI = "central_ex_status_wifi"

    /** Иконка Батарея/Температура (Politica.h(21) = 97). */
    const val ICON_ID_TEMP = 97
    /** Иконка Wi‑Fi (жёстко 98 в APK). */
    const val ICON_ID_WIFI = 98
    /** Иконка Яркость (жёстко 99 в APK). */
    const val ICON_ID_BRIGHTNESS = 99

    /** Ранги (порядок на панели). */
    const val RANK_BATTERY = 10
    const val RANK_EXT_TEMP = 20
    const val RANK_WIFI = 31
    const val RANK_BRIGHTNESS = 30

    /** Ключи SharedPreferences (как в w0.kc cases 7/8/9). */
    const val PREF_BATTERY = "status_battery_enabled"
    const val PREF_EXT_TEMP = "status_temp_ext_enabled"
    const val PREF_WIFI = "status_wifi_enabled"

    /** Ключи строки состояния (hvac_digital_* — §6). */
    const val PREF_HVAC_BATT = "hvac_digital_batt"
    const val PREF_HVAC_TEMP = "hvac_digital_temp"
    const val PREF_HVAC_EXT_TEMP = "hvac_digital_ext_temp"

    /** Системные свойства hvac_digital_* (Flyme SystemUI plugin). */
    const val SYS_HVAC_BATT = "persist.sys.hvacdigital.batt"
    const val SYS_HVAC_TEMP = "persist.sys.hvacdigital.temp"
    const val SYS_HVAC_EXT_TEMP = "persist.sys.hvacdigital.ext_temp"

    /** Период синхронизации из APK (r2 case 10: handler.postDelayed 15000L). */
    const val SYNC_INTERVAL_MS = 15000L

    // ──────────────────────────────────────────────────────────────
    // VHAL property IDs (Politica.h indices → CentralEXAuto mapping)
    // ──────────────────────────────────────────────────────────────

    /** SOC (тяговая батарея) — Politica.h(21) = 0x16400b00 (373295872). */
    const val VHAL_SOC = 0x16400b00
    /** Наружная температура — Politica.h(23). */
    const val VHAL_EXT_TEMP = 0x16400c00

    // ──────────────────────────────────────────────────────────────
    // Внутреннее состояние
    // ──────────────────────────────────────────────────────────────

    private var carPropertyManagerRef: WeakReference<android.car.hardware.property.CarPropertyManager>? = null
    private var syncHandler: android.os.Handler? = null
    private val syncRunnable = Runnable { syncAll() }

    // ──────────────────────────────────────────────────────────────
    // Публичный API
    // ──────────────────────────────────────────────────────────────

    /**
     * Постить/обновить иконку Батареи.
     * @param soc Процент заряда 0..100 (null — скрыть/очистить).
     */
    fun postBattery(ctx: Context, soc: Int?) {
        if (soc == null) {
            cancel(ctx, ID_BATTERY)
            return
        }
        val clamped = soc.coerceIn(0, 100)
        val color = when {
            clamped > 80 -> Color.GREEN        // зелёный > 80%
            clamped >= 20 -> Color.WHITE       // белый 20–80%
            else -> Color.rgb(255, 165, 0)     // оранжевый < 20%
        }
        val text = "$clamped%"
        val icon = makeTextIcon(ctx, text, "", color, isBattery = true)
        val pending = PendingIntent.getActivity(
            ctx, ICON_ID_TEMP,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        postStatusIcon(
            ctx, ID_BATTERY, ICON_ID_TEMP, RANK_BATTERY,
            "Батарея",
            "Батарея: $text",
            text, icon, pending, CHANNEL_TEMP,
            "Внешняя температура (панель)"
        )
    }

    /**
     * Постить/обновить иконку Наружной температуры.
     * @param tempC Температура в °C (null — скрыть/очистить).
     */
    fun postExtTemp(ctx: Context, tempC: Int?) {
        if (tempC == null) {
            cancel(ctx, ID_EXT_TEMP)
            return
        }
        val text = "${tempC}°C"
        val icon = makeTextIcon(ctx, "${tempC}°", "C", Color.WHITE, isBattery = false)
        val pending = PendingIntent.getActivity(
            ctx, ICON_ID_TEMP,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        postStatusIcon(
            ctx, ID_EXT_TEMP, ICON_ID_TEMP, RANK_EXT_TEMP,
            "Внешняя температура",
            "Снаружи: $tempC °C",
            text, icon, pending, CHANNEL_TEMP,
            "Внешняя температура (панель)"
        )
    }

    /**
     * Постить/обновить иконку Wi‑Fi.
     * @param state 0=off, 1=searching, 2=connected (как lg.ordinal в APK).
     * @param rssi Уровень сигнала в dBm (для connected), используется для баров.
     */
    fun postWifi(ctx: Context, state: Int, rssi: Int = -100) {
        if (state == 0) {
            cancel(ctx, ID_WIFI)
            return
        }
        val iconColor: Int
        val arcCount: Int
        val arcColorConnected: Int
        val arcColorDisconnected: Int

        when (state) {
            1 -> {
                iconColor = Color.argb(140, 255, 255, 255)
                arcCount = 0
                arcColorConnected = Color.argb(70, 255, 255, 255)
                arcColorDisconnected = Color.argb(140, 255, 255, 255)
            }
            2 -> {
                iconColor = Color.rgb(255, 69, 0)
                arcCount = 1
                arcColorConnected = Color.WHITE
                arcColorDisconnected = Color.argb(140, 255, 255, 255)
            }
            3 -> {
                iconColor = Color.rgb(255, 165, 0)
                arcCount = 2
                arcColorConnected = Color.WHITE
                arcColorDisconnected = Color.argb(140, 255, 255, 255)
            }
            4 -> {
                iconColor = Color.YELLOW
                arcCount = 3
                arcColorConnected = Color.WHITE
                arcColorDisconnected = Color.argb(140, 255, 255, 255)
            }
            5 -> {
                iconColor = Color.GREEN
                arcCount = 4
                arcColorConnected = Color.WHITE
                arcColorDisconnected = Color.argb(140, 255, 255, 255)
            }
            else -> {
                iconColor = Color.WHITE
                arcCount = 5
                arcColorConnected = Color.WHITE
                arcColorDisconnected = Color.argb(140, 255, 255, 255)
            }
        }

        val icon = makeWifiIcon(ctx, arcCount, iconColor, arcColorConnected, arcColorDisconnected)

        val title = "WiFi"
        val contentText = when (state) {
            1 -> "Выключено — нажмите, чтобы включить и открыть меню"
            2 -> "Включено, нет соединения — нажмите, чтобы открыть меню"
            else -> "Включено — нажмите, чтобы открыть меню"
        }

        val pending = PendingIntent.getBroadcast(
            ctx, ICON_ID_WIFI,
            Intent("com.example.voiceapp3.ACTION_APPLY_STATUS_WIFI"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        postStatusIcon(
            ctx, ID_WIFI, ICON_ID_WIFI, RANK_WIFI,
            title,
            contentText,
            "wifi_$state", icon, pending, CHANNEL_WIFI,
            "WiFi (панель)"
        )
    }

    /** Отменить конкретную иконку по ID. */
    fun cancel(ctx: Context, id: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(id)
        Log.i(TAG, "cancel id=$id")
    }

    /** Отменить все статус-иконки среза №3 (батарея, темп, wifi). */
    fun cancelAll(ctx: Context) {
        cancel(ctx, ID_BATTERY)
        cancel(ctx, ID_EXT_TEMP)
        cancel(ctx, ID_WIFI)
        Log.i(TAG, "cancelAll")
    }

    /** Синхронизировать все включённые иконки: прочитать VHAL, обновить нотификации. */
    fun syncAll(ctx: Context? = null) {
        val target = ctx?.applicationContext ?: appContext ?: return
        initContext(target)
        val cpm = getCarPropertyManager(target)
        if (cpm != null) {
            if (isEnabled(target, PREF_BATTERY)) {
                val soc = readSoc(cpm)
                postBattery(target, soc)
            }
            if (isEnabled(target, PREF_EXT_TEMP)) {
                val temp = readExtTemp(cpm)
                postExtTemp(target, temp)
            }
        }
        if (isEnabled(target, PREF_WIFI)) {
            updateWifiFromSystem(target)
        }
    }

    /** Запустить периодическую синхронизацию (как handler в CentralWatchdogService). */
    fun startPeriodicSync(ctx: Context) {
        initContext(ctx)
        if (syncHandler != null) return
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        syncHandler = h
        h.postDelayed(syncRunnable, SYNC_INTERVAL_MS)
        Log.i(TAG, "periodic sync started (${SYNC_INTERVAL_MS}ms)")
    }

    /** Остановить периодическую синхронизацию. */
    fun stopPeriodicSync() {
        syncHandler?.removeCallbacks(syncRunnable)
        syncHandler = null
        Log.i(TAG, "periodic sync stopped")
    }

    /** Применить настройку hvac_digital_* (строка состояния). */
    fun setHvacDigital(ctx: Context, key: String, enabled: Boolean): ToolsResult {
        initContext(ctx)
        val (sysProp, title) = when (key) {
            PREF_HVAC_BATT -> SYS_HVAC_BATT to "Процент заряда"
            PREF_HVAC_TEMP -> SYS_HVAC_TEMP to "Цифровой климат-контроль"
            PREF_HVAC_EXT_TEMP -> SYS_HVAC_EXT_TEMP to "Цифровая наружная температура"
            else -> return ToolsResult(
                action = "settings.$key",
                title = key,
                steps = listOf(ToolsStep(
                    step = "validate", command = "hvac_digital key",
                    startedAtMs = 0, finishedAtMs = 0, exitCode = 1,
                    stderr = "unknown key $key", status = ToolsStep.Status.FAILED
                ))
            )
        }

        val steps = ArrayList<ToolsStep>(3)
        val isBatt = if (enabled) "1" else "0"

        // 1) persist: SharedPreferences (гарантия сохранения выбора пользователя) + SystemProperties
        steps += toolsStep(step = "persist", command = "prefs.putBoolean($key) + SystemProperties.set($sysProp, $isBatt)", expected = isBatt) {
            ctx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
                .edit().putBoolean(key, enabled).putString(key + "_str", isBatt).commit()
            var propOk = false
            try {
                val cls = Class.forName("android.os.SystemProperties")
                val set = cls.getMethod("set", String::class.java, String::class.java)
                set.invoke(null, sysProp, isBatt)
                propOk = true
            } catch (_: Exception) { }
            true to (if (propOk) "pref + sysprop written" else "pref written (sysprop restricted)")
        }

        // 2) подтверждение применения
        steps += toolsStep(step = "confirm", command = "prefs.getBoolean()", expected = isBatt) {
            val prefVal = ctx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE).getBoolean(key, enabled)
            true to if (prefVal) "1" else "0"
        }

        // 3) try restart SystemUI plugin (Flyme)
        val pluginPkg = "com.flyme.auto.systemuiplugin"
        val pm = ctx.packageManager
        val pluginExists = try {
            pm.getPackageInfo(pluginPkg, 0)
            true
        } catch (e: Exception) {
            false
        }

        if (pluginExists) {
            steps += toolsStep(step = "restart_sysui", command = "pm disable/enable $pluginPkg") {
                try {
                    pm.setApplicationEnabledSetting(pluginPkg, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0)
                    pm.setApplicationEnabledSetting(pluginPkg, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0)
                    true to "SystemUI plugin restarted"
                } catch (e: Exception) {
                    false to "plugin restart failed: ${e.message}"
                }
            }
        } else {
            steps += stepOf(
                step = "restart_sysui",
                command = "Flyme SystemUI plugin check",
                status = ToolsStep.Status.PARTIAL,
                stderr = "plugin $pluginPkg not installed — hvac_digital persists but live effect unavailable"
            )
        }

        return ToolsResult(
            action = "settings.$key",
            title = title,
            steps = steps
        )
    }

    /** Прочитать текущее значение hvac_digital_*. */
    fun getHvacDigital(ctx: Context, key: String): Boolean {
        val prefs = ctx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
        if (prefs.contains(key)) {
            return prefs.getBoolean(key, false)
        }
        val sysProp = when (key) {
            PREF_HVAC_BATT -> SYS_HVAC_BATT
            PREF_HVAC_TEMP -> SYS_HVAC_TEMP
            PREF_HVAC_EXT_TEMP -> SYS_HVAC_EXT_TEMP
            else -> return false
        }
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java, String::class.java)
            val v = (get.invoke(null, sysProp, "0") as String?) ?: "0"
            v == "1"
        } catch (_: Exception) {
            false
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Внутренние вспомогательные
    // ──────────────────────────────────────────────────────────────

    @Volatile
    private var appContext: Context? = null

    fun initContext(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
    }

    private fun isEnabled(ctx: Context, prefKey: String): Boolean =
        ctx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE).getBoolean(prefKey, false)

    private fun getCarPropertyManager(ctx: Context): android.car.hardware.property.CarPropertyManager? {
        val ref = carPropertyManagerRef
        val existing = ref?.get()
        if (existing != null) return existing
        try {
            // На этом ГУ работает Car.createCar(context) без ServiceConnection
            val car = android.car.Car.createCar(ctx)
            val cpm = car?.getCarManager(android.car.Car.PROPERTY_SERVICE) as? android.car.hardware.property.CarPropertyManager
            if (cpm != null) {
                carPropertyManagerRef = WeakReference(cpm)
            }
            return cpm
        } catch (e: Throwable) {
            Log.w(TAG, "Car.createCar failed: ${e.message}")
            return null
        }
    }

    private fun readSoc(cpm: android.car.hardware.property.CarPropertyManager): Int? {
        return try {
            for (area in intArrayOf(0, 1)) {
                val prop = cpm.getProperty<Any>(VHAL_SOC, area)
                val value = prop?.value
                if (value is Number) {
                    val v = value.toInt()
                    if (v in 0..100) return v
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun readExtTemp(cpm: android.car.hardware.property.CarPropertyManager): Int? {
        return try {
            for (area in intArrayOf(0, 1)) {
                val prop = cpm.getProperty<Any>(VHAL_EXT_TEMP, area)
                val value = prop?.value
                if (value is Number) {
                    return value.toInt()
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun updateWifiFromSystem(ctx: Context) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (wm == null) {
            postWifi(ctx, 0)
            return
        }
        if (!wm.isWifiEnabled) {
            postWifi(ctx, 1)
            return
        }
        val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val ni = cm?.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI)
        if (ni != null && ni.isConnected) {
            val info = wm.connectionInfo
            val rssi = info?.rssi ?: -100
            val level = android.net.wifi.WifiManager.calculateSignalLevel(rssi, 5)
            val state = level + 2
            postWifi(ctx, state, rssi)
        } else {
            postWifi(ctx, 1)
        }
    }

    /** Генерация иконки с текстом (порт kg.a для батареи/температуры). */
    private fun makeTextIcon(
        ctx: Context,
        mainText: String,
        subText: String,
        color: Int,
        isBattery: Boolean
    ): Icon {
        val density = ctx.resources.displayMetrics.density
        val baseSize = (56 * density).toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.DEFAULT
            textSize = baseSize.toFloat()
        }
        val mainWidth = paint.measureText(mainText)
        var subWidth = 0f
        var gap = 0
        if (subText.isNotEmpty()) {
            paint.textSize = (baseSize * 0.55f).toInt().toFloat()
            subWidth = paint.measureText(subText)
            gap = ((if (isBattery) 3 else 1) * density).toInt()
        }

        val fontMetrics = paint.fontMetrics
        val height = ((fontMetrics.descent - fontMetrics.ascent) / 0.61f).toInt().coerceAtLeast(1)
        val padding = ((if (isBattery) 10 else 0) * density).toInt()
        val iconWidth = (padding * 2 + mainWidth + gap + subWidth).toInt()

        val bmp = Bitmap.createBitmap(iconWidth.coerceAtLeast(1), height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val y = ((height - (fontMetrics.descent - fontMetrics.ascent)) / 2f) - fontMetrics.ascent
        val xStart = padding + ((iconWidth - padding * 2 - mainWidth - gap - subWidth) / 2f).coerceAtLeast(0f)
        paint.textSize = baseSize.toFloat()
        canvas.drawText(mainText, xStart, y, paint)
        if (subText.isNotEmpty()) {
            paint.textSize = (baseSize * 0.55f).toInt().toFloat()
            canvas.drawText(subText, xStart + mainWidth + gap, y, paint)
        }
        return Icon.createWithBitmap(bmp)
    }

    /** Генерация иконки Wi‑Fi (круг + дуги, порт CentralWatchdogService.u0). */
    private fun makeWifiIcon(
        ctx: Context,
        arcCount: Int,
        centerColor: Int,
        arcColorOn: Int,
        arcColorOff: Int
    ): Icon {
        val density = ctx.resources.displayMetrics.density
        val size = (36 * density).toInt().coerceAtLeast(1)
        val radius = size / 2f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = centerColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(radius, radius * 1.3f, 0.07f * size, centerPaint)

        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.075f * size
            strokeCap = Paint.Cap.ROUND
        }
        for (i in 1..4) {
            val arcRadius = ((i * 0.105f) + 0.1f) * size
            val rect = android.graphics.RectF(
                radius - arcRadius, radius * 1.3f - arcRadius,
                radius + arcRadius, radius * 1.3f + arcRadius
            )
            arcPaint.color = if (i <= arcCount) arcColorOn else arcColorOff
            canvas.drawArc(rect, 215f, 110f, false, arcPaint)
        }
        return Icon.createWithBitmap(bmp)
    }

    /** Постинг нотификации с flag_status_icon_* extras (порт kg.j). */
    private fun postStatusIcon(
        ctx: Context,
        notifId: Int,
        iconId: Int,
        rank: Int,
        title: String,
        contentText: String,
        describe: String,
        icon: Icon,
        pendingIntent: PendingIntent,
        channelId: String,
        channelName: String
    ) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }

        val builder = Notification.Builder(ctx, channelId)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)

        val notification = builder.build()
        val extras = notification.extras
        extras.putBoolean("flag_status_icon_notification", true)
        extras.putInt("flag_status_icon_id", iconId)
        extras.putString("flag_status_icon_describe", describe)
        extras.putParcelable("flag_status_icon_icon", icon)
        extras.putParcelable("flag_status_icon_pressed_icon", icon)
        extras.putBoolean("flag_status_icon_hide", false)
        extras.putInt("flag_status_icon_rank", rank)
        extras.putInt("flag_status_icon_space_x", 1)
        extras.putBoolean("flag_status_icon_is_pick_on", false)
        extras.putInt("flag_status_icon_specific_width", 0)

        try {
            nm.notify(notifId, notification)
            Log.i(TAG, "postStatusIcon id=$notifId iconId=$iconId rank=$rank describe=$describe")
        } catch (e: Exception) {
            Log.w(TAG, "notify failed id=$notifId", e)
        }
    }

    internal fun stepOf(
        step: String,
        command: String,
        status: ToolsStep.Status,
        expected: String = "",
        actual: String = "",
        stdout: String = "",
        stderr: String = ""
    ): ToolsStep {
        val t = android.os.SystemClock.elapsedRealtime()
        return ToolsStep(
            step = step,
            command = command,
            startedAtMs = t,
            finishedAtMs = t,
            exitCode = if (status == ToolsStep.Status.OK) 0 else 1,
            stdout = stdout,
            stderr = stderr,
            expected = expected,
            actual = actual,
            status = status
        )
    }
}
