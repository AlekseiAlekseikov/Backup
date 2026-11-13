package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Настоящий тумблер JIT и его связывание с фактом `getprop`.
 *
 * Референс (`w0/r7`): `toggle.setActive(getprop)` — положение берётся из ФАКТА, а не из preference;
 * текст — `master_perf_jit_on/off`. ОТЛИЧИЕ (по требованию владельца): при нечитаемом `getprop`
 * показывается «состояние неизвестно», а не ложное «выключено».
 *
 * Логика положения вынесена в [JitRef.switchPosition]/[JitRef.switchUpdate], поэтому проверяется
 * целиком на JVM; в самой панели дополнительно проверяется, что программное обновление НЕ вызывает
 * обработчик пользовательского клика.
 */
class JitUiStateTest {

    private fun code(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readText()

    @Test
    fun confirmedOnWinsOverPreferenceOff() {
        // `getprop=true`, preference=false ⇒ тумблер включён (раньше был выключен — это и был баг).
        assertTrue(JitRef.switchPosition("on", preference = false))
        assertEquals("программное обновление переставляет тумблер",
            true, JitRef.switchUpdate(false, "on"))
    }

    @Test
    fun confirmedOffWinsOverPreferenceOn() {
        assertFalse(JitRef.switchPosition("off", preference = true))
        assertEquals(false, JitRef.switchUpdate(true, "off"))
    }

    @Test
    fun unknownKeepsPreferenceAndSaysUnknown() {
        assertTrue(JitRef.switchPosition("unknown", preference = true))
        assertFalse(JitRef.switchPosition("unknown", preference = false))
        assertNull("неизвестное не переставляется программно", JitRef.switchUpdate(true, "unknown"))
        assertNull(JitRef.switchUpdate(false, "unknown"))
        assertEquals("unknown", JitRef.confirmedState(null))
        assertEquals("unknown",
            JitRef.confirmedState(JitRef.PropFact(JitRef.Prop.UNKNOWN, "", 137, "rc_nonzero")))
    }

    @Test
    fun noRedrawWhenFactMatchesCurrentPosition() {
        assertNull(JitRef.switchUpdate(true, "on"))
        assertNull(JitRef.switchUpdate(false, "off"))
    }

    @Test
    fun panelProgrammaticUpdateDoesNotFireClickListener() {
        val panel = code("src/main/java/com/example/voiceapp3/ui/ToolsPanel.kt")
        val fn = panel.substring(panel.indexOf("private fun applyJitFact"), panel.indexOf("private fun jitRuntimeLineText"))
        assertTrue("слушатель снимается перед программной установкой",
            fn.contains("setOnCheckedChangeListener(null)"))
        assertTrue("и возвращается после", fn.contains("setOnCheckedChangeListener { _, checked -> listener(checked) }"))
        assertFalse("программное обновление не пишет настройку", fn.contains("addProperty(key"))
        assertFalse(fn.contains("send(\"settings.change\""))
        // Положение при построении — из подтверждённого факта.
        // §4 iter45: значение берётся один раз в `raw` — и `null` («не прочитано») больше
        // НЕ выдаётся за OFF (в заголовке появляется «состояние ещё не прочитано»).
        assertTrue(panel.contains("JitRef.switchPosition(jitConfirmed ?: \"unknown\", raw ?: def)"))
        assertTrue(panel.contains("val raw = boolState(key)"))
        assertTrue(panel.contains("состояние ещё не прочитано"))
        assertTrue("подпись про неизвестное состояние есть",
            panel.contains("состояние неизвестно"))
    }

    @Test
    fun panelRunsVerificationOnOpenAndUpdatesBothWidgets() {
        val panel = code("src/main/java/com/example/voiceapp3/ui/ToolsPanel.kt")
        assertTrue("сверка при открытии панели", panel.contains("AppPerformance.verifyJitOnly(activity)"))
        assertTrue("после ответа обновляется строка",
            panel.contains("view.text = jitRuntimeLineText(fresh, gen0)"))
        assertTrue("устаревший ответ (поколение сменилось) не применяется",
            panel.contains("JIT_UI stale_fact skipped generation_changed"))
        assertTrue("и тумблер", panel.contains("applyJitFact(fresh)"))
        assertFalse("новых кнопок JIT нет",
            panel.contains("addButton(\"JIT") || panel.contains("text = \"JIT"))
    }

    @Test
    fun verificationIsReachableAndNotPerTick() {
        val app = code("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        assertTrue("сверка проверяет наличие намерения до shell",
            app.contains("JitNet.hasPendingPedido(app)"))
        assertTrue("и честно пишет, что снимает ключ",
            app.contains("write=remove_if_confirmed"))
        val svc = code("src/main/java/com/example/voiceapp3/VoiceAssistantService.kt")
        assertTrue("watchdog зовёт сверку только внутри ветки тяжёлых тиков",
            svc.contains("AppPerformance.verifyJitOnly(app)"))
    }
}
