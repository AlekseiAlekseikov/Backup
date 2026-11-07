package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33j §4.5 — вычистка числового мусора из `persist.sys.hvacdigital.*`
 * (наследие 1.0.53: `temp_ext=9.5`, `exttemp=9.5`, `batt=71`).
 *
 * Тест фиксирует ГРАНИЦЫ: трогаем только свои ключи, легаси — в пустое,
 * числа в флагах — в `0`, и ничего больше.
 */
class PersistCleanerTest {

    @Test
    fun garbageDetection() {
        assertTrue(PersistCleaner.isGarbage("9.5"))
        assertTrue(PersistCleaner.isGarbage("71"))
        assertTrue(PersistCleaner.isGarbage("app"))
        assertFalse(PersistCleaner.isGarbage(""))
        assertFalse(PersistCleaner.isGarbage("0"))
        assertFalse(PersistCleaner.isGarbage("1"))
    }

    @Test
    fun scriptOnlyTouchesHvacDigitalProps() {
        val s = PersistCleaner.script()
        val props = Regex("getprop (persist\\.[a-z0-9_.]+)").findAll(s).map { it.groupValues[1] }.toSet()
        assertTrue("скрипт должен что-то чистить", props.isNotEmpty())
        for (p in props) {
            assertTrue("чужой persist трогать нельзя: $p", p.startsWith("persist.sys.hvacdigital."))
        }
    }

    @Test
    fun legacyKeyNormalizedAndFlagValuesResetToZero() {
        val s = PersistCleaner.script()
        assertTrue("легаси-ключ temp_ext обрабатывается", s.contains("persist.sys.hvacdigital.temp_ext"))
        // iter33m §3.2: третье имя наружней из getprop.txt — тоже только в 0, без удаления.
        assertTrue("третье имя ext_temp нормализуется", s.contains("persist.sys.hvacdigital.ext_temp"))
        assertTrue("все три имени — в LEGACY_PROPS",
            PersistCleaner.LEGACY_PROPS == listOf(
                "persist.sys.hvacdigital.temp_ext",
                "persist.sys.hvacdigital.ext_temp"))
        // iter33l §1.2: НЕ удаляем (эксперимент с именем пропа наружней ещё идёт) — только в 0.
        assertTrue("легаси — нормализация, не удаление", s.contains("reason=legacy_key_normalized"))
        assertFalse("пустое значение в temp_ext больше не пишем", s.contains("setprop persist.sys.hvacdigital.temp_ext \"\""))
        assertTrue("числа в флагах — в 0", s.contains("reason=numeric_not_flag"))
        assertTrue("setprop на 0 для флага", s.contains("setprop persist.sys.hvacdigital.batt 0"))
        // Флаги перечислены ровно как у референса (ah.java:17 / u5.java:27).
        assertEquals(
            listOf(HvacDigitalProps.PROP_TEMP, HvacDigitalProps.PROP_BATT, HvacDigitalProps.PROP_EXT),
            PersistCleaner.FLAG_PROPS
        )
    }
}
