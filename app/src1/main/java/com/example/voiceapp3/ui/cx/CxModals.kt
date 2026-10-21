package com.example.voiceapp3.ui.cx

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.voiceapp3.R

/**
 * Приложение из списка keep-alive.
 *
 * Форма совпадает с тем, что присылает `AppPerformance.getInstalledAppsInfo`
 * и что рисовал `cxRenderKeepAliveList`: `label`, `packageName`, `enabled`.
 */
data class CxKeepAliveApp(
    val label: String,
    val packageName: String,
    var enabled: Boolean,
)

/**
 * Модальные окна — замена `mRoot()` / `closeModal()` / `cxHelpOpen()` и
 * семейства `cxOpenKeepAliveModal` / `cxRenderKeepAliveList` /
 * `cxSaveKeepAliveModal`.
 *
 * В шелле модалка рисовалась в отдельный `#modalRoot`, добавленный в `body`,
 * и закрывалась очисткой его содержимого; клик по подложке
 * (`.mBack`, не по карточке) тоже закрывал. Здесь — слой поверх всего
 * содержимого [CxShell], тот же порядок: контент, якорь хостов, док, модалка.
 */
class CxModals(
    private val ctx: Context,
    private val host: FrameLayout,
    private val act: CxAct,
) {

    private var current: View? = null

    /** Открытое окно keep-alive; нужно, чтобы подать ему пришедший список. */
    var keepAlive: CxKeepAliveModal? = null
        private set

    val isOpen: Boolean get() = current != null

    /**
     * Кнопка «Назад». Порт `window.__nativeBack`: если модалка открыта —
     * закрыть её и вернуть true (системный выход отменяется), иначе false.
     */
    fun onBackPressed(): Boolean {
        if (current == null) return false
        close()
        return true
    }

    fun close() {
        current?.let { host.removeView(it) }
        current = null
        keepAlive = null
    }

    // ── справка ─────────────────────────────────────────────────────────

    /**
     * Окно справки раздела — порт `cxHelpOpen(page)`.
     *
     * Состав берётся из [CxHelp] (каталог `CX_HELP`): заголовок и список пар
     * «подпись — текст». Если раздела в каталоге нет, окно не открывается —
     * как в шелле: `if(!items) return`.
     */
    fun showHelp(pageId: String) {
        val section = CxHelp.of(pageId) ?: return
        close()
        val card = card(CxMetrics.MODAL_CARD_WIDTH_DP)
        card.addView(title(section.title))

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (item in section.items) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val pv = CxTheme.dp(ctx, CxMetrics.HELP_ROW_PAD_V_DP)
            row.setPadding(0, pv, 0, pv)
            row.addView(TextView(ctx).apply {
                text = item.label
                setTextColor(CxTheme.color(ctx, R.attr.cxInk))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 14f))
                setTypeface(null, Typeface.BOLD)
            })
            row.addView(TextView(ctx).apply {
                text = item.text
                setTextColor(CxTheme.color(ctx, R.attr.cxMuted))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 12.5f))
                setPadding(0, CxTheme.dp(ctx, 3f), 0, 0)
            })
            body.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            body.addView(divider())
        }
        card.addView(body)
        card.addView(buttonRow(listOf("ЗАКРЫТЬ" to null)) { close() })
        present(card)
    }

    // ── keep-alive ──────────────────────────────────────────────────────

    /**
     * Окно «Всегда активные приложения» — порт `cxOpenKeepAliveModal()`.
     *
     * Как и в шелле, при открытии отправляется `perf.getKeepAliveList`, а
     * список приходит асинхронно и подставляется в уже открытое окно
     * (`CxWiring.onKeepAliveApps` → [CxKeepAliveModal.setApps]). До прихода
     * списка показывается «Загрузка списка приложений…».
     *
     * Браузерный фолбэк шелла с пятью выдуманными приложениями
     * (Spotify, Яндекс.Музыка, Яндекс.Навигатор, YouTube Music, 2ГИС) НЕ
     * перенесён: это имитация, а не данные.
     */
    fun openKeepAlive() {
        close()
        val modal = CxKeepAliveModal(ctx, act) { close() }
        keepAlive = modal
        val card = card(CxMetrics.KEEPALIVE_WIDTH_DP)
        card.addView(title("Всегда активные приложения"))
        card.addView(TextView(ctx).apply {
            text = "Отметьте приложения, которые система не должна выгружать " +
                "из памяти при работе в фоне:"
            setTextColor(CxTheme.color(ctx, R.attr.cxMuted))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 14f))
            setPadding(0, 0, 0, CxTheme.dp(ctx, 14f))
        })
        card.addView(modal.buildListHost(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(buttonRow(listOf("ОТМЕНА" to null, "СОХРАНИТЬ" to "accent")) { which ->
            if (which == 1) modal.save()
            close()
        })
        modal.attach(this)
        present(card)
        act.act("perf.getKeepAliveList", emptyMap())
    }

    // ── общее ───────────────────────────────────────────────────────────

    private fun present(card: View) {
        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(CxMetrics.MODAL_SCRIM)
            // клик по подложке (не по карточке) закрывает — как
            // onclick="if(event.target===this)closeModal()"
            setOnClickListener { close() }
        }
        val p = CxTheme.dp(ctx, CxMetrics.MODAL_PAD_DP)
        scrim.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        card.setPadding(p, p, p, p)
        host.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        current = scrim
    }

    private fun card(widthDp: Float) = ScrollView(ctx).apply {
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        inner.background = roundRect(CxTheme.color(ctx, R.attr.cxCard),
            CxTheme.dp(ctx, CxMetrics.MODAL_RADIUS_DP))
        val pad = CxTheme.dp(ctx, CxMetrics.MODAL_CARD_PAD_DP)
        inner.setPadding(pad, pad, pad, pad)
        addView(inner, ViewGroup.LayoutParams(
            CxTheme.dp(ctx, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT))
        tag = inner
    }

    private fun title(text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(CxTheme.color(ctx, R.attr.cxInk))
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, CxMetrics.MODAL_TITLE_SP))
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 0, 0, CxTheme.dp(ctx, 12f))
    }

    private fun divider() = View(ctx).apply {
        setBackgroundColor(CxTheme.color(ctx, R.attr.cxLine))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, CxTheme.dp(ctx, 1f)))
    }

    /**
     * Ряд кнопок `.btnrow`. `onClick` получает индекс нажатой кнопки.
     * `kind == "accent"` даёт зелёный фон, как у «СОХРАНИТЬ» в шелле.
     */
    private fun buttonRow(items: List<Pair<String, String?>>,
                          onClick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = CxTheme.dp(ctx, CxMetrics.BTN_ROW_MARGIN_TOP_DP)
            }
        }
        val gap = CxTheme.dp(ctx, CxMetrics.BTN_ROW_GAP_DP)
        for ((i, item) in items.withIndex()) {
            val accent = item.second == "accent"
            val b = TextView(ctx).apply {
                text = item.first
                gravity = Gravity.CENTER
                setTextColor(if (accent) 0xFFFFFFFF.toInt()
                             else CxTheme.color(ctx, R.attr.cxInk))
                background = roundRect(CxTheme.color(ctx,
                    if (accent) R.attr.cxGreen else R.attr.cxCard2),
                    CxTheme.dp(ctx, CxMetrics.TILE_BTN_RADIUS_DP))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 14f))
                setTypeface(null, Typeface.BOLD)
                val pv = CxTheme.dp(ctx, CxMetrics.TILE_BTN_PAD_V_DP)
                val ph = CxTheme.dp(ctx, CxMetrics.TILE_BTN_PAD_H_DP)
                setPadding(ph, pv, ph, pv)
                setOnClickListener { onClick(i) }
            }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = gap
            })
        }
        return row
    }

    private fun roundRect(color: Int, radiusPx: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }
}

/**
 * Содержимое окна keep-alive. Вынесено в отдельный класс, потому что список
 * приходит асинхронно уже после открытия окна.
 */
class CxKeepAliveModal(
    private val ctx: Context,
    private val act: CxAct,
    private val onClose: () -> Unit,
) {

    private var apps: List<CxKeepAliveApp> = emptyList()
    private var listHost: LinearLayout? = null
    private var modals: CxModals? = null

    fun attach(m: CxModals) { modals = m }

    fun buildListHost(): View {
        val scroll = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                CxTheme.dp(ctx, CxMetrics.KEEPALIVE_LIST_MAX_DP))
        }
        val host = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        listHost = host
        scroll.addView(host)
        render()
        return scroll
    }

    /** Список пришёл из нативной части — порт `window.__onKeepAliveApps`. */
    fun setApps(list: List<CxKeepAliveApp>) {
        apps = list
        render()
    }

    private fun render() {
        val host = listHost ?: return
        host.removeAllViews()
        if (apps.isEmpty()) {
            host.addView(TextView(ctx).apply {
                text = "Загрузка списка приложений…"
                gravity = Gravity.CENTER
                setTextColor(CxTheme.color(ctx, R.attr.cxMuted))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 13f))
                val p = CxTheme.dp(ctx, 24f)
                setPadding(0, p, 0, p)
            })
            return
        }
        for ((i, app) in apps.withIndex()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pv = CxTheme.dp(ctx, 10f)
                setPadding(0, pv, 0, pv)
            }
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(ctx).apply {
                text = app.label
                setTextColor(CxTheme.color(ctx, R.attr.cxInk))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 16f))
                setTypeface(null, Typeface.BOLD)
            })
            col.addView(TextView(ctx).apply {
                text = app.packageName
                setTextColor(CxTheme.color(ctx, R.attr.cxMuted2))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 12f))
            })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = CxTheme.dp(ctx, 12f)
            })
            val cb = CheckBox(ctx).apply {
                isChecked = app.enabled
                setOnCheckedChangeListener { _, checked -> apps[i].enabled = checked }
            }
            row.addView(cb)
            host.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /**
     * Сохранение — порт `cxSaveKeepAliveModal()`: собираются `packageName`
     * отмеченных приложений и уходят одним событием `perf.saveKeepAlive`
     * с payload `{packages: [String]}`.
     */
    fun save() {
        val selected = apps.filter { it.enabled }.map { it.packageName }
        act.act("perf.saveKeepAlive", mapOf<String, Any?>("packages" to selected))
        onClose()
    }
}
