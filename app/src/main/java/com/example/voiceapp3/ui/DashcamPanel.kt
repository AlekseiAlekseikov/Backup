package com.example.voiceapp3.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.voiceapp3.R
import com.example.voiceapp3.nav.NavTheme
import kotlin.math.roundToInt

/**
 * Нативная вкладка «Видеорегистратор» (этап 6, вариант «каркас с заглушкой»).
 *
 * Запись с камеры, таймер, снимки и список записей не подключены: в проекте
 * нет источника видео. Прежний HTML-макет (картинка, кнопки записи, фиктивные
 * записи) удалён. Геометрия накрытия #content — из content.frame, как у «Приложений».
 */
class DashcamPanel(private val activity: Activity) {

    private val density = activity.resources.displayMetrics.density
    private var palette: NavTheme = NavTheme.byId(null)
    private var visible = false

    private val view: ScrollView = activity.findViewById(R.id.dashcamPanel)
    private val title: TextView = activity.findViewById(R.id.dashcamTitle)
    private val subtitle: TextView = activity.findViewById(R.id.dashcamSubtitle)
    private val card: View = activity.findViewById(R.id.dashcamCard)
    private val cardTitle: TextView = activity.findViewById(R.id.dashcamCardTitle)
    private val cardText: TextView = activity.findViewById(R.id.dashcamCardText)

    init {
        view.visibility = View.GONE
        applyPalette()
    }

    /** Тема шелла (ui.theme): только перекраска, состояния у панели нет. */
    fun setTheme(id: String?) {
        palette = NavTheme.byId(id)
        applyPalette()
    }

    /** Геометрия из JS (`content.frame`), физические пиксели. */
    fun onFrame(active: Boolean, x: Float, y: Float, w: Float, h: Float, @Suppress("UNUSED_PARAMETER") fresh: Boolean) {
        if (!active) {
            hide()
            return
        }
        if (!visible) show()
        if (w >= 1f && h >= 1f) {
            val lp = view.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(0, 0)
            lp.width = w.roundToInt()
            lp.height = h.roundToInt()
            lp.leftMargin = x.roundToInt()
            lp.topMargin = y.roundToInt()
            view.layoutParams = lp
        }
    }

    private fun show() {
        visible = true
        view.visibility = View.VISIBLE
        view.scrollTo(0, 0)
        view.bringToFront()
    }

    private fun hide() {
        if (!visible) return
        visible = false
        view.visibility = View.GONE
    }

    private fun applyPalette() {
        val p = palette
        view.setBackgroundColor(p.page1)
        title.setTextColor(p.ink)
        subtitle.setTextColor(p.muted)
        cardTitle.setTextColor(p.ink)
        cardText.setTextColor(p.muted)
        card.background = GradientDrawable().apply {
            cornerRadius = 15f * density
            setColor(p.card2)
        }
    }
}
