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
 * Три режима: система / тёмная / светлая.
 * MODE_NIGHT_FOLLOW_SYSTEM — AndroidX, на API 14+ (Android 9 в порядке).
 * На ГУ «система» следует дню/ночи машины (uiMode).
 */
object AppTheme {

    const val PREFS = "voiceapp3"
    private const val PREF_MODE = "theme_mode"
    private const val PREF_LIGHT = "light_theme"

    enum class Mode { SYSTEM, DARK, LIGHT }

    fun mode(context: Context): Mode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when (prefs.getString(PREF_MODE, null)) {
            "light" -> Mode.LIGHT
            "dark" -> Mode.DARK
            "system" -> Mode.SYSTEM
            else -> if (prefs.getBoolean(PREF_LIGHT, false)) Mode.LIGHT else Mode.DARK
        }
    }

    fun setMode(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_MODE, mode.name.lowercase())
            .apply()
        applySaved(context)
    }

    fun applySaved(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode(context)) {
                Mode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                Mode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                Mode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /**
     * Светлая ли сейчас тема.
     * Для обычного Activity (оверлей) configuration.uiMode не следует
     * AppCompatDelegate — поэтому сначала смотрим сохранённый режим.
     */
    fun isLight(context: Context): Boolean = when (mode(context)) {
        Mode.LIGHT -> true
        Mode.DARK -> false
        Mode.SYSTEM -> {
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