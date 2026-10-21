package com.example.voiceapp3.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.example.voiceapp3.R
import com.example.voiceapp3.nav.NavTheme
import kotlin.math.roundToInt

/**
 * Нативный тост шелла (замена HTML `#toast`, ui/index.html).
 *
 * Пилюля сверху по центру (как прежний `.toast`: top 64 px), цвета из темы,
 * появляется, через 1,7 с скрывается. Новый тост заменяет показанный.
 * Контейнер не перехватывает касания.
 */
class ToastPanel(private val activity: Activity) {

    private val host: FrameLayout = activity.findViewById(R.id.toastHost)
    private val density = activity.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private var palette: NavTheme = NavTheme.byId(null)

    private val pill = TextView(activity).apply {
        textSize = 13.5f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(22), dp(10), dp(22), dp(10))
        alpha = 0f
        visibility = View.GONE
        elevation = dp(8).toFloat()
    }

    private val hideTask = Runnable { hide() }

    init {
        // Порядок отрисовки задаёт elevation: тост должен быть над панелями.
        host.elevation = dp(50).toFloat()
        host.addView(
            pill,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(64)
            },
        )
        applyPalette()
    }

    fun setTheme(id: String?) {
        palette = NavTheme.byId(id)
        applyPalette()
    }

    fun show(message: String) {
        handler.removeCallbacks(hideTask)
        pill.animate().cancel()
        pill.text = message
        pill.visibility = View.VISIBLE
        pill.translationY = dp(-12).toFloat()
        pill.animate().alpha(1f).translationY(0f).setDuration(250).start()
        handler.postDelayed(hideTask, 1700L)
    }

    private fun hide() {
        pill.animate().cancel()
        pill.animate().alpha(0f).translationY(dp(-12).toFloat()).setDuration(250)
            .withEndAction { pill.visibility = View.GONE }
            .start()
    }

    private fun applyPalette() {
        pill.setTextColor(palette.onbtn)
        pill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(100).toFloat()
            setColor(palette.btn)
        }
    }

    private fun dp(v: Int): Int = (v * density + 0.5f).roundToInt()
}
