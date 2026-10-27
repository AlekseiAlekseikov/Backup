package com.example.voiceapp3

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat

/**
 * Тема приложения и цвет верхней полосы IHU629G.
 *
 * Поведение — точный порт w0/y7.n() из CentralEXAuto:
 *  - ключ theme_mode в SharedPreferences "voiceapp3":
 *      "auto"   — следовать конфигурации ГУ (день/ночь по configuration.uiMode),
 *      "light"  — принудительно светлая,
 *      "dark"   — принудительно тёмная.
 *  - по умолчанию — "auto" (в OEM — "auto", legacy: light_mode=true/false).
 *  - Вместо AppCompatDelegate.setDefaultNightMode применяем паттерн OEM:
 *    оборачиваем Context через createConfigurationContext, выставляя
 *    uiMode = UI_MODE_NIGHT_YES/NO поверх текущей конфигурации. Это гарантирует,
 *    что ночной режим переключается на лету при ACTION_CONFIGURATION_CHANGED.
 *  - AppCompatDelegate.setDefaultNightMode всё же вызываем для совместимости
 *    с компонентами, которые смотрят на него (AppCompatDialogs и т.п.).
 */
object AppTheme {

    const val PREFS = "voiceapp3"
    private const val PREF_MODE = "theme_mode"
    private const val PREF_LIGHT_LEGACY = "light_theme"

    enum class Mode { AUTO, LIGHT, DARK }

    fun mode(context: Context): Mode {
        val prefs = dps(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when (prefs.getString(PREF_MODE, null)) {
            "light" -> Mode.LIGHT
            "dark" -> Mode.DARK
            "auto" -> Mode.AUTO
            null, "system" -> {
                // legacy-миграция из старого ключа PREF_LIGHT_LEGACY.
                if (prefs.contains(PREF_LIGHT_LEGACY)) {
                    if (prefs.getBoolean(PREF_LIGHT_LEGACY, false)) Mode.LIGHT else Mode.DARK
                } else Mode.AUTO
            }
            else -> Mode.AUTO
        }
    }

    fun setMode(context: Context, mode: Mode) {
        dps(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_MODE, mode.name.lowercase())
            .remove(PREF_LIGHT_LEGACY)
            .apply()
        applySaved(context)
    }

    /**
     * DeviceProtectedStorage — настройки читаются до разблокировки (как в OEM
     * w0.zd.a() — createDeviceProtectedStorageContext).
     */
    private fun dps(context: Context): Context {
        return try {
            context.createDeviceProtectedStorageContext()
        } catch (_: Throwable) {
            context
        }
    }

    fun applySaved(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode(context)) {
                Mode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                Mode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                Mode.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /**
     * Оборачивает base Context под текущую тему — точный порт y7.n().
     * Должен вызываться из Activity.attachBaseContext() ДО super.attachBaseContext().
     *
     * Логика OEM (y7.java:872-901):
     *  1. Читаем theme_mode, вычисляем z3 — ночь? (true/false).
     *  2. Сравниваем z3 с текущим (configuration.uiMode & UI_MODE_NIGHT_MASK) == NIGHT_YES.
     *  3. Если совпадает — возвращаем как есть; иначе создаём Configuration-copy,
     *     выставляем правильный бит (32=НОЧЬ, 16=ДЕНЬ) и возвращаем
     *     context.createConfigurationContext(newConfig).
     */
    fun attachBaseContext(context: Context): Context {
        return try {
            val prefs = dps(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val z3 = when (prefs.getString(PREF_MODE, null)) {
                "light" -> false
                "dark" -> true
                else -> {
                    val night = context.resources.configuration.uiMode and
                            Configuration.UI_MODE_NIGHT_MASK
                    night == Configuration.UI_MODE_NIGHT_YES
                }
            }
            val configuration = context.resources.configuration
            val currentIsNight =
                (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (z3 == currentIsNight) {
                context
            } else {
                val newConfig = Configuration(configuration)
                val newUiMode = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        (if (z3) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                newConfig.uiMode = newUiMode
                context.createConfigurationContext(newConfig)
            }
        } catch (_: Throwable) {
            context
        }
    }

    /**
     * Светлая ли сейчас тема (для оконных флагов строки состояния).
     * Учитывает и AUTO режим по реальному configuration.uiMode.
     */
    fun isLight(context: Context): Boolean = when (mode(context)) {
        Mode.LIGHT -> true
        Mode.DARK -> false
        Mode.AUTO -> {
            val night = context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
            night != Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun applyToWindow(activity: Activity) {
        val color = ContextCompat.getColor(activity, R.color.bar)
        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
        )
        window.statusBarColor = color
        window.navigationBarColor = color
        window.decorView.setBackgroundColor(color)

        if (Build.VERSION.SDK_INT >= 23) {
            val view = window.decorView
            var vis = view.systemUiVisibility
            vis = if (isLight(activity)) {
                vis or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                vis and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            view.systemUiVisibility = vis
        }

        runCatching {
            activity.setTaskDescription(
                ActivityManager.TaskDescription(
                    "Голосовой ассистент",
                    null as Bitmap?,
                    color
                )
            )
        }
    }
}
