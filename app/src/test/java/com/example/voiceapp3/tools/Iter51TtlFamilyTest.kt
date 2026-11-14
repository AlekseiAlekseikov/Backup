package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter51 §4.3(1) — IPv6: «нет в системе» и «есть, но не применился» — РАЗНЫЕ факты.
 *
 * Дефект на HEAD: для ON достаточно было `VERIFY_V4=OK` + `uplink_only=yes`, поэтому
 * `VERIFY_V6=FAIL` (правило v6 не встало или не прочиталось) давал ПОЛНЫЙ `OK` — половина
 * семейства не применена, а интерфейс и `TtlResult` заявляли успех.
 *
 * Проверяется настоящий production-парсер `TtlVerify.parse` на выводе, который печатает
 * сгенерированный `TtlScript.build(true)` (форматы строк взяты из него же).
 */
class Iter51TtlFamilyTest {

    private val script by lazy { TtlScript.build(true) }

    /** Собрать вывод в формате production-скрипта. */
    private fun output(
        verifyV4: String,
        verifyV6: String,
        chain4: String = "ours",
        chain6: String = "ours",
        verdict: String = "",
        extra: String = ""
    ): String = """
        TTL_OWNERSHIP v4=$chain4 v6=$chain6 v6_supported=1 read_v4=ok read_v6=ok own_chain=VAPP_TTL
        TTL_TRY variant=uplink scope="-o wlan0" carplay=none
        TTL_ADD family=v4 chain=VAPP_TTL rc=0
        TTL_ADD family=v4 jump scope="-o wlan0" rc=0
        TTL_ADD family=v6 chain=VAPP_TTL rc=0
        TTL_ADD family=v6 jump scope="-o wlan0" rc=0
        TTL_V4=1 TTL_V6=1 OWN_V4=1 OWN_V6=1 OWN_J4=1 OWN_J6=1 FOREIGN_V4=0 FOREIGN_V6=0
        VERIFY_V4=$verifyV4 VERIFY_V6=$verifyV6 CHAIN4=$chain4 CHAIN6=$chain6 READ_V4=ok READ_V6=ok
        TTL_SCOPE result=scoped scoped=yes uplink_only=yes uplink=wlan0 rule="-o wlan0 -j VAPP_TTL"
        TTL_CHECK: rule_v4=65 rule_v6=65 real=UNKNOWN expected=65 src=10.0.0.5 dst=1.1.1.1 iface=wlan0 method=rules_not_wire reason=wire_not_measured jumps_v4=1 jumps_v6=1 chain_v4=$chain4 chain_v6=$chain6 ? $verdict
        TTL_RESULT rule_v4=65 rule_v6=65 verify_v4=$verifyV4 verify_v6=$verifyV6 wire_v4=not_measured enable=1 scoped=yes uplink=wlan0 variant=uplink own_chain=VAPP_TTL foreign_v4=0 foreign_v6=0 chain_v4=$chain4 chain_v6=$chain6 read_v4=ok read_v6=ok
        $extra
    """.trimIndent()

    @Test
    fun scriptItselfEmitsSeparateFamilyVerdicts() {
        // Формат строк — из production-скрипта, а не придуман в тесте.
        assertTrue(script.contains("VERIFY_V4="))
        assertTrue(script.contains("VERIFY_V6="))
        assertTrue(script.contains("SCOPED_V4_ONLY"))
    }

    @Test
    fun v4OkWithV6FailIsNotFullSuccess() {
        val p = TtlVerify.parse(true, output("OK", "FAIL"))
        assertNotEquals("v4 OK + v6 FAIL — это НЕ полный успех: ${p.detail}",
            TtlVerify.Verdict.OK, p.verdict)
        assertTrue("состояние названо частичным: ${p.detail}",
            p.verdict == TtlVerify.Verdict.PARTIAL)
        assertTrue("причина указывает на IPv6: ${p.reason}",
            p.reason?.contains("IPv6") == true && p.reason?.contains("VERIFY_V6=FAIL") == true)
    }

    @Test
    fun v4OkWithV6NotAppliedIsNotFullSuccess() {
        val p = TtlVerify.parse(true, output("OK", "NOT_APPLIED"))
        assertNotEquals(TtlVerify.Verdict.OK, p.verdict)
        assertTrue(p.reason?.contains("IPv6") == true)
    }

    @Test
    fun v4OkWithV6UnreadableIsNotFullSuccess() {
        val p = TtlVerify.parse(true, output("OK", "UNKNOWN"))
        assertNotEquals("нечитаемое состояние v6 не равно «всё хорошо»", TtlVerify.Verdict.OK, p.verdict)
    }

    @Test
    fun bothFamiliesOkIsFullSuccess() {
        val p = TtlVerify.parse(true, output("OK", "OK"))
        assertEquals(TtlVerify.Verdict.OK, p.verdict)
    }

    @Test
    fun absentIpv6IsHonestV4OnlyNotFailure() {
        // IPv6 в системе нет: это не ошибка, но и не «оба семейства проверены».
        val out = output("OK", "SKIPPED").replace("v6_supported=1", "v6_supported=0")
        val p = TtlVerify.parse(true, out)
        assertEquals("отсутствие IPv6 не FAILED", TtlVerify.Verdict.OK, p.verdict)
        assertTrue("и это видно в детали: ${p.detail}", p.detail.contains("ТОЛЬКО IPv4"))
    }

    @Test
    fun v4FailureStillFails() {
        val p = TtlVerify.parse(true, output("FAIL", "OK"))
        assertEquals(TtlVerify.Verdict.FAILED, p.verdict)
    }
}
