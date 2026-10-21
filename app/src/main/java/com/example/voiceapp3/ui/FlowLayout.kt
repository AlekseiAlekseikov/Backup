package com.example.voiceapp3.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

/**
 * Раскладка с переносом по строкам (аналог `display:flex; flex-wrap:wrap; gap`).
 * Дочерние элементы идут слева направо и переносятся, когда не помещаются
 * по ширине. Зазор между элементами — [spacingPx] по горизонтали и вертикали.
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    var spacingPx: Int = 0

    private val positions = ArrayList<IntArray>()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val maxW = width - paddingLeft - paddingRight
        positions.clear()
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) {
                positions.add(intArrayOf(0, 0))
                continue
            }
            child.measure(
                MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                MeasureSpec.UNSPECIFIED,
            )
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (x > 0 && x + cw > maxW) {
                x = 0
                y += rowH + spacingPx
                rowH = 0
            }
            positions.add(intArrayOf(x, y))
            x += cw + spacingPx
            if (ch > rowH) rowH = ch
        }
        val height = y + rowH + paddingTop + paddingBottom
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val p = positions[i]
            val left = paddingLeft + p[0]
            val top = paddingTop + p[1]
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}
