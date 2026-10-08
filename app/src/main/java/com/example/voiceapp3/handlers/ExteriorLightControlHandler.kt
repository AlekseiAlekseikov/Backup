package com.example.voiceapp3.handlers

import android.util.Log
import com.example.voiceapp3.IntentHandler
import com.example.voiceapp3.PredictionResult
import com.example.voiceapp3.car.Ex2Vehicle
import com.example.voiceapp3.car.VehiclePropertyHelper

/**
 * На EX2 из внешнего света в командах оставляем только аварийку.
 * Противотуманок нет. HAZARD_LIGHTS_SWITCH 289410579, 1 = вкл, 0 = выкл.
 */
class ExteriorLightControlHandler(
    private val vehiclePropertyHelper: VehiclePropertyHelper
) : IntentHandler {

    private val TAG = "ExteriorLightControlHandler"

    override fun canHandle(intent: String): Boolean = intent == "exterior_light_control"

    override fun handle(prediction: PredictionResult): Boolean {
        val params = extractCommonEntities(prediction)
        val text = prediction.normalizedText.lowercase().replace('ё', 'е')
        val on = params.action != "unset"
        val value = if (on) Ex2Vehicle.LIGHT_ON else Ex2Vehicle.LIGHT_OFF
        Log.i(TAG, "text='$text' on=$on")

        if (text.contains("туман") || text.contains("птф")) {
            Log.w(TAG, "противотуманки на EX2 убраны")
            return false
        }

        val ok = Ex2Vehicle.setInt(vehiclePropertyHelper, Ex2Vehicle.HAZARD_SWITCH, 0, value)
        Log.i(TAG, "HAZARD_SWITCH=$value ok=$ok")
        return ok
    }
}
