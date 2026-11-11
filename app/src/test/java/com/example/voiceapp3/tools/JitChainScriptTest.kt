package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Реальный текст цепочки исполняется настоящим `/bin/sh` на стенде [JitIsoStand].
 *
 * Проверяется ФАКТИЧЕСКИЙ порядок команд, поведение цикла слива правил, отсутствие запрещённых
 * действий и полное совпадение с литералом референса (шаг монтирований исполняется).
 */
class JitChainScriptTest {

    @Test
    fun orderIsReferenceOrder() {
        val stand = JitIsoStand(rules = 2)
        val (rc, _) = stand.exec(JitRef.chainScript(want = true))
        assertEquals(0, rc)
        val calls = stand.calls()
        assertEquals("первая команда — выключение Wi-Fi", "svc wifi disable", calls.first())
        assertEquals("последняя — один рестарт zygote", "setprop ctl.restart zygote", calls.last())
        assertEquals(1, calls.count { it == "setprop ctl.restart zygote" })
        assertEquals(1, calls.count { it == "setprop dalvik.vm.usejit true" })
        assertTrue(
            "свойство выставляется ПЕРЕД рестартом",
            calls.indexOf("setprop dalvik.vm.usejit true") < calls.indexOf("setprop ctl.restart zygote")
        )
        assertEquals("слив правил до нуля", 3, calls.count { it == "ip rule show" })
        assertEquals("пауза 1 с между попытками", 2, calls.count { it == "sleep 1" })
        assertTrue("шаг монтирований исполняется, как в референсе",
            calls.any { it.startsWith("umount -l ") })
        assertTrue("и он до setprop",
            calls.indexOfFirst { it.startsWith("umount") } <
                calls.indexOf("setprop dalvik.vm.usejit true"))
    }

    @Test
    fun drainStopsWhenNoRules() {
        val stand = JitIsoStand(rules = 0)
        stand.exec(JitRef.chainScript(want = true))
        assertEquals(1, stand.calls().count { it == "ip rule show" })
        assertEquals(0, stand.calls().count { it.startsWith("sleep") })
    }

    @Test
    fun drainGivesUpAfterTwentySeconds() {
        val stand = JitIsoStand(rules = 99)
        stand.exec(JitRef.chainScript(want = true))
        assertEquals(20, stand.calls().count { it == "ip rule show" })
        assertEquals(20, stand.calls().count { it == "sleep 1" })
        assertEquals("после окна всё равно один рестарт",
            1, stand.calls().count { it == "setprop ctl.restart zygote" })
    }

    @Test
    fun disableWritesFalse() {
        val stand = JitIsoStand(rules = 0)
        stand.exec(JitRef.chainScript(want = false))
        val calls = stand.calls()
        assertTrue(calls.contains("setprop dalvik.vm.usejit false"))
        assertFalse(calls.contains("setprop dalvik.vm.usejit true"))
        assertEquals("setprop ctl.restart zygote", calls.last())
    }

    @Test
    fun mountStepTouchesOnlyDataApp() {
        val stand = JitIsoStand(rules = 0)
        val (rc, _) = stand.exec(JitRef.chainScript(want = true))
        assertEquals(0, rc)
        val calls = stand.calls()
        val umounts = calls.filter { it.startsWith("umount") }
        assertEquals("ровно точки /data/app/", 2, umounts.size)
        assertTrue(umounts.all { it.startsWith("umount -l ") })
        assertTrue("чужие точки не трогаем", calls.none { it.contains("/data/data") })
        assertTrue(
            "шаг монтирований — до setprop",
            calls.indexOf(umounts.first()) < calls.indexOf("setprop dalvik.vm.usejit true")
        )
        assertEquals("setprop ctl.restart zygote", calls.last())
    }

    @Test
    fun sentScriptEqualsReferenceText() {
        // Отправляемое совпадает с литералом референса целиком — вариантов нет.
        val stand = JitIsoStand(rules = 0)
        stand.exec(JitRef.chainScript(true))
        assertEquals(JitRef.referenceScript(true), JitRef.chainScript(true))
        assertTrue(stand.callsText().contains("umount -l"))
    }

    @Test
    fun noForbiddenCommands() {
        val stand = JitIsoStand(rules = 1)
        stand.exec(JitRef.chainScript(want = true))
        val text = stand.callsText()
        for (bad in listOf("persist.", "usejitprofiles", "rule del", "flush", "ndc", "settings", "kill", "pkill")) {
            assertFalse("в выполненных командах нет «$bad»", text.contains(bad))
        }
        assertEquals("правила только читаются", 2, stand.calls().count { it == "ip rule show" })
    }

    @Test
    fun failingSetpropDoesNotBreakOrder() {
        // Отказ `setprop` на устройстве не должен обрывать последовательность: рестарт остаётся
        // последней командой, а факт отказа виден по коду возврата скрипта (его и анализирует
        // JitRef.chainOutcome). Здесь подставной `setprop` возвращает 1 — как «нет прав/свойство
        // не поддержано».
        val stand = JitIsoStand(rules = 0)
        val f = java.io.File(stand.dir, "setprop")
        f.writeText("#!/bin/sh\necho \"setprop \$*\" >> \"\$CALLS\"\nexit 1\n")
        f.setExecutable(true)
        val (rc, _) = stand.exec(JitRef.chainScript(true))
        assertEquals("код возврата — отказ последнего setprop, а не «всё хорошо»", 1, rc)
        val calls = stand.calls()
        assertEquals("первая команда всё равно выполнена", "svc wifi disable", calls.first())
        assertTrue("свойство выставлено до рестарта", calls.contains("setprop dalvik.vm.usejit true"))
        assertEquals("рестарт остался последней командой", "setprop ctl.restart zygote", calls.last())
        assertTrue("шаг монтирований всё равно выполнен", calls.any { it.startsWith("umount -l ") })
    }
}
