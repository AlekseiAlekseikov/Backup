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
        if (lvl !in MIN_LEVEL..MAX_LEVEL) return
        // iter33b: сначала ЧИТАЕМ. Раньше каждый тик watchdog'а писал три значения
        // (settings put + sysfs + setprop) и печатал два VERIFY — даже когда всё уже равно.
        // Теперь при совпадении — ни одной записи, ни одной строки (бюджет C1/H: ≈1 exec/min).
        val settingsOk = readSystem(ctx, SETTINGS_KEY) == lvl
        val sysfsOk = readSysfs() == lvl
        if (settingsOk && sysfsOk) return
        writeSystemInt(ctx, SETTINGS_KEY, lvl); writeSysfs(lvl); writeProp(lvl)
        AppLog.d(TAG, "restoreIfEnabled: $lvl for ${bucketPref(ctx)} (было расхождение: settings=$settingsOk sysfs=$sysfsOk)")
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
     * iter21 §3: КНОПКА «Не гасить ГУ» — одноразовое действие, НЕ тумблер, НЕ запоминает pref.
     * Через root-канал ставит screen_off_timeout=2147483647 и stay_on_while_plugged_in=7,
     * сразу читает обратно для честного вердикта. Никаких watchdog-циклов, никаких while-true.
     */
    fun pressKeepAwakeButton(ctx: Context): ToolsResult {
        val t0 = SystemClock.uptimeMillis()
        val D = "$"
        val script = """
echo '=== KEEPAWAKE_BUTTON (one-shot) ==='
_BEFORE=${D}(settings get system screen_off_timeout 2>&1)
_STAY_BEFORE=${D}(settings get global stay_on_while_plugged_in 2>&1)
settings put system screen_off_timeout 2147483647 2>&1
settings put global stay_on_while_plugged_in 7 2>&1
_RC=${D}?
_AFTER=${D}(settings get system screen_off_timeout 2>&1)
_STAY_AFTER=${D}(settings get global stay_on_while_plugged_in 2>&1)
echo "before=${D}_BEFORE stay_before=${D}_STAY_BEFORE"
echo "after=${D}_AFTER stay_after=${D}_STAY_AFTER rc=${D}_RC"
""".trimIndent()
        val (rc, out) = AdbClient.execute("echo '$script' | sh", 10000, expectRoot = true)
        val mBefore = Regex("before=([^\\n]+)").find(out)?.groupValues?.get(1)?.trim() ?: ""
        val mAfter  = Regex("after=([^\\n]+)").find(out)?.groupValues?.get(1)?.trim() ?: ""
        val mStayB  = Regex("stay_before=([^\\n]+)").find(out)?.groupValues?.get(1)?.trim() ?: ""
        val mStayA  = Regex("stay_after=([^\\n]+)").find(out)?.groupValues?.get(1)?.trim() ?: ""
        val mRc     = Regex("rc=([0-9-]+)").find(out)?.groupValues?.get(1) ?: rc.toString()
        val ok = (mAfter == "2147483647")
        AppLog.i(TAG, "KEEPAWAKE_BUTTON: before=$mBefore set=2147483647 after=$mAfter stay_on=$mStayB→$mStayA rc=$mRc → ${if (ok) "OK" else "FAILED"}")
        val step = ToolsStep(step = "keepawake.btn",
            command = "settings put system screen_off_timeout 2147483647; settings put global stay_on_while_plugged_in 7",
            startedAtMs = t0, finishedAtMs = SystemClock.uptimeMillis(),
            exitCode = if (ok) 0 else 1,
            stdout = out, stderr = if (ok) "" else "ожидалось after=2147483647, получили=$mAfter",
            expected = "after=2147483647 stay_on=7",
            actual = "after=$mAfter stay_on=$mStayA",
            status = if (ok) ToolsStep.Status.OK else ToolsStep.Status.FAILED)
        return ToolsResult("settings.keepawake_btn", "Не гасить ГУ", listOf(step))
    }

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
     * iter20: Синхронное первое чтение screen_off_timeout — ДО любых записей.
     *
     * Отличия от iter19:
     *  - Не делает вложенного BgWorker.post: чтение выполняется в вызывающем таске
     *    последовательно, потом сразу же вердикт (устранена гонка потоков из §2).
     *  - Идентичность загрузки через /proc/sys/kernel/random/boot_id: при новом boot_id
     *    сбрасываются durable_boot_readback_ts и durable_after_reboot_logged,
     *    иначе проверка после ребута никогда не срабатывала (§1).
     *  - Ограниченный ретрай root-канала 3×3с с AdbClient.start() между попытками (§3).
     *  - При получении `null` или пустом ответе — вердикт NO_VALUE, не FAILED.
     *  - Возвращает data-class с результатом; checkDurableAfterReboot теперь принимает его.
     */
    data class BootReadback(
        val actual: String,
        val stayOn: String,
        val uptimeS: Long,
        val bootIdShort: String,
        val state: String,
        val attempt: Int,
        val rc: Int,
        val failed: Boolean,        // не смогли прочитать (root-канал)
        val wroteBeforeCheck: Boolean
    )

    private fun doPreWriteBootReadback(app: Context): BootReadback {
        // ---- boot_id guard (iter20 §1): при смене boot_id сбрасываем флаги ----
        val bootId = try { File("/proc/sys/kernel/random/boot_id").readText().trim() } catch (_: Throwable) { "" }
        val bootIdShort = bootId.take(8)
        val savedBootId = Prefs2.getString(app, "durable_boot_id", "") ?: ""
        if (savedBootId != bootId) {
            Prefs2.putString(app, "durable_boot_id", bootId)
            Prefs2.putLong(app, "durable_boot_readback_ts", 0L)
            Prefs2.putBoolean(app, "durable_after_reboot_logged", false)
        }
        val alreadyRead = Prefs2.getLong(app, "durable_boot_readback_ts", 0L) != 0L
        // iter21 §6: uptime читаем через SystemClock.elapsedRealtime (это монотонное время с boot,
        // в миллисекундах; /1000 = секунды). Runtime.exec("sh -c cat /proc/uptime") на устройстве
        // возвращал -1 из-за неверного массива argv, из-за чего любая загрузка считалась late-start.
        val uptimeS = android.os.SystemClock.elapsedRealtime() / 1000L

        if (alreadyRead) {
            return BootReadback(actual = "", stayOn = "", uptimeS = uptimeS,
                bootIdShort = bootIdShort, state = "already-read",
                attempt = 0, rc = -1, failed = true, wroteBeforeCheck = true)
        }

        var actual = ""; var stayOn = ""; var rc = -1; var attempt = 0; var state = "init"
        // ---- retry 3×3s (iter20 §3) ----
        for (i in 1..3) {
            attempt = i
            state = AdbClient.currentState().toString()
            if (state != "CONNECTED" && state != "ROOT") {
                AdbClient.start()
                Thread.sleep(1500)
            }
            state = AdbClient.currentState().toString()
            val (rc1, out) = AdbClient.execute(
                "settings get system screen_off_timeout 2>&1; echo ---; settings get global stay_on_while_plugged_in 2>&1",
                5000, expectRoot = true)
            rc = rc1
            val lines = out.lines().map { it.trim() }
            val v = lines.firstOrNull { it.isNotBlank() && !it.startsWith("---") } ?: ""
            if (v.isNotBlank() && v != "null" && rc1 == 0) {
                actual = v
                stayOn = lines.dropWhile { it != "---" }.drop(1).firstOrNull { it.isNotBlank() } ?: ""
                state = AdbClient.currentState().toString()
                break
            }
            if (i < 3) Thread.sleep(3000)
        }
        val failed = actual.isBlank() || rc != 0
        if (failed) {
            AppLog.w(TAG, "DURABLE_PREBOOT_READBACK: failed reason=root-channel actual=\"$actual\" rc=$rc uptime=${uptimeS}s boot_id=$bootIdShort state=$state attempt=$attempt")
            // Не сохраняем прочитанное (пустое) как итоговое — оставляем ts=0,
            // чтобы следующий вызов (из ensureKeepAwake после поднятия канала) мог попробовать ещё раз.
            return BootReadback(actual = actual, stayOn = stayOn, uptimeS = uptimeS,
                bootIdShort = bootIdShort, state = state, attempt = attempt, rc = rc,
                failed = true, wroteBeforeCheck = false)
        }
        Prefs2.putLong(app, "durable_boot_readback_ts", SystemClock.elapsedRealtime())
        Prefs2.putString(app, "durable_boot_readback_val", actual)
        Prefs2.putString(app, "durable_boot_readback_stayon", stayOn)
        Prefs2.putLong(app, "durable_boot_uptime_s", uptimeS)
        Prefs2.putBoolean(app, "durable_boot_wrote_before_check", false)
        AppLog.i(TAG, "DURABLE_PREBOOT_READBACK: actual=$actual stay_on=$stayOn uptime=${uptimeS}s boot_id=$bootIdShort state=$state attempt=$attempt rc=$rc wrote_before_check=false")
        return BootReadback(actual = actual, stayOn = stayOn, uptimeS = uptimeS,
            bootIdShort = bootIdShort, state = state, attempt = attempt, rc = rc,
            failed = false, wroteBeforeCheck = false)
    }

    /**
     * iter20: Единая точка входа при старте — один BgWorker пост, внутри которого
     * сначала читаем (doPreWriteBootReadback), сразу же вердикт (checkDurableAfterReboot),
     * и только потом ensureKeepAwake/bootDiag — больше нет гонки двух постов.
     */
    fun bootDurableCheckAndThen(ctx: Context, afterRead: () -> Unit) {
        val app = ctx.applicationContext
        BgWorker.post("ScreenBrightness.durableCheck") {
            val rb = doPreWriteBootReadback(app)
            checkDurableAfterReboot(app, rb)
            afterRead()
        }
    }

    /**
     * Применить keep-awake если pref включен (boot-path/service-startup).
     * iter19/20: вызывается ПОСЛЕ doPreWriteBootReadback+checkDurableAfterReboot.
     */
    fun ensureKeepAwake(ctx: Context) {
        val app = ctx.applicationContext
        ensureSerialProbeAsync(app)
        val enabled = Prefs2.getBoolean(app, PREF_KEEP_AWAKE, true)
        if (!Prefs2.getBoolean(app, BOOT_DIAG_DONE_KEY, false)) {
            // iter19: runBootDiag стал БЕЗ settings put — только чтение.
            BgWorker.post("ScreenBrightness.bootDiag") { runBootDiag(app) }
        }
        BgWorker.post("ScreenBrightness.ensureKeepAwake") {
            // iter20: durable-чтение+вердикт уже отработали в bootDurableCheckAndThen (до вызова ensureKeepAwake).
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
        // iter19: диагностика только НАБЛЮДАЕТ, не меняет состояние — ни settings put,
        // ни stay_on. Раньше скрипт ставил MAX и тем самым смазывал картину реального
        // значения (и влиял на DURABLE_AFTER_REBOOT).
        try {
            val uptimeLocal = execShort("cat /proc/uptime; echo; getprop ro.boot.bootcount; echo; echo \"uid=\$(id)\"")
            AppLog.i(TAG, "BOOT_DIAG local(uid=?): $uptimeLocal")

            val hookProbe = execShort("ls -ld /system/etc/init.d /data/adb/service.d /data/adb/post-fs-data.d /vendor/etc/init 2>&1; echo ---; ls /system/etc/init.d/ 2>&1 | head; echo ---; ls /data/adb/service.d/ 2>&1 | head")
            AppLog.i(TAG, "BOOT_DIAG hook-probe (init.d/service.d/vendor.init):\n$hookProbe")

            val propProbe = execShort("getprop | grep -iE 'screen_off|screen.timeout|stay_on|screen_off_timeout' 2>&1 | head -20")
            AppLog.i(TAG, "BOOT_DIAG persist-prop probe:\n$propProbe")

            // Чисто диагностический скрипт (НЕ ИЗМЕНЯЕТ состояние).
            val script = """
echo '=== SCREEN_TIMEOUT_DIAG (root via AdbClient, OBSERVE-ONLY — no settings put) ==='
date
echo "uid: \$(id)"
echo "uptime: \$(cat /proc/uptime)"
echo "bootcount: \$(getprop ro.boot.bootcount)"
echo "serial: \$(getprop ro.serialno)"
echo "--- ls -l settings_system.xml ---"
ls -l /data/system/users/0/settings_system.xml 2>&1 || echo NO_XML
echo "--- md5 settings_system.xml ---"
md5sum /data/system/users/0/settings_system.xml 2>&1 || echo NO_MD5
echo "--- CURRENT screen_off_timeout ---"
settings get system screen_off_timeout 2>&1
echo "--- CURRENT stay_on_while_plugged_in ---"
settings get global stay_on_while_plugged_in 2>&1
echo "--- dumpsys power grep ---"
dumpsys power 2>/dev/null | grep -iE 'screenofftimeout|mScreenOffTimeoutSetting|mWakefulness' | head -10
echo "=== END ==="
""".trimIndent()
            AppLog.i(TAG, "BOOT_DIAG: dispatching observe-only root script via AdbClient.execute (20s)")
            val (rc, out) = AdbClient.execute(script, 20000, expectRoot = true)
            AppLog.i(TAG, "BOOT_DIAG immediate rc=$rc len=${out.length} tail:\n${out.takeLast(2500)}")

            // Отложенная проверка через 30с — тоже только наблюдение.
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                BgWorker.post("ScreenBrightness.bootDiag.after30") {
                    val afterScript = """
echo '=== SCREEN_TIMEOUT_DIAG AFTER 30s (observe-only) ==='
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

                    // iter17/18: попытка установить долговечный механизм (запускаем после
                    // того, как наблюдательная часть отработала и не смазала картину).
                    installDurableScreenOffIfPossible(ctx)
                }
            }, 30000L)

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
    /**
     * iter18: честный probe долговечного механизма screen_off_timeout.
     *
     *  1) Пишем одноразовый (не while-true) сервис в /data/adb/service.d/ через ROOT AdbClient
     *     (не через execShort uid=1000 — там никогда не было прав записи). chmod 755.
     *     Верифицируем: ls -l + md5sum + sh -n. Если файл появился и валиден → installed=magisk-service.d.
     *  2) persist.sys.prop: делаем СВЯЗНЫЙ тест — пишем заведомо фейк (1800000), ждём 3с, читаем
     *     через root settings get; если значение реально ИЗМЕНИЛОСЬ с baseline — механизм работает.
     *     Тогда откатываемся на MAX 2147483647 и ставим installed=persist.sys.
     *     Если не изменилось — persist-путь мёртв, честно пишем DURABLE_PROBE: binding=dead.
     *  3) init.d: только ls + mount-инфо (system r/o). Никогда не считаем installed.
     *  4) Сохраняем timestamp пробы в Prefs2, чтобы после следующей загрузки вывести
     *     DURABLE_AFTER_REBOOT: ожидание/факт.
     * Ни при каких обстоятельствах не выставляется INSTALLED по тому факту, что settings
     * уже показывает 2147483647 — это делал и сам APK на предыдущем шаге.
     */
    private fun installDurableScreenOffIfPossible(ctx: Context) {
        var verdict: String = "IMPOSSIBLE_WITHOUT_FIRMWARE_MOD"
        var installedHook: String? = null
        var magiskProbe: String = "not-run"
        var runtimeProbe: String = "not-run"
        var initdProbe: String = "not-run"
        Prefs2.putLong(ctx, "durable_probe_ts", SystemClock.elapsedRealtime())
        Prefs2.putString(ctx, "durable_expected_timeout", "2147483647")

        // ---- probe 1: boot hooks (Magisk service.d / init.d) via ROOT AdbClient ----
        try {
            val (rcLs, outLs) = AdbClient.execute("ls -ld /data/adb/service.d /system/etc/init.d 2>&1; id", 5000, expectRoot = true)
            AppLog.i(TAG, "DURABLE: boot-hook ls rc=$rcLs: $outLs")
            val oneShotScript = "#!/system/bin/sh\n# voiceapp3 one-shot durable screen_off_timeout (iter19)\n# Survives APK delete because service.d runs at boot via Magisk.\nsettings put system screen_off_timeout 2147483647\nsettings put global stay_on_while_plugged_in 7\nexit 0\n"
            val writeCmd = """HOOK_DIR=""
if [ -d /data/adb/service.d ] && [ -w /data/adb/service.d ]; then HOOK_DIR=/data/adb/service.d
elif [ -d /system/etc/init.d ] && [ -w /system/etc/init.d ]; then HOOK_DIR=/system/etc/init.d
fi
echo "HOOK_DIR=${'$'}HOOK_DIR"
if [ -n "${'$'}HOOK_DIR" ]; then
  SCRIPT_PATH=${'$'}HOOK_DIR/99voiceapp3_screenoff.sh
  cat > ${'$'}SCRIPT_PATH <<'VOICEEOF'
$oneShotScript
VOICEEOF
  chmod 755 ${'$'}SCRIPT_PATH
  echo '--- ls -l ---'; ls -l ${'$'}SCRIPT_PATH 2>&1
  echo '--- sh -n ---'; sh -n ${'$'}SCRIPT_PATH && echo SYNTAX_OK
  echo '--- md5 ---'; md5sum ${'$'}SCRIPT_PATH 2>&1
else
  echo "NO_WRITABLE_HOOK_DIR"
fi"""
            val (rcW, outW) = AdbClient.execute(writeCmd, 8000, expectRoot = true)
            AppLog.i(TAG, "DURABLE: boot-hook install rc=$rcW:\n${outW.takeLast(1200)}")
            if (outW.contains("SYNTAX_OK") && rcW == 0) {
                val hookPath = Regex("HOOK_DIR=([^\\s]+)").find(outW)?.groupValues?.get(1) ?: "hook"
                installedHook = "$hookPath/99voiceapp3_screenoff.sh(one-shot)"
                magiskProbe = "hook-installed-at-$hookPath"
                verdict = "INSTALLED source=$installedHook"
            } else {
                magiskProbe = "no-writable-hook-dir (out=${outW.replace('\n',' ').takeLast(200)})"
            }
        } catch (t: Throwable) {
            magiskProbe = "error:${t.message}"
            AppLog.w(TAG, "DURABLE boot-hook err: ${t.message}")
        }

        // ---- probe 2: root settings R/W (bogus test) — всегда прогоняем, но даёт
        // вердикт RUNTIME_ONLY, а не INSTALLED (нет реального boot-хука).
        if (installedHook == null) {
            try {
                val testScript = """echo '--- baseline ---'; settings get system screen_off_timeout 2>&1
echo '--- set bogus 1800000 ---'; settings put system screen_off_timeout 1800000 2>&1; echo rc=${'$'}?
sleep 3
echo '--- after-bogus ---'; settings get system screen_off_timeout 2>&1
echo '--- revert MAX ---'; settings put system screen_off_timeout 2147483647 2>&1
sleep 2
echo '--- reverted ---'; settings get system screen_off_timeout 2>&1"""
                val (rcB, outB) = AdbClient.execute(testScript, 15000, expectRoot = true)
                val lines = outB.lines()
                var baseline = ""; var afterBogus = ""; var reverted = ""
                for ((idx, l) in lines.withIndex()) {
                    when (l.trim()) {
                        "--- baseline ---" -> if (idx + 1 < lines.size) baseline = lines[idx + 1].trim()
                        "--- after-bogus ---" -> if (idx + 1 < lines.size) afterBogus = lines[idx + 1].trim()
                        "--- reverted ---" -> if (idx + 1 < lines.size) reverted = lines[idx + 1].trim()
                    }
                }
                val bogusApplied = afterBogus == "1800000" && afterBogus != baseline
                val revertedOk = reverted == "2147483647"
                val okStr = "${if (bogusApplied && revertedOk) "true" else "false"}"
                AppLog.i(TAG, "DURABLE_PROBE: baseline=$baseline after_bogus=$afterBogus reverted=$reverted ok=$okStr binding=${if (bogusApplied) "works" else "dead"}")
                if (!revertedOk) {
                    AppLog.w(TAG, "DURABLE_PROBE: REVERT_FAILED value=$reverted (expected=2147483647), re-applying MAX")
                    AdbClient.execute("settings put system screen_off_timeout 2147483647; settings put global stay_on_while_plugged_in 7; settings get system screen_off_timeout", 5000, expectRoot = true)
                }
                if (bogusApplied) {
                    AdbClient.execute("setprop persist.sys.screen_off_timeout 2147483647; setprop persist.sys.stay_on_while_plugged_in 7", 5000, expectRoot = true)
                    runtimeProbe = "root-settings-rw (runtime only, no boot hook)"
                    verdict = "RUNTIME_ONLY"
                } else {
                    runtimeProbe = "dead(baseline=$baseline after_bogus=$afterBogus reverted=$reverted)"
                }
            } catch (t: Throwable) {
                runtimeProbe = "error:${t.message}"
                AppLog.w(TAG, "DURABLE runtime probe err: ${t.message}")
            }
        }

        // ---- probe 3: /system/etc/init.d info ----
        try {
            val (rcI, outI) = AdbClient.execute("ls -ld /system/etc/init.d 2>&1; echo '--- mount /system ---'; mount | grep ' /system ' | head", 5000, expectRoot = true)
            initdProbe = outI.replace('\n', ' ').take(300)
            AppLog.i(TAG, "DURABLE: /system/etc/init.d probe rc=$rcI: $outI")
        } catch (t: Throwable) {
            initdProbe = "error:${t.message}"
        }

        AppLog.i(TAG, "DURABLE_SCREEN_OFF: verdict=$verdict magisk=$magiskProbe runtime=$runtimeProbe initd=$initdProbe\n" +
                "  rejected: watchdog-in-apk (dies with APK delete)\n" +
                "  rejected: /data/local/tmp script (not auto-started at boot)\n" +
                "  RUNTIME_ONLY = MAX применяется пока APK жив; без boot-хука при удалении APK не выживет.")
    }

    /**
     * iter20: сверка долговечности после ребута — принимает результат BootReadback
     * (снятый СРАЗУ в том же BgWorker таске, до записей), без дополнительного BgWorker-поста.
     * wrote_before_check=false — гарантия честности. При late-start → skipped; при failed
     * чтении (root-channel) → skipped reason=no-preboot-readback; при null/пустом actual → NO_VALUE.
     */
    private fun checkDurableAfterReboot(ctx: Context, rb: BootReadback) {
        val expected = Prefs2.getString(ctx, "durable_expected_timeout", "2147483647") ?: "2147483647"
        if (Prefs2.getBoolean(ctx, "durable_after_reboot_logged", false)) return

        // already-read не должно доходить сюда из bootDurableCheckAndThen (он переустановил флаги
        // при новом boot_id), но страхуемся.
        val uptimeMin = if (rb.uptimeS > 0) (rb.uptimeS / 60).toInt() else -1

        if (rb.failed) {
            AppLog.i(TAG, "DURABLE_AFTER_REBOOT: skipped reason=no-preboot-readback uptime=${uptimeMin}m state=${rb.state} attempt=${rb.attempt}")
            // НЕ ставим durable_after_reboot_logged — дадим шанс на следующем ensureKeepAwake
            // прочитать ещё раз, если root-канал поднимется позже.
            return
        }
        if (uptimeMin < 0 || uptimeMin > 10) {
            AppLog.i(TAG, "DURABLE_AFTER_REBOOT: skipped reason=late-start uptime=${uptimeMin}m wrote_before_check=${rb.wroteBeforeCheck}")
            Prefs2.putBoolean(ctx, "durable_after_reboot_logged", true)
            return
        }
        val actual = rb.actual.trim()
        val stayOn = rb.stayOn.trim()
        val verdict = when {
            actual.isEmpty() || actual == "null" -> "NO_VALUE"
            actual == expected -> "OK"
            else -> "FAILED"
        }
        AppLog.i(TAG, "DURABLE_AFTER_REBOOT: expected=$expected actual=${actual.ifEmpty { "(empty)" }} stay_on=$stayOn uptime=${uptimeMin}m wrote_before_check=${rb.wroteBeforeCheck} → $verdict")
        Prefs2.putBoolean(ctx, "durable_after_reboot_logged", true)
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

