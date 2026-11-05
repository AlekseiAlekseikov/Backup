package com.example.voiceapp3.tools

import com.example.voiceapp3.tools.HvacDigitalPlugin.BootGate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * iter33h B2.3 — гейт boot-установки плагина по тегу загрузки (3 обязательных теста).
 * Смысл: «одна попытка на загрузку» больше не опирается на `prev_uptime_ms`
 * (в 1.0.53 он жил 5 минут и глушил повтор после реального возврата root).
 */
class BootGateTest {

    // 1. Другая загрузка (или пустой тег) — попытку разрешаем.
    @Test
    fun otherBootRuns() {
        assertEquals(BootGate.RUN, HvacDigitalPlugin.bootGateDecision("boot-A", "boot-B", "OK"))
        assertEquals(BootGate.RUN, HvacDigitalPlugin.bootGateDecision("", "boot-B", "OK"))
        assertEquals(BootGate.RUN, HvacDigitalPlugin.bootGateDecision("boot-B", "", "OK"))
    }

    // 2. Та же загрузка и результат уже есть — повтор не нужен.
    @Test
    fun sameBootWithResultSkips() {
        assertEquals(BootGate.SKIP, HvacDigitalPlugin.bootGateDecision("boot-A", "boot-A", "OK"))
        assertEquals(BootGate.SKIP, HvacDigitalPlugin.bootGateDecision("boot-A", " boot-A ", "FAILED"))
    }

    // 3. Та же загрузка, но прошлая попытка провалилась ИЗ-ЗА ОТСУТСТВИЯ ROOT —
    //    после возврата root попытку надо повторить (иначе «нет root-канала» навсегда).
    @Test
    fun rootFailRunsAgain() {
        assertEquals(BootGate.RUN, HvacDigitalPlugin.bootGateDecision("boot-A", "boot-A", "ROOT_FAIL"))
        assertEquals(BootGate.RUN, HvacDigitalPlugin.bootGateDecision("boot-A", "boot-A", ""))
    }
}
