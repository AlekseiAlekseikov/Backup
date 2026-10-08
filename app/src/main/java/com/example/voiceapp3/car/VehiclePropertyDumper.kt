package com.example.voiceapp3.car

import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyConfig
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Вычитывает с ГУ все свойства VHAL.
 * Файл: Android/data/com.example.voiceapp3/files/vhal-dump.txt
 */
object VehiclePropertyDumper {

    private const val TAG = "VhalDump"

    fun dump(context: Context, helper: VehiclePropertyHelper): File? {
        val pm = helper.propertyManager
        if (pm == null) {
            Log.e(TAG, "propertyManager=null — Car ещё не подключён")
            return null
        }

        val lines = ArrayList<String>(512)
        lines += "=== VHAL dump ==="
        lines += "time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}"
        lines += "model=EX2 ihu=IHU629G"
        lines += "sdk=${Build.VERSION.SDK_INT}"
        lines += ""

        val aospNames = aospNameMap()
        val list = try {
            pm.propertyList ?: emptyList()
        } catch (t: Throwable) {
            Log.e(TAG, "propertyList упал", t)
            lines += "propertyList FAILED: ${t.message}"
            return write(context, lines)
        }

        lines += "count=${list.size}"
        lines += ""

        val sorted = list.sortedBy { it.propertyId }
        for (cfg in sorted) {
            lines += formatOne(pm, cfg, aospNames)
        }

        val file = write(context, lines)
        Log.i(TAG, "Дамп ${list.size} свойств → ${file?.absolutePath}")
        return file
    }

    private fun formatOne(
        pm: CarPropertyManager,
        cfg: CarPropertyConfig<*>,
        aospNames: Map<Int, String>
    ): String {
        val id = cfg.propertyId
        val name = aospNames[id] ?: vendorHint(id)
        val areas = try {
            cfg.areaIds?.toList() ?: listOf(0)
        } catch (_: Throwable) {
            listOf(0)
        }
        val access = try {
            when (cfg.access) {
                CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ -> "READ"
                CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_WRITE -> "WRITE"
                CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE -> "RW"
                else -> "access=${cfg.access}"
            }
        } catch (_: Throwable) {
            "?"
        }
        val change = try { cfg.changeMode } catch (_: Throwable) { -1 }
        val values = areas.joinToString(",") { area ->
            "$area=${readValue(pm, id, area)}"
        }
        return "id=$id hex=0x${id.toString(16).uppercase()} $name " +
                "${decode(id)} access=$access change=$change areas=$areas values={$values}"
    }

    private fun readValue(pm: CarPropertyManager, id: Int, area: Int): String {
        val type = id and 0x00FF0000
        return try {
            when (type) {
                0x00200000 -> pm.getBooleanProperty(id, area).toString()
                0x00400000 -> pm.getIntProperty(id, area).toString()
                0x00600000 -> pm.getFloatProperty(id, area).toString()
                else -> "type"
            }
        } catch (t: Throwable) {
            t.javaClass.simpleName
        }
    }

    private fun decode(id: Int): String {
        val group = when (id.toLong() and 0xF0000000L) {
            0x10000000L -> "SYSTEM"
            0x20000000L -> "VENDOR"
            else -> "GROUP?"
        }
        val area = when (id and 0x0F000000) {
            0x01000000 -> "GLOBAL"
            0x02000000 -> "WHEEL"
            0x03000000 -> "WINDOW"
            0x04000000 -> "MIRROR"
            0x05000000 -> "SEAT"
            0x06000000 -> "DOOR"
            else -> "AREA?"
        }
        val type = when (id and 0x00FF0000) {
            0x00100000 -> "STRING"
            0x00200000 -> "BOOLEAN"
            0x00400000 -> "INT32"
            0x00410000 -> "INT32_VEC"
            0x00500000 -> "INT64"
            0x00600000 -> "FLOAT"
            0x00610000 -> "FLOAT_VEC"
            0x00700000 -> "BYTES"
            else -> "TYPE?"
        }
        return "[$group|$area|$type|low=0x${(id and 0xFFFF).toString(16)}]"
    }

    private fun vendorHint(id: Int): String = when (id) {
        Ex2Vehicle.DRIVE_MODE -> "DRIVE_MODE"
        Ex2Vehicle.WINDOW_POS -> "WINDOW_POS_VENDOR"
        Ex2Vehicle.STEERING_WHEEL_HEAT -> "STEERING_WHEEL_HEAT"
        Ex2Vehicle.HVAC_ELECTRIC_DEFROSTER -> "HVAC_ELECTRIC_DEFROSTER"
        else -> "-"
    }

    private fun aospNameMap(): Map<Int, String> {
        val out = HashMap<Int, String>()
        try {
            for (f in VehiclePropertyIds::class.java.fields) {
                if (f.type == Int::class.javaPrimitiveType) {
                    runCatching { out[f.getInt(null)] = f.name }
                }
            }
        } catch (_: Throwable) {}
        return out
    }

    private fun write(context: Context, lines: List<String>): File? {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "vhal-dump.txt")
        return try {
            file.writeText(lines.joinToString("\n"))
            file
        } catch (t: Throwable) {
            Log.e(TAG, "не записал ${file.absolutePath}", t)
            null
        }
    }
}