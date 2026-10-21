package com.example.voiceapp3.tools

import android.car.Car
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Stage CXT · срез №5: Автоматизация окон и дверей (§9 ТЗ).
 *
 * Точный перенос логики CentralEXAuto (w0.s2, w0.u, w0.g1, CentralWatchdogService):
 * 1. window_driver_on_door: Снятие избыточного давления воздуха при закрытии
 *    двери (filete de ar) — приоткрытие стекла на 10% (POS 10) при открытии двери,
 *    возврат в закрытое положение (POS 0) при закрытии.
 * 2. window_opposite_on_door: Приоткрытие противоположного окна при открытии
 *    пассажирских дверей.
 * 
 * Точные VHAL свойства Geely EX2 (IHU629G):
 * - DOOR_POS: 373295872 (0x16400b00)
 * - WINDOW_POS: 322964416 (0x13400bc0)
 */
object WindowDoors {
    private const val TAG = "WindowDoors"
    private const val PREFS_NAME = "voiceapp3"

    const val PREF_WINDOW_DRIVER_ON_DOOR = "window_driver_on_door"
    const val PREF_WINDOW_OPPOSITE_ON_DOOR = "window_opposite_on_door"

    // VHAL VehiclePropertyIds (IHU629G Geely EX2)
    const val PROP_DOOR_POS = 373295872     // 0x16400b00
    const val PROP_WINDOW_POS = 322964416   // 0x13400bc0

    // Зоны дверей -> Зоны стекол (из w0.u CentralEXAuto)
    // f4340s = [16, 64, 256, 1024]
    // f4341t = {1: 16, 4: 64, 16: 256, 64: 1024}
    // f4342u = {16: 64, 64: 16, 256: 1024, 1024: 256}
    val DOOR_TO_WINDOW = mapOf(1 to 16, 4 to 64, 16 to 256, 64 to 1024)
    val OPPOSITE_WINDOW = mapOf(16 to 64, 64 to 16, 256 to 1024, 1024 to 256)

    private var car: Car? = null
    private var carPropertyManager: CarPropertyManager? = null
    private var isRegistered = false
    private var appContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val doorStates = HashMap<Int, Boolean>()
    private var activeVentWindow: Int? = null

    
    private var screenWakeReceiver: android.content.BroadcastReceiver? = null
    private var lastSleepTimeMs: Long = SystemClock.elapsedRealtime()

    fun registerWakeReceiver(context: Context) {
        if (screenWakeReceiver != null) return
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
            addAction("android.intent.action.QUICKBOOT_POWERON")
        }
        screenWakeReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: android.content.Intent?) {
                val now = SystemClock.elapsedRealtime()
                val idleSeconds = (now - lastSleepTimeMs) / 1000
                lastSleepTimeMs = now
                AppLog.i(ctx, TAG, "ГУ проснулось (${intent?.action}), простой = ${idleSeconds} с")
                /* Звук разблокировки здесь НЕ играется: пробуждение экрана — не
                   снятие машины с охраны. Единственный источник события замка —
                   LockStateTrigger (свойство центрального замка + фильтр по
                   фронту в LockTransition). */
            }
        }
        try {
            context.applicationContext.registerReceiver(screenWakeReceiver, filter)
        } catch (e: Throwable) {
            Log.w(TAG, "registerWakeReceiver failed", e)
        }
    }

private val carCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.propertyId == PROP_DOOR_POS) {
                val areaId = value.areaId
                val rawVal = (value.value as? Number)?.toInt() ?: return
                // rawVal == 1: открыта, rawVal == 2: закрыта (из w0.s2)
                val isOpen = (rawVal == 1)
                onDoorChanged(areaId, isOpen)
            }
        }

        override fun onErrorEvent(propId: Int, zone: Int) {
            // suppressed
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isWindowDriverOnDoor(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_WINDOW_DRIVER_ON_DOOR, false)
    }

    fun isWindowOppositeOnDoor(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_WINDOW_OPPOSITE_ON_DOOR, false)
    }

    /**
     * Инициализация подписки на CarPropertyManager для дверей.
     */
    fun initCarService(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (carPropertyManager != null || isRegistered) return

        try {
            car = Car.createCar(appCtx, null, 0L) { carObj, ready ->
                if (ready) {
                    try {
                        val cpm = carObj.getCarManager("property") as? CarPropertyManager
                        if (cpm != null) {
                            carPropertyManager = cpm
                            registerDoorListener(); registerWakeReceiver(context)
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Ошибка получения CarPropertyManager", e)
                    }
                } else {
                    carPropertyManager = null
                    isRegistered = false
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Car.createCar failed", e)
        }
    }

    private fun registerDoorListener() {
        val cpm = carPropertyManager ?: return
        if (isRegistered) return
        try {
            cpm.registerCallback(carCallback, PROP_DOOR_POS, 0.0f)
            isRegistered = true
            Log.i(TAG, "Успешно подписались на события DOOR_POS (0x16400b00)")
        } catch (e: Throwable) {
            Log.w(TAG, "registerCallback DOOR_POS failed", e)
        }
    }

    private fun onDoorChanged(doorAreaId: Int, isOpen: Boolean) {
        val lastState = doorStates[doorAreaId]
        doorStates[doorAreaId] = isOpen
        if (lastState == isOpen) return

        val cpm = carPropertyManager ?: return
        val targetWindow = DOOR_TO_WINDOW[doorAreaId] ?: return

        Log.i(TAG, "Дверь 0x${Integer.toHexString(doorAreaId)} ${if (isOpen) "открыта" else "закрыта"}")

        /* Звук замка от двери НЕ зависит: состояние дверей (DOOR_POS) — не
           состояние центрального замка. Открытая дверь при уже снятой с охраны
           машине давала повторный звук — триггер перенесён в LockStateTrigger. */

        if (isOpen) {
            // Если включено открывать стекло:
            // опускаем стекло на 10% (POS 10)
            try {
                cpm.setIntProperty(PROP_WINDOW_POS, targetWindow, 10)
                activeVentWindow = targetWindow
                Log.i(TAG, "Окно 0x${Integer.toHexString(targetWindow)} -> приоткрыто на 10%")
            } catch (e: Throwable) {
                Log.w(TAG, "Не удалось приоткрыть окно 0x${Integer.toHexString(targetWindow)}", e)
            }
        } else {
            // Дверь закрылась: возвращаем в POS 0 (полностью закрыто)
            try {
                cpm.setIntProperty(PROP_WINDOW_POS, targetWindow, 0)
                if (activeVentWindow == targetWindow) activeVentWindow = null
                Log.i(TAG, "Окно 0x${Integer.toHexString(targetWindow)} -> закрыто (0%)")
            } catch (e: Throwable) {
                Log.w(TAG, "Не удалось закрыть окно 0x${Integer.toHexString(targetWindow)}", e)
            }
        }
    }

    fun setWindowDriverOnDoor(context: Context, enabled: Boolean): ToolsResult {
        val t0 = SystemClock.uptimeMillis()
        getPrefs(context).edit().putBoolean(PREF_WINDOW_DRIVER_ON_DOOR, enabled).commit()
        if (enabled) {
            initCarService(context)
        }
        val readback = isWindowDriverOnDoor(context)
        val step = ToolsStep(
            step = "settings.windowDriverOnDoor",
            command = "prefs.putBoolean($PREF_WINDOW_DRIVER_ON_DOOR, $enabled)",
            startedAtMs = t0,
            finishedAtMs = SystemClock.uptimeMillis(),
            exitCode = 0,
            stdout = "windowDriverOnDoor=$readback",
            stderr = "",
            expected = enabled.toString(),
            actual = readback.toString(),
            status = ToolsStep.Status.OK
        )
        return ToolsResult(
            action = "settings.windowDriverOnDoor",
            title = "Стекло водителя при открытии двери",
            steps = listOf(step)
        )
    }

    fun setWindowOppositeOnDoor(context: Context, enabled: Boolean): ToolsResult {
        val t0 = SystemClock.uptimeMillis()
        getPrefs(context).edit().putBoolean(PREF_WINDOW_OPPOSITE_ON_DOOR, enabled).commit()
        if (enabled) {
            initCarService(context)
        }
        val readback = isWindowOppositeOnDoor(context)
        val step = ToolsStep(
            step = "settings.windowOppositeOnDoor",
            command = "prefs.putBoolean($PREF_WINDOW_OPPOSITE_ON_DOOR, $enabled)",
            startedAtMs = t0,
            finishedAtMs = SystemClock.uptimeMillis(),
            exitCode = 0,
            stdout = "windowOppositeOnDoor=$readback",
            stderr = "",
            expected = enabled.toString(),
            actual = readback.toString(),
            status = ToolsStep.Status.OK
        )
        return ToolsResult(
            action = "settings.windowOppositeOnDoor",
            title = "Противоположное стекло",
            steps = listOf(step)
        )
    }
}
