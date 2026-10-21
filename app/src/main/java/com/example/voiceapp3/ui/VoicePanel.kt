package com.example.voiceapp3.ui

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.example.voiceapp3.R
import com.example.voiceapp3.nav.NavTheme
import com.google.gson.JsonObject

/**
 * Нативная вкладка «Голос» (замена HTML-панели voice в index.html).
 *
 * Статика — в `view_voice_panel.xml`. Здесь: микрофон с кольцами пульса и
 * эквалайзером, статус, карточка ответа, переключатель, кнопки и каталог
 * команд по разделам (аккордеон, фразы — [FlowLayout]).
 *
 * Состояние голоса приходит от VoiceAssistantService через
 * [UiBridge.voiceUi] → [onVoiceUi]; правила те же, что у `native:voice.ui`
 * в JS (listen / status / result / idle). Кнопки микрофона и вызова шлют
 * `voice.request`, перезапуск — `voice.micRestart`, как прежний делегат JS.
 *
 * Тап по фразе отправляет её в VoiceAssistantService (voice.phrase → HUB_PHRASE):
 * фраза выполняется реально, ответ и карточка результата приходят через voiceUi.
 */
class VoicePanel(
    private val activity: Activity,
    /** Отправка события в JS-мост, как у вызова GeelyNative.onEvent. */
    private val send: (name: String, json: String) -> Unit,
) {
    private val view: ScrollView = activity.findViewById(R.id.voicePanel)
    private val title: TextView = activity.findViewById(R.id.voiceTitle)
    private val subtitle: TextView = activity.findViewById(R.id.voiceSubtitle)
    private val hero: LinearLayout = activity.findViewById(R.id.voiceHero)
    private val ring1: View = activity.findViewById(R.id.voiceRing1)
    private val ring2: View = activity.findViewById(R.id.voiceRing2)
    private val mic: TextView = activity.findViewById(R.id.voiceMic)
    private val state: TextView = activity.findViewById(R.id.voiceState)
    private val wake: TextView = activity.findViewById(R.id.voiceWake)
    private val eq: LinearLayout = activity.findViewById(R.id.voiceEq)
    private val eqBars: List<View> = listOf(
        R.id.voiceEq1, R.id.voiceEq2, R.id.voiceEq3, R.id.voiceEq4, R.id.voiceEq5,
    ).map { activity.findViewById(it) }
    private val resp: LinearLayout = activity.findViewById(R.id.voiceResp)
    private val respTitle: TextView = activity.findViewById(R.id.voiceRespTitle)
    private val respReply: TextView = activity.findViewById(R.id.voiceRespReply)
    private val rowTitle: TextView = activity.findViewById(R.id.voiceRowTitle)
    private val rowSub: TextView = activity.findViewById(R.id.voiceRowSub)
    private val rowDivider: View = activity.findViewById(R.id.voiceRowDivider)
    private val switch: Switch = activity.findViewById(R.id.voiceSwitch)
    private val call: TextView = activity.findViewById(R.id.voiceCall)
    private val restart: TextView = activity.findViewById(R.id.voiceRestart)
    private val sectCatalog: TextView = activity.findViewById(R.id.voiceSectCatalog)
    private val catalog: LinearLayout = activity.findViewById(R.id.voiceCatalog)

    private val density = activity.resources.displayMetrics.density

    /** Анимации пульса (кольца) и эквалайзера; запускаются только при «слушаю». */
    private val animators = ArrayList<Animator>()

    private var palette: NavTheme = NavTheme.byId(null)
    private var track: Int = TRACK_BY_THEME.getValue("sepia")
    private var visible = false
    private var live = false

    /** Как глобальный openSec в JS: переживает перерисовки вкладки. */
    private var openSec = -1

    private val switchListener = CompoundButton.OnCheckedChangeListener { _, on ->
        toast("Активация голосом" + if (on) " · вкл" else " · выкл")
    }

    init {
        view.visibility = View.GONE
        switch.setOnCheckedChangeListener(switchListener)
        mic.setOnClickListener { send("voice.request", "{}") }
        call.setOnClickListener { send("voice.request", "{}") }
        restart.setOnClickListener {
            toast("Микрофон перезапущен ✓")
            send("voice.micRestart", "{}")
        }
        animators += pulseAnimator(ring1, 0L)
        animators += pulseAnimator(ring2, 800L)
        eqBars.forEachIndexed { i, bar ->
            bar.post { bar.pivotY = bar.height.toFloat() }
            animators += eqAnimator(bar, i * 150L)
        }
        applyPalette()
    }

    /** Тема шелла (ui.theme): палитра и перерисовка видимого каталога. */
    fun setTheme(id: String?) {
        palette = NavTheme.byId(id)
        track = TRACK_BY_THEME[palette.id] ?: TRACK_BY_THEME.getValue("sepia")
        applyPalette()
        if (visible) buildCatalog()
    }

    /**
     * Геометрия из JS (`content.frame`, вкладка «voice»). [fresh] — вкладка
     * перерисована заново: состояние голоса сбрасывается, как при render().
     */
    fun onFrame(active: Boolean, x: Float, y: Float, w: Float, h: Float, fresh: Boolean) {
        if (!active) {
            hide()
            return
        }
        if (!visible) show()
        else if (fresh) {
            resetState()
            buildCatalog()
        }
        if (w >= 1f && h >= 1f) {
            val lp = view.layoutParams as? android.widget.FrameLayout.LayoutParams
                ?: android.widget.FrameLayout.LayoutParams(0, 0)
            lp.width = w.toInt()
            lp.height = h.toInt()
            lp.leftMargin = x.toInt()
            lp.topMargin = y.toInt()
            view.layoutParams = lp
        }
    }

    /**
     * Сервисное событие голоса (voice.ui). Вызывается из UiBridge.voiceUi на
     * UI-потоке. Вне вкладки ничего не делает — как `if(!e.mb)return` в JS.
     */
    fun onVoiceUi(phase: String, text: String?, phrase: String?, reply: String?) {
        if (!visible) return
        when (phase) {
            "listen" -> {
                setLive(true)
                state.text = "Слушаю…"
                hideResp()
            }
            "status" -> if (!text.isNullOrEmpty()) {
                var t = text
                if (t.isBlank() || t == "Привет" || t == "Слушаю..." || t == "Слушаю…") t = "Слушаю…"
                state.text = t
                if (!live) setLive(true)
            }
            "result" -> {
                setLive(false)
                state.text = "Жду обращения"
                if (!phrase.isNullOrEmpty()) showResp(phrase, reply.orEmpty())
            }
            "idle" -> {
                setLive(false)
                state.text = "Жду обращения"
            }
        }
    }

    private fun show() {
        visible = true
        resetState()
        buildCatalog()
        view.visibility = View.VISIBLE
        view.scrollTo(0, 0)
        view.bringToFront()
    }

    private fun hide() {
        if (!visible) return
        visible = false
        setLive(false)
        view.visibility = View.GONE
    }

    private fun resetState() {
        setLive(false)
        state.text = "Жду обращения"
        hideResp()
        // Как render() в JS: переключатель снова включён, без лишнего тоста.
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = true
        switch.setOnCheckedChangeListener(switchListener)
    }

    /** Состояние «слушаю»: оранжевый микрофон, кольца, эквалайзер. */
    private fun setLive(on: Boolean) {
        live = on
        mic.background = circle(if (on) HOT else palette.btn)
        mic.setTextColor(palette.onbtn)
        eq.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            animators.filter { !it.isRunning }.forEach { it.start() }
        } else {
            animators.forEach { it.cancel() }
            ring1.alpha = 0f; ring1.scaleX = 1f; ring1.scaleY = 1f
            ring2.alpha = 0f; ring2.scaleX = 1f; ring2.scaleY = 1f
        }
    }

    private fun showResp(phrase: String, reply: String) {
        respTitle.text = "«$phrase»"
        respReply.text = reply
        resp.visibility = View.VISIBLE
    }

    private fun hideResp() {
        resp.visibility = View.GONE
    }

    private fun applyPalette() {
        val p = palette
        view.background = rounded(p.card, CARD_RADIUS_DP)
        title.setTextColor(p.ink)
        subtitle.setTextColor(p.muted)
        hero.background = rounded(p.card2, 18f)
        state.setTextColor(p.ink)
        wake.setTextColor(p.muted)
        wake.background = rounded(p.card, 100f, p.line, 1f)
        resp.background = rounded(p.card, 14f, p.line, 1f)
        respTitle.setTextColor(p.ink)
        respReply.setTextColor(GREEN_RESP)
        rowTitle.setTextColor(p.ink)
        rowSub.setTextColor(p.muted)
        rowDivider.setBackgroundColor(p.line)
        switch.thumbTintList = ColorStateList.valueOf(WHITE)
        switch.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(GREEN_SWITCH, track),
        )
        call.background = rounded(p.btn, 100f)
        call.setTextColor(p.onbtn)
        restart.background = rounded(p.card2, 100f)
        restart.setTextColor(p.ink)
        sectCatalog.setTextColor(p.muted)
        setLive(live)
    }

    // ── Каталог команд: аккордеон по разделам ───────────────────────────────

    private fun buildCatalog() {
        catalog.removeAllViews()
        val p = palette
        VoiceCommandCatalog.SECTIONS.forEachIndexed { i, s ->
            val open = openSec == i
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                background = rounded(p.card2, 15f)
                setOnClickListener {
                    val scroll = view.scrollY
                    openSec = if (openSec == i) -1 else i
                    buildCatalog()
                    view.scrollTo(0, scroll)
                }
            }
            val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(s.title, 14f, p.ink, bold = true))
            texts.addView(
                text(s.hint, 12f, p.muted, bold = false),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(1f) },
            )
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val count = text(s.phrases.size.toString(), 12f, p.muted, bold = true).apply {
                background = rounded(p.card, 100f, p.line, 1f)
                setPadding(dp(10f), dp(3f), dp(10f), dp(3f))
            }
            row.addView(count, marginStart(dp(10f)))

            val arrow = text("›", 14f, if (open) HOT else p.line, bold = true).apply {
                rotation = if (open) 90f else 0f
            }
            row.addView(arrow, marginStart(dp(10f)))

            catalog.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8f) },
            )

            if (open) {
                val flow = FlowLayout(activity).apply {
                    spacingPx = dp(8f)
                    setPadding(dp(2f), dp(10f), dp(2f), dp(4f))
                }
                s.phrases.forEach { phrase ->
                    flow.addView(
                        text(phrase, 13f, p.ink, bold = true).apply {
                            background = rounded(p.card2, 100f)
                            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
                            setOnClickListener {
                                view.scrollTo(0, 0)
                                send("voice.phrase", JsonObject().apply { addProperty("text", phrase) }.toString())
                            }
                        },
                    )
                }
                catalog.addView(
                    flow,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
        }
    }

    // ── Анимации ─────────────────────────────────────────────────────────────

    /**
     * Кольцо пульса: scale 0.85 → 1.25 и прозрачность 0.9 → 0 за 1.6 с (как CSS ring).
     * [delayMs] — задержка первого цикла, как animation-delay в CSS.
     */
    private fun pulseAnimator(ring: View, delayMs: Long): Animator {
        val set = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(ring, View.SCALE_X, 0.85f, 1.25f),
                ObjectAnimator.ofFloat(ring, View.SCALE_Y, 0.85f, 1.25f),
                ObjectAnimator.ofFloat(ring, View.ALPHA, 0.9f, 0f),
            )
            duration = 1600L
            interpolator = DecelerateInterpolator()
            startDelay = delayMs
        }
        set.childAnimations.forEach {
            (it as ObjectAnimator).apply {
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART
            }
        }
        return set
    }

    /** Полоска эквалайзера: scaleY 0.3 ↔ 1, полный цикл 0.8 с, задержка по индексу. */
    private fun eqAnimator(bar: View, delayMs: Long): Animator =
        ObjectAnimator.ofFloat(bar, View.SCALE_Y, 0.3f, 1f).apply {
            duration = 400L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = delayMs
        }

    // ── Общие построители ────────────────────────────────────────────────────

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean): TextView =
        TextView(activity).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun marginStart(px: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = px }

    private fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun rounded(
        color: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeDp: Float = 0f,
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(color)
            if (strokeColor != null && strokeDp > 0f) setStroke(dp(strokeDp), strokeColor)
        }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()

    private companion object {
        const val CARD_RADIUS_DP = 22f

        /** Оранжевый акцент «слушаю» (#E86A33 в CSS). */
        val HOT: Int = 0xFFE86A33.toInt()
        val WHITE: Int = 0xFFFFFFFF.toInt()

        /** Зелёный переключателя (#34C759) и текст ответа (#1FB34E) из CSS. */
        val GREEN_SWITCH: Int = 0xFF34C759.toInt()
        val GREEN_RESP: Int = 0xFF1FB34E.toInt()

        /** --track по data-th шелла; ключ — id из NavTheme. */
        val TRACK_BY_THEME: Map<String, Int> = mapOf(
            "light" to 0xFFE3E3E1.toInt(),
            "dark" to 0xFF3A5964.toInt(),
            "midnight" to 0xFF2A2A2A.toInt(),
            "sepia" to 0xFFE4DACA.toInt(),
            "nordic" to 0xFF3A414D.toInt(),
            "daylight" to 0xFFD8D8D8.toInt(),
            "tokyoNeon" to 0xFF251A3C.toInt(),
            "sunsetDrive" to 0xFF3E2238.toInt(),
            "bubblegum" to 0xFFF3DCEE.toInt(),
        )
    }
}
