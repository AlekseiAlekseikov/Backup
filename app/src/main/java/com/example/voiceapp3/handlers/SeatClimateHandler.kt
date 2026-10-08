package com.example.voiceapp3.handlers

import android.util.Log
import com.example.voiceapp3.CommandParams
import com.example.voiceapp3.IntentHandler
import com.example.voiceapp3.PredictionResult
import com.example.voiceapp3.car.Ex2Vehicle
import com.example.voiceapp3.car.VehiclePropertyHelper

/**
 * Подогрев сидений и руля EX2.
 *
 * Уровни 1…3 (мин / средн / макс). 0 = выкл.
 * Пишем и AOSP HVAC_SEAT_TEMPERATURE, и вендор 624955432 — иначе на ГУ
 * ступень не загорается.
 * Вентиляции сидений в дампе нет.
 * Обогрев руля: 289408269, зона 0, те же 0…3.
 * «Обогрей стекло» сюда не приходит — это hvac_defrost.
 */
class SeatClimateHandler(
    private val vehiclePropertyHelper: VehiclePropertyHelper
) : IntentHandler {

    private val TAG = "SeatClimateHandler"

    private val PILOT_SEAT = Ex2Vehicle.SEAT_DRIVER
    private val PASSENGER_SEAT = Ex2Vehicle.SEAT_PASSENGER
    private val MIN_POWER = 1
    private val MAX_POWER = 3
    private val DEFAULT_POWER = 2

    override fun canHandle(intent: String): Boolean =
        intent == "seat_heat" || intent == "steering_heat"

    override fun handle(prediction: PredictionResult): Boolean {
        val params = extractCommonEntities(prediction)
        val text = prediction.normalizedText.lowercase().replace('ё', 'е')
        Log.i(TAG, "intent=${prediction.intent} action=${params.action} value=${params.value} text='$text'")

        if (prediction.intent == "steering_heat" || (text.contains("рул") && !text.contains("сиден"))) {
            return setSteeringHeat(params.action != "unset", params, text)
        }

        val targetSeats = determineTargetSeats(prediction)
        return when (params.action) {
            "set" -> handleSetClimate(params, targetSeats, text)
            "unset" -> handleUnsetClimate(targetSeats)
            "increase" -> handleChangeClimatePower(targetSeats, increase = true)
            "decrease" -> handleChangeClimatePower(targetSeats, increase = false)
            else -> handleSetClimate(params, targetSeats, text)
        }
    }

    private fun setSteeringHeat(on: Boolean, params: CommandParams, text: String): Boolean {
        val value = when {
            !on -> 0
            else -> resolveLevel(params, text)
        }
        Log.i(TAG, "руль heat=$value")
        return Ex2Vehicle.setInt(
            vehiclePropertyHelper, Ex2Vehicle.STEERING_WHEEL_HEAT, 0, value
        )
    }

    private fun determineTargetSeats(prediction: PredictionResult): Set<Int> {
        val text = prediction.normalizedText
        if (Regex("кресел|сидений|сидушек|диванов|оба|обе|все", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)
        ) {
            return setOf(PILOT_SEAT, PASSENGER_SEAT)
        }
        return when (prediction.getString("direction")) {
            "left" -> setOf(PILOT_SEAT)
            "right" -> setOf(PASSENGER_SEAT)
            "both" -> setOf(PILOT_SEAT, PASSENGER_SEAT)
            else -> setOf(PILOT_SEAT)
        }
    }

    private fun handleSetClimate(
        params: CommandParams,
        targetSeats: Set<Int>,
        text: String
    ): Boolean {
        val power = resolveLevel(params, text)
        return setClimatePower(targetSeats, power)
    }

    /**
     * 1 мин, 2 средн, 3 макс.
     * Число больше 3 — это не ступень сиденья (часто «22» от климата), не берём.
     */
    private fun resolveLevel(params: CommandParams, text: String): Int {
        when {
            text.contains("макс") || text.contains("сильный") || text.contains("сильна") ||
                    text.contains("полн") || text.contains("на всю") -> return MAX_POWER
            text.contains("средн") || text.contains("норм") -> return DEFAULT_POWER
            text.contains("слаб") -> return MIN_POWER
            text.contains("минималь") ||
                    (text.contains("мин") && !text.contains("минус") && !text.contains("максим")) ->
                return MIN_POWER
        }
        if (params.unit == "percent" && params.value != null) {
            return when {
                params.value >= 80 -> 3
                params.value >= 40 -> 2
                else -> 1
            }
        }
        if (params.value != null && params.value in MIN_POWER..MAX_POWER) {
            return params.value
        }
        return DEFAULT_POWER
    }

    private fun handleUnsetClimate(targetSeats: Set<Int>): Boolean {
        var success = true
        for (seat in targetSeats) {
            success = writeSeat(seat, 0) && success
        }
        return success
    }

    private fun handleChangeClimatePower(
        targetSeats: Set<Int>,
        increase: Boolean
    ): Boolean {
        var success = true
        for (seat in targetSeats) {
            val current = readSeat(seat)
            if (current < 0) {
                Log.w(TAG, "уровень неизвестен seat=$seat")
                success = false
                continue
            }
            val next = if (increase) {
                if (current <= 0) DEFAULT_POWER else (current + 1).coerceAtMost(MAX_POWER)
            } else {
                (current - 1).coerceAtLeast(0)
            }
            val ok = if (next <= 0) writeSeat(seat, 0) else setClimatePower(setOf(seat), next)
            success = ok && success
        }
        return success
    }

    private fun setClimatePower(targetSeats: Set<Int>, power: Int): Boolean {
        val clamped = power.coerceIn(MIN_POWER, MAX_POWER)
        var success = true
        for (seat in targetSeats) {
            success = writeSeat(seat, clamped) && success
        }
        return success
    }

    private fun readSeat(seat: Int): Int {
        val aosp = Ex2Vehicle.getInt(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_SEAT_TEMPERATURE, seat
        )
        if (aosp in 0..MAX_POWER) return aosp
        val vendor = Ex2Vehicle.getInt(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_SEAT_HEAT_VENDOR, seat
        )
        if (vendor in 0..MAX_POWER) return vendor
        return -1
    }

    private fun writeSeat(seat: Int, level: Int): Boolean {
        Log.i(TAG, "сиденье $seat = $level")
        val aosp = Ex2Vehicle.setInt(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_SEAT_TEMPERATURE, seat, level
        )
        val vendor = Ex2Vehicle.setInt(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_SEAT_HEAT_VENDOR, seat, level
        )
        return aosp || vendor
    }
}