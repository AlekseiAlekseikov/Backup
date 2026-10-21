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
 * Нативная вкладка «Сервис» (этап 7, вариант «каркас с заглушкой»).
 *
 * Пробег, давление в шинах, ТО, 12V и VIN не подключены: в проекте нет
 * источника данных автомобиля. Прежний HTML-макет с захардкоженными
 * значениями и кнопкой «Записаться на ТО» (без обработчика) удалён.
 * Геометрия накрытия #content — из content.frame, как у «Приложений».
 */
class ServicePanel(private val activity: Activity) {

    private val density = activity.resources.displayMetrics.density
    private var palette: NavTheme = NavTheme.byId(null)
    private var visible = false

    private val view: ScrollView = activity.findViewById(R.id.servicePanel)
    private val title: TextView = activity.findViewById(R.id.serviceTitle)
    private val subtitle: TextView = activity.findViewById(R.id.serviceSubtitle)
    private val card: View = activity.findViewById(R.id.serviceCard)
    private val cardTitle: TextView = activity.findViewById(R.id.serviceCardTitle)
    private val cardText: TextView = activity.findViewById(R.id.serviceCardText)

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
