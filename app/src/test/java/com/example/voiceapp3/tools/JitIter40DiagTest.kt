package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * iter40 §3.1 — усечение считается ОТДЕЛЬНО по каждому диагностическому блоку, и сводка `END`
 * отличает «прочитано» от «записано в файл».
 *
 * Проверяется production-код: [LogBudget.DiagState], настоящие сборщики [InternetDiag] и
 * [JitMountDiag] и файловый sink во временном каталоге.
 *
 * Чего тест не доказывает: `AppLog`/`LogFiles` с настоящим `Context` Android на JVM не
 * исполняются — их путь (включая fallback `filesDir/logs`) проверяется в CI/на устройстве.
 */
class DiagTruncationPerBlockTest {

    private class Sink(val file: File, private val budget: LogBudget.DiagState, private val nowMs: Long = 0L) {
        val lines = mutableListOf<String>()
        fun log(msg: String) {
            lines += msg
            val v = budget.decide(msg, msg.toByteArray(Charsets.UTF_8).size + 40, nowMs)
            if (v.write) file.appendText(msg + "\n")
            if (v.notice != null) file.appendText(v.notice + "\n")
        }
        fun fileText(): String = file.readText()
    }

    private fun tmpFile(): File =
        File(Files.createTempDirectory("diag40").toFile(), "voiceapp3-01.01.2026.log")

    @Test
    fun mountBlockExhaustingBudgetStillMarksNetworkBlockSeparately() {
        val budget = LogBudget.DiagState(bytes = 600, lines = 6)
        val f = tmpFile()
        val sink = Sink(f, budget)

        sink.log("JIT_MOUNT_DIAG_BEGIN ts=1")
        repeat(10) { sink.log("JIT_MOUNT_DIAG_DATA name=proc_mounts part=${it + 1}/10 " + "m".repeat(60)) }
        sink.log("JIT_MOUNT_DIAG_END sections=3")

        // Сеть идёт ПОСЛЕ исчерпания бюджета монтированиями.
        sink.log("INTERNET_DIAG_BEGIN ts=2")
        sink.log("INTERNET_DIAG_SECTION name=routes status=ok")
        sink.log("INTERNET_DIAG_DATA name=routes part=1/1 default via 10.0.0.1")
        sink.log("INTERNET_DIAG_END sections=1")

        val text = f.readText()
        assertTrue("усечение монтирований объявлено", text.contains("JIT_MOUNT_DIAG_TRUNCATED"))
        assertTrue(
            "у сетевого блока СВОЯ отметка, а не молчание",
            text.contains("INTERNET_DIAG_TRUNCATED")
        )
        assertTrue("BEGIN сети сохранён", text.contains("INTERNET_DIAG_BEGIN"))
        assertTrue("END сети сохранён", text.contains("INTERNET_DIAG_END"))
        assertTrue(budget.truncatedFor("JIT_MOUNT_DIAG"))
        assertTrue(budget.truncatedFor("INTERNET_DIAG"))
        assertTrue(budget.droppedFor("JIT_MOUNT_DIAG") > 0)
        assertTrue(budget.droppedFor("INTERNET_DIAG") > 0)
        // Уведомление больше не обещает сохранение разделов.
        assertFalse("нет ложного обещания про SECTION", text.contains("разделы и сводка сохраняются"))
        assertTrue(text.contains("SECTION/DATA ограничены"))
    }

    @Test
    fun reverseOrderMarksMountsSeparatelyToo() {
        val budget = LogBudget.DiagState(bytes = 600, lines = 6)
        val f = tmpFile()
        val sink = Sink(f, budget)
        sink.log("INTERNET_DIAG_BEGIN ts=1")
        repeat(10) { sink.log("INTERNET_DIAG_DATA name=wlan0_addr part=${it + 1}/10 " + "n".repeat(60)) }
        sink.log("INTERNET_DIAG_END sections=1")
        sink.log("JIT_MOUNT_DIAG_BEGIN ts=2")
        sink.log("JIT_MOUNT_DIAG_DATA name=proc_mounts part=1/1 x")
        sink.log("JIT_MOUNT_DIAG_END sections=3")

        val text = f.readText()
        assertTrue(text.contains("INTERNET_DIAG_TRUNCATED"))
        assertTrue(text.contains("JIT_MOUNT_DIAG_TRUNCATED"))
        assertTrue(text.contains("JIT_MOUNT_DIAG_BEGIN"))
        assertTrue(text.contains("JIT_MOUNT_DIAG_END"))
        assertTrue(budget.truncatedFor("INTERNET_DIAG"))
        assertTrue(budget.truncatedFor("JIT_MOUNT_DIAG"))
    }

    @Test
    fun onlyTheBlockThatOverflowedIsMarked() {
        val budget = LogBudget.DiagState(bytes = 100_000, lines = 1000)
        val f = tmpFile()
        val sink = Sink(f, budget)
        sink.log("INTERNET_DIAG_BEGIN ts=1")
        sink.log("INTERNET_DIAG_DATA name=routes part=1/1 ok")
        sink.log("INTERNET_DIAG_END sections=1")
        val text = f.readText()
        assertFalse("усечения не было", text.contains("_TRUNCATED"))
        assertFalse(budget.truncatedFor("INTERNET_DIAG"))
        assertFalse(budget.truncatedFor("JIT_MOUNT_DIAG"))
    }

    @Test
    fun competingWritersKeepPerBlockAccounting() {
        val budget = LogBudget.DiagState(bytes = 4000, lines = 1000)
        val threads = listOf("INTERNET_DIAG" to Thread {
            repeat(200) { budget.decide("INTERNET_DIAG_DATA строка", 60, 0) }
        }, "JIT_MOUNT_DIAG" to Thread {
            repeat(200) { budget.decide("JIT_MOUNT_DIAG_DATA строка", 60, 0) }
        })
        threads.forEach { it.second.start() }
        threads.forEach { it.second.join(5000) }
        assertTrue(budget.usedBytes() <= 4000 + 120)
        assertTrue(
            "оба блока получили свою отметку",
            budget.truncatedFor("INTERNET_DIAG") && budget.truncatedFor("JIT_MOUNT_DIAG")
        )
    }

    @Test
    fun networkEndDistinguishesReadFromWritten() {
        val shell = object : JitRef.JitShell {
            override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
                val section = Regex("__JMD_(\\w+)_RC").find(command)?.groupValues?.get(1) ?: return 0 to ""
                return 0 to "data\n__JMD_${section}_RC=0\n__JMD_EOF=1"
            }
            override fun mapsExcerpt(): String? = null
        }
        for ((probe, expected) in listOf<Pair<(() -> Boolean)?, String>>(
            ({ true }) to "written=truncated",
            ({ false }) to "written=full",
            (null as (() -> Boolean)?) to "written=unknown"
        )) {
            val lines = mutableListOf<String>()
            InternetDiag.collect(shell, true, null, { lines += it }, "test", "1.0.61", probe)
            val end = lines.last()
            assertTrue("END блока: $expected (получено: $end)", end.contains(expected))
            assertTrue("END — это END", end.startsWith("INTERNET_DIAG_END"))
        }
    }

    @Test
    fun mountEndDistinguishesReadFromWritten() {
        val shell = object : JitRef.JitShell {
            override fun exec(command: String, timeoutMs: Int) = 0 to
                "1 0 8:1 / /data/app/com.example rw 0 0\n__JMD_ID_RC=0\n__JMD_NSSELF_RC=0\n" +
                "__JMD_NSINIT_RC=0\n__JMD_WC_RC=0\n__JMD_CAT_RC=0\n__JMD_EOF=1"
            override fun mapsExcerpt(): String? = null
        }
        val lines = mutableListOf<String>()
        JitMountDiag.collect(shell, true, { lines += it }, "test") { true }
        val end = lines.last { it.startsWith("JIT_MOUNT_DIAG_END") }
        assertTrue("в сводке видно, что запись усечена: $end", end.contains("written=truncated"))
    }
}

/**
 * iter40 §3.2 — редакция личных данных не должна портить адреса и должна честно сообщать
 * `redacted=yes`.
 *
 * Проверяется настоящий [InternetDiag.redact]/[InternetDiag.redactWithFlag] и полный
 * [InternetDiag.collect] на подставном shell с реальным текстом `ip addr`/`dumpsys`.
 */
class InternetDiagRedaction40Test {

    @Test
    fun ssidWithSpacesAndQuotesIsRemovedEntirely() {
        val cases = listOf(
            "SSID: \"My Private Network\"" to "My Private Network",
            "SSID: 'Owner iPhone'" to "Owner iPhone",
            "ssid=\"Net, with comma\"" to "Net, with comma",
            "BSSID: 8C:1A:BF:11:22:33" to "8C:1A:BF:11:22:33",
            "mWifiInfo SSID: \"Домашняя сеть\" BSSID: aa:bb:cc:dd:ee:ff" to "Домашняя сеть"
        )
        for ((text, secret) in cases) {
            val (out, changed) = InternetDiag.redactWithFlag(text)
            assertTrue("замена произошла: $text", changed)
            assertFalse("«$secret» не должно остаться", out.contains(secret, ignoreCase = true))
            assertTrue("поле сохранено как факт: $out", out.lowercase().contains("ssid") || out.lowercase().contains("bssid"))
        }
    }

    @Test
    fun ipv6WithTwoDigitGroupsIsNotDamaged() {
        val v6 = "inet6 fe80::1a:2b:3c:4d:5e:6f/64 scope link"
        val (out, changed) = InternetDiag.redactWithFlag(v6)
        assertEquals("адрес не должен меняться", v6, out)
        assertFalse("ложной замены нет", changed)
        val full = "inet6 2001:db8:0:1:2a:3b:4c:5d/64 scope global"
        assertEquals(full, InternetDiag.redact(full))
    }

    @Test
    fun macInsideLongerStructureIsNotSliced() {
        val text = "link/ether 34:5b:bb:1a:2c:3d brd ff:ff:ff:ff:ff:ff"
        val out = InternetDiag.redact(text)
        assertFalse(out.contains("34:5b:bb:1a:2c:3d"))
        assertTrue("broadcast тоже MAC-подобный и скрыт", !out.contains("ff:ff:ff:ff:ff:ff"))
        assertTrue(out.contains("link/ether"))
    }

    @Test
    fun redactedFlagReflectsActualReplacement() {
        val shell = object : JitRef.JitShell {
            override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
                val section = Regex("__JMD_(\\w+)_RC").find(command)?.groupValues?.get(1) ?: return 0 to ""
                val leak = "3: wlan0: <UP>\n    link/ether aa:bb:cc:dd:ee:ff\n" +
                    "    inet 192.168.43.117/24 scope global wlan0\n" +
                    "    SSID: \"My Private Network\""
                return 0 to "$leak\n__JMD_${section}_RC=0\n__JMD_EOF=1"
            }
            override fun mapsExcerpt(): String? = null
        }
        val lines = mutableListOf<String>()
        InternetDiag.collect(shell, true, null, { lines += it }, "test", "1.0.61")
        val all = lines.joinToString("\n")
        assertFalse("MAC не попал", all.contains("aa:bb:cc:dd:ee:ff"))
        assertFalse("SSID не попал", all.contains("My Private Network"))
        assertTrue("IP сохранён для анализа", all.contains("192.168.43.117/24"))
        assertTrue("флаг не врёт", all.contains("redacted=yes"))
        assertFalse(all.contains("redacted=no") && all.contains("link/ether"))
    }

    @Test
    fun cleanOutputIsReportedAsNotRedacted() {
        val shell = object : JitRef.JitShell {
            override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
                val section = Regex("__JMD_(\\w+)_RC").find(command)?.groupValues?.get(1) ?: return 0 to ""
                return 0 to "0: from all lookup local\n__JMD_${section}_RC=0\n__JMD_EOF=1"
            }
            override fun mapsExcerpt(): String? = null
        }
        val lines = mutableListOf<String>()
        InternetDiag.collect(shell, true, null, { lines += it }, "test", "1.0.61")
        assertTrue(lines.any { it.contains("name=ip_rules") && it.contains("redacted=no") })
    }

    @Test
    fun transportErrorWithMacIsRedacted() {
        val shell = object : JitRef.JitShell {
            override fun exec(command: String, timeoutMs: Int): Pair<Int, String> =
                1 to "ERR_ADB_IO peer 34:5b:bb:1a:2c:3d closed; ssid=HomeNet"
            override fun mapsExcerpt(): String? = null
        }
        val lines = mutableListOf<String>()
        InternetDiag.collect(shell, true, null, { lines += it }, "test", "1.0.61")
        val all = lines.joinToString("\n")
        assertFalse(all.contains("34:5b:bb:1a:2c:3d"))
        assertFalse(all.contains("HomeNet"))
    }
}
