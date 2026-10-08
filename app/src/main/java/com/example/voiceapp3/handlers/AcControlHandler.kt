package com.example.voiceapp3.handlers

import android.content.Context
import android.util.Log
import com.example.voiceapp3.IntentHandler
import com.example.voiceapp3.PredictionResult
import com.example.voiceapp3.car.Ex2Vehicle
import com.example.voiceapp3.car.HuClimate
import com.example.voiceapp3.car.VehiclePropertyHelper

/**
 * Климат EX2. Флаги HVAC — зона 117.
 *
 * Обогрев стёкол — две команды (лобовое / заднее), не одна кнопка.
 *   Как виджеты EX Sapo Central: лоб setBoolean(270535680, 117), зад setBoolean(354419988, 2).
 *   557884758=2 — статус, не команда. 557846566 — рециркуляция у Sapo, не заднее.
 *
 * «Обдув лобового стекла» / оттай — воздух на лобовое (MAX_DEFROST).
 * «Обдув на стекло» / «обдув на лобовое стекло» — только направление.
 *
 * «Быстрый обогрев» / «Быстрое охлаждение».
 * Кнопки ГУ — через Flyme HVAC (HuClimate), не VHAL-пресет.
 * VHAL остаётся запасным, чтобы климат всё же менялся.
 */
class AcControlHandler(
    val vehiclePropertyHelper: VehiclePropertyHelper,
    private val appContext: Context? = null,
) : IntentHandler {

    private val TAG = "AcControlHandler"

    /** Направление до «обдув лобового стекла», чтобы выключение вернуло как было. */
    private var savedFanDir: Int = -1

    /** 0 нет, 1 быстрый обогрев, 2 быстрое охлаждение. GET с ГУ может врать — дубль в памяти. */
    @Volatile private var quickMode: Int = 0

    override fun canHandle(intent: String): Boolean =
        intent == "ac_control" || intent == "hvac_defrost"

    override fun handle(prediction: PredictionResult): Boolean {
        val params = extractCommonEntities(prediction)
        val text = prediction.normalizedText.lowercase().replace('ё', 'е')
        Log.i(TAG, "intent=${prediction.intent} action=${params.action} text='$text'")
        val on = params.action != "unset"

        return when {
            isQuickCool(text) -> handleQuickClimate(heat = false, text = text, action = params.action)
            isQuickHeat(text) -> handleQuickClimate(heat = true, text = text, action = params.action)

            prediction.intent == "hvac_defrost" || isGlassCommand(text) ->
                handleGlass(text, on)

            isRecirc(text) -> setRecirc(wantRecircOn(text, params.action))

            isMaxAc(text) -> setMaxAc(on)

            isAuto(text) -> setAuto(on)

            params.action == "unset" -> turnOffAc()
            params.action == "set" -> turnOnAc(setAuto = !text.contains("кондиционер") || text.contains("авто"))
            else -> {
                Log.w(TAG, "нет действия для ac_control")
                false
            }
        }
    }

    private fun handleGlass(text: String, on: Boolean): Boolean {
        val spiral = text.contains("подогре") || text.contains("обогре") ||
                text.contains("спираль")
        val air = text.contains("оттай") ||
                (text.contains("обдув") && text.contains("лобов") && !text.contains(" на ")) ||
                (text.contains("макс") && (text.contains("стекл") || text.contains("лобов")))

        if (air && !spiral) return setAirDefrost(on)

        val rear = text.contains("задн") || text.contains("зеркал")
        val front = text.contains("лобов")
        return when {
            rear && !front -> setGlassHeat(front = false, rear = true, on = on)
            front && !rear -> setGlassHeat(front = true, rear = false, on = on)
            else -> setGlassHeat(front = true, rear = true, on = on)
        }
    }

    fun turnOnAc(setAuto: Boolean = true): Boolean {
        Log.i(TAG, "AC ON auto=$setAuto")
        Ex2Vehicle.setBool(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_POWER_ON, Ex2Vehicle.HVAC_POWER_AREA, true
        )
        val ac = Ex2Vehicle.setBool(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_AC_ON, Ex2Vehicle.HVAC_AREA, true
        )
        if (setAuto) {
            Ex2Vehicle.setBool(
                vehiclePropertyHelper, Ex2Vehicle.HVAC_AUTO_ON, Ex2Vehicle.HVAC_AREA, true
            )
            Ex2Vehicle.setBool(
                vehiclePropertyHelper, Ex2Vehicle.HVAC_AUTO_ON, Ex2Vehicle.TEMP_ROW1_LEFT, true
            )
        }
        return ac
    }

    fun turnOffAc(): Boolean {
        Log.i(TAG, "AC OFF")
        val ac = Ex2Vehicle.setBool(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_AC_ON, Ex2Vehicle.HVAC_AREA, false
        )
        Ex2Vehicle.setBool(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_AUTO_ON, Ex2Vehicle.HVAC_AREA, false
        )
        return ac
    }

    fun setAcDirection(direction: Int): Boolean {
        Log.i(TAG, "fan direction=$direction area=${Ex2Vehicle.HVAC_AREA}")
        val a = Ex2Vehicle.setInt(
            vehiclePropertyHelper,
            Ex2Vehicle.HVAC_FAN_DIRECTION,
            Ex2Vehicle.HVAC_AREA,
            direction
        )
        if (a) return true
        return Ex2Vehicle.setInt(
            vehiclePropertyHelper,
            Ex2Vehicle.HVAC_FAN_DIRECTION,
            Ex2Vehicle.TEMP_ROW1_LEFT,
            direction
        )
    }

    private fun setAuto(on: Boolean): Boolean {
        Log.i(TAG, "AUTO=$on")
        if (on) {
            Ex2Vehicle.setBool(
                vehiclePropertyHelper, Ex2Vehicle.HVAC_AC_ON, Ex2Vehicle.HVAC_AREA, true
            )
        }
        val a = Ex2Vehicle.setBool(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_AUTO_ON, Ex2Vehicle.HVAC_AREA, on
        )
        val b = Ex2Vehicle.setBool(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_AUTO_ON, Ex2Vehicle.TEMP_ROW1_LEFT, on
        )
        return a || b
    }

    private fun setRecirc(on: Boolean): Boolean {
        Log.i(TAG, "RECIRC=$on")
        return Ex2Vehicle.setBool(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_RECIRC_ON, Ex2Vehicle.HVAC_AREA, on
        )
    }

    private fun setMaxAc(on: Boolean): Boolean {
        Log.i(TAG, "MAX_AC=$on")
        if (on) {
            Ex2Vehicle.setBool(
                vehiclePropertyHelper, Ex2Vehicle.HVAC_AC_ON, Ex2Vehicle.HVAC_AREA, true
            )
        }
        return Ex2Vehicle.setBool(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_MAX_AC_ON, Ex2Vehicle.HVAC_AREA, on
        )
    }

    /**
     * Спираль — как виджеты EX Sapo Central, не статус 557884758.
     * Лоб: 270535680 зона 117. Зад: 354419988 зона 2.
     * Запас: AOSP ELECTRIC_DEFROSTER / DEFROSTER зона 1 или 2.
     * GET не проверяем: sapo-id в дампе нет, 758=2 не значит «греет».
     */
    private fun setGlassHeat(front: Boolean, rear: Boolean, on: Boolean): Boolean {
        if (!front && !rear) return false
        Log.i(TAG, "GLASS_HEAT front=$front rear=$rear on=$on")
        var ok = false
        if (front) {
            val sapo = Ex2Vehicle.setBool(
                vehiclePropertyHelper, Ex2Vehicle.WINDSHIELD_HEAT, Ex2Vehicle.HVAC_AREA, on
            )
            val el = Ex2Vehicle.setBool(
                vehiclePropertyHelper,
                Ex2Vehicle.HVAC_ELECTRIC_DEFROSTER, Ex2Vehicle.WINDSHIELD_FRONT, on
            )
            val df = Ex2Vehicle.setBool(
                vehiclePropertyHelper,
                Ex2Vehicle.HVAC_DEFROSTER, Ex2Vehicle.WINDSHIELD_FRONT, on
            )
            Log.i(TAG, "front sapo=$sapo electric=$el defrost=$df")
            ok = sapo || el || df || ok
        }
        if (rear) {
            val sapo = Ex2Vehicle.setBool(
                vehiclePropertyHelper, Ex2Vehicle.REAR_DEFROST, Ex2Vehicle.WINDSHIELD_REAR, on
            )
            val el = Ex2Vehicle.setBool(
                vehiclePropertyHelper,
                Ex2Vehicle.HVAC_ELECTRIC_DEFROSTER, Ex2Vehicle.WINDSHIELD_REAR, on
            )
            val df = Ex2Vehicle.setBool(
                vehiclePropertyHelper,
                Ex2Vehicle.HVAC_DEFROSTER, Ex2Vehicle.WINDSHIELD_REAR, on
            )
            Log.i(TAG, "rear sapo=$sapo electric=$el defrost=$df")
            ok = sapo || el || df || ok
        }
        return ok
    }

    /** «Обдув лобового стекла» / оттай. Не спираль и не «обдув на стекло». */
    private fun setAirDefrost(on: Boolean): Boolean {
        Log.i(TAG, "AIR_DEFROST=$on savedDir=$savedFanDir")
        if (on) {
            val cur = Ex2Vehicle.getInt(
                vehiclePropertyHelper, Ex2Vehicle.HVAC_FAN_DIRECTION, Ex2Vehicle.HVAC_AREA
            )
            if (cur > 0 && cur != 4) savedFanDir = cur
            else if (savedFanDir <= 0) savedFanDir = 1
            Ex2Vehicle.setBool(
                vehiclePropertyHelper, Ex2Vehicle.HVAC_DEFROSTER, Ex2Vehicle.WINDSHIELD_FRONT, true
            )
            val max117 = Ex2Vehicle.setBool(
                vehiclePropertyHelper, Ex2Vehicle.HVAC_MAX_DEFROST_ON, Ex2Vehicle.HVAC_AREA, true
            )
            val max1 = Ex2Vehicle.setBool(
                vehiclePropertyHelper,
                Ex2Vehicle.HVAC_MAX_DEFROST_ON, Ex2Vehicle.WINDSHIELD_FRONT, true
            )
            val dir = setAcDirection(4)
            Log.i(TAG, "AIR_DEFROST on max117=$max117 max1=$max1 dir=$dir")
            return max117 || max1 || dir
        }
        Ex2Vehicle.setBool(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_MAX_DEFROST_ON, Ex2Vehicle.HVAC_AREA, false
        )
        Ex2Vehicle.setBool(
            vehiclePropertyHelper,
            Ex2Vehicle.HVAC_MAX_DEFROST_ON, Ex2Vehicle.WINDSHIELD_FRONT, false
        )
        Ex2Vehicle.setBool(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_DEFROSTER, Ex2Vehicle.WINDSHIELD_FRONT, false
        )
        val ac = turnOffAc()
        val back = if (savedFanDir > 0) savedFanDir else 1
        val dir = setAcDirection(back)
        savedFanDir = -1
        Log.i(TAG, "AIR_DEFROST off ac=$ac dir=$dir back=$back")
        return true
    }

    /**
     * Сначала кнопка ГУ (Flyme HVAC), потом VHAL.
     * Иначе ГУ видит ручную сборку климата и не зажигает пресет.
     */
    private fun handleQuickClimate(heat: Boolean, text: String, action: String?): Boolean {
        val inHeat = isQuickHeatOn()
        val inCool = isQuickCoolOn()
        val wantOff = isQuickOff(text, action)
        val wantOn = isQuickOn(text, action) && !wantOff
        val targetOn = when {
            wantOff -> false
            wantOn -> true
            heat && inHeat -> false
            !heat && inCool -> false
            else -> true
        }
        Log.i(TAG, "QUICK heat=$heat wantOff=$wantOff wantOn=$wantOn inHeat=$inHeat inCool=$inCool -> on=$targetOn")
        if (!targetOn) return exitQuickClimate()
        if (heat && inHeat) {
            Log.i(TAG, "QUICK_HEAT уже включён — не перезапускаю")
            return true
        }
        if (!heat && inCool) {
            Log.i(TAG, "QUICK_COOL уже включён — не перезапускаю")
            return true
        }
        return if (heat) enterQuickHeat() else enterQuickCool()
    }

    private fun isQuickOff(text: String, action: String?): Boolean =
        action == "unset" ||
                text.contains("выключ") || text.contains("отключ") ||
                text.contains("убери") || text.contains("останови") ||
                text.contains("прекрати")

    private fun isQuickOn(text: String, action: String?): Boolean =
        action == "set" || text.contains("включ") || text.contains("запус")

    private fun isQuickHeatOn(): Boolean {
        if (quickMode == 1) return true
        if (quickMode == 2) return false
        return Ex2Vehicle.getInt(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_HEAT_MODE, 0
        ) == Ex2Vehicle.HVAC_HEAT_MODE_HEAT
    }

    private fun isQuickCoolOn(): Boolean {
        if (quickMode == 2) return true
        if (quickMode == 1) return false
        if (Ex2Vehicle.getBool(
                vehiclePropertyHelper, Ex2Vehicle.HVAC_MAX_AC_ON, Ex2Vehicle.HVAC_AREA
            )
        ) return true
        return Ex2Vehicle.getBool(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_MAX_AC_ON, Ex2Vehicle.TEMP_ROW1_LEFT
        )
    }

    private fun writeSeats(level: Int) {
        Ex2Vehicle.setInt(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_SEAT_TEMPERATURE, Ex2Vehicle.SEAT_DRIVER, level
        )
        Ex2Vehicle.setInt(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_SEAT_TEMPERATURE, Ex2Vehicle.SEAT_PASSENGER, level
        )
        Ex2Vehicle.setInt(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_SEAT_HEAT_VENDOR, Ex2Vehicle.SEAT_DRIVER, level
        )
        Ex2Vehicle.setInt(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_SEAT_HEAT_VENDOR, Ex2Vehicle.SEAT_PASSENGER, level
        )
    }

    private fun writeVendorTemp(celsius: Float) {
        val v = celsius.coerceIn(Ex2Vehicle.TEMP_MIN, Ex2Vehicle.TEMP_MAX)
        Ex2Vehicle.setNumber(vehiclePropertyHelper, Ex2Vehicle.HVAC_VENDOR_TEMP, Ex2Vehicle.SEAT_DRIVER, v)
        Ex2Vehicle.setNumber(vehiclePropertyHelper, Ex2Vehicle.HVAC_VENDOR_TEMP, Ex2Vehicle.SEAT_PASSENGER, v)
    }

    private fun writePower(on: Boolean) {
        Ex2Vehicle.setBool(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_POWER_ON, Ex2Vehicle.HVAC_POWER_AREA, on
        )
        Ex2Vehicle.setBool(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_POWER_ON, Ex2Vehicle.HVAC_AREA, on
        )
    }

    private fun writeFan(speed: Int): Boolean {
        val aosp = Ex2Vehicle.setIntAreas(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_FAN_SPEED, speed, Ex2Vehicle.FAN_AREAS
        )
        Ex2Vehicle.setInt(vehiclePropertyHelper, Ex2Vehicle.HVAC_FAN_SPEED_VENDOR, 0, speed)
        return aosp
    }

    private fun writeDir(direction: Int): Boolean {
        val aosp = Ex2Vehicle.setIntAreas(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_FAN_DIRECTION, direction, Ex2Vehicle.DIR_AREAS
        )
        Ex2Vehicle.setInt(vehiclePropertyHelper, Ex2Vehicle.HVAC_FAN_DIR_VENDOR, 0, direction)
        return aosp
    }

    private fun writeAuto(on: Boolean) {
        Ex2Vehicle.setBoolAreas(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_AUTO_ON, on, Ex2Vehicle.BOOL_AREAS
        )
    }

    private fun writeMaxAc(on: Boolean): Boolean =
        Ex2Vehicle.setBoolAreas(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_MAX_AC_ON, on, Ex2Vehicle.BOOL_AREAS
        )

    private fun writeAc(on: Boolean): Boolean =
        Ex2Vehicle.setBoolAreas(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_AC_ON, on, Ex2Vehicle.BOOL_AREAS
        )

    private fun writeRecirc(on: Boolean): Boolean =
        Ex2Vehicle.setBoolAreas(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_RECIRC_ON, on, Ex2Vehicle.BOOL_AREAS
        )

    private fun writeHeatMode(heat: Boolean): Boolean =
        Ex2Vehicle.setInt(
            vehiclePropertyHelper,
            Ex2Vehicle.HVAC_HEAT_MODE,
            0,
            if (heat) Ex2Vehicle.HVAC_HEAT_MODE_HEAT else Ex2Vehicle.HVAC_HEAT_MODE_COOL
        )

    private fun clickHu(heat: Boolean, on: Boolean): Boolean {
        val ctx = appContext ?: return false
        return try {
            HuClimate.setQuick(ctx, heat, on)
        } catch (t: Throwable) {
            Log.w(TAG, "HU click heat=$heat on=$on: ${t.message}")
            false
        }
    }

    private fun exitQuickClimate(): Boolean {
        Log.i(TAG, "QUICK off")
        when (quickMode) {
            1 -> clickHu(heat = true, on = false)
            2 -> clickHu(heat = false, on = false)
            else -> {
                clickHu(heat = true, on = false)
                clickHu(heat = false, on = false)
            }
        }
        writeMaxAc(false)
        writeHeatMode(heat = false)
        writeRecirc(false)
        writeSeats(0)
        writeAuto(true)
        quickMode = 0
        return true
    }

    private fun enterQuickHeat(): Boolean {
        Log.i(TAG, "QUICK_HEAT on")
        val hu = clickHu(heat = true, on = true)
        writePower(true)
        val mode = writeHeatMode(heat = true)
        writeAuto(false)
        writeAc(false)
        writeMaxAc(false)
        writeRecirc(false)
        val fan = writeFan(Ex2Vehicle.FAN_MAX)
        val dir = writeDir(2)
        writeSeats(3)
        writeVendorTemp(Ex2Vehicle.TEMP_MAX)
        writeTemp(Ex2Vehicle.TEMP_MAX)
        quickMode = 1
        Log.i(TAG, "QUICK_HEAT hu=$hu fan=$fan dir=$dir mode=$mode")
        return hu || mode || fan || dir
    }

    private fun enterQuickCool(): Boolean {
        Log.i(TAG, "QUICK_COOL on")
        val hu = clickHu(heat = false, on = true)
        writePower(true)
        val maxAc = writeMaxAc(true)
        writeHeatMode(heat = false)
        writeAuto(false)
        val ac = writeAc(true)
        val rec = writeRecirc(true)
        val fan = writeFan(Ex2Vehicle.FAN_MAX)
        val dir = writeDir(1)
        writeSeats(0)
        writeVendorTemp(Ex2Vehicle.TEMP_MIN)
        writeTemp(Ex2Vehicle.TEMP_MIN)
        quickMode = 2
        Log.i(TAG, "QUICK_COOL hu=$hu ac=$ac max=$maxAc rec=$rec fan=$fan dir=$dir")
        return hu || maxAc || ac || rec || fan || dir
    }

    private fun writeTemp(value: Float) {
        Ex2Vehicle.setNumberAreas(
            vehiclePropertyHelper, Ex2Vehicle.HVAC_TEMPERATURE_SET, value, Ex2Vehicle.TEMP_AREAS
        )
    }

    private fun isQuickHeat(text: String): Boolean {
        if (!text.contains("быстр")) return false
        if (text.contains("охлажд")) return false
        if (text.contains("сиден") || text.contains("стекл") || text.contains("лобов") ||
            text.contains("задн") || text.contains("рул") || text.contains("зеркал")
        ) return false
        return text.contains("обогрев") || text.contains("нагрев")
    }

    private fun isQuickCool(text: String): Boolean {
        if (!text.contains("быстр")) return false
        return text.contains("охлажд")
    }

    private fun isGlassCommand(text: String): Boolean {
        if (text.contains("обдув") && text.contains(" на ") &&
            (text.contains("стекл") || text.contains("лобов"))
        ) return false
        if (text.contains("обдув") && !text.contains("лобов") &&
            !text.contains("макс") && !text.contains("оттай") &&
            !text.contains("подогре") && !text.contains("обогре") && !text.contains("спираль")
        ) return false
        if (text.contains("оттай") || (text.contains("лобов") && !text.contains(" на "))) return true
        if (text.contains("стекл") && text.contains("макс")) return true
        val heat = text.contains("подогре") || text.contains("обогре") ||
                text.contains("нагрей") || text.contains("обогрей") ||
                text.contains("спираль")
        val glass = text.contains("стекл") || text.contains("зеркал")
        return heat && glass
    }

    private fun isRecirc(text: String): Boolean =
        text.contains("рециркул") || text.contains("салонн воздух") ||
                text.contains("с улиц") || text.contains("свеж воздух") ||
                text.contains("наружн воздух")

    private fun wantRecircOn(text: String, action: String?): Boolean = when {
        text.contains("улиц") || text.contains("свеж") || text.contains("наружн") -> false
        text.contains("салон") || text.contains("рециркул") -> action != "unset"
        else -> action != "unset"
    }

    private fun isMaxAc(text: String): Boolean =
        text.contains("макс") && (text.contains("холод") || text.contains("конд"))

    private fun isAuto(text: String): Boolean =
        text.contains("авто") && !text.contains("аварий")
}