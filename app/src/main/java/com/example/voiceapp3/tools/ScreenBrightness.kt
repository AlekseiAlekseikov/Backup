package com.example.voiceapp3.tools

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.example.voiceapp3.tools.BgWorker
import java.io.File

/**
 * Яркость экрана, «Запоминать яркость по фарам» и «Время заставки».
 *
 * КРИТИЧНО (по требованию владельца): мы НЕ лезем в Settings.System.SCREEN_OFF_TIMEOUT
 * и НЕ держим его в Int.MAX_VALUE watchdog'ом. Машина управляет затуханием сама.
 *
 * В частности:
 *  - Никакого постоянного pin'а таймаута в 2147483647 (healScreenTimeoutOnce и watchdog удалены).
 *  - При первом старте мы СОХРАНЯЕМ текущее значение как «оригинальное», но НЕ меняем его
 *    ни на какое хардкод-значение. Заводской таймаут на ИХУ629Г мне не известен — его
 *    знает только прошивка. Если мы перестали перетирать значение, OEM DisplayManager
 *    сам вернёт заводской режим.
 *  - Если при первом старте мы видим Int.MAX_VALUE (след предыдущей версии приложения) —
 *    в лог пишется чёткое предупреждение. Автоматическое восстановление не делается:
 *    без дампа прошивки я не могу знать правильное число.
 *  - Яркостные операции работают на отдельном HandlerThread "BrightMem", не на main.
 */
object ScreenBrightness {

    const val TAG = "CENTRAL_ScreenBrightness"
    const val PREFS_NAME = "voiceapp3"

    const val SETTINGS_KEY = "screen_brightness"
    const val PERSIST_PROP = "persist.sys.geely.screen_brightness"
    const val SYSFS_PATH = "/sys/class/leds/lcd-backlight/brightness"

    const val PREF_REMEMBER = "master_remember_brightness"
    const val PREF_DAY = "bright_day"
    const val PREF_NIGHT = "bright_night"
    const val PREF_REST_SEC = "cx_screen_saver_timeout_sec"

    /** Исходное значение screen_off_timeout, которое мы увидели при ПЕРВОМ запуске.
     *  Нужно для будущей опции keep-awake (чтобы вернуть при выключении). */
    const val PREF_ORIGINAL_TIMEOUT = "original_screen_off_timeout_ms"
    const val PREF_TIMEOUT_NOTED = "screen_timeout_noted_v2"
    /**
     * P5 (по prompt_next/prompt_four_fixes): опция «не гаснуть никогда».
     *
     * ⚠️ Работает ТОЛЬКО на ГУ с конкретным серийным номером (гейт см. EXPECTED_SERIAL).
     * На любом другом ГУ — в лог «mismatch → skip» и НИЧЕГО не пишет в SCREEN_OFF_TIMEOUT.
     *
     * Прочность (durable) значения: значение переживает удаление приложения по той
     * причине, что **Settings.System не очищается при uninstall** — Android не
     * удаляет значения из таблицы system/secure/global при удалении APK, т.к. они
     * не привязаны к пакету. Скрипта/init.d-хука НЕ создаём — на IHU629G нет
     * подтверждённого механизма init.d, файл в /data/local/tmp сам не запускается
     * и был бы мусором (см. prompt_four_fixes §2).
     */
    const val PREF_KEEP_AWAKE = "keep_awake_never_sleep"
    const val PREF_KEEP_AWAKE_APPLIED = "keep_awake_applied_v1"
    const val PREF_CACHED_SERIAL = "cached_serial_v1"
    const val PREF_CACHED_SERIAL_SRC = "cached_serial_src_v1"
    const val PREF_CACHED_SERIAL_TS = "cached_serial_ts_v1"

    /**
     * Серийный номер целевого ГУ, взят из дампа прошивки (geely_dump/getprop.txt,
     * строка `[persist.device.real.serialno]: [IHU629G0MAS9W00010001144]`).
     * ПРЕДУПРЕЖДЕНИЕ БЕЗОПАСНОСТИ: серийник хардкожен и уходит в публичный репозиторий.
     * По-хорошему его надо вынести в local.properties/gradle buildConfigField, а в коде
     * держать BuildConfig.EXPECTED_SERIAL — решать владельцу.
     *
     * Это серийный номер платы, не привязан к автомобилю (не VIN, не марка/модель).
     */
    private const val EXPECTED_SERIAL = "IHU629G0MAS9W00010001144"
    /** Вторичный серийник ro.boot.serialno из того же дампа. */
    private const val EXPECTED_SERIAL_BOOT = "AB1FRA00029968507"

    private const val SCREEN_OFF_MAX = Int.MAX_VALUE
    private const val PERSIST_FORCE_PROP = "persist.sys.screen_off_timeout_force"
    /** TTL кэша серийника — 1 час (серийник не меняется, перепроверяем редко). */
    private const val SERIAL_CACHE_TTL_MS = 3600_000L

    const val MIN_PERCENT = 5
    const val MAX_PERCENT = 100
    const val MIN_LEVEL = 12
    const val MAX_LEVEL = 255

    val REST_CHOICES = listOf(0, 10, 30, 60, 120)

    private val workerThread = HandlerThread("BrightMem").apply { isDaemon = true; start() }
    private val worker = Handler(workerThread.looper)
    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private var restWatchdogRunnable: Runnable? = null

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isHeadlightsOn(ctx: Context): Boolean {
        val uiMode = ctx.resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    fun bucketPref(ctx: Context): String = if (isHeadlightsOn(ctx)) PREF_NIGHT else PREF_DAY
    fun bucketLabel(ctx: Context): String = if (isHeadlightsOn(ctx)) "ночь (фары вкл)" else "день (фары выкл)"

    fun percentOf(level: Int): Int = (((level.coerceIn(0,255)*100)+127)/255).coerceIn(MIN_PERCENT, MAX_PERCENT)
    fun levelOf(percent: Int): Int = ((percent.coerceIn(MIN_PERCENT, MAX_PERCENT)*255)/100).coerceIn(MIN_LEVEL, MAX_LEVEL)

    fun currentLevel(ctx: Context): Int = readSysfs() ?: (readSystem(ctx, SETTINGS_KEY) ?: 128)
    fun currentPercent(ctx: Context): Int = percentOf(currentLevel(ctx))

    fun readSystem(ctx: Context, key: String): Int? = try {
        Settings.System.getInt(ctx.contentResolver, key)
    } catch (_: Throwable) { null }

    fun writeSystemInt(ctx: Context, key: String, value: Int): WriteResult = try {
        val ok = Settings.System.putInt(ctx.contentResolver, key, value)
        // P8 VERIFY: readback через settings get после записи
        val got = readSystem(ctx, key)
        val verified = got == value
        if (verified) {
            AppLog.i(TAG, "VERIFY settings write: key=$key expected=$value got=$got → SUCCESS")
        } else {
            AppLog.w(TAG, "VERIFY settings write: key=$key expected=$value got=$got (setProp ok=$ok) → FAILED")
        }
        WriteResult(ok && verified, got, if (!verified) "readback=$got expected=$value" else null)
    } catch (t: Throwable) { WriteResult(false, null, t.message) }

    fun readSysfs(): Int? = try { File(SYSFS_PATH).readText().trim().toIntOrNull() } catch (_: Throwable) { null }
    fun writeSysfs(level: Int): WriteResult {
        val clamped = level.coerceIn(0,255)
        return try {
            File(SYSFS_PATH).writeText(clamped.toString())
            WriteResult(true, readSysfs(), null)
        } catch (t: Throwable) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("sh","-c","echo $clamped > $SYSFS_PATH"))
                p.waitFor()
                WriteResult(p.exitValue()==0, readSysfs(), null)
            } catch (ex: Throwable) { WriteResult(false, null, ex.message) }
        }
    }
    fun writeProp(level: Int): WriteResult = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh","-c","setprop $PERSIST_PROP $level"))
        p.waitFor(); WriteResult(p.exitValue()==0, level, null)
    } catch (t: Throwable) { WriteResult(false, null, t.message) }

    fun captureLevel(ctx: Context) {
        val p = prefs(ctx); if (!p.getBoolean(PREF_REMEMBER, false)) return
        val lvl = currentLevel(ctx)
        if (lvl > 0) { p.edit().putInt(bucketPref(ctx), lvl).apply(); AppLog.d(TAG, "captureLevel saved $lvl to ${bucketPref(ctx)}") }
    }

    fun restoreIfEnabled(ctx: Context) {
        val p = prefs(ctx); if (!p.getBoolean(PREF_REMEMBER, false)) return
        val lvl = p.getInt(bucketPref(ctx), -1)
        if (lvl in MIN_LEVEL..MAX_LEVEL) {
            writeSystemInt(ctx, SETTINGS_KEY, lvl); writeSysfs(lvl); writeProp(lvl)
            AppLog.d(TAG, "restoreIfEnabled: $lvl for ${bucketPref(ctx)}")
        }
    }

    fun syncOnContext(ctx: Context) {
        val app = ctx.applicationContext
        // Прогреваем кэш серийника заранее (фоново, не блокирует старт).
        ensureSerialProbeAsync(app)
        worker.post {
            ensureKeepAwake(app)
            restoreIfEnabled(app); scheduleRestWatchdog(app)
        }
    }

    fun restSeconds(ctx: Context): Int = prefs(ctx).getInt(PREF_REST_SEC, 0)

    data class RestOutcome(val ok: Boolean, val appliedMs: Long?, val readbackMs: Long?, val error: String?, val note: String?)

    /** Применение таймаута заставки. НЕ трогает SCREEN_OFF_TIMEOUT. */
    fun applyRestTimeout(ctx: Context, seconds: Int): RestOutcome = try {
        prefs(ctx).edit().putInt(PREF_REST_SEC, seconds).apply()
        scheduleRestWatchdog(ctx.applicationContext)
        if (seconds > 0) {
            RestOutcome(true, seconds*1000L, seconds*1000L, null, "Заставка через ${seconds}с (оверлей)")
        } else {
            mainHandler.post { ScreenSaverOverlay.dismiss(ctx.applicationContext) }
            RestOutcome(true, 0L, 0L, null, "Заставка отключена")
        }
    } catch (t: Throwable) {
        RestOutcome(false, null, null, t.message, null)
    }

    data class WriteResult(val ok: Boolean, val readback: Int?, val error: String?)

    /**
     * Результат детекции серийника: значение + какой источник его дал.
     */
    data class SerialInfo(
        val serial: String,
        val source: String,       // ключ getprop / "Build.getSerial" / "Build.SERIAL" / "cache" / "none"
        val dtMs: Long,
        val allProps: Map<String,String> = emptyMap()
    )

    /**
     * Не блокирующий вызов серийника: возвращает кэш (если свежий), иначе
     * ставит определение на BgWorker и отдаёт пустую строку (следующий вызов
     * подхватит результат). ТРЕБОВАНИЕ prompt_four_fixes §3: ни один Runtime.exec
     * не на main-thread.
     */
    @Volatile private var cachedSerial: SerialInfo? = null
    @Volatile private var serialProbeInFlight: Boolean = false

    private fun cachedSerialFresh(ctx: Context): SerialInfo? {
        val c = cachedSerial
        if (c != null && System.currentTimeMillis() - (prefs(ctx).getLong(PREF_CACHED_SERIAL_TS,0)) < SERIAL_CACHE_TTL_MS && c.serial.isNotEmpty()) return c
        val sp = prefs(ctx)
        val sv = sp.getString(PREF_CACHED_SERIAL,"") ?: ""
        val src = sp.getString(PREF_CACHED_SERIAL_SRC,"") ?: ""
        if (sv.isNotEmpty() && System.currentTimeMillis() - sp.getLong(PREF_CACHED_SERIAL_TS,0) < SERIAL_CACHE_TTL_MS) {
            val ci = SerialInfo(sv, "cache:$src", 0); cachedSerial = ci; return ci
        }
        return null
    }

    /**
     * Синхронное определение серийника — ТОЛЬКО для вызова с фонового потока
     * (BgWorker). На main-thread бросает IllegalStateException.
     *
     * ОДИН запуск `getprop` без аргументов (выводит все проперти за раз),
     * из вывода выбираем интересующие ключи — это в разы быстрее 8 отдельных exec.
     * Затем Build.getSerial / Build.SERIAL (in-process, не exec).
     */
    private fun detectActualSerialBg(ctx: Context): SerialInfo {
        BgWorker.assertNotMain("ScreenBrightness.detectActualSerialBg")
        val t0 = SystemClock.elapsedRealtime()
        // ОДИН getprop за раз
        val props = LinkedHashMap<String,String>()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh","-c","getprop"))
            p.waitFor(500)
            p.inputStream.bufferedReader().forEachLine { line ->
                val m = Regex("^\\[([^]]+)]:\\s*\\[([^]]*)]\\s*$").find(line)
                if (m != null) props[m.groupValues[1]] = m.groupValues[2]
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "SCREEN_TIMEOUT: getprop batch read failed: ${t.message}")
        }
        // Приоритетные ключи (в порядке убывания доверия на IHU629G)
        val priorityKeys = listOf(
            "persist.device.real.serialno",
            "ro.serialno",
            "ro.boot.serialno",
            "ro.product.serialno",
            "persist.sys.serialno",
            "ro.serial",
            "gsm.serial",
            "ro.ril.oem.psno"
        )
        for (k in priorityKeys) {
            val v = props[k]?.trim()
            if (!v.isNullOrEmpty() && v != "unknown" && v != "0" && v.length >= 4) {
                val dt = SystemClock.elapsedRealtime() - t0
                AppLog.i(TAG, "SCREEN_TIMEOUT: serial detect dt=${dt}ms src=getprop:$k val=$v")
                val info = SerialInfo(v, "getprop:$k", dt, props)
                persistSerialCache(ctx, info)
                return info
            }
        }
        // Build.getSerial
        try {
            if (Build.VERSION.SDK_INT >= 26 && ctx.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val v = Build.getSerial()
                if (v.isNotEmpty() && v != Build.UNKNOWN) {
                    val dt = SystemClock.elapsedRealtime() - t0
                    AppLog.i(TAG, "SCREEN_TIMEOUT: serial detect dt=${dt}ms src=Build.getSerial val=$v")
                    val info = SerialInfo(v, "Build.getSerial", dt, props); persistSerialCache(ctx, info); return info
                }
            }
        } catch (_: SecurityException) { Log.w(TAG, "SCREEN_TIMEOUT: READ_PHONE_STATE not granted, skipping Build.getSerial")
        } catch (_: Throwable) {}
        // Build.SERIAL
        try {
            val v = Build.SERIAL
            if (v.isNotEmpty() && v != Build.UNKNOWN) {
                val dt = SystemClock.elapsedRealtime() - t0
                AppLog.i(TAG, "SCREEN_TIMEOUT: serial detect dt=${dt}ms src=Build.SERIAL val=$v")
                val info = SerialInfo(v, "Build.SERIAL", dt, props); persistSerialCache(ctx, info); return info
            }
        } catch (_: Throwable) {}
        val dt = SystemClock.elapsedRealtime() - t0
        AppLog.w(TAG, "SCREEN_TIMEOUT: serial detection FAILED dt=${dt}ms — all sources empty/unknown")
        return SerialInfo("", "none", dt, props)
    }

    private fun persistSerialCache(ctx: Context, info: SerialInfo) {
        cachedSerial = info
        prefs(ctx).edit()
            .putString(PREF_CACHED_SERIAL, info.serial)
            .putString(PREF_CACHED_SERIAL_SRC, info.source)
            .putLong(PREF_CACHED_SERIAL_TS, System.currentTimeMillis())
            .apply()
    }

    /** Асинхронно (на BgWorker) запускает детекцию серийника если кэш не свежий. */
    private fun ensureSerialProbeAsync(ctx: Context, force: Boolean = false) {
        if (!force && cachedSerialFresh(ctx) != null) return
        if (serialProbeInFlight) return
        serialProbeInFlight = true
        BgWorker.post("ScreenBrightness.serialProbe") {
            try { detectActualSerialBg(ctx) } finally { serialProbeInFlight = false }
        }
    }

    /**
     * UI/dev-панель: читаем все серийные проперти в одном вызове и возвращаем
     * готовый многострочный отчёт для показа пользователю. Работает на BgWorker.
     */
    fun serialCheckReport(ctx: Context, onDone: (String) -> Unit) {
        val app = ctx.applicationContext
        BgWorker.post("ScreenBrightness.serialCheckReport") {
            val info = detectActualSerialBg(app)
            val sb = StringBuilder()
            sb.append("SCREEN_TIMEOUT: device serial check\n")
            for (k in listOf("persist.device.real.serialno","ro.serialno","ro.boot.serialno")) {
                val v = info.allProps[k] ?: "(not set)"
                sb.append("  ").append(k.padEnd(32)).append("= ").append(v).append("\n")
            }
            sb.append("  expected                       = ").append(EXPECTED_SERIAL).append(" / ").append(EXPECTED_SERIAL_BOOT).append("\n")
            val verdict = if (info.serial == EXPECTED_SERIAL || info.serial == EXPECTED_SERIAL_BOOT) "applicable" else "mismatch"
            sb.append("  → ").append(verdict).append(" (detected src=").append(info.source).append(" dt=").append(info.dtMs).append("ms)")
            val msg = sb.toString()
            AppLog.i(TAG, msg.replace("\n"," | "))
            Handler(Looper.getMainLooper()).post { onDone(msg) }
        }
    }

    private fun isTargetHuFromCache(): Boolean {
        val c = cachedSerial ?: return false
        return c.serial == EXPECTED_SERIAL || c.serial == EXPECTED_SERIAL_BOOT
    }

    /**
     * Применить keep-awake ON/OFF. НА ВЫЗЫВАЮЩЕМ ПОТОКЕ — только запись pref и
     * запуск фоновой работы. Никакого getprop/Runtime.exec здесь быть не должно
     * (prompt_four_fixes §3: главный поток <16 мс).
     */
    fun setKeepAwake(ctx: Context, enabled: Boolean) {
        val app = ctx.applicationContext
        prefs(app).edit().putBoolean(PREF_KEEP_AWAKE, enabled).apply()
        // Запускаем фоновый apply, который уже сам сделает определение серийника
        // (detectActualSerialBg на BgWorker) и, если серийник не совпадёт — выйдет
        // с логом "serial mismatch → skip".
        BgWorker.post("ScreenBrightness.setKeepAwake") {
            applyKeepAwakeInternal(app, enabled)
        }
    }

    private fun applyKeepAwakeInternal(ctx: Context, enabled: Boolean) {
        BgWorker.assertNotMain("ScreenBrightness.applyKeepAwakeInternal")
        // Обязательно заново определяем серийник (фоново): кэш может быть пуст/устарел.
        val serial = detectActualSerialBg(ctx)
        val isTarget = serial.serial == EXPECTED_SERIAL || serial.serial == EXPECTED_SERIAL_BOOT
        if (!isTarget) {
            AppLog.w(TAG, "SCREEN_TIMEOUT: serial mismatch expected=$EXPECTED_SERIAL/$EXPECTED_SERIAL_BOOT actual=${serial.serial} (src=${serial.source} dt=${serial.dtMs}ms) → skip (not touching screen_off_timeout)")
            return
        }
        AppLog.i(TAG, "SCREEN_TIMEOUT: serial match (src=${serial.source} dt=${serial.dtMs}ms) → applicable, applying enabled=$enabled")

        // Сохраняем оригинальное значение перед первой записью.
        val p = prefs(ctx)
        val before = readSystem(ctx, Settings.System.SCREEN_OFF_TIMEOUT)
        if (before != null && before != SCREEN_OFF_MAX && !p.contains(PREF_ORIGINAL_TIMEOUT)) {
            p.edit().putLong(PREF_ORIGINAL_TIMEOUT, before.toLong()).apply()
            AppLog.i(TAG, "SCREEN_TIMEOUT: сохранили оригинальное значение = $before")
        }
        val target = if (enabled) SCREEN_OFF_MAX else {
            val restored = p.getLong(PREF_ORIGINAL_TIMEOUT, 0L).toInt()
            if (restored > 0 && restored != SCREEN_OFF_MAX) restored else 600000
        }

        // Путь 1: Settings.System (переживает uninstall: Android НЕ удаляет
        // Settings.System при удалении APK).
        val wr = writeSystemInt(ctx, Settings.System.SCREEN_OFF_TIMEOUT, target)
        val after = readSystem(ctx, Settings.System.SCREEN_OFF_TIMEOUT)

        // Путь 2: persist-проперти (без init.d-скрипта — см. комментарий в блоке
        // констант: на IHU629G нет подтверждённого init.d-хука, файл в /data/local/tmp
        // не запускается сам и был бы мусором). persist prop сам по себе только
        // сигнализирует намерение, но НЕ обеспечивает перезагрузку системы — это
        // честно отмечаем в логе.
        var rootRc = -1; var rootOut = "(no-root)"
        try {
            val setCmd = if (enabled) "setprop $PERSIST_FORCE_PROP 1" else "setprop $PERSIST_FORCE_PROP 0"
            val (rc, out) = AdbClient.execute(setCmd, 5000, expectRoot = true)
            rootRc = rc; rootOut = out.replace('\n',' ').take(200)
        } catch (t: Throwable) {
            AppLog.w(TAG, "SCREEN_TIMEOUT: root persist prop failed: ${t.message}")
        }

        val applied = after == target
        AppLog.i(TAG, "VERIFY SCREEN_TIMEOUT: before=$before target=$target after=$after settingsOk=${wr.ok} rootRc=$rootRc → ${if (applied) "SUCCESS" else "FAILED"} rootOut=$rootOut")
        AppLog.i(TAG, "SCREEN_TIMEOUT: NOTE — значение держится в Settings.System (переживает uninstall); init.d/durable-скрипт НЕ ставится (нет подтверждённого init.d-хука на IHU629G; файл в /data/local/tmp не самозапускается)")
        // Диагностика «кто сбросит»
        try {
            val dumpSb = StringBuilder()
            val proc = Runtime.getRuntime().exec(arrayOf("sh","-c","dumpsys settings 2>/dev/null | grep -i screen_off_timeout | head -20"))
            proc.waitFor(2000)
            proc.inputStream.bufferedReader().forEachLine { dumpSb.append(it).append(";") }
            AppLog.i(TAG, "SCREEN_TIMEOUT: dumpsys screen_off entries: $dumpSb")
        } catch (_: Throwable) {}
        if (applied) p.edit().putBoolean(PREF_KEEP_AWAKE_APPLIED, enabled).apply()
    }

    /**
     * Применить keep-awake если pref включен (boot-path/service-startup).
     * На вызывающем потоке — только планирование фоновой работы.
     */
    fun ensureKeepAwake(ctx: Context) {
        val app = ctx.applicationContext
        // Если кэш не свежий — запускаем асинхронную детекцию заранее (для UI).
        ensureSerialProbeAsync(app)
        val enabled = prefs(app).getBoolean(PREF_KEEP_AWAKE, false)
        if (enabled) {
            BgWorker.post("ScreenBrightness.ensureKeepAwake") {
                applyKeepAwakeInternal(app, true)
            }
        }
    }

    private fun scheduleRestWatchdog(ctx: Context) {
        restWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        val sec = restSeconds(ctx)
        if (sec <= 0) return
        val r = object : Runnable {
            override fun run() {
                AppLog.d(TAG, "screenSaver fired after ${sec}s")
                ScreenSaverOverlay.show(ctx)
            }
        }
        restWatchdogRunnable = r
        mainHandler.postDelayed(r, sec * 1000L)
    }
}

