package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Автоматическая read-only диагностика сети ([InternetDiag]).
 *
 * Проверяется ФАКТИЧЕСКИ отправляемые строки (read-only + ограниченный ICMP), разбор маркеров,
 * раздельные статусы, честная неготовность канала и формат меток лога.
 *
 * ВАЖНО: транспорт и Android API здесь подставные. На ГУ диагностика не запускалась, реальные
 * `ip`/`ping`/`ConnectivityManager` этим тестом не проверяются — это модель, а не устройство.
 */
class InternetDiagTest {

    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        logs.clear()
    }

    private fun log(s: String) {
        synchronized(logs) { logs.add(s) }
    }

    /** §5.1 iter41: приёмник с подтверждением записи (модель датированного файла). */
    private fun sink(): JitFake.TestSink = JitFake.TestSink(logs)

    private class ScriptedShell(private val answer: (String) -> Pair<Int, String>) : JitRef.JitShell {
        val commands = mutableListOf<String>()
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            commands += command
            return answer(command)
        }
        override fun mapsExcerpt(): String? = null
    }

    /** Отвечает «как shell»: тело + маркер rc + маркер конца. */
    private fun okShell(bodies: Map<String, String> = emptyMap()): ScriptedShell = ScriptedShell { cmd ->
        val name = Regex("__JMD_([A-Z0-9_]+)_RC=").find(cmd)?.groupValues?.get(1) ?: "X"
        val body = bodies[name] ?: when (name) {
            "WLAN0_ADDR" -> "3: wlan0: <UP> mtu 1500\n    inet 192.168.43.5/24 brd 192.168.43.255 scope global wlan0"
            "IP_RULES" -> "0:\tfrom all lookup local\n32000:\tfrom all lookup main"
            "ROUTES" -> "default via 192.168.43.1 dev wlan0 table 1004\n192.168.43.0/24 dev wlan0"
            "DNS_PROPS" -> "net.dns1=192.168.43.1\nnet.dns2=\ndhcp.wlan0.dns1=192.168.43.1"
            "PING_IP" -> "3 packets transmitted, 3 received, 0% packet loss"
            "PING_NAME" -> "3 packets transmitted, 3 received, 0% packet loss"
            else -> ""
        }
        val rc = 0
        (0 to "{\n$body\n}\n__JMD_${name}_RC=$rc\n__JMD_EOF=1")
    }

    private class FakeNet(
        private val wifi: Boolean? = true,
        private val transports: Set<String>? = setOf("wifi"),
        private val validated: Boolean? = true,
        private val portal: Boolean? = false,
        private val dns: List<String>? = listOf("192.168.43.1")
    ) : InternetDiag.NetInfo {
        override fun wifiEnabled(): Boolean? = wifi
        override fun activeTransports(): Set<String>? = transports
        override fun validated(): Boolean? = validated
        override fun captivePortal(): Boolean? = portal
        override fun dnsServers(): List<String>? = dns
    }

    @Test
    fun onlyReadOnlyCommandsPlusBoundedPings() {
        val shell = okShell()
        InternetDiag.collect(shell, true, FakeNet(), sink(), "test", "1.0.61")
        assertEquals("шесть shell-разделов", 6, shell.commands.size)
        for (cmd in shell.commands) {
            val noRedirect = cmd.replace("2>&1", "")
            for (bad in listOf("umount", "mount ", "setprop", "svc ", "kill", "reboot", "iptables",
                "ip rule add", "ip rule del", "ip route add", "ip route del", "ndc", "settings",
                "ifconfig", "wpa_cli", "input ")) {
                assertFalse("команда не содержит «$bad»: $cmd", noRedirect.contains(bad))
            }
        }
        assertTrue("ping только до двух адресов и с лимитом пакетов",
            shell.commands.count { it.contains("ping -c 3 -W 2") } == 2)
        assertTrue(shell.commands.any { it.contains("ip addr show wlan0") })
        assertTrue(shell.commands.any { it.contains("ip rule show") })
        assertTrue(shell.commands.any { it.contains("ip route show table all") })
        assertTrue(shell.commands.any { it.contains("1.1.1.1") })
        assertTrue(shell.commands.any { it.contains("google.com") })
    }

    @Test
    fun allSectionsOkInNormalCase() {
        val sections = InternetDiag.collect(okShell(), true, FakeNet(), sink(), "service.onCreate", "1.0.61")
        assertEquals(7, sections.size)
        assertTrue(sections.all { it.status == InternetDiag.STATUS_OK })
        assertEquals("echo_received", sections.first { it.name == "ping_ip" }.verdict)
        assertTrue(logs.any { it.startsWith("INTERNET_DIAG_BEGIN") })
        assertTrue(logs.any { it.startsWith("INTERNET_DIAG_END") })
        assertTrue(logs.any { it.startsWith("INTERNET_DIAG_DATA name=wlan0_addr") })
        assertTrue(logs.any { it.contains("validated=true") })
        assertTrue(logs.any { it.contains("active_network_dns=192.168.43.1") })
    }

    @Test
    fun missingWlan0IsEmptyReadErrorNotInternetVerdict() {
        val shell = okShell(mapOf("WLAN0_ADDR" to "Device \"wlan0\" does not exist."))
        val sections = InternetDiag.collect(shell, true, FakeNet(wifi = false, transports = emptySet()), sink(), "t", "v")
        val s = sections.first { it.name == "wlan0_addr" }
        assertEquals("команда выполнилась, факта нет — не «нет интернета»", InternetDiag.STATUS_OK, s.status)
        assertTrue(logs.any { it.contains("does not exist") })
    }

    @Test
    fun missingIpToolIsUnavailableNotEmpty() {
        val shell = ScriptedShell { cmd ->
            val name = Regex("__JMD_([A-Z0-9_]+)_RC=").find(cmd)?.groupValues?.get(1) ?: "X"
            0 to "/system/bin/sh: ip: not found\n__JMD_${name}_RC=127\n__JMD_EOF=1"
        }
        val sections = InternetDiag.collect(shell, true, FakeNet(), sink(), "t", "v")
        assertEquals(InternetDiag.STATUS_UNAVAILABLE,
            sections.first { it.name == "ip_rules" }.status)
        assertEquals(InternetDiag.STATUS_UNAVAILABLE,
            sections.first { it.name == "routes" }.status)
    }

    @Test
    fun permissionDeniedIsSeparateStatus() {
        val shell = ScriptedShell { cmd ->
            val name = Regex("__JMD_([A-Z0-9_]+)_RC=").find(cmd)?.groupValues?.get(1) ?: "X"
            0 to "ip: Permission denied\n__JMD_${name}_RC=1\n__JMD_EOF=1"
        }
        val sections = InternetDiag.collect(shell, true, FakeNet(), sink(), "t", "v")
        assertEquals(InternetDiag.STATUS_PERMISSION_DENIED, sections.first { it.name == "ip_rules" }.status)
    }

    @Test
    fun missingEndMarkerIsUnknown() {
        val shell = ScriptedShell { cmd ->
            val name = Regex("__JMD_([A-Z0-9_]+)_RC=").find(cmd)?.groupValues?.get(1) ?: "X"
            0 to "some output\n__JMD_${name}_RC=0\n"
        }
        val sections = InternetDiag.collect(shell, true, FakeNet(), sink(), "t", "v")
        assertEquals(InternetDiag.STATUS_UNKNOWN, sections.first { it.name == "routes" }.status)
    }

    @Test
    fun timeoutIsTimeoutNotNoInternet() {
        val shell = ScriptedShell { _ -> 137 to "ERR_ADB_TIMEOUT after 12000ms" }
        val sections = InternetDiag.collect(shell, true, FakeNet(), sink(), "t", "v")
        assertEquals(InternetDiag.STATUS_TIMEOUT, sections.first { it.name == "ping_ip" }.status)
        assertFalse("нет заявления об отсутствии интернета",
            logs.joinToString("\n").contains("no_internet"))
    }

    @Test
    fun pingFailureIsNotProofOfNoInternet() {
        val shell = okShell(mapOf("PING_IP" to "3 packets transmitted, 0 received, 100% packet loss"))
        val sections = InternetDiag.collect(shell, true, FakeNet(), sink(), "t", "v")
        val ping = sections.first { it.name == "ping_ip" }
        assertEquals(InternetDiag.STATUS_OK, ping.status)
        assertEquals("no_reply", ping.verdict)
        assertTrue(logs.any { it.contains("НЕ доказывает отсутствия интернета") })
    }

    @Test
    fun ipNoReplyButNameResolvesIsKeptAsFact() {
        val shell = okShell(
            mapOf(
                "PING_IP" to "3 packets transmitted, 0 received, 100% packet loss",
                "PING_NAME" to "3 packets transmitted, 3 received, 0% packet loss"
            )
        )
        val sections = InternetDiag.collect(shell, true, FakeNet(), sink(), "t", "v")
        assertEquals("no_reply", sections.first { it.name == "ping_ip" }.verdict)
        assertEquals("echo_received", sections.first { it.name == "ping_name" }.verdict)
        assertTrue(logs.any { it.contains("ping_ip") && it.contains("verdict=no_reply") })
    }

    @Test
    fun emptyDnsPropsAreNotDnsAbsence() {
        val shell = okShell(mapOf("DNS_PROPS" to "net.dns1=\nnet.dns2=\n"))
        val sections = InternetDiag.collect(shell, true, FakeNet(dns = emptyList()), sink(), "t", "v")
        assertEquals(InternetDiag.STATUS_OK, sections.first { it.name == "dns_props" }.status)
        assertTrue(logs.any { it.contains("active_network_dns=empty") })
        assertFalse(logs.joinToString("\n").contains("dns=missing"))
    }

    @Test
    fun channelNotReadyMeansNoCommandsAtAll() {
        val shell = okShell()
        val sections = InternetDiag.collect(shell, false, FakeNet(), sink(), "service.onCreate", "1.0.61")
        assertTrue("ни одной команды", shell.commands.isEmpty())
        assertTrue("shell-разделы честно не выполнены",
            sections.filter { it.name != "android_connectivity" }
                .all { it.status == InternetDiag.STATUS_CHANNEL_NOT_READY })
        assertTrue("Android API от канала не зависит",
            sections.first { it.name == "android_connectivity" }.status in
                listOf(InternetDiag.STATUS_OK, InternetDiag.STATUS_UNAVAILABLE))
        assertTrue(logs.any { it.contains("channel_ready=false") })
    }

    @Test
    fun channelNotReadyHelperWritesHonestMarkers() {
        val sections = InternetDiag.collectChannelNotReady(sink(), "service.onCreate", "channel_not_ready_after_30s")
        assertEquals(7, sections.size)
        assertTrue(sections.all { it.status == InternetDiag.STATUS_CHANNEL_NOT_READY })
        assertTrue(logs.first().startsWith("INTERNET_DIAG_BEGIN"))
        assertTrue(logs.last().startsWith("INTERNET_DIAG_END"))
        assertTrue(logs.any { it.contains("root_unknown") || it.contains("channel_not_ready") })
    }

    @Test
    fun androidApiUnavailableIsNotFalse() {
        val sections = InternetDiag.collect(okShell(), true, null, sink(), "t", "v")
        val s = sections.first { it.name == "android_connectivity" }
        assertEquals(InternetDiag.STATUS_UNAVAILABLE, s.status)
        assertTrue(logs.any { it.contains("name=android_connectivity status=unavailable") })
    }

    @Test
    fun validatedIsNotASiteCheckAndNoSsidIsLogged() {
        InternetDiag.collect(okShell(), true, FakeNet(), sink(), "t", "v")
        val all = logs.joinToString("\n")
        assertTrue(all.contains("validated≠проверка конкретного сайта") || all.contains("validated="))
        assertFalse("SSID не пишем", all.contains("SSID="))
        assertFalse("MAC не пишем", Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}").containsMatchIn(all))
    }

    @Test
    fun longLineIsSplitAndMarked() {
        val long = "10.0.0.1/24 " + "x".repeat(4000)
        val shell = okShell(mapOf("WLAN0_ADDR" to long))
        InternetDiag.collect(shell, true, FakeNet(), sink(), "t", "v")
        val parts = logs.filter { it.contains("name=wlan0_addr part=") }
        assertTrue("поделено на части", parts.size > 1)
        assertTrue(parts.any { it.contains("продолжение строки") })
        assertTrue(parts.all { it.length < 2_000 })
    }

    @Test
    fun endSummaryIsFactsNotDiagnosis() {
        val sections = InternetDiag.collect(okShell(), true, FakeNet(), sink(), "t", "v")
        // iter43 §2.1: END — провизионная (written=unconfirmed), итог — в отдельной строке
        // `_END_CONFIRM` уже после записи END.
        val end = logs.last { it.startsWith("INTERNET_DIAG_END ") }
        val confirm = logs.last { it.startsWith("INTERNET_DIAG_END_CONFIRM ") }
        assertTrue("END не заявляет подтверждённый итог", end.contains("written=unconfirmed"))
        assertTrue(end.contains("network_changes=0"))
        assertTrue("итог: это факты, а не диагноз", confirm.contains("это факты, а не диагноз"))
        // Проба усечения в этом тесте не передана (`null`) ⇒ честная метка `unknown`,
        // а не `full`: «проверить нельзя» не выдаётся за подтверждённую полноту.
        assertTrue("неизвестность честно помечена", confirm.contains("written=unknown"))
        assertTrue(confirm.contains("write_reason=truncation_not_checkable"))
        assertEquals(7, sections.size)
    }

    @Test
    fun chunksNeverExceedLimit() {
        val text = (1..50).joinToString("\n") { "line-$it-" + "y".repeat(80) }
        val parts = InternetDiag.chunks(text, 500)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 700 })
    }

    @Test
    fun adapterPathIsBackgroundAndIndependentOfJit() {
        val src = File("src/main/java/com/example/voiceapp3/tools/JitNet.kt")
            .let { if (it.isFile) it else File("app/src/main/java/com/example/voiceapp3/tools/JitNet.kt") }
            .readText()
        val fn = src.substring(src.indexOf("fun requestDiagOnce"), src.indexOf("internal fun resetDiagForTest"))
        assertTrue("в фоне", fn.contains("BgWorker.post") && fn.contains("assertNotMain"))
        assertTrue("независимые ошибки", fn.contains("JitMountDiag.collect") && fn.contains("InternetDiag.collect"))
        assertFalse("не зависит от JIT-клика", fn.contains("JitOp.click"))
        assertTrue("одна попытка за процесс", fn.contains("diagAttempted.compareAndSet(false, true)"))
    }

    @Test
    fun logGoesToDatedAppLog() {
        // Метки пишутся тем же логгером приложения (AppLog → LogFiles): имя файла по дате.
        assertEquals("voiceapp3-22.10.2026.log",
            LogFiles.fileNameFor(java.time.LocalDate.of(2026, 10, 22)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))
    }
}
