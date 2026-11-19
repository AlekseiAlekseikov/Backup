package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter67 §G и §D — «включено, не применено» / «ждёт перезапуска фреймворка» — различимые состояния.
 *
 * Живые факты: `dalvik.vm.usejit=false` при включённом тумблере и замер
 * `JIT pedido=true getprop=true code-cache-executavel=false result=cache_missing` — владелец видел
 * «включён» и «неудача» одновременно, хотя не хватало только старта zygote.
 */
class Iter67UiStateTest {

    @Test
    fun `intent=true fact=false → ON_NOT_APPLIED`() {
        // fail-before: состояние бралось из намерения, поэтому тумблер показывал «включено».
        assertEquals(JitUiState.ON_NOT_APPLIED,
            JitUi.jitUiState(intent = true, propValue = JitRef.Prop.FALSE, cache = null))
    }

    @Test
    fun `fact=true cache=false → PENDING_RESTART, не ON`() {
        assertEquals(JitUiState.PENDING_RESTART,
            JitUi.jitUiState(intent = true, propValue = JitRef.Prop.TRUE, cache = false))
    }

    @Test
    fun `fact=true cache=true → ON`() {
        assertEquals(JitUiState.ON,
            JitUi.jitUiState(intent = true, propValue = JitRef.Prop.TRUE, cache = true))
    }

    @Test
    fun `fact unknown → UNKNOWN, не OFF`() {
        // fail-before: недоступное property давало ложное «выключено».
        assertEquals(JitUiState.UNKNOWN,
            JitUi.jitUiState(intent = true, propValue = JitRef.Prop.UNKNOWN, cache = null))
        assertEquals(JitUiState.UNKNOWN,
            JitUi.jitUiState(intent = false, propValue = null, cache = null))
    }

    @Test
    fun `намерение выключено и свойство false → OFF`() {
        assertEquals(JitUiState.OFF,
            JitUi.jitUiState(intent = false, propValue = JitRef.Prop.FALSE, cache = null))
    }

    @Test
    fun `тексты состояний различимы и без красного`() {
        assertEquals("включено", JitUi.text(JitUiState.ON))
        assertEquals("выключено", JitUi.text(JitUiState.OFF))
        assertEquals("включено, не применено", JitUi.text(JitUiState.ON_NOT_APPLIED))
        assertEquals("включено, ждёт перезапуска фреймворка", JitUi.text(JitUiState.PENDING_RESTART))
        assertEquals("неизвестно", JitUi.text(JitUiState.UNKNOWN))
        assertEquals("on_not_applied", JitUi.wire(JitUiState.ON_NOT_APPLIED))
        assertEquals("pending_restart", JitUi.wire(JitUiState.PENDING_RESTART))
    }

    @Test
    fun `prop true cache false → pending_restart и намерение сохраняется`() {
        val outcome = JitUi.verifyOutcome(true, JitRef.Prop.TRUE, false)
        assertEquals(VerifyOutcome.PENDING_RESTART, outcome)
        // fail-before: `CACHE_MISSING` считался провалом и снимал/блокировал намерение.
        assertTrue("намерение должно сохраняться", JitUi.keepPedido(outcome))
        val line = JitUi.stateLine(true, "true", false, outcome)
        assertTrue("нет result=: $line", line.contains("result=pending_restart"))
        assertTrue("нет why=: $line", line.contains("why=zygote_not_restarted"))
        assertTrue("нет prop=: $line", line.contains("prop=true"))
        assertTrue("нет cache=: $line", line.contains("cache=false"))
    }

    @Test
    fun `prop false → failed, намерение не сохраняется`() {
        val outcome = JitUi.verifyOutcome(true, JitRef.Prop.FALSE, null)
        assertEquals(VerifyOutcome.FAILED, outcome)
        assertFalse(JitUi.keepPedido(outcome))
    }

    @Test
    fun `нечитаемое свойство → unknown и намерение сохраняется`() {
        val outcome = JitUi.verifyOutcome(true, JitRef.Prop.UNKNOWN, null)
        assertEquals(VerifyOutcome.UNKNOWN, outcome)
        assertTrue(JitUi.keepPedido(outcome))
    }

    @Test
    fun `perfJitState и perfJitWhy отдаются панели`() {
        val src = java.io.File("app/src/main/java/com/example/voiceapp3/tools/ToolsBackend.kt").readText()
        // fail-before: свойства `perfJitState`/`perfJitWhy` отсутствовали вовсе.
        assertTrue("нет perfJitState", src.contains("addProperty(\"perfJitState\""))
        assertTrue("нет perfJitRuntime", src.contains("addProperty(\"perfJitRuntime\""))
        assertTrue("нет perfJitRuntimeRaw", src.contains("addProperty(\"perfJitRuntimeRaw\""))
        assertTrue("нет perfJitWhy", src.contains("addProperty(\"perfJitWhy\""))
        assertTrue("переключатель не из намерения", src.contains("addProperty(\"perfJit\", AppPerformance.isAutoOptimizeEnabled"))
    }

    @Test
    fun `сверка в ядре пишет JIT_STATE и сохраняет намерение при pending_restart`() {
        val src = java.io.File("app/src/main/java/com/example/voiceapp3/tools/JitNetCore.kt").readText()
        assertTrue("нет строки JIT_STATE", src.contains("JitUi.stateLine("))
        assertTrue("нет сохранения намерения по исходу", src.contains("JitUi.keepPedido(outcome)"))
    }
}
