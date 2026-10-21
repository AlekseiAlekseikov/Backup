package com.example.voiceapp3.handlers

import android.util.Log
import com.example.voiceapp3.IntentHandler
import com.example.voiceapp3.PredictionResult
import com.example.voiceapp3.car.Ex2Vehicle
import com.example.voiceapp3.car.VehiclePropertyHelper

/**
 * Стёкла EX2. Люка и шторки нет — эти слова ничего не двигают.
 *
 *   полностью открыть  → WINDOW_POS=100, затем WINDOW_MOVE=+1
 *   полностью закрыть  → WINDOW_POS=0,   затем WINDOW_MOVE=-1
 *   на N процентов     → только позиция N
 *   чуть / немного     → текущая позиция ±20, без MOVE
 */
class WindowControlHandler(
    private val vehiclePropertyHelper: VehiclePropertyHelper
) : IntentHandler {

    companion object {
        private const val TAG = "WindowControlHandler"
        private const val MOVE_OPEN = 1
        private const val MOVE_CLOSE = -1
        private const val STEP = 20
    }

    private val missingRegex = Regex("люк|крыш|панорам|шторк")
    private val lockRegex = Regex("замок|блокир")
    private val pluralRegex = Regex("окна|стекла|форточки|все\\s+окон|всех\\s+окон")
    private val aBitRegex = Regex(
        "чуть|чут|немного|немого|слегка|чуток|немножко|капельк|приоткро|призакро"
    )
    private val halfRegex = Regex("наполовину|половину|на\\s+половину")
    private val percentWordRegex = Regex("%|процент|проц")
    private val naNumberRegex = Regex(
        """на\s+(\d{1,3}|ноль|один|одну|одна|одно|два|две|три|четыре|пять|шесть|семь|восемь|девять|десять|одиннадцать|двенадцать|тринадцать|четырнадцать|пятнадцать|шестнадцать|семнадцать|восемнадцать|девятнадцать|двадцать|тридцать|сорок|пятьдесят|шестьдесят|семьдесят|восемьдесят|девяносто|сто)\b"""
    )

    override fun canHandle(intent: String): Boolean = intent == "window_control"

    override fun handle(prediction: PredictionResult): Boolean {
        val text = (prediction.normalizedText.ifBlank { prediction.text }).lowercase()
            .replace('ё', 'е')
        Log.i(TAG, "cmd='${prediction.text}' entities=${prediction.entities}")

        if (missingRegex.containsMatchIn(text)) {
            Log.w(TAG, "у EX2 нет люка и шторки")
            return false
        }

        if (lockRegex.containsMatchIn(text)) {
            val on = extractCommonEntities(prediction).action != "unset"
            return Ex2Vehicle.setBool(
                vehiclePropertyHelper, Ex2Vehicle.WINDOW_LOCK, Ex2Vehicle.WINDOW_LOCK_AREA, on
            )
        }

        return handleWindows(prediction, text)
    }

    private fun handleWindows(prediction: PredictionResult, text: String): Boolean {
        val params = extractCommonEntities(prediction)
        val explicit = extractExplicitPercent(prediction, params.value, params.unit, text)
        val bit = prediction.entities["amount"] == "bit" || aBitRegex.containsMatchIn(text)
        var action = params.action
        if (action == null) {
            if (explicit == null && !bit) {
                Log.w(TAG, "нет action")
                return false
            }
            action = "set"
        }
        val shouldOpen = action == "set" || action == "increase"
        val direction = params.direction ?: (prediction.entities["direction"] as? String)
        val position = params.position ?: (prediction.entities["position"] as? String)
        val areas = determineTargetAreas(text, direction, position)
        Log.i(TAG, "windows open=$shouldOpen explicit=$explicit bit=$bit areas=$areas")
        var any = false
        for (area in areas) {
            val percent = resolvePercent(area, explicit, text, shouldOpen, bit)
            if (setWindow(area, percent, move = !bit && (percent == 0 || percent == 100))) {
                any = true
            }
        }
        return any
    }

    private fun extractExplicitPercent(
        prediction: PredictionResult,
        value: Int?,
        unit: String?,
        text: String
    ): Int? {
        if (halfRegex.containsMatchIn(text)) return 50
        val raw = value ?: (prediction.entities["value"] as? Int) ?: return null
        if (raw !in 0..100) return null
        val resolvedUnit = unit ?: (prediction.entities["unit"] as? String)
        if (resolvedUnit == "percent") return raw
        if (percentWordRegex.containsMatchIn(text)) return raw
        if (naNumberRegex.containsMatchIn(text)) return raw
        return null
    }

    private fun resolvePercent(
        areaId: Int,
        explicit: Int?,
        text: String,
        shouldOpen: Boolean,
        bit: Boolean
    ): Int {
        if (explicit != null) return explicit.coerceIn(0, 100)

        if (bit || aBitRegex.containsMatchIn(text)) {
            val cur = readPos(areaId)
            val base = when {
                cur in 0..100 -> cur
                shouldOpen -> 0
                else -> 100
            }
            val delta = if (shouldOpen) STEP else -STEP
            val target = (base + delta).coerceIn(0, 100)
            Log.i(TAG, "relative area=$areaId current=$cur base=$base delta=$delta -> $target")
            return target
        }

        return if (shouldOpen) 100 else 0
    }

    private fun readPos(areaId: Int): Int {
        val v = Ex2Vehicle.getInt(vehiclePropertyHelper, Ex2Vehicle.WINDOW_POS, areaId)
        Log.i(TAG, "WINDOW_POS read area=$areaId value=$v")
        return v
    }

    private fun determineTargetAreas(
        text: String,
        direction: String?,
        position: String?
    ): Set<Int> {
        var dir = direction
        var pos = position
        if (dir == null) {
            dir = when {
                Regex("лев|водител").containsMatchIn(text) &&
                        Regex("прав|пассажир").containsMatchIn(text) -> "both"
                Regex("лев|водител").containsMatchIn(text) -> "left"
                Regex("прав|пассажир").containsMatchIn(text) -> "right"
                else -> null
            }
        }
        if (pos == null) {
            pos = when {
                Regex("передн|спереди|первый\\s+ряд").containsMatchIn(text) &&
                        Regex("задн|сзади|второй\\s+ряд|детск").containsMatchIn(text) -> "both"
                Regex("передн|спереди|первый\\s+ряд").containsMatchIn(text) -> "front"
                Regex("задн|сзади|второй\\s+ряд|детск").containsMatchIn(text) -> "rear"
                else -> null
            }
        }
        val plural = pluralRegex.containsMatchIn(text) || dir == "both" || pos == "both" ||
                text.contains("все окн") || text.contains("все стекл")
        if (dir == null && pos == null) {
            return if (plural) {
                setOf(
                    Ex2Vehicle.WIN_DRIVER, Ex2Vehicle.WIN_PASSENGER,
                    Ex2Vehicle.WIN_REAR_LEFT, Ex2Vehicle.WIN_REAR_RIGHT
                )
            } else {
                setOf(Ex2Vehicle.WIN_DRIVER)
            }
        }
        val finalDir = dir ?: "both"
        val finalPos = pos ?: if (plural) "both" else "front"
        return when (finalPos) {
            "front" -> when (finalDir) {
                "left" -> setOf(Ex2Vehicle.WIN_DRIVER)
                "right" -> setOf(Ex2Vehicle.WIN_PASSENGER)
                else -> setOf(Ex2Vehicle.WIN_DRIVER, Ex2Vehicle.WIN_PASSENGER)
            }
            "rear" -> when (finalDir) {
                "left" -> setOf(Ex2Vehicle.WIN_REAR_LEFT)
                "right" -> setOf(Ex2Vehicle.WIN_REAR_RIGHT)
                else -> setOf(Ex2Vehicle.WIN_REAR_LEFT, Ex2Vehicle.WIN_REAR_RIGHT)
            }
            else -> when (finalDir) {
                "left" -> setOf(Ex2Vehicle.WIN_DRIVER, Ex2Vehicle.WIN_REAR_LEFT)
                "right" -> setOf(Ex2Vehicle.WIN_PASSENGER, Ex2Vehicle.WIN_REAR_RIGHT)
                else -> setOf(
                    Ex2Vehicle.WIN_DRIVER, Ex2Vehicle.WIN_PASSENGER,
                    Ex2Vehicle.WIN_REAR_LEFT, Ex2Vehicle.WIN_REAR_RIGHT
                )
            }
        }
    }

    /**
     * 100 / 0 — до упора: позиция и команда «ехать».
     * «Чуть» и промежуточное — только позиция, без MOVE.
     */
    private fun setWindow(areaId: Int, percent: Int, move: Boolean): Boolean {
        val posOk = Ex2Vehicle.setInt(
            vehiclePropertyHelper, Ex2Vehicle.WINDOW_POS, areaId, percent
        )
        Log.i(TAG, "WINDOW_POS area=$areaId value=$percent ok=$posOk")

        if (move && (percent == 100 || percent == 0)) {
            val dir = if (percent == 100) MOVE_OPEN else MOVE_CLOSE
            val moveOk = Ex2Vehicle.setInt(
                vehiclePropertyHelper, Ex2Vehicle.WINDOW_MOVE, areaId, dir
            )
            Log.i(TAG, "WINDOW_MOVE area=$areaId value=$dir ok=$moveOk")
            return posOk || moveOk
        }

        return posOk
    }
}
