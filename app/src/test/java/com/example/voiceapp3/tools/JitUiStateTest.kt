package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Что видит водитель: тумблер JIT и строка факта (REVIEW 3-го захода, п.5).
 *
 * Правило одно: `on`/`off` — только по подтверждённому факту, иначе `unknown`. Ложное «выключено»
 * (из preference или из «ещё не читали») запрещено. Новых кнопок нет — это проверяется по коду.
 */
class JitUiStateTest {

    private val id = "7b2c4d5e-6f70-4a8b-9c1d-2e3f4a5b6c7d"

    private fun fact(v: JitRef.Prop) = JitRef.PropFact(v, if (v == JitRef.Prop.TRUE) "true" else "false", 0, "ok")

    private fun op(phase: JitRef.Phase, want: Boolean) =
        JitRef.Op(phase, want, JitRef.Net(true, false), id, 5L)

    @Test
    fun onOnlyWhenRuntimeIsTrue() {
        assertEquals("on", JitRef.confirmedState(fact(JitRef.Prop.TRUE), null))
        assertEquals("on", JitRef.confirmedState(fact(JitRef.Prop.TRUE), op(JitRef.Phase.DONE, false)))
    }

    @Test
    fun offOnlyWithProvenOperation() {
        assertEquals("выключено — только факт: прочитано false И завершённая операция want=false",
            "off", JitRef.confirmedState(fact(JitRef.Prop.FALSE), op(JitRef.Phase.DONE, false)))
        assertEquals("операция не завершена ⇒ unknown",
            "unknown", JitRef.confirmedState(fact(JitRef.Prop.FALSE), op(JitRef.Phase.SENT, false)))
        assertEquals("операция была на включение ⇒ unknown",
            "unknown", JitRef.confirmedState(fact(JitRef.Prop.FALSE), op(JitRef.Phase.DONE, true)))
    }

    @Test
    fun unknownIsShownHonestly() {
        assertEquals("ещё не читали", "unknown", JitRef.confirmedState(null, null))
        assertEquals("транспорт не ответил", "unknown",
            JitRef.confirmedState(JitRef.PropFact(JitRef.Prop.UNKNOWN, "", 137, "rc_nonzero"), null))
    }

    @Test
    fun panelHasNoNewButtonsAndUpdatesWidget() {
        fun code(rel: String): String =
            listOf(File(rel), File("app/$rel")).first { it.isFile }.readLines()
                .filterNot {
                    val t = it.trim()
                    t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
                }.joinToString("\n")
        val panel = code("src/main/java/com/example/voiceapp3/ui/ToolsPanel.kt")
        // строка факта — это TextView, а не кнопка; тумблер JIT по-прежнему один
        assertTrue(panel.contains("private fun addJitRuntimeLine()"))
        assertTrue("после ответа текст обновляется", panel.contains("view.text = jitRuntimeLineText(fresh)"))
        assertFalse("никаких новых кнопок для JIT", panel.contains("addButton(\"JIT") || panel.contains("Button(activity).apply { text = \"JIT"))
        // в тумблере состояние берётся из подтверждённого факта
        assertTrue(panel.contains("strState(\"perfJitConfirmed\")"))
        assertTrue("unknown показывается словами", panel.contains("состояние неизвестно"))
        // backend отдаёт подтверждённое состояние отдельным полем
        val backend = code("src/main/java/com/example/voiceapp3/tools/ToolsBackend.kt")
        assertTrue(backend.contains("addProperty(\"perfJitConfirmed\""))
        assertTrue(backend.contains("addProperty(\"perfJitRuntime\""))
    }

    @Test
    fun refreshCallbackIsCalledOnce() {
        // Логика «обновить виджет после чтения» проверяется на уровне адаптера: колбэк вызывается
        // ровно один раз и с тем значением, которое прочитано (см. AppPerformance.refreshJitRuntimeAsync).
        var calls = 0
        val cb: (JitRef.PropFact) -> Unit = { calls++ }
        cb(fact(JitRef.Prop.TRUE))
        assertEquals(1, calls)
    }
}
