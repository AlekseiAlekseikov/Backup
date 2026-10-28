package com.example.voiceapp3.tools

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.util.Log
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
        worker.post { noteCurrentTimeoutOnce(app); restoreIfEnabled(app); scheduleRestWatchdog(app) }
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
     * ОДНОКРАТНО при первом старте: зафиксировать текущий screen_off_timeout как
     * «оригинальный» (до любых наших действий) и отметить в логе.
     *
     * НИЧЕГО НЕ ПЕРЕЗАПИСЫВАЕМ. Если видим Int.MAX_VALUE — это след предыдущей версии,
     * и мы НЕ знаем заводское значение (дампа прошивки нет в workspace), поэтому
     * просто предупреждаем в лог и просим пользователя выставить таймаут в системных
     * настройках — после этого наше приложение его больше никогда не тронет.
     */
    private fun noteCurrentTimeoutOnce(ctx: Context) {
        try {
            val p = prefs(ctx)
            if (p.getBoolean(PREF_TIMEOUT_NOTED, false)) return
            val cur = readSystem(ctx, Settings.System.SCREEN_OFF_TIMEOUT)
            if (cur == null) {
                AppLog.w(TAG, "SCREEN_TIMEOUT: не могу прочитать текущий screen_off_TIMEOUT")
            } else if (cur == Int.MAX_VALUE) {
                AppLog.e(TAG, "SCREEN_TIMEOUT: VERIFY ТЕКУЩЕЕ_ЗНАЧЕНИЕ=Int.MAX_VALUE (2147483647). "
                    + "Это остаток от предыдущей версии приложения. Заводское значение мне НЕИЗВЕСТНО "
                    + "(дамп прошивки не загружен) — автоматическое восстановление не делаю. "
                    + "ПОЖАЛУЙСТА, выставьте таймаут затухания вручную в Настройки → Экран → Спящий режим, "
                    + "после этого приложение больше не будет его трогать.")
            } else {
                // Запоминаем как оригинальное, чтобы при будущей опции keep-awake
                // можно было вернуть при выключении.
                p.edit().putLong(PREF_ORIGINAL_TIMEOUT, cur.toLong()).apply()
                AppLog.i(TAG, "SCREEN_TIMEOUT: VERIFY текущее значение = $cur мс — принято за оригинальное. "
                    + "Приложение больше не будет изменять screen_off_TIMEOUT.")
            }
            p.edit().putBoolean(PREF_TIMEOUT_NOTED, true).apply()
        } catch (t: Throwable) {
            AppLog.e(TAG, "SCREEN_TIMEOUT note failed", t)
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

