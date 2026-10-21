package com.example.voiceapp3.ui.cx

import android.content.Context
import android.util.TypedValue

/**
 * Доступ к токенам палитры из кода.
 *
 * Все цвета интерфейса живут в атрибутах темы (`?attr/cxInk` и т. д.), а не в
 * `colors.xml`, потому что палитр десять и переключаются они на лету. Для
 * программно создаваемых View атрибут нужно разрешить вручную — этим и
 * занимается [color].
 */
object CxTheme {

    /** Цвет из атрибута текущей темы. При отсутствии — запасной, чтобы не упасть. */
    fun color(ctx: Context, attrRes: Int, fallback: Int = 0xFF000000.toInt()): Int {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(attrRes, tv, true)) tv.data else fallback
    }

    /** dp -> px для текущей плотности экрана. */
    fun dp(ctx: Context, value: Float): Int =
        Math.round(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics))

    fun dp(ctx: Context, value: Int): Int = dp(ctx, value.toFloat())

    /** sp -> px. */
    fun sp(ctx: Context, value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, value, ctx.resources.displayMetrics)
}
