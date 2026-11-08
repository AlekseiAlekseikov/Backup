package com.example.voiceapp3.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33n-fix1 §3.1 — окно debounce тумблера TTL.
 *
 * Лог 08.11 (`voiceapp3 (26).log`, 20:12): первый клик честно упал (`нет uplink`, правило не
 * поставлено), второй клик через 13 с получил `✗ skipped: debounce 13424ms` — окно 30 с съело
 * нормальный повтор. Требование: повтор ON — не дольше 3 с, OFF — не блокируется вовсе.
 */
class TtlDebounceTest {

    private fun netTtl(): String = listOf(
        File("src/main/java/com/example/voiceapp3/tools/NetworkTtl.kt"),
        File("app/src/main/java/com/example/voiceapp3/tools/NetworkTtl.kt")
    ).first { it.isFile }.readText()

    // 1. Окно ON ровно 3 с.
    @Test
    fun onWindowIsThreeSeconds() {
        val s = netTtl()
        assertTrue("нет константы TTL_ON_DEBOUNCE_MS = 3_000L",
            s.contains("TTL_ON_DEBOUNCE_MS = 3_000L"))
    }

    // 2. OFF не блокируется: окно обнуляется при enabled=false.
    @Test
    fun offIsNeverDebounced() {
        val s = netTtl()
        assertTrue("окно должно зависеть от enabled",
            s.contains("val debounceMs = if (enabled) TTL_ON_DEBOUNCE_MS else 0L"))
        assertTrue("условие пропуска обязано проверять debounceMs > 0",
            s.contains("if (debounceMs > 0L && sinceLast in 0 until debounceMs)"))
    }

    // 3. Прежнее окно 30 с в тумблере не осталось (в приёмнике сети оно своё и не проверяется).
    @Test
    fun toggleNoLongerUsesThirtySeconds() {
        val s = netTtl()
        val toggle = s.substringAfter("fun setTtl65Active").substringBefore("synchronized(this)")
        assertTrue("в тумблере осталось окно 30 000 мс", !toggle.contains("30_000L"))
    }
}
