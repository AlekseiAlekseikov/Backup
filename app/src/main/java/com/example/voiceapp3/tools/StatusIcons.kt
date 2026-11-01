package com.example.voiceapp3.tools

import com.example.voiceapp3.tools.Prefs2

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

    // iter22 §1: канал и ранги ОДИН-В-ОДИН как в CEX w0/kg#j: единый канал
    // "central_ex_status_temp", IMPORTANCE_LOW, setShowBadge(false);
    // notifId 15020 (batt) iconId=97 rank=32; notifId 15021 (temp) iconId=15 rank=22;
    // Wi-Fi остаётся на том же канале с собственным notifId.
    private const val CHANNEL_STATUS = "central_ex_status_temp"
    private const val CHANNEL_WIFI = "central_ex_status_wifi"
    private const val NOTIF_BATT = 15020
    private const val NOTIF_TEMP = 15021
    private const val NOTIF_WIFI = 15024
    private const val ICON_ID_BATT = 97
    private const val ICON_ID_TEMP = 15
    private const val RANK_BATT = 32
    private const val RANK_TEMP = 22
    private const val RANK_WIFI = 42

    private val AREAS = intArrayOf(0, 1, 2, 3, 4, 7, 0x10, 0x20, 0x30)
    private val RANGES = arrayOf(0x0..0x4FF, 0x11400000..0x117FFFFF, 0x15400000..0x167FFFFF)
    // iter21 §5: 0x11600703 УДАЛЁН (всегда возвращал константу 25.0 — не реальный датчик).
    private val PRECISE_COMMON = intArrayOf(0x157, 0x11600005, 0x11600207, 0x11600208,
        0x16400017, 0x21600017, 0x16400001, 0x15500703, 0x11500703,
        0x16400703, 0x21600703, 0x11600502, 0x11600204, 0x11400204)

    // Подтверждённые ID VHAL.
    // EV_BATTERY_LEVEL=0x11600309 (Float %, 0..100)
    // ED_EV_BATTERY_PERCENTAGE=0x2140a6ed (Int, x10 — делим на 10)
    private const val PROP_EV_BATT_LEVEL = 0x11600309
    private const val PROP_ED_EV_BATT_PCT = 0x2140a6ed
    // iter21 §5: 5 НОВЫХ ID для пробы наружной температуры (логгируем ВСЕ raw, "--" до верификации).
    private val TEMP_PROBE_IDS = intArrayOf(
        0x2140A863.toInt(), 0x2140B009, 0x2140B00D, 0x2140A6DC.toInt(), 0x21402063
    )
    // AC_AMBIENT_TEMP (0x2140a377) — Int со смещением 80, половина градуса (norm: (raw-80)/2 °C).
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
    // iter22 §2: детектор «залипшего» значения (25.0) — только ЛОГ, значение не скрываем.
    @Volatile private var tempSameCount: Int = 0
    @Volatile private var tempPrev: Float? = null
    // iter16: кэш последних прочитанных значений (для push в persist.sys.hvacdigital.*
    // под строковый плагин). Плагин ожидает ЗНАЧЕНИЯ (напр. "98.1", "22.5", "9.7"), а не 0/1.
    @Volatile private var lastBattPct: Float? = null
    @Volatile private var lastTempC: Float? = null
    @Volatile private var lastExtTempC: Float? = null

    // ─── Prefs helper ───
    private fun prefs(c: Context): SharedPreferences = Prefs2.cps(c)

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
                    AppLog.i(TAG, "CarPropertyManager ready=${cpm != null}")
                    // iter17: дамп VHAL тяжёлый (1000+ binder-вызовов), нельзя
                    // выполнять на main looper при старте (он и был причиной
                    // белого окна). Откладываем на 8с на BgWorker — чтобы не
                    // конкурировать с первым кадром.
                    if (!dumpLogged && cpm != null) {
                        dumpLogged = true
                        android.os.Handler(Looper.getMainLooper()).postDelayed({
                            BgWorker.post("StatusIcons.vhalDump") {
                                val t0 = android.os.SystemClock.uptimeMillis()
                                val propsBefore = dumpKnownPropsCount(cpm)
                                dumpKnownProps()
                                val dt = android.os.SystemClock.uptimeMillis() - t0
                                AppLog.i(TAG, "VHAL_DUMP: end dt=${dt}ms props≈$propsBefore")
                            }
                        }, 8000L)
                    }
                } catch (e: Exception) { AppLog.w(TAG, "getCarManager err", e) }
            }
            carObj?.connect()
        } catch (e: Exception) { Log.w(TAG,"Car.createCar: ${e.message}") }
    }

    /** iter16: явный список вендорных id, найденных в dex плагина. Добавляем их
     *  в дамп безусловно (чтобы гарантированно увидеть в логе), даже если они
     *  не попадают в TEMP_RANGES. */
    private val VENDOR_PLUGIN_IDS = intArrayOf(
        0x15600503.toInt(), // getFloatProperty в плагине (наружная?)
        0x15600502,
        0x1140050E,
        0x15200510,
        0x10020200,
        0x15400500,
    )

    // iter17: подсчёт числа пропертей перед дампом (для VHAL_DUMP метрики).
    private fun dumpKnownPropsCount(m: android.car.hardware.property.CarPropertyManager?): Int {
        return try { m?.propertyList?.size ?: -1 } catch (_: Throwable) { -1 }
    }

    private fun dumpKnownProps() {
        val m = cpm ?: return
        AppLog.i(TAG, "VHAL_DUMP: start (bg thread=${Thread.currentThread().name})")
        AppLog.i(TAG,"=== VHAL CANDPROP dump start (look for 10°C/98.1% matches HU panel) ===")
        // iter16: расширенные диапазоны (вкл. 0x10xxxxxx из dex плагина). ONE PASS за старт.
        val TEMP_RANGES = arrayOf(
            0x10000000..0x10FFFFFF,
            0x11000000..0x117FFFFF,
            0x15000000..0x167FFFFF,
            0x21400000..0x217FFFFF,
            0x25400000..0x267FFFFF,
        )
        // iter16: для полного дампа ограничиваем area=0 (один проход), чтобы не устроить
        // 9000+ строк при каждом старте. Полная area-пробежка нам не нужна — важны
        // вендорные id с area=0, как в dex плагина.
        val ALL_AREAS = intArrayOf(0)
        try {
            val propList = m.propertyList
            AppLog.i(TAG,"VHAL: propertyList size=${propList?.size ?: "null"}")
            for (cfg in (propList ?: emptyList())) {
                val pid = cfg.propertyId
                // Итерация 11: dump конфига свойства (доступ/areaType/supportedAreas)
                // — по этому можно понять, какое свойство реально поддерживается ГУ.
                val inRange = TEMP_RANGES.any { pid in it } ||
                        pid == 0x15400701 || pid == 0x15400703
                if (inRange) {
                    val areaIds = try { cfg.areaIds?.joinToString(",") { "0x%x".format(it) } } catch (_: Throwable) { "n/a" }
                    AppLog.i(TAG, String.format("CANDCFG 0x%08X access=%d areaType=0x%08X areas=[$areaIds]", pid, cfg.access, cfg.areaType))
                }
                if (!inRange) continue
                for (a in ALL_AREAS) {
                    try {
                        val v = m.getFloatProperty(pid, a)
                        if (!v.isNaN() && v > -55f && v < 70f) {
                            AppLog.i(TAG, String.format("CANDPROP 0x%08X@0x%02X F = %.2f °C", pid, a, v))
                        } else if (!v.isNaN() && v in 0f..101f) {
                            AppLog.i(TAG, String.format("CANDPROP 0x%08X@0x%02X F = %.2f %%", pid, a, v))
                        }
                    } catch (_: Exception) {}
                    try {
                        val iv = m.getIntProperty(pid, a)
                        val fvT = when {
                            iv in -550..700 -> iv / 10.0f
                            iv in -45..65 -> iv.toFloat()
                            else -> Float.NaN
                        }
                        if (!fvT.isNaN() && fvT > -55f && fvT < 70f) {
                            AppLog.i(TAG, String.format("CANDPROP 0x%08X@0x%02X I raw=%d = %.1f °C", pid, a, iv, fvT))
                        } else if (iv in 0..1010) {
                            AppLog.i(TAG, String.format("CANDPROP 0x%08X@0x%02X I raw=%d = %.1f %%", pid, a, iv, iv/10.0f))
                        } else if (iv in 0..101) {
                            AppLog.i(TAG, String.format("CANDPROP 0x%08X@0x%02X I raw=%d = %d %%", pid, a, iv, iv))
                        }
                    } catch (_: Exception) {}
                }
            }
            // iter16: явный проход по вендорным id из плагина, даже если они не в propertyList
            for (pid in VENDOR_PLUGIN_IDS) {
                for (a in ALL_AREAS) {
                    try {
                        val v = m.getFloatProperty(pid, a)
                        if (!v.isNaN()) AppLog.i(TAG, String.format("CANDPROP(VEND) 0x%08X@0x%02X F = %.4f", pid, a, v))
                    } catch (_: Exception) {}
                    try {
                        val iv = m.getIntProperty(pid, a)
                        AppLog.i(TAG, String.format("CANDPROP(VEND) 0x%08X@0x%02X I raw=%d", pid, a, iv))
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "VHAL propertyList enumeration failed, fallback to PRECISE_COMMON+VENDOR scan: ${e.message}")
            val allFallback = PRECISE_COMMON + VENDOR_PLUGIN_IDS
            for (p in allFallback) for (a in ALL_AREAS) {
                try { val v = m.getFloatProperty(p,a); if (!v.isNaN()) AppLog.i(TAG,String.format("CANDPROP(fb) 0x%08X@0x%02X F=%f",p,a,v)) } catch(_:Exception){}
                try {
                    val iv = m.getIntProperty(p,a)
                    AppLog.i(TAG,String.format("CANDPROP(fb) 0x%08X@0x%02X I raw=%d",p,a,iv))
                } catch(_:Exception){}
            }
        }
        AppLog.i(TAG,"=== VHAL CANDPROP dump end ===")
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

    // РЕФЕРЕНС: com.geely.hvacstepperv2.TempStepperService#i(int area) — getFloatProperty(291505923, area),
    // при NaN — getIntProperty(291505923, area): -550..700 → /10.0f, -45..65 → as-is.
    // РЕФЕРЕНС: #g() — перебор зон f1286d0 = {0,1,4,7}, первый валидный в (-55, 70) — «Exterior OK».
    // 291505923 == 0x11600703 == ENV_OUTSIDE_TEMPERATURE. Именно этот ID эталон и использует
    // для наружной температуры; прошлая «константа 25.0» — следствие чтения только area=0
    // без проверки диапазона и без int-ветки. Отсюда: число возвращаем всегда, «--» — только
    // если ни одна зона/ветка не дала значения за всё время жизни сервиса (см. syncAll).
    private const val PROP_ENV_OUTSIDE_TEMP = 291505923 // 0x11600703
    private val CEX_OUTSIDE_AREAS = intArrayOf(0, 1, 4, 7) // f1286d0

    /** РЕФЕРЕНС: CarPropertyManager#getCarPropertyConfig (в CEX — через рефлексию, API разнится). */
    private fun propConfigAccessAreaType(m: android.car.hardware.property.CarPropertyManager, propId: Int): Pair<Int, Int>? {
        return try {
            val meth = m.javaClass.getMethod("getCarPropertyConfig", Int::class.javaPrimitiveType!!)
            val cfg = meth.invoke(m, propId) ?: return null
            val access = runCatching { cfg.javaClass.getMethod("getAccess").invoke(cfg) as Int }.getOrNull() ?: -1
            val areaType = runCatching { cfg.javaClass.getMethod("getAreaType").invoke(cfg) as Int }.getOrNull() ?: -1
            access to areaType
        } catch (t: Throwable) { null }
    }

    private fun readTempScan(): Float? {
        val m = cpm ?: run { AppLog.w(TAG, "TEMP: no CarPropertyManager"); return null }
        val cfg = propConfigAccessAreaType(m, PROP_ENV_OUTSIDE_TEMP)
        AppLog.i(TAG, "TEMP PROBE_CFG 0x11600703 access=${cfg?.first ?: "n/a"} areaType=${cfg?.second ?: "n/a"} " +
                "(access 1=READ 3=READ_WRITE)")
        // access: 1 = READ_ONLY, 3 = READ_WRITE; если свойство не читаемое — не тратим binder-вызовы.
        if (cfg != null && cfg.first != 1 && cfg.first != 3) {
            AppLog.w(TAG, "TEMP: 0x11600703 not readable (access=${cfg.first}) → skip")
            return null
        }
        for (area in CEX_OUTSIDE_AREAS) {
            // float-ветка CEX#i
            try {
                val f = m.getFloatProperty(PROP_ENV_OUTSIDE_TEMP, area)
                AppLog.i(TAG, String.format("TEMP_PROBE 0x11600703@%d float=%.3f", area, f))
                if (!f.isNaN() && f > -55f && f < 70f) {
                    tempSource = "HAL 0x11600703@$area"
                    AppLog.i(TAG, String.format("Temp HAL=%.1f → UI=%.1f (area=0x%x)", f, f, area))
                    return f
                }
            } catch (e: Exception) {
                AppLog.d(TAG, String.format("TEMP_PROBE 0x11600703@%d float ERR %s", area, e.message))
            }
            // int-ветка CEX#i
            try {
                val iv = m.getIntProperty(PROP_ENV_OUTSIDE_TEMP, area)
                AppLog.i(TAG, String.format("TEMP_PROBE 0x11600703@%d int=%d", area, iv))
                if (iv > -550 && iv < 700) {
                    val v = iv / 10.0f
                    if (v > -55f && v < 70f) {
                        tempSource = "HAL 0x11600703@$area (int/10)"
                        AppLog.i(TAG, String.format("Temp HAL=%.1f → UI=%.1f (area=0x%x int)", v, v, area))
                        return v
                    }
                }
                if (iv in -45..65) {
                    tempSource = "HAL 0x11600703@$area (int)"
                    AppLog.i(TAG, String.format("Temp HAL=%.1f → UI=%.1f (area=0x%x int raw)", iv.toFloat(), iv.toFloat(), area))
                    return iv.toFloat()
                }
            } catch (e: Exception) {
                AppLog.d(TAG, String.format("TEMP_PROBE 0x11600703@%d int ERR %s", area, e.message))
            }
        }
        AppLog.w(TAG, "TEMP: 0x11600703 unavailable in all CEX areas {0,1,4,7}")

        // ДИАГНОСТИКА (не источник): перебор альтернативных ID — ТОЛЬКО лог, значения
        // никогда не выбираются автоматически (мандат iter22 §2.4: TEMP_PROBE = дамп).
        val diag = StringBuilder()
        for (pid in TEMP_PROBE_IDS) for (a in intArrayOf(0, 2, 7)) {
            val f = runCatching { m.getFloatProperty(pid, a) }.getOrElse { Float.NaN }
            val i = runCatching { m.getIntProperty(pid, a) }.getOrElse { Int.MIN_VALUE }
            diag.append(String.format("\n  0x%08X@%d float=%s int=%d", pid, a, f.toString(), i))
        }
        AppLog.i(TAG, "TEMP_PROBE (diagnostics only, not a source):$diag")
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

    /**
     * РЕФЕРЕНС: w0/kg#j(ctx, notifId, iconId, rank, title, text, icon, pi)
     * Канал "central_ex_status_temp" IMPORTANCE_LOW (2), setShowBadge(false),
     * setSmallIcon(icon: Icon), setContentTitle/Text/Intent, setOngoing/ShowWhen,
     * extras flag_status_icon_* ровно как в CEX.
     */
    private fun postNotif(ctx: Context, notifId: Int, iconId: Int, rank: Int,
                          title: String, text: String, describe: String,
                          icon: Icon, contentIntent: PendingIntent,
                          channelId: String = CHANNEL_STATUS) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channelId, "CentralEX status icons", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val n = Notification.Builder(ctx, channelId)
            .setSmallIcon(icon)
            .setContentTitle(title).setContentText(text).setContentIntent(contentIntent)
            .setOngoing(true).setShowWhen(false).build()
        n.extras.putBoolean("flag_status_icon_notification", true)
        n.extras.putInt("flag_status_icon_id", iconId)
        n.extras.putString("flag_status_icon_describe", describe)
        n.extras.putParcelable("flag_status_icon_icon", icon)
        n.extras.putParcelable("flag_status_icon_pressed_icon", icon)
        n.extras.putBoolean("flag_status_icon_hide", false)
        n.extras.putInt("flag_status_icon_rank", rank)
        n.extras.putInt("flag_status_icon_space_x", 1)
        n.extras.putBoolean("flag_status_icon_is_pick_on", false)
        n.extras.putInt("flag_status_icon_specific_width", 0)
        try {
            nm.notify(notifId, n)
            AppLog.i(TAG, "KmIconProbe: postStatusIcon $describe notifId=$notifId iconId=$iconId rank=$rank text=\"$text\"")
        } catch (t: Throwable) { AppLog.e(TAG, "notify $notifId err", t) }
    }

    // iter22 §1 pill-нотификация УДАЛЕНА: вместо неё — две отдельные иконки (batt/temp)
    // с битмапами по геометрии CEX w0/kg#a, как того требует мандат.

    /** h(dp) — CEX w0/kg#h */
    private fun h(ctx: Context, dp: Int): Int =
        (dp * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /** i(x) — CEX w0/kg#i: Math.round(x*10)/10 → "%.1f" или целое */
    fun fmtOneDecimal(x: Float): String {
        val r = Math.round(x * 10f) / 10f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else String.format(Locale.US, "%.1f", r)
    }

    /**
     * РЕФЕРЕНС: w0/kg#a(ctx, main, suffix, color, padH=22, keepNum=true, modeKm=false, scale=0.61f).
     * Рисует битмап под intrinsic-размер: TEXT_DP=56, суффикс 0.61×, padH=22dp,
     * высота = max(1, (int)((descent-ascent)/0.61f)),
     * ширина = 2*padH + measureText(main) + gap + measureText(suffix).
     */
    private fun makeTextIconCex(ctx: Context, main: String, suffix: String, color: Int): Icon {
        val TEXT_DP = 56
        val SUFFIX_SCALE = 0.61f
        val PAD_H_DP = 22
        val GAP_DP = 0   // в CEX gap=0 между main и suffix (suffix стартует сразу после main)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color; typeface = Typeface.DEFAULT; isAntiAlias = true
        }
        val mainSize = h(ctx, TEXT_DP).toFloat()
        p.textSize = mainSize
        val mainW = p.measureText(main)
        val fm = p.fontMetrics
        val textH = fm.descent - fm.ascent
        val bmpH = (textH / SUFFIX_SCALE).toInt().coerceAtLeast(1)
        var sfxW = 0f; val gapPx = h(ctx, GAP_DP)
        val sfxPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color; typeface = Typeface.DEFAULT; isAntiAlias = true
            textSize = SUFFIX_SCALE * mainSize
        }
        if (suffix.isNotEmpty()) sfxW = sfxPaint.measureText(suffix)
        val padH = h(ctx, PAD_H_DP)
        var bmpW = padH*2 + (mainW + gapPx + sfxW).toInt()
        if (bmpW < 1) bmpW = 1
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val y = ((bmpH - textH) / 2f) - fm.ascent
        canvas.drawText(main, padH.toFloat(), y, p)
        if (suffix.isNotEmpty()) canvas.drawText(suffix, padH + mainW + gapPx, y, sfxPaint)
        return Icon.createWithBitmap(bmp)
    }

    /**
     * Иконка Wi-Fi: 3 дуги + точка, РАЗМЕР по той же геометрии (рисуется в том же
     * TEXT_DP масштабе, чтобы высота иконки совпадала с batt/temp в статус-баре).
     */
    private fun makeWifiIconCex(ctx: Context, level: Int, color: Int): Icon {
        val TEXT_DP = 56
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; typeface = Typeface.DEFAULT; isAntiAlias = true; style = Paint.Style.FILL
        }
        val size = h(ctx, TEXT_DP)
        val padH = h(ctx, 22)
        val bmpW = (padH*2 + size*0.55f).toInt().coerceAtLeast(1)
        val bmpH = size.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = bmpW/2f
        val dotR = size * 0.05f
        val dotCy = bmpH * 0.82f
        canvas.drawCircle(cx, dotCy, dotR, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = size * 0.06f
        p.strokeCap = Paint.Cap.ROUND
        for (k in 1..3) {
            if (k > level + 1) p.alpha = 0x55  // неактивные дуги — сероватые
            val r = bmpH * (0.20f + (k-1)*0.14f)
            val oval = RectF(cx - r, dotCy - r, cx + r, dotCy + r)
            canvas.drawArc(oval, -135f, 90f, false, p)
            p.alpha = 0xFF
        }
        return Icon.createWithBitmap(bmp)
    }

    // DO NOT replace with a text placeholder — синоним для обратной совместимости.
    private fun makeTextIcon(ctx: Context, main: String, suffix: String, color: Int): Icon =
        makeTextIconCex(ctx, main, suffix, color)

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
        // Итерация 8: трассировка префа PREF_EXT_TEMP — файл + значение + поток
        // (чтобы понять, кто пишет false между тиками).
        val pf = prefs(app)
        val pPath = "/data/data/${app.packageName}/shared_prefs/voiceapp3.xml"
        AppLog.i(TAG, "PREF TRACE: read  file=$pPath key=$PREF_EXT_TEMP value=$showTemp seq=$seq thread=${Thread.currentThread().name}")

        var battStr: String? = null; var battPct: Float? = null; var battHas = false
        var tempStr: String? = null; var tempVal: Float? = null; var tempHas = false
        var wifiStr: String? = null; var wifiLvl: Int = -1; var wifiHas = false

        if (showBatt) {
            AppLog.d(TAG, "BATT: toggle=on read start")
            val soc = readSocScan()
            if (soc != null) {
                // iter22 §1: одна цифра после запятой (42.4, 100.0, 7.0) — как w0/kg#i("%.1f").
                val socStr = fmtOneDecimal(soc)
                val icon = makeTextIconCex(app, socStr, "%", Color.WHITE)
                val text = "$socStr% · $socSource"
                postNotif(app, NOTIF_BATT, ICON_ID_BATT, RANK_BATT,
                    "Батарея $socStr%", text, "StatusIcon_BatteryPercent", icon,
                    telemetryPi(app, NOTIF_BATT))
                AppLog.i(TAG, "BATT: value=$socStr% src=$socSource → posted (CEX geom 56dp/0.61)")
                battStr = "$socStr%"; battPct = soc; battHas = true
                lastBattPct = soc
            } else { cancel(app, NOTIF_BATT); AppLog.w(TAG,"BATT: SOC null") }
        } else cancel(app, NOTIF_BATT)

        if (showTemp) {
            val t = readTempScan()
            // iter22 §2: число показываем ВСЕГДА, если хоть раз его прочитали
            // (аналогично OEM-панели "10°C"). "--" только при полном отсутствии данных.
            val display: Float? = t ?: lastTempC
            if (display != null) {
                val tStr = fmtOneDecimal(display)
                val sign = if (display > 0f) "+" else ""
                val icon = makeTextIconCex(app, tStr, "°C", Color.WHITE)
                val text = "$sign$tStr °C · $tempSource"
                postNotif(app, NOTIF_TEMP, ICON_ID_TEMP, RANK_TEMP,
                    "Снаружи $sign$tStr°C", text, "StatusIcon_OutsideTemperature", icon,
                    telemetryPi(app, NOTIF_TEMP))
                AppLog.i(TAG, "Temp HAL=%.1f → UI=%s°C (src=%s)".format(display, tStr, tempSource))
                // iter22 §2: если число не меняется много тиков — это, вероятно, дефолт VHAL.
                // Значение НЕ скрываем (владелец: показывать всегда), только предупреждаем в логе.
                if (display == tempPrev) tempSameCount++ else { tempSameCount = 0; tempPrev = display }
                if (tempSameCount == 5) {
                    AppLog.w(TAG, "TEMP: value pinned at ${tStr}°C for 5+ ticks (src=$tempSource) — possibly VHAL default, compare with GU panel")
                }
                tempStr = "$sign$tStr°"; tempVal = display; tempHas = true
                lastTempC = display; lastExtTempC = display
            } else {
                val icon = makeTextIconCex(app, "--", "", Color.WHITE)
                postNotif(app, NOTIF_TEMP, ICON_ID_TEMP, RANK_TEMP,
                    "Снаружи --°C", "нет данных от VHAL", "StatusIcon_OutsideTemperature", icon,
                    telemetryPi(app, NOTIF_TEMP))
                tempStr = "--°"; tempHas = true
                AppLog.w(TAG, "TEMP: no data yet → \"--\" shown")
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
                // РЕГРЕСС-ЗОНА: Wi-Fi остаётся на своём notifId/канале и тех же кликах
                // (android.settings.WIFI_SETTINGS); меняется ТОЛЬКО размер иконки.
                val icon = makeWifiIconCex(app, level, Color.WHITE)
                postNotif(app, NOTIF_WIFI, NOTIF_WIFI, RANK_WIFI, "Wi-Fi", state, "StatusIcon_Wifi",
                    icon, wifiSettingsPi(app), CHANNEL_WIFI)
                wifiStr = if (!enabled) "✕" else if (connected) "$level/4" else "…"
                wifiLvl = level; wifiHas = true
                AppLog.i(TAG, "WIFI: level=$level rssi=$rssi state=$state → posted")
            } catch (e: Exception) { AppLog.e(TAG,"WIFI: notify ERROR: ${e.message}", e) }
        } else cancel(app, NOTIF_WIFI)


        // iter16: после каждого tick проталкиваем РЕАЛЬНЫЕ значения в
        // persist.sys.hvacdigital.{batt,temp,exttemp,temp_ext}, если соответствующий
        // тогл включён. Плагин ожидает значения ("98.1", "22.0", "9.7"), а не 0/1 —
        // поэтому форматируем до одного знака после запятой, batt и temp — с .1f.
        // Если значение ещё не прочитано (первый tick) — не пишем ничего (пропускаем).
        pushHvacDigitalPropsIfEnabled(app)
    }

    /**
     * iter16: протолкнуть значения батареи/температуры/наружки в persist.sys.hvacdigital.*
     * для строкового плагина SystemUI. Пишет ЗНАЧЕНИЯ, а не 0/1; если тогл выключен — 0.
     * Работает на BgWorker (вызывается из syncAllImpl, который уже на BgWorker).
     */
    private fun pushHvacDigitalPropsIfEnabled(app: Context) {
        val p = prefs(app)
        val sb = StringBuilder()
        val fmt = { v: Float? -> if (v == null) null else String.format(Locale.US, "%.1f", v) }
        // batt
        val battOn = p.getBoolean(PREF_HVAC_BATT, false)
        val bv = if (battOn) fmt(lastBattPct) ?: return else "0"
        sb.append("setprop $SYS_HVAC_BATT $bv; ")
        // temp (внутренний климат)
        val tempOn = p.getBoolean(PREF_HVAC_TEMP, false)
        val tv = if (tempOn) fmt(lastTempC) ?: return else "0"
        sb.append("setprop $SYS_HVAC_TEMP $tv; ")
        // ext_temp — пишем в ОБА свойства (старый и новый плагин)
        val extOn = p.getBoolean(PREF_HVAC_EXT_TEMP, false)
        val ev = if (extOn) fmt(lastExtTempC) ?: return else "0"
        sb.append("setprop $SYS_HVAC_EXT_TEMP_EXT $ev; setprop $SYS_HVAC_EXT_EXTTEMP $ev; ")
        // verify echo
        sb.append("echo batt=\$(getprop $SYS_HVAC_BATT) temp=\$(getprop $SYS_HVAC_TEMP) ext_temp=\$(getprop $SYS_HVAC_EXT_TEMP_EXT) exttemp=\$(getprop $SYS_HVAC_EXT_EXTTEMP)")
        try {
            val (rc, out) = AdbClient.execute(sb.toString(), 5000)
            AppLog.i(TAG, "HVACDIGITAL_PUSH rc=$rc batt=$bv temp=$tv exttemp=$ev out=${out.replace('\n',' ').take(200)}")
        } catch (t: Throwable) {
            AppLog.w(TAG, "HVACDIGITAL_PUSH failed: ${t.message}")
        }
    }

    @JvmStatic fun cancelAll(ctx: Context) {
        cancel(ctx, NOTIF_BATT); cancel(ctx, NOTIF_TEMP); cancel(ctx, NOTIF_WIFI)
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
        Prefs2.putBoolean(ctx, pref, value)
        // iter16: при ВКЛЮЧЕНИИ любого из трёх тоглов — пытаемся заинсталлить плагин
        // (mount --bind на OEM com.flyme.auto.systemuiplugin). Плагин уже лежит в
        // app/src/main/res/raw/geely_auto_systemui_plugin_digital.apk (18 МБ).
        var installNote = "plugin-not-invoked"
        if (value) {
            try {
                val r = HvacDigitalPlugin.enable(ctx)
                installNote = "plugin.enable invoked (steps=${r.steps.size} lastStatus=${r.steps.lastOrNull()?.status})"
                AppLog.i(TAG, "HVACDIGITAL: plugin.enable -> $installNote")
            } catch (t: Throwable) {
                installNote = "plugin.enable err: ${t.message}"
                AppLog.w(TAG, "HVACDIGITAL: plugin.enable failed", t)
            }
        }
        // Сразу же проталкиваем текущие значения (а не 0/1) — на случай, если пользователь
        // включил тогл после того, как syncAll уже прочитал данные.
        BgWorker.post("StatusIcons.hvacdigital.push") { pushHvacDigitalPropsIfEnabled(ctx.applicationContext) }
        val step = ToolsStep(
            step = "hvacdigital.$pref",
            command = "prefs.put($pref=$value) + push values + $installNote",
            startedAtMs = t0, finishedAtMs = android.os.SystemClock.uptimeMillis(),
            exitCode = 0, stdout = installNote, stderr = "",
            expected = "$pref=$value", actual = "$pref=$value (values pushed on next tick)",
            status = ToolsStep.Status.OK
        )
        AppLog.i(TAG, "VERIFY hvacdigital toggle $pref=$value: $installNote")
        return ToolsResult(
            action = "hvacdigital",
            title = "Цифровая индикация климата",
            steps = listOf(step)
        )
    }

    @JvmStatic fun getHvacDigital(ctx: Context, pref: String): Boolean =
        prefs(ctx).getBoolean(pref, false)

    @JvmStatic fun bootRestoreHvacDigital(ctx: Context) {
        val app = ctx.applicationContext
        // iter16: при буте, если хоть один тогл включен — пытаемся подмонтировать плагин
        // (после ребута bind-mount слетает, надо переустановить).
        val p = prefs(app)
        val anyOn = p.getBoolean(PREF_HVAC_BATT, false) ||
                   p.getBoolean(PREF_HVAC_TEMP, false) ||
                   p.getBoolean(PREF_HVAC_EXT_TEMP, false)
        if (anyOn) {
            BgWorker.post("StatusIcons.hvacdigital.boot-install") {
                try {
                    val r = HvacDigitalPlugin.enable(app)
                    AppLog.i(TAG, "HVACDIGITAL_BOOT: plugin.enable -> steps=${r.steps.size} last=${r.steps.lastOrNull()?.status} mountLine=${r.steps.lastOrNull()?.stdout?.take(200)}")
                } catch (t: Throwable) {
                    AppLog.w(TAG, "HVACDIGITAL_BOOT: plugin.enable failed", t)
                }
            }
        }
        // Значения протолкнутся на первом tick (startPeriodicSync в BootReceiver) —
        // до tick'а у нас ещё нет batt/temp значений. Пишем 0 как старт.
        val sb = StringBuilder()
        for (sp in listOf(SYS_HVAC_BATT, SYS_HVAC_TEMP, SYS_HVAC_EXT_TEMP_EXT, SYS_HVAC_EXT_EXTTEMP)) {
            sb.append("setprop $sp 0; ")
        }
        sb.append("echo reset_all_hvacdigital")
        try {
            val (_, out) = AdbClient.execute(sb.toString(), 3000)
            AppLog.i(TAG, "HVACDIGITAL_BOOT: reset props -> ${out.replace('\n',' ').take(200)}")
        } catch (t: Throwable) {
            AppLog.w(TAG, "HVACDIGITAL_BOOT: reset failed: ${t.message}")
        }
    }
}
