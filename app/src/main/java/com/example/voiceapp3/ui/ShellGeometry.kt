package com.example.voiceapp3.ui

import android.app.Activity
import android.view.View
import kotlin.math.max

/**
 * Нативная геометрия главного экрана (замена JS-замеров index.html).
 *
 * Повторяет CSS прежней раскладки `.main`:
 *  · верх: верхняя строка 50 dp + padding 2 dp;
 *  · низ: 86 dp (место под плавающий док);
 *  · бока: 22 dp; зазор между колонками 12 dp;
 *  · колонки: 1 : 1,35 с минимумами 380 / 460 dp; при ширине ≤ 1100 dp — 1 : 1;
 *  · полноэкранные вкладки (CarPlay, AA, телеметрия, видеорегистратор, навигация):
 *    карточка автомобиля скрыта, #content занимает всю ширину;
 *  · CarPlay / AA: видео — от (0,0) на всю ширину, высота до верха дока минус 10 dp, не меньше 320 dp.
 *
 * Все прямоугольники — физические пиксели, как раньше. Потребители
 * (карточка, нативные вкладки, проекции) получают их через UiBridge.
 */
class ShellGeometry(
    private val activity: Activity,
    private val host: View,
    private val bridge: UiBridge,
) {
    private val density = activity.resources.displayMetrics.density
    private var tab: String = "quick"
    private var dockTopPx: Int = 0

    private fun dp(v: Int): Float = v * density

    /** Вкладка сменилась (tab.change). [fresh] — первый показ вкладки. */
    fun setTab(id: String, fresh: Boolean) {
        tab = id
        refresh(fresh)
    }

    /** Верх нативного дока (физ. px), из dock.geometry. */
    fun setDockTop(px: Int) {
        dockTopPx = px
        refresh(false)
    }

    /** Пересчёт по текущему размеру экрана. */
    fun refresh(fresh: Boolean) {
        val w = host.width.toFloat()
        val h = host.height.toFloat()
        if (w <= 0f || h <= 0f) return

        val top = dp(50) + dp(2)
        val bottom = h - dp(86)
        val mainH = max(0f, bottom - top)
        val left = dp(22)
        val right = w - dp(22)
        val gap = dp(12)
        val total = right - left - gap

        val full = tab == "carplay" || tab == "aa" || tab == "telemetry" ||
            tab == "dashcam" || tab == "navi"

        val carX: Float
        val carW: Float
        val contentX: Float
        val contentW: Float
        if (full) {
            carX = 0f; carW = 0f
            contentX = left; contentW = right - left
        } else {
            val leftW = leftColumnWidth(total)
            carX = left; carW = leftW
            contentX = left + leftW + gap; contentW = total - leftW
        }

        bridge.applyCarFrame(carX, top, carW, mainH)

        for (t in CONTENT_TABS) {
            val active = t == tab
            bridge.applyContentFrame(t, active, contentX, top, contentW, mainH, fresh && active)
        }

        if (tab == "carplay" || tab == "aa") {
            val stack = if (tab == "aa") "androidAuto" else "carplay"
            val videoH = max(dp(320), dockTopPx - dp(10))
            bridge.projectionFrame(stack, 0f, 0f, w, videoH)
        }
    }

    /** Ширина левой колонки (карточка) по правилам сетки. */
    private fun leftColumnWidth(total: Float): Float {
        val widthDp = total / density + 22f * 2 + 12f
        if (widthDp <= 1100f) return total / 2f
        var l = max(total / 2.35f, dp(380))
        if (total - l < dp(460)) l = total - dp(460)
        return max(0f, l)
    }

    companion object {
        /** Вкладки с накрытием #content (нативные панели). */
        private val CONTENT_TABS = listOf(
            "quick", "voice", "apps", "tools", "dashcam", "service", "updates",
        )
    }
}
