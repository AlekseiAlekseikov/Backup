package com.example.voiceapp3.ui

import android.content.Context
import android.car.VehiclePropertyIds
import android.util.Log
import com.example.voiceapp3.car.Ex2Vehicle
import com.example.voiceapp3.car.VehiclePropertyHelper
import java.util.concurrent.Executors

/**
 * Stage B7: управление кузовом для карточки машины (капот/багажник).
 *
 * Логика открытия — 1:1 из голосового TrunkControlHandler пользователя
 * (handlers/TrunkControlHandler.kt), чтобы экран и голос ходили одним
 * и тем же путём VHAL:
 *  - защита от скорости: PERF_VEHICLE_SPEED >= 5 км/ч → блокировка;
 *  - задний багажник: DOOR_MOVE зона 536870912 = 1;
 *  - капот/передний: DOOR_MOVE зона 268435456 = 1 + FRUNK_OPEN
 *    (0x2140801E) = 1; успех = запись прошла ИЛИ статус читается как 1
 *    (в дампе открытого капота 557875230 = 1);
 *  - закрытия электроприводом на EX2 нет — не пишем 0 (прямо из
 *    комментария хендлера).
 *
 * Центральный замок: управления нет — в источниках телеметрии (capy)
 * замок только читается (VEHIC_RKE_LOCK_FEEDBACK / PEPS_RKECommand —
 * наблюдение команд брелока, walk-away lock там объявлен dead code).
 * Каталог ex2_carprops.csv даёт лишь имена состояний (BODY_DOOR_LOCK_STATE
 * 0x214020bc и т.п.) без признака записываемости — гадательные записи
 * в вендорские свойства не делаем.
 *
 * Результат уходит в JS пакетом telemetry.actionDone {act, ok, message}
 * (тот же протокол, что у кнопок ⚙ — JS его уже тостит).
 */
class BodyControlAdapter(
    context: Context,
    private val push: (name: String, json: String) -> Unit
) {

    private val appContext = context.applicationContext
    private val executor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "body-control-ui") }

    /** Создаётся лениво: Car-соединение нужно только по первому действию. */
    @Volatile
    private var helper: VehiclePropertyHelper? = null

    private fun helperOrNull(): VehiclePropertyHelper? {
        helper?.let { return it }
        return runCatching { VehiclePropertyHelper(appContext) }
            .onFailure { Log.e(TAG, "car_service недоступен", it) }
            .getOrNull()
            .also { helper = it }
    }

    /** Открыть капот или багажник (закрытие электроприводом невозможно). */
    fun open(target: String) {
        executor.execute {
            val h = helperOrNull()
            if (h == null) {
                done(target, false, "Car-сервис недоступен")
                return@execute
            }
            // Защита от скорости — как в TrunkControlHandler.tooFast().
            val speed = runCatching {
                h.getFloatProperty(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)
            }.getOrDefault(0f)
            if (speed >= 5.0f) {
                done(target, false, "Блокировка: скорость ${speed.toInt()} км/ч")
                return@execute
            }
            val ok = when (target) {
                "trunk" -> {
                    Log.i(TAG, "открываю задний багажник (карточка)")
                    Ex2Vehicle.setInt(h, Ex2Vehicle.DOOR_MOVE, Ex2Vehicle.TRUNK_AREA, 1)
                }
                "hood" -> {
                    Log.i(TAG, "открываю капот (карточка)")
                    val move = Ex2Vehicle.setInt(
                        h, Ex2Vehicle.DOOR_MOVE, Ex2Vehicle.HOOD_AREA, 1
                    )
                    Ex2Vehicle.setInt(h, Ex2Vehicle.FRUNK_OPEN, 0, 1)
                    val read = Ex2Vehicle.getInt(h, Ex2Vehicle.FRUNK_OPEN, 0)
                    val res = move || read == 1
                    Log.i(TAG, "FRUNK move=$move status=$read ok=$res")
                    res
                }
                else -> return@execute
            }
            done(
                target, ok,
                if (ok) "Открываю " + (if (target == "hood") "капот" else "багажник")
                else "Не получилось — см. лог"
            )
        }
    }

    private fun done(act: String, ok: Boolean, message: String) {
        val json = "{\"act\":\"$act\",\"ok\":$ok,\"message\":\"$message\"}"
        push("telemetry.actionDone", json)
    }

    companion object {
        private const val TAG = "BodyControlAdapter"
    }
}
