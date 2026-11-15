package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * iter57 §7.4 — РУЧНОЙ ON/OFF СКВОЗНЫМ ПУТЁМ и возврат Wi‑Fi.
 *
 * Проверяется настоящая цепочка [JitOp.click] и продолжение после перезапуска фреймворка
 * ([JitOp.onProcessStart]) на подставном устройстве ([JitDev] — JVM-модель claim/terminal и
 * fake-shell), а не отдельные helper'ы.
 *
 * Что доказывается:
 *  1. ручной клик ON отправляет payload ОДИН раз, и `ctl.restart zygote` — последняя команда
 *     скрипта (иначе свойства не применяются);
 *  2. ручной клик OFF идёт тем же путём (свой `usejit=false`), а не отдельной ветвью;
 *  3. после перезапуска фреймворка Wi‑Fi ВОЗВРАЩАЕТСЯ (это и есть «интернет обратно»), и это
 *     делает продолжение того же клика, а не фоновая служба;
 *  4. ВНЕ ручного клика принудительного `ctl.restart` нет: старт процесса без снимка ничего не
 *     отправляет (`no_snapshot`);
 *  5. ни в payload, ни в возврате Wi‑Fi нет AP (`startSoftAp`, `hostapd`), AVAS, `CarplayMonitor`,
 *     `connadaptor`, `adb.tcp`, `setenforce`, 5555 и `mount`/`umount` вне объявленного шага.
 *
 * Чего тесты НЕ доказывают: поведение реального `zygote`, `/system/bin/sh`, `svc wifi`, VHAL и
 * фактический интернет на ГУ. Это JVM + fake shell.
 */
class Iter57JitE2eTest {

    private val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
    private fun log(s: String) { logs += s }
    private fun lines(): String = synchronized(logs) { logs.joinToString("\n") }

    @Before
    fun clean() {
        JitDev.reset()
        logs.clear()
        JitOp.resetStateForTest()
    }

    private val forbidden = listOf(
        "startSoftAp", "hostapd", "softap", "AVAS", "avas", "CarplayMonitor",
        "connadaptor", "adb.tcp", "5555", "setenforce", "pm install", "reboot"
    )

    /**
     * Записывающий fake-shell: служебные claim/terminal-команды обслуживает модель устройства
     * ([JitDev]), остальное — payload (rc=0). Список команд ведётся ЯВНО, чтобы проверять, что
     * именно ушло на устройство.
     */
    private class RecShell : JitRef.JitShell {
        val executed = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            executed += command
            return JitDev.exec(command) { 0 to "" }
        }
        override fun mapsExcerpt(): String? = null
        /** Только payload (не claim/terminal-служебное). */
        fun payloadCmds(): List<String> = synchronized(executed) {
            executed.filter { it.contains("setprop ctl.restart zygote") }
        }
    }

    @Test
    fun manualOnSendsOnePayloadAndRestartsZygoteLast() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = RecShell()

        val c = JitOp.click(store, net, shell, want = true, log = ::log)

        assertNull("ручной ON не заблокирован: ${c.blocked} / ${lines()}", c.blocked)
        assertEquals("payload исполнен ровно один раз", 1, JitDev.payloadRuns)
        val payloads = shell.payloadCmds()
        assertEquals("в транспорте ровно одна payload-команда: size=${payloads.size} " +
            "all=${shell.executed.size} cmds=${shell.executed.joinToString(" || ") { it.take(60) }}",
            1, payloads.size)
        val s = payloads[0]
        assertTrue("свойство включения в скрипте: ${s.takeLast(120)}",
            s.contains("setprop $PROP true"))
        assertTrue("рестарт zygote последний: ${s.takeLast(120)}",
            s.trimEnd().endsWith("setprop ctl.restart zygote"))
        assertTrue("снятие слоёв объявлено шагом скрипта, а не отдельной командой",
            s.contains("grep ' /data/app/' /proc/mounts"))
        assertTrue("ожидание вытеснения правил wlan0", s.contains("ip rule show | grep -c 'oif wlan0'"))
        for (bad in forbidden) {
            assertFalse("запрещённое «$bad» не отправлялось", s.contains(bad))
        }
    }

    @Test
    fun manualOffGoesThroughTheSameChainWithItsOwnValue() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = RecShell()

        val c = JitOp.click(store, net, shell, want = false, log = ::log)

        assertNull("ручной OFF не заблокирован: ${c.blocked}", c.blocked)
        val s = shell.payloadCmds().single()
        assertTrue("свойство выключения: ${s.takeLast(120)}", s.contains("setprop $PROP false"))
        assertTrue("рестарт zygote последний", s.trimEnd().endsWith("setprop ctl.restart zygote"))
        assertEquals("payload исполнен один раз", 1, JitDev.payloadRuns)
    }

    @Test
    fun wifiComesBackAfterFrameworkRestartOfTheSameClick() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = RecShell()
        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertNull(c.blocked)
        // Перезапуск фреймворка «съел» Wi‑Fi — как это и происходит на устройстве.
        net.wifi = false
        net.requests.clear()

        val sched = JitFake.Scheduler()
        val res = JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertTrue("продолжение клика запланировано: $res", res.scheduled)
        assertTrue("Wi‑Fi включён обратно: ${net.requests} / ${lines()}",
            net.requests.contains("wifi=true"))
        assertTrue("возврат назван в журнале: ${lines()}",
            lines().contains("JIT_RESTORE") || lines().contains("restore"))
        assertTrue("Wi‑Fi действительно включён", net.wifi)
    }

    @Test
    fun noForcedRestartOutsideAManualClick() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)

        // Старт процесса без снимка/отметки ручного клика (обычная загрузка). Транспорта здесь
        // нет вообще: если бы старт что-то отправлял, это было бы видно по запланированной
        // задаче и по журналу.
        val sched = JitFake.Scheduler()
        val res = JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertFalse("boot-restore не запускает цепочку: $res / ${lines()}", res.scheduled)
        assertEquals("ни одной задачи не запланировано", 0, sched.scheduled.size)
        assertEquals("payload не исполнялся", 0, JitDev.payloadRuns)
        assertFalse("сеть не трогалась", net.requests.isNotEmpty())
        assertFalse("и принудительного рестарта в журнале нет", lines().contains("ctl.restart"))
        assertTrue("причина названа: ${lines()}",
            lines().contains("no_snapshot") || lines().contains("snapshot=none"))
    }

    @Test
    fun restoreOfWifiTouchesNeitherApNorAvasNorPlugin() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = RecShell()
        JitOp.click(store, net, shell, want = true, log = ::log)
        net.wifi = false
        net.requests.clear()
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        for (bad in forbidden) {
            assertFalse("возврат Wi‑Fi не трогает «$bad»: ${lines()}", lines().contains(bad))
        }
        assertTrue("единственное сетевое действие — включение Wi‑Fi: ${net.requests}",
            net.requests.all { it == "wifi=true" })
    }

    private companion object {
        const val PROP = "dalvik.vm.usejit"
    }
}
