package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter67 §I — возврат сети не зависит ни от исхода payload, ни от 5 минут, ни от отсутствия claim.
 *
 * Живой случай (клик 19.11): `svc wifi disable` выполнился, `setprop` — нет, операция осталась
 * `undefined`; Wi-Fi не вернулся, потому что `allowEnable` отказал по возрасту, а `claimGuard` — по
 * отсутствию файла. Владелец включал сеть руками.
 */
class Iter67NetRestoreTest {

    private val wifiOn = JitRef.WifiSnapshot(true)
    private val wifiOff = JitRef.WifiSnapshot(false)

    @Test
    fun `сеть выключили мы — восстанавливаем при любом возрасте`() {
        val d = JitNetDecision.restoreDecision(
            snap = wifiOn, weDisarmed = true, markAgeMs = 10 * 60_000,
            claimKnown = false, nowEnabled = false
        )
        assertTrue("возврат запрещён по возрасту — это и был дефект", d.restoreNetwork)
        assertEquals("we_disarmed", d.why)
        assertFalse("исход цепочки этим не доказан", d.chainResolved.not())
    }

    @Test
    fun `нет claim — не чужой`() {
        val d = JitNetDecision.restoreDecision(
            snap = wifiOn, weDisarmed = false, markAgeMs = 60_000,
            claimKnown = false, nowEnabled = false
        )
        assertTrue(d.restoreNetwork)
        assertEquals("claim_absent_not_foreign", d.why)
    }

    @Test
    fun `снимок wifi=0 — не включаем`() {
        assertFalse(JitNetDecision.restoreDecision(wifiOff, true, null, false, false).restoreNetwork)
        assertEquals("was_off",
            JitNetDecision.restoreDecision(wifiOff, true, null, false, false).why)
    }

    @Test
    fun `сеть уже включена — не трогаем, но цепочка не доказана`() {
        val d = JitNetDecision.restoreDecision(wifiOn, true, 1_000, true, true)
        assertFalse(d.restoreNetwork)
        assertEquals("already_on", d.why)
    }

    @Test
    fun `старая отметка не нашего клика — не включаем`() {
        val d = JitNetDecision.restoreDecision(
            snap = wifiOn, weDisarmed = false, markAgeMs = 6 * 60_000,
            claimKnown = true, nowEnabled = false
        )
        assertFalse("включать по чужому старому снимку нельзя", d.restoreNetwork)
        assertEquals("too_old_and_not_ours", d.why)
    }

    @Test
    fun `нет снимка — возвращать нечего`() {
        assertEquals("no_snapshot",
            JitNetDecision.restoreDecision(null, true, 1_000, true, false).why)
    }

    @Test
    fun `текст различает not_sent и sent_no_reply`() {
        assertEquals("not_sent", JitNetDecision.uiReason("previous_unfinished"))
        assertEquals("sent_no_reply", JitNetDecision.uiReason("chain_undefined"))
        assertEquals("none", JitNetDecision.uiReason(null))
    }

    @Test
    fun `причина отказа видна владельцу без красного`() {
        val text = JitNetDecision.deniedText("too_old_and_not_ours")
        assertTrue("нет причины в тексте: $text", text.contains("too_old_and_not_ours"))
        assertTrue("нет объяснения: $text", text.contains("Wi-Fi был выключен кликом"))
    }

    @Test
    fun `флаг сняли сеть мы ставится до отправки и снимается после подтверждения`() {
        val src = java.io.File("app/src/main/java/com/example/voiceapp3/tools/JitNetCore.kt").readText()
        val set = src.indexOf("putBool(JitNetDecision.KEY_NET_CUT_BY_US, true)")
        val sent = src.indexOf("\"JIT_CHAIN_SENT op=")
        val clear = src.indexOf("putBool(JitNetDecision.KEY_NET_CUT_BY_US, false)")
        // fail-before: флага не было вовсе, поэтому сеть после сбоя никто не возвращал.
        assertTrue("флаг не ставится", set > 0)
        assertTrue("флаг должен ставиться ДО отправки цепочки", set < sent)
        assertTrue("флаг не снимается", clear > 0)
        assertTrue("флаг должен сниматься ПОСЛЕ отправки", clear > sent)
        assertTrue("снятие привязано к подтверждённому включению",
            src.contains("JitRef.RestoreCompletion.COMPLETE) {\n                    val cleared"))
    }

    @Test
    fun `решение о возврате принимается на старте, а не только как продолжение`() {
        val src = java.io.File("app/src/main/java/com/example/voiceapp3/tools/JitNetCore.kt").readText()
        val start = src.indexOf("fun onProcessStart(")
        val decisionAt = src.indexOf("JitNetDecision.restoreDecision(")
        // fail-before: возраст решал всё, а «сняли сеть мы» не учитывалось.
        assertTrue("нет решения в пути старта", decisionAt > start)
        assertTrue("решение должно логироваться строкой",
            src.contains("JIT_RESTORE_DECISION op="))
    }
}
