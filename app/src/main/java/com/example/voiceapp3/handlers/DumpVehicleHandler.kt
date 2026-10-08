package com.example.voiceapp3.handlers

import android.content.Context
import android.util.Log
import com.example.voiceapp3.IntentHandler
import com.example.voiceapp3.PredictionResult
import com.example.voiceapp3.car.VehiclePropertyDumper
import com.example.voiceapp3.car.VehiclePropertyHelper

class DumpVehicleHandler(
    private val vehiclePropertyHelper: VehiclePropertyHelper,
    private val context: Context
) : IntentHandler {

    companion object {
        private const val TAG = "DumpVehicleHandler"
    }

    override fun canHandle(intent: String): Boolean = intent == "dump_vehicle"

    override fun handle(prediction: PredictionResult): Boolean {
        Log.i(TAG, "дамп VHAL по команде '${prediction.text}'")
        val file = VehiclePropertyDumper.dump(context, vehiclePropertyHelper)
        if (file == null) {
            Log.e(TAG, "дамп не записался")
            return false
        }
        Log.i(TAG, "файл ${file.absolutePath} size=${file.length()}")
        return true
    }
}
