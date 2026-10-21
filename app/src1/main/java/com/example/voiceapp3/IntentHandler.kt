package com.example.voiceapp3

import android.content.Context
import android.util.Log
import com.example.voiceapp3.car.CarAudioPlayer
import com.example.voiceapp3.car.VehiclePropertyHelper
import com.example.voiceapp3.handlers.AcControlHandler
import com.example.voiceapp3.handlers.ChangeAcTempHandler
import com.example.voiceapp3.handlers.ChangeFanSpeedHandler
import com.example.voiceapp3.handlers.DriveModeHandler
import com.example.voiceapp3.handlers.RecupModeHandler
import com.example.voiceapp3.handlers.DumpVehicleHandler
import com.example.voiceapp3.handlers.ExteriorLightControlHandler
import com.example.voiceapp3.handlers.ExternalSoundHandler
import com.example.voiceapp3.handlers.ExternalSpeechHandler
import com.example.voiceapp3.handlers.OpenAppHandler
import com.example.voiceapp3.handlers.SeatClimateHandler
import com.example.voiceapp3.handlers.TrunkControlHandler
import com.example.voiceapp3.handlers.WindowControlHandler
import com.example.voiceapp3.handlers.getAction
import com.example.voiceapp3.handlers.getUnit
import com.example.voiceapp3.handlers.getValue

interface IntentHandler {
    fun canHandle(intent: String): Boolean
    fun handle(prediction: PredictionResult): Boolean

    fun extractCommonEntities(prediction: PredictionResult): CommandParams {
        return CommandParams(
            action = prediction.getAction(),
            value = prediction.getValue(),
            unit = prediction.getUnit(),
            direction = prediction.entities["direction"] as? String,
            position = prediction.entities["position"] as? String
        )
    }
}

data class CommandParams(
    val action: String?,
    val value: Int?,
    val unit: String?,
    val direction: String? = null,
    val position: String? = null
)

class IntentHandlerRegistry(
    vehiclePropertyHelper: VehiclePropertyHelper,
    carAudioPlayer: CarAudioPlayer,
    context: Context
) {
    private val handlers = mutableListOf(
        AcControlHandler(vehiclePropertyHelper, context),
        ChangeAcTempHandler(vehiclePropertyHelper),
        ChangeFanSpeedHandler(vehiclePropertyHelper),
        SeatClimateHandler(vehiclePropertyHelper),
        WindowControlHandler(vehiclePropertyHelper),
        TrunkControlHandler(vehiclePropertyHelper),
        ExteriorLightControlHandler(vehiclePropertyHelper),
        DriveModeHandler(vehiclePropertyHelper),
        RecupModeHandler(vehiclePropertyHelper),
        DumpVehicleHandler(vehiclePropertyHelper, context),
        OpenAppHandler(context),
        ExternalSoundHandler(carAudioPlayer),
        ExternalSpeechHandler(carAudioPlayer),
    )

    fun register(handler: IntentHandler) {
        handlers.add(handler)
    }

    fun handle(prediction: PredictionResult): Boolean {
        val intent = prediction.intent

        if (intent.isBlank() || intent == "unknown") {
            Log.w(TAG, "Пустой или неизвестный интент — обработчик не ищу")
            return false
        }

        val handler = handlers.firstOrNull { it.canHandle(intent) }
        if (handler == null) {
            Log.w(TAG, "Нет обработчика для интента '$intent'")
            return false
        }

        Log.i(TAG, "Интент '$intent' -> ${handler.javaClass.simpleName}")
        return handler.handle(prediction)
    }

    private companion object {
        const val TAG = "IntentRegistry"
    }
}