package com.example.voiceapp3.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.voiceapp3.R
import com.example.voiceapp3.nav.NavTheme
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Нативная карточка автомобиля на главном экране (замена HTML-карточки; слот #carSlot — якорь).
 *
 * Содержимое: запас хода, заряд, капот / замок / багажник, картинка по цвету,
 * полоса заряда и статус, точки выбора цвета.
 *
 * Данные — те же пуши, что уходят в JS (telemetry.data / telemetry.live), но
 * читаются здесь напрямую: UiBridge.pushToUi передаёт их сюда (порт telHomePaint /
 * telBodyPaint из index.html). Выбор цвета хранится в тех же настройках
 * (`pref_car_paint`, файл `voiceapp3`), что и раньше.
 *
 * Геометрия — из JS (`car.frame`): прямоугольник слота #carSlot в физических
 * пикселях. Это временно: снимается вместе с переносом главного экрана.
 */
class CarCardPanel(
    private val activity: Activity,
    private val send: (name: String, json: String) -> Unit,
) {
    private val view: FrameLayout = activity.findViewById(R.id.carCard)
    private val density = activity.resources.displayMetrics.density
    private val prefs = activity.getSharedPreferences("voiceapp3", android.content.Context.MODE_PRIVATE)

    private var palette: NavTheme = NavTheme.byId(null)

    // ── Состояние из телеметрии ──
    private var dataSoc: Double? = null
    private var liveSoc: Double? = null
    private var rangeKm: Double? = null
    private var packWh: Double? = null
    private var chargeState: String? = null
    private var dcKw: Double? = null
    private var vhalKw: Double? = null
    private var etaMin: Double? = null
    private var plugged = false
    private var hood: Double? = null
    private var trunk: Double? = null

    /** Состояние кнопок как в HTML: нажатие переключает; живой сигнал его перекрывает. */
    private var hoodOpen: Boolean = false
    private var trunkOpen: Boolean = false
    private val calloutTitles = mutableListOf<TextView>()
    private val hoodLine = View(activity)
    private val trunkLine = View(activity)
    private val lockLine = View(activity)

    /** Подпись над деталью: линия от подписи до точки на картинке и кружок на её конце. */
    private class Anchor(val column: View, val line: View, val dot: View, val fx: Float, val fy: Float, val surf: Float)
    private val anchors = mutableListOf<Anchor>()
    private val stage = FrameLayout(activity)
    private val hoodDot = View(activity)
    private val trunkDot = View(activity)
    private val lockDot = View(activity)
    private val speedNum = text("—", 56f, bold = true)
    private val speedUnit = text("км/ч", 18f)
    private var speedRow: View? = null
    private var speedKmh: Double? = null
    private val calloutPills = mutableListOf<View>()

    private var locked = true
    private var colorId: String = prefs.getString(PREF_CAR_PAINT, null) ?: "green"

    // ── Виджеты (строятся в коде внутри корня карточки) ──
    private val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(14))
        isClickable = true // касания по карточке не уходят в скрытый HTML под ней
    }
    private val rangeNum = text("—", 40f, bold = true)
    private val rangeUnit = text("км", 14f)
    private val battNum = text("—%", 22f, bold = true)
    private val battSub = text("батарея", 11f)
    private val hoodState = text("нет данных", 12f)
    private val trunkState = text("нет данных", 12f)
    private val lockBtn = text("🔒", 18f)
    private val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val barBg = FrameLayout(activity)
    private val barFill = View(activity)
    private val statusText = text("Не заряжается", 13f)
    private val nameText = text("", 12f)
    private val dotViews = mutableListOf<View>()

    init {
        buildLayout()
        view.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        applyPalette()
        setColor(colorId, save = false)
        paint()
    }

    /** Палитра темы шелла (ui.theme). */
    fun setTheme(id: String?) {
        palette = NavTheme.byId(id)
        applyPalette()
        paint()
    }

    /** Геометрия из JS (`car.frame`), физические пиксели. */
    fun onFrame(x: Float, y: Float, w: Float, h: Float) {
        if (w < 1f || h < 1f) {
            view.visibility = View.GONE
            return
        }
        val lp = view.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(0, 0)
        lp.width = w.roundToInt()
        lp.height = h.roundToInt()
        lp.leftMargin = x.roundToInt()
        lp.topMargin = y.roundToInt()
        view.layoutParams = lp
        view.visibility = View.VISIBLE
        view.bringToFront()
    }

    /**
     * Пуш из UiBridge.pushToUi (на UI-потоке): `telemetry.data` или `telemetry.live`.
     */
    fun onTelemetry(name: String, json: String) {
        val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return
        when (name) {
            "telemetry.data" -> {
                chargeState = obj.obj("status").str("chargeState")
                val range = obj.obj("range")
                rangeKm = range.num("carKm")
                dataSoc = range.num("soc")
                packWh = obj.obj("settings").num("packWh")
            }
            "telemetry.live" -> {
                val list = obj.get("signals")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
                val values = HashMap<String, Double>()
                for (el in list) {
                    val s = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val quality = s.str("quality")?.uppercase()
                    if (quality == "UNAVAILABLE" || quality == "ERROR") continue
                    val id = s.str("signalId") ?: continue
                    s.num("value")?.let { values[id] = it }
                }
                // Как в JS: значение, пришедшее раньше, держится, пока не придёт новое.
                values["HV_BATTERY_SOC"]?.let { liveSoc = it }
                values["EV_DC_CHARGE_POWER"]?.let { dcKw = it }
                values["EV_BATTERY_INSTANTANEOUS_POWER"]?.let { vhalKw = it }
                values["EV_CHARGE_ESTIMATED_TIME"]?.let { etaMin = it }
                values["EV_CHARGE_PORT_CONNECTED"]?.let { plugged = it != 0.0 }
                values["BCM_HOOD_STATUS"]?.let { hood = it }
                values["BODY_DOOR_TRUNK_DOOR_POS"]?.let { trunk = it }
                values["VEHICLE_SPEED"]?.let { speedKmh = decodeSpeed(it) }
            }
            else -> return
        }
        paint()
    }

    // ── Сборка разметки ──

    private fun buildLayout() {
        // Запас хода + заряд
        root.addView(text("Запас хода", 11f).apply { alpha = 0.8f })
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(rangeNum)
            addView(rangeUnit, lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(6); bottomMargin = dp(6)
            })
            addView(View(activity), lp(0, 0, 1f))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(battNum)
                addView(battSub)
            })
        })

        // Сцена: машина прижата к полоске заряда, подписи и линии — к своим деталям.
        stage.addView(image, FrameLayout.LayoutParams(0, 0))
        // Скорость — наверху сцены по центру, крупно.
        val speedBox = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(speedNum, lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(5) })
            addView(speedUnit, lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) })
        }
        speedRow = speedBox
        stage.addView(speedBox, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        val hoodCol = calloutBox("Капот", hoodState, hoodLine) { toggleHood() }
        val trunkCol = calloutBox("Багажник", trunkState, trunkLine) { toggleTrunk() }
        lockBtn.gravity = Gravity.CENTER
        lockBtn.isClickable = true
        lockBtn.setOnClickListener { toggleLock() }
        val lockCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            setOnClickListener { toggleLock() }
            addView(lockBtn, lp(dp(42), dp(42)))
        }
        for (col in listOf(hoodCol, trunkCol, lockCol)) {
            stage.addView(col, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        // Доли положения точки на картинке (0..1 по ширине и высоте): капот — перед, замок — дверь, багажник — корма.
        anchors += Anchor(hoodCol, hoodLine, hoodDot, 0.12f, SURF_HOOD, SURF_HOOD)
        anchors += Anchor(lockCol, lockLine, lockDot, CAR_CENTER_X, SURF_LOCK, SURF_LOCK)
        anchors += Anchor(trunkCol, trunkLine, trunkDot, 0.82f, SURF_TRUNK, SURF_TRUNK)
        for (a in anchors) {
            stage.addView(a.line, FrameLayout.LayoutParams(dp(1.5f), 0))
            stage.addView(a.dot, FrameLayout.LayoutParams(dp(7f), dp(7f)))
        }
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeStage() }
        root.addView(stage, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Полоса заряда
        barBg.addView(barFill)
        root.addView(barBg, lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(4) })
        root.addView(statusText.apply { setPadding(0, dp(6), 0, dp(6)) })

        // Цвет
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Цвет", 11f).apply { alpha = 0.8f }, lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) })
            for ((id, hex, label) in COLORS) {
                val dot = View(activity)
                dot.setOnClickListener { setColor(id, save = true) }
                dot.contentDescription = label
                dotViews.add(dot)
                addView(dot, lp(dp(22), dp(22)).apply { rightMargin = dp(8) })
                dot.tag = hex
            }
            addView(nameText, lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(4) })
        })
    }

    /** Колонка как у HTML `.co`: заголовок, значение, линия под ним. */
    /** Надпись над деталью: заголовок капсом, значение и линия к детали. */
    private fun calloutBox(title: String, value: TextView, line: View, onTap: () -> Unit): View {
        val titleView = TextView(activity).apply {
            text = title.uppercase()
            gravity = Gravity.CENTER_HORIZONTAL
            textSize = 11f
            isSingleLine = true
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        calloutTitles.add(titleView)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            minimumWidth = dp(96)
            isClickable = true
            setOnClickListener { onTap() }
            addView(titleView, lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            value.gravity = Gravity.CENTER_HORIZONTAL
            addView(value, lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    // ── Команды ──

    /**
     * Реальное открытие через мост (car.hood → BodyControlAdapter). Вид кнопки
     * меняет телеметрия автомобиля, а не нажатие. Закрыть электроприводом на EX2
     * нельзя — честно говорим об этом и ничего не отправляем.
     */
    private fun toggleHood() {
        if (hoodOpen) {
            toast("Капот уже открыт. Закрывается вручную")
            return
        }
        send("car.hood", "{\"open\":true}")
        toast("Открываю капот")
    }

    private fun toggleTrunk() {
        if (trunkOpen) {
            toast("Багажник уже открыт. Закрывается вручную")
            return
        }
        send("car.trunk", "{\"open\":true}")
        toast("Открываю багажник")
    }

    private fun toggleLock() {
        locked = !locked
        lockBtn.text = if (locked) "🔒" else "🔓"
        toast(if (locked) "Машина закрыта" else "Машина открыта")
    }

    private fun setColor(id: String, save: Boolean) {
        val entry = COLORS.firstOrNull { it.first == id } ?: COLORS[1]
        colorId = entry.first
        image.setImageResource(imageFor(colorId))
        nameText.text = entry.third
        dotViews.forEachIndexed { i, dot ->
            val c = COLORS[i]
            dot.background = oval(colorOf(c.second), if (c.first == colorId) palette.ink else null)
        }
        if (save) prefs.edit().putString(PREF_CAR_PAINT, colorId).apply()
    }

    // ── Отрисовка значений ──

    private fun paint() {
        speedNum.text = speedKmh?.let { it.roundToInt().coerceIn(0, 300).toString() } ?: "—"
        hood?.let { hoodOpen = it != 0.0 }
        trunk?.let { trunkOpen = it != 0.0 }
        applyCallout(hoodState, hoodLine, hoodDot, hoodOpen)
        applyCallout(trunkState, trunkLine, trunkDot, trunkOpen)
        lockLine.setBackgroundColor(lineColor())
        lockDot.background = oval(lineColor(), null)

        val soc = liveSoc ?: dataSoc
        if (soc == null) {
            battNum.text = "—%"
            rangeNum.text = "—"
            setFill(0.0, levelColor(0))
            statusText.text = "Нет данных автомобиля"
            return
        }
        val pct = soc.roundToInt().coerceIn(0, 100)
        battNum.text = "$pct%"
        setFill(pct / 100.0, levelColor(pct))
        rangeNum.text = rangeKm?.roundToInt()?.toString() ?: "—"

        if (chargeState == "ACTIVE") {
            val p = when {
                dcKw != null && dcKw!! > 0 -> dcKw
                vhalKw != null -> abs(vhalKw!!)
                else -> null
            }
            var min: Int? = etaMin?.takeIf { it > 0 }?.roundToInt()
            if (min == null && p != null && p > 0.2 && packWh != null) {
                min = ((100 - soc) / 100 * (packWh!! / 1000) / p * 60).roundToInt().takeIf { it > 0 }
            }
            val sb = StringBuilder("Зарядка")
            if (p != null) sb.append(" · ").append(fmt(p, 1)).append(" кВт")
            if (min != null) sb.append(" · ≈ ").append(min).append(" мин")
            statusText.text = sb.toString()
        } else {
            statusText.text = if (plugged) "Подключена · не заряжается" else "Не заряжается"
        }

    }

    /** Значение, линия и точка: открыто — оранжевым, как `.co.open` в HTML. Иконки цветные, без тинта. */
    private fun applyCallout(value: TextView, line: View, dot: View, open: Boolean) {
        val accent = 0xFFE86A33.toInt()
        value.text = if (open) "открыт" else "закрыт"
        value.setTextColor(if (open) accent else palette.ink)
        val color = if (open) accent else lineColor()
        line.setBackgroundColor(color)
        dot.background = oval(color, null)
    }

    /**
     * Ставит картинку у полоски заряда (по высоте, без обрезки), подписи — вверху сцены,
     * линии — от подписи вниз до точки на детали. Только translation и размер линии,
     * чтобы не зациклить перекладку.
     */
    private fun placeStage() {
        val w = stage.width.toFloat()
        val h = stage.height.toFloat()
        if (w < 1f || h < 1f || anchors.isEmpty()) return
        val gap = dp(6f).toFloat()
        val spacing = dp(8f).toFloat()
        val topLimit = (speedRow?.bottom?.toFloat() ?: 0f) + dp(6f).toFloat()
        // Группа «надписи + машина» центрируется в свободной области между скоростью и полоской заряда.
        val maxByWidth = w * IMG_H / IMG_W
        val lift = dp(26f).toFloat()
        val rowH = anchors.maxOf { it.column.height.toFloat() }
        val minSurf = anchors.minOf { it.surf }
        val availH = h - topLimit
        val maxByAvail = (availH - gap - rowH - lift) / (1f - minSurf)
        val imgH = minOf(maxByWidth, maxByAvail).coerceAtLeast(1f)
        val imgW = imgH * IMG_W / IMG_H
        val groupH = (1f - minSurf) * imgH + gap + rowH + lift
        val groupTop = topLimit + ((availH - groupH) / 2f).coerceAtLeast(0f)
        val imgT = groupTop + gap + rowH + lift - minSurf * imgH
        // Силуэт машины в картинке смещён влево — центрируем по нему, а не по рамке картинки.
        val imgL = w / 2f - CAR_CENTER_X * imgW
        setSize(image, imgW.roundToInt(), imgH.roundToInt())
        image.translationX = imgL
        image.translationY = imgT
        val lineW = dp(1.5f).toFloat()
        val dotR = dp(3.5f).toFloat()
        val sorted = anchors.sortedBy { it.fx }
        // Горизонтальные позиции: без наложения, в пределах карточки.
        val lefts = FloatArray(sorted.size)
        for ((i, a) in sorted.withIndex()) {
            val px = imgL + a.fx * imgW
            val cw = a.column.width.toFloat()
            var left = (px - cw / 2f).coerceIn(0f, (w - cw).coerceAtLeast(0f))
            if (i > 0) left = maxOf(left, lefts[i - 1] + sorted[i - 1].column.width + spacing)
            lefts[i] = left
        }
        val overflow = lefts.last() + sorted.last().column.width - w
        if (overflow > 0f) for (i in lefts.indices) lefts[i] = (lefts[i] - overflow).coerceAtLeast(0f)
        for ((i, a) in sorted.withIndex()) {
            val px = imgL + a.fx * imgW
            val py = imgT + a.fy * imgH
            val colH = a.column.height.toFloat()
            // Общий уровень ряда: выше самой высокой поверхности, плашки по центру ряда.
            val rowTop = (imgT + minSurf * imgH - gap - rowH - lift).coerceAtLeast(topLimit)
            val plateTop = rowTop + (rowH - colH) / 2f
            val lineTop = rowTop + rowH
            a.column.translationX = lefts[i]
            a.column.translationY = plateTop
            a.line.translationX = px - lineW / 2f
            a.line.translationY = lineTop
            setSize(a.line, lineW.roundToInt(), (py - lineTop).coerceAtLeast(0f).roundToInt())
            a.dot.translationX = px - dotR
            a.dot.translationY = py - dotR
        }
    }

    private fun setSize(v: View, w: Int, h: Int) {
        val lp = v.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(w, h)
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            v.layoutParams = lp
        }
    }

    private fun lineColor(): Int = (palette.muted2 and 0x00FFFFFF) or 0x40000000

    /** Цвет полоски заряда: ≥50 % зелёный, 20–49 % оранжевый, <20 % красный. */
    private fun levelColor(pct: Int): Int = when {
        pct >= 50 -> 0xFF1FB34E.toInt()
        pct >= 20 -> 0xFFE8933C.toInt()
        else -> 0xFFE5484D.toInt()
    }

    private fun setFill(fraction: Double, color: Int) {
        barFill.background = rounded(color, 4f)
        barBg.post {
            val w = (barBg.width * fraction).roundToInt()
            barFill.layoutParams = FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.MATCH_PARENT)
            barFill.requestLayout()
        }
    }

    private fun applyPalette() {
        val p = palette
        view.background = rounded(p.card, 18f)
        listOf(rangeNum, battNum, statusText, nameText).forEach { it.setTextColor(p.ink) }
        listOf(rangeUnit, battSub).forEach { it.setTextColor(p.muted) }
        calloutTitles.forEach { it.setTextColor(p.muted2) }
        calloutPills.forEach { it.background = null }
        speedNum.setTextColor(p.ink)
        speedUnit.setTextColor(p.muted)
        lockBtn.background = oval(p.btn, null)
        lockBtn.setTextColor(p.onbtn)
        barBg.background = rounded(p.card2, 4f)
        setColor(colorId, save = false)
    }

    // ── Утилиты ──

    private fun text(value: String, sp: Float, bold: Boolean = false): TextView =
        TextView(activity).apply {
            text = value
            textSize = sp
            setTextColor(palette.ink)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun lp(w: Int, h: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
        }

    private fun oval(color: Int, stroke: Int?): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (stroke != null) setStroke(dp(2), stroke)
        }

    private fun colorOf(hex: String): Int = android.graphics.Color.parseColor(hex)

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * density + 0.5f).roundToInt()
    private fun dp(v: Float): Int = (v * density + 0.5f).roundToInt()

    private fun fmt(v: Double, digits: Int): String =
        String.format(java.util.Locale.ROOT, "%.${digits}f", v).replace('.', ',')

    private fun JsonObject?.obj(key: String): JsonObject? =
        this?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject?.num(key: String): Double? =
        this?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    private fun JsonObject?.str(key: String): String? =
        this?.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    companion object {
        /** Тот же ключ, что у UiBridge (PREF_CAR_PAINT). */
        private const val PREF_CAR_PAINT = "pref_car_paint"

        /** Размер исходной картинки автомобиля (все цвета — 880×391). */
        private const val IMG_W = 880f
        private const val IMG_H = 391f

        /** Центр силуэта машины по ширине картинки (alpha-bbox x 1..746 из 880). */
        private const val CAR_CENTER_X = 0.4244f
        /** Доля высоты картинки, на которой начинается силуэт в точке (alpha-bbox car_green.webp). */
        /** Как capy (VehiclePropertyHelper.decodeVehicleSpeed): модуль, 0..140, +1 км/ч при скорости от 10. */
        fun decodeSpeed(raw: Double): Double {
            val n = kotlin.math.abs(raw).coerceIn(0.0, 140.0)
            return if (n >= 10.0) (n + 1.0).coerceIn(0.0, 140.0) else n
        }

        private const val SURF_HOOD = 0.317f
        private const val SURF_LOCK = 0.008f
        private const val SURF_TRUNK = 0.307f

        /** id, HEX (как в index.html), подпись. */
        private val COLORS = listOf(
            Triple("beige", "#E7DCC2", "Бежевый"),
            Triple("green", "#B4D2A4", "Зелёный"),
            Triple("grey", "#9AA0A4", "Серый"),
            Triple("pink", "#F2C6CD", "Розовый"),
            Triple("silver", "#CBD0D4", "Серебристый"),
            Triple("white", "#F7F7F7", "Белый"),
        )

        private fun imageFor(id: String): Int = when (id) {
            "beige" -> R.drawable.car_beige
            "grey" -> R.drawable.car_grey
            "pink" -> R.drawable.car_pink
            "silver" -> R.drawable.car_silver
            "white" -> R.drawable.car_white
            else -> R.drawable.car_green
        }
    }
}
