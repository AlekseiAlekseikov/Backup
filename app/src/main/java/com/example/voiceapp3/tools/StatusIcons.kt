package com.example.voiceapp3.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.car.Car
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Иконки статус-бара Geely — порт w0.kg.j из CentralEXAuto.
 *
 * Совместимость API (используется ToolsBackend/VoiceAssistantService/UiBridge):
 *  - PREF_BATTERY / PREF_EXT_TEMP / PREF_WIFI — prefs-ключи для тумблеров панели уведомлений
 *  - PREF_HVAC_BATT / PREF_HVAC_TEMP / PREF_HVAC_EXT_TEMP — prefs-ключи для тумблеров
 *    "цифровой индикации" климата в статус-баре (через persist.sys.hvacdigital.*)
 *  - syncAll(context) — безаргументная, читает prefs сама
 *  - startPeriodicSync(context) / stopPeriodicSync() / cancelAll(context)
 *  - setHvacDigital(context, pref, value) / getHvacDigital(context, pref)
 *  - bootRestoreHvacDigital(context)
 *
 * Два отдельных notification-id: 15020 (батарея) и 15021 (температура) с разными
 * каналами — поэтому иконки больше не сливаются в одну.
 */
object StatusIcons {
    private const val TAG = "CENTRAL_StatusIcons"
    const val PREF_BATTERY = "status_icon_battery"
    const val PREF_EXT_TEMP = "status_icon_temp_ext"
    const val PREF_WIFI = "status_icon_wifi"

    /** Цифровая индикация в статус-баре (плагин GeelyAutoSystemUIPlugin-digital.apk).
     *  Флаги — persist.sys.hvacdigital.{batt,temp,temp_ext} (0/1). */
    const val PREF_HVAC_BATT = "hvacdigital_batt"
    const val PREF_HVAC_TEMP = "hvacdigital_temp"
    const val PREF_HVAC_EXT_TEMP = "hvacdigital_temp_ext"
    private const val SYS_HVAC_BATT = "persist.sys.hvacdigital.batt"
    private const val SYS_HVAC_TEMP = "persist.sys.hvacdigital.temp"
    // §3 регрессия exttemp/temp_ext: на устройстве владельца установлен плагин
    // com.flyme.auto.systemuiplugin, который слушает `persist.sys.hvacdigital.temp_ext`
    // (см. строки 469/471/473 голоса и log «setprop persist.sys.hvacdigital.temp_ext»).
    // В новых версиях CentralEXAuto (w0/x7.java, ah.java, u5.java) фигурирует имя
    // `persist.sys.hvacdigital.exttemp`. Для совместимости с ОБЕИМИ версиями плагина —
    // пишем В ОБА свойства сразу; при чтении наружной температуры в readTempScan пробуем
    // оба, предпочитая то, которое установлено.
    private const val SYS_HVAC_EXT_TEMP_EXT = "persist.sys.hvacdigital.temp_ext"   // старый плагин (на устройстве)
    private const val SYS_HVAC_EXT_EXTTEMP = "persist.sys.hvacdigital.exttemp"    // новый CentralEXAuto v3.3.9+
    @Deprecated("Use SYS_HVAC_EXT_TEMP_EXT/SYS_HVAC_EXT_EXTTEMP explicitly; kept for sysPropFor mapping")
    private const val SYS_HVAC_EXT = SYS_HVAC_EXT_TEMP_EXT

    private const val CHANNEL_BATT = "central_ex_status_batt"
    private const val CHANNEL_TEMP = "central_ex_status_temp"
    private const val CHANNEL_WIFI = "central_ex_status_wifi"
    private const val NOTIF_BATT = 15020
    private const val NOTIF_TEMP = 15021
    private const val NOTIF_WIFI = 15024
    private const val RANK_BATT = 10
    private const val RANK_TEMP = 20
    private const val RANK_WIFI = 31

    private val AREAS = intArrayOf(0, 1, 2, 3, 4, 7, 0x10, 0x20, 0x30)
    private val RANGES = arrayOf(0x0..0x4FF, 0x11400000..0x117FFFFF, 0x15400000..0x167FFFFF)
    private val PRECISE_COMMON = intArrayOf(0x157, 0x11600005, 0x11600207, 0x11600208,
        0x16400017, 0x21600017, 0x16400001, 0x15500703, 0x11500703, 0x11600703,
        0x16400703, 0x21600703, 0x11600502, 0x11600204, 0x11400204)

    // Подтверждённые ID VHAL по дампу geely_dump/car_service_dump.txt
    // EV_BATTERY_LEVEL=0x11600309 (Float %, 0..100)
    // ED_EV_BATTERY_PERCENTAGE=0x2140a6ed (Int, x10 — делим на 10)
    // ENV_OUTSIDE_TEMPERATURE=0x11600703 (Float °C)
    private const val PROP_EV_BATT_LEVEL = 0x11600309
    private const val PROP_ED_EV_BATT_PCT = 0x2140a6ed
    private const val PROP_ENV_OUT_TEMP = 0x11600703
    // AC_AMBIENT_TEMP (0x2140a377) — Int со смещением 80, половина градуса (norm: (raw-80)/2 °C).
    // Это КАБИННЫЙ ambient-датчик HVAC, а не уличная температура.
    // ПОСЛЕ декомпила CentralEXAuto v3.3.9 (com.geely.hvacstepperv2.TempStepperService.i()):
    // наружка читается из ENV_OUTSIDE_TEMPERATURE (0x11600703) с двойной нормализацией
    // (float °C, либо int/10, либо int как-есть); zones {0,1,4,7}. AC_AMBIENT_TEMP
    // оставлен как fallback на случай, если на конкретной ГУ ENV_OUTSIDE_TEMPERATURE не отдаётся.
    private const val PROP_AC_AMBIENT_TEMP = 0x2140A377

    @Volatile private var cpm: android.car.hardware.property.CarPropertyManager? = null
    @Volatile private var carObj: Car? = null
    @Volatile private var h: Handler? = null
    @Volatile private var dumpLogged = false
    // P-фикс: защита от гонки — один syncAll за раз, иначе 4 потока публикуют
    // в одни и те же notification id и перебивают друг друга (4.2%/42.4% в логе).
    private val syncInFlight = AtomicBoolean(false)
    private val tickSeq = AtomicInteger(0)
    @Volatile private var periodicRunning = false

    @Volatile private var socProp = 0; @Volatile private var socArea = 0
    @Volatile private var tmpProp = 0; @Volatile private var tmpArea = 0
    /** Источник последнего прочитанного SOC/temp (для VERIFY в логе и в уведомлении). */
    @Volatile private var socSource: String = "none"
    @Volatile private var tempSource: String = "none"

    // ─── Prefs helper ───
    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)

    // ─── Normalization (w0.y7.N Float; Int halves+80 w0.y7.P) ───
    private fun normFloat(raw: Float?, lo: Float, hi: Float): Float? {
        if (raw == null || raw.isNaN() || raw == 0f) return null
        var v = raw; if (v > 100f) v /= 10f
        return if (v in lo..hi) v else null
    }
    private fun normInt80(raw: Int?): Float? {
        if (raw == null || raw == 0) return null
        val t = (raw - 80).toFloat() / 2f
        return if (t in -50f..70f) t else null
    }

    // ─── VHAL scan ───
    private fun ensureCar(ctx: Context) {
        if (cpm != null) return
        val app = ctx.applicationContext
        try {
            carObj = Car.createCar(app, null, 0L) { c: Car, ready: Boolean ->
                if (ready) try {
                    cpm = c.getCarManager(Car.PROPERTY_SERVICE) as? android.car.hardware.property.CarPropertyManager
                    Log.i(TAG,"CarPropertyManager ready=${cpm != null}")
                    if (!dumpLogged && cpm != null) { dumpLogged = true; dumpKnownProps() }
                } catch (e: Exception) { Log.w(TAG,"getCarManager err",e) }
            }
            carObj?.connect()
        } catch (e: Exception) { Log.w(TAG,"Car.createCar: ${e.message}") }
    }

    private fun dumpKnownProps() {
        val m = cpm ?: return
        Log.i(TAG,"=== VHAL dump start (look for BATT/TEMP raw values here) ===")
        for (p in PRECISE_COMMON) for (a in AREAS) {
            try { val v = m.getFloatProperty(p,a); if (!v.isNaN()) Log.i(TAG,String.format("DUMP F 0x%08X@%d = %f",p,a,v)) } catch(_:Exception){}
            try { val v = m.getIntProperty(p,a); if (v != 0) Log.i(TAG,String.format("DUMP I 0x%08X@%d = %d",p,a,v)) } catch(_:Exception){}
        }
        Log.i(TAG,"=== VHAL dump end ===")
    }

    private fun readSocScan(): Float? {
        val m = cpm ?: return null
        fun tryProp(p: Int, a: Int, label: String, scale: Float = 1f): Float? {
            try {
                val raw = m.getFloatProperty(p, a)
                if (!raw.isNaN() && raw != 0f) {
                    val v = if (scale != 1f) raw / scale else raw
                    if (v in 0f..101f) {
                        socProp = p; socArea = a; socSource = label
                        AppLog.i(TAG, "soc=%.1f (src=$socSource raw=$raw area=$a scale=$scale)".format(v))
                        return v.coerceIn(0f, 100f)
                    }
                }
            } catch (_: Exception) {}
            try {
                val raw = m.getIntProperty(p, a)
                if (raw != 0) {
                    val v = if (scale != 1f) raw.toFloat() / scale else raw.toFloat()
                    if (v in 0f..101f) {
                        socProp = p; socArea = a; socSource = label
                        AppLog.i(TAG, "soc=%.1f (src=$socSource raw=$raw area=$a scale=$scale)".format(v))
                        return v.coerceIn(0f, 100f)
                    }
                }
            } catch (_: Exception) {}
            return null
        }
        if (socProp != 0) {
            for (a in AREAS) tryProp(socProp, a, socSource)?.let { return it }
            socProp = 0
        }
        tryProp(PROP_EV_BATT_LEVEL, 0, "VHAL:0x11600309/EV_BATTERY_LEVEL")?.let { return it }
        // P-фикс: ED_EV_BATTERY_PERCENTAGE (0x2140a6ed) на этом ГУ возвращает УЖЕ ПРОЦЕНТЫ
        // (raw=42.4 → 42.4%). Раньше здесь стоял scale=10f, деливший значение на 10
        // (42.4→4.2) — отсюда ложные "4.2%" вместо "42.4%" в логе у владельца.
        // Доказательство: в voiceapp3-2.log потоки с scale=1.0 всегда давали 42.4%,
        // а с scale=10.0 — 4.2%. scale=1.0 — единственно верный вариант.
        tryProp(PROP_ED_EV_BATT_PCT, 0, "VHAL:0x2140a6ed/ED_EV_BATTERY_PERCENTAGE")?.let { return it }
        for (p in PRECISE_COMMON) {
            for (a in intArrayOf(0, 2, 7)) {
                tryProp(p, a, "VHAL:0x${"%08X".format(p)}[fallback]")?.let { return it }
            }
        }
        AppLog.w(TAG, "soc=? (ни один проп не дал валидного значения)")
        socSource = "none"
        return null
    }

    private fun readTempScan(): Float? {
        val m = cpm ?: return null
        AppLog.i(TAG, "TEMP: toggle=on read start")

        // CentralEXAuto pattern (com.geely.hvacstepperv2.TempStepperService.i()):
        // PRIMARY = ENV_OUTSIDE_TEMPERATURE (0x11600703) по зонам {0,1,4,7},
        // с двойной нормализацией:
        //   1) getFloatProperty(p, area) — напрямую °C; валидный диапазон -55..70 °C
        //   2) если float не дал, getIntProperty(p, area):
        //        - если raw в [-550..700] → raw / 10.0f   (десятые доли градуса)
        //        - иначе если raw в [-45..65] → raw.toFloat() (уже градусы)
        // Декомпилировано из TempBattPillFloaterService/TempStepperService CentralEXAuto v3.3.9.
        fun tryOutsideCex(area: Int, label: String): Float? {
            // Float first
            try {
                val raw = m.getFloatProperty(PROP_ENV_OUT_TEMP, area)
                if (!raw.isNaN() && raw > -55f && raw < 70f) {
                    AppLog.i(TAG, "TEMP: ENV_OUTSIDE@$area float raw=$raw -> $raw °C (CentralEX)")
                    return raw
                }
            } catch (e: Exception) { AppLog.d(TAG, "TEMP: $label float err=${e.message}") }
            // Int fallback (two ranges, как у CentralEXAuto TempStepperService.i())
            try {
                val raw = m.getIntProperty(PROP_ENV_OUT_TEMP, area)
                val v: Float? = when {
                    raw in -550..700 -> raw / 10.0f
                    raw in -45..65   -> raw.toFloat()
                    else -> null
                }
                if (v != null && v > -55f && v < 70f) {
                    AppLog.i(TAG, "TEMP: ENV_OUTSIDE@$area int raw=$raw -> $v °C (CentralEX /10 norm)")
                    return v
                }
            } catch (e: Exception) { AppLog.d(TAG, "TEMP: $label int err=${e.message}") }
            return null
        }

        fun tryFloatProp(p: Int, a: Int, label: String): Float? {
            try {
                val raw = m.getFloatProperty(p, a)
                if (!raw.isNaN() && raw != 0f) {
                    val v = raw.coerceIn(-50f, 70f)
                    if (v in -50f..70f) {
                        tmpProp = p; tmpArea = a; tempSource = label
                        AppLog.i(TAG, "TEMP: read raw=$raw v=%.1f src=$tempSource area=$a".format(v))
                        return v
                    } else { AppLog.d(TAG, "TEMP: $label raw=$raw out of range") }
                }
            } catch (e: Exception) { AppLog.d(TAG, "TEMP: $label float err=${e.message}") }
            return null
        }
        fun tryInt80Prop(p: Int, a: Int, label: String): Float? {
            try {
                val raw = m.getIntProperty(p, a)
                if (raw != 0) normInt80(raw)?.let { v ->
                    tmpProp = p; tmpArea = a; tempSource = label
                    AppLog.i(TAG, "TEMP: read raw=$raw v=$v (norm=($raw-80)/2) src=$tempSource")
                    return v.toFloat()
                }
            } catch (e: Exception) { AppLog.d(TAG, "TEMP: $label int err=${e.message}") }
            return null
        }
        // P-фикс: собираем ВСЕХ кандидатов и логируем единой строкой для сравнения
        // с эталоном (Строка состояния). Приоритет выверен по декомпилу CentralEXAuto v3.3.9:
        //   1) ENV_OUTSIDE_TEMPERATURE (0x11600703) по зонам {0,1,4,7} — primary,
        //      с двойной нормализацией (float °C / int /10). Именно это читает
        //      PillFloater/HvacStepper CentralEXAuto, который рисует "°C" в статус-баре.
        //   2) hvacdigital getprop temp_ext/exttemp (сигнал flyme-плагина; без рута
        //      может быть 0/1 — флаг включения, поэтому после VHAL).
        //   3) AC_AMBIENT_TEMP (0x2140a377) — кабина HVAC, Int (raw-80)/2; это не
        //      наружная, а ambient салона; оставляем как fallback.
        //   4) PRECISE_COMMON (fallback).
        // Кеш tmpProp между вызовами НЕ ИСПОЛЬЗУЕТСЯ — сбрасываем в 0 каждый проход,
        // чтобы однажды залипшее 25°C не заблокировало нахождение реального значения.
        data class Cand(val name: String, val value: Float?)
        val candidates = mutableListOf<Cand>()
        var chosen: Float? = null
        var chosenArea = 0

        fun pick(v: Float?, label: String, area: Int = 0): Float? {
            candidates.add(Cand(label, v))
            if (v != null && chosen == null) { chosen = v; chosenArea = area }
            return v
        }
        fun probe(label: String, area: Int = 0, get: () -> Float?): Float? {
            val v = runCatching { get() }.getOrNull()
            return pick(v, label, area)
        }

        tmpProp = 0

        // 1) PRIMARY: ENV_OUTSIDE_TEMPERATURE по зонам как в CentralEXAuto.
        for (a in intArrayOf(0, 1, 4, 7)) {
            probe("VHAL:0x11600703/ENV_OUTSIDE_TEMPERATURE@$a", a) {
                tryOutsideCex(a, "VHAL:0x11600703/ENV_OUTSIDE_TEMPERATURE@$a")
            }
            if (chosen != null) { tempSource = "VHAL/CentralEX:ENV_OUTSIDE@$a"; tmpProp = PROP_ENV_OUT_TEMP; tmpArea = a; break }
        }
        // 2) hvacdigital getprop temp_ext / exttemp (кросс-проверка).
        if (chosen == null) {
            for (prop in SYS_HVAC_EXT_ALL) {
                val v = runCatching {
                    val pp = Runtime.getRuntime().exec(arrayOf("sh","-c","getprop $prop"))
                    if (!pp.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) { pp.destroyForcibly() }
                    val sVal = pp.inputStream.bufferedReader().readText().trim()
                    pp.errorStream.bufferedReader().readText().trim()
                    val fv = sVal.toFloatOrNull()
                    AppLog.d(TAG, "TEMP: getprop $prop raw=\"$sVal\" parsed=$fv")
                    fv?.takeIf { it in -50f..70f }?.also {
                        AppLog.i(TAG, "TEMP: getprop $prop val=\"$sVal\" -> $it")
                    }
                }.getOrNull()
                pick(v, "hvacdigital/$prop")
                if (chosen != null) { tempSource = "hvacdigital/$prop"; break }
            }
        }
        // 3) AC_AMBIENT_TEMP (кабина) — fallback (norm (raw-80)/2 °C).
        if (chosen == null) {
            for (a in intArrayOf(0, 2, 7)) {
                probe("VHAL:0x2140a377/AC_AMBIENT_TEMP@$a", a) {
                    tryInt80Prop(PROP_AC_AMBIENT_TEMP, a, "VHAL:0x2140A377/AC_AMBIENT_TEMP@$a")
                }
                if (chosen != null) break
            }
        }
        // 4) PRECISE_COMMON fallback
        if (chosen == null) {
            outer@ for (pp in PRECISE_COMMON) {
                for (a in intArrayOf(0, 2, 7)) {
                    val label = "VHAL:0x${"%08X".format(pp)}@$a[fallback]"
                    probe(label, a) { tryInt80Prop(pp, a, label) }
                    if (chosen != null) break@outer
                    probe(label, a) { tryFloatProp(pp, a, label) }
                    if (chosen != null) break@outer
                }
            }
        }
        val finalV = chosen
        if (finalV != null) {
            AppLog.i(TAG, "TEMP: candidates — " + candidates.joinToString(" | ") { "${it.name}=${it.value}" })
            AppLog.i(TAG, "TEMP: chosen=%.1f (src=$tempSource area=$chosenArea)".format(finalV))
            return finalV
        }
        AppLog.w(TAG, "TEMP: candidates — " + candidates.joinToString(" | ") { "${it.name}=${it.value}" })
        AppLog.w(TAG, "TEMP: notify ERROR: ни один источник не дал валидного значения")
        tempSource = "none"
        return null
    }

    /** PendingIntent для клика по батарее/температуре → наше приложение, сразу вкладка «Телеметрия». */
    private fun telemetryPi(ctx: Context, id: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(ctx, "com.example.voiceapp3.MainActivity")
            putExtra("destination", "telemetry")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val flags = if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(ctx, id + 1000, intent, flags)
    }

    /** PendingIntent для клика по Wi-Fi → системные настройки Wi-Fi. */
    private fun wifiSettingsPi(ctx: Context): PendingIntent {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val flags = if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(ctx, 15099, intent, flags)
    }

    private fun postNotif(ctx: Context, id: Int, channelId: String, icon: Icon, title: String, text: String, rank: Int,
                          contentIntent: PendingIntent) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channelId, title, NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val n = Notification.Builder(ctx, channelId)
            .setSmallIcon(icon)
            .setContentTitle(title).setContentText(text).setContentIntent(contentIntent)
            .setOngoing(true).setShowWhen(false).build()
        n.extras.putBoolean("flag_status_icon_notification", true)
        n.extras.putInt("flag_status_icon_id", id)
        n.extras.putString("flag_status_icon_describe", "centralex_$id")
        n.extras.putParcelable("flag_status_icon_icon", icon)
        n.extras.putParcelable("flag_status_icon_pressed_icon", icon)
        n.extras.putBoolean("flag_status_icon_hide", false)
        n.extras.putInt("flag_status_icon_rank", rank)
        n.extras.putInt("flag_status_icon_space_x", 1)
        n.extras.putBoolean("flag_status_icon_is_pick_on", false)
        n.extras.putInt("flag_status_icon_specific_width", 0)
        try { nm.notify(id, n); AppLog.d(TAG,"post notif id=$id rank=$rank text=\"$text\"") }
        catch (t: Throwable) { AppLog.e(TAG,"notify $id err", t) }
    }

    // DO NOT replace with a text placeholder. Use arc-based draw for wifi; keep text-draw for batt/temp.
    private fun makeTextIcon(ctx: Context, main: String, suffix: String, color: Int): Icon {
        val dp = ctx.resources.displayMetrics.density
        val size = (24 * dp).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; typeface = Typeface.DEFAULT_BOLD
            textSize = size * 0.55f; textAlign = Paint.Align.CENTER
            style = Paint.Style.FILL
        }
        val pSfx = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; typeface = Typeface.DEFAULT
            textSize = size * 0.35f; textAlign = Paint.Align.LEFT
        }
        if (main == "wifi") {
            // Значок Wi-Fi: маленькая точка внизу + 3 дуги (уровни сигнала) над ней.
            // Все дуги рисуем одним цветом (нет инфы об уровне в small icon-е; детали в contentText).
            val cx = size/2f
            val dotR = size * 0.06f
            val dotCy = size * 0.82f
            canvas.drawCircle(cx, dotCy, dotR, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = size * 0.08f
            p.strokeCap = Paint.Cap.ROUND
            // 3 дуги: radii 0.22/0.38/0.54 размера
            for (k in 1..3) {
                val r = size * (0.22f + (k-1)*0.16f)
                val oval = RectF(cx - r, dotCy - r, cx + r, dotCy + r)
                canvas.drawArc(oval, -135f, 90f, false, p)
            }
            p.style = Paint.Style.FILL
        } else {
            val mainW = p.measureText(main)
            val sfxW = if (suffix.isNotEmpty()) pSfx.measureText(suffix) else 0f
            val totalW = mainW + sfxW
            val startX = (size - totalW) / 2f
            canvas.drawText(main, startX + mainW/2f, size*0.72f, p)
            if (suffix.isNotEmpty()) {
                canvas.drawText(suffix, startX + mainW, size*0.72f, pSfx)
            }
        }
        return Icon.createWithBitmap(bmp)
    }

    /**
     * Иконка температуры «снаружи»: компактный термометр (глиф, НЕ текст-заглушка)
     * слева + цифра значения справа. Аналогично Wi-Fi: рисуется в Canvas, без
     * использования эмодзи/текста как иконки — чтобы статус-бар не показывал
     * регрессию "голое +12° вместо значка".
     * DO NOT replace with text placeholder.
     */
    private fun makeTempIcon(ctx: Context, valueStr: String, color: Int): Icon {
        val dp = ctx.resources.displayMetrics.density
        val size = (24 * dp).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Glyph-термометр слева:
        //   ─ вертикальная колба (stroke-прямоугольник со скруглённым верхом),
        //   ─ круглый шарик внизу (заполненный),
        //   ─ короткая ртутная колонка внутри (для индикации «температура»).
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE
            strokeWidth = size * 0.09f; strokeCap = Paint.Cap.ROUND
        }
        val gx0 = size * 0.15f                // левая граница глифа
        val gx1 = size * 0.38f                // правая граница глифа
        val colW = gx1 - gx0
        val cx = (gx0 + gx1) / 2f
        val topY = size * 0.15f
        val midY = size * 0.66f                // нижняя точка колбы (без шарика)
        // Колба (прямоугольник со скруглённым верхом и переходом в шарик внизу).
        // Рисуем левую/правую вертикали + верхнюю дугу (нижняя дуга и шарик — ниже).
        val bulbTop = midY - colW * 0.4f
        val stemLeft = gx0 + colW * 0.25f
        val stemRight = gx1 - colW * 0.25f
        canvas.drawLine(stemLeft, topY + colW / 2f, stemLeft, bulbTop, glyph)
        canvas.drawLine(stemRight, topY + colW / 2f, stemRight, bulbTop, glyph)
        // Верхнее скругление (180° дуга от левой вертикали к правой):
        val topOval = RectF(stemLeft, topY, stemRight, topY + colW)
        canvas.drawArc(topOval, 180f, 180f, false, glyph)
        // Нижнее скругление колбы — переход к шарику (180° дуга вниз):
        val botOval = RectF(stemLeft, bulbTop, stemRight, bulbTop + colW)
        canvas.drawArc(botOval, 0f, 180f, false, glyph)
        // Шарик внизу (заполненный круг, центр ниже низа колбы):
        val bulbR = colW * 0.75f
        val bulbCy = midY + bulbR * 0.3f
        glyph.style = Paint.Style.FILL
        canvas.drawCircle(cx, bulbCy, bulbR, glyph)
        // Иконка в статус-баре одноцветная, поэтому ртутную колонку внутри
        // не рисуем (в монохроме она не видна и только ломает глиф).

        // Значение температуры справа:
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; typeface = Typeface.DEFAULT_BOLD
            textSize = size * 0.55f; textAlign = Paint.Align.LEFT
            style = Paint.Style.FILL
        }
        val deg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; typeface = Typeface.DEFAULT
            textSize = size * 0.32f; textAlign = Paint.Align.LEFT
        }
        val numX = size * 0.46f
        val numY = size * 0.74f
        canvas.drawText(valueStr, numX, numY, num)
        val degX = numX + num.measureText(valueStr) - size * 0.02f
        canvas.drawText("°", degX, numY - size*0.22f, deg)
        AppLog.i(TAG, "TEMP: icon glyph=thermometer size=${size}x${size}px value=$valueStr")
        return Icon.createWithBitmap(bmp)
    }

    private fun makeWifiIconWithLevel(ctx: Context, level: Int, color: Int): Icon {
        // Тоже дуги, но активные дуги только до level (0..4 или -1 при disc/off).
        // Для small icon статуса-бара всё же рисуем все дуги (размер не разглядеть),
        // а level отражаем толщиной/альфой. Функция оставлена для будущего отображения.
        return makeTextIcon(ctx, "wifi", "", color)
    }

    private fun cancel(ctx: Context, id: Int) {
        try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id) } catch(_:Throwable){}
    }

    // ─── Public API (совместимый с существующими callers) ───
    @JvmStatic fun syncAll(ctx: Context) {
        BgWorker.assertNotMain("StatusIcons.syncAll")
        // P-фикс: если один syncAll уже работает — второй не запускаем (гонка
        // потоков = главный источник багов "4.2% вместо 42.4%" и дублированных
        // post notif в логе у владельца). CAS гарантирует единственность.
        if (!syncInFlight.compareAndSet(false, true)) {
            AppLog.d(TAG, "syncAll skipped (another sync in flight)")
            return
        }
        val seq = tickSeq.incrementAndGet()
        try {
            syncAllImpl(ctx.applicationContext, seq)
        } finally {
            syncInFlight.set(false)
        }
    }

    private fun syncAllImpl(app: Context, seq: Int) {
        ensureCar(app)
        val showBatt = prefs(app).getBoolean(PREF_BATTERY, false)
        val showTemp = prefs(app).getBoolean(PREF_EXT_TEMP, false)
        val showWifi = prefs(app).getBoolean(PREF_WIFI, false)
        Log.d(TAG,"syncAll seq=$seq batt=$showBatt temp=$showTemp wifi=$showWifi cpm=${cpm!=null}")

        var battStr: String? = null; var battPct: Float? = null; var battHas = false
        var tempStr: String? = null; var tempVal: Float? = null; var tempHas = false
        var wifiStr: String? = null; var wifiLvl: Int = -1; var wifiHas = false

        if (showBatt) {
            AppLog.d(TAG, "BATT: toggle=on read start")
            val soc = readSocScan()
            if (soc != null) {
                val socStr = if (soc == soc.toInt().toFloat()) "${soc.toInt()}" else String.format(Locale.US, "%.1f", soc)
                val text = "$socStr% · $socSource"
                val w = (24 * app.resources.displayMetrics.density).toInt().coerceAtLeast(24)
                postNotif(app, NOTIF_BATT, CHANNEL_BATT, makeTextIcon(app, "%", "", Color.WHITE), "Батарея", text, RANK_BATT,
                    telemetryPi(app, NOTIF_BATT))
                AppLog.i(TAG, "BATT: value=$socStr% smallIconSize=${w}x${w}px click→telemetry src=$socSource → posted + OVERLAY")
                battStr = "$socStr%"; battPct = soc; battHas = true
            } else { cancel(app, NOTIF_BATT); AppLog.w(TAG,"BATT: SOC null") }
        } else cancel(app, NOTIF_BATT)

        if (showTemp) {
            val t = readTempScan()
            if (t != null) {
                val tStr = if (t == t.toInt().toFloat()) "${t.toInt()}" else String.format(Locale.US, "%.1f", t)
                val sign = if (t > 0) "+" else ""
                val text = "$sign$tStr °C · $tempSource"
                val w = (24 * app.resources.displayMetrics.density).toInt().coerceAtLeast(24)
                postNotif(app, NOTIF_TEMP, CHANNEL_TEMP, makeTextIcon(app, "°", "", Color.WHITE), "Снаружи", text, RANK_TEMP,
                    telemetryPi(app, NOTIF_TEMP))
                AppLog.i(TAG, "TEMP: value=$sign$tStr°C smallIconSize=${w}x${w}px src=$tempSource → posted + OVERLAY")
                tempStr = "$sign$tStr°"; tempVal = t; tempHas = true
            } else {
                cancel(app, NOTIF_TEMP)
                AppLog.w(TAG, "TEMP: notify ERROR: value=null")
            }
        } else { cancel(app, NOTIF_TEMP); AppLog.d(TAG, "TEMP: toggle=off") }

        if (showWifi) {
            try {
                val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = wm.connectionInfo
                val rssi = info.rssi
                val level = WifiManager.calculateSignalLevel(rssi, 5)
                val enabled = wm.isWifiEnabled
                val connected = info.networkId != -1
                val state = when {
                    !enabled -> "off"
                    connected -> "on (${level}/4 rssi=$rssi)"
                    else -> "disc"
                }
                postNotif(app, NOTIF_WIFI, CHANNEL_WIFI, makeWifiIconWithLevel(app, level, Color.WHITE), "Wi-Fi", state, RANK_WIFI,
                    wifiSettingsPi(app))
                wifiStr = if (!enabled) "✕" else if (connected) "$level/4" else "…"
                wifiLvl = level
                wifiHas = true
                AppLog.i(TAG, "WIFI: level=$level rssi=$rssi icon=arcs click→android.settings.WIFI_SETTINGS state=$state → posted + OVERLAY")
            } catch (e: Exception) { AppLog.e(TAG,"WIFI: notify ERROR: ${e.message}", e) }
        } else cancel(app, NOTIF_WIFI)

        // P-фикс раздел 0: основной рендер — в overlay-окно (читаемый размер ~12sp текст).
        // smallIcon нотификации — заглушка ("%", "°", дуги) для системной шторки; визуально
        // индикатор рисуется в StatusOverlay, там же и клики по PendingIntent.
        if (battHas || tempHas || wifiHas) {
            try {
                val battIcon = if (battHas) makeBattGlyph(app, battPct ?: 0f) else null
                val tempIcon = if (tempHas) makeTempOverlayIcon(app) else null
                val wifiIcon = if (wifiHas) makeWifiOverlayIcon(app, wifiLvl) else null
                val ovSizeDp = 32
                StatusOverlay.update(
                    app,
                    battHas, battStr, battIcon, telemetryPi(app, NOTIF_BATT),
                    tempHas, tempStr, tempIcon, telemetryPi(app, NOTIF_TEMP),
                    wifiHas, wifiStr, wifiIcon, wifiSettingsPi(app)
                )
                AppLog.i(TAG, "OVERLAY: render size=${ovSizeDp}dp seg(B=$battHas T=$tempHas W=$wifiHas) seq=$seq")
            } catch (t: Throwable) {
                AppLog.w(app, TAG, "OVERLAY update err: ${t.message}")
            }
        } else {
            StatusOverlay.hide(app)
        }
    }

    @JvmStatic fun cancelAll(ctx: Context) {
        cancel(ctx, NOTIF_BATT); cancel(ctx, NOTIF_TEMP); cancel(ctx, NOTIF_WIFI)
        StatusOverlay.hide(ctx)
    }

    // ─── Глифы для overlay (крупные, ICON_SIZE_DP=16px, белые) ───

    private fun makeBattGlyph(ctx: Context, percent: Float): Icon {
        val dp = ctx.resources.displayMetrics.density
        val size = (16 * dp).toInt().coerceAtLeast(16)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = size*0.09f; strokeCap = Paint.Cap.ROUND }
        val pad = size*0.12f; val h = size*0.55f; val w = size*0.65f; val topY = (size-h)/2
        val left = size*0.20f; val right = left+w
        c.drawRoundRect(left, topY, right, topY+h, size*0.08f, size*0.08f, p)
        c.drawRect(right - size*0.02f, topY + h*0.25f, right+size*0.08f, topY + h*0.75f, p)
        // Заполненность (percent 0..100)
        p.style = Paint.Style.FILL
        val fillH = h*0.75f * (percent/100f).coerceIn(0f,1f)
        c.drawRect(left+size*0.09f, topY+h-fillH-size*0.05f, right-size*0.09f, topY+h-size*0.12f, p)
        return Icon.createWithBitmap(bmp)
    }

    private fun makeTempOverlayIcon(ctx: Context): Icon {
        // reuse existing thermometer glyph but at overlay size
        val dp = ctx.resources.displayMetrics.density
        val size = (16 * dp).toInt().coerceAtLeast(16)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; style=Paint.Style.STROKE; strokeWidth=size*0.10f; strokeCap=Paint.Cap.ROUND }
        val stemLeft = size*0.32f; val stemRight = size*0.52f; val topY = size*0.12f
        val colW = stemRight - stemLeft
        val bulbTop = size*0.48f
        c.drawLine(stemLeft, topY+colW/2, stemLeft, bulbTop, glyph)
        c.drawLine(stemRight, topY+colW/2, stemRight, bulbTop, glyph)
        c.drawArc(RectF(stemLeft, topY, stemRight, topY+colW), 180f, 180f, false, glyph)
        c.drawArc(RectF(stemLeft, bulbTop, stemRight, bulbTop+colW), 0f, 180f, false, glyph)
        glyph.style = Paint.Style.FILL
        val bulbR = colW*0.95f
        val cx = (stemLeft+stemRight)/2f
        c.drawCircle(cx, bulbTop+colW*0.45f+bulbR*0.05f, bulbR, glyph)
        return Icon.createWithBitmap(bmp)
    }

    private fun makeWifiOverlayIcon(ctx: Context, level: Int): Icon {
        val dp = ctx.resources.displayMetrics.density
        val size = (16 * dp).toInt().coerceAtLeast(16)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; style=Paint.Style.STROKE; strokeWidth=size*0.10f; strokeCap=Paint.Cap.ROUND }
        val cx = size/2f; val dotCy = size*0.82f; val dotR = size*0.07f
        c.drawCircle(cx, dotCy, dotR, p)
        val active = level.coerceIn(-1,4)
        for (k in 1..3) {
            val r = size*(0.20f + (k-1)*0.16f)
            val oval = RectF(cx-r, dotCy-r, cx+r, dotCy+r)
            val isActive = (active >= k || active < 0)
            p.alpha = if (isActive) 255 else 90
            c.drawArc(oval, -135f, 90f, false, p)
        }
        p.alpha = 255
        return Icon.createWithBitmap(bmp)
    }

    @JvmStatic fun startPeriodicSync(ctx: Context) {
        // P-фикс: если периодика уже запущена — не стартовать второй Handler/Runnable,
        // иначе получаем N параллельных тиков (в логе у владельца "цикл 15с активен" ×4).
        if (periodicRunning) {
            Log.i(TAG, "startPeriodicSync: already running, skip (idempotent)")
            return
        }
        stopPeriodicSync()
        val hh = Handler(Looper.getMainLooper()); h = hh
        val app = ctx.applicationContext
        periodicRunning = true
        hh.post(object : Runnable {
            override fun run() {
                if (!periodicRunning || h !== hh) return
                BgWorker.post("StatusIcons.tick") {
                    try { syncAll(app) } catch (t: Throwable) { Log.e(TAG,"tick err",t) }
                }
                hh.postDelayed(this, 15000L)
            }
        })
        BgWorker.post("StatusIcons.firstTick") {
            try { syncAll(app) } catch (t: Throwable) { Log.e(TAG,"first tick err",t) }
        }
        Log.i(TAG,"startPeriodicSync (single instance, 15s tick on BgWorker)")
    }
    @JvmStatic fun stopPeriodicSync() {
        periodicRunning = false
        h?.removeCallbacksAndMessages(null); h = null
        Log.i(TAG,"stopPeriodicSync")
    }

    // ─── HVAC digital (флаги persist.sys.hvacdigital.*) ───
    /**
     * Цифровая индикация климата в статус-баре — включает соответствующую
     * цифровую плашку плагина GeelyAutoSystemUIPlugin через setprop:
     *   persist.sys.hvacdigital.batt / .temp / .temp_ext
     *
     * Плагин монтируется AppPerformance.setVideo60Fps НЕ занимается — плагин
     * лежит в assets/GeelyAutoSystemUIPlugin-digital.apk и его bind-mount /
     * подмена APK делает отдельная функция (здесь только переключатель флагов).
     */
    fun sysPropFor(pref: String): String = when (pref) {
        PREF_HVAC_BATT -> SYS_HVAC_BATT
        PREF_HVAC_TEMP -> SYS_HVAC_TEMP
        PREF_HVAC_EXT_TEMP -> SYS_HVAC_EXT_TEMP_EXT  // primary = старый плагин
        else -> ""
    }

    /** Все свойства внешней температуры, которые надо выставлять одновременно. */
    private val SYS_HVAC_EXT_ALL = arrayOf(SYS_HVAC_EXT_TEMP_EXT, SYS_HVAC_EXT_EXTTEMP)

    @JvmStatic fun setHvacDigital(ctx: Context, pref: String, value: Boolean): ToolsResult {
        val t0 = android.os.SystemClock.uptimeMillis()
        prefs(ctx).edit().putBoolean(pref, value).commit()
        val v = if (value) 1 else 0
        // Для batt/temp одно свойство; для ext_temp пишем в ОБА имени (temp_ext — старый
        // плагин на устройстве, exttemp — новый CentralEXAuto).
        val props = when (pref) {
            PREF_HVAC_BATT -> listOf(SYS_HVAC_BATT)
            PREF_HVAC_TEMP -> listOf(SYS_HVAC_TEMP)
            PREF_HVAC_EXT_TEMP -> SYS_HVAC_EXT_ALL.toList()
            else -> listOf(sysPropFor(pref))
        }.filter { it.isNotEmpty() }
        val cmd = buildString {
            for (prop in props) append("setprop $prop $v; ")
            append("echo ")
            var first = true
            for (prop in props) {
                if (!first) append(" "); first = false
                append("$prop=\$(getprop $prop)")
            }
        }
        val (exit, out) = AdbClient.execute(cmd, 5000)
        val allApplied = props.all { p -> out.contains("$p=$v") }
        val step = ToolsStep(
            step = "hvacdigital.$pref",
            command = cmd.take(200),
            startedAtMs = t0, finishedAtMs = android.os.SystemClock.uptimeMillis(),
            exitCode = exit, stdout = out, stderr = "",
            expected = props.joinToString(" ") { "$it=$v" },
            actual = out.lines().lastOrNull { it.contains(if(pref==PREF_HVAC_EXT_TEMP) "temp_ext" else props.first()) } ?: out,
            status = if ((value && allApplied) || (!value && exit==0)) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL
        )
        AppLog.i(TAG, "VERIFY hvacdigital set $pref=$v: applied=$allApplied props=$props out=${out.replace('\n',' ').take(300)}")
        return ToolsResult(
            action = "hvacdigital",
            title = "Цифровая индикация климата",
            steps = listOf(step)
        )
    }

    @JvmStatic fun getHvacDigital(ctx: Context, pref: String): Boolean =
        prefs(ctx).getBoolean(pref, false)

    @JvmStatic fun bootRestoreHvacDigital(ctx: Context) {
        val p = prefs(ctx)
        val sb = StringBuilder()
        val pairs = mutableListOf(
            PREF_HVAC_BATT to SYS_HVAC_BATT,
            PREF_HVAC_TEMP to SYS_HVAC_TEMP,
        )
        for (prop in SYS_HVAC_EXT_ALL) pairs.add(PREF_HVAC_EXT_TEMP to prop)
        for ((k,sp) in pairs) {
            val v = if (p.getBoolean(k, false)) 1 else 0
            sb.append("setprop $sp $v; ")
        }
        sb.append("echo batt=\$(getprop $SYS_HVAC_BATT) temp=\$(getprop $SYS_HVAC_TEMP) ext_temp_ext=\$(getprop $SYS_HVAC_EXT_TEMP_EXT) ext_exttemp=\$(getprop $SYS_HVAC_EXT_EXTTEMP)")
        val (_,out) = AdbClient.execute(sb.toString(), 5000)
        Log.i(TAG,"bootRestoreHvacDigital: $out")
    }
}
