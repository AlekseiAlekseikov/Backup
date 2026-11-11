package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Что видит водитель.
 *
 * Референс (`w0/bb` → `w0/r7`) ставит тумблер по `getprop`: `trim()=="true"` → включён, любая
 * другая строка → выключен. Мы делаем то же для подтверждённых значений, но **нечитаемое
 * значение показываем как «состояние неизвестно»**, а не как «выключено» (требование владельца,
 * отличие описано в отчёте §3). Новых кнопок и тумблеров нет — проверяется по исходнику.
 */
class JitUiStateTest {

    private fun fact(v: JitRef.Prop, raw: String = "") =
        JitRef.PropFact(v, raw.ifEmpty { v.name.lowercase() }, 0, "ok")

    @Test
    fun onOnlyWhenRuntimeIsTrue() {
        assertEquals("on", JitRef.confirmedState(fact(JitRef.Prop.TRUE, "true")))
    }

    @Test
    fun offOnlyWhenRuntimeIsFalse() {
        assertEquals("off", JitRef.confirmedState(fact(JitRef.Prop.FALSE, "false")))
    }

    @Test
    fun unknownIsShownHonestly() {
        assertEquals("ещё не читали", "unknown", JitRef.confirmedState(null))
        assertEquals("транспорт не ответил", "unknown",
            JitRef.confirmedState(JitRef.PropFact(JitRef.Prop.UNKNOWN, "", 137, "rc_nonzero")))
        assertEquals("пустой вывод", "unknown",
            JitRef.confirmedState(JitRef.PropFact(JitRef.Prop.UNKNOWN, "", 0, "empty")))
    }

    @Test
    fun preferenceDoesNotDriveTheToggle() {
        // Значение тумблера выводится ТОЛЬКО из факта: функция не принимает ни префы, ни операцию.
        assertEquals("unknown", JitRef.confirmedState(null))
        val store = JitFake.Store()
        store.values[JitRef.KEY_AUTO] = true
        assertEquals("preference сама по себе ничего не доказывает", "unknown",
            JitRef.confirmedState(null))
    }

    @Test
    fun panelHasNoNewButtonsAndUpdatesWidget() {
        fun code(rel: String): String =
            listOf(File(rel), File("app/$rel")).first { it.isFile }.readText()

        val panel = code("src/main/java/com/example/voiceapp3/ui/ToolsPanel.kt")
        assertTrue(panel.contains("private fun addJitRuntimeLine()"))
        assertTrue("после ответа текст обновляется", panel.contains("view.text = jitRuntimeLineText(fresh)"))
        assertTrue("unknown показывается словами", panel.contains("состояние неизвестно"))
        assertTrue("состояние тумблера — из подтверждённого факта",
            panel.contains("strState(\"perfJitConfirmed\")"))
        assertFalse("никаких новых кнопок для JIT",
            panel.contains("addButton(\"JIT") || panel.contains("Button(activity).apply { text = \"JIT"))

        val backend = code("src/main/java/com/example/voiceapp3/tools/ToolsBackend.kt")
        assertTrue(backend.contains("addProperty(\"perfJitConfirmed\""))
        assertTrue(backend.contains("addProperty(\"perfJitRuntime\""))
    }
}
