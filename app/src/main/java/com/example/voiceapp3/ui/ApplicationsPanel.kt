package com.example.voiceapp3.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.voiceapp3.R
import com.example.voiceapp3.nav.NavTheme
import kotlin.math.roundToInt

/**
 * Нативная вкладка «Приложения» (замена HTML-панели `apps` в index.html).
 *
 * Поведение повторяет прежнюю панель (appsView / installApp в index.html):
 *  - вкладки «USB» и «Установленные (N)»;
 *  - строки приложений с кнопками «Установить» / «Открыть» и полоской прогресса;
 *  - плитки установленных приложений (3 в ряд);
 *  - тосты на «Открыть», клик по строке и плитке.
 *
 * Состояние (какие приложения установлены, активная вкладка) хранится здесь,
 * как раньше — в JS-глобалах панели. Событий в мост не отправляется: прежняя
 * панель их тоже не слала. Список приложений и «установка» — прежние демо-данные
 * и имитация прогресса (см. docs/ui-migration-audit.md, раздел 14).
 *
 * Статика — в `view_apps_panel.xml`. Панель накрывает прямоугольник #content
 * (content.frame) и скрыта на остальных вкладках.
 */
class ApplicationsPanel(private val activity: Activity) {

    private data class App(
        val name: String,
        val file: String,
        val version: String,
        val size: String,
        val emoji: String,
        val color: String,
    )

    private val view: ScrollView = activity.findViewById(R.id.appsPanel)
    private val tabsBox: LinearLayout = activity.findViewById(R.id.appsTabs)
    private val body: LinearLayout = activity.findViewById(R.id.appsBody)
    private val title: TextView = activity.findViewById(R.id.appsTitle)
    private val subtitle: TextView = activity.findViewById(R.id.appsSubtitle)
    private val usbCard: LinearLayout = activity.findViewById(R.id.appsUsbCard)
    private val usbIcon: TextView = activity.findViewById(R.id.appsUsbIcon)
    private val usbName: TextView = activity.findViewById(R.id.appsUsbName)
    private val usbInfo: TextView = activity.findViewById(R.id.appsUsbInfo)

    private val density = activity.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private var palette: NavTheme = NavTheme.byId(null)
    private var selBg: Int = QuickPanel.SEL_SEPIA.first
    private var selFg: Int = QuickPanel.SEL_SEPIA.second
    private var visible = false

    /** Активная вкладка: 0 — USB, 1 — установленные (как appTab в JS). */
    private var appTab = 0
    /** Установленные приложения; порядок добавления сохраняется (как Set в JS). */
    private val installed = linkedSetOf("Aptoide", "VK Видео")
    /** Приложения в процессе установки: имя → процент 0..100. */
    private val progress = HashMap<String, Int>()

    init {
        view.visibility = View.GONE
        applyPalette()
    }

    /** Тема шелла (ui.theme): палитра и перерисовка видимой панели. */
    fun setTheme(id: String?) {
        palette = NavTheme.byId(id)
        val pair = QuickPanel.SEL_BY_THEME[palette.id] ?: QuickPanel.SEL_SEPIA
        selBg = pair.first
        selFg = pair.second
        applyPalette()
        if (visible) rebuild()
    }

    /**
     * Геометрия из JS (`content.frame`): активна ли вкладка и прямоугольник
     * #content в физических пикселях. Состояние панели при [fresh] не сбрасывается:
     * в прежней панели appTab и список установленных были глобальными.
     */
    fun onFrame(active: Boolean, x: Float, y: Float, w: Float, h: Float, fresh: Boolean) {
        if (!active) {
            hide()
            return
        }
        if (!visible) show()
        else if (fresh) rebuild()
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

    private fun applyPalette() {
        val p = palette
        view.setBackgroundColor(p.page1)
        title.setTextColor(p.ink)
        subtitle.setTextColor(p.muted)
        usbCard.background = rounded(p.card2, 15f)
        usbIcon.background = rounded(p.btn, 13f)
        usbIcon.setTextColor(p.onbtn)
        usbName.setTextColor(p.ink)
        usbInfo.setTextColor(p.muted)
        if (visible) rebuild()
    }

    private fun rebuild() {
        tabsBox.removeAllViews()
        body.removeAllViews()
        buildTabs()
        if (appTab == 0) buildUsb() else buildInstalled()
    }

    // ── Сегментный переключатель ───────────────────────────────────────────

    private fun buildTabs() {
        val p = palette
        tabsBox.background = rounded(p.card2, 13f)
        tabsBox.setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        val labels = listOf("USB", "Установленные (${installed.size})")
        labels.forEachIndexed { i, label ->
            val sel = appTab == i
            val cell = text(label, 13.5f, if (sel) selFg else p.muted, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(2f), dp(10f), dp(2f), dp(10f))
                background = if (sel) rounded(selBg, 9f) else null
                setOnClickListener {
                    appTab = i
                    rebuild()
                }
            }
            tabsBox.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ── Вкладка «USB»: список приложений ──────────────────────────────────

    private fun buildUsb() {
        APPS.forEach { app ->
            val ins = installed.contains(app.name)
            val busy = progress.containsKey(app.name)

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10f), 0, dp(10f))
                setOnClickListener { toast("${app.name} · ${app.size} · v${app.version}") }
            }

            val top = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val icon = text(app.emoji, 22f, Color.WHITE, bold = false).apply {
                gravity = Gravity.CENTER
                background = rounded(Color.parseColor(app.color), 13f)
            }
            top.addView(icon, LinearLayout.LayoutParams(dp(44f), dp(44f)))

            val info = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), 0, dp(8f), 0)
            }
            info.addView(text(app.name, 14.5f, palette.ink, bold = true))
            info.addView(text("${app.file} · v${app.version} · ${app.size}", 12f, palette.muted, bold = false))
            top.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            if (ins) {
                top.addView(pill("Открыть", ghost = true) { toast("Запуск…") })
            } else {
                val label = if (busy) "···" else "Установить"
                top.addView(pill(label, ghost = false, enabled = !busy) { startInstall(app) })
            }
            row.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            if (busy) row.addView(progressBar(progress[app.name] ?: 0), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6f)).apply {
                topMargin = dp(8f)
            })

            body.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            // Нижняя линия строки (border-bottom в прежней панели).
            body.addView(View(activity).apply { setBackgroundColor(palette.line) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)))
        }
    }

    /** Имитация установки из прежней панели: +10% каждые 130 мс, затем — «установлено». */
    private fun startInstall(app: App) {
        if (progress.containsKey(app.name)) return
        progress[app.name] = 0
        rebuild()
        scheduleTick(app)
    }

    private fun scheduleTick(app: App) {
        handler.postDelayed({
            val next = (progress[app.name] ?: return@postDelayed) + 10
            if (next >= 100) {
                progress.remove(app.name)
                installed.add(app.name)
                toast("«${app.name}» установлено ✓")
            } else {
                progress[app.name] = next
                scheduleTick(app)
            }
            if (visible) rebuild()
        }, 130L)
    }

    // ── Вкладка «Установленные»: плитки ───────────────────────────────────

    private fun buildInstalled() {
        body.addView(text("Установлено · ${installed.size}", 11.5f, palette.muted, bold = true).apply {
            letterSpacing = 0.1f
            isAllCaps = true
            setPadding(0, dp(15f), 0, dp(8f))
        })

        val list = APPS.filter { installed.contains(it.name) }
        list.chunked(3).forEach { rowApps ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            rowApps.forEach { app -> row.addView(tile(app), tileParams()) }
            repeat(3 - rowApps.size) { row.addView(View(activity), tileParams()) }
            body.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun tile(app: App): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4f), dp(14f), dp(4f), dp(14f))
            background = rounded(palette.card2, 15f)
            setOnClickListener { toast("Запуск «${app.name}»…") }
        }
        val icon = text(app.emoji, 24f, Color.WHITE, bold = false).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.parseColor(app.color), 13f)
        }
        box.addView(icon, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        box.addView(text(app.name, 12f, palette.ink, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8f), 0, 0)
        })
        return box
    }

    private fun tileParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(4f), 0, dp(4f), 0)
        }

    // ── Общие элементы ────────────────────────────────────────────────────

    private fun pill(label: String, ghost: Boolean, enabled: Boolean = true, onClick: () -> Unit): TextView {
        val p = palette
        return text(label, 13.5f, if (ghost) p.ink else p.onbtn, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18f), dp(9f), dp(18f), dp(9f))
            background = if (ghost) rounded(Color.TRANSPARENT, 100f, strokeColor = p.btn, strokeDp = 1.5f)
            else rounded(p.btn, 100f)
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.6f
            setOnClickListener { if (enabled) onClick() }
        }
    }

    /** Полоска прогресса: заполненная часть — весом, остаток — пустой. */
    private fun progressBar(percent: Int): View {
        val track = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(palette.track, 100f)
            clipToOutline = true
        }
        val fill = View(activity).apply { background = progressGradient() }
        val rest = View(activity)
        val filled = percent.coerceIn(0, 100)
        track.addView(fill, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, filled.toFloat().coerceAtLeast(0.001f)))
        track.addView(rest, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (100 - filled).toFloat().coerceAtLeast(0.001f)))
        return track
    }

    private fun progressGradient(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(Color.parseColor("#1FB34E"), Color.parseColor("#2FD35F")),
    ).apply { cornerRadius = dp(100f).toFloat() }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean): TextView =
        TextView(activity).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null, strokeDp: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null && strokeDp > 0f) setStroke(dp(strokeDp), strokeColor)
        }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()

    private companion object {
        val APPS = listOf(
            App("Aptoide", "hd-kinopoisk.apk", "9.22.5.3", "31 МБ", "📦", "#F59E0B"),
            App("VK Видео", "VKVideo_Auto_1.70.1.apk", "1.70.1", "48 МБ", "📺", "#0077FF"),
            App("FreeTube", "freetube-2-5-0.apk", "2.5.0", "26 МБ", "▶️", "#E8533B"),
            App("Angry Birds", "com.rovio.angrybirds.apk", "8.0.3", "96 МБ", "🐦", "#EF4444"),
            App("Aurora Droid", "auroradroid-2.0.1.apk", "2.0.1", "12 МБ", "✦", "#0EA5E9"),
            App("Sunray Toolkit", "sunray-latest.apk", "1.3", "8 МБ", "🧰", "#8B5CF6"),
        )
    }
}
