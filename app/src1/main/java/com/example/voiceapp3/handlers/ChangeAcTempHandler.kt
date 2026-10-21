package com.example.voiceapp3.handlers

import android.util.Log
import com.example.voiceapp3.CommandParams
import com.example.voiceapp3.IntentHandler
import com.example.voiceapp3.PredictionResult
import com.example.voiceapp3.car.Ex2Vehicle
import com.example.voiceapp3.car.VehiclePropertyHelper
import kotlin.math.roundToInt

/**
 * Температура климата EX2. Однозонный: одно значение во все зоны.
 *
 * Пока не чистили машины, запись шла в зону 1 (ROW_1_LEFT) как FLOAT.
 * После перехода на 49/68 из дампа getFloat кидает IAE, запись не проходит,
 * а запасной turnOnAc щёлкает компрессором — отсюда «не получилось» и вкл/выкл AC.
 *
 * Теперь: питание климата (POWER), запись во все известные зоны, компрессор
 * не трогаем. Диапазон 17…32, только целые, «теплее/холоднее» = ±1.
 */
class ChangeAcTempHandler(
    val vehiclePropertyHelper: VehiclePropertyHelper
) : IntentHandler {

    private val TAG = "ChangeAcTempHandler"
    private val acControlHandler = AcControlHandler(vehiclePropertyHelper)

    companion object {
        private const val MIN_TEMP = 17.0f
        private const val MAX_TEMP = 32.0f
        private const val DEFAULT_STEP = 1.0f
        private const val TEMP_GRID = 1.0f
        private const val COMFORT_TEMP = 22.0f

        /** Зона 1 — как в рабочей сборке. 49/68 — конфиг дампа. 4 и 117 — запас. */
        private val TEMP_AREAS = intArrayOf(
            Ex2Vehicle.TEMP_ROW1_LEFT,
            Ex2Vehicle.TEMP_ROW1_RIGHT,
            Ex2Vehicle.TEMP_DRIVER,
            Ex2Vehicle.TEMP_PASSENGER,
            Ex2Vehicle.HVAC_AREA
        )
    }

    @Volatile
    private var lastWritten: Float = -1f

    override fun canHandle(intent: String): Boolean = intent == "change_ac_temp"

    override fun handle(prediction: PredictionResult): Boolean {
        val params = extractCommonEntities(prediction)
        val text = prediction.normalizedText

        Log.i(TAG, "action=${params.action} value=${params.value} unit=${params.unit} text='$text'")

        val action = effectiveAction(params, text)

        return when (action) {
            "unset" -> acControlHandler.turnOffAc()
            "increase", "decrease" -> handleChangeTemperature(action, params, text)
            else -> handleSetTemperature(params, text)
        }
    }

    private fun effectiveAction(params: CommandParams, text: String): String? {
        val a = params.action
        if (a == "increase" || a == "decrease" || a == "unset") return a
        if (params.value == null) {
            if (text.containsAny("тепле", "жарче", "погрей", "потепл")) return "increase"
            if (text.containsAny("холодне", "прохладне", "остуди", "похолод")) return "decrease"
        }
        return a
    }

    private fun handleSetTemperature(params: CommandParams, text: String): Boolean {
        when {
            text.containsAny("макс") && text.containsAny("холод") ->
                return setTemp(MIN_TEMP)
            text.containsAny("минимум", "минимальн") ->
                return setTemp(MIN_TEMP)
            text.containsAny("максимум", "максимальн", "на всю") ->
                return setTemp(MAX_TEMP)
            text.containsAny("нормальн", "как обычно") ->
                return setTemp(COMFORT_TEMP)
        }

        if (params.unit == "percent" && params.value != null) {
            val fraction = params.value.coerceIn(0, 100) / 100f
            return setTemp(MIN_TEMP + (MAX_TEMP - MIN_TEMP) * fraction)
        }

        val degrees = params.value?.toFloat() ?: parseTemperature(text)
        if (degrees == null) {
            Log.w(TAG, "Не понял температуру: '$text'")
            return false
        }
        return setTemp(degrees)
    }

    private fun handleChangeTemperature(
        action: String?,
        params: CommandParams,
        text: String
    ): Boolean {
        val increase = action == "increase"
        var current = readTemp()
        if (current <= 0f && lastWritten in MIN_TEMP..MAX_TEMP) {
            current = lastWritten
            Log.i(TAG, "чтение не вышло, беру последнюю записанную $current")
        }
        if (current <= 0f) {
            Log.w(TAG, "текущая температура неизвестна, компрессор не трогаю")
            return false
        }

        val explicit: Float? = when {
            params.unit == "percent" && params.value != null -> {
                val fraction = params.value.coerceIn(0, 100) / 100f
                (MAX_TEMP - MIN_TEMP) * fraction
            }
            params.value != null -> params.value.toFloat()
            else -> parseTemperature(text)
        }
        val delta = explicit ?: DEFAULT_STEP
        val base = current.snapToStep()
        val target = if (increase) base + delta else base - delta
        Log.i(TAG, "текущая $current сетка $base дельта ${if (increase) "+" else "-"}$delta -> $target")
        return setTemp(target)
    }

    fun setTemp(temp: Float): Boolean {
        val clamped = temp.coerceIn(MIN_TEMP, MAX_TEMP).snapToStep()
        Log.i(TAG, "ставлю $clamped °C")
        ensureClimatePower()
        if (writeTemp(clamped)) {
            lastWritten = clamped
            return true
        }
        Log.w(TAG, "температура не записалась ни в одну зону")
        return false
    }

    /**
     * HVAC_POWER_ON в дампе нет, но в рабочей сборке писали зону 5.
     * Неудача здесь не считается ошибкой. HVAC_AC_ON не трогаем.
     */
    private fun ensureClimatePower() {
        Ex2Vehicle.setBool(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_POWER_ON, Ex2Vehicle.HVAC_POWER_AREA, true
        )
        Ex2Vehicle.setBool(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_POWER_ON, Ex2Vehicle.HVAC_AREA, true
        )
    }

    private fun writeTemp(value: Float): Boolean {
        var any = false
        for (area in TEMP_AREAS) {
            val ok = Ex2Vehicle.setNumber(
                vehiclePropertyHelper, Ex2Vehicle.HVAC_TEMPERATURE_SET, area, value
            )
            if (ok) {
                Log.i(TAG, "зона $area приняла $value")
                any = true
            } else {
                Log.w(TAG, "зона $area не приняла $value")
            }
        }
        return any
    }

    private fun readTemp(): Float {
        for (area in TEMP_AREAS) {
            val v = Ex2Vehicle.getNumber(
                vehiclePropertyHelper, Ex2Vehicle.HVAC_TEMPERATURE_SET, area
            )
            if (v in MIN_TEMP..MAX_TEMP) {
                Log.i(TAG, "прочитал $v из зоны $area")
                return v
            }
        }
        return -1f
    }

    private fun parseTemperature(raw: String): Float? {
        val text = raw.lowercase().replace('ё', 'е')
        if (Regex("""на градус\b""").containsMatchIn(text)) return 1.0f
        Regex("""(\d{1,2})(?:[.,](\d))?""").find(text)?.let { m ->
            val whole = m.groupValues[1].toFloat()
            val frac = m.groupValues[2].toFloatOrNull()?.div(10f) ?: 0f
            val value = whole + frac
            if (value in 1f..MAX_TEMP) return value
        }
        val words = text.split(" ", "-").filter { it.isNotBlank() }
        var sum = 0
        var found = false
        for (w in words) {
            val n = NUMERALS[w] ?: continue
            found = true
            sum = if (n >= 20 && sum >= 20) n else sum + n
        }
        if (!found || sum == 0) return null
        return sum.toFloat()
    }

    private val NUMERALS: Map<String, Int> = mapOf(
        "один" to 1, "одну" to 1, "два" to 2, "две" to 2, "три" to 3,
        "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7,
        "восемь" to 8, "девять" to 9, "десять" to 10,
        "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13,
        "четырнадцать" to 14, "пятнадцать" to 15, "шестнадцать" to 16,
        "семнадцать" to 17, "восемнадцать" to 18, "девятнадцать" to 19,
        "двадцать" to 20, "тридцать" to 30,
    )

    private fun String.containsAny(vararg parts: String): Boolean =
        parts.any { this.contains(it) }

    private fun Float.snapToStep(): Float =
        (this / TEMP_GRID).roundToInt() * TEMP_GRID
}
