package com.example.voiceapp3.ui.cx

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.voiceapp3.R

/** Поставщик содержимого страниц и панелей. */
interface CxPageProvider {
    /** Заголовок страницы или панели. */
    fun title(pageId: String): String

    /**
     * Строки страницы или панели. Пустой список — панель-якорь, содержимое
     * которой рисует нативный хост (Flutter, MapLibre) поверх [CxShell.anchorContainer].
     */
    fun rows(pageId: String, state: CxState): List<CxRow>
}

/**
 * Каркас интерфейса — перенос основного макета шелла:
 *
 *     .topbar                        шапка: модель, значок P, температура,
 *                                    часы, состояние связи и версия
 *     .main
 *       section.card.carCard         карточка машины (левая колонка)
 *       section.card.contentCard     содержимое вкладки (правая колонка)
 *     .tabbar                        плавающий док-пилюля поверх всего
 *
 * Существенная деталь, которую первая версия каркаса не учитывала: сайдбар
 * `.cx-nav` (340px) — это НЕ колонка основного макета, а часть содержимого
 * вкладки «Инструменты». Он рисуется внутри `#content` рядом с `#cxPage`.
 * На остальных вкладках его нет.
 *
 * Порядок слоёв сохранён: контент на всё окно, панель-якорь и док поверх.
 * В шелле WebView был прозрачным, а Flutter-хосты и карта рисовали поверх него
 * с отступом под док; здесь то же самое даёт [anchorContainer].
 */
class CxShell(
    private val activity: Activity,
    private val provider: CxPageProvider,
    private val write: CxWrite,
    private val act: CxAct,
    private val onSay: ((String) -> Unit)? = null,
) {

    private val ctx: Context get() = activity

    private lateinit var rootFrame: FrameLayout
    private lateinit var topBarView: View
    private lateinit var mainView: LinearLayout
    private lateinit var contentHost: LinearLayout
    private lateinit var navHost: LinearLayout
    private lateinit var anchorHost: FrameLayout
    private lateinit var dockHost: LinearLayout

    // карточка машины
    private lateinit var rangeView: TextView
    private lateinit var batteryView: TextView
    private lateinit var batteryLimitView: TextView
    private lateinit var hoodView: LinearLayout
    private lateinit var trunkView: LinearLayout
    private lateinit var lockView: TextView
    private lateinit var chargeView: TextView
    private lateinit var battFill: View
    private lateinit var battBarTrack: FrameLayout
    private lateinit var paintName: TextView
    private val paintDots = LinkedHashMap<String, View>()

    // шапка
    private lateinit var outTempView: TextView
    private lateinit var clockView: TextView
    private lateinit var connView: TextView
    private lateinit var parkedView: TextView

    private val tabViews = LinkedHashMap<String, TextView>()
    private val navViews = LinkedHashMap<String, TextView>()
    private val openGroups = LinkedHashSet<String>()

    var currentTab: String = "quick"
        private set
    var currentTool: String = "screen"
        private set

    private var page: CxPage? = null
    private var state: CxState = CxState.EMPTY
    private var notes: CxNoteSource = NoNotes
    private var themeStore: CxThemeStore? = null
    private var paletteSink: ((String) -> Unit)? = null
    private var selectSink: ((String, String) -> Unit)? = null

    /** Хранилище палитр; без него плитки выбора не подсвечены. */
    fun setThemeStore(store: CxThemeStore?) { themeStore = store }

    companion object {
        /**
         * Вкладка, на которой контент рисует нативный хост карты, а каркас
         * становится прозрачным. Совпадает с условием `id==='navi'` в
         * `select()` шелла.
         */
        const val NAV_FULL_TAB = "navi"

        /**
         * Вкладки full-bleed видео — условие `body.projfull` из шелла и
         * `stackOf(id)` (index.html: `id==='carplay' ? 'carplay' :
         * id==='aa' ? 'androidAuto' : null`).
         */
        val PROJ_FULL_TABS = setOf("carplay", "aa")
    }

    /** Приёмник выбора палитры — должен вызывать [CxPalettes.select]. */
    fun setPaletteSink(sink: ((String) -> Unit)?) { paletteSink = sink }

    /** Приёмник выбора файла из списка скопированных звуков. */
    fun setSelectSink(sink: ((String, String) -> Unit)?) { selectSink = sink }

    // ── построение ──────────────────────────────────────────────────────

    fun build(): View {
        rootFrame = FrameLayout(ctx).apply {
            setBackgroundColor(CxTheme.color(ctx, R.attr.cxPage1))
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        topBarView = buildTopBar()
        col.addView(topBarView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val main = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        mainView = main
        main.addView(buildCarCard(), LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 0.82f))
        contentHost = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        main.addView(contentHost, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1.6f))
        col.addView(main, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        rootFrame.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        anchorHost = FrameLayout(ctx)
        rootFrame.addView(anchorHost, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        rootFrame.addView(buildDock(), dockLp())

        /* Начальная отрисовка. Раньше paintTabs() и renderContent() звались
           только из selectTab() и selectTool(), то есть по первому тапу: на
           старте правая часть экрана оставалась пустой, а выбранная вкладка
           не подсвеченной — ровно то, что видно на скриншоте с эмулятора. */
        paintTabs()
        renderContent()

        rootFrame.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (l != ol || t != ot || r != or_ || b != ob) onLayoutChanged?.invoke()
        }

        /* Модальные окна — самый верхний слой. В шелле `#modalRoot` добавлялся
           в конец body, то есть поверх дока; порядок сохранён. */
        modals = CxModals(ctx, rootFrame, act)
        return rootFrame
    }

    private var modals: CxModals? = null

    /** Модальные окна: справка разделов и список keep-alive. */
    fun modals(): CxModals? = modals

    /**
     * Кнопка «Назад»: сначала закрывается модальное окно, и только потом
     * приложение выходит. Порт `window.__nativeBack`.
     */
    fun onBackPressed(): Boolean = modals?.onBackPressed() == true

    private fun dockLp(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        bottomMargin = CxTheme.dp(ctx, CxMetrics.DOCK_BOTTOM_DP)
        leftMargin = CxTheme.dp(ctx, CxMetrics.DOCK_SIDE_MARGIN_DP)
        rightMargin = CxTheme.dp(ctx, CxMetrics.DOCK_SIDE_MARGIN_DP)
    }

    /** Высота дока в пикселях после раскладки — для `setDockInsetPx` у хостов. */
    fun dockInsetPx(): Int {
        val v = if (::dockHost.isInitialized) dockHost else return 0
        return v.height + CxTheme.dp(ctx, CxMetrics.DOCK_BOTTOM_DP)
    }

    /**
     * Прямоугольник области проекции в пикселях окна: x, y, ширина, высота.
     *
     * Замена `@JavascriptInterface fun projectionFrame(stack, x, y, w, h)`
     * (`UiBridge.kt:769-772`), куда координаты приходили из JS как
     * `getBoundingClientRect × devicePixelRatio`. Комментарий там же объяснял,
     * почему системы координат совпадали: WebView занимал всё окно. Теперь
     * всё окно занимает каркас, поэтому совпадение сохраняется, а меряем мы
     * слой-якорь напрямую — он и есть область под оверлей.
     *
     * Плотность экрана не умножается: `getLocationInWindow` и `width`/`height`
     * уже в физических пикселях, в отличие от CSS-пикселей JS.
     */
    fun projectionFramePx(): IntArray {
        if (!::rootFrame.isInitialized) return intArrayOf(0, 0, 0, 0)
        val w = rootFrame.width
        if (w <= 0) return intArrayOf(0, 0, 0, 0)

        /* Нижняя кромка — верх плавающего дока минус зазор, как в шелле.
           Разность двух getLocationInWindow намеренно: `ProjectionRenderBridge
           .frame` раскладывает оверлей вызовом `view.layout(l, t, r, b)`, то
           есть в координатах РОДИТЕЛЬСКОГО FrameLayout из activity_main.xml,
           а не окна. Каркас занимает тот же FrameLayout целиком, поэтому его
           собственное смещение равно нулю, а смещение окна (строка состояния)
           в разности сокращается. */
        var bottom = rootFrame.height
        if (::dockHost.isInitialized && dockHost.height > 0) {
            val root = IntArray(2); rootFrame.getLocationInWindow(root)
            val dock = IntArray(2); dockHost.getLocationInWindow(dock)
            bottom = (dock[1] - root[1]) -
                CxTheme.dp(ctx, CxMetrics.PROJ_DOCK_GAP_DP)
        }
        val h = maxOf(CxTheme.dp(ctx, CxMetrics.PROJ_MIN_HEIGHT_DP), bottom)
        return intArrayOf(0, 0, w, h)
    }

    /**
     * Вызывается после каждой раскладки. Сюда слой обвязки передаёт хостам
     * отступ под док и прямоугольник проекции — оба значения до раскладки
     * равны нулю, поэтому публиковать их раньше бессмысленно.
     */
    var onLayoutChanged: (() -> Unit)? = null

    // ── шапка (.topbar) ─────────────────────────────────────────────────

    private fun buildTopBar(): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(CxTheme.color(ctx, R.attr.cxBarBg), CxTheme.dp(ctx, 14f))
            val ph = CxTheme.dp(ctx, CxMetrics.TOPBAR_PAD_H_DP)
            setPadding(ph, CxTheme.dp(ctx, CxMetrics.TOPBAR_PAD_TOP_DP),
                ph, CxTheme.dp(ctx, CxMetrics.TOPBAR_PAD_BOTTOM_DP))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                val m = CxTheme.dp(ctx, 12f)
                topMargin = m; marginStart = m; marginEnd = m
            }
        }
        val title = TextView(ctx).apply {
            text = "Geely EX2  P"
            setTextColor(CxTheme.color(ctx, R.attr.cxInk))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 17f))
            setTypeface(null, Typeface.BOLD)
        }
        bar.addView(title)
        parkedView = TextView(ctx).apply {
            text = "· Припаркован"
            setTextColor(CxTheme.color(ctx, R.attr.cxMuted))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 13f))
            (layoutParams as? LinearLayout.LayoutParams)?.marginStart =
                CxTheme.dp(ctx, 8f)
        }
        bar.addView(parkedView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = CxTheme.dp(ctx, 8f)
        })

        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        outTempView = topChip(ctx, "+12°")
        clockView = topChip(ctx, "--:--")
        connView = topChip(ctx, "Подключено к машине")
        for (v in listOf(outTempView, clockView, connView)) {
            right.addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = CxTheme.dp(ctx, CxMetrics.TOPBAR_GAP_DP)
            })
        }
        bar.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return bar
    }

    private fun topChip(c: Context, text: String) = TextView(c).apply {
        this.text = text
        setTextColor(CxTheme.color(c, R.attr.cxMuted))
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(c, 13f))
    }

    // ── карточка машины (.carCard) ──────────────────────────────────────

    private fun buildCarCard(): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(CxTheme.color(ctx, R.attr.cxCard), CxTheme.dp(ctx, 16f))
            val p = CxTheme.dp(ctx, 18f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                val m = CxTheme.dp(ctx, 12f)
                marginStart = m; marginEnd = m / 2; bottomMargin = m
            }
        }
        card.addView(TextView(ctx).apply {
            text = "Запас хода"
            setTextColor(CxTheme.color(ctx, R.attr.cxMuted2))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 11.5f))
        })

        val rangeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        rangeView = TextView(ctx).apply {
            text = "—"
            setTextColor(CxTheme.color(ctx, R.attr.cxInk))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 40f))
            setTypeface(null, Typeface.BOLD)
        }
        rangeRow.addView(rangeView)
        rangeRow.addView(TextView(ctx).apply {
            text = " км"
            setTextColor(CxTheme.color(ctx, R.attr.cxMuted))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 14f))
        })
        val battCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        batteryView = TextView(ctx).apply {
            text = "—"
            setTextColor(CxTheme.color(ctx, R.attr.cxInk))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 20f))
            setTypeface(null, Typeface.BOLD)
        }
        batteryLimitView = TextView(ctx).apply {
            text = "батарея"
            setTextColor(CxTheme.color(ctx, R.attr.cxMuted2))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 11.5f))
        }
        battCol.addView(batteryView); battCol.addView(batteryLimitView)
        rangeRow.addView(battCol, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(rangeRow)

        // Капот / замок / багажник
        val callouts = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        callouts.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = CxTheme.dp(ctx, 14f)
        }
        hoodView = latchButton(ctx, "Капот", "закрыт") { act.act(CxCarCard.ACT_HOOD, mapOf("open" to "true")) }
        trunkView = latchButton(ctx, "Багажник", "закрыт") { act.act(CxCarCard.ACT_TRUNK, mapOf("open" to "true")) }
        lockView = TextView(ctx).apply {
            text = CxCarCard.lockGlyph(0)
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 22f))
            background = roundRect(CxTheme.color(ctx, R.attr.cxCard2), CxTheme.dp(ctx, 12f))
        }
        callouts.addView(hoodView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        callouts.addView(lockView, LinearLayout.LayoutParams(
            CxTheme.dp(ctx, 52f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = CxTheme.dp(ctx, 8f); marginEnd = CxTheme.dp(ctx, 8f)
        })
        callouts.addView(trunkView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(callouts)

        // Полоса заряда. Нажатие не навешивается: событие car.charging мертво
        // (см. CxCarCard.ACT_CHARGING_DEAD).
        battBarTrack = FrameLayout(ctx).apply {
            background = roundRect(CxTheme.color(ctx, R.attr.cxTrack), CxTheme.dp(ctx, 999f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, CxTheme.dp(ctx, 9f)).apply {
                topMargin = CxTheme.dp(ctx, 16f)
            }
        }
        battFill = View(ctx).apply {
            background = roundRect(CxTheme.color(ctx, R.attr.cxGreen), CxTheme.dp(ctx, 999f))
        }
        battBarTrack.addView(battFill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(battBarTrack)

        chargeView = TextView(ctx).apply {
            text = "Не заряжается"
            setTextColor(CxTheme.color(ctx, R.attr.cxMuted))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 12f))
            gravity = Gravity.CENTER
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = CxTheme.dp(ctx, 6f)
        }
        card.addView(chargeView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = CxTheme.dp(ctx, 6f)
        })

        card.addView(buildPaintPicker())
        return card
    }

    private fun latchButton(c: Context, name: String, value: String,
                            onClick: () -> Unit): LinearLayout {
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundRect(CxTheme.color(c, R.attr.cxCard2), CxTheme.dp(c, 12f))
            val p = CxTheme.dp(c, 10f)
            setPadding(p, p, p, p)
            setOnClickListener { onClick() }
        }
        box.addView(TextView(c).apply {
            text = name
            setTextColor(CxTheme.color(c, R.attr.cxMuted))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(c, 11.5f))
        })
        box.addView(TextView(c).apply {
            text = value
            setTextColor(CxTheme.color(c, R.attr.cxInk))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(c, 13f))
            setTypeface(null, Typeface.BOLD)
            tag = "latchValue"
        })
        return box
    }

    /** Выбор цвета кузова — `.colors`: подпись, шесть образцов, имя выбранного. */
    private fun buildPaintPicker(): View {
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = CxTheme.dp(ctx, 18f)
            }
        }
        wrap.addView(TextView(ctx).apply {
            text = "Цвет"
            setTextColor(CxTheme.color(ctx, R.attr.cxMuted2))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 11.5f))
        })
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = CxTheme.dp(ctx, 8f)
        }
        val size = CxTheme.dp(ctx, 26f)
        for (p in CxCarCard.PAINTS) {
            val dot = View(ctx).apply {
                background = roundRect(p.color, size / 2)
                setOnClickListener {
                    act.act(CxCarCard.ACT_COLOR, mapOf("color" to p.id))
                    paintDots.forEach { (k, v) -> paintRing(v, k == p.id) }
                    paintName.text = p.title
                }
            }
            paintDots[p.id] = dot
            row.addView(dot, LinearLayout.LayoutParams(size, size).apply {
                marginEnd = CxTheme.dp(ctx, 8f)
            })
        }
        paintName = TextView(ctx).apply {
            text = CxCarCard.PAINTS[1].title
            setTextColor(CxTheme.color(ctx, R.attr.cxInk))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 12.5f))
        }
        row.addView(paintName, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        wrap.addView(row)
        return wrap
    }

    private fun paintRing(v: View, selected: Boolean) {
        val pad = if (selected) CxTheme.dp(ctx, 3f) else 0
        v.setPadding(pad, pad, pad, pad)
        v.background = if (selected) {
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(CxTheme.color(ctx, R.attr.cxSel))
            }
        } else null
    }

    // ── док (.tabbar) ───────────────────────────────────────────────────

    private fun buildDock(): View {
        dockHost = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(CxTheme.color(ctx, R.attr.cxBarBg),
                CxTheme.dp(ctx, CxMetrics.DOCK_RADIUS_DP))
            val p = CxTheme.dp(ctx, CxMetrics.DOCK_PAD_DP)
            setPadding(p, p, p, p)
        }
        for (t in CxNav.TABS) {
            val v = TextView(ctx).apply {
                text = t.icon + "  " + t.label
                gravity = Gravity.CENTER
                setTextColor(CxTheme.color(ctx, R.attr.cxMuted))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    CxTheme.sp(ctx, CxMetrics.TAB_TEXT_SP))
                val pv = CxTheme.dp(ctx, CxMetrics.TAB_PAD_V_DP)
                val ph = CxTheme.dp(ctx, CxMetrics.TAB_PAD_H_DP)
                setPadding(ph, pv, ph, pv)
                setOnClickListener { selectTab(t.id) }
            }
            tabViews[t.id] = v
            dockHost.addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                val g = CxTheme.dp(ctx, CxMetrics.DOCK_GAP_DP)
                marginStart = g; marginEnd = g
            })
        }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(dockHost)
        }
    }

    // ── выбор раздела ───────────────────────────────────────────────────

    fun selectTab(id: String) {
        if (CxNav.TABS.none { it.id == id }) return
        currentTab = id
        paintTabs()
        renderContent()
        onTabChanged?.invoke(id)
    }

    fun selectTool(id: String) {
        val path = CxNav.pathTo(id) ?: return
        for (n in path) if (n is CxNav.Node.Group) openGroups.add(n.id)
        currentTool = id
        renderContent()
    }

    /**
     * Показ и скрытие нативных хостов при смене вкладки.
     *
     * В шелле это делал обработчик `tab.change` в `UiBridge.kt:465-476`:
     * `telemetryHost?.show()/hide()` и `navHost?.show()/hide()`. Здесь тот же
     * контракт, но вызывать его должен слой обвязки — у каркаса нет ссылок на
     * хосты.
     */
    var onTabChanged: ((tabId: String) -> Unit)? = null

    private fun paintTabs() {
        for ((id, v) in tabViews) {
            val sel = id == currentTab
            v.background = if (sel)
                roundRect(CxTheme.color(ctx, R.attr.cxSel),
                    CxTheme.dp(ctx, CxMetrics.TAB_RADIUS_DP)) else null
            v.setTextColor(CxTheme.color(ctx,
                if (sel) R.attr.cxOnSel else R.attr.cxMuted))
            v.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    // ── содержимое ──────────────────────────────────────────────────────

    /**
     * Режим `body.navfull` из шелла — вкладка «Навигация».
     *
     * Точный порт трёх действующих правил CSS (index.html:203-206):
     *
     *     body.navfull{background:transparent!important}
     *     body.navfull .topbar{display:none}
     *     body.navfull .main{display:none}
     *     body.navfull #climBar{display:none}
     *
     * Карта `NavigationHostController` добавляется в `decorRoot` ПОД контентом
     * активности (mapLayer с index 0), поэтому на этой вкладке каркас обязан
     * быть прозрачным целиком — иначе карту не видно. Видимым остаётся только
     * плавающий док. Класс ставил `select()` в index.html:899
     * (`id==='navi'`), а фон виджета WebView красил `UiBridge
     * .applyNavWebViewBg` — оба механизма заменены этим методом.
     *
     * Четвёртому правилу соответствовать нечему: элемента с id `climBar` в
     * шелле нет (проверено поиском по index.html), климат живёт в плитках
     * быстрого доступа внутри `.main` и скрывается вместе с ним.
     */
    /**
     * Полноэкранные режимы каркаса — порты классов `body.navfull` и
     * `body.projfull` из шелла.
     */
    private fun applyNavFull() {
        if (!::rootFrame.isInitialized) return
        val nav = currentTab == NAV_FULL_TAB
        val proj = currentTab in PROJ_FULL_TABS
        rootFrame.setBackgroundColor(
            if (nav) Color.TRANSPARENT else CxTheme.color(ctx, R.attr.cxPage1))
        /* Верхняя строка скрывается в обоих режимах: `body.navfull .topbar` и
           `body.projfull .topbar` (index.html:204 и 22). */
        if (::topBarView.isInitialized)
            topBarView.visibility = if (nav || proj) View.GONE else View.VISIBLE
        /* `.main` скрывается только на «Навигации» (`body.navfull .main`,
           index.html:205). На вкладках проекции CSS её не трогал: там
           `#projView` становился `position:fixed` на всю ширину с z-index 30
           и накрывал её собой, а док (z-index 40) оставался сверху. Нативный
           оверлей TextureView играет ту же роль, поэтому содержимое не
           прячем — иначе под оверлеем останется дыра в фоне, когда видео
           ещё не привязано. */
        if (::mainView.isInitialized)
            mainView.visibility = if (nav) View.GONE else View.VISIBLE
    }

    private fun renderContent() {
        applyNavFull()
        contentHost.removeAllViews()
        if (currentTab == "tools") {
            navHost = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = roundRect(CxTheme.color(ctx, R.attr.cxCard), CxTheme.dp(ctx, 16f))
            }
            contentHost.addView(navHost, LinearLayout.LayoutParams(
                CxTheme.dp(ctx, CxMetrics.NAV_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                val m = CxTheme.dp(ctx, 12f)
                marginEnd = m / 2; bottomMargin = m
            })
            renderNav()
        }
        val pageCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val p = newPage()
        val pageId = if (currentTab == "tools") toolPageId(currentTool) else currentTab
        val rows = provider.rows(pageId, state)
        pageCol.addView(p.render(rows, state, notes), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        page = p
        contentHost.addView(pageCol, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun newPage(): CxPage = CxPage(ctx, write, act, onSay).also {
        it.themeStore = themeStore
        it.paletteSink = paletteSink
        it.selectSink = selectSink
        /* Локальные действия, которые не уходят в нативную часть: открытие
           модалки keep-alive и очистка журнала. В шелле это были прямые вызовы
           `cxOpenKeepAliveModal()` и `cxDevClearLogs()`. */
        it.localAction = { action ->
            when (action) {
                CxScreenPerformance.ACT_MANAGE_KEEP_ALIVE -> {
                    modals?.openKeepAlive(); true
                }
                else -> false
            }
        }
    }

    private fun toolPageId(id: String): String = when (val n = CxNav.find(id)) {
        is CxNav.Node.Page -> n.page
        is CxNav.Node.Group -> n.page ?: n.id
        null -> id
    }

    private fun renderNav() {
        navHost.removeAllViews()
        navViews.clear()
        val pad = CxTheme.dp(ctx, 12f)
        navHost.setPadding(pad, CxTheme.dp(ctx, CxMetrics.TOPBAR_PAD_TOP_DP), pad, pad)
        for (n in CxNav.TOOLS) addNavNode(n, 0)
        paintNav()
    }

    private fun addNavNode(node: CxNav.Node, depth: Int) {
        val selectable = node is CxNav.Node.Page ||
            (node is CxNav.Node.Group && node.page != null)
        val v = TextView(ctx).apply {
            text = node.label
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(CxTheme.color(ctx, R.attr.cxInk))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                CxTheme.sp(ctx, if (depth == 0) 15f else 14f))
            setTypeface(null, if (depth == 0) Typeface.BOLD else Typeface.NORMAL)
            val pv = CxTheme.dp(ctx, 10f)
            setPadding(CxTheme.dp(ctx, 10f + depth * 14f), pv, CxTheme.dp(ctx, 10f), pv)
            if (selectable) setOnClickListener { selectTool(node.id) }
        }
        navViews[node.id] = v
        navHost.addView(v, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (node is CxNav.Node.Group && node.children.isNotEmpty() &&
            openGroups.contains(node.id)) {
            for (c in node.children) addNavNode(c, depth + 1)
        }
    }

    private fun paintNav() {
        for ((id, v) in navViews) {
            val sel = id == currentTool
            v.background = if (sel)
                roundRect(CxTheme.color(ctx, R.attr.cxSel), CxTheme.dp(ctx, 10f)) else null
            if (sel) v.setTextColor(CxTheme.color(ctx, R.attr.cxOnSel))
        }
    }

    /** Контейнер для нативных хостов (Flutter, MapLibre) на вкладках-якорях. */
    fun anchorContainer(): FrameLayout {
        requireBuilt("anchorContainer()")
        return anchorHost
    }

    /**
     * Виджет плавающего дока.
     *
     * Нужен `NavigationHostController`: его слой хрома добавляется в
     * `decorRoot` поверх контента активности и сам маршрутизирует тапы,
     * поэтому полосу дока он проксирует сюда (`dockTargetView`). Раньше
     * целью проксирования был WebView целиком.
     */
    fun dockView(): View {
        requireBuilt("dockView()")
        return dockHost
    }

    /**
     * Каркас построен? `build()` вызывается лениво — при первом обращении к
     * `CxUi.root`. До него все `lateinit`-поля не инициализированы, и любой
     * геттер виджета бросает `UninitializedPropertyAccessException` без
     * указания причины. Поэтому доступы идут через [requireBuilt].
     */
    val isBuilt: Boolean get() = ::rootFrame.isInitialized

    private fun requireBuilt(who: String) {
        if (!isBuilt) throw IllegalStateException(
            "CxShell.$who вызван до build(): сначала обратитесь к CxUi.root " +
            "(или CxUi.dockView(), который делает это сам)")
    }

    // ── состояние ───────────────────────────────────────────────────────

    /** Обновить снимок и подписи без пересборки — аналог `cxSyncControls`. */
    fun bind(newState: CxState, newNotes: CxNoteSource) {
        state = newState; notes = newNotes
        page?.bind(newState, newNotes)
        bindCarCard(newState)
        bindTopBar(newState)
    }

    /** Полная перерисовка содержимого — аналог `render()` в шелле. */
    fun repaint() {
        if (::contentHost.isInitialized) renderContent()
    }

    private fun bindCarCard(s: CxState) {
        if (!::rangeView.isInitialized) return
        rangeView.text = if (s.has(CxCarCard.KEY_RANGE_KM))
            s.int(CxCarCard.KEY_RANGE_KM, 0).toString() else "—"
        val pct = if (s.has(CxCarCard.KEY_BATTERY_PCT)) s.int(CxCarCard.KEY_BATTERY_PCT, 0) else -1
        batteryView.text = if (pct >= 0) "$pct%" else "—"
        val lim = s.int(CxCarCard.KEY_BATTERY_LIMIT, 0)
        batteryLimitView.text = if (lim > 0) "батарея · лимит $lim%" else "батарея"
        hoodValue().text = CxCarCard.latchLabel(s.bool(CxCarCard.KEY_HOOD, false))
        trunkValue().text = CxCarCard.latchLabel(s.bool(CxCarCard.KEY_TRUNK, false))
        lockView.text = CxCarCard.lockGlyph(s.int(CxCarCard.KEY_LOCK, 0))
        chargeView.text = if (s.bool(CxCarCard.KEY_CHARGING, false)) "Заряжается" else "Не заряжается"
        val fillPct = if (pct >= 0) pct else 0
        battBarTrack.post {
            val lp = battFill.layoutParams
            lp.width = battBarTrack.width * fillPct / 100
            battFill.layoutParams = lp
        }
        val paint = s.string(CxCarCard.PREF_CAR_PAINT, CxCarCard.PAINTS[1].id)
        CxCarCard.paintById(paint)?.let {
            paintName.text = it.title
            paintDots.forEach { (k, v) -> paintRing(v, k == it.id) }
        }
    }

    private fun hoodValue(): TextView =
        hoodView.findViewWithTag("latchValue") ?: TextView(ctx)

    private fun trunkValue(): TextView =
        trunkView.findViewWithTag("latchValue") ?: TextView(ctx)

    private fun bindTopBar(s: CxState) {
        if (!::clockView.isInitialized) return
        s.string("clock", "").takeIf { it.isNotEmpty() }?.let { clockView.text = it }
        if (s.has(CxCarCard.KEY_OUT_TEMP))
            outTempView.text = "%+d°".format(s.int(CxCarCard.KEY_OUT_TEMP, 0))
        parkedView.text = if (s.bool(CxCarCard.KEY_PARKED, true)) "· Припаркован" else "· В движении"
        s.string("appVersion", "").takeIf { it.isNotEmpty() }?.let {
            connView.text = "Подключено к машине · EX2 v$it"
        }
    }

    private fun roundRect(color: Int, radiusPx: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color); cornerRadius = radiusPx.toFloat()
        }
}
