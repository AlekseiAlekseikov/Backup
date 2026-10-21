package com.example.voiceapp3.tools

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * Яркость экрана, «Запоминать яркость по фарам» и «Время заставки» —
 * функциональный порт CentralEXAuto (класс `w0.m`, тег лога `BrightMem`,
 * плюс watchdog-логика `w0.r2`/`w0.t1`/`w0.r1` и оверлей заставки `QuickAccessFloaterService`).
 *
 * Соответствия восстановлены по декомпиляции v3.3.9:
 *  - «фары включены» = ночная тема ГУ: `(uiMode & UI_MODE_NIGHT_MASK) == NIGHT_YES`;
 *  - уровни хранятся в двух корзинах prefs: `bright_day` / `bright_night`;
 *  - запись яркости: `Settings.System "screen_brightness"` (0..255);
 *  - Заставка («Descanso de Tela»): управляется через ScreenSaverOverlay (часы поверх всех окон +
 *    приглушение подсветки).
 *  - КРИТИЧНО: Settings.System.SCREEN_OFF_TIMEOUT НИКОГДА не устанавливается в малые значения,
 *    так как это переводит ГУ в глубокий сон с отключением тачскрина и звукового стека AVAS.
 *    При первом запуске выполняется однократное автоисправление (healScreenTimeoutOnce) в Int.MAX_VALUE.
 */
object ScreenBrightness {

    const val TAG = "BrightMem"
    const val PREFS_NAME = "centralex_tools"

    const val SETTINGS_KEY = "screen_brightness"
    const val PERSIST_PROP = "persist.sys.geely.screen_brightness"
    const val SYSFS_PATH = "/sys/class/leds/lcd-backlight/brightness"

    const val PREF_REMEMBER = "master_remember_brightness"
    const val PREF_DAY = "bright_day"
    const val PREF_NIGHT = "bright_night"
    const val PREF_REST_SEC = "cx_screen_saver_timeout_sec"
    const val PREF_HEALED_V1 = "screen_timeout_healed_v1"

    const val MIN_PERCENT = 5
    const val MAX_PERCENT = 100
    const val MIN_LEVEL = 12
    const val MAX_LEVEL = 255

    val REST_CHOICES = listOf(0, 10, 30, 60, 120)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var restWatchdogRunnable: Runnable? = null

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Однократное автоматическое исправление системного screen_off_timeout.
     * Защищает от зависания ГУ в глубоком сне при перезагрузках или обновлениях.
     */
    fun healScreenTimeoutOnce(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean(PREF_HEALED_V1, false)) {
            try {
                Log.i(TAG, "Healing screen_off_timeout to safe value (Int.MAX_VALUE)...")
                writeSystemInt(ctx, Settings.System.SCREEN_OFF_TIMEOUT, Int.MAX_VALUE)
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "settings put system screen_off_timeout 2147483647 2>/dev/null || true"))
                p.edit().putBoolean(PREF_HEALED_V1, true).apply()
            } catch (t: Throwable) {
                Log.w(TAG, "healScreenTimeoutOnce error", t)
            }
        }
    }

    fun isHeadlightsOn(ctx: Context): Boolean {
        val uiMode = ctx.resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun bucketPref(ctx: Context): String = if (isHeadlightsOn(ctx)) PREF_NIGHT else PREF_DAY
    fun bucketLabel(ctx: Context): String = if (isHeadlightsOn(ctx)) "ночь (фары вкл)" else "день (фары выкл)"

    fun percentOf(level: Int): Int = (((level.coerceIn(0, 255) * 100) + 127) / 255).coerceIn(MIN_PERCENT, MAX_PERCENT)
    fun levelOf(percent: Int): Int = ((percent.coerceIn(MIN_PERCENT, MAX_PERCENT) * 255) / 100).coerceIn(MIN_LEVEL, MAX_LEVEL)

    fun currentLevel(ctx: Context): Int {
        val sysfs = readSysfs()
        if (sysfs != null && sysfs > 0) return sysfs
        return readSystem(ctx, SETTINGS_KEY) ?: 128
    }

    fun currentPercent(ctx: Context): Int = percentOf(currentLevel(ctx))

    fun readSystem(ctx: Context, key: String): Int? {
        return try {
            Settings.System.getInt(ctx.contentResolver, key)
        } catch (_: Throwable) {
            null
        }
    }

    fun writeSystemInt(ctx: Context, key: String, value: Int): WriteResult {
        return try {
            val ok = Settings.System.putInt(ctx.contentResolver, key, value)
            val readback = readSystem(ctx, key)
            WriteResult(ok, readback, null)
        } catch (t: Throwable) {
            WriteResult(false, null, t.message)
        }
    }

    fun readSysfs(): Int? {
        return try {
            File(SYSFS_PATH).readText().trim().toIntOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    fun writeSysfs(level: Int): WriteResult {
        val clamped = level.coerceIn(0, 255)
        return try {
            File(SYSFS_PATH).writeText(clamped.toString())
            WriteResult(true, readSysfs(), null)
        } catch (t: Throwable) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "echo $clamped > $SYSFS_PATH"))
                p.waitFor()
                WriteResult(p.exitValue() == 0, readSysfs(), null)
            } catch (ex: Throwable) {
                WriteResult(false, null, ex.message)
            }
        }
    }

    fun writeProp(level: Int): WriteResult {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "setprop $PERSIST_PROP $level"))
            p.waitFor()
            WriteResult(p.exitValue() == 0, level, null)
        } catch (t: Throwable) {
            WriteResult(false, null, t.message)
        }
    }

    fun captureLevel(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean(PREF_REMEMBER, false)) return
        val lvl = currentLevel(ctx)
        if (lvl > 0) {
            p.edit().putInt(bucketPref(ctx), lvl).apply()
            Log.d(TAG, "captureLevel: saved $lvl to ${bucketPref(ctx)}")
        }
    }

    fun restoreIfEnabled(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean(PREF_REMEMBER, false)) return
        val lvl = p.getInt(bucketPref(ctx), -1)
        if (lvl in MIN_LEVEL..MAX_LEVEL) {
            writeSystemInt(ctx, SETTINGS_KEY, lvl)
            writeSysfs(lvl)
            writeProp(lvl)
            Log.d(TAG, "restoreIfEnabled: restored $lvl for ${bucketPref(ctx)}")
        }
    }

    fun syncOnContext(ctx: Context) {
        healScreenTimeoutOnce(ctx)
        if (prefs(ctx).getBoolean(PREF_REMEMBER, false)) {
            restoreIfEnabled(ctx)
        }
        scheduleRestWatchdog(ctx)
    }

    /* ── «Время заставки» (полный порт CentralEXAuto) ── */

    fun restSeconds(ctx: Context): Int = prefs(ctx).getInt(PREF_REST_SEC, 0)

    data class RestOutcome(
        val ok: Boolean,
        val appliedMs: Long?,
        val readbackMs: Long?,
        val error: String?,
        val note: String?
    )

    /**
     * Применение таймаута заставки:
     * - Сохраняет секундный интервал в SharedPreferences.
     * - Гарантирует, что системный экранный сон Android выключен (Int.MAX_VALUE).
     * - Запускает таймер watchdog оверлея часов.
     */
    fun applyRestTimeout(ctx: Context, seconds: Int): RestOutcome {
        val p = prefs(ctx)
        return try {
            // Всегда держим системный таймаут Android в безопасности
            writeSystemInt(ctx, Settings.System.SCREEN_OFF_TIMEOUT, Int.MAX_VALUE)

            p.edit().putInt(PREF_REST_SEC, seconds).apply()
            scheduleRestWatchdog(ctx)

            if (seconds > 0) {
                RestOutcome(true, seconds * 1000L, seconds * 1000L, null, "Заставка включена (${seconds}с оверлей)")
            } else {
                ScreenSaverOverlay.dismiss(ctx)
                RestOutcome(true, 0L, 0L, null, "Заставка отключена")
            }
        } catch (t: Throwable) {
            RestOutcome(false, null, null, t.message, null)
        }
    }

    fun scheduleRestWatchdog(ctx: Context) {
        restWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        val sec = restSeconds(ctx)
        if (sec <= 0) return

        val r = object : Runnable {
            override fun run() {
                ScreenSaverOverlay.show(ctx)
            }
        }
        restWatchdogRunnable = r
        mainHandler.postDelayed(r, sec * 1000L)
    }

    data class WriteResult(val ok: Boolean, val readback: Int?, val error: String?)
}
