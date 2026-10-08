package com.example.voiceapp3.handlers

import android.util.Log
import com.example.voiceapp3.IntentHandler
import com.example.voiceapp3.PredictionResult
import com.example.voiceapp3.car.Ex2Vehicle
import com.example.voiceapp3.car.VehiclePropertyHelper

/**
 * Режимы вождения EX2: только эко, комфорт, спорт.
 *
 * Дампы с ГУ:
 *   557850837 (0x214020D5) RW
 *     эко      570491137
 *     комфорт  570491138
 *     спорт    570491139
 *
 * 557846540 в тех же дампах всегда 0 — запись в него принимается,
 * машина не переключается. Сюда больше не пишем.
 *
 * GET сразу после SET часто ещё старое число, хотя ГУ уже переключился.
 * Успех = запись прошла.
 */
class DriveModeHandler(
    private val vehiclePropertyHelper: VehiclePropertyHelper
) : IntentHandler {

    companion object {
        private const val TAG = "DriveModeHandler"
        const val MODE_ECO = "eco"
        const val MODE_COMFORT = "comfort"
        const val MODE_SPORT = "sport"
    }

    override fun canHandle(intent: String): Boolean = intent == "drive_mode"

    override fun handle(prediction: PredictionResult): Boolean {
        val text = (prediction.normalizedText.ifBlank { prediction.text })
            .lowercase()
            .replace('ё', 'е')
        val mode = (prediction.entities["mode"] as? String) ?: detectMode(text)
        Log.i(TAG, "cmd='${prediction.text}' mode=$mode")
        if (mode == null) {
            Log.w(TAG, "не понял режим: '$text'")
            return false
        }
        val value = valueFor(mode) ?: return false
        val written = Ex2Vehicle.setInt(
            vehiclePropertyHelper, Ex2Vehicle.DRIVE_MODE, 0, value
        )
        if (!written) {
            Log.w(TAG, "drive mode $mode value=$value не записалось")
            return false
        }
        val read = Ex2Vehicle.getInt(vehiclePropertyHelper, Ex2Vehicle.DRIVE_MODE, 0)
        Log.i(TAG, "drive mode $mode value=$value read=$read")
        return true
    }

    private fun valueFor(mode: String): Int? = when (mode) {
        MODE_COMFORT -> Ex2Vehicle.MODE_COMFORT
        MODE_ECO -> Ex2Vehicle.MODE_ECO
        MODE_SPORT -> Ex2Vehicle.MODE_SPORT
        else -> null
    }

    internal fun detectMode(text: String): String? = when {
        text.contains("спорт") || text.contains("sport") -> MODE_SPORT
        text.contains("эко") || text.contains("эхо") ||
                text.contains("еко") || text.contains("ико") ||
                text.contains("эконом") || text.contains("экономич") ||
                text.contains("eco") -> MODE_ECO
        text.contains("комфорт") || text.contains("comfort") ||
                text.contains("обычн") || text.contains("стандарт") ||
                text.contains("нормальн") -> MODE_COMFORT
        else -> null
    }
}