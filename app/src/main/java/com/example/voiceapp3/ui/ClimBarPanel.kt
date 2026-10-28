package com.example.voiceapp3.ui

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.voiceapp3.R
import com.example.voiceapp3.tools.ToolsBackend
import kotlin.math.roundToInt

/**
 * Нативная полоса климата (замена HTML `#climBar`, ui/index.html).
 *
 * Тёмная пилюля снизу по центру, 86 dp от низа — как прежний `position:fixed; bottom:86px`.
 * Видна, когда включён тумблер «Полоса климата» (ключ app_climate_bar_enabled);
 * скрывается на вкладке «Навигация» (как прежний body.navfull #climBar).
 * Значения статичны, как в макете. Цвета фиксированы (тёмная пилюля на любой теме).
 */
class ClimBarPanel(private val activity: Activity) {

    private val host: FrameLayout = activity.findViewById(R.id.climBarHost)
    private val density = activity.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences =
        activity.getSharedPreferences(ToolsBackend.Prefs.FILE, Activity.MODE_PRIVATE)

    private var tabAllows = true

    private val pill = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(100).toFloat()
            setColor(0xEB1A1A1A.toInt())
        }
        elevation = dp(6).toFloat()
        visibility = View.GONE
    }

    // Тумблер меняют в «Инструментах»: изменение приходит через слушатель
    // SharedPreferences. Слушатель держим полем: SharedPreferences хранит его слабо.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ToolsBackend.Prefs.CLIMATE_BAR) handler.post { refresh() }
    }

    init {
        // Порядок отрисовки задаёт elevation: без неё панели с elevation перекрывают полосу.
        host.elevation = dp(50).toFloat()
        pill.addView(bold("21.5°"), spacing(0))
        pill.addView(dim("водитель"), spacing(12))
        pill.addView(
            TextView(activity).apply {
                text = "❄ AUTO"
                textSize = 12f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(100).toFloat()
                    setColor(0x29FFFFFF)
                }
                setPadding(dp(12), dp(3), dp(12), dp(3))
            },
            spacing(12),
        )
        pill.addView(dim("пассажир"), spacing(12))
        pill.addView(bold("22.0°"), spacing(12))

        host.addView(
            pill,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(86)
            },
        )
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        refresh()
    }

    /** Вкладка «Навигация» скрывает полосу. Вызывается из tab.change. */
    fun setTabAllows(allowed: Boolean) {
        tabAllows = allowed
        refresh()
    }

    private fun refresh() {
        val enabled = prefs.getBoolean(ToolsBackend.Prefs.CLIMATE_BAR, false)
        pill.visibility = if (enabled && tabAllows) View.VISIBLE else View.GONE
    }

    private fun bold(value: String): TextView = TextView(activity).apply {
        text = value
        textSize = 16f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
    }

    private fun dim(value: String): TextView = TextView(activity).apply {
        text = value
        textSize = 12f
        alpha = 0.6f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
    }

    private fun spacing(startDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(startDp)
        }

    private fun dp(v: Int): Int = (v * density + 0.5f).roundToInt()
}
