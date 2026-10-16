package com.example.voiceapp3.handlers

import android.util.Log
import com.example.voiceapp3.IntentHandler
import com.example.voiceapp3.PredictionResult
import com.example.voiceapp3.car.Ex2Vehicle
import com.example.voiceapp3.car.VehiclePropertyHelper

/**
 * Рекуперация EX2: слабая, средняя, сильная.
 *
 * Дампы с ГУ:
 *   557885171 (0x2140A6F3) RW
 *     слабая   537003265
 *     средняя  537003266
 *     сильная  537003267
 *
 * GET сразу после SET часто ещё старое число, хотя ГУ уже переключился.
 * Успех = запись прошла.
 */
class RecupModeHandler(
    private val vehiclePropertyHelper: VehiclePropertyHelper
) : IntentHandler {

    companion object {
        private const val TAG = "RecupModeHandler"
        const val MODE_WEAK = "weak"
        const val MODE_MEDIUM = "medium"
        const val MODE_STRONG = "strong"
    }

    override fun canHandle(intent: String): Boolean = intent == "recup_mode"

    override fun handle(prediction: PredictionResult): Boolean {
        val text = (prediction.normalizedText.ifBlank { prediction.text })
            .lowercase()
            .replace('ё', 'е')
        val mode = (prediction.entities["mode"] as? String) ?: detectMode(text)
        Log.i(TAG, "cmd='${prediction.text}' mode=$mode")
        if (mode == null) {
            Log.w(TAG, "не понял уровень рекуперации: '$text'")
            return false
        }
        val value = valueFor(mode) ?: return false
        val written = Ex2Vehicle.setInt(
            vehiclePropertyHelper, Ex2Vehicle.RECUP_MODE, 0, value
        )
        if (!written) {
            Log.w(TAG, "recup $mode value=$value не записалось")
            return false
        }
        val read = Ex2Vehicle.getInt(vehiclePropertyHelper, Ex2Vehicle.RECUP_MODE, 0)
        Log.i(TAG, "recup $mode value=$value read=$read")
        return true
    }

    private fun valueFor(mode: String): Int? = when (mode) {
        MODE_WEAK -> Ex2Vehicle.RECUP_WEAK
        MODE_MEDIUM -> Ex2Vehicle.RECUP_MEDIUM
        MODE_STRONG -> Ex2Vehicle.RECUP_STRONG
        else -> null
    }

    internal fun detectMode(text: String): String? = when {
        text.contains("сильн") || text.contains("максимальн") ||
                text.contains("макс ") || text.contains("strong") -> MODE_STRONG
        text.contains("слаб") || text.contains("минимальн") ||
                text.contains("weak") -> MODE_WEAK
        text.contains("средн") || text.contains("нормальн") ||
                text.contains("medium") -> MODE_MEDIUM
        else -> null
    }
}