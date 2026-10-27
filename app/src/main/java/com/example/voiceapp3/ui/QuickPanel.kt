package com.example.voiceapp3.ui

import android.app.Activity
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.voiceapp3.R
import com.example.voiceapp3.nav.NavTheme
import com.google.gson.JsonObject
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Нативная вкладка «Быстрый доступ» (замена HTML-панели `quick` в index.html).
 *
 * Статика (заголовок, подписи секций, контейнеры) — в `view_quick_panel.xml`.
 * Здесь строятся кнопки: режимы, рекуперация, обдув с рециркуляцией, обогрев
 * и сиденья. Вид повторяет CSS прежней панели; палитра — из [NavTheme] плюс
 * `--sel`/`--onsel` по теме (в NavTheme их нет, значения взяты из CSS шелла).
 *
 * Логики здесь нет: каждое нажатие шлёт то же событие с той же нагрузкой, что
 * делал делегированный обработчик JS (drive.mode, drive.recup, climate.air,
 * climate.recirc, climate.heat, climate.seat), и показывает тот же тост, что
 * `say()` в JS. Состояние выбора локально и при каждом открытии вкладки
 * сбрасывается к значениям по умолчанию JS: Комфорт, Средняя, Лицо.
 *
 * Геометрию накрытия задаёт JS (`quick.frame`) по прямоугольнику #content.
 */
class QuickPanel(
    private val activity: Activity,
    /** Отправка события в JS-мост, как у JS-вызова GeelyNative.onEvent. */
    private val send: (name: String, json: String) -> Unit,
) {
    private val view: ScrollView = activity.findViewById(R.id.quickPanel)
    private val modesBox: LinearLayout = activity.findViewById(R.id.quickModes)
    private val recupBox: LinearLayout = activity.findViewById(R.id.quickRecup)
    private val airBox: LinearLayout = activity.findViewById(R.id.quickAir)
    private val heatBox: LinearLayout = activity.findViewById(R.id.quickHeat)
    private val title: TextView = activity.findViewById(R.id.quickTitle)
    private val subtitle: TextView = activity.findViewById(R.id.quickSubtitle)
    private val sections: List<TextView> = listOf(
        R.id.quickSectMode, R.id.quickSectRecup, R.id.quickSectAir, R.id.quickSectHeat,
    ).map { activity.findViewById(it) }

    private val density = activity.resources.displayMetrics.density

    /** События уходят в фоне, по порядку — как вызовы из JavaBridge-потока WebView. */
    private val out = Executors.newSingleThreadExecutor { r ->
        Thread(r, "quick-bridge").apply { isDaemon = true }
    }

    private var palette: NavTheme = NavTheme.byId(null)
    private var selBg: Int = SEL_SEPIA.first
    private var selFg: Int = SEL_SEPIA.second
    private var visible = false

    // Локальное состояние выбора (по умолчанию — как у JS-панели).
    private var modeSel = "Комфорт"
    private var recupSel = "Средняя"
    private var airSel = "Лицо"
    private var recircOn = false
    private val heatOn = HashMap<String, Boolean>()
    private val seatLevel = HashMap<String, Int>()

    init {
        view.visibility = View.GONE
        applyPalette()
    }

    /** Тема шелла (ui.theme): палитра и перерисовка видимой панели. */
    fun setTheme(id: String?) {
        palette = NavTheme.byId(id)
        val pair = SEL_BY_THEME[palette.id] ?: SEL_SEPIA
        selBg = pair.first
        selFg = pair.second
        applyPalette()
        if (visible) rebuild()
    }

    /**
     * Геометрия из JS (`quick.frame`): активна ли вкладка и прямоугольник
     * #content в физических пикселях. [fresh] — JS только что отрисовал
     * вкладку заново, значит состояние выбора сбрасывается, как при render().
     */
    fun onFrame(active: Boolean, x: Float, y: Float, w: Float, h: Float, fresh: Boolean) {
        if (!active) {
            hide()
            return
        }
        if (!visible) show()
        else if (fresh) {
            resetState()
            rebuild()
        }
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
        resetState()
        rebuild()
        view.visibility = View.VISIBLE
        view.scrollTo(0, 0)
        view.bringToFront()
    }

    private fun hide() {
        if (!visible) return
        visible = false
        view.visibility = View.GONE
    }

    private fun resetState() {
        modeSel = "Комфорт"
        recupSel = "Средняя"
        airSel = "Лицо"
        recircOn = false
        heatOn.clear()
        seatLevel.clear()
    }

    private fun rebuild() {
        buildModes()
        buildRecup()
        buildAir()
        buildHeat()
    }

    private fun applyPalette() {
        val p = palette
        view.background = rounded(p.card, CARD_RADIUS_DP)
        title.setTextColor(p.ink)
        subtitle.setTextColor(p.muted)
        sections.forEach { it.setTextColor(p.muted) }
    }

    // ── Режим движения: 3 кнопки в ряд ──────────────────────────────────────

    private fun buildModes() {
        modesBox.removeAllViews()
        val cells = listOf(
            Triple("Эко", "🌱", "max запас"),
            Triple("Комфорт", "☁", "баланс"),
            Triple("Спорт", "⚡", "динамика"),
        ).map { (value, icon, sub) -> modeCell(value, icon, sub) }
        addGrid(modesBox, cells, cols = 3)
    }

    private fun modeCell(value: String, icon: String, sub: String): View {
        val p = palette
        val on = value == modeSel
        val fg = if (on) p.onbtn else p.ink
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4f), dp(12f), dp(4f), dp(12f))
            background = rounded(if (on) p.btn else p.card2, 15f)
            setOnClickListener {
                modeSel = value
                buildModes()
                toast("Режим: $value")
                dispatch("drive.mode", JsonObject().apply { addProperty("mode", value) })
            }
        }
        box.addView(
            text(icon, 23f, fg, bold = false),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(5f) },
        )
        box.addView(text(value, 13.5f, fg, bold = true))
        box.addView(
            text(sub, 11f, if (on) NavTheme.alphaOf(p.onbtn, 0xB3) else p.muted, bold = false),
        )
        return box
    }

    // ── Рекуперация: сегментированный переключатель ─────────────────────────

    private fun buildRecup() {
        recupBox.removeAllViews()
        recupBox.background = rounded(palette.card2, 13f)
        recupBox.setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        listOf("Низкая", "Средняя", "Высокая").forEachIndexed { i, value ->
            val on = value == recupSel
            val tv = TextView(activity).apply {
                text = value
                textSize = 13.5f
                gravity = Gravity.CENTER
                isSingleLine = true
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(2f), dp(10f), dp(2f), dp(10f))
                setTextColor(if (on) selFg else palette.muted)
                background = if (on) rounded(selBg, 9f) else null
                if (on) elevation = dp(2f).toFloat()
                setOnClickListener {
                    recupSel = value
                    buildRecup()
                    toast(value)
                    dispatch("drive.recup", JsonObject().apply { addProperty("value", value) })
                }
            }
            recupBox.addView(
                tv,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(4f)
                },
            )
        }
    }

    // ── Обдув: 5 направлений + рециркуляция ─────────────────────────────────

    private fun buildAir() {
        airBox.removeAllViews()
        val cells = AIR_ITEMS.map { (value, label, icon) ->
            val on = value == airSel
            tile(
                label = label,
                icon = icon,
                fg = if (on) palette.onbtn else palette.ink,
                bg = if (on) palette.btn else palette.card2,
            ) {
                airSel = value
                buildAir()
                toast("Обдув: $value")
                dispatch("climate.air", JsonObject().apply {
                    addProperty("value", value)
                    addProperty("on", true)
                })
            }
        } + listOf(
            tile(
                label = "Рециркуляция",
                icon = R.drawable.ic_quick_recirc,
                fg = if (recircOn) palette.onbtn else palette.ink,
                bg = if (recircOn) palette.btn else palette.card2,
            ) {
                recircOn = !recircOn
                buildAir()
                toast("Рециркуляция " + if (recircOn) "вкл" else "выкл")
                dispatch("climate.recirc", JsonObject().apply {
                    addProperty("value", "Рециркуляция")
                    addProperty("on", recircOn)
                })
            },
        )
        addGrid(airBox, cells, cols = 3)
    }

    // ── Обогрев: 4 обогревателя + 2 сиденья (одна сетка, как в JS) ──────────

    private fun buildHeat() {
        heatBox.removeAllViews()
        val cells = HEAT_ITEMS.map { (name, label, icon) ->
            val on = heatOn[name] == true
            tile(
                label = label,
                icon = icon,
                fg = if (on) WHITE else palette.ink,
                bg = if (on) HOT else palette.card2,
            ) {
                val now = !(heatOn[name] == true)
                heatOn[name] = now
                buildHeat()
                toast(name + if (now) " · вкл" else " · выкл")
                dispatch("climate.heat", JsonObject().apply {
                    addProperty("name", name)
                    addProperty("on", now)
                })
            }
        } + SEAT_ITEMS.map { (name, label) ->
            val level = seatLevel[name] ?: 0
            val bars = seatBars(level)
            tile(
                label = label,
                icon = R.drawable.ic_quick_seat,
                fg = palette.ink,
                bg = palette.card2,
                strokeColor = if (level > 0) HOT else null,
                extra = bars,
            ) {
                val next = (level + 1) % 4
                seatLevel[name] = next
                buildHeat()
                toast("$name: уровень $next")
                dispatch("climate.seat", JsonObject().apply {
                    addProperty("name", name)
                    addProperty("level", next)
                })
            }
        }
        addGrid(heatBox, cells, cols = 3)
    }

    /** Полоски уровня сиденья: 3 сегмента 15×4 dp, заполненные — оранжевые. */
    private fun seatBars(level: Int): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until 3) {
            val seg = View(activity).apply {
                background = rounded(if (i < level) HOT else palette.line, 2f)
            }
            row.addView(
                seg,
                LinearLayout.LayoutParams(dp(15f), dp(4f)).apply {
                    if (i > 0) marginStart = dp(3f)
                },
            )
        }
        return row
    }

    // ── Общие построители ────────────────────────────────────────────────────

    /**
     * Плитка иконка + подпись (как `.air`, `.heat`, `.seat` в CSS): фон скруглён,
     * иконка 26 dp, подпись 12 sp bold. [extra] — полоски уровня под подписью.
     */
    private fun tile(
        label: String,
        icon: Int,
        fg: Int,
        bg: Int,
        strokeColor: Int? = null,
        extra: View? = null,
        onTap: () -> Unit,
    ): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4f), dp(11f), dp(4f), dp(10f))
            background = rounded(bg, 15f, strokeColor, 2f)
            setOnClickListener { onTap() }
        }
        val iv = ImageView(activity).apply {
            setImageResource(icon)
            setColorFilter(fg, PorterDuff.Mode.SRC_IN)
        }
        box.addView(iv, LinearLayout.LayoutParams(dp(26f), dp(26f)))
        box.addView(
            text(label, 12f, fg, bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6f) },
        )
        if (extra != null) {
            box.addView(
                extra,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(6f) },
            )
        }
        return box
    }

    /** Сетка с колонками равной ширины и зазором 8 dp, как CSS grid repeat(3, 1fr). */
    private fun addGrid(container: LinearLayout, cells: List<View>, cols: Int) {
        container.removeAllViews()
        val rows = cells.chunked(cols)
        rows.forEachIndexed { r, rowCells ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                // Пустая ячейка сохраняет ширину колонок последней неполной строки.
                val cell = rowCells.getOrNull(c) ?: View(activity)
                row.addView(
                    cell,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (c > 0) marginStart = dp(8f)
                    },
                )
            }
            container.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { if (r < rows.size - 1) bottomMargin = dp(8f) },
            )
        }
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean): TextView =
        TextView(activity).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null, strokeDp: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(color)
            if (strokeColor != null && strokeDp > 0f) setStroke(dp(strokeDp), strokeColor)
        }

    private fun dispatch(name: String, body: JsonObject) {
        val json = body.toString()
        out.execute { send(name, json) }
    }

    /** Тост как `say()` в JS: тот же текст, та же роль подтверждения действия. */
    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()

    companion object {
        const val CARD_RADIUS_DP = 22f

        /** Оранжевый акцент обогрева и сидений (#E86A33 в CSS). */
        val HOT: Int = 0xFFE86A33.toInt()
        val WHITE: Int = 0xFFFFFFFF.toInt()

        /** (--sel, --onsel) по data-th шелла; ключ — id из NavTheme. */
        val SEL_BY_THEME: Map<String, Pair<Int, Int>> = mapOf(
            "light" to Pair(0xFFFFFF.opaque(), 0x0D0D0D.opaque()),
            "dark" to Pair(0xD7E8E5.opaque(), 0x17313B.opaque()),
            "midnight" to Pair(0xE8E8E8.opaque(), 0x0A0A0A.opaque()),
            "sepia" to Pair(0xFFFFFF.opaque(), 0x231C0F.opaque()),
            "nordic" to Pair(0xD5DEEB.opaque(), 0x20242C.opaque()),
            "daylight" to Pair(0xFFFFFF.opaque(), 0x000000.opaque()),
            "tokyoNeon" to Pair(0xE9DDFF.opaque(), 0x150E24.opaque()),
            "sunsetDrive" to Pair(0xFFE0CF.opaque(), 0x2A1728.opaque()),
            "pearl" to Pair(0xFFFFFF.opaque(), 0x1A1A1A.opaque()),
        )
        val SEL_SEPIA: Pair<Int, Int> = SEL_BY_THEME.getValue("sepia")

        /** Направления обдува: (value — как в JS data-v, подпись, иконка). */
        val AIR_ITEMS: List<Triple<String, String, Int>> = listOf(
            Triple("Лицо", "Лицо", R.drawable.ic_quick_air_face),
            Triple("Ноги", "Ноги", R.drawable.ic_quick_air_legs),
            Triple("Лицо+Ноги", "Лицо+Ноги", R.drawable.ic_quick_air_face_legs),
            Triple("Стекло", "Стекло", R.drawable.ic_quick_air_glass),
            Triple("Ноги+Стекло", "Ноги+Стекло", R.drawable.ic_quick_air_legs_glass),
        )

        /** Обогреватели: (name — как в JS data-v, подпись, иконка). */
        val HEAT_ITEMS: List<Triple<String, String, Int>> = listOf(
            Triple("Руль", "Руль", R.drawable.ic_quick_heat_wheel),
            Triple("Лобовое стекло", "Лобовое", R.drawable.ic_quick_heat_windshield),
            Triple("Заднее стекло", "Заднее", R.drawable.ic_quick_heat_rear),
            Triple("Стекло Макс", "Стекло Макс", R.drawable.ic_quick_heat_glass_max),
        )

        /** Сиденья: (name — как в JS data-v, подпись). */
        val SEAT_ITEMS: List<Pair<String, String>> = listOf(
            Pair("Сиденье водителя", "Сиденье Вод."),
            Pair("Сиденье пассажира", "Сиденье Пасс."),
        )

        private fun Int.opaque(): Int = this or -0x1000000
    }
}
