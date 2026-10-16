package com.example.voiceapp3.car

import android.car.Car
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

/**
 * Доступ к VHAL на EX2 / IHU629G, Android 9 (API 28).
 *
 * На этом ГУ работает Car.createCar(context) без ServiceConnection —
 * дамп 1737 свойств был снят именно так.
 *
 * isPropertyAvailable на вендорных id часто false, хотя чтение/запись
 * проходят. Проверку больше не используем как запрет.
 */
class VehiclePropertyHelper(context: Context) {
    private val TAG: String = "VehiclePropertyHelper"
    private var car: Car? = null
    var propertyManager: CarPropertyManager? = null

    init {
        try {
            val created = Car.createCar(context)
            car = created
            propertyManager = created.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            Log.i(TAG, "car_service готов, pm=$propertyManager")
        } catch (t: Throwable) {
            Log.e(TAG, "Не удалось подключить Car", t)
            car = null
            propertyManager = null
        }
    }

    fun getIntProperty(propertyId: Int, areaId: Int): Int {
        return try {
            val pm = propertyManager
            if (pm == null) {
                Log.e(TAG, "pm=null getInt $propertyId")
                -1
            } else {
                pm.getIntProperty(propertyId, areaId)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "getInt $propertyId area=$areaId", t)
            -1
        }
    }

    fun getBoolProperty(propertyId: Int, areaId: Int): Boolean {
        return try {
            val pm = propertyManager
            if (pm == null) {
                Log.e(TAG, "pm=null getBool $propertyId")
                false
            } else {
                pm.getBooleanProperty(propertyId, areaId)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "getBool $propertyId area=$areaId", t)
            false
        }
    }

    fun getFloatProperty(propertyId: Int, areaId: Int): Float {
        return try {
            val pm = propertyManager
            if (pm == null) {
                Log.e(TAG, "pm=null getFloat $propertyId")
                -1f
            } else {
                pm.getFloatProperty(propertyId, areaId)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "getFloat $propertyId area=$areaId", t)
            -1f
        }
    }

    fun setIntProperty(propertyId: Int, areaId: Int, value: Int): Boolean {
        Log.i(TAG, "setInt $propertyId area=$areaId = $value")
        return try {
            val pm = propertyManager
            if (pm == null) {
                Log.e(TAG, "pm=null setInt $propertyId")
                false
            } else {
                pm.setIntProperty(propertyId, areaId, value)
                true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "setInt $propertyId area=$areaId value=$value", t)
            false
        }
    }

    fun setBoolProperty(propertyId: Int, areaId: Int, value: Boolean): Boolean {
        Log.i(TAG, "setBool $propertyId area=$areaId = $value")
        return try {
            val pm = propertyManager
            if (pm == null) {
                Log.e(TAG, "pm=null setBool $propertyId")
                false
            } else {
                pm.setBooleanProperty(propertyId, areaId, value)
                true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "setBool $propertyId area=$areaId value=$value", t)
            false
        }
    }

    fun setFloatProperty(propertyId: Int, areaId: Int, value: Float): Boolean {
        Log.i(TAG, "setFloat $propertyId area=$areaId = $value")
        return try {
            val pm = propertyManager
            if (pm == null) {
                Log.e(TAG, "pm=null setFloat $propertyId")
                false
            } else {
                pm.setFloatProperty(propertyId, areaId, value)
                true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "setFloat $propertyId area=$areaId value=$value", t)
            false
        }
    }

    fun disconnect() {
        try {
            car?.disconnect()
        } catch (t: Throwable) {
            Log.w(TAG, "disconnect", t)
        } finally {
            car = null
            propertyManager = null
        }
    }
}
