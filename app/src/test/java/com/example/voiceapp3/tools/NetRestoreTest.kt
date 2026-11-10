package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33p §2.3/§6 — возврат сети после подъёма фреймворка.
 *
 * Правила: возвращаем ТОЛЬКО то, что было включено до рестарта (`wifi=1`/`ap=1` из сохранённого
 * состояния); Wi-Fi — до 3 попыток с паузой ~3 с; санация `ip rule` ограничена правилами
 * `oif wlan0` (чужие правила policy-routing не трогаем).
 */
class NetRestoreTest {

    private fun src(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readText()

    private val net = "src/main/java/com/example/voiceapp3/tools/JitNet.kt"

    private fun bodyOf(source: String, fn: String): String {
        val i = source.indexOf(fn)
        if (i < 0) return ""
        val j = source.indexOf('{', i)
        if (j < 0) return ""
        var depth = 0
        for (k in j until source.length) {
            when (source[k]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(i, k + 1)
                }
            }
        }
        return source.substring(i)
    }

    // 1. Разбор/формат сохранённого состояния; битая строка — не состояние, а null.
    @Test
    fun savedStateRoundTrip() {
        assertEquals(JitNet.State(true, true), JitNet.parse(JitNet.State(true, true).wire()))
        assertEquals(JitNet.State(false, false), JitNet.parse(JitNet.State(false, false).wire()))
        assertEquals("битая строка — не состояние", null, JitNet.parse("ap=1"))
        assertEquals(null, JitNet.parse(null))
        assertEquals(null, JitNet.parse(""))
    }

    // 2. Возврат идёт строго по сохранённому состоянию (§2.3).
    @Test
    fun restoreUsesSavedStateOnly() {
        val s = src(net)
        val restore = bodyOf(s, "fun restoreAfterRestart")
        assertTrue("нет возврата по сохранённому состоянию", restore.isNotEmpty())
        assertTrue("Wi-Fi возвращаем только если был включён", restore.contains("if (saved.wifi)"))
        assertTrue("AP возвращаем только если был поднят", restore.contains("if (saved.ap)"))
        assertTrue("источник — параметр saved", restore.contains("saved: State"))
        assertTrue("без сохранённого состояния — skip", restore.contains("reason=was_off"))
    }

    // 3. Wi-Fi: не больше 3 попыток с паузой ~3 с (§2.3б).
    @Test
    fun wifiRetriesAreBounded() {
        assertEquals("до 3 попыток", 3, JitNet.WIFI_RESTORE_ATTEMPTS)
        assertEquals("пауза ~3 с", 3_000L, JitNet.WIFI_RESTORE_PAUSE_MS)
        val s = src(net)
        assertTrue("цикл попыток по константе",
            s.contains("for (attempt in 1..WIFI_RESTORE_ATTEMPTS)"))
        assertTrue("пауза между попытками", s.contains("Thread.sleep(WIFI_RESTORE_PAUSE_MS)"))
        assertTrue("результат пишется в лог", s.contains("JIT_NET_RESTORE wifi=1 attempt="))
    }

    // 4. Санация: только правила `oif wlan0`, чужие правила не трогаем.
    @Test
    fun sanitizeTouchesOnlyOifWlan0() {
        val script = JitNet.sanitizeScript()
        assertTrue("удаление идёт по выборке oif wlan0", script.contains("grep 'oif wlan0'"))
        assertTrue("удаляем по приоритету из этой выборки", script.contains("ip rule del priority"))
        assertTrue("итог санации в лог", script.contains("JIT_NET_SANITIZE removed="))
        assertTrue("скоуп явно в логе", script.contains("scope=oif_wlan0"))
        for (bad in listOf("iptables", "ip6tables", "flush", "oif wlan1", "oif usb0")) {
            assertTrue("санация не должна трогать: $bad", !script.contains(bad))
        }
        // Правила/скрипт TTL не меняются — проверка, что санация не про TTL.
        val ttl = src("src/main/java/com/example/voiceapp3/tools/TtlScript.kt")
        assertTrue("TTL-скрипт остаётся отдельным", ttl.isNotEmpty() && !ttl.contains("JIT_NET_SANITIZE"))
    }

    // 5. AP: механизм референса, при недоступности — честный лог, без «лечения» системы.
    @Test
    fun apRestoreIsHonestWhenUnavailable() {
        val s = src(net)
        assertTrue("AP поднимается через startSoftAp", s.contains("\"startSoftAp\""))
        assertTrue("недоступность логируется", s.contains("no_startSoftAp"))
        assertTrue("факт «уже поднят» тоже логируется", s.contains("result=already_up"))
        for (bad in listOf("svc wifi enable", "setenforce", "pm install")) {
            assertTrue("запрещено в JitNet: $bad", !s.contains(bad))
        }
    }
}
