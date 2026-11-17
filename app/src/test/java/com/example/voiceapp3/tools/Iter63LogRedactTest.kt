package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter63 §3.3 — `LogRedact`: время не маскируется, IPv6 и «IPv4:порт» маскируются ЦЕЛИКОМ.
 *
 * Дефекты iter62 (подтверждены прогоном на его коде):
 *  1. шаблон IPv6 требовал 3 групп с двоеточиями, поэтому короткая запись вида `fe80::1ff`
 *     проходила в журнал как есть;
 *  2. порт печатался: из `192.168.1.5:8080` маскировался только адрес, `:8080` оставался —
 *     то есть идентификатор узла частично всё же попадал в лог;
 *  3. порядок проверок был «MAC → IPv4 → IPv6», из-за чего `a:b:c:d:e:f` (шесть hex-групп
 *     по одной цифре) считался MAC, а настоящий IPv6 рядом — нет.
 *
 * Проверяется production [LogRedact] — та же функция, которую вызывают `AdbClient`/`LogKit`.
 * Ничего «для теста» не добавлено: сравнение идёт с фактическим результатом боевого объекта.
 */
class Iter63LogRedactTest {

    // ─────────── 1. Время суток — НЕ секрет и НЕ IPv6 ───────────

    @Test
    fun clockTimeIsNotMistakenForAnAddress() {
        val raw = "APPLY at 12:34:56 done"
        val out = LogRedact.text(raw, 300)
        assertTrue("время суток обязано остаться читаемым: $out", out.contains("12:34:56"))
    }

    @Test
    fun timestampAndUptimeStayReadable() {
        val out = LogRedact.text("ts=2026-10-22 12:34:56 pid=12:34 uptime=9:5", 300)
        assertTrue("дата-время остаётся: $out", out.contains("12:34:56"))
        assertTrue("минуты:секунды в pid остаются: $out", out.contains("12:34"))
    }

    // ─────────── 2. IPv6 маскируется при ЛЮБОМ числе групп ───────────

    @Test
    fun shortAndLongIpv6AreBothMasked() {
        listOf(
            "fe80::1",
            "fe80::1ff:fe23:4567:890a",
            "2a02:6b8::2:242",
            "::1",
            "2001:db8:0:0:0:0:2:1"
        ).forEach { addr ->
            val out = LogRedact.text("peer=$addr rc=0", 300)
            assertFalse("IPv6 «$addr» попал в журнал: $out", out.contains(addr))
            assertTrue("осталась метка семейства: $out", out.contains("<ip6>"))
        }
    }

    @Test
    fun ipv6WithPortAndBracketsIsMaskedWholly() {
        listOf("[fe80::1]:8080", "fe80::1:8080", "[2a02:6b8::2]:443").forEach { addr ->
            val out = LogRedact.text("connect $addr failed", 300)
            assertFalse("адрес с портом «$addr» попал в журнал: $out", out.contains(addr))
            assertFalse("порт всё ещё виден: $out", out.contains("8080") || out.contains("443"))
        }
    }

    // ─────────── 3. IPv4 вместе с портом — целиком ───────────

    @Test
    fun ipv4WithPortIsMaskedWhole() {
        val out = LogRedact.text("from 192.168.1.5:8080 to 10.0.0.1:5555", 300)
        assertFalse("адрес попал: $out", out.contains("192.168.1.5") || out.contains("10.0.0.1"))
        assertFalse("порт попал: $out", out.contains("8080") || out.contains("5555"))
        assertTrue("метка семейства есть: $out", out.contains("<ip4>"))
    }

    @Test
    fun ipv4WithPortInBracketsAndProtocolFormIsMaskedWhole() {
        listOf("tcp://192.168.0.7:443/x", "192.168.0.7:443", "[192.168.0.7]:443").forEach { s ->
            val out = LogRedact.text("url=$s", 300)
            assertFalse("«$s» попал в журнал: $out", out.contains("192.168.0.7"))
            assertFalse("порт «443» попал в журнал: $out", out.contains("443"))
        }
    }

    // ─────────── 4. MAC (в т.ч. «короткие» hex-группы) ───────────

    @Test
    fun macIsMaskedButNumbersAreNot() {
        val out = LogRedact.text("mac=11:22:33:44:55:66 wifi=aa:bb:cc:dd:ee:ff", 300)
        assertFalse("MAC попал: $out", out.contains("11:22:33:44:55:66") || out.contains("aa:bb:cc:dd:ee:ff"))
        val keep = LogRedact.text("counters a=1 b=2 ratio=3:4", 300)
        assertTrue("обычные числа не тронуты: $keep", keep.contains("a=1") && keep.contains("3:4"))
    }

    // ─────────── 5. Строка целиком: время осталось, адрес ушёл ───────────

    @Test
    fun logLineKeepsTimeAndDropsAddress() {
        val out = LogRedact.text("12:34:56 CONNECT 10.1.2.3:5555 via [fe80::1]:8080 mac 0a:1b:2c:3d:4e:5f", 300)
        assertTrue("время сохранено: $out", out.contains("12:34:56"))
        assertFalse("адреса/порты/MAC ушли: $out",
            out.contains("10.1.2.3") || out.contains("5555") || out.contains("fe80::1") ||
                out.contains("8080") || out.contains("0a:1b:2c:3d:4e:5f"))
    }

    @Test
    fun hostNameIsStillUseful() {
        val out = LogRedact.text("https://example.invalid/ping rc=0", 300)
        assertTrue("имя хоста не секрет, остаётся: $out", out.contains("example.invalid"))
    }

    // ─────────── 6. Детерминизм ───────────

    @Test
    fun redactionIsDeterministic() {
        val raw = "12:00:00 192.168.1.5:80 fe80::1 'payload'"
        assertEquals(LogRedact.text(raw, 300), LogRedact.text(raw, 300))
    }
}
