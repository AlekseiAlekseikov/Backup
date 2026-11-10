package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33p (1.0.61) — чистая логика референса: разбор `jit_restaurar`, текст скрипта, сверка
 * `bb`/`r7`, решение о продолжении операции, отказ второго клика, опознание загрузки.
 *
 * Никакого Android здесь нет (потому и JVM): Android-связка — `JitNet`, исполнение скрипта —
 * `JitChainScriptTest`, сквозная последовательность — `JitChainEndToEndTest`.
 */
class JitRefTest {

    // ───────────────────────── снимок: формат референса целиком ─────────────────────────

    @Test
    fun parseRestoreAcceptsExactlyReferenceFormat() {
        assertEquals(JitRef.Net(true, true), JitRef.parseRestore("wifi=1,ap=1"))
        assertEquals(JitRef.Net(false, true), JitRef.parseRestore("wifi=0,ap=1"))
        assertEquals(JitRef.Net(true, false), JitRef.parseRestore(" wifi=1,ap=0 "))
    }

    @Test
    fun parseRestoreRejectsAnythingElse() {
        // `w0/e2` использует `^wifi=([01]),ap=([01])$`: мусор ⇒ снимка нет, а не «догадка».
        assertNull(JitRef.parseRestore(null))
        assertNull(JitRef.parseRestore(""))
        assertNull(JitRef.parseRestore("wifi=1"))
        assertNull(JitRef.parseRestore("wifi=2,ap=1"))
        assertNull(JitRef.parseRestore("wifi=1,ap=1,extra=0"))
        assertNull(JitRef.parseRestore("WIFI=1,AP=1"))
    }

    @Test
    fun wireRoundTrip() {
        for (w in listOf(true, false)) for (a in listOf(true, false)) {
            val n = JitRef.Net(w, a)
            assertEquals(n, JitRef.parseRestore(n.wire()))
        }
    }

    // ───────────────────────── скрипт: ровно последовательность `la.run` ─────────────────────────

    @Test
    fun scriptStartsWithWifiDisableAndEndsWithZygoteRestart() {
        val s = JitRef.chainScript(true)
        val lines = s.lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals("svc wifi disable", lines.first())
        assertEquals("setprop dalvik.vm.usejit true", lines[lines.size - 2])
        assertEquals("setprop ctl.restart zygote", lines.last())
        assertEquals("рестарт zygote ровно один", 1, lines.count { it == "setprop ctl.restart zygote" })
    }

    @Test
    fun drainLoopIsReferenceShaped() {
        val s = JitRef.chainScript(false)
        assertTrue(s.contains("_n=0"))
        assertTrue(s.contains("while [ \$_n -lt 20 ]"))
        assertTrue(s.contains("[ \"\$(ip rule show | grep -c 'oif wlan0')\" = \"0\" ] && break"))
        assertTrue("ожидание ограничено 20 секундами", s.contains("sleep 1; _n=\$((\$_n + 1))"))
        assertTrue(s.contains("setprop dalvik.vm.usejit false"))
    }

    @Test
    fun mountStepIsOffByDefaultAndOnlyAddsReferenceLine() {
        assertFalse("по умолчанию шаг монтирований выключен (§1.1)", JitRef.MOUNT_STEP_ENABLED)
        assertFalse(JitRef.chainScript(true).contains("umount"))
        val withMount = JitRef.chainScript(true, mountStep = true)
        assertTrue(withMount.contains("grep ' /data/app/' /proc/mounts"))
        assertTrue(withMount.contains("umount -l"))
        // включённый шаг стоит между сливом правил и setprop — как в `la.run`
        assertTrue(withMount.indexOf("umount -l") < withMount.indexOf("setprop dalvik.vm.usejit"))
        // и не отменяет главного: последняя команда всё равно рестарт zygote
        assertEquals("setprop ctl.restart zygote", withMount.lines().last { it.trim().isNotEmpty() }.trim())
    }

    @Test
    fun noForbiddenCommandsInScript() {
        for (want in listOf(true, false)) for (mount in listOf(true, false)) {
            val s = JitRef.chainScript(want, mount)
            for (bad in listOf("persist.", "usejitprofiles", "ip rule del", "ip rule flush",
                "ndc ", "settings ", "kill ", "pkill", "setenforce", "svc data")) {
                assertFalse("скрипт не содержит «$bad»", s.contains(bad))
            }
        }
    }

    // ───────────────────────── сверка `bb`/`r7` ─────────────────────────

    @Test
    fun verifyFollowsReferenceFormula() {
        assertEquals(JitRef.Verify.OK, JitRef.verify(true, "true", true))
        assertEquals(JitRef.Verify.NOT_APPLIED, JitRef.verify(true, "false", true))
        assertEquals(JitRef.Verify.CACHE_MISSING, JitRef.verify(true, "true", false))
        assertEquals(JitRef.Verify.CACHE_MISSING, JitRef.verify(true, "true", null))
        // при выключении кэш не требуется: `&& (!pedido || cache != false)`
        assertEquals(JitRef.Verify.OK, JitRef.verify(false, "false", null))
        assertEquals(JitRef.Verify.NOT_APPLIED, JitRef.verify(false, "true", true))
    }

    @Test
    fun noDataIsUnknownNeverSuccess() {
        assertEquals(JitRef.Verify.NO_DATA, JitRef.verify(true, "", null))
        assertEquals(JitRef.Verify.NO_DATA, JitRef.verify(false, "  ", null))
        assertEquals("unknown", JitRef.verifyLabel(JitRef.Verify.NO_DATA))
    }

    // ───────────────────────── продолжение операции ─────────────────────────

    @Test
    fun continuationNeedsSameBoot() {
        assertEquals(JitRef.Continuation.NO_RESTORE, JitRef.continuation(false, false, true))
        assertEquals(JitRef.Continuation.CONTINUE, JitRef.continuation(true, true, true))
        assertEquals(JitRef.Continuation.FOREIGN_BOOT, JitRef.continuation(true, false, true))
        // загрузку опознать нельзя ⇒ тоже не своё (никаких самовольных записей)
        assertEquals(JitRef.Continuation.FOREIGN_BOOT, JitRef.continuation(true, true, false))
    }

    @Test
    fun repeatedClickRefusedOnlyForCurrentBoot() {
        assertTrue(JitRef.canStart(false, false))
        assertFalse("снимок этой загрузки не разобран ⇒ второй клик не начинаем", JitRef.canStart(true, true))
        assertTrue("остаток прошлой загрузки не блокирует новый клик", JitRef.canStart(true, false))
    }

    @Test
    fun delayAndTimeoutMatchReference() {
        assertEquals("postDelayed(0x3a98)", 15_000L, JitRef.RESTORE_DELAY_MS)
        assertEquals("цикл `while [ \$_n -lt 20 ]`", 20, JitRef.DRAIN_MAX_S)
        assertEquals("H1(40000, …)", 40_000, JitRef.CHAIN_TIMEOUT_MS)
    }

    @Test
    fun chainOutcomeDoesNotClaimRestart() {
        assertEquals(JitRef.ChainOutcome.REPLY_RECEIVED, JitRef.chainOutcome(0, ""))
        assertEquals(JitRef.ChainOutcome.CHANNEL_LOST, JitRef.chainOutcome(137, "ERR_ADB_TIMEOUT"))
        assertEquals(JitRef.ChainOutcome.CHANNEL_LOST, JitRef.chainOutcome(0, "ERR_EXCEPTION"))
        assertEquals("нет вердикта «рестарт выполнен»", "reply_received",
            JitRef.chainOutcomeLabel(JitRef.ChainOutcome.REPLY_RECEIVED))
    }
}
