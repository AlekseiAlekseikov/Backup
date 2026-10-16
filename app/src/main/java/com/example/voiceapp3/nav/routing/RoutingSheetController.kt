package com.example.voiceapp3.nav.routing

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.example.voiceapp3.nav.ConceptIcons
import com.example.voiceapp3.nav.NavTheme
import java.util.Locale

/**
 * Этап 7: шторка «Офлайн-маршрутизация». Визуальный язык офлайн-шторки
 * этапа 5 (ручка, шапка с одним ✕, скругление из темы), СКРИМА НЕТ —
 * как у офлайн-шторки. Все цифры честные: размер из HEAD до скачивания,
 * прогресс в МБ без ~, компиляция помечена как «минуты» без обещаний.
 */
class RoutingSheetController(
    private val activity: Activity,
    private val density: Float,
    private val theme: NavTheme,
    private val graphs: GraphManager
) {

    val view: LinearLayout

    /** Колбэк видимости для хоста (блокировка тапов карты). */
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    /** «Граф готов» → хост может строить маршрут. */
    var onGraphReady: (() -> Unit)? = null

    private var shown = false
    private var slideAnimating = false
    private var probedSizeBytes: Long = -1L
    private var wasReady = false

    private val statusLine: TextView
    private val barBack: View
    private val barFill: View
    private var barWidthPx = 0
    private val btnRow: LinearLayout

    init {
        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(theme.sheetTop, theme.sheetBot)
            ).apply {
                cornerRadius = dpf(26f)
                setStroke(dp(1), theme.borderHairline)
            }
            visibility = View.GONE
            isClickable = true
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, dpf(26f))
                }
            }
        }
        view.addView(grip())
        view.addView(header())
        statusLine = TextView(activity).apply {
            setTextColor(theme.textSecondary)
            textSize = 13.5f
            setPadding(dp(20), dp(2), dp(20), dp(10))
        }
        view.addView(statusLine)
        // тонкая полоса прогресса (бар = доля, как в офлайн-шторке)
        barBack = View(activity).apply {
            background = GradientDrawable().apply {
                setColor(theme.page2)
                cornerRadius = dpf(3f)
            }
            post { barWidthPx = width }
        }
        barFill = View(activity).apply {
            background = GradientDrawable().apply {
                setColor(theme.accent1)
                cornerRadius = dpf(3f)
            }
        }
        val barTrack = android.widget.FrameLayout(activity).apply {
            addView(barBack, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
            addView(barFill, ViewGroup.LayoutParams(1, dp(6)))
        }
        view.addView(barTrack, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(6)
        ).apply {
            marginStart = dp(20); marginEnd = dp(20); topMargin = dp(2); bottomMargin = dp(12)
        })
        btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
        }
        view.addView(btnRow)

        graphs.listener = { st -> render(st) }
        render(graphs.currentStatus())
    }

    // ═══════════════════════════════════ видимость ════

    fun isShown(): Boolean = shown

    fun show() {
        if (slideAnimating || shown) return
        slideAnimating = true
        shown = true
        onVisibilityChanged?.invoke(true)
        // честный размер для кнопки — уточняем каждый показ (недорого)
        graphs.probeDownloadSize { probedSizeBytes = it; render(graphs.currentStatus()) }
        val h = sheetHeightPx().toFloat()
        view.visibility = View.VISIBLE
        view.translationY = h * OPEN_FROM_FRACTION
        view.animate().cancel()
        view.animate()
            .translationY(0f)
            .setDuration(OPEN_CLOSE_MS)
            .setInterpolator(SHEET_EASE)
            .withEndAction { slideAnimating = false }
            .start()
        render(graphs.currentStatus())
    }

    fun hide() {
        if (!shown) return
        shown = false
        slideAnimating = false
        view.animate().cancel()
        onVisibilityChanged?.invoke(false)
        val h = sheetHeightPx().toFloat()
        view.animate()
            .translationY(h * OPEN_FROM_FRACTION)
            .setDuration(OPEN_CLOSE_MS)
            .setInterpolator(SHEET_EASE)
            .withEndAction {
                view.visibility = View.GONE
                view.translationY = 0f
            }
            .start()
    }

    // ═══════════════════════════════════ рендер статуса ════

    private fun render(st: GraphManager.Status) {
        if (!shown) return
        when (st.phase) {
            GraphManager.Phase.MISSING -> {
                statusLine.text = buildString {
                    append("Граф маршрутов Беларуси не загружен.\n")
                    append("Маршруты строятся на устройстве (GraphHopper, без сети, без квот). ")
                    append("Один раз: скачать снапшот дорог OSM")
                    if (probedSizeBytes > 0)
                        append(String.format(Locale.US, " (%d МБ)", probedSizeBytes / 1024 / 1024))
                    append(" с Geofabrik и подготовить граф — займёт несколько минут.")
                }
                showBar(-1f)
                buttons(
                    "Скачать и подготовить" to { graphs.downloadAndBuild() }
                )
            }
            GraphManager.Phase.CHECKING -> {
                statusLine.text = st.detail
                showBar(-1f)
                buttons("Пауза" to { graphs.cancel() })
            }
            GraphManager.Phase.DOWNLOADING -> {
                val got = st.bytesGot / 1024.0 / 1024
                statusLine.text = if (st.bytesTotal > 0) String.format(
                    Locale.US, "Скачиваю снапшот дорог… %.0f из %.0f МБ\n(пауза сохраняет прогресс — продолжится с места)", got,
                    st.bytesTotal / 1024.0 / 1024)
                else String.format(Locale.US, "Скачиваю снапшот дорог… %.0f МБ", got)
                showBar(if (st.bytesTotal > 0)
                    (st.bytesGot.toDouble() / st.bytesTotal).toFloat() else 0f)
                buttons("Пауза" to { graphs.cancel() })
            }
            GraphManager.Phase.IMPORTING -> {
                statusLine.text = (st.detail.ifBlank { "Подготовка графа…" }) +
                    "\n(компиляция на устройстве занимает минуты; отмена применится после текущего прохода)"
                showBar(-1f)   // недетерминированная фаза — честно без процента
                buttons("Отмена" to { graphs.cancel() })
            }
            GraphManager.Phase.READY -> {
                // fix9: размер считается на worker'е и приходит в статусе
                // (никакого обхода дерева файлов на main-потоке)
                if (!wasReady) {
                    wasReady = true
                    onGraphReady?.invoke()
                }
                val mb = if (st.bytesTotal > 0) st.bytesTotal / 1024 / 1024
                    else graphs.bytesOnDisk() / 1024 / 1024
                statusLine.text = String.format(Locale.US,
                    "Граф готов · занимает %d МБ. Маршруты — офлайн, без квот.", mb)
                showBar(1f)
                buttons(
                    "Обновить граф (скачать заново)" to { graphs.downloadAndBuild() },
                    "Удалить граф" to { graphs.deleteAll() }
                )
            }
            GraphManager.Phase.ERROR -> {
                // fix9: честная приписка — если прежний граф цел, обновление
                // его не тронуло (staging-контракт)
                val suffix = if (graphs.hasGraphOnDisk())
                    "\n(прежний граф не пострадал и остаётся доступным)" else ""
                statusLine.text = "Не получилось: ${st.error ?: "ошибка"}$suffix"
                showBar(-1f)
                buttons(
                    "Повторить" to { graphs.downloadAndBuild() },
                    "Удалить всё" to { graphs.deleteAll() }
                )
            }
        }
    }

    private fun showBar(fraction: Float) {
        if (fraction < 0f) {
            // недетерминированно — полоска спрятана (нет ложного процента)
            barFill.layoutParams = barFill.layoutParams.apply { width = 1 }
            barFill.requestLayout()
            barBack.alpha = 0.35f
            return
        }
        barBack.alpha = 1f
        val w = if (barWidthPx > 0) barWidthPx else view.width - dp(40)
        barFill.layoutParams = barFill.layoutParams.apply {
            width = (w * fraction).toInt().coerceAtLeast(dp(3))
        }
        barFill.requestLayout()
    }

    private fun buttons(vararg pairs: Pair<String, () -> Unit>) {
        btnRow.removeAllViews()
        pairs.forEach { (label, action) ->
            btnRow.addView(TextView(activity).apply {
                text = label
                // fix8d: тёмный ink на тёмной заливке кнопки не читается → onbtn
                setTextColor(theme.onbtn)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(theme.btn, theme.btnShadowed)
                ).apply { cornerRadius = dpf(18f) }
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(10) })
        }
    }

    // ═══════════════════════════════════ каркас ════

    private fun grip(): View = View(activity).apply {
        background = GradientDrawable().apply {
            setColor(theme.borderHairline)
            cornerRadius = dpf(2f)
        }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(4)).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8)
        }
    }

    private fun header(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(20), 0, dp(14), dp(8))
        addView(TextView(activity).apply {
            text = "Офлайн-маршрутизация"
            setTextColor(theme.ink)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        val close = android.widget.ImageView(activity).apply {
            setImageBitmap(ConceptIcons.fabIcon(
                activity, ConceptIcons.Kind.CLOSE, theme.textSecondary, boxDp = 30f))
            background = GradientDrawable().apply {
                setColor(theme.page2)
                cornerRadius = dpf(16f)
            }
            setOnClickListener { hide() }
        }
        addView(close, LinearLayout.LayoutParams(dp(32), dp(32)))
    }

    private fun sheetHeightPx(): Int {
        val h = view.layoutParams?.height ?: 0
        return if (h > 0) h else (activity.resources.displayMetrics.heightPixels * 0.35f).toInt()
    }

    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpf(v: Float): Float = v * density

    companion object {
        private const val OPEN_CLOSE_MS = 380L
        private val OPEN_FROM_FRACTION = 0.94f
        private val SHEET_EASE = PathInterpolator(0.2f, 0.9f, 0.25f, 1f)
    }
}
