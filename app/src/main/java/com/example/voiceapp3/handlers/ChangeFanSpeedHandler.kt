package com.example.voiceapp3.handlers

import android.util.Log
import com.example.voiceapp3.CommandParams
import com.example.voiceapp3.IntentHandler
import com.example.voiceapp3.PredictionResult
import com.example.voiceapp3.car.Ex2Vehicle
import com.example.voiceapp3.car.VehiclePropertyHelper

/**
 * Обдув EX2. HVAC_FAN_SPEED и HVAC_FAN_DIRECTION — только зона 117.
 * Скоростей 1…8. Зоны 5 в дампе нет.
 */
class ChangeFanSpeedHandler(
    val vehiclePropertyHelper: VehiclePropertyHelper
) : IntentHandler {

    private val TAG = "ChangeFanSpeedHandler"
    private val acControlHandler = AcControlHandler(vehiclePropertyHelper)

    private companion object {
        const val DEFAULT_SPEED_CHANGE = 1
        const val DEFAULT_ON_SPEED = 3
        const val DIR_FACE = 1
        const val DIR_FLOOR = 2
        const val DIR_DEFROST = 4
        const val DIR_ALL = 7
    }

    private val minSpeed: Int = Ex2Vehicle.FAN_MIN
    private val maxSpeed: Int = Ex2Vehicle.FAN_MAX

    override fun canHandle(intent: String): Boolean = intent == "change_fan_speed"

    override fun handle(prediction: PredictionResult): Boolean {
        val params = extractCommonEntities(prediction)
        val text = prediction.normalizedText
        Log.i(TAG, "action=${params.action} value=${params.value} unit=${params.unit} text='$text'")

        val heatGlass = text.contains("подогре") || text.contains("обогре") ||
                text.contains("спираль")
        val directions = mutableSetOf<Int>()
        if (text.contains(Regex("салон|внутр|лицо|себя"))) directions.add(DIR_FACE)
        if (text.contains(Regex("ног|вниз|пол\\b"))) directions.add(DIR_FLOOR)
        if (!heatGlass && text.contains(Regex("стекл|вверх|лобов|обдув стекл"))) {
            directions.add(DIR_DEFROST)
        }
        if (text.contains(Regex("везде|всюду|всего|все стороны|вместе"))) {
            directions.clear()
            directions.add(DIR_ALL)
        }

        if (directions.isNotEmpty()) {
            if (params.action == "unset" && directions == setOf(DIR_DEFROST)) {
                Log.i(TAG, "выключаю направление на стекло -> лицо")
                return acControlHandler.setAcDirection(DIR_FACE)
            }
            if (params.value != null) handleSetFanSpeed(params)
            val isMultiple = text.contains("плюс") || directions.size > 1
            val combined = if (isMultiple) directions.reduce { acc, d -> acc or d }
            else directions.first()
            Log.i(TAG, "направление обдува -> $combined")
            return acControlHandler.setAcDirection(combined)
        }

        return when (params.action) {
            "set" -> handleSetFanSpeed(params)
            "increase", "decrease" -> handleChangeFanSpeed(params)
            "unset" -> turnFanOff()
            else -> handleSetFanSpeed(params)
        }
    }

    private fun handleSetFanSpeed(params: CommandParams): Boolean {
        if (params.value != null) {
            if (params.unit == "percent") {
                val fraction = params.value.coerceIn(0, 100) / 100f
                val speed = minSpeed + ((maxSpeed - minSpeed) * fraction).toInt()
                return setFanSpeed(speed)
            }
            return setFanSpeed(params.value)
        }
        val current = readFanSpeed()
        if (current > 0) {
            Log.i(TAG, "обдув уже $current")
            return true
        }
        return setFanSpeed(DEFAULT_ON_SPEED)
    }

    private fun handleChangeFanSpeed(params: CommandParams): Boolean {
        val current = readFanSpeed()
        if (current < 0) {
            Log.w(TAG, "скорость неизвестна")
            return false
        }
        val increase = params.action == "increase"
        val delta = when {
            params.unit == "percent" && params.value != null -> {
                val fraction = params.value.coerceIn(0, 100) / 100f
                val d = ((maxSpeed - minSpeed) * fraction).toInt()
                if (d < 1) 1 else d
            }
            params.value != null -> params.value
            else -> DEFAULT_SPEED_CHANGE
        }
        val target = if (increase) current + delta else current - delta
        Log.i(TAG, "обдув $current ${if (increase) "+" else "-"}$delta -> $target")
        return setFanSpeed(target)
    }

    private fun turnFanOff(): Boolean {
        Log.i(TAG, "выключаю обдув")
        if (writeFanSpeed(0)) return true
        return writeFanSpeed(minSpeed)
    }

    private fun setFanSpeed(speed: Int): Boolean {
        val clamped = speed.coerceIn(minSpeed, maxSpeed)
        Log.i(TAG, "скорость обдува $clamped")
        if (writeFanSpeed(clamped)) return true
        Log.i(TAG, "не записалось, поднимаю климат")
        if (!acControlHandler.turnOnAc(setAuto = false)) return false
        return writeFanSpeed(clamped)
    }

    private fun writeFanSpeed(speed: Int): Boolean =
        Ex2Vehicle.setInt(
            vehiclePropertyHelper,
            Ex2Vehicle.HVAC_FAN_SPEED,
            Ex2Vehicle.HVAC_AREA,
            speed
        )

    private fun readFanSpeed(): Int =
        Ex2Vehicle.getInt(
            vehiclePropertyHelper,
            Ex2Vehicle.HVAC_FAN_SPEED,
            Ex2Vehicle.HVAC_AREA
        )
}