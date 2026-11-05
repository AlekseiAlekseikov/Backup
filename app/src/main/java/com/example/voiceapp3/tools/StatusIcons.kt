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
     *  Значения пишем только для batt/exttemp (%.1f) и via — см. pushHvacDigitalPropsIfEnabled. */
    const val PREF_HVAC_BATT = "hvacdigital_batt"
    const val PREF_HVAC_TEMP = "hvacdigital_temp"
    const val PREF_HVAC_EXT_TEMP = "hvacdigital_temp_ext"
    // iter33i P0-a: имена берём из HvacDigitalProps (один источник истины), значения не пишем.
    private const val SYS_HVAC_BATT = HvacDigitalProps.PROP_BATT
    // E1 (iter33h): `persist.sys.hvacdigital.temp` приложением БОЛЬШЕ НЕ ПИШЕТСЯ.
    // Уставку климата плагин читает сам (HvacVehicleTempWidget.getPropertyId() → HVAC_TEMPERATURE_SET),
    // а наше «значение» уходило в слот климата поверх стандартной полоски.
    // F2: ключ наружной — ОДИН (`persist.sys.hvacdigital.exttemp`), как в референсе
    // (w0/x7.java:142, w0/ah.java:17, w0/u5.java:27); `temp_ext` больше не пишем.
    private const val SYS_HVAC_VIA = HvacDigitalProps.PROP_VIA                    // только ЧТЕНИЕ (диагностика)
    private const val SYS_HVAC_EXT_EXTTEMP = HvacDigitalProps.PROP_EXT              // F2: единственный ключ наружной

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
    // 0x11600703 в этом списке не нужен: наружная читается отдельно, с зонами (см. readTempScan).
    private val PRECISE_COMMON = intArrayOf(0x157, 0x11600005, 0x11600207, 0x11600208,
        0x16400017, 0x21600017, 0x16400001, 0x15500703, 0x11500703,
        0x16400703, 0x21600703, 0x11600502, 0x11600204, 0x11400204)

    // Подтверждённые ID VHAL.
    // EV_BATTERY_LEVEL=0x11600309 (Float %, 0..100)
    // ED_EV_BATTERY_PERCENTAGE=0x2140a6ed (Int, x10 — делим на 10)
    private const val PROP_EV_BATT_LEVEL = 0x11600309
    private const val PROP_ED_EV_BATT_PCT = 0x2140a6ed
    // iter23 P1 (решение владельца): ИСТОЧНИК наружной температуры — 0x2140A377.
    // На ГУ оно даёт реальные 9.2 °C при 92 (int, шаг 0.1 °C), access=3, areaType=0, areas=[0x0].
    // Раньше константа называлась «салон» и в расчёте применялось (raw-80)/2 — это давало
    // мусор; правильная нормализация: float как есть, иначе int/10.
    private const val PROP_AC_AMBIENT_TEMP = 0x2140A377
    /** F1: AC_AMBIENT_TEMP_INVALID — только диагностика (в UI не показываем). */
    private const val PROP_AC_AMBIENT_INVALID = 0x2140A376
    /**
     * F1 (поправка владельца 05.11: «наружную ищем в референсе»).
     * Референс CentralEXAuto 3.3.9, `com.geely.hvacstepperv2.TempStepperService`:
     *   `getFloatProperty(291505923, area)` → 291505923 == 0x11600703 == ENV_OUTSIDE_TEMPERATURE,
     *   зоны перебираются в порядке `f1286d0 = {0, 1, 4, 7}`, валидный диапазон −55…+70,
     *   int-ветка: −550…700 ⇒ /10, иначе −45…65 как есть; печать `%.1f`.
     * Приоритет запрета «0x11600703 не читать» снят этим решением владельца.
     */
    private const val PROP_ENV_OUTSIDE_TEMP = 0x11600703
    /** Зоны ENV_OUTSIDE_TEMPERATURE — порядок референса (TempStepperService.f1286d0). */
    private val ENV_OUTSIDE_AREAS = intArrayOf(0, 1, 4, 7)
    // Резервные ID — ТОЛЬКО для строки лога TEMP_CANDS, автопереключения нет.
    private const val PROP_TEMP_RESERVE_1 = 0x2160A6E9
    private const val PROP_TEMP_RESERVE_2 = 0x2140A673
    // Резервные 0x2160A6E9/0x2140A673 и ambient — только диагностика; источник наружной —
    // ENV_OUTSIDE_TEMPERATURE 0x11600703 с зонами (iter33h F1, итерация 33i ничего не меняет).

    @Volatile private var cpm: android.car.hardware.property.CarPropertyManager? = null
    @Volatile private var carObj: Car? = null
    @Volatile private var h: Handler? = null
    @Volatile private var dumpLogged = false
    // P-фикс: защита от гонки — один syncAll за раз, иначе 4 потока публикуют
    // в одни и те же notification id и перебивают друг друга (4.2%/42.4% в логе).
    private val syncInFlight = AtomicBoolean(false)
    private val tickSeq = AtomicInteger(0)
    @Volatile private var periodicRunning = false
    @Volatile private var lastTickLogAtMs = 0L
    @Volatile private var lastTickLogMs = 0L

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
                    // iter23 P4: полный VHAL-дамп (≈9 с, ~1700 свойств, 3400+ строк лога)
                    // НЕ запускается в рабочем пути. Только по явной кнопке
                    // «Диагностика VHAL» (Инструменты → Для разработчика) через dumpVhalOnDemand().
                    AppLog.i(TAG, "VHAL_DUMP: skipped in normal run (iter23 P4 — only on demand)")
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

    /**
     * iter23 P4: приёмник строк дампа. null — дамп не идёт (в рабочем тике его нет вообще),
     * иначе — накапливаем строки, чтобы сохранить их в отдельный файл отчёта.
     */
    private var dumpSink: StringBuilder? = null

    private fun dlog(msg: String) {
        AppLog.i(TAG, msg)
        dumpSink?.append(msg)?.append('\n')
    }

    /** iter23 P4: дамп только по кнопке «Диагностика VHAL» (не в рабочем пути). */
    @JvmStatic
    fun dumpVhalOnDemand(ctx: Context) {
        BgWorker.assertNotMain("StatusIcons.dumpVhalOnDemand")
        ensureCar(ctx)
        val t0 = android.os.SystemClock.uptimeMillis()
        val props = dumpKnownPropsCount(cpm)
        val sink = StringBuilder()
        dumpSink = sink
        try {
            dumpKnownProps()
        } finally {
            dumpSink = null
        }
        val dt = android.os.SystemClock.uptimeMillis() - t0
        // Отдельный файл отчёта (не в общем журнале приложения, не в рабочем тике).
        val outFile = runCatching {
            val f = java.io.File(ctx.filesDir, "vhal_dump_${System.currentTimeMillis()}.txt")
            f.writeText("VHAL_DUMP on demand dt=${dt}ms props≈$props\n" + sink)
            f
        }.getOrNull()
        AppLog.i(TAG, "VHAL_DUMP: end dt=${dt}ms props≈$props (on demand) file=${outFile?.absolutePath ?: "write failed"}")
    }

    private fun dumpKnownProps() {
        val m = cpm ?: return
        dlog( "VHAL_DUMP: start (bg thread=${Thread.currentThread().name})")
        dlog("=== VHAL CANDPROP dump start (look for 10°C/98.1% matches HU panel) ===")
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
            dlog("VHAL: propertyList size=${propList?.size ?: "null"}")
            for (cfg in (propList ?: emptyList())) {
                val pid = cfg.propertyId
                // Итерация 11: dump конфига свойства (доступ/areaType/supportedAreas)
                // — по этому можно понять, какое свойство реально поддерживается ГУ.
                val inRange = TEMP_RANGES.any { pid in it } ||
                        pid == 0x15400701 || pid == 0x15400703
                if (inRange) {
                    val areaIds = try { cfg.areaIds?.joinToString(",") { "0x%x".format(it) } } catch (_: Throwable) { "n/a" }
                    dlog( String.format("CANDCFG 0x%08X access=%d areaType=0x%08X areas=[$areaIds]", pid, cfg.access, cfg.areaType))
                }
                if (!inRange) continue
                for (a in ALL_AREAS) {
                    try {
                        val v = m.getFloatProperty(pid, a)
                        if (!v.isNaN() && v > -55f && v < 70f) {
                            dlog( String.format("CANDPROP 0x%08X@0x%02X F = %.2f °C", pid, a, v))
                        } else if (!v.isNaN() && v in 0f..101f) {
                            dlog( String.format("CANDPROP 0x%08X@0x%02X F = %.2f %%", pid, a, v))
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
                            dlog( String.format("CANDPROP 0x%08X@0x%02X I raw=%d = %.1f °C", pid, a, iv, fvT))
                        } else if (iv in 0..1010) {
                            dlog( String.format("CANDPROP 0x%08X@0x%02X I raw=%d = %.1f %%", pid, a, iv, iv/10.0f))
                        } else if (iv in 0..101) {
                            dlog( String.format("CANDPROP 0x%08X@0x%02X I raw=%d = %d %%", pid, a, iv, iv))
                        }
                    } catch (_: Exception) {}
                }
            }
            // iter16: явный проход по вендорным id из плагина, даже если они не в propertyList
            for (pid in VENDOR_PLUGIN_IDS) {
                for (a in ALL_AREAS) {
                    try {
                        val v = m.getFloatProperty(pid, a)
                        if (!v.isNaN()) dlog( String.format("CANDPROP(VEND) 0x%08X@0x%02X F = %.4f", pid, a, v))
                    } catch (_: Exception) {}
                    try {
                        val iv = m.getIntProperty(pid, a)
                        dlog( String.format("CANDPROP(VEND) 0x%08X@0x%02X I raw=%d", pid, a, iv))
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            dlog( "VHAL propertyList enumeration failed, fallback to PRECISE_COMMON+VENDOR scan: ${e.message}")
            val allFallback = PRECISE_COMMON + VENDOR_PLUGIN_IDS
            for (p in allFallback) for (a in ALL_AREAS) {
                try { val v = m.getFloatProperty(p,a); if (!v.isNaN()) dlog(String.format("CANDPROP(fb) 0x%08X@0x%02X F=%f",p,a,v)) } catch(_:Exception){}
                try {
                    val iv = m.getIntProperty(p,a)
                    dlog(String.format("CANDPROP(fb) 0x%08X@0x%02X I raw=%d",p,a,iv))
                } catch(_:Exception){}
            }
        }
        dlog("=== VHAL CANDPROP dump end ===")
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


    /** Чтение одного свойства: float → иначе int/10 (шаг 0.1 °C), диапазон −50…+70. */
    private fun readTempProp(m: android.car.hardware.property.CarPropertyManager, propId: Int, area: Int): Pair<Float?, String> {
        try {
            val f = m.getFloatProperty(propId, area)
            if (!f.isNaN()) {
                return if (f > -50f && f < 70f) f to String.format("%.1f", f) else null to "float=${f}(out of range)"
            }
        } catch (e: Exception) {
            // some VHALs throw for float on int props — это норма, идём в int-ветку
        }
        try {
            val iv = m.getIntProperty(propId, area)
            val v = iv / 10.0f
            return if (v > -50f && v < 70f) v to "int=$iv→$v" else null to "int=$iv(out of range)"
        } catch (e: Exception) {
            return null to "ERR:${e.message}"
        }
    }

    /**
     * F1 (iter33h): источник наружной температуры — ENV_OUTSIDE_TEMPERATURE `0x11600703` с area,
     * как в референсе (`TempStepperService.i(int)` / `g()`): float → иначе int (−550…700 ⇒ /10,
     * иначе −45…65 как есть), зоны {0,1,4,7}, валидный диапазон −55…+70, печать `%.1f`.
     * AC_AMBIENT_TEMP (0x2140A377) и AC_AMBIENT_TEMP_INVALID (0x2140A376) — ТОЛЬКО диагностика.
     */
    private fun readTempScan(): Float? {
        val m = cpm ?: run { AppLog.w(TAG, "TEMP: no CarPropertyManager"); return null }
        // 1) наружная — ровно как в референсе: зоны {0,1,4,7}, float → int.
        var outside: Float? = null
        var outsideArea = -1
        for (a in ENV_OUTSIDE_AREAS) {
            val v = readEnvOutsideProp(m, a)
            if (v != null) { outside = v; outsideArea = a; break }
        }
        if (outside != null && outsideArea >= 0) {
            tempSource = "HAL 0x11600703@0x%x".format(outsideArea)
            AppLog.i(TAG, "Exterior OK (ENV_OUTSIDE_TEMPERATURE) area=0x%x → %.1f°C".format(outsideArea, outside))
        } else {
            tempSource = "none"
            AppLog.w(TAG, "TEMP: ENV_OUTSIDE_TEMPERATURE (0x11600703) недоступен во всех зонах " +
                ENV_OUTSIDE_AREAS.joinToString(",") { "0x%x".format(it) } + " — показываем н/д")
        }
        // 2) диагностика: ambient + INVALID + резервные ID. В UI не идут.
        val (amb, ambRaw) = readTempProp(m, PROP_AC_AMBIENT_TEMP, 0)
        val inv = try { m.getIntProperty(PROP_AC_AMBIENT_INVALID, 0) } catch (e: Exception) { null }
        val (r1, _) = readTempProp(m, PROP_TEMP_RESERVE_1, 0)
        val (r2, _) = readTempProp(m, PROP_TEMP_RESERVE_2, 0)
        val fmt = { v: Float? -> v?.let { String.format(Locale.US, "%.1f", it) } ?: "н/д" }
        // TEMP_CANDS — одна строка на цикл опроса (H1: без повторов).
        AppLog.i(TAG, "TEMP_CANDS: A377=${fmt(amb)} A673=${fmt(r2)} A6E9=${fmt(r1)} " +
            "env_outside=${fmt(outside)} area=0x%x src=%s".format(outsideArea, tempSource))
        AppLog.i(TAG, "TEMP_SRC ambient=${fmt(amb)} invalid=${inv ?: "н/д"} env_outside=${fmt(outside)} " +
            "ambient_raw=$ambRaw")
        return outside
    }

    /**
     * F1: чтение ENV_OUTSIDE_TEMPERATURE с area — порт референса
     * (`TempStepperService.i(int)`, dex: `getFloatProperty(291505923, area)`, затем
     * `getIntProperty(291505923, area)`).
     */
    private fun readEnvOutsideProp(m: android.car.hardware.property.CarPropertyManager, area: Int): Float? {
        try {
            val f = m.getFloatProperty(PROP_ENV_OUTSIDE_TEMP, area)
            envOutsideToCelsius(f, null)?.let { return it }
        } catch (e: Exception) {
            // int-only проп — норма, идём в int-ветку
        }
        return try {
            envOutsideToCelsius(null, m.getIntProperty(PROP_ENV_OUTSIDE_TEMP, area))
        } catch (e: Exception) {
            null
        }
    }

    // F1: нормализация и формат наружной вынесены в OutsideTemp.kt
    // (`envOutsideToCelsius` / `formatOutsideTempC`) — их проверяет `TempFormatTest`.

    /**
     * iter33i P1 — read-only действие «Температуры: разведка».
     *
     * Список свойств, ID и зоны — в [TEMP_PROBE_TABLE] (ID сверены с эталоном имён машины
     * `app/src/main/assets/ex2_carprops.csv` и с VHAL-дампом). Строк «err=no_id» больше нет:
     * ID известен для каждого свойства, `err=` — только результат чтения.
     *
     * Формат строки: `TEMP_PROBE hex=0x… name=… area=0x… value=… err=<none|none(int)|ExceptionName>`.
     */
    fun tempProbe(ctx: android.content.Context): ToolsResult {
        val m = cpm ?: run {
            val t = android.os.SystemClock.elapsedRealtime()
            return ToolsResult("status.tempProbe", "Температуры: разведка", listOf(
                ToolsStep("probe", "TEMP_PROBE", t, t, 1, stderr = "нет CarPropertyManager (сервис не подключён)",
                    status = ToolsStep.Status.BLOCKED)))
        }
        val steps = ArrayList<ToolsStep>()
        for (e in TEMP_PROBE_TABLE) {
            for (area in e.areas) {
                val t = android.os.SystemClock.elapsedRealtime()
                var value = "н/д"
                var err = "none"
                if (e.floatFirst) {
                    try {
                        val f = m.getFloatProperty(e.propId, area)
                        value = if (f.isNaN()) "н/д" else String.format(Locale.US, "%.1f", f)
                    } catch (ex: Exception) {
                        err = ex.javaClass.simpleName
                        try {
                            value = m.getIntProperty(e.propId, area).toString()
                            err = "none(int)"
                        } catch (ex2: Exception) {
                            err = ex2.javaClass.simpleName
                        }
                    }
                } else {
                    try {
                        value = m.getIntProperty(e.propId, area).toString()
                    } catch (ex: Exception) {
                        err = ex.javaClass.simpleName
                        try {
                            val f = m.getFloatProperty(e.propId, area)
                            value = if (f.isNaN()) "н/д" else String.format(Locale.US, "%.1f", f)
                            err = "none(float)"
                        } catch (ex2: Exception) {
                            err = ex.javaClass.simpleName
                        }
                    }
                }
                val line = tempProbeLine(e.hex, e.name, area, value, err)
                AppLog.i(TAG, line)
                steps.add(ToolsStep("probe", line, t, t, if (err == "none" || err == "none(int)") 0 else 1,
                    stdout = line, status = ToolsStep.Status.OK))
            }
        }
        val t = android.os.SystemClock.elapsedRealtime()
        val hint = "Сравните с приборкой. Если число отличается — нажмите «Отправить» (уйдёт в отчёт)"
        steps.add(ToolsStep("hint", hint, t, t, 0, stdout = hint, status = ToolsStep.Status.OK))
        return ToolsResult("status.tempProbe", "Температуры: разведка", steps)
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
            // iter33 C3: полная строка — только при смене того, что реально отрисовано.
            val probeKey = describe + "|" + rank + "|" + text
            if (probeKey != lastKmIconProbe) {
                lastKmIconProbe = probeKey
                AppLog.i(TAG, "KmIconProbe: postStatusIcon $describe notifId=$notifId iconId=$iconId rank=$rank text=\"$text\" (изменилось)")
            } else {
                LogKit.trace("ICON_POST") { "KmIconProbe: postStatusIcon $describe notifId=$notifId rank=$rank text=\"$text\"" }
            }
        } catch (t: Throwable) { AppLog.e(TAG, "notify $notifId err", t) }
    }

    // pill-нотификация (109) удалена: вместо неё — две отдельные иконки batt/temp
    // с битмапами по геометрии CEX w0/kg#a.
    // Константы — из w0/kg#b (вызов a(...)): padH=10, scale=0.82f; gap=h(3) при scale>=0.5.
    private const val TEXT_DP = 56
    private const val SUFFIX_SCALE = 0.82f
    private const val PAD_H_DP = 10
    private const val GAP_DP = 3

    /** h(dp) — CEX w0/kg#h */
    private fun h(ctx: Context, dp: Int): Int =
        (dp * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /** i(x) — CEX w0/kg#i: Math.round(x*10)/10 → "%.1f" или целое */
    fun fmtOneDecimal(x: Float): String {
        val r = Math.round(x * 10f) / 10f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else String.format(Locale.US, "%.1f", r)
    }

    /**
     * РЕФЕРЕНС: w0/kg#a(ctx, main, suffix, color, padH, keepNum, modeKm, scale)
     *   вызов из w0/kg#b: a(ctx, main, suffix, color, **padH=10**, keepNum=true, modeKm=false, **scale=0.82f**)
     *   const/16 v4, 10 / const/4 v5, 1 / const/4 v6, 0 / const v7, 1062333317 (0.82f)
     * Геометрия один-в-один:
     *   TEXT_DP      = 56            (h(ctx,56))
     *   SUFFIX_SCALE = 0.82f         (mul-float suffixSize = scale * mainSize)
     *   PAD_H_DP     = 10            (h(ctx,10) слева и справа)
     *   GAP_DP       = 3             (h(ctx, scale<0.5 ? 1 : 3))
     *   bmpH         = max(1,(int)((descent-ascent)/0.61f))    ← 1058810102 = 0.61f
     *   bmpW         = max(fact, reserve), где при keepNum резерв под "-40"+"°C" и "100"+"%",
     *                  поэтому ширина иконки не «прыгает» при смене значения.
     * Текст рисуется со сдвигом f9 = max(0,(bmpW-fact)/2), чтобы цифры стояли по центру резерва.
     */
    private fun makeTextIconCex(ctx: Context, main: String, suffix: String, color: Int,
                                padHDp: Int = PAD_H_DP, keepNum: Boolean = true,
                                scale: Float = SUFFIX_SCALE, kind: String = ""): Icon {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color; typeface = Typeface.DEFAULT; isAntiAlias = true
        }
        val mainSize = h(ctx, TEXT_DP).toFloat()
        p.textSize = mainSize
        val mainW = p.measureText(main)
        val fm = p.fontMetrics
        val textH = fm.descent - fm.ascent
        val suffixSize = scale * mainSize
        val gapPx = h(ctx, if (scale < 0.5f) 1 else GAP_DP)
        val sfxPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color; typeface = Typeface.DEFAULT; isAntiAlias = true
            textSize = suffixSize
        }
        val sfxW = if (suffix.isNotEmpty()) sfxPaint.measureText(suffix) else 0f
        val bmpH = ((textH / 0.61f).toInt()).coerceAtLeast(1)
        val padH = h(ctx, padHDp)
        val factW = padH * 2 + (mainW + if (suffix.isNotEmpty()) gapPx + sfxW else 0f).toInt()

        // keepNum: резерв ширины под худший случай, как в эталоне.
        var bmpW = factW
        if (keepNum) {
            p.textSize = mainSize
            val negW = p.measureText("-40")
            val hundredW = p.measureText("100")
            val reserve = maxOf(
                padH * 2 + (negW + gapPx + sfxPaint.measureText("°C")).toInt(),
                padH * 2 + (hundredW + gapPx + sfxPaint.measureText("%")).toInt(),
            )
            bmpW = maxOf(factW, reserve)
        }
        if (bmpW < 1) bmpW = 1
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val y = ((bmpH - textH) / 2f) - fm.ascent
        val f9 = maxOf(0f, (bmpW - factW) / 2f)
        p.textSize = mainSize
        canvas.drawText(main, padH + f9, y, p)
        if (suffix.isNotEmpty()) canvas.drawText(suffix, padH + mainW + gapPx + f9, y, sfxPaint)

        // iter22 (ревью §2.1): фактическая геометрия в лог — по ней сразу видно, рисует ли
        // вендорный плагин битмап в intrinsic-размере или масштабирует его сам.
        val dens = ctx.resources.displayMetrics.density
        // iter33 C3: геометрия пишется, только когда она ИЗМЕНИЛАСЬ (это факт); одинаковые
        // повторы считаются и сворачиваются в ADB_TRACE_SUPPRESSED n=ICON_GEOM=…
        logIconGeom(kind.ifEmpty { main + suffix } + ":" + bmpW + "x" + bmpH) {
            "ICON_GEOM: kind=${kind.ifEmpty { main + suffix }} w=${bmpW}px h=${bmpH}px " +
                "(w=${"%.1f".format(bmpW / dens)}dp h=${"%.1f".format(bmpH / dens)}dp) " +
                "glyph=${"%.1f".format((fm.descent - fm.ascent) / dens)}dp " +
                "textSize=${"%.1f".format(mainSize)} suffix=$suffix padH=${padHDp}dp gap=${GAP_DP}dp keepNum=$keepNum"
        }
        return Icon.createWithBitmap(bmp)
    }

    /**
     * Иконка Wi-Fi: 3 дуги + точка, РАЗМЕР по той же геометрии (рисуется в том же
     * TEXT_DP масштабе, чтобы высота иконки совпадала с batt/temp в статус-баре).
     */
    /**
     * iter23 P2.5: Wi-Fi в том же визуальном масштабе, что цифры.
     *
     * iter24 P2.5: причина поломки 1.0.45 — у Paint не был задан textSize, поэтому
     * fontMetrics были от дефолтных 12 px → bmpH = 23 px при глифе 40,3 dp (лог 1.0.45:
     * `ICON_GEOM: kind=wifi w=66px h=23px glyph=40,3dp` против `kind=batt h=111px`).
     *
     * Факт из лога 1.0.44: битмап текстовых иконок 111 dp, но сами цифры занимают
     * высоту заглавной ≈ 0.72 × textSize ≈ 40 dp (остальное — поля от деления на 0.61).
     * Дуги Wi-Fi рисовались почти на весь битмап (54 dp) — отсюда «немного больше».
     * Теперь: битмап ТОЙ ЖЕ высоты, что у текстовых (`(descent-ascent)/0.61`), а сама
     * пиктограмма вписана в `glyph = 0.72 × textSize`.
     */
    private fun makeWifiIconCex(ctx: Context, level: Int, color: Int): Icon {
        val textPx = h(ctx, TEXT_DP).toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; typeface = Typeface.DEFAULT; isAntiAlias = true; style = Paint.Style.FILL
            // iter24 P2.5: textSize ОБЯЗАТЕЛЬНО до fontMetrics — иначе метрики берутся от
            // дефолтных 12 px и битмап получается 23 px при глифе 40 dp (обрезало сверху/снизу).
            this.textSize = textPx
        }
        val fm = p.fontMetrics
        val capPx = textPx * 0.72f                 // высота заглавной цифры = визуальный размер цифр
        val bmpH = ((fm.descent - fm.ascent) / 0.61f).toInt().coerceAtLeast(1)  // как у текстовых
        val padH = h(ctx, PAD_H_DP)
        // Страховка: глиф не может быть больше битмапа (иначе обрезка/растяжение).
        val fit = if (capPx > bmpH * 0.98f) (bmpH * 0.98f) / capPx else 1f
        val glyphPx = capPx * fit
        val glyphW = glyphPx * 1.15f               // чуть шире, чем высота — иначе дуги слипнутся
        val bmpW = (padH * 2 + glyphW).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = bmpW / 2f
        // Точка внизу и дуги сверху в сумме занимают ровно glyphPx по вертикали.
        val dotR = glyphPx * 0.09f
        val dotCy = bmpH / 2f + glyphPx / 2f - dotR
        val maxR = glyphPx - dotR * 2f             // внешний радиус самой большой дуги
        canvas.drawCircle(cx, dotCy, dotR, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = glyphPx * 0.10f
        p.strokeCap = Paint.Cap.ROUND
        for (k in 1..3) {
            if (k > level + 1) p.alpha = 0x55      // неактивные дуги бледнее (регресс-зона: уровни)
            val r = maxR * (0.38f + (k - 1) * 0.31f)
            val oval = RectF(cx - r, dotCy - r, cx + r, dotCy + r)
            canvas.drawArc(oval, -135f, 90f, false, p)
            p.alpha = 0xFF
        }
        val dens = ctx.resources.displayMetrics.density
        logIconGeom("wifi:" + bmpW + "x" + bmpH + ":lvl" + level) {
            "ICON_GEOM: kind=wifi w=${bmpW}px h=${bmpH}px " +
                "(w=${"%.1f".format(bmpW / dens)}dp h=${"%.1f".format(bmpH / dens)}dp) " +
                "glyph=${"%.1f".format(glyphPx / dens)}dp level=$level padH=${PAD_H_DP}dp " +
                "fit=${"%.2f".format(fit)} textSize=${"%.1f".format(textPx / dens)}dp"
        }
        return Icon.createWithBitmap(bmp)
    }

    private fun cancel(ctx: Context, id: Int) {
        try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id) } catch(_:Throwable){}
    }

    // ─── Public API (совместимый с существующими callers) ───
    /** iter33 C3: последние записанные значения — чтобы не повторять одинаковые строки. */
    @Volatile private var lastExtTempPrefValue: Boolean? = null
    @Volatile private var lastKmIconProbe: String = ""
    @Volatile private var lastIconGeom: String = ""

    /** iter33 C3: подробная строка геометрии — только при изменении отрисованного. */
    private fun logIconGeom(key: String, msg: () -> String) {
        if (lastIconGeom != key) {
            lastIconGeom = key
            AppLog.i(TAG, msg() + " (изменилось)")
        } else {
            // H1.5: та же геометрия — строку не печатаем, только счётчик подавления
            // (одна сводка в минуту, без молчаливой потери).
            LogKit.suppressed("ICON_GEOM")
        }
    }

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
        val t0 = android.os.SystemClock.uptimeMillis()
        var reads = 0
        try {
            reads = syncAllImpl(ctx.applicationContext, seq)
        } finally {
            syncInFlight.set(false)
            val dt = android.os.SystemClock.uptimeMillis() - t0
            // iter23 P4: метрика тика — раз в минуту пишем, сколько занял опрос VHAL.
            lastTickLogMs = dt
            val now = android.os.SystemClock.uptimeMillis()
            // iter33 C3: подробный TICK — только при нарушении бюджета (overdue) либо первые
            // 20 раз на сеанс; остальное считается и видно в HEARTBEAT (exec/min) и в
            // `ADB_TRACE_SUPPRESSED n=TICK=…`. Факт «тик был» не теряется — он в счётчике.
            if (now - lastTickLogAtMs > 60_000L || dt > 100) {
                lastTickLogAtMs = now
                AppLog.i(TAG, "TICK: dt=${dt}ms reads=$reads overdue=${dt > 100}")
            } else {
                LogKit.trace("TICK") { "TICK: dt=${dt}ms reads=$reads overdue=false" }
            }
        }
    }

    private fun syncAllImpl(app: Context, seq: Int): Int {
        var reads = 0
        ensureCar(app)
        val showBatt = prefs(app).getBoolean(PREF_BATTERY, false)
        val showTemp = prefs(app).getBoolean(PREF_EXT_TEMP, false)
        val showWifi = prefs(app).getBoolean(PREF_WIFI, false)
        Log.d(TAG,"syncAll seq=$seq batt=$showBatt temp=$showTemp wifi=$showWifi cpm=${cpm!=null}")
        // Итерация 8: трассировка префа PREF_EXT_TEMP — файл + значение + поток
        // (чтобы понять, кто пишет false между тиками).
        val pf = prefs(app)
        val pPath = "/data/data/${app.packageName}/shared_prefs/voiceapp3.xml"
        // iter33 C3: полная трассировка префа — ТОЛЬКО при смене значения (это и есть факт),
        // одинаковые чтения считаются и сворачиваются в ADB_TRACE_SUPPRESSED.
        if (showTemp != lastExtTempPrefValue) {
            lastExtTempPrefValue = showTemp
            AppLog.i(TAG, "PREF TRACE: read  file=$pPath key=$PREF_EXT_TEMP value=$showTemp seq=$seq thread=${Thread.currentThread().name} (изменилось)")
        } else {
            LogKit.trace("PREF_TRACE") { "PREF TRACE: read key=$PREF_EXT_TEMP value=$showTemp seq=$seq" }
        }

        var battStr: String? = null; var battPct: Float? = null; var battHas = false
        var tempStr: String? = null; var tempVal: Float? = null; var tempHas = false
        var wifiStr: String? = null; var wifiLvl: Int = -1; var wifiHas = false

        if (showBatt) {
            AppLog.d(TAG, "BATT: toggle=on read start")
            val soc = readSocScan().also { reads++ }
            if (soc != null) {
                // iter22 §1: одна цифра после запятой (42.4, 100.0, 7.0) — как w0/kg#i("%.1f").
                val socStr = fmtOneDecimal(soc)
                val icon = makeTextIconCex(app, socStr, "%", Color.WHITE, kind = "batt")
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
            val t = readTempScan().also { reads++ }
            // iter22 §2: число показываем ВСЕГДА, если хоть раз его прочитали
            // (аналогично OEM-панели "10°C"). "--" только при полном отсутствии данных.
            // F1: подмены на ambient нет — если наружная не читается, честно «н/д».
            val display: Float? = t ?: lastTempC
            if (display != null) {
                val tStr = fmtOneDecimal(display)
                val sign = if (display > 0f) "+" else ""
                val icon = makeTextIconCex(app, tStr, "°C", Color.WHITE, kind = "temp")
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
                val icon = makeTextIconCex(app, "н/д", "", Color.WHITE, kind = "temp")
                postNotif(app, NOTIF_TEMP, ICON_ID_TEMP, RANK_TEMP,
                    "Снаружи н/д", "ENV_OUTSIDE_TEMPERATURE недоступен", "StatusIcon_OutsideTemperature", icon,
                    telemetryPi(app, NOTIF_TEMP))
                tempStr = "н/д"; tempHas = true
                AppLog.w(TAG, "TEMP: no data yet → \"н/д\" shown")
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


        // iter33h E1: проталкиваем только batt и exttemp (значения %.1f) + via.
        // `persist.sys.hvacdigital.temp` не пишем: уставку климата плагин читает сам.
        // Если значение ещё не прочитано (первый tick) — не пишем ничего (пропускаем).
        pushHvacDigitalPropsIfEnabled(app)
        return reads
    }

    /**
     * iter16: протолкнуть значения батареи/температуры/наружки в persist.sys.hvacdigital.*
     * для строкового плагина SystemUI. Пишет ЗНАЧЕНИЯ, а не 0/1; если тогл выключен — 0.
     * Работает на BgWorker (вызывается из syncAllImpl, который уже на BgWorker).
     */
    /** iter33 B5: последняя отправленная комбинация — setprop только при изменении. */
    @Volatile private var lastPushedHvac: String = ""
    /** H1: `HVACDIGITAL_PUSH skip reason=unchanged` — не чаще одного раза в 5 минут. */
    @Volatile private var lastPushSkipLogAtMs: Long = 0L
    private val PUSH_SKIP_LOG_EVERY_MS = 5 * 60_000L

    /** `via` — round-trip значение, которое пишет само приложение (AdbClient, `persist.sys.hvacdigital.via`). */
    private fun lastViaForLog(): String = try {
        Runtime.getRuntime().exec(arrayOf("getprop", SYS_HVAC_VIA)).inputStream.bufferedReader().readText().trim()
    } catch (t: Throwable) { "н/д" }

    private fun pushHvacDigitalPropsIfEnabled(app: Context) {
        val p = prefs(app)
        // iter33i P0-a: в чужие props уходят ТОЛЬКО флаги 0|1 — ровно как в референсе
        // (`w0/x7.java:137-147`: setprop …temp/batt/exttemp = "1"/"0"). Значения (°C, %) в эти
        // свойства НЕ пишем: наш плагин их не читает вовсе (0 вхождений "hvacdigital" в APK),
        // а цифры нашей строки состояния рисуются своими иконками ниже.
        val tempOn = p.getBoolean(PREF_HVAC_TEMP, false)
        val battOn = p.getBoolean(PREF_HVAC_BATT, false)
        val extOn = p.getBoolean(PREF_HVAC_EXT_TEMP, false)
        val command = HvacDigitalProps.flagsCommand(tempOn, battOn, extOn)
        // E1.1 (iter33h): setprop persist.sys.hvacdigital.temp УБРАН. Уставку климата плагин
        // читает сам (com.flyme.auto.hvacapi.widget.HvacVehicleTempWidget.getPropertyId() →
        // 358614275 == HVAC_TEMPERATURE_SET, area=1), а наше «значение» уходило в слот климата
        // и ложилось поверх стандартной полоски.
        // iter33 B5: dirty-флаг. Раньше setprop уходил каждые 15 с «на всякий случай»
        // (44 записи за 10 минут). Теперь — только когда набор флагов реально изменился.
        val fingerprint = HvacDigitalProps.flagsFingerprint(tempOn, battOn, extOn)
        if (fingerprint == lastPushedHvac) {
            // H1: не чаще одного раза в 5 минут.
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastPushSkipLogAtMs >= PUSH_SKIP_LOG_EVERY_MS) {
                lastPushSkipLogAtMs = now
                AppLog.d(TAG, "HVACDIGITAL_PUSH skip reason=unchanged " + fingerprint)
            }
            return
        }
        // verify echo: читаем обратно ровно то, что записали (три флага), плюс `via` — ТОЛЬКО чтение.
        val script = command.replace("&& echo FLAGS_OK",
            "&& echo \"temp=\$(getprop ${HvacDigitalProps.PROP_TEMP}) batt=\$(getprop ${HvacDigitalProps.PROP_BATT}) " +
                "exttemp=\$(getprop ${HvacDigitalProps.PROP_EXT}) via=\$(getprop ${HvacDigitalProps.PROP_VIA})\"")
        try {
            val (rc, out) = AdbClient.execute(script, 5000)
            if (rc == 0) lastPushedHvac = fingerprint
            AppLog.i(TAG, "HVACDIGITAL_PUSH rc=$rc $fingerprint out=${out.replace('\n', ' ').take(200)}")
        } catch (t: Throwable) {
            AppLog.w(TAG, "HVACDIGITAL_PUSH failed: ${t.message}")
        }
    }

    @JvmStatic fun cancelAll(ctx: Context) {
        cancel(ctx, NOTIF_BATT); cancel(ctx, NOTIF_TEMP); cancel(ctx, NOTIF_WIFI)
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
     * Цифровая индикация климата в статус-баре — плашка плагина GeelyAutoSystemUIPlugin.
     *
     * iter33h E1: `persist.sys.hvacdigital.temp` (уставка климата) приложением НЕ пишется —
     * плагин читает её сам через CarPropertyManager; значения приложение даёт только для
     * `batt` и наружной (`exttemp`), см. pushHvacDigitalPropsIfEnabled.
     * F2: ключ наружной — ОДИН (`exttemp`), поэтому списков «оба свойства» больше нет.
     */
    fun sysPropFor(pref: String): String = when (pref) {
        PREF_HVAC_BATT -> SYS_HVAC_BATT
        PREF_HVAC_EXT_TEMP -> SYS_HVAC_EXT_EXTTEMP
        // PREF_HVAC_TEMP: ключа нет — см. E1, значение уставки приложение не публикует.
        else -> ""
    }

    /** Все свойства внешней температуры, которые надо выставлять одновременно — одно. */
    private val SYS_HVAC_EXT_ALL = arrayOf(SYS_HVAC_EXT_EXTTEMP)

    @JvmStatic fun setHvacDigital(ctx: Context, pref: String, value: Boolean): ToolsResult {
        val t0 = android.os.SystemClock.uptimeMillis()
        Prefs2.putBoolean(ctx, pref, value)
        // iter16: при ВКЛЮЧЕНИИ любого из трёх тоглов — пытаемся заинсталлить плагин
        // (mount --bind на OEM com.flyme.auto.systemuiplugin). Плагин уже лежит в
        // app/src/main/res/raw/geely_auto_systemui_plugin_digital.apk (18 МБ).
        // iter33 A2: статус тумблера ВЫВОДИТСЯ из установки плагина. Раньше здесь стоял
        // захардкоженный ToolsStep.Status.OK — из-за него панель показывала «SUCCESS»,
        // хотя рядом в логе было `VERIFY hvacdigital.enable: FAILED (rc=0 VIA=? PM_PATH= …)`.
        var installNote = "plugin-not-invoked"
        var pluginStatus: ToolsStep.Status? = null
        var pluginReason = "plugin-not-invoked"
        if (value) {
            val r = try {
                HvacDigitalPlugin.enable(ctx)
            } catch (t: Throwable) {
                AppLog.w(TAG, "HVACDIGITAL: plugin.enable failed", t)
                null
            }
            pluginStatus = when {
                r == null -> ToolsStep.Status.FAILED
                r.steps.isEmpty() -> ToolsStep.Status.FAILED
                r.steps.any { it.status == ToolsStep.Status.FAILED } -> ToolsStep.Status.FAILED
                r.steps.any { it.status == ToolsStep.Status.BLOCKED } -> ToolsStep.Status.BLOCKED
                r.steps.all { it.status == ToolsStep.Status.OK } -> ToolsStep.Status.OK
                else -> ToolsStep.Status.PARTIAL
            }
            pluginReason = r?.summary ?: "enable бросил исключение"
            val cnt = r?.steps?.size ?: 0
            installNote = "plugin.enable steps=" + cnt + " status=" + pluginStatus +
                " reason=" + pluginReason.take(120)
            AppLog.i(TAG, "HVACDIGITAL: plugin.enable -> $installNote")
        }
        // Сразу же проталкиваем текущие значения (а не 0/1) — на случай, если пользователь
        // включил тогл после того, как syncAll уже прочитал данные.
        BgWorker.post("StatusIcons.hvacdigital.push") { pushHvacDigitalPropsIfEnabled(ctx.applicationContext) }
        // iter33 A2: хардкод Status.OK удалён — статус берётся из результата enable().
        val finalStatus = pluginStatus ?: ToolsStep.Status.OK
        val step = ToolsStep(
            step = "hvacdigital.$pref",
            command = "prefs.put($pref=$value) + push values + $installNote",
            startedAtMs = t0, finishedAtMs = android.os.SystemClock.uptimeMillis(),
            exitCode = if (finalStatus == ToolsStep.Status.OK || finalStatus == ToolsStep.Status.PARTIAL) 0 else 1,
            stdout = installNote,
            stderr = if (finalStatus == ToolsStep.Status.OK) "" else pluginReason.take(300),
            expected = if (value) "плагин смонтирован (mounted=true)" else "$pref=false",
            actual = "$pref=$value; $installNote",
            status = finalStatus
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

    /**
     * iter33 B2: в watchdog'е — ТОЛЬКО чтение состояния.
     *
     * Что было (1.0.52): каждые 60 с вызывался `HvacDigitalPlugin.enable` (17 раз за 10 минут),
     * а внутри — `setenforce 0`, `kill SystemUI` (SYSUI_PID_BEFORE=1174 → AFTER=20740),
     * чистка dalvik-cache и `pm install`. Плюс сброс свойств в 0 через setprop — цифры
     * в статус-баре гасились каждую минуту.
     *
     * Что стало: плагин ставится ТОЛЬКО по клику (setHvacDigital → HvacDigitalPlugin.enable,
     * один раз за процесс). Здесь — `checkStatus` (pm path + /proc/mounts, без записи) и,
     * если не смонтировано, ОДНА строка в лог. Автоповторов нет.
     */
    /**
     * iter33b A1 — boot-вход установки плагина климата. Вызывается ТОЛЬКО из boot-ветки
     * ([EXTRA_BOOT_RESTORE]), не из watchdog'а и не по таймеру.
     *
     * Здесь только проверка «тумблер включён»; все предохранители (одна попытка за загрузку,
     * read-first по mount+inode, отсутствие повторов) — внутри [HvacDigitalPlugin.bootInstall].
     */
    @JvmStatic fun bootInstallHvacDigital(ctx: Context) {
        val app = ctx.applicationContext
        val p = prefs(app)
        val anyOn = p.getBoolean(PREF_HVAC_BATT, false) ||
                   p.getBoolean(PREF_HVAC_TEMP, false) ||
                   p.getBoolean(PREF_HVAC_EXT_TEMP, false)
        if (!anyOn) {
            AppLog.i(TAG, "HVAC_BOOT: skip (toggle off)")
            return
        }
        BgWorker.post("StatusIcons.hvacdigital.bootinstall") {
            runCatching { HvacDigitalPlugin.bootInstall(app) }
                .onFailure { AppLog.w(TAG, "HVAC_BOOT: bootInstall threw", it) }
        }
    }

    @JvmStatic fun bootRestoreHvacDigital(ctx: Context) {
        val app = ctx.applicationContext
        val p = prefs(app)
        val anyOn = p.getBoolean(PREF_HVAC_BATT, false) ||
                   p.getBoolean(PREF_HVAC_TEMP, false) ||
                   p.getBoolean(PREF_HVAC_EXT_TEMP, false)
        if (!anyOn) return
        BgWorker.post("StatusIcons.hvacdigital.probe") {
            val st = runCatching { HvacDigitalPlugin.checkStatus(app) }.getOrNull()
            if (st == null) {
                AppLog.w(TAG, "HVAC_PROBE: checkStatus threw (watchdog, no action)")
                return@post
            }
            if (st.overlayMounted) {
                LogKit.stateHvac("mounted")
                AppLog.i(TAG, "HVAC_PROBE pm_path=" + (st.oemPkgPath ?: "?") +
                    " mounted=true src_inode=- tgt_inode=- selinux=- via=bind rc=- props=- (watchdog, read-only)")
            } else {
                LogKit.stateHvac("not")
                AppLog.i(TAG, "HVAC_PROBE: not mounted (watchdog, no action) pm_path=" + (st.oemPkgPath ?: "?") +
                    " mounted=false reason=" + st.mountReason + " shadowed=" + st.shadowedByInstall)
                AppLog.i(TAG, "HVAC_RESULT: PARTIAL reason=not_mounted_no_action_in_watchdog")
            }
        }
    }
}
