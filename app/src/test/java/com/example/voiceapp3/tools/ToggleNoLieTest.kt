package com.example.voiceapp3.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33l §2.3 — регресс-тест на ЛОЖНЫЙ УСПЕХ.
 *
 * Лог 07.11 15:02–15:05: владелец видел `TOGGLE … ✓ SUCCESS`, при этом рядом лежало
 * `HVACDIGITAL_PUSH rc=1 ERR_ROOT_CHANNEL_UNAVAILABLE` — пропы не записались, а тумблер
 * показывал успех и потом «скакал» обратно по `FLAGS_SYNC`.
 *
 * Тест держит три вещи:
 *  1) успех тумблера = `rc=0` И `FLAG_VERIFY match=ok` (в коде нет ветки «✓ при rc!=0»);
 *  2) периодического PUSH из sync больше нет — только `alignHvacFlagsOnce` (один раз за процесс);
 *  3) клики сериализованы (mutex), иначе recarga летит пачками.
 */
class ToggleNoLieTest {

    private fun src(rel: String): String {
        val f = listOf(File(rel), File("app/$rel")).firstOrNull { it.isFile }
            ?: error("не найден $rel (cwd=${File(".").absolutePath})")
        return f.readText()
    }

    @Test
    fun toggleSuccessNeedsApplyAndVerify() {
        val apply = src("src/main/java/com/example/voiceapp3/tools/HvacFlagsApply.kt")
        assertTrue("успех должен требовать и apply, и verify",
            apply.contains("val ok = rc == 0 && match"))
        assertTrue("строка успеха строится через toggleLine с verify",
            apply.contains("toggleLine(label, value, wrote, via, \"bar_recarga\", applyRes, verify)"))
        assertFalse("нельзя возвращать ✓ по одному result",
            apply.contains("\"${'$'}{if (result == \"ok\" || result == \"noop\") \"✓\" else \"✗\"}"))
    }

    @Test
    fun syncDoesNotPushPeriodically() {
        val icons = src("src/main/java/com/example/voiceapp3/tools/StatusIcons.kt")
        assertFalse("периодический PUSH из sync запрещён (петля тумблеров)",
            icons.contains("pushHvacDigitalPropsIfEnabled"))
        assertTrue("вместо него — одноразовое выравнивание", icons.contains("alignHvacFlagsOnce(app)"))
        assertTrue("выравнивание одно за процесс", icons.contains("alignDone.compareAndSet(false, true)"))
    }

    @Test
    fun toggleIsSerialized() {
        val apply = src("src/main/java/com/example/voiceapp3/tools/HvacFlagsApply.kt")
        assertTrue("mutex клика", apply.contains("busy.compareAndSet(false, true)"))
        assertTrue("строка пропуска по занятости", apply.contains("TOGGLE_SKIP reason=busy"))
        assertTrue("mutex снимается в finally", apply.contains("busy.set(false)"))
    }
}
