package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Разбор и формулы ядра — по DEX референса.
 *
 * Здесь проверяется то, что видно в `w0/r7`, `w0/j9`, `w0/la`, `onCreate` и `w0/e2`: литерал
 * скрипта, регулярка снимка, трактовка `getprop`, формула `ok`, положение тумблера, `c1()`.
 */
class JitRefTest {

    private fun code(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readText()

    private fun codeNoComments(rel: String): String =
        code(rel).lines()
            .filterNot { val s = it.trim(); s.startsWith("*") || s.startsWith("//") || s.startsWith("/*") }
            .joinToString("\n")

    // ─────────────────────────── литерал `w0/la.run` ───────────────────────────

    @Test
    fun chainScriptIsReferenceLiteralByteForByte() {
        // Литерал из DEX (`w0/la` pswitch_10e), 392 символа, как было в APK.
        val dex = "\n" +
            "        svc wifi disable\n" +
            "        _n=0\n" +
            "        while [ \$_n -lt 20 ]; do\n" +
            "          [ \"\$(ip rule show | grep -c 'oif wlan0')\" = \"0\" ] && break\n" +
            "          sleep 1; _n=\$((_n + 1))\n" +
            "        done\n" +
            "        for _m in \$(grep ' /data/app/' /proc/mounts | awk '{print \$2}'); do\n" +
            "          umount -l \"\$_m\" 2>/dev/null\n" +
            "        done\n" +
            "        setprop dalvik.vm.usejit true\n" +
            "        setprop ctl.restart zygote\n    "
        assertEquals(dex, JitRef.referenceScript(true))
        assertEquals(392, JitRef.referenceScript(true).length)
        assertEquals("литерал тот же и для false (меняется только значение)", dex.replace("usejit true", "usejit false"),
            JitRef.referenceScript(false))
    }

    @Test
    fun executedScriptIsTheSameLiteral() {
        assertEquals(JitRef.referenceScript(true), JitRef.chainScript(true))
        assertEquals(JitRef.referenceScript(false), JitRef.chainScript(false))
        assertTrue("шаг umount -l присутствует и исполняется", JitRef.chainScript(true).contains("umount -l"))
        assertEquals("mount_step=present", JitRef.mountStepNote())
    }

    @Test
    fun scriptOrderAndBoundaries() {
        val lines = JitRef.chainScript(true).trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals("svc wifi disable", lines.first())
        assertEquals("последняя команда — рестарт zygote", "setprop ctl.restart zygote", lines.last())
        assertEquals(1, lines.count { it == "setprop ctl.restart zygote" })
        assertEquals(1, lines.count { it.startsWith("setprop dalvik.vm.usejit") })
        val propIdx = lines.indexOfFirst { it.startsWith("setprop dalvik.vm.usejit") }
        val umountIdx = lines.indexOfFirst { it.startsWith("umount -l") }
        assertTrue("umount до setprop", umountIdx in 0 until propIdx)
        assertTrue("слив правил до umount", lines.indexOfFirst { it.contains("ip rule show") } < umountIdx)
    }

    @Test
    fun noForbiddenPropertiesOrCommandsInScript() {
        for (want in listOf(true, false)) {
            val s = JitRef.chainScript(want)
            for (bad in listOf("persist.dalvik.vm.usejit", "persist.sys.dalvik.vm.usejit",
                "usejitprofiles", "ip rule del", "ip rule flush", "ndc ", "settings ", "setenforce",
                "kill ", "pkill", "route add", "iptables")) {
                assertFalse("в скрипте нет «$bad»", s.contains(bad))
            }
        }
    }

    // ─────────────────────────── снимок `jit_restaurar` ───────────────────────────

    @Test
    fun parseRestoreMatchesOnlyFullString() {
        assertEquals(JitRef.Net(true, false), JitRef.parseRestore("wifi=1,ap=0"))
        assertEquals(JitRef.Net(false, true), JitRef.parseRestore("wifi=0,ap=1"))
        assertEquals(JitRef.Net(true, true), JitRef.parseRestore("wifi=1,ap=1"))
        assertEquals(JitRef.Net(false, false), JitRef.parseRestore("wifi=0,ap=0"))
        // `matches()` без trim: пробелы и обрезки не совпадают.
        assertNull(JitRef.parseRestore(null))
        assertNull(JitRef.parseRestore(""))
        assertNull(JitRef.parseRestore("wifi=1"))
        assertNull(JitRef.parseRestore(" wifi=1,ap=0"))
        assertNull(JitRef.parseRestore("wifi=1,ap=0 "))
        assertNull(JitRef.parseRestore("wifi=2,ap=0"))
        assertNull(JitRef.parseRestore("wifi=1,ap=0,extra=1"))
        assertNull(JitRef.parseRestore("WIFI=1,AP=0"))
    }

    @Test
    fun netWireIsReferenceFormat() {
        assertEquals("wifi=1,ap=0", JitRef.Net(true, false).wire())
        assertEquals("wifi=0,ap=1", JitRef.Net(false, true).wire())
        assertEquals("wifi=0,ap=0", JitRef.Net(false, false).wire())
    }

    // ─────────────────────────── `getprop` ───────────────────────────

    @Test
    fun parsePropTruthTable() {
        assertEquals(JitRef.Prop.TRUE, JitRef.parseProp(0, "true").value)
        assertEquals(JitRef.Prop.TRUE, JitRef.parseProp(0, "true\n").value)
        assertEquals(JitRef.Prop.FALSE, JitRef.parseProp(0, "false").value)
        // ОТЛИЧИЕ: нечитаемое/странное — UNKNOWN, а не «выключено».
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(1, "true").value)
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(0, "").value)
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(0, "ERR_TIMEOUT").value)
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(0, "true\nfalse").value)
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(0, "yes").value)
        assertEquals("rc_nonzero", JitRef.parseProp(2, "").reason)
        assertEquals("transport_error", JitRef.parseProp(0, "ERR_X").reason)
    }

    // ─────────────────────────── формула `w0/r7` целиком ───────────────────────────

    private fun prop(v: JitRef.Prop) = JitRef.PropFact(v, v.name.lowercase(), 0, "ok")

    @Test
    fun verifyTableForConfirmedValues() {
        // ok = (pedido == getprop) && (!pedido || maps != Boolean.FALSE)
        val t = JitRef.Prop.TRUE
        val f = JitRef.Prop.FALSE
        // pedido = true
        assertEquals("true/true/cache=true → ok", JitRef.Verify.OK, JitRef.verify(true, prop(t), true))
        assertEquals("true/true/cache=null → ok", JitRef.Verify.OK, JitRef.verify(true, prop(t), null))
        assertEquals("true/true/cache=false → НЕ ok", JitRef.Verify.CACHE_MISSING,
            JitRef.verify(true, prop(t), false))
        assertEquals("true/false (любой cache) → НЕ ok", JitRef.Verify.NOT_APPLIED,
            JitRef.verify(true, prop(f), true))
        assertEquals(JitRef.Verify.NOT_APPLIED, JitRef.verify(true, prop(f), false))
        assertEquals(JitRef.Verify.NOT_APPLIED, JitRef.verify(true, prop(f), null))
        // pedido = false
        assertEquals("false/false/cache=false → ok (в референсе cache не мешает)", JitRef.Verify.OK,
            JitRef.verify(false, prop(f), false))
        assertEquals(JitRef.Verify.OK, JitRef.verify(false, prop(f), true))
        assertEquals(JitRef.Verify.OK, JitRef.verify(false, prop(f), null))
        assertEquals("false/true → НЕ ok", JitRef.Verify.NOT_APPLIED, JitRef.verify(false, prop(t), true))
        assertEquals(JitRef.Verify.NOT_APPLIED, JitRef.verify(false, prop(t), false))
        assertEquals(JitRef.Verify.NOT_APPLIED, JitRef.verify(false, prop(t), null))
    }

    @Test
    fun verifyUnknownPropertyIsNotAVerdict() {
        val u = JitRef.PropFact(JitRef.Prop.UNKNOWN, "", 137, "rc_nonzero")
        for (pedido in listOf(true, false)) {
            for (cache in listOf(true, false, null)) {
                assertEquals("неизвестное свойство не выдаётся за результат",
                    JitRef.Verify.UNKNOWN, JitRef.verify(pedido, u, cache))
            }
        }
    }

    @Test
    fun verifyLabels() {
        assertEquals("ok", JitRef.verifyLabel(JitRef.Verify.OK))
        assertEquals("not_applied", JitRef.verifyLabel(JitRef.Verify.NOT_APPLIED))
        assertEquals("cache_missing", JitRef.verifyLabel(JitRef.Verify.CACHE_MISSING))
        assertEquals("unknown", JitRef.verifyLabel(JitRef.Verify.UNKNOWN))
    }

    // ─────────────────────────── тумблер и `c1()` ───────────────────────────

    @Test
    fun confirmedStateAndSwitchPosition() {
        assertEquals("on", JitRef.confirmedState(prop(JitRef.Prop.TRUE)))
        assertEquals("off", JitRef.confirmedState(prop(JitRef.Prop.FALSE)))
        assertEquals("unknown", JitRef.confirmedState(null))
        assertEquals("unknown", JitRef.confirmedState(JitRef.PropFact(JitRef.Prop.UNKNOWN, "", 1, "x")))
        // Подтверждённый факт побеждает preference в обе стороны.
        assertTrue(JitRef.switchPosition("on", preference = false))
        assertFalse(JitRef.switchPosition("off", preference = true))
        // Без факта — намерение водителя.
        assertTrue(JitRef.switchPosition("unknown", preference = true))
        assertFalse(JitRef.switchPosition("unknown", preference = false))
        // Программное обновление: переставляем только когда факт известен и отличается.
        assertEquals(true, JitRef.switchUpdate(false, "on"))
        assertEquals(false, JitRef.switchUpdate(true, "off"))
        assertNull("уже верно — не трогаем", JitRef.switchUpdate(true, "on"))
        assertNull("уже верно — не трогаем", JitRef.switchUpdate(false, "off"))
        assertNull("неизвестно — не выдаём за факт", JitRef.switchUpdate(true, "unknown"))
        assertNull(JitRef.switchUpdate(false, "unknown"))
    }

    @Test
    fun clientWifiBusyFollowsC1() {
        // isWifiEnabled() || getWifiState()==ENABLING; исключение ⇒ true
        assertTrue(JitRef.clientWifiBusy(true, false))
        assertTrue(JitRef.clientWifiBusy(false, true))
        assertTrue(JitRef.clientWifiBusy(null, null))
        assertTrue(JitRef.clientWifiBusy(null, false))
        assertTrue(JitRef.clientWifiBusy(false, null))
        assertFalse(JitRef.clientWifiBusy(false, false))
    }

    // ─────────────────────────── итог транспорта ───────────────────────────

    @Test
    fun chainOutcomeDoesNotCallTimeoutSuccess() {
        assertEquals(JitRef.ChainOutcome.REPLY_RECEIVED, JitRef.chainOutcome(0, ""))
        assertEquals(JitRef.ChainOutcome.CHANNEL_LOST, JitRef.chainOutcome(137, "ERR_CHANNEL_LOST"))
        assertEquals(JitRef.ChainOutcome.CHANNEL_LOST, JitRef.chainOutcome(1, ""))
        assertEquals("reply_received", JitRef.chainOutcomeLabel(JitRef.ChainOutcome.REPLY_RECEIVED))
        assertEquals("channel_lost", JitRef.chainOutcomeLabel(JitRef.ChainOutcome.CHANNEL_LOST))
    }

    // ─────────────────────────── в ядре нет своих механизмов ───────────────────────────

    @Test
    fun noOwnMechanismsLeftInCore() {
        val core = codeNoComments("src/main/java/com/example/voiceapp3/tools/JitNetCore.kt")
        for (bad in listOf("HOLD_", "ARMED", "SENT_STATE", "enum class Phase", "jit_op", "KEY_OP",
            "ClickResult", "StartDecision", "bootId", "boot_id", "PID_CHECK", "TTL_MS",
            "FileObserver", "watchdog", "jit_verified", "jit_pending_verify", "MOUNT_STEP_ENABLED")) {
            assertFalse("ядро не содержит «$bad»", core.contains(bad))
        }
        // Никакого «лечения перезагрузкой» и никаких persist-свойств JIT.
        assertFalse(core.contains("persist."))
        assertFalse(core.contains("usejitprofiles"))
        // Единственная команда рестарта — в литерале скрипта.
        assertEquals(1, Regex("ctl\\.restart zygote").findAll(core).count())
    }

    @Test
    fun adapterIsIndependentStoresAndRealRemove() {
        val net = codeNoComments("src/main/java/com/example/voiceapp3/tools/JitNet.kt")
        assertTrue("удаление ключа настоящим remove", net.contains(".remove(key)"))
        assertTrue("каждое хранилище пишется отдельно", net.contains("for (s in all)"))
        assertFalse("нет обрыва цепочки по первому commit", net.contains("saved && runCatching"))
        assertTrue("сигнал FAILED берётся из того же broadcast",
            net.contains("JitRef.AP_STATE_CHANGED_ACTION") && net.contains("JitRef.AP_STATE_FAILED"))
        assertTrue("CarplayMonitor честно недоступен", net.contains("override fun carplayBusy(): Boolean? = null"))
        assertFalse("адаптер не выключает Wi-Fi", net.contains("setWifi(false"))
    }
}
