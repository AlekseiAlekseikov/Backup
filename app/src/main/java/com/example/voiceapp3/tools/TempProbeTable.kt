package com.example.voiceapp3.tools

/**
 * iter33i P1 — таблица «Температуры: разведка» БЕЗ выдуманных ID.
 *
 * Источники (в порядке приоритета):
 *  1. `app/src/main/assets/ex2_carprops.csv` — эталон имён/hex этой машины (ассет проекта);
 *  2. VHAL-дамп `uploads/vhal-dump.txt` — какие из них реально есть в прошивке;
 *  3. референс CentralEXAuto 3.3.9 (`com.geely.hvacstepperv2.TempStepperService`) — зоны чтения.
 *
 * Имя в `name=` пишется ровно как в CSV (мандат §3). `err=no_id` больше не существует:
 * для всех строк ID известен, `err=` — только результат чтения (`none`, `none(int)`, имя исключения).
 */
internal data class TempProbeEntry(
    /** hex в верхнем регистре, как в CSV, с префиксом 0x. */
    val hex: String,
    /** id в десятичном виде — ровно то, что уходит в CarPropertyManager. */
    val propId: Int,
    /** имя свойства: CSV (эталон машины) либо AOSP-имя для ENV_OUTSIDE_TEMPERATURE. */
    val name: String,
    /** зоны чтения — порядок важен (у референса он тоже фиксированный). */
    val areas: IntArray,
    /** какой getter пробовать первым: true → getFloatProperty, false → getIntProperty. */
    val floatFirst: Boolean,
    /** источник ID и зон — печатается в отчёте, в лог не идёт. */
    val source: String,
)

/**
 * Полный список разведки. Порядок — от «наружная → салон → уставка».
 *
 * `HVAC_TEMPERATURE_CURRENT` (`0x15600502`) и `AC_INSIDE_TEMP` (`0x2140A379`) — РАЗНЫЕ свойства:
 * первое читает референс (`TempStepperService`), второе — имя из CSV этой машины. Подменять
 * одно другим запрещено (мандат §3).
 *
 * `HVAC_TEMPERATURE_SET` (`0x15600503`) читается по трём зонам: плагин берёт `getAreaId() → 1`
 * (`HvacVehicleTempWidget.java:556`), а дамп перечисляет `areas=[49, 68]` — нужна полная картина.
 */
internal val TEMP_PROBE_TABLE: List<TempProbeEntry> = listOf(
    TempProbeEntry(
        hex = "0x11600703", propId = 0x11600703, name = "ENV_OUTSIDE_TEMPERATURE",
        areas = intArrayOf(0, 1, 4, 7), floatFirst = true,
        source = "ex2_carprops.csv:45 + vhal-dump (FLOAT, areas=[0]) + TempStepperService.f1286d0={0,1,4,7}",
    ),
    TempProbeEntry(
        hex = "0x2140A377", propId = 0x2140A377, name = "AC_AMBIENT_TEMP",
        areas = intArrayOf(0), floatFirst = false,
        source = "ex2_carprops.csv:985 + vhal-dump (INT32, 0=95)",
    ),
    TempProbeEntry(
        hex = "0x2140A376", propId = 0x2140A376, name = "AC_AMBIENT_TEMP_INVALID",
        areas = intArrayOf(0), floatFirst = false,
        source = "ex2_carprops.csv:984 + vhal-dump (INT32, 0=1)",
    ),
    TempProbeEntry(
        hex = "0x2140A379", propId = 0x2140A379, name = "AC_INSIDE_TEMP",
        areas = intArrayOf(0), floatFirst = false,
        source = "ex2_carprops.csv:987 + vhal-dump (INT32, 0=0)",
    ),
    TempProbeEntry(
        hex = "0x2140A378", propId = 0x2140A378, name = "AC_INSIDE_TEMP_INVALID",
        areas = intArrayOf(0), floatFirst = false,
        source = "ex2_carprops.csv:986 + vhal-dump (INT32, 0=0)",
    ),
    TempProbeEntry(
        hex = "0x15600502", propId = 0x15600502, name = "HVAC_TEMPERATURE_CURRENT",
        areas = intArrayOf(1, 4, 0, 7), floatFirst = true,
        source = "TempStepperService.i(int)/g() («Sensor cabina OK (HVAC_TEMPERATURE_CURRENT) area=0x%x»)",
    ),
    TempProbeEntry(
        hex = "0x15600503", propId = 0x15600503, name = "ZONED_TEMP_SETPOINT",
        areas = intArrayOf(1, 49, 68), floatFirst = true,
        source = "ex2_carprops.csv:75 + vhal-dump (areas=[49,68]) + HvacVehicleTempWidget.getAreaId()→1",
    ),
    TempProbeEntry(
        hex = "0x2140101D", propId = 0x2140101D, name = "HVAC_IN_OUT_TEMP",
        areas = intArrayOf(0), floatFirst = false,
        source = "ex2_carprops.csv:124 + vhal-dump (INT32, 0=0)",
    ),
    TempProbeEntry(
        hex = "0x21404515", propId = 0x21404515, name = "HU_ENVIRONMENTAL_TEMP",
        areas = intArrayOf(0), floatFirst = false,
        source = "ex2_carprops.csv:453 + vhal-dump (INT32, 0=0)",
    ),
    TempProbeEntry(
        hex = "0x2140A267", propId = 0x2140A267, name = "TEMPERATURE_STATE",
        areas = intArrayOf(0), floatFirst = false,
        source = "ex2_carprops.csv:876 + vhal-dump (INT32, 0=0)",
    ),
)

/** Формат строки разведки — один на все свойства (покрыт `TempProbeTableTest`). */
internal fun tempProbeLine(hex: String, name: String, area: Int, value: String, err: String): String =
    "TEMP_PROBE hex=$hex name=$name area=0x%x value=$value err=$err".format(area)
