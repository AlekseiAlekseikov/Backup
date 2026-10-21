package com.example.voiceapp3.ui

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.content.Context
import android.content.res.ColorStateList
import androidx.appcompat.widget.SwitchCompat
import com.example.voiceapp3.AppUpdater
import com.example.voiceapp3.R
import com.example.voiceapp3.nav.NavTheme
import com.google.gson.JsonParser
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Нативная вкладка «Обновления» (этап 8).
 *
 * Проверка, скачивание и установка — AppUpdater через UiBridge (те же события
 * update.check / update.install, что и у прежней HTML-вкладки). Состояние
 * приходит снимком update.state (UiBridge.pushUpdateState) и рисуется здесь.
 * Переключатель «Автообновление» прежнего макета не имел логики и не перенесён.
 */
class UpdatesPanel(
    private val activity: Activity,
    private val emit: (String, String) -> Unit,
) {
    private val density = activity.resources.displayMetrics.density
    private var palette: NavTheme = NavTheme.byId(null)
    private var visible = false

    /** Фаза: idle / checking / available / uptodate / downloading / installing / error. */
    private var phase = "idle"
    private var version: String? = null
    private var notes = ""
    private var percent = 0
    private var message: String? = null
    /** Тумблер «Автообновление» (настройка, общая с UiBridge). */
    private var autoEnabled = false

    private val view: ScrollView = activity.findViewById(R.id.updatesPanel)
    private val title: TextView = activity.findViewById(R.id.updatesTitle)
    private val subtitle: TextView = activity.findViewById(R.id.updatesSubtitle)
    private val body: LinearLayout = activity.findViewById(R.id.updatesBody)

    private data class Head(val icon: String, val iconBg: Int, val title: String, val sub: String, val subColor: Int)

    init {
        view.visibility = View.GONE
        autoEnabled = activity.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
            .getBoolean(PREF_UPDATE_AUTO, false)
        applyPalette()
    }

    /** Состояние тумблера пришло из моста (update.auto). */
    fun setAutoUpdateChecked(enabled: Boolean) {
        if (autoEnabled == enabled) return
        autoEnabled = enabled
        if (visible) rebuild()
    }

    /** Тема шелла (ui.theme): палитра и перерисовка видимой панели. */
    fun setTheme(id: String?) {
        palette = NavTheme.byId(id)
        applyPalette()
        if (visible) rebuild()
    }

    /** Снимок update.state из UiBridge (JSON-объект с полем phase и опциональными полями). */
    fun onUpdateState(json: String) {
        val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return
        fun str(key: String): String? = obj.get(key)?.takeIf { !it.isJsonNull }?.asString
        str("phase")?.let { phase = it }
        str("version")?.let { version = it }
        str("changelog")?.let { notes = it }
        obj.get("percent")?.takeIf { !it.isJsonNull }?.let { percent = it.asInt }
        str("message")?.let { message = it }
        if (phase == "available") percent = 0
        if (visible) rebuild()
    }

    /** Геометрия из JS (`content.frame`), физические пиксели. */
    fun onFrame(active: Boolean, x: Float, y: Float, w: Float, h: Float, fresh: Boolean) {
        if (!active) {
            hide()
            return
        }
        if (!visible) show() else if (fresh) rebuild()
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
        // Как в прежней вкладке: при открытии проверяем, если ещё не проверяли или можно повторить.
        if (phase == "idle" || phase == "uptodate" || phase == "error") {
            check()
        } else {
            rebuild()
        }
    }

    private fun hide() {
        if (!visible) return
        visible = false
        view.visibility = View.GONE
    }

    private fun check() {
        phase = "checking"
        rebuild()
        emit("update.check", "{}")
    }

    private fun install() {
        phase = "downloading"
        percent = 0
        message = "Скачиваю ${version ?: ""}…"
        rebuild()
        emit("update.install", "{}")
    }

    private fun applyPalette() {
        view.setBackgroundColor(palette.page1)
        title.setTextColor(palette.ink)
        subtitle.setTextColor(palette.muted)
    }

    private fun rebuild() {
        body.removeAllViews()
        body.addView(headCard())
        body.addView(zone())
        body.addView(autoRow())
    }

    /** Тумблер «Автообновление»: вкл. — проверка и установка без нажатия. */
    private fun autoRow(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = matchWrap(12)
        }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(label("Автообновление", 14f, palette.ink, bold = true))
        col.addView(label("Сама проверяет новые версии и ставит их", 12f, palette.muted, bold = false))
        val sw = SwitchCompat(activity).apply {
            isChecked = autoEnabled
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(palette.btn, palette.muted))
            trackTintList = ColorStateList(states, intArrayOf(palette.btn, palette.track))
            setOnCheckedChangeListener { _, checked ->
                if (checked != autoEnabled) {
                    autoEnabled = checked
                    emit("update.auto", JSONObject().put("enabled", checked).toString())
                }
            }
        }
        row.addView(col)
        row.addView(sw)
        return row
    }

    // ── карточка версии ───────────────────────────────────────────────────

    private fun headCard(): View {
        val v = AppUpdater.currentVersionName(activity)
        val head = when (phase) {
            "available" -> Head("↓", 0xFFE86A33.toInt(), "EX2 · v${version ?: ""} доступно",
                "GitHub · готово к установке", 0xFFE86A33.toInt())
            "installing" -> Head("⭮", 0xFF1FB34E.toInt(), "Устанавливаю v${version ?: v}",
                "Не выключайте автомобиль", palette.muted)
            "uptodate" -> Head("✓", 0xFF1FB34E.toInt(), "EX2 · v$v",
                "У вас последняя версия", 0xFF1FB34E.toInt())
            else -> Head("↓", palette.btn, "EX2 · v$v",
                "Обновления — GitHub Releases", palette.muted)
        }
        return versionCard(head.icon, head.iconBg, head.title, head.sub, head.subColor)
    }

    private fun versionCard(icon: String, iconBg: Int, titleText: String, sub: String, subColor: Int): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(palette.card2, 15f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = matchWrap(0)
        }
        val iconBox = TextView(activity).apply {
            text = icon
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(if (iconBg == palette.btn) palette.onbtn else 0xFFFFFFFF.toInt())
            background = rounded(iconBg, 13f)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(13)
            }
        }
        col.addView(label(titleText, 15f, palette.ink, bold = true))
        col.addView(label(sub, 12f, subColor, bold = subColor != palette.muted))
        row.addView(iconBox)
        row.addView(col)
        return row
    }

    // ── зона действий по фазе ──────────────────────────────────────────────

    private fun zone(): View {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap(0)
        }
        when (phase) {
            "checking" -> col.addView(button("Проверяю…", enabled = false) {})
            "available" -> {
                notesBlock()?.let { col.addView(it) }
                col.addView(kv("Текущая версия", "v${AppUpdater.currentVersionName(activity)}"))
                col.addView(kv("Новая версия", "v${version ?: ""}"))
                col.addView(button("Установить сейчас") { install() })
            }
            "downloading" -> col.addView(progressBlock(
                title = "${percent.coerceIn(0, 99)}%",
                pct = percent.coerceIn(0, 99),
                msg = message ?: "Скачивание…",
                hint = "Связь может обрываться — скачивание продолжится с места обрыва",
            ))
            "installing" -> col.addView(progressBlock(
                title = "Установка",
                pct = 100,
                msg = message ?: "Устанавливаю… приложение перезапустится",
                hint = "Не выключайте автомобиль во время установки",
            ))
            "error" -> {
                col.addView(versionCard("!", 0xFFE8533B.toInt(), "Не получилось", message ?: "Ошибка обновления",
                    palette.muted))
                col.addView(button("Повторить") { check() })
            }
            else -> {
                col.addView(kv("Источник", "GitHub · AlekseiAlekseikov/GeelyTools"))
                col.addView(button("Проверить обновление") { check() })
            }
        }
        return col
    }

    /** «Что нового» — строки changelog, как в прежней вкладке (до 10 строк). */
    private fun notesBlock(): View? {
        val lines = notes.split(Regex("\r?\n"))
            .map { it.replace(Regex("^#+\\s*"), "").replace(Regex("^[-*]\\s+"), "").trim() }
            .filter {
                it.isNotEmpty() && !Regex("^\\[?tag", RegexOption.IGNORE_CASE).containsMatchIn(it) &&
                    it.lowercase() != "что нового" && it.lowercase() != "что нового:"
            }
            .take(10)
        if (lines.isEmpty()) return null
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.card2, 15f)
            setPadding(dp(16), dp(4), dp(16), dp(4))
            layoutParams = matchWrap(4)
        }
        lines.forEach { box.addView(label(it, 13.5f, palette.ink, bold = true).apply { setPadding(0, dp(9), 0, dp(9)) }) }
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap(0)
        }
        wrap.addView(label("Что нового".uppercase(), 11.5f, palette.muted, bold = true))
        wrap.addView(box)
        return wrap
    }

    private fun progressBlock(title: String, pct: Int, msg: String, hint: String): View {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap(12)
        }
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        top.addView(label(title, 26f, palette.ink, bold = true))
        top.addView(label(msg, 13f, palette.muted, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        })
        col.addView(top)

        val track = FrameLayout(activity).apply {
            background = rounded(palette.track, 100f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12)).apply {
                topMargin = dp(8)
            }
        }
        val fillRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 100f
        }
        val fill = View(activity).apply {
            background = rounded(palette.btn, 100f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, pct.toFloat())
        }
        val rest = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (100 - pct).toFloat())
        }
        fillRow.addView(fill)
        fillRow.addView(rest)
        track.addView(fillRow)
        col.addView(track)

        col.addView(label(hint, 12f, palette.muted, bold = false).apply {
            layoutParams = matchWrap(8)
        })
        return col
    }

    // ── мелкие элементы ───────────────────────────────────────────────────

    private fun kv(k: String, v: String): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            layoutParams = matchWrap(0)
        }
        row.addView(label(k, 14f, palette.muted, bold = false).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(label(v, 14f, palette.ink, bold = true))
        return row
    }

    private fun button(label: String, enabled: Boolean = true, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(palette.onbtn)
            background = rounded(palette.btn, 100f)
            setPadding(dp(24), dp(11), dp(24), dp(11))
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.55f
            if (enabled) setOnClickListener { onClick() }
            layoutParams = matchWrap(12)
        }

    private fun label(s: String, sp: Float, color: Int, bold: Boolean): TextView =
        TextView(activity).apply {
            text = s
            textSize = sp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun matchWrap(topDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(topDp) }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        cornerRadius = radiusDp * density
        setColor(color)
    }

    private fun dp(v: Int): Int = (v * density).roundToInt()
}
