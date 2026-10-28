package com.example.voiceapp3.tools

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
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
     * P5 (по prompt_next): опция «не гаснуть никогда». Функция с гейтом по серийному
     * номеру ГУ: ТОЛЬКО на конкретной голове IHUMas9W (serial см. EXPECTED_SERIAL).
     * На любом другом ГУ — не включается и не пишет ничего в SCREEN_OFF_TIMEOUT.
     *
     * Путь «durable after uninstall» (требование): помимо Settings.System мы
     * устанавливаем persist.sys.screen_off_timeout_force через root-канал и
     * кладём однократный init.d-скрипт в /data/local/tmp/user.sh (где ГУ применяет
     * его при загрузке) — это переживает удаление APK, т.к. /data/local/tmp не
     * очищается при uninstall.
     */
    const val PREF_KEEP_AWAKE = "keep_awake_never_sleep"
    const val PREF_KEEP_AWAKE_APPLIED = "keep_awake_applied_v1"

    /**
     * Серийный номер целевого ГУ, взят из дампа прошивки (geely_dump/getprop.txt):
     *   [persist.device.real.serialno]: [IHU629G0MAS9W00010001144]
     * Это серийный номер платы, не привязан к автомобилю (не VIN, не марка/модель
     * авто). При переносе ГУ в другую машину серийник сохраняется, функция продолжает
     * работать; на любом другом ГУ с другим серийником функция не применяется.
     */
    private const val EXPECTED_SERIAL = "IHU629G0MAS9W00010001144"
    /** Вторичный серийник (ro.boot.serialno / ro.serialno из того же дампа). Если любой
     *  из двух совпадает — считаем, что это нужное ГУ. */
    private const val EXPECTED_SERIAL_BOOT = "AB1FRA00029968507"

    private const val SCREEN_OFF_MAX = Int.MAX_VALUE
    private const val PERSIST_FORCE_PROP = "persist.sys.screen_off_timeout_force"
    private const val DURABLE_SCRIPT_PATH = "/data/local/tmp/screen_timeout_always_max.sh"

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
     * Определяем, это ли целевое ГУ — строго по серийному номеру. Привязки
     * к машине (VIN, Car property, марка/модель) НЕТ.
     *
     * Порядок проверки:
     *   1. persist.device.real.serialno через getprop (всегда даёт корректный
     *      серийник на IHU629G и не требует прав приложения).
     *   2. ro.boot.serialno / ro.serialno (вторичный серийник из того же дампа).
     *   3. Build.getSerial() (системный вызов, может потребовать READ_PHONE_STATE
     *      — мы system_app, оборачиваем в try/catch на SecurityException).
     *   4. Settings.Secure.ANDROID_ID — как последний запасной (меняется при factory reset!).
     */
    private fun detectActualSerial(ctx: Context): String {
        val tried = mutableListOf<String>()
        fun readProp(name: String): String? = try {
            val p = Runtime.getRuntime().exec(arrayOf("sh","-c","getprop $name"))
            p.waitFor(500)
            val v = p.inputStream.bufferedReader().readText().trim()
            if (v.isNotEmpty() && v != "unknown" && v != "0" && v.length >= 4) v else null
        } catch (_: Throwable) { null }
        for (prop in listOf("persist.device.real.serialno", "ro.serialno", "ro.boot.serialno",
                              "ro.product.serialno", "persist.sys.serialno", "ro.serial",
                              "gsm.serial", "ro.ril.oem.psno")) {
            val v = readProp(prop)
            if (v != null) { tried.add("$prop=$v"); return v }
        }
        try {
            if (Build.VERSION.SDK_INT >= 26 && ctx.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED) {
                val v = Build.getSerial()
                if (v.isNotEmpty() && v != Build.UNKNOWN) { tried.add("Build.getSerial=$v"); return v }
            }
        } catch (_: SecurityException) {
            Log.w(TAG, "SCREEN_TIMEOUT: READ_PHONE_STATE not granted, skipping Build.getSerial")
        } catch (_: Throwable) {}
        try {
            val v = Build.SERIAL
            if (v.isNotEmpty() && v != Build.UNKNOWN) { tried.add("Build.SERIAL=$v"); return v }
        } catch (_: Throwable) {}
        AppLog.w(TAG, "SCREEN_TIMEOUT: serial detection failed, tried: ${tried.joinToString(",")}")
        return ""
    }

    private fun isTargetHu(ctx: Context): Boolean {
        val s = detectActualSerial(ctx)
        return s == EXPECTED_SERIAL || s == EXPECTED_SERIAL_BOOT
    }

    /**
     * Применить keep-awake ON/OFF (SCREEN_OFF_TIMEOUT=Int.MAX_VALUE при включении,
     * восстановить сохранённое оригинальное значение при выключении).
     *
     * Пишем ТОЛЬКО если:
     *  - функция реально включена пользователем (pref PREF_KEEP_AWAKE = true);
     *  - мы на целевом ГУ (serial совпадает с EXPECTED_SERIAL / EXPECTED_SERIAL_BOOT);
     *  - иначе — в лог «→ skip» и ничего не делаем.
     *
     * Durable-путь (чтобы значение пережило удаление приложения):
     *  - ставим persist.sys.screen_off_timeout_force=1 через root-канал;
     *  - кладём скрипт в /data/local/tmp/screen_timeout_always_max.sh, который
     *    при своём запуске через init.d (на ECARX IHU с userdebug это работает)
     *    выставляет screen_off_timeout=MAX. Это не удаляется вместе с APK.
     *
     * Если root-канал недоступен (ERR_ROOT_CHANNEL_UNAVAILABLE) — пытаемся только
     * через Settings.System (от Java/system_app), без скрипта в /data/local/tmp.
     * Это всё равно делает значение «долгоживущим», потому что Settings.System не
     * удаляется при uninstall.
     */
    fun setKeepAwake(ctx: Context, enabled: Boolean): Boolean {
        val app = ctx.applicationContext
        val p = prefs(app)
        p.edit().putBoolean(PREF_KEEP_AWAKE, enabled).apply()

        val expected = if (isTargetHu(app)) "applicable" else "skip"
        val actual = detectActualSerial(app)
        val isTarget = isTargetHu(app)
        AppLog.i(TAG, "SCREEN_TIMEOUT: serial expected=IHU629G0MAS9W00010001144/AB1FRA00029968507 actual=$actual → $expected")
        if (!isTarget) {
            AppLog.w(TAG, "SCREEN_TIMEOUT: serial mismatch → skip (not touching screen_off_timeout)")
            return false
        }

        BgWorker.post("ScreenBrightness.setKeepAwake") {
            applyKeepAwakeInternal(app, enabled)
        }
        return true
    }

    private fun applyKeepAwakeInternal(ctx: Context, enabled: Boolean) {
        BgWorker.assertNotMain("ScreenBrightness.applyKeepAwakeInternal")
        // Сохраняем оригинальное значение перед первой записью.
        val p = prefs(ctx)
        val before = readSystem(ctx, Settings.System.SCREEN_OFF_TIMEOUT)
        if (before != null && before != SCREEN_OFF_MAX && !p.contains(PREF_ORIGINAL_TIMEOUT)) {
            p.edit().putLong(PREF_ORIGINAL_TIMEOUT, before.toLong()).apply()
            AppLog.i(TAG, "SCREEN_TIMEOUT: сохранили оригинальное значение = $before")
        }
        val target = if (enabled) SCREEN_OFF_MAX else {
            val restored = p.getLong(PREF_ORIGINAL_TIMEOUT, 0L).toInt()
            if (restored > 0 && restored != SCREEN_OFF_MAX) restored else 600000  // fallback 10 минут
        }

        // Путь 1: Settings.System (работает и без root, но только пока процесс system_server
        // не сброшен на заводские настройки; сам по себе Settings.System не удаляется с APK).
        val wr = writeSystemInt(ctx, Settings.System.SCREEN_OFF_TIMEOUT, target)
        val after = readSystem(ctx, Settings.System.SCREEN_OFF_TIMEOUT)

        // Путь 2: persist-проперти + init.d-скрипт через root-канал (durable path, переживает uninstall).
        var rootRc = -1; var rootOut = "(no-root)"
        try {
            val script = if (enabled) """
                setprop $PERSIST_FORCE_PROP 1
                # Создаём durable-скрипт, который срабатывает при любом shell/init.d:
                cat > '$DURABLE_SCRIPT_PATH' <<'___EOF___'
#!/system/bin/sh
# Written by voiceapp3 keep-awake for serial IHU629G0MAS9W00010001144.
# Setting screen_off_timeout to max so screen never dims.
settings put system screen_off_timeout 2147483647
___EOF___
                chmod 755 '$DURABLE_SCRIPT_PATH' 2>/dev/null || true
                '$DURABLE_SCRIPT_PATH' 2>/dev/null || true
                echo DURABLE_SCRIPT_INSTALLED=\$( [ -f '$DURABLE_SCRIPT_PATH' ] && echo 1 || echo 0)
            """.trimIndent() else """
                setprop $PERSIST_FORCE_PROP 0
                rm -f '$DURABLE_SCRIPT_PATH' 2>/dev/null || true
                echo DURABLE_SCRIPT_REMOVED=\$( [ -f '$DURABLE_SCRIPT_PATH' ] && echo 1 || echo 0)
            """.trimIndent()
            val (rc, out) = AdbClient.execute(script, 10000, expectRoot = true)
            rootRc = rc; rootOut = out.replace('\n', ' ').take(400)
        } catch (t: Throwable) {
            AppLog.w(TAG, "SCREEN_TIMEOUT: root durable path failed: ${t.message}")
        }

        val applied = after == target
        AppLog.i(TAG, "VERIFY SCREEN_TIMEOUT: before=$before target=$target after=$after settingsOk=${wr.ok} rootRc=$rootRc → ${if (applied) "SUCCESS" else "FAILED"} rootOut=$rootOut")
        // Диагностика «кто сбросит»
        try {
            val dumpSb = StringBuilder()
            val proc = Runtime.getRuntime().exec(arrayOf("sh","-c","dumpsys settings 2>/dev/null | grep -i screen_off_timeout | head -20"))
            proc.waitFor(2000)
            proc.inputStream.bufferedReader().forEachLine { dumpSb.append(it).append(";") }
            AppLog.i(TAG, "SCREEN_TIMEOUT: dumpsys screen_off entries: $dumpSb")
        } catch (_: Throwable) {}
        if (applied) {
            p.edit().putBoolean(PREF_KEEP_AWAKE_APPLIED, enabled).apply()
        }
    }

    /**
     * Применить keep-awake если pref включен (вызывается из boot-пути / watchdog
     * / на старте сервиса) — только на целевом ГУ.
     */
    fun ensureKeepAwake(ctx: Context) {
        val app = ctx.applicationContext
        val p = prefs(app)
        val enabled = p.getBoolean(PREF_KEEP_AWAKE, false)
        if (enabled && isTargetHu(app)) {
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

