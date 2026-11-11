package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ядро референса без Android: формат снимка, текст скрипта, формула `bb/r7`, опознание загрузки,
 * фазы операции. Проверяется то, что можно проверить точно, и НЕ выдаётся за полевую проверку.
 */
class JitRefTest {

    // ───────────────────────── снимок ─────────────────────────

    @Test
    fun parseRestoreAcceptsOnlyReferenceFormat() {
        assertEquals(JitRef.Net(true, true), JitRef.parseRestore("wifi=1,ap=1"))
        assertEquals(JitRef.Net(false, true), JitRef.parseRestore(" wifi=0,ap=1 "))
        assertNull(JitRef.parseRestore(null))
        assertNull(JitRef.parseRestore("wifi=1"))
        assertNull(JitRef.parseRestore("wifi=2,ap=1"))
        assertNull(JitRef.parseRestore("wifi=1,ap=1,extra=0"))
    }

    @Test
    fun opPayloadRoundTripAndStrictness() {
        val op = JitRef.Op(JitRef.Phase.SENT, true, JitRef.Net(true, false), "3f2a9c1e-aaaa-bbbb", 1234L, false)
        val back = JitRef.decodeOp(op.encode())
        assertEquals(op, back)
        // строгость: любое отклонение ⇒ null (снимок не трогаем)
        assertNull(JitRef.decodeOp(null))
        assertNull(JitRef.decodeOp(""))
        assertNull(JitRef.decodeOp("v=2;phase=ARMED;want=1;wifi=1;ap=0;boot=x;ts=1"))
        assertNull(JitRef.decodeOp("v=1;phase=WHAT;want=1;wifi=1;ap=0;boot=x;ts=1"))
        assertNull(JitRef.decodeOp("v=1;phase=ARMED;want=7;wifi=1;ap=0;boot=x;ts=1"))
        assertNull(JitRef.decodeOp("v=1;phase=ARMED;want=1;wifi=1;ap=0;boot=x"))
        assertNull(JitRef.decodeOp("мусор"))
    }

    // ───────────────────────── скрипт ─────────────────────────

    @Test
    fun defaultScriptMatchesReferenceExceptMountStep() {
        // Полный литерал референса (из smali `w0/la.run`, ветка pswitch_10e):
        val ref = JitRef.referenceScript(true)
        val ours = JitRef.chainScript(true)
        assertTrue("референс содержит шаг монтирований", ref.contains("umount -l"))
        assertFalse("включённая конфигурация шаг НЕ содержит (отличие §3.1)", ours.contains("umount"))
        assertEquals("разница — ровно три строки шага монтирований (for/umount/done)", 3,
            ref.lines().size - ours.lines().size)
        // всё остальное — байт в строку
        assertEquals("без блока for/umount/done тексты совпадают символ в символ",
            ref.replace(Regex("(?s)        for _m in .*?done\\n"), ""), ours)
    }

    @Test
    fun mountStepOnEqualsReferenceVerbatim() {
        assertEquals(JitRef.referenceScript(true), JitRef.chainScript(true, mountStep = true))
        assertEquals(JitRef.referenceScript(false), JitRef.chainScript(false, mountStep = true))
    }

    @Test
    fun scriptShape() {
        val lines = JitRef.chainScript(true).lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals("svc wifi disable", lines.first())
        assertEquals("setprop ctl.restart zygote", lines.last())
        assertEquals(1, lines.count { it == "setprop ctl.restart zygote" })
        assertTrue(JitRef.chainScript(true).contains("while [ \$_n -lt 20 ]"))
        assertTrue(JitRef.chainScript(false).contains("setprop dalvik.vm.usejit false"))
        for (bad in listOf("persist.", "usejitprofiles", "ip rule del", "flush", "ndc ", "settings ")) {
            assertFalse("в скрипте нет «$bad»", JitRef.chainScript(true).contains(bad))
        }
    }

    // ───────────────────────── честное чтение ─────────────────────────

    @Test
    fun onlyTrueAndFalseAreFacts() {
        assertEquals(JitRef.Prop.TRUE, JitRef.parseProp(0, "true\n").value)
        assertEquals(JitRef.Prop.FALSE, JitRef.parseProp(0, "false\n").value)
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(0, "").value)
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(0, "yes").value)
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(0, "true\nfalse").value)
        // ошибка канала: раньше это считалось за `false` и при want=false давало ложный OK
        val err = JitRef.parseProp(1, "ERR_ROOT_CHANNEL_UNAVAILABLE")
        assertEquals(JitRef.Prop.UNKNOWN, err.value)
        assertEquals("rc_nonzero", err.reason)
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(0, "ERR_ROOT_CHANNEL_UNAVAILABLE").value)
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(137, "ERR_ADB_TIMEOUT after 8000ms").value)
    }

    @Test
    fun verifyNeverCallsUnknownSuccess() {
        val ok = JitRef.parseProp(0, "true")
        val off = JitRef.parseProp(0, "false")
        val unknown = JitRef.parseProp(1, "ERR_ADB_TIMEOUT")
        assertEquals(JitRef.Verify.OK, JitRef.verify(true, ok, true))
        assertEquals(JitRef.Verify.NOT_APPLIED, JitRef.verify(true, off, true))
        assertEquals(JitRef.Verify.CACHE_MISSING, JitRef.verify(true, ok, false))
        assertEquals("cache=null — неизвестно, не отсутствие", JitRef.Verify.UNKNOWN, JitRef.verify(true, ok, null))
        // хочу выключить: ошибка канала больше НЕ даёт OK
        assertEquals(JitRef.Verify.UNKNOWN, JitRef.verify(false, unknown, null))
        assertEquals(JitRef.Verify.OK, JitRef.verify(false, off, null))
        assertEquals(JitRef.Verify.NOT_APPLIED, JitRef.verify(false, ok, true))
        assertEquals("unknown", JitRef.verifyLabel(JitRef.Verify.UNKNOWN))
    }

    // ───────────────────────── загрузка и фазы ─────────────────────────

    @Test
    fun bootMatchDistinguishesProofFromGuess() {
        val id1 = "8f1c2b44-1c2d-4e5f-9a0b-11c2d3e4f5a6"
        val id2 = "0a9b8c7d-6e5f-4a3b-2c1d-0e9f8a7b6c5d"
        assertEquals(JitRef.BootMatch.SAME, JitRef.bootMatch(id1, id1))
        assertEquals(JitRef.BootMatch.PREVIOUS, JitRef.bootMatch(id1, id2))
        // epoch-теги (fallback при недоступном boot_id) НЕ доказывают ту же загрузку
        assertEquals(JitRef.BootMatch.UNKNOWN, JitRef.bootMatch("epoch1700000000", "epoch1700000000"))
        assertEquals(JitRef.BootMatch.UNKNOWN, JitRef.bootMatch("", id1))
        assertEquals(JitRef.BootMatch.UNKNOWN, JitRef.bootMatch("epoch1700000000", id1))
        assertEquals(JitRef.BootMatch.UNKNOWN, JitRef.bootMatch("что-то странное-1", id1))
    }

    @Test
    fun startDecisionRequiresEvidence() {
        val id = "8f1c2b44-1c2d-4e5f-9a0b-11c2d3e4f5a6"
        val op = JitRef.Op(JitRef.Phase.ARMED, true, JitRef.Net(true, false), id, 10L)
        assertEquals(
            "только boot ID + 15 с — НЕ основание возвращать сеть",
            JitRef.StartDecision.WAIT_NO_EVIDENCE, JitRef.startDecision(op, id, evidence = false)
        )
        assertEquals(JitRef.StartDecision.RESTORE, JitRef.startDecision(op, id, evidence = true))
        assertEquals(
            JitRef.StartDecision.KEEP_PREVIOUS_BOOT,
            JitRef.startDecision(op, "0a9b8c7d-6e5f-4a3b-2c1d-0e9f8a7b6c5d", evidence = true)
        )
        assertEquals(
            JitRef.StartDecision.KEEP_UNKNOWN_BOOT,
            JitRef.startDecision(op, "epoch1700000000", evidence = true)
        )
        val done = op.copy(phase = JitRef.Phase.DONE)
        assertEquals(JitRef.StartDecision.DONE, JitRef.startDecision(done, id, evidence = true))
        val nothing = op.copy(net = JitRef.Net(false, false))
        assertEquals("нечего возвращать — сеть не трогаем", JitRef.StartDecision.DONE,
            JitRef.startDecision(nothing, id, evidence = true))
        assertEquals(JitRef.StartDecision.IDLE, JitRef.startDecision(null, id, evidence = true))
    }

    @Test
    fun clickGateBlocksOnlyUnresolvedOrCurrentBoot() {
        val id = "8f1c2b44-1c2d-4e5f-9a0b-11c2d3e4f5a6"
        val op = JitRef.Op(JitRef.Phase.SENT, true, JitRef.Net(true, true), id, 10L)
        assertEquals(JitRef.ClickGate.ALLOW, JitRef.clickGate(null, false, id))
        assertEquals(JitRef.ClickGate.REFUSE_UNRESOLVED, JitRef.clickGate(null, true, id))
        assertEquals(JitRef.ClickGate.REFUSE_PENDING, JitRef.clickGate(op, false, id))
        assertEquals(JitRef.ClickGate.REFUSE_PENDING, JitRef.clickGate(op, false, "epoch1700000000"))
        assertEquals(JitRef.ClickGate.ALLOW, JitRef.clickGate(op, false, "0a9b8c7d-6e5f-4a3b-2c1d-0e9f8a7b6c5d"))
        assertEquals(JitRef.ClickGate.ALLOW, JitRef.clickGate(op.copy(phase = JitRef.Phase.DONE), false, id))
    }

    @Test
    fun chainOutcomeDoesNotClaimRestart() {
        assertEquals(JitRef.ChainOutcome.REPLY_RECEIVED, JitRef.chainOutcome(0, ""))
        assertEquals(JitRef.ChainOutcome.CHANNEL_LOST, JitRef.chainOutcome(137, "ERR_ADB_TIMEOUT"))
        assertEquals(JitRef.ChainOutcome.CHANNEL_LOST, JitRef.chainOutcome(0, "ERR_EXCEPTION"))
    }

    @Test
    fun referenceConstants() {
        assertEquals("0x3a98", 15_000L, JitRef.RESTORE_DELAY_MS)
        assertEquals("0x9c40", 40_000, JitRef.CHAIN_TIMEOUT_MS)
        assertEquals("0x4e20", 20_000L, JitRef.AP_POLL_TOTAL_MS)
        assertEquals("0x3e8", 1_000L, JitRef.AP_POLL_STEP_MS)
        assertEquals(20, JitRef.DRAIN_MAX_S)
        assertFalse(JitRef.MOUNT_STEP_ENABLED)
        assertNotNull(JitRef.decodeOp(JitRef.Op(JitRef.Phase.ARMED, false, JitRef.Net(false, false), "x", 1L).encode()))
    }

    @Test
    fun verbatimReferenceLiteralFromSmali() {
        // Литерал взят ИЗ DEX, а не из нашего же кода: `w0/la.smali:326` (const-string v5) +
        // `w0/la.smali:342` (const-string v2) + подстановка значения свойства (la.smali:153).
        // Сравнение байт в байт — чтобы правка скрипта не могла «незаметно» разойтись с референсом.
        val ref = "\n        svc wifi disable\n        _n=0\n" +
            "        while [ \$_n -lt 20 ]; do\n" +
            "          [ \"\$(ip rule show | grep -c 'oif wlan0')\" = \"0\" ] && break\n" +
            "          sleep 1; _n=\$((_n + 1))\n" +
            "        done\n" +
            "        for _m in \$(grep ' /data/app/' /proc/mounts | awk '{print \$2}'); do\n" +
            "          umount -l \"\$_m\" 2>/dev/null\n" +
            "        done\n" +
            "        setprop dalvik.vm.usejit true\n" +
            "        setprop ctl.restart zygote\n    "
        assertEquals("включённая конфигурация — символ в символ с DEX", ref, JitRef.chainScript(true, mountStep = true))
        assertEquals(392, JitRef.chainScript(true, mountStep = true).length)
        assertEquals(ref, JitRef.referenceScript(true))
        assertEquals(ref.replace("usejit true", "usejit false"), JitRef.chainScript(false, mountStep = true))
    }
}
