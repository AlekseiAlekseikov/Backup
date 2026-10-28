package com.example.voiceapp3.handlers

import android.car.VehiclePropertyIds
import android.util.Log
import com.example.voiceapp3.IntentHandler
import com.example.voiceapp3.PredictionResult
import com.example.voiceapp3.car.Ex2Vehicle
import com.example.voiceapp3.car.VehiclePropertyHelper

/**
 * Багажник EX2.
 *
 * Задний  — DOOR_MOVE зона 536870912 = 1.
 * Передний — DOOR_MOVE зона 268435456 = 1; в дампе открытого капота 557875230 = 1.
 * Закрытия электроприводом нет: не пишем 0 и не говорим «готово».
 */
class TrunkControlHandler(
    val vehiclePropertyHelper: VehiclePropertyHelper
) : IntentHandler {

    private val TAG = "TrunkControlHandler"

    override fun canHandle(intent: String): Boolean = intent == "trunk_control"

    override fun handle(prediction: PredictionResult): Boolean {
        val params = extractCommonEntities(prediction)
        val text = prediction.normalizedText.lowercase().replace('ё', 'е')
        val front = params.position == "front" ||
                text.contains("капот") ||
                text.contains("спереди") ||
                (text.contains("передн") && text.contains("багаж"))
        return when (params.action) {
            "set" -> if (front) openFrunk() else openTrunk()
            "unset" -> {
                Log.w(TAG, "закрыть багажник на EX2 нельзя")
                false
            }
            else -> false
        }
    }

    private fun tooFast(): Boolean {
        val speed = vehiclePropertyHelper.getFloatProperty(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)
        if (speed >= 5.0f) {
            Log.w(TAG, "багажник заблокирован, скорость $speed")
            return true
        }
        return false
    }

    private fun openTrunk(): Boolean {
        if (tooFast()) return false
        Log.i(TAG, "открываю задний багажник")
        return Ex2Vehicle.setInt(
            vehiclePropertyHelper, Ex2Vehicle.DOOR_MOVE, Ex2Vehicle.TRUNK_AREA, 1
        )
    }

    private fun openFrunk(): Boolean {
        if (tooFast()) return false
        Log.i(TAG, "открываю передний багажник")
        val move = Ex2Vehicle.setInt(
            vehiclePropertyHelper, Ex2Vehicle.DOOR_MOVE, Ex2Vehicle.HOOD_AREA, 1
        )
        Ex2Vehicle.setInt(vehiclePropertyHelper, Ex2Vehicle.FRUNK_OPEN, 0, 1)
        val read = Ex2Vehicle.getInt(vehiclePropertyHelper, Ex2Vehicle.FRUNK_OPEN, 0)
        val ok = move || read == 1
        Log.i(TAG, "FRUNK move=$move status=$read ok=$ok")
        return ok
    }
}