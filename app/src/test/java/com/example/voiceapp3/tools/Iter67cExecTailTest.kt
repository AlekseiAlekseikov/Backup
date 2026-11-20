package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** iter67c §6 — при rc≠0 в «← execute» виден хвост вывода; при rc=0 и для passive — нет. */
class Iter67cExecTailTest {

    @Test
    fun `при rc не 0 есть out, при rc 0 нет`() {
        val err = "ERR_ADB_TOO_LONG: cmd_len=44454 > 4096 (use executeScript for long scripts)"
        val t = LogRedact.execTail(1, err, passive = false)
        assertTrue("нет out= при rc=1: «$t»", t.startsWith(" out="))
        assertTrue("причина не видна: «$t»", t.contains("ERR_ADB_TOO_LONG") && t.contains("44454"))
        assertEquals("при rc=0 строка не раздувается", "", LogRedact.execTail(0, err, passive = false))
    }

    @Test
    fun `для passive не печатается никогда`() {
        assertEquals("", LogRedact.execTail(1, "inet 192.168.1.20/24 link/ether aa:bb:cc:dd:ee:ff", passive = true))
    }

    @Test
    fun `хвост маскирован и не длиннее 300 байт`() {
        val noisy = "x".repeat(2_000) + "\nоткуда 10.0.0.7 mac aa:bb:cc:dd:ee:ff ssid=HomeNet ошибка: нет доступа"
        val t = LogRedact.execTail(2, noisy, passive = false)
        val body = t.removePrefix(" out=")
        assertTrue("хвост длиннее 300 байт: ${body.toByteArray(Charsets.UTF_8).size}",
            body.removePrefix("…").toByteArray(Charsets.UTF_8).size <= 300)
        assertTrue("это должен быть ХВОСТ (конец вывода): «$t»", t.endsWith("нет доступа"))
        assertFalse("IP напечатан: $t", t.contains("10.0.0.7"))
        assertFalse("MAC напечатан: $t", t.contains("aa:bb:cc:dd:ee:ff"))
        assertFalse("SSID напечатан: $t", t.contains("HomeNet"))
        assertFalse("перевод строки в строке журнала: $t", t.contains("\n"))
    }

    @Test
    fun `AdbClient проводит хвост только в строку execute`() {
        val src = File("app/src/main/java/com/example/voiceapp3/tools/AdbClient.kt").readText()
        val exec = src.substringAfter("\"← execute dt=").substringBefore("LogKit.noteJobRc")
        assertTrue("строка «← execute» обязана добавлять execTail: $exec",
            exec.contains("LogRedact.execTail(out.first, out.second, passive = false)"))
        val passive = src.substringAfter("\"← passive dt=").substringBefore("LogKit.noteJobRc")
        assertFalse("строка «← passive» не должна печатать вывод: $passive",
            passive.contains("execTail") || passive.contains("out.second)"))
    }
}
