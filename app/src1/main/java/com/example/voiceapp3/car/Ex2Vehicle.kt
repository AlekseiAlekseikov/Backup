package com.example.voiceapp3.car

import android.car.VehiclePropertyIds
import android.util.Log
import kotlin.math.roundToInt

/**
 * Константы VHAL Geely EX2 (IHU629G) — только то, что есть в живом дампе.
 *
 * Зоны HVAC:
 *   117 = весь салон (1+4+16+32+64)
 *    49 = водитель (1+16+32) — в конфиге TEMPERATURE_SET
 *    68 = пассажир (4+64)
 *     1 = ROW_1_LEFT — так температура писалась, пока работала
 *     4 = ROW_1_RIGHT
 *     5 = HVAC_POWER_ON в исходном хендлере
 *
 * Режимы вождения (дампы эко / комфорт / спорт):
 *   557850837 (0x214020D5) RW INT32 GLOBAL
 *     эко      570491137 = 0x22010101
 *     комфорт  570491138 = 0x22010102
 *     спорт    570491139 = 0x22010103
 *   557846540 в дампах всегда 0 — это не рабочий переключатель.
 */
object Ex2Vehicle {
    private const val TAG = "Ex2Vehicle"

    const val HVAC_AREA = 117
    const val TEMP_DRIVER = 49
    const val TEMP_PASSENGER = 68
    const val TEMP_ROW1_LEFT = 1
    const val TEMP_ROW1_RIGHT = 4
    const val HVAC_POWER_AREA = 5
    const val SEAT_DRIVER = 1
    const val SEAT_PASSENGER = 4

    const val DRIVE_MODE = 557850837          // 0x214020D5, живой переключатель EX2
    const val WINDOW_POS = 591405227          // 0x234020AB
    const val WINDOW_MOVE = 322964417         // AOSP WINDOW_MOVE
    const val WINDOW_LOCK = 320867268
    const val WINDOW_LOCK_AREA = 1344
    const val DOOR_MOVE = 373295873
    const val HOOD_AREA = 268435456            // 0x10000000, передний багажник / капот
    const val TRUNK_AREA = 536870912           // 0x20000000, задний багажник
    const val FRUNK_OPEN = 557875230           // 0x2140801E, в дампе 1 = открыт
    const val HAZARD_SWITCH = 289410579
    const val STEERING_WHEEL_HEAT = 289408269
    const val HVAC_FAN_SPEED_VENDOR = 557846559     // 0x2140101F, копия скорости обдува
    const val HVAC_FAN_DIR_VENDOR = 557846560       // 0x21401020, копия направления
    const val HVAC_HEAT_MODE = 557883737            // 0x2140A159
    const val HVAC_HEAT_MODE_COOL = 268567041       // 0x10020201
    const val HVAC_HEAT_MODE_HEAT = 268567042       // 0x10020202, дамп «быстрый обогрев»
    /**
     * Вендорная температура салона. Sapo: setFloat(624955446, 1/4, °C), 17…32.
     * В дампе INT32, «быстрый обогрев» 1=30 — это 30 °C, не таймер пресета.
     */
    const val HVAC_VENDOR_TEMP = 624955446          // 0x25401036
    const val HVAC_QUICK_HEAT_TIMER = HVAC_VENDOR_TEMP
    const val HVAC_SEAT_HEAT_VENDOR = 624955432     // 0x25401028, дамп обогрева 1=3,4=3
    /**
     * 544/555/556 = 1 в дампах обогрева/охлаждения вместе со спиралью
     * (933=1, 758=2, 736=120). В комфорте/эко/спорт = 2. Это не пресет ГУ.
     */
    const val HVAC_QUICK_SCENE = 557884544          // 0x2140A480
    const val HVAC_QUICK_FLAG_A = 557887555         // 0x2140B043
    const val HVAC_QUICK_FLAG_B = 557887556         // 0x2140B044
    const val HVAC_QUICK_SCENE_ON = 1
    const val HVAC_QUICK_SCENE_OFF = 2
    /** Как Sapo пишет обдув: 1, 49, 5, 0 — и зона 117 из дампа. */
    val FAN_AREAS: IntArray = intArrayOf(117, 1, 49, 5, 0)
    val BOOL_AREAS: IntArray = intArrayOf(117, 1, 5, 0)
    val DIR_AREAS: IntArray = intArrayOf(117, 1, 4)
    val TEMP_AREAS: IntArray = intArrayOf(1, 4, 49, 68, 117)
    const val HVAC_DEFROSTER = 320865540
    const val HVAC_ELECTRIC_DEFROSTER = 320865556  // 0x13200514, WINDOW bool зоны 1/2; GET с ГУ всегда false
    /**
     * EX Sapo Central (виджеты EX2): лобовая спираль.
     * setBooleanProperty(270535680, 117, on). В дампе IHU id нет — HAL всё равно может принять.
     */
    const val WINDSHIELD_HEAT = 270535680          // 0x10200C00
    /**
     * EX Sapo: задняя спираль. HVAC_ELECTRIC_DEFROSTER с кодировкой SEAT, зона 2.
     * AOSP-вариант того же 0x0514 — HVAC_ELECTRIC_DEFROSTER зона 2.
     */
    const val REAR_DEFROST = 354419988             // 0x15200514
    const val GLASS_HEAT = 557884758               // 0x2140A556, статус: HAL принимает 2, спираль не включает
    const val GLASS_HEAT_ON = 2
    const val GLASS_HEAT_OFF = 0
    /** Живой флаг с ГУ: 1 при включённой спирали, 0 после нашей команды и в эко/комфорт. */
    const val GLASS_HEAT_ENABLE = 557884934        // 0x2140A606
    const val GLASS_HEAT_ENABLE_ON = 1
    const val GLASS_HEAT_ENABLE_OFF = 0
    const val GLASS_HEAT_SCENE = 557884933         // 0x2140A605, ГУ вкл=1, выкл/команда=4
    const val GLASS_HEAT_SCENE_ON = 1
    const val GLASS_HEAT_SCENE_OFF = 4
    const val GLASS_HEAT_TIMER = 557884736         // 0x2140A540, ГУ вкл=120, иначе 30
    const val GLASS_HEAT_TIMER_ON = 120
    const val GLASS_HEAT_TIMER_OFF = 30
    const val GLASS_HEAT_AUX_A = 557883712         // ГУ вкл=2, команда/комфорт=1
    const val GLASS_HEAT_AUX_B = 557883744
    const val GLASS_HEAT_AUX_C = 557883745
    const val GLASS_HEAT_AUX_ON = 2
    const val GLASS_HEAT_AUX_OFF = 1
    /** Импульс лобовое/заднее. WINDOW INT32, зоны 1 и 2, в дампах всегда 0. */
    const val GLASS_HEAT_CMD = 591397123           // 0x23400103
    const val GLASS_HEAT_CMD_ALT = 591400996       // 0x23401024, GET ClassCast — пробуем bool
    const val GLASS_HEAT_CMD_ON = 1
    const val GLASS_HEAT_CMD_OFF = 0
    const val HVAC_POWER_ON = 354419972            // VehiclePropertyIds.HVAC_POWER_ON, в дампе нет

    const val WIN_DRIVER = 16
    const val WIN_PASSENGER = 64
    const val WIN_REAR_LEFT = 256
    const val WIN_REAR_RIGHT = 1024
    const val WINDSHIELD_FRONT = 1
    const val WINDSHIELD_REAR = 2

    const val FAN_MIN = 1
    const val FAN_MAX = 8
    const val TEMP_MIN = 17f
    const val TEMP_MAX = 32f

    const val MODE_ECO = 570491137        // 0x22010101
    const val MODE_COMFORT = 570491138    // 0x22010102
    const val MODE_SPORT = 570491139      // 0x22010103

    const val RECUP_MODE = 557885171      // 0x2140A6F3, живая рекуперация EX2
    const val RECUP_WEAK = 537003265      // 0x20020501
    const val RECUP_MEDIUM = 537003266    // 0x20020502
    const val RECUP_STRONG = 537003267    // 0x20020503

    const val LIGHT_OFF = 0
    const val LIGHT_ON = 1

    val HVAC_AC_ON: Int get() = VehiclePropertyIds.HVAC_AC_ON
    val HVAC_AUTO_ON: Int get() = VehiclePropertyIds.HVAC_AUTO_ON
    val HVAC_MAX_AC_ON: Int get() = VehiclePropertyIds.HVAC_MAX_AC_ON
    val HVAC_MAX_DEFROST_ON: Int get() = VehiclePropertyIds.HVAC_MAX_DEFROST_ON
    val HVAC_RECIRC_ON: Int get() = VehiclePropertyIds.HVAC_RECIRC_ON
    val HVAC_FAN_SPEED: Int get() = VehiclePropertyIds.HVAC_FAN_SPEED
    val HVAC_FAN_DIRECTION: Int get() = VehiclePropertyIds.HVAC_FAN_DIRECTION
    val HVAC_TEMPERATURE_SET: Int get() = VehiclePropertyIds.HVAC_TEMPERATURE_SET
    val HVAC_SEAT_TEMPERATURE: Int get() = VehiclePropertyIds.HVAC_SEAT_TEMPERATURE

    fun setInt(helper: VehiclePropertyHelper, id: Int, area: Int, value: Int): Boolean {
        try {
            if (helper.setIntProperty(id, area, value)) return true
        } catch (t: Throwable) {
            Log.w(TAG, "helper.setInt $id area=$area: ${t.message}")
        }
        val pm = helper.propertyManager ?: return false
        return try {
            pm.setIntProperty(id, area, value)
            Log.i(TAG, "force setInt $id area=$area = $value")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "force setInt $id area=$area value=$value: ${t.message}")
            false
        }
    }

    fun setBool(helper: VehiclePropertyHelper, id: Int, area: Int, value: Boolean): Boolean {
        try {
            if (helper.setBoolProperty(id, area, value)) return true
        } catch (t: Throwable) {
            Log.w(TAG, "helper.setBool $id area=$area: ${t.message}")
        }
        val pm = helper.propertyManager ?: return false
        return try {
            pm.setBooleanProperty(id, area, value)
            Log.i(TAG, "force setBool $id area=$area = $value")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "force setBool $id area=$area value=$value: ${t.message}")
            false
        }
    }

    fun setFloat(helper: VehiclePropertyHelper, id: Int, area: Int, value: Float): Boolean {
        try {
            if (helper.setFloatProperty(id, area, value)) return true
        } catch (t: Throwable) {
            Log.w(TAG, "helper.setFloat $id area=$area: ${t.message}")
        }
        val pm = helper.propertyManager ?: return false
        return try {
            pm.setFloatProperty(id, area, value)
            Log.i(TAG, "force setFloat $id area=$area = $value")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "force setFloat $id area=$area value=$value: ${t.message}")
            false
        }
    }

    fun getInt(helper: VehiclePropertyHelper, id: Int, area: Int): Int {
        return try {
            helper.getIntProperty(id, area)
        } catch (t: Throwable) {
            Log.w(TAG, "getInt $id area=$area: ${t.message}")
            -1
        }
    }

    fun getBool(helper: VehiclePropertyHelper, id: Int, area: Int): Boolean {
        return try {
            helper.getBoolProperty(id, area)
        } catch (t: Throwable) {
            Log.w(TAG, "getBool $id area=$area: ${t.message}")
            false
        }
    }

    fun getFloat(helper: VehiclePropertyHelper, id: Int, area: Int): Float {
        return try {
            helper.getFloatProperty(id, area)
        } catch (t: Throwable) {
            Log.w(TAG, "getFloat $id area=$area: ${t.message}")
            -1f
        }
    }

    /**
     * Температура в дампе объявлена FLOAT по зонам 49/68, но getFloat кидает
     * IllegalArgumentException. Пробуем float, потом int.
     */
    fun getNumber(helper: VehiclePropertyHelper, id: Int, area: Int): Float {
        val f = getFloat(helper, id, area)
        if (f > 0f) return f
        val i = getInt(helper, id, area)
        if (i > 0) return i.toFloat()
        return -1f
    }

    fun setNumber(helper: VehiclePropertyHelper, id: Int, area: Int, value: Float): Boolean {
        if (setFloat(helper, id, area, value)) return true
        if (setInt(helper, id, area, value.roundToInt())) return true
        return false
    }

    fun setIntAreas(helper: VehiclePropertyHelper, id: Int, value: Int, areas: IntArray): Boolean {
        var ok = false
        for (area in areas) {
            if (setInt(helper, id, area, value)) ok = true
        }
        return ok
    }

    fun setBoolAreas(helper: VehiclePropertyHelper, id: Int, value: Boolean, areas: IntArray): Boolean {
        var ok = false
        for (area in areas) {
            if (setBool(helper, id, area, value)) ok = true
        }
        return ok
    }

    fun setNumberAreas(helper: VehiclePropertyHelper, id: Int, value: Float, areas: IntArray): Boolean {
        var ok = false
        for (area in areas) {
            if (setNumber(helper, id, area, value)) ok = true
        }
        return ok
    }
}