package com.example.voiceapp3.nav

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * navigator-app-7.html, блок `.states`: переключатель состояний экрана
 * (плавающая стеклянная «пилюля» над доком, по центру). В макете это
 * демонстрационный переключатель — по явному требованию пользователя
 * переносится в приложение как навигация по реальным состояниям:
 * карта / поиск / карточка места / навигация / офлайн-карты.
 *
 * Хост вешает по центру снизу (LP: WRAP×WRAP над доком) и сам
 * прячет/ставит активное состояние в колбэках шторок.
 */
class StateBarController(
    activity: Activity,
    private val density: Float,
    private val theme: NavTheme
) {

    enum class UiState { MAP, SEARCH, PLACE, DRIVE, OFFLINE }

    /** Корневой view (пилюля с кнопками). */
    val view: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dpS(6), dpS(6), dpS(6), dpS(6))
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(theme.panelTop, theme.panelBot)
        ).apply {
            cornerRadius = dpS(16).toFloat()
            setStroke(dpS(1), theme.borderHairline)
        }
    }

    /** Тап по кнопке → хост переводит UI в выбранное состояние. */
    var onStateSelected: ((UiState) -> Unit)? = null

    private var active: UiState = UiState.MAP
    private val buttons = LinkedHashMap<UiState, TextView>()

    private val labels = listOf(
        UiState.MAP to "Карта",
        UiState.SEARCH to "Поиск",
        UiState.PLACE to "Карточка места",
        UiState.DRIVE to "Навигация"
        // «Офлайн-карты» из бара УБРАНЫ по требованию пользователя 2026-10-05:
        // шторка уже открывается кнопкой ⇩ в правой колонке. Enum OFFLINE
        // оставлен для маркера активности (колбэк офлайн-шторки).
    )

    init {
        for ((i, pair) in labels.withIndex()) {
            val (state, label) = pair
            val btn = TextView(activity).apply {
                text = label
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dpS(14), dpS(8), dpS(14), dpS(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (i > 0) marginStart = dpS(4)
                }
                attachPressFeedbackS(this)
                setOnClickListener { onStateSelected?.invoke(state) }
            }
            buttons[state] = btn
            view.addView(btn)
        }
        restyle()
    }

    fun current(): UiState = active

    /** Внешний триггер: хост помечает фактическое состояние (например,
     *  карточка места открылась не из бара, а по долгому тапу на карте). */
    fun setActive(state: UiState) {
        if (active == state) return
        active = state
        restyle()
    }

    fun currentActive(): UiState = active

    private fun restyle() {
        for ((state, v) in buttons) {
            if (state == active) {
                v.setTextColor(theme.onbtn)
                v.background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(theme.btn, theme.btnShadowed)
                ).apply { cornerRadius = dpS(12).toFloat() }
            } else {
                v.setTextColor(theme.textSecondary)
                v.background = GradientDrawable().apply {
                    setColor(theme.chipBg)
                    cornerRadius = dpS(12).toFloat()
                    setStroke(dpS(1), theme.borderHairline)
                }
            }
        }
    }

    private fun attachPressFeedbackS(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            }
            false
        }
    }

    private fun dpS(v: Int): Int = (v * density).toInt()
}
