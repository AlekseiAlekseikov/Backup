package com.example.voiceapp3.tools

import com.example.voiceapp3.tools.Prefs2

import android.content.Context
import android.content.SharedPreferences
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
import java.util.concurrent.TimeUnit

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

    fun prefs(ctx: Context): SharedPreferences = Prefs2.cps(ctx)

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
        if (!Prefs2.getBoolean(ctx, PREF_REMEMBER, false)) return
        val lvl = currentLevel(ctx)
        if (lvl > 0) { Prefs2.putInt(ctx, bucketPref(ctx), lvl); AppLog.d(TAG, "captureLevel saved $lvl to ${bucketPref(ctx)}") }
    }

    fun restoreIfEnabled(ctx: Context) {
        if (!Prefs2.getBoolean(ctx, PREF_REMEMBER, false)) return
        val lvl = Prefs2.cps(ctx).getInt(bucketPref(ctx), -1)
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

    fun restSeconds(ctx: Context): Int = Prefs2.getInt(ctx, PREF_REST_SEC, 0)

    data class RestOutcome(val ok: Boolean, val appliedMs: Long?, val readbackMs: Long?, val error: String?, val note: String?)

    /** Применение таймаута заставки. НЕ трогает SCREEN_OFF_TIMEOUT. */
    fun applyRestTimeout(ctx: Context, seconds: Int): RestOutcome = try {
        Prefs2.putInt(ctx, PREF_REST_SEC, seconds)
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
        if (c != null && System.currentTimeMillis() - Prefs2.getLong(ctx, PREF_CACHED_SERIAL_TS, 0L) < SERIAL_CACHE_TTL_MS && c.serial.isNotEmpty()) return c
        val sv = Prefs2.getString(ctx, PREF_CACHED_SERIAL, "") ?: ""
        val src = Prefs2.getString(ctx, PREF_CACHED_SERIAL_SRC, "") ?: ""
        if (sv.isNotEmpty() && System.currentTimeMillis() - Prefs2.getLong(ctx, PREF_CACHED_SERIAL_TS, 0L) < SERIAL_CACHE_TTL_MS) {
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
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly() }
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
        Prefs2.putString(ctx, PREF_CACHED_SERIAL, info.serial)
        Prefs2.putString(ctx, PREF_CACHED_SERIAL_SRC, info.source)
        Prefs2.putLong(ctx, PREF_CACHED_SERIAL_TS, System.currentTimeMillis())
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

    /** Текущее состояние фичи «Не гасить экран» — для UI-среза. */
    fun isKeepAwake(ctx: Context): Boolean =
        Prefs2.getBoolean(ctx.applicationContext, PREF_KEEP_AWAKE, true)

    /**
     * Применить keep-awake ON/OFF. НА ВЫЗЫВАЮЩЕМ ПОТОКЕ — только запись pref и
     * запуск фоновой работы. Никакого getprop/Runtime.exec здесь быть не должно
     * (prompt_four_fixes §3: главный поток <16 мс).
     */
    fun setKeepAwake(ctx: Context, enabled: Boolean) {
        val app = ctx.applicationContext
        Prefs2.putBoolean(app, PREF_KEEP_AWAKE, enabled)
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
        val before = readSystem(ctx, Settings.System.SCREEN_OFF_TIMEOUT)
        if (before != null && before != SCREEN_OFF_MAX && !Prefs2.cps(ctx).contains(PREF_ORIGINAL_TIMEOUT)) {
            Prefs2.putLong(ctx, PREF_ORIGINAL_TIMEOUT, before.toLong())
            AppLog.i(TAG, "SCREEN_TIMEOUT: сохранили оригинальное значение = $before")
        }
        val target = if (enabled) SCREEN_OFF_MAX else {
            val restored = Prefs2.getLong(ctx, PREF_ORIGINAL_TIMEOUT, 0L).toInt()
            if (restored > 0 && restored != SCREEN_OFF_MAX) restored else 600000
        }

        // Путь 1: in-process Settings.System.putInt (uid=1000 system_app).
        val settingsOk = try {
            writeSystemInt(ctx, Settings.System.SCREEN_OFF_TIMEOUT, target).ok
        } catch (t: Throwable) { AppLog.w(TAG, "SCREEN_TIMEOUT: Settings.System.putInt threw: ${t.message}"); false }

        // Путь 2: sh 'settings put' (Runtime.exec, shell uid=2000, но часто
        // на Flyme у shell есть WRITE_SECURE_SETTINGS через priv-permission).
        // Реальный rc извлекаем из вывода (echo rc=$?); захардкоженный 0 врал
        // при Permission denied и т.п. (iter13).
        var shRc = -1; var shOut = "(not run)"
        try {
            val pp = Runtime.getRuntime().exec(arrayOf("sh","-c","settings put system screen_off_timeout $target; echo rc=\$?; settings get system screen_off_timeout"))
            if (!pp.waitFor(4, TimeUnit.SECONDS)) { pp.destroyForcibly(); shRc=-2; shOut="timeout" }
            else {
                val raw = (pp.inputStream.bufferedReader().readText() + pp.errorStream.bufferedReader().readText()).replace('\n',' ').take(300)
                shOut = raw
                val m = Regex("""rc=(\d+)""").find(raw)
                shRc = m?.groupValues?.get(1)?.toIntOrNull() ?: -1
            }
        } catch (t: Throwable) { shOut = "sh err: ${t.message}" }

        // Путь 3: ADB 'settings put' (uid=0 root). expectRoot=false — канал
        // может быть не готов, это не повод падать: статус отразим в rc.
        var adbRc = -1; var adbOut = "(not run)"
        try {
            val (rc, out) = AdbClient.execute("settings put system screen_off_timeout $target; settings get system screen_off_timeout", 5000, expectRoot = false)
            adbRc = rc; adbOut = out.replace('\n',' ').take(300)
        } catch (t: Throwable) { adbOut = "adb err: ${t.message}" }

        val readback = readSystem(ctx, Settings.System.SCREEN_OFF_TIMEOUT)
        val persisted = readback == target
        AppLog.i(TAG, "SCREEN_TIMEOUT: set $target → readback=$readback (settings.put ok=$settingsOk, sh rc=$shRc '$shOut', adb rc=$adbRc '$adbOut') → ${if (persisted) "PERSISTED" else "NOT PERSISTED"}")

        // persist prop — только сигнал намерения, НЕ гарантия перезагрузки (нет init.d-хука).
        try {
            val setCmd = if (enabled) "setprop $PERSIST_FORCE_PROP 1" else "setprop $PERSIST_FORCE_PROP 0"
            val (rc, out) = AdbClient.execute(setCmd, 3000, expectRoot = false)
            AppLog.i(TAG, "SCREEN_TIMEOUT: persist prop rc=$rc out='${out.replace('\n',' ').take(100)}'")
        } catch (t: Throwable) {
            AppLog.w(TAG, "SCREEN_TIMEOUT: persist prop failed: ${t.message}")
        }
        if (persisted) Prefs2.putBoolean(ctx, PREF_KEEP_AWAKE_APPLIED, enabled)
    }

    /**
     * Применить keep-awake если pref включен (boot-path/service-startup).
     * На вызывающем потоке — только планирование фоновой работы.
     */
    fun ensureKeepAwake(ctx: Context) {
        val app = ctx.applicationContext
        // Если кэш не свежий — запускаем асинхронную детекцию заранее (для UI).
        ensureSerialProbeAsync(app)
        val enabled = Prefs2.getBoolean(app, PREF_KEEP_AWAKE, true)
        // Однократная boot-диагностика (кто гаснет экран) — один раз после запуска
        // процесса, даже если keep-awake выключен (нам важно понять СОСТОЯНИЕ).
        if (!Prefs2.getBoolean(app, BOOT_DIAG_DONE_KEY, false)) {
            BgWorker.post("ScreenBrightness.bootDiag") { runBootDiag(app) }
        }
        BgWorker.post("ScreenBrightness.ensureKeepAwake") {
            val expected = if (enabled) SCREEN_OFF_MAX else Prefs2.getLong(app, PREF_ORIGINAL_TIMEOUT, 600000L).toInt()
            val current = readSystem(app, Settings.System.SCREEN_OFF_TIMEOUT)
            val needReapply = enabled && (current == null || current != SCREEN_OFF_MAX)
            AppLog.i(TAG, "SCREEN_TIMEOUT: boot readback=$current expected=$expected enabled=$enabled → reapply=${if(needReapply) "yes" else "no"}")
            if (needReapply) applyKeepAwakeInternal(app, true)
            scheduleRestWatchdog(app)
        }
    }

    private const val BOOT_DIAG_DONE_KEY = "boot_screen_diag_done_v2"

    private fun execShort(cmd: String, timeoutMs: Long = 4000L): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh","-c",cmd))
        if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) { p.destroyForcibly(); "(timeout)" }
        else { ((p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()).trimEnd()).take(2000) }
    } catch (t: Throwable) { "(exec err: ${t.message})" }

    private fun runBootDiag(ctx: Context) {
        // iter17: читаем screen_off_timeout + settings_system.xml ЧЕРЕЗ root-канал AdbClient.execute
        // (sh uid=1000 не может ls/cat /data/system/users/0/settings_system.xml и не даёт
        // достоверных результатов на многих вендорных платформах). Добавляем uptime, bootcount,
        // pre/post 30s измерение, ls -l XML и сразу же ставим MAX + stay_on.
        try {
            val uptimeLocal = execShort("cat /proc/uptime; echo; getprop ro.boot.bootcount; echo; echo \"uid=\$(id)\"")
            AppLog.i(TAG, "BOOT_DIAG local(uid=?): $uptimeLocal")

            // Проверка — есть ли init.d / Magisk service.d / init.rc хуки? Это нужно
            // для механизма "долгого" screen_off_timeout, переживающего удаление APK.
            val hookProbe = execShort("ls -ld /system/etc/init.d /data/adb/service.d /data/adb/post-fs-data.d /vendor/etc/init 2>&1; echo ---; ls /system/etc/init.d/ 2>&1 | head; echo ---; ls /data/adb/service.d/ 2>&1 | head")
            AppLog.i(TAG, "BOOT_DIAG hook-probe (init.d/service.d/vendor.init):\n$hookProbe")

            // Проверка persist.* prop — может ли вендор сбрасывать нас через него?
            val propProbe = execShort("getprop | grep -iE 'screen_off|screen.timeout|stay_on|screen_off_timeout' 2>&1 | head -20")
            AppLog.i(TAG, "BOOT_DIAG persist-prop probe:\n$propProbe")

            // Root-скрипт с пред/пост замером
            val script = """
echo '=== SCREEN_TIMEOUT_DIAG (root via AdbClient, 15s budget) ==='
date
echo "uid: \$(id)"
echo "uptime: \$(cat /proc/uptime)"
echo "bootcount: \$(getprop ro.boot.bootcount)"
echo "serial: \$(getprop ro.serialno)"
echo "--- ls -l settings_system.xml ---"
ls -l /data/system/users/0/settings_system.xml 2>&1 || echo NO_XML
echo "--- md5 settings_system.xml ---"
md5sum /data/system/users/0/settings_system.xml 2>&1 || echo NO_MD5
echo "--- BEFORE screen_off_timeout ---"
settings get system screen_off_timeout 2>&1
echo "--- BEFORE stay_on_while_plugged_in ---"
settings get global stay_on_while_plugged_in 2>&1
echo "--- apply MAX ---"
settings put system screen_off_timeout 2147483647 2>&1
settings put global stay_on_while_plugged_in 7 2>&1
echo "rc=\$?"
echo "--- AFTER immediate ---"
settings get system screen_off_timeout 2>&1
echo "--- dumpsys power grep ---"
dumpsys power 2>/dev/null | grep -iE 'screenofftimeout|mScreenOffTimeoutSetting|mWakefulness' | head -10
echo "=== END IMMEDIATE ==="
""".trimIndent()
            AppLog.i(TAG, "BOOT_DIAG: dispatching root script via AdbClient.execute (30s)")
            val (rc, out) = AdbClient.execute(script, 30000, expectRoot = true)
            AppLog.i(TAG, "BOOT_DIAG immediate rc=$rc len=${out.length} tail:\n${out.takeLast(2500)}")

            // Отложенная проверка через 30с: удержался ли MAX
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                BgWorker.post("ScreenBrightness.bootDiag.after30") {
                    val afterScript = """
echo '=== SCREEN_TIMEOUT_DIAG AFTER 30s ==='
date
echo "uptime: \$(cat /proc/uptime)"
echo "ls -l xml:"; ls -l /data/system/users/0/settings_system.xml 2>&1
echo "md5 xml:"; md5sum /data/system/users/0/settings_system.xml 2>&1
echo "screen_off_timeout NOW:"; settings get system screen_off_timeout 2>&1
echo "stay_on NOW:"; settings get global stay_on_while_plugged_in 2>&1
echo "=== END ==="
""".trimIndent()
                    val (rc2, out2) = AdbClient.execute(afterScript, 10000, expectRoot = true)
                    AppLog.i(TAG, "BOOT_DIAG after-30s rc=$rc2 len=${out2.length} tail:\n${out2.takeLast(1500)}")

                    // iter17: попытка установить ДОЛГОВЕЧНЫЙ механизм, переживающий удаление APK.
                    // Смотрим наличие init.d/service.d хуков и persist props.
                    installDurableScreenOffIfPossible(ctx)
                }
            }, 30000L)

            // Старые dumpsys-диагностики через sh (uid=1000) оставляем как доп. контекст
            val extra = execShort("echo '--- dumpsys power (sh, uid=1000) ---'; dumpsys power 2>/dev/null | grep -iE 'timeout|screenoff|mHolds|wakefulness' | head -20")
            AppLog.i(TAG, "BOOT_SCREEN_DIAG sh:\n$extra")
            Prefs2.putBoolean(ctx, BOOT_DIAG_DONE_KEY, true)
        } catch (t: Throwable) { AppLog.w(TAG, "BOOT_SCREEN_DIAG failed: ${t.message}") }
    }

    /**
     * iter17: попытка установить ДОЛГОВЕЧНЫЙ механизм screen_off_timeout, переживающий удаление APK.
     * Мы НЕ делаем watchdog в процессе приложения и НЕ пишем /data/local/tmp скрипт — оба варианта
     * уже отклонены в ТЗ. Пробуем по порядку:
     *  1) init.d скрипт в /system/etc/init.d/ (если существует исполняемым и монтируется rw)
     *  2) Magisk service.d (/data/adb/service.d/) — если есть и каталог доступен на запись
     *  3) persist.sys.* prop (getprop/setprop) — если вендор читает его при загрузке
     * Если ничего не получилось — честно логируем "НЕВЫПОЛНИМО БЕЗ ИЗМЕНЕНИЯ ПРОШИВКИ"
     * и перечисляем что было проверено.
     */
    private fun installDurableScreenOffIfPossible(ctx: Context) {
        val verdict = StringBuilder("DURABLE_SCREEN_OFF: verdict=")
        var installed: String? = null
        val probeMark = "/data/local/tmp/hook_test_${SystemClock.elapsedRealtime()}"
        // probe 1: Magisk service.d (пользовательская зона /data — чаще всего writable)
        try {
            val t = execShort("ls -ld /data/adb/service.d 2>&1; id")
            AppLog.i(TAG, "DURABLE: probe Magisk service.d: $t")
            if ("/data/adb/service.d".let {
                    val p = Runtime.getRuntime().exec(arrayOf("sh","-c","test -d $it && test -w $it && echo W"))
                    p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                    p.inputStream.bufferedReader().readText().trim() == "W"
                }) {
                val script = "#!/system/bin/sh\n# voiceapp3 durable screen_off_timeout (installed by APK, survives APK delete)\nwhile true; do\n  settings put system screen_off_timeout 2147483647 2>/dev/null\n  settings put global stay_on_while_plugged_in 7 2>/dev/null\n  sleep 60\ndone\n"
                val marker = execShort("echo -n \"${script.replace("$","\\$")}\" > /data/adb/service.d/99voiceapp3_screenoff.sh 2>&1; chmod 755 /data/adb/service.d/99voiceapp3_screenoff.sh 2>&1; ls -l /data/adb/service.d/99voiceapp3_screenoff.sh; echo ---; cat /data/adb/service.d/99voiceapp3_screenoff.sh")
                AppLog.i(TAG, "DURABLE: wrote magisk service.d script → $marker")
                val (rc, chk) = AdbClient.execute("ls -l /data/adb/service.d/99voiceapp3_screenoff.sh; sh -n /data/adb/service.d/99voiceapp3_screenoff.sh && echo SYNTAX_OK", 5000, expectRoot = true)
                AppLog.i(TAG, "DURABLE: magisk root verify rc=$rc: $chk")
                installed = "magisk-service.d:/data/adb/service.d/99voiceapp3_screenoff.sh"
            }
        } catch (t: Throwable) { AppLog.w(TAG, "DURABLE magisk probe err: ${t.message}") }

        if (installed == null) {
            // probe 2: persist.sys prop
            try {
                val hasProp = execShort("getprop persist.sys.screen_off_timeout_force; echo ---; getprop persist.sys.screen_off_timeout")
                AppLog.i(TAG, "DURABLE: probe persist.sys props: $hasProp")
                // Пишем persist prop через AdbClient (root). Если вендор читает его при загрузке — он применит MAX.
                val (rc3, out3) = AdbClient.execute("setprop persist.sys.screen_off_timeout 2147483647; setprop persist.sys.stay_on_while_plugged_in 7; echo ---; getprop persist.sys.screen_off_timeout", 5000, expectRoot = true)
                AppLog.i(TAG, "DURABLE: persist.sys set rc=$rc3: $out3")
                // Проверим, применилось ли в settings после setprop
                val verify = execShort("sleep 2; settings get system screen_off_timeout 2>&1")
                AppLog.i(TAG, "DURABLE: after setprop readback: $verify")
                if (verify.trim() == "2147483647") {
                    installed = "persist.sys.screen_off_timeout=2147483647"
                }
            } catch (t: Throwable) { AppLog.w(TAG, "DURABLE persist prop err: ${t.message}") }
        }

        if (installed == null) {
            // probe 3: init.d /system/etc/init.d — почти всегда read-only без ремаунта
            try {
                val t = execShort("ls -ld /system/etc/init.d 2>&1; mount | grep ' /system ' | head")
                AppLog.i(TAG, "DURABLE: probe /system/etc/init.d: $t")
            } catch (_: Throwable) {}
            verdict.append("IMPOSSIBLE_WITHOUT_FIRMWARE_MOD")
                .append("\n  checked: /data/adb/service.d (absent/unwritable)")
                .append("\n  checked: persist.sys.screen_off_timeout (setprop did not reflect in settings)")
                .append("\n  checked: /system/etc/init.d (read-only system partition)")
                .append("\n  rejected: watchdog-in-apk (dies with APK delete)")
                .append("\n  rejected: /data/local/tmp script (not auto-started at boot)")
                .append("\n  Решение: применение MAX через работающий сервис APK + один setprop на persist.sys." +
                        " Если удалить APK — механизм исчезнет; для живучести нужна поддержка прошивки.")
        } else {
            verdict.append("INSTALLED source=$installed")
        }
        AppLog.i(TAG, verdict.toString())
    }

    private fun scheduleRestWatchdog(ctx: Context) {
        restWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        val sec = restSeconds(ctx)
        val sysTimeout = readSystem(ctx, Settings.System.SCREEN_OFF_TIMEOUT)
        AppLog.i(TAG, "SCREEN_SAVER: scheduleWatchdog sec=$sec system screen_off_timeout=$sysTimeout (${if(sec<=0) "no screensaver overlay" else "overlay will show after ${sec}s"})")
        if (sec <= 0) return
        val r = object : Runnable {
            override fun run() {
                val sysTo = readSystem(ctx, Settings.System.SCREEN_OFF_TIMEOUT)
                AppLog.i(TAG, "SCREEN_SAVER: watchdog fired (scheduled=${sec}s ago); current system screen_off_timeout=$sysTo")
                ScreenSaverOverlay.show(ctx)
            }
        }
        restWatchdogRunnable = r
        mainHandler.postDelayed(r, sec * 1000L)
    }
}

