package com.example.voiceapp3.tools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33i P1 — таблица «Температуры: разведка»: hex ↔ id ↔ имя.
 *
 * Эталон имён — ассет проекта `app/src/main/assets/ex2_carprops.csv` (рабочий каталог юнит-теста
 * — модуль `app`, поэтому путь относительный). Тест ловит три типовые ошибки:
 *  • выдуманный/опечатанный hex (не совпадает с CSV для этого имени);
 *  • `no_id`-строки (у каждого свойства обязан быть ID);
 *  • подмену `AC_INSIDE_TEMP` строкой `HVAC_TEMPERATURE_CURRENT` и обратно.
 */
class TempProbeTableTest {

    private val csv: Map<String, Int> by lazy {
        val f = File("src/main/assets/ex2_carprops.csv")
        assertTrue("эталон имён не найден: ${f.absolutePath}", f.isFile)
        f.readLines()
            .mapNotNull { line ->
                val p = line.trim().split(',')
                if (p.size < 2) null else p[1].trim().uppercase() to p[0].trim().toInt(16)
            }
            .toMap()
    }

    @Test
    fun everyEntryHasKnownIdAndCsvName() {
        assertTrue("таблица не должна быть пустой", TEMP_PROBE_TABLE.isNotEmpty())
        for (e in TEMP_PROBE_TABLE) {
            // hex в таблице ↔ propId
            assertEquals("hex↔id у ${e.name}", e.propId, e.hex.removePrefix("0x").toInt(16))
            // propId ↔ CSV (кроме свойства, которого в CSV этой машины нет)
            val csvId = csv[e.name]
            if (csvId != null) {
                assertEquals("id↔CSV у ${e.name}", csvId, e.propId)
            }
            assertTrue("${e.name}: нужна хотя бы одна зона", e.areas.isNotEmpty())
        }
    }

    @Test
    fun noDuplicatesAndNoPlaceholders() {
        val names = TEMP_PROBE_TABLE.map { it.name }
        assertEquals("имена уникальны", names.size, names.toSet().size)
        for (e in TEMP_PROBE_TABLE) {
            assertTrue("${e.name}: ID не может быть 0", e.propId != 0)
            assertTrue("${e.name}: hex должен быть 0x…", e.hex.startsWith("0x"))
        }
    }

    @Test
    fun acrossTempAndCurrentTempAreSeparateRows() {
        val inside = TEMP_PROBE_TABLE.single { it.name == "AC_INSIDE_TEMP" }
        val current = TEMP_PROBE_TABLE.single { it.name == "HVAC_TEMPERATURE_CURRENT" }
        assertEquals("AC_INSIDE_TEMP (CSV:987)", 0x2140A379, inside.propId)
        assertEquals("HVAC_TEMPERATURE_CURRENT (референс)", 0x15600502, current.propId)
        assertTrue("это разные строки", inside.propId != current.propId)
    }

    @Test
    fun setpointReadByThreeAreasFromReferenceAndDump() {
        val setpoint = TEMP_PROBE_TABLE.single { it.name == "ZONED_TEMP_SETPOINT" }
        assertEquals(0x15600503, setpoint.propId)
        // 1 — плагин (getAreaId()→1), 49 и 68 — VHAL-дамп.
        assertEquals(listOf(1, 49, 68), setpoint.areas.toList())
    }

    @Test
    fun outsideTempUsesReferenceAreas() {
        val outside = TEMP_PROBE_TABLE.single { it.name == "ENV_OUTSIDE_TEMPERATURE" }
        assertEquals(0x11600703, outside.propId)
        // TempStepperService.f1286d0 = {0, 1, 4, 7}
        assertEquals(listOf(0, 1, 4, 7), outside.areas.toList())
        assertTrue("наружная — float-свойство", outside.floatFirst)
    }

    @Test
    fun lineFormatHasNoNoId() {
        val line = tempProbeLine("0x2140A379", "AC_INSIDE_TEMP", 0, "0", "none")
        assertEquals("TEMP_PROBE hex=0x2140A379 name=AC_INSIDE_TEMP area=0x0 value=0 err=none", line)
        assertTrue("err=no_id больше не существует", !line.contains("no_id"))
        val withErr = tempProbeLine("0x15600503", "ZONED_TEMP_SETPOINT", 68, "н/д", "IllegalArgumentException")
        assertEquals("TEMP_PROBE hex=0x15600503 name=ZONED_TEMP_SETPOINT area=0x44 value=н/д err=IllegalArgumentException",
            withErr)
    }

    @Test
    fun csvIdsForMandateRows() {
        // Прямая сверка с мандатом §3 (строки 124 / 453 / 876 / 986 / 987 CSV).
        assertEquals(0x2140101D, csv["HVAC_IN_OUT_TEMP"])
        assertEquals(0x21404515, csv["HU_ENVIRONMENTAL_TEMP"])
        assertEquals(0x2140A267, csv["TEMPERATURE_STATE"])
        assertEquals(0x2140A378, csv["AC_INSIDE_TEMP_INVALID"])
        assertEquals(0x2140A379, csv["AC_INSIDE_TEMP"])
        assertEquals(0x11600703, csv["ENV_OUTSIDE_TEMPERATURE"])
        assertEquals(0x15600503, csv["ZONED_TEMP_SETPOINT"])
    }
}
