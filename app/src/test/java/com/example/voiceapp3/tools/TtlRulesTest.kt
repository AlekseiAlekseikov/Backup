package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33k §3 — правила TTL/HL со СКОУПОМ. Тест фиксирует: глобального правила без скоупа
 * не бывает, порядок вариантов (сначала `uplink`, затем `! -o carplay`), строки лога.
 */
class TtlRulesTest {

    private val route = "1.1.1.1 via 192.168.1.1 dev wlan0 src 192.168.1.5 uid 1000"
    private val link = """
        1: lo: <LOOPBACK,UP> mtu 65536
        2: wlan0: <BROADCAST,UP> mtu 1500
        3: rndis0: <BROADCAST,UP> mtu 1500
        4: dummy0: <BROADCAST,NOARP> mtu 1500
    """.trimIndent()

    // 1. uplink из `ip route get`.
    @Test
    fun uplinkParsed() {
        assertEquals("wlan0", TtlRules.uplinkOf(route))
        assertEquals(null, TtlRules.uplinkOf("unreachable default"))
    }

    // 2. CarPlay-интерфейсы распознаны, обычные — нет.
    @Test
    fun carplayDetected() {
        val cp = TtlRules.carplayIfaces(link)
        assertEquals(listOf("rndis0"), cp)
        assertFalse(cp.contains("wlan0"))
        assertFalse(cp.contains("lo"))
        assertTrue(TtlRules.allIfaces(link).containsAll(listOf("wlan0", "rndis0", "dummy0")))
    }

    // 3. iter33m §1.2: единственный вариант — uplink; «всё кроме CarPlay» удалён.
    @Test
    fun variantsOrderedAndScoped() {
        val v = TtlRules.variants("wlan0", listOf("rndis0"))
        assertEquals(1, v.size)
        assertEquals("uplink", v[0].first)
        assertEquals("-A POSTROUTING -o wlan0", v[0].second)

        // без uplink правила нет вовсе: `! -o rndis0` не покрывал групповой p2p-p2p0-0
        assertTrue("fallback exclude_carplay удалён", TtlRules.variants(null, listOf("rndis0")).isEmpty())
    }

    // 4. Нет ни uplink, ни CarPlay — вариантов нет (глобальное правило запрещено).
    @Test
    fun noGlobalFallback() {
        assertTrue(TtlRules.variants(null, emptyList()).isEmpty())
        for ((_, scope) in TtlRules.variants("wlan0", listOf("rndis0"))) {
            assertTrue("правило обязано быть со скоупом: $scope", scope.contains(" -o "))
            assertFalse("правило без -o/-i запрещено: $scope", scope == "-A POSTROUTING")
        }
    }

    // 5. Текст правил: 65 и для v4 (TTL), и для v6 (HL).
    @Test
    fun ruleText() {
        assertTrue(TtlRules.ruleV4("-A POSTROUTING -o wlan0").endsWith("-j TTL --ttl-set 65"))
        assertTrue(TtlRules.ruleV6("-A POSTROUTING -o wlan0").endsWith("-j HL --hl-set 65"))
    }

    // 6. Строки лога мандата §3.2–§3.4.
    @Test
    fun lines() {
        val net = TtlRules.netLine(listOf("wlan0", "rndis0"), listOf("rndis0"), "wlan0", 1, 1)
        assertTrue(net, net.startsWith("TTL_NET ifaces=wlan0,rndis0 carplay=rndis0 uplink=wlan0 v4=1 v6=1"))

        val tryL = TtlRules.tryLine("uplink", "-A POSTROUTING -o wlan0", 1, 1)
        assertTrue(tryL, tryL.contains("TTL_TRY variant=uplink scope=\"-A POSTROUTING -o wlan0\" v4=1 v6=1 carplay=unknown"))

        assertEquals("TTL_OFF v4=0 v6=0 result=OK", TtlRules.offLine(0, 0))
        assertEquals("TTL_OFF v4=1 v6=0 result=FAILED", TtlRules.offLine(1, 0))

        val boot = TtlRules.bootLine(1, 1)
        assertTrue(boot, boot == "TTL_BOOT rules_restored=yes v4=1 v6=1 scoped=true")
        assertTrue(TtlRules.bootLine(0, 0).contains("rules_restored=no"))
    }
}
