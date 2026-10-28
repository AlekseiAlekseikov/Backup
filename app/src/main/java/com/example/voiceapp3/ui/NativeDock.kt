package com.example.voiceapp3.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.voiceapp3.R
import com.example.voiceapp3.nav.NavTheme

/**
 * Нативный плавающий док вкладок шелла (замена HTML `#tabbar`).
 *
 * Контейнер и его строка описаны в `activity_main.xml` (`nativeDock`,
 * `nativeDockRow`). Кнопки создаются здесь, в Kotlin, по списку вкладок,
 * который готовит ShellTabs (правило CarPlay / AA — в Kotlin). Выбор вкладки уходит в JS (`ui.selectTab`), подтверждение — tab.change.
 *
 * Палитра — из [NavTheme]: фон дока — card с прозрачностью 0.94 (как
 * --barbg в HTML), выбранная кнопка — btn/onbtn, остальные подписи — muted2.
 */
class NativeDock(
    private val activity: Activity,
    private val onSelect: (String) -> Unit,
) {
    data class Tab(val id: String, val icon: String, val label: String)

    private class Item(
        val root: LinearLayout,
        val icon: TextView,
        val label: TextView,
        val tab: Tab,
    )

    val view: HorizontalScrollView = activity.findViewById(R.id.nativeDock)
    private val row: LinearLayout = activity.findViewById(R.id.nativeDockRow)

    private val density = activity.resources.displayMetrics.density
    private val screenWidthPx = activity.resources.displayMetrics.widthPixels

    private var items: List<Item> = emptyList()
    private var selectedId: String? = null
    private var palette: NavTheme = NavTheme.byId(null)

    /** Вызывается при изменении геометрии дока: высота и верх в пикселях. */
    var onGeometryChanged: ((heightPx: Int, topPx: Int) -> Unit)? = null

    init {
        view.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            onGeometryChanged?.invoke(bottom - top, top)
        }
    }

    /** Полная перерисовка списка, если набор id изменился; иначе — обновление подписей. */
    fun setTabs(tabs: List<Tab>, selected: String?, themeId: String?) {
        if (themeId != null) palette = NavTheme.byId(themeId)
        val sameIds = tabs.map { it.id } == items.map { it.tab.id }
        if (!sameIds) {
            row.removeAllViews()
            items = tabs.map { buildItem(it) }
            items.forEach { row.addView(it.root) }
        } else {
            items = items.mapIndexed { i, item ->
                if (item.tab != tabs[i]) {
                    item.icon.text = tabs[i].icon
                    item.label.text = tabs[i].label
                }
                Item(item.root, item.icon, item.label, tabs[i])
            }
        }
        selectedId = selected
        applyPalette()
        constrainWidth()
    }

    /** Подсветить выбранную вкладку (без перерисовки списка). */
    fun setSelected(id: String?, themeId: String?) {
        if (themeId != null) palette = NavTheme.byId(themeId)
        selectedId = id
        applyPalette()
    }

    /** Смена палитры шелла (ui.theme) без изменения списка и выбора. */
    fun setTheme(themeId: String?) {
        palette = NavTheme.byId(themeId)
        applyPalette()
    }

    /** Поднять док над Flutter-телеметрией и картой, которые добавляются позже. */
    fun raise() {
        view.bringToFront()
    }

    private fun buildItem(tab: Tab): Item {
        val icon = TextView(activity).apply {
            text = tab.icon
            textSize = 17f
            gravity = Gravity.CENTER
        }
        val label = TextView(activity).apply {
            text = tab.label
            textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setSingleLine(true)
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11f), dp(9f), dp(11f), dp(9f))
            isClickable = true
            isFocusable = true
            contentDescription = tab.label
            setOnClickListener { onSelect(tab.id) }
            addView(icon, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(7f) })
            addView(label, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(2f) }
        }
        return Item(root, icon, label, tab)
    }

    private fun applyPalette() {
        val p = palette
        view.background = pill(NavTheme.alphaOf(p.card, 0xF0))
        items.forEach { item ->
            val selected = item.tab.id == selectedId
            item.root.background = if (selected) pill(p.btn) else null
            val color = if (selected) p.onbtn else p.muted2
            item.icon.setTextColor(color)
            item.label.setTextColor(color)
            item.root.isSelected = selected
        }
    }

    private fun constrainWidth() {
        // max-width: calc(100vw - 24px) из HTML-дока.
        row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val pad = view.paddingLeft + view.paddingRight
        val maxW = screenWidthPx - dp(24f)
        val wanted = minOf(row.measuredWidth + pad, maxW)
        val lp = view.layoutParams as FrameLayout.LayoutParams
        if (lp.width != wanted) {
            lp.width = wanted
            view.layoutParams = lp
        }
    }

    private fun pill(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 100f * density
            setColor(color)
        }

    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()
}
