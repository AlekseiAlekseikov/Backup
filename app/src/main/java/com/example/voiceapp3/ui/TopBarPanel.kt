package com.example.voiceapp3.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.voiceapp3.BuildConfig
import com.example.voiceapp3.R
import com.example.voiceapp3.nav.NavTheme
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.timhss.capyenergy.profile.GeelyProperties
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Нативная верхняя строка шелла (замена HTML `.topbar`, ui/index.html).
 *
 * Содержимое: «Geely EX2 [P] · Припаркован», температура (статично, как в макете),
 * часы (обновляются раз в 10 с), пилюля «Подключено к машине · EX2 v<версия>».
 * Цвета — из выбранной темы (ui.theme), сама строка прозрачна.
 */
class TopBarPanel(private val activity: Activity) {

    private val root: FrameLayout = activity.findViewById(R.id.topBar)
    private val density = activity.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private var palette: NavTheme = NavTheme.byId(null)

    private val titleText = text("Geely EX2", 19f, bold = true)
    private val badge = text("—", 12f, bold = true)
    private val parkedText = text("Нет данных", 13f)

    /** Последняя передача из живого кадра (держится, пока не придёт новая). */
    private var gearRaw: Int? = null
    private val tempText = text("+12° 🌤", 14f, bold = true)
    private val clockText = text("--:--", 19f, bold = true)
    private val connPill = LinearLayout(activity)
    private val connDot = View(activity)
    private val connText = text("Подключено к машине", 12.5f, bold = true)
    private val connVersion = text("· EX2 v${BuildConfig.VERSION_NAME}", 12.5f)

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 10_000L)
        }
    }

    init {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(8))
        }

        // Левая часть: заголовок, бейдж передачи, статус парковки.
        val left = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        left.addView(titleText, wrap(endMargin = dp(10)))
        left.addView(badge.apply { setPadding(dp(10), dp(3), dp(10), dp(3)) }, wrap(endMargin = dp(10)))
        left.addView(parkedText, wrap())
        row.addView(left, wrap())

        // Правая часть: температура, часы, пилюля подключения (отступ слева — auto).
        val right = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        right.addView(tempText, wrap(endMargin = dp(12)))
        right.addView(clockText, wrap(endMargin = dp(12)))

        connPill.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        connPill.addView(connDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(7) })
        connPill.addView(connText, wrap())
        connPill.addView(connVersion, wrap(startMargin = dp(4)))
        right.addView(connPill, wrap())

        // Правая группа прижата к правому краю: занимает остаток строки.
        right.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
        })

        root.addView(
            row,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        updateClock()
        handler.postDelayed(clockTick, 10_000L)
        applyPalette()
    }

    /** Смена темы из ui.theme. */
    fun setTheme(id: String?) {
        palette = NavTheme.byId(id)
        applyPalette()
    }

    /**
     * Живой кадр (`telemetry.live`): передача (GEAR, битовая маска как в capy).
     * Скорость показывает карточка авто. Пуш приходит из UiBridge.pushToUi на UI-потоке.
     */
    fun onTelemetry(name: String, json: String) {
        if (name != "telemetry.live") return
        val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return
        val list = obj.get("signals")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        for (el in list) {
            val s = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val quality = s.str("quality")?.uppercase()
            if (quality == "UNAVAILABLE" || quality == "ERROR") continue
            val value = s.num("value") ?: continue
            if (s.str("signalId") == "GEAR") gearRaw = value.toInt()
        }
        paint()
    }

    private fun paint() {
        val gear = gearRaw?.let { gearMode(it) }
        badge.text = gear?.first ?: "—"
        parkedText.text = gear?.second ?: "Нет данных"
    }

    /** Буква и подпись режима; порядок проверки как в capy (D, P, R, N). */
    private fun gearMode(raw: Int): Pair<String, String>? = when {
        (raw and GeelyProperties.GEAR_DRIVE) != 0 -> "D" to "Движение"
        (raw and GeelyProperties.GEAR_PARK) != 0 -> "P" to "Припаркован"
        (raw and GeelyProperties.GEAR_REVERSE) != 0 -> "R" to "Задний ход"
        (raw and GeelyProperties.GEAR_NEUTRAL) != 0 -> "N" to "Нейтраль"
        else -> null
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.num(key: String): Double? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    /** Скрывается на CarPlay / Android Auto, как прежний body.projfull .topbar. */
    fun setVisible(visible: Boolean) {
        root.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateClock() {
        val c = Calendar.getInstance()
        clockText.text = String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    private fun applyPalette() {
        val p = palette
        listOf(titleText, connText).forEach { it.setTextColor(p.ink) }
        listOf(parkedText, tempText, connVersion).forEach { it.setTextColor(p.muted) }
        clockText.setTextColor(p.ink)
        badge.setTextColor(p.onbtn)
        badge.background = rounded(p.btn, 8f)
        connDot.background = oval(0xFF34C759.toInt())
        connPill.background = rounded(p.card, 100f)
    }

    // ── Утилиты ──

    private fun text(value: String, sizeSp: Float, bold: Boolean = false): TextView =
        TextView(activity).apply {
            this.text = value
            textSize = sizeSp
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(palette.ink)
        }

    private fun wrap(endMargin: Int = 0, startMargin: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = endMargin
            marginStart = startMargin
        }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun dp(v: Int): Int = (v * density + 0.5f).roundToInt()
    private fun dp(v: Float): Int = (v * density + 0.5f).roundToInt()
}
