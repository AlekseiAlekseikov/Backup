package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33p (1.0.61) — текст ЕДИНСТВЕННОЙ цепочки исполняется настоящим `/bin/sh` на изолированном
 * стенде [JitIsoStand] (подставные `svc`, `ip`, `setprop`, `getprop`, `sleep`, `umount`).
 *
 * Проверяется ФАКТИЧЕСКИЙ порядок команд, поведение цикла слива правил и отсутствие запрещённых
 * действий — а не «похожесть» исходника.
 */
class JitChainScriptTest {

    @Test
    fun orderIsReferenceByteForByte() {
        val stand = JitIsoStand(rules = 2)
        val (rc, _) = stand.exec(JitRef.chainScript(want = true))
        assertEquals(0, rc)
        val calls = stand.calls()
        assertEquals("первая команда — выключение Wi-Fi", "svc wifi disable", calls.first())
        assertEquals("последняя команда — рестарт zygote", "setprop ctl.restart zygote", calls.last())
        assertEquals("рестарт ровно один", 1, calls.count { it == "setprop ctl.restart zygote" })
        assertEquals("свойство JIT выставлено один раз",
            1, calls.count { it == "setprop dalvik.vm.usejit true" })
        assertTrue("свойство стоит ПЕРЕД рестартом",
            calls.indexOf("setprop dalvik.vm.usejit true") < calls.indexOf("setprop ctl.restart zygote"))
        assertEquals("слив правил ждёт, пока счёт не станет 0", 3, calls.count { it == "ip rule show" })
        assertEquals("пауза между попытками — 1 с", 2, calls.count { it == "sleep 1" })
        assertFalse("без включённого шага монтирований umount не вызывается",
            calls.any { it.startsWith("umount") })
    }

    @Test
    fun drainStopsImmediatelyWhenNoRules() {
        val stand = JitIsoStand(rules = 0)
        stand.exec(JitRef.chainScript(want = true))
        val calls = stand.calls()
        assertEquals("одна проверка правил", 1, calls.count { it == "ip rule show" })
        assertEquals("ни одной паузы", 0, calls.count { it.startsWith("sleep") })
        assertEquals("setprop ctl.restart zygote", calls.last())
    }

    @Test
    fun drainGivesUpAfterTwentySeconds() {
        val stand = JitIsoStand(rules = 99)
        stand.exec(JitRef.chainScript(want = true))
        val calls = stand.calls()
        assertEquals("не более 20 попыток", 20, calls.count { it == "ip rule show" })
        assertEquals("не более 20 пауз по 1 с", 20, calls.count { it == "sleep 1" })
        assertEquals("после истечения окна всё равно один рестарт",
            1, calls.count { it == "setprop ctl.restart zygote" })
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
    fun mountStepTouchesOnlyDataAppPoints() {
        val stand = JitIsoStand(rules = 0)
        val (rc, _) = stand.exec(JitRef.chainScript(want = true, mountStep = true))
        assertEquals(0, rc)
        val calls = stand.calls()
        val umounts = calls.filter { it.startsWith("umount") }
        assertEquals("только точки /data/app/", 2, umounts.size)
        assertTrue("ленивое снятие", umounts.all { it.startsWith("umount -l ") })
        assertTrue("чужие точки не снимаются", calls.none { it.contains("/data/data") })
        assertTrue("шаг монтирований — до setprop",
            calls.indexOf(umounts.first()) < calls.indexOf("setprop dalvik.vm.usejit true"))
        assertEquals("setprop ctl.restart zygote", calls.last())
    }

    @Test
    fun scriptDoesNotTouchPersistOrNetworkRules() {
        val stand = JitIsoStand(rules = 1)
        stand.exec(JitRef.chainScript(want = true, mountStep = true))
        val text = stand.callsText()
        assertFalse("нет persist.*", text.contains("persist."))
        assertFalse("нет usejitprofiles", text.contains("usejitprofiles"))
        assertFalse("правила не удаляются", text.contains("rule del") || text.contains("flush"))
        assertEquals("только чтение правил", 2, stand.calls().count { it == "ip rule show" })
    }
}
