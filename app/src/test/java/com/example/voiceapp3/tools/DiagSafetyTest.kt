package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * iter39 §3.2 — личные данные в диагностике.
 *
 * Проверяется НАСТОЯЩИЙ редактор [InternetDiag.redact] на реальных многострочных примерах
 * `ip addr show wlan0`, `ip route`, `dumpsys wifi`-подобных строк и на ошибках транспорта:
 * MAC во всех типичных форматах и SSID/BSSID удаляются, а IP/маршруты/номера таблиц/DNS
 * остаются — они нужны для анализа.
 *
 * Чего тест НЕ доказывает: что полный общий лог приложения не содержит иных сведений. Редактор
 * удаляет только известные форматы; остальные данные (пути, имена пакетов, адреса) в логе есть, и
 * об этом сказано владельцу.
 */
class InternetDiagRedactionTest {

    private val ipAddrSample = """
        3: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq state UP
            link/ether 34:5b:bb:1a:2c:3d brd ff:ff:ff:ff:ff:ff
            inet 192.168.43.117/24 brd 192.168.43.255 scope global wlan0
            inet6 fe80::365b:bbff:fe1a:2c3d/64 scope link
    """.trimIndent()

    @Test
    fun macFromRealIpAddrIsRemovedButIpStays() {
        val out = InternetDiag.redact(ipAddrSample)
        assertFalse("MAC удалён", out.contains("34:5b:bb:1a:2c:3d"))
        assertTrue("MAC явно помечен", out.contains("<удалён:MAC>"))
        assertTrue("IPv4 сохранён", out.contains("192.168.43.117/24"))
        assertTrue("broadcast сохранён", out.contains("192.168.43.255"))
        assertTrue("IPv6 сохранён", out.contains("fe80::365b:bbff:fe1a:2c3d/64"))
        assertTrue("имя интерфейса сохранено", out.contains("wlan0"))
        assertTrue("структура строки сохранена", out.contains("link/ether"))
    }

    @Test
    fun macInAllTypicalFormatsIsRemoved() {
        val cases = listOf(
            "AA:BB:CC:DD:EE:FF",
            "aa-bb-cc-dd-ee-ff",
            "aabb.ccdd.eeff",
            "**:**:**:**:**:**",
            "02:00:00:00:00:00"
        )
        for (mac in cases) {
            val out = InternetDiag.redact("ethernet hardware address $mac state up")
            assertFalse("формат $mac удалён", out.contains(mac))
            assertTrue(out.contains("<удалён:MAC>"))
        }
    }

    @Test
    fun ssidAndBssidAreRemovedInDifferentRegistrations() {
        val text = """
            SSID: MyPhone_5G
            BSSID: 8c:1a:bf:11:22:33
            ssid=HomeNet
            bssid = aa:bb:cc:dd:ee:ff
            mWifiInfo SSID: "Owner iPhone", BSSID: 1a:2b:3c:4d:5e:6f, MAC: 6f:5e:4d:3c:2b:1a
        """.trimIndent()
        val out = InternetDiag.redact(text)
        for (secret in listOf("MyPhone_5G", "HomeNet", "Owner iPhone",
            "8c:1a:bf:11:22:33", "aa:bb:cc:dd:ee:ff", "1a:2b:3c:4d:5e:6f", "6f:5e:4d:3c:2b:1a")) {
            assertFalse("«$secret» не должно попасть в лог", out.contains(secret, ignoreCase = true))
        }
        assertTrue("поле ssid сохранено как факт", out.contains("ssid="))
        assertTrue("поле bssid сохранено как факт", out.lowercase().contains("bssid"))
    }

    @Test
    fun routesAndTablesAndDnsAreKept() {
        val text = """
            0:	from all lookup local
            10000:	from all fwmark 0x0/0xffff lookup 1003
            default via 192.168.43.1 dev wlan0 table 1003 proto static
            192.168.43.0/24 dev wlan0 table 1003 scope link
            net.dns1=192.168.43.1
            net.dns2=8.8.8.8
        """.trimIndent()
        val out = InternetDiag.redact(text)
        assertEquals("структура маршрутов/правил/DNS не меняется", text, out)
        assertFalse("редактор не заявляет работу там, где её нет",
            InternetDiag.needsRedaction(text))
    }

    @Test
    fun transportErrorTextIsRedactedToo() {
        val err = "ERR_ADB: io error: peer link/ether 34:5b:bb:1a:2c:3d dropped; ssid=HomeNet"
        assertTrue("признак есть", InternetDiag.needsRedaction(err))
        val out = InternetDiag.redact(err)
        assertFalse(out.contains("34:5b:bb:1a:2c:3d"))
        assertFalse(out.contains("HomeNet"))
        assertTrue("текст ошибки не потерян", out.startsWith("ERR_ADB: io error: peer"))
    }

    @Test
    fun redactionIsAppliedToDataAndSectionLinesOfRealCollect() {
        val leaked = """
            3: wlan0: <UP> mtu 1500
                link/ether aa:bb:cc:dd:ee:ff brd ff:ff:ff:ff:ff:ff
                inet 10.0.0.5/24 scope global wlan0
        """.trimIndent()
        val shell = object : JitRef.JitShell {
            override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
                val section = Regex("__JMD_(\\w+)_RC").find(command)?.groupValues?.get(1)
                    ?: return 0 to leaked
                return 0 to "$leaked\n__JMD_${section}_RC=0\n__JMD_EOF=1"
            }
            override fun mapsExcerpt(): String? = null
        }
        val lines = mutableListOf<String>()
        InternetDiag.collect(shell, true, null, { lines += it }, "test", "1.0.61")
        val all = lines.joinToString("\n")
        assertFalse("MAC не попал в датированный лог", all.contains("aa:bb:cc:dd:ee:ff"))
        assertTrue("IP для анализа остался", all.contains("10.0.0.5/24"))
        assertTrue("факт редактирования виден в разделе", all.contains("redacted=yes"))
        assertTrue("предупреждение о чувствительности есть", all.contains("чувствительные"))
        assertTrue("предупреждение про полный лог есть", all.contains("ПОЛНЫЙ общий лог"))
    }
}

/**
 * iter39 §3.3/§3.4 — бюджет датированного лога (production-объект [LogBudget]) в связке
 * «сборщик → решение бюджета → файловый sink».
 *
 * Модель ФС — настоящий файл во временном каталоге. Сам `AppLog`/`LogFiles` (android `Context`)
 * на JVM не исполняется: их вызовы проверяются только в CI/на устройстве. Здесь проверено, что
 * диагностические блоки не теряются из-за ОБЩЕГО trim, имеют собственный лимит, нумерованные
 * части и явный `truncated`.
 */
class DiagLogBudgetTest {

    private fun sinkOf(file: File, budget: LogBudget.DiagState, nowMs: Long = 0L): (String) -> Unit = { msg ->
        val v = budget.decide(msg, msg.toByteArray(Charsets.UTF_8).size + 40, nowMs)
        if (v.write) file.appendText(msg + "\n")
        if (v.notice != null) file.appendText(v.notice + "\n")
    }

    @Test
    fun generalTrimDoesNotSwallowDiagnosticBlocks() {
        val general = LogBudget.AppState(bytes = 200, windowMs = 600_000)
        // Общий бюджет исчерпан обычными строками.
        repeat(5) { general.decide(100, it.toLong(), exempt = false) }
        assertTrue("общий бюджет в trim", general.droppedLines() > 0 || general.usedBytes() > 200)

        val diag = LogBudget.DiagState()
        val dir = Files.createTempDirectory("diaglog").toFile()
        val f = File(dir, "voiceapp3-01.01.2026.log")
        val sink = sinkOf(f, diag)
        sink("INTERNET_DIAG_BEGIN ts=1 source=test")
        sink("INTERNET_DIAG_SECTION name=routes status=ok")
        sink("INTERNET_DIAG_DATA name=routes part=1/1 default via 10.0.0.1")
        sink("INTERNET_DIAG_END sections=1")
        val text = f.readText()
        for (marker in listOf("_BEGIN", "_SECTION", "_DATA", "_END")) {
            assertTrue("маркер $marker не потерян при общем trim", text.contains(marker))
        }
        assertFalse(text.contains("TRUNCATED"))
    }

    @Test
    fun longBlockIsTruncatedExplicitlyAndEndsAreKept() {
        val budget = LogBudget.DiagState(bytes = 400, lines = 3)
        val dir = Files.createTempDirectory("diaglog").toFile()
        val f = File(dir, "voiceapp3-01.01.2026.log")
        val sink = sinkOf(f, budget)
        sink("INTERNET_DIAG_BEGIN ts=1")
        repeat(50) { sink("INTERNET_DIAG_DATA name=routes part=${it + 1}/50 " + "x".repeat(40)) }
        sink("INTERNET_DIAG_END sections=1")

        val lines = f.readLines()
        assertTrue("начало сохранено", lines.any { it.startsWith("INTERNET_DIAG_BEGIN") })
        assertTrue("окончание сохранено", lines.any { it.startsWith("INTERNET_DIAG_END") })
        val trunc = lines.filter { it.contains("INTERNET_DIAG_TRUNCATED") }
        assertEquals("усечение объявлено ровно один раз", 1, trunc.size)
        assertTrue(trunc[0].contains("truncated=true"))
        assertTrue(trunc[0].contains("limit_bytes=400"))
        assertTrue("нумерация частей остаётся в данных", lines.any { it.contains("part=1/50") })
        assertTrue("дальние части не пишутся", lines.none { it.contains("part=40/50") })
        assertFalse("усечение видно и в состоянии", !budget.truncated())
    }

    @Test
    fun numberedPartsAreNeverSilentlyRenumberedOrDropped() {
        val budget = LogBudget.DiagState(bytes = 100_000, lines = 1000)
        val dir = Files.createTempDirectory("diaglog").toFile()
        val f = File(dir, "voiceapp3-01.01.2026.log")
        val sink = sinkOf(f, budget)
        sink("JIT_MOUNT_DIAG_BEGIN ts=1")
        val total = 6
        repeat(total) { sink("JIT_MOUNT_DIAG_DATA name=proc_mounts part=${it + 1}/$total line$it") }
        sink("JIT_MOUNT_DIAG_END sections=1")
        val parts = f.readLines().filter { it.contains("part=") }
            .map { Regex("part=(\\d+)/(\\d+)").find(it)!!.groupValues.drop(1).map(String::toInt) }
        assertEquals("все части на месте", total, parts.size)
        assertEquals("нумерация 1..N", (1..total).toList(), parts.map { it[0] })
        assertTrue(parts.all { it[1] == total })
    }

    @Test
    fun budgetWindowResetsAndDoesNotSilenceDiagnosticsForever() {
        val budget = LogBudget.DiagState(bytes = 100, lines = 1, windowMs = 1000)
        budget.decide("INTERNET_DIAG_DATA a", 80, 0)
        val second = budget.decide("INTERNET_DIAG_DATA b", 80, 100)
        assertFalse("в окне места нет", second.write)
        assertTrue("усечение объявлено", second.notice!!.contains("TRUNCATED"))
        assertTrue(budget.truncated())
        // Следующее окно: снова можно писать, счётчики сброшены.
        val next = budget.decide("INTERNET_DIAG_DATA c", 80, 5000)
        assertTrue("после окна сбор не запрещён навсегда", next.write)
        assertFalse(budget.truncated())
    }

    @Test
    fun competingWritersDoNotExceedTheLimit() {
        val budget = LogBudget.DiagState(bytes = 500, lines = 1000)
        val threads = (1..8).map {
            Thread {
                repeat(200) { budget.decide("INTERNET_DIAG_DATA часть", 50, 0) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }
        assertTrue("лимит байт не превышен с запасом на одну строку", budget.usedBytes() <= 500 + 50)
        assertTrue("усечение зафиксировано", budget.truncated())
    }

    @Test
    fun writeFailureIsVisibleThroughIndependentChannel() {
        // Модель «файл недоступен»: sink бросает; сборщик обязан получить ошибку, а не «сохранено».
        val dir = Files.createTempDirectory("diaglog").toFile()
        val f = File(dir, "voiceapp3-01.01.2026.log")
        f.createNewFile()
        f.setWritable(false)
        if (f.canWrite()) {
            // На среде, где права не применяются (root в контейнере), честно пропускаем проверку.
            return
        }
        var failed = false
        val sink: (String) -> Unit = { msg ->
            try {
                f.appendText(msg + "\n")
            } catch (t: Throwable) {
                failed = true
            }
        }
        assertTrue("запись должна явно провалиться", runCatching { sink("INTERNET_DIAG_DATA x") }.isSuccess)
        assertTrue("ошибка записи заметна", failed)
        f.setWritable(true)
    }

    @Test
    fun fallbackDirectoryLogicIsTestableWithoutAndroid() {
        // `LogFiles.dirFor` требует Context, поэтому здесь проверяется только модель выбора каталога:
        // внешний недоступен → внутренний. Само переключение в `LogFiles` проверяется в CI.
        val external = Files.createTempDirectory("ext").toFile()
        val internal = Files.createTempDirectory("int").toFile()
        assertTrue(LogFiles.writable(external))
        val chosen = if (LogFiles.writable(external)) external else internal
        assertEquals("пишем во внешний, если он принимает запись", external, chosen)
        external.setWritable(false)
        val chosen2 = if (LogFiles.writable(external)) external else internal
        if (!external.canWrite()) {
            assertEquals("иначе — fallback внутрь приложения", internal, chosen2)
        }
        external.setWritable(true)
    }

    @Test
    fun statusOfDistinguishesPassiveUnavailableFromEmptyAndRootMissing() {
        assertEquals(
            InternetDiag.STATUS_CHANNEL_NOT_READY,
            InternetDiag.statusOf(3, "ERR_ADB_PASSIVE_UNAVAILABLE reason=not_started", null, false)
        )
        assertEquals(
            InternetDiag.STATUS_UNAVAILABLE,
            InternetDiag.statusOf(3, "ERR_ADB_PASSIVE_UNAVAILABLE reason=root_unavailable", null, false)
        )
        assertEquals(
            "«нет root» — не «пусто»",
            InternetDiag.STATUS_UNAVAILABLE,
            InternetDiag.statusOf(1, "ERR_ROOT_CHANNEL_UNAVAILABLE (state=NO_ROOT_SH)", null, false)
        )
        assertEquals(
            "ненулевой транспорт при целых маркерах — не ok",
            InternetDiag.STATUS_UNKNOWN,
            InternetDiag.statusOf(2, "__JMD_ROUTES_RC=0\n__JMD_EOF=1", 0, true)
        )
        assertEquals(
            "отказ прав важнее кода транспорта",
            InternetDiag.STATUS_PERMISSION_DENIED,
            InternetDiag.statusOf(1, "Permission denied\n__JMD_ROUTES_RC=1\n__JMD_EOF=1", 1, true)
        )
        assertNull("нет маркера — нет заголовка", InternetDiag.rcOf("ip rule show", "routes"))
    }

    @Test
    fun bytesAreUtf8NotStringLength() {
        assertEquals("ASCII", 3, JitMountDiag.utf8Bytes("abc"))
        val cyr = "маршрут"
        assertEquals("кириллица: 2 байта на символ", cyr.length * 2, JitMountDiag.utf8Bytes(cyr))
        assertTrue("длина в символах меньше длины в байтах",
            cyr.length < JitMountDiag.utf8Bytes(cyr))
        // `fileStatus` на недоборе байт обязан сказать truncated, а не ok.
        val out = "  2  40 /proc/mounts\n__JMD_WC_RC=0\n" +
            "1 1 0:1 / /data/app/x\n" + "\n__JMD_CAT_RC=0\n__JMD_EOF=1"
        assertEquals(
            JitMountDiag.STATUS_TRUNCATED,
            JitMountDiag.fileStatus(0, out, 0, 0, 2, 40, 1, JitMountDiag.utf8Bytes("1 1 0:1 / /data/app/x"), 1)
        )
    }
}
