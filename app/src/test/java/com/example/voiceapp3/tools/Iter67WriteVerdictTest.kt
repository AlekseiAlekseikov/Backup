package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter67 §A — «SUCCESS» только при двух доказательствах.
 *
 * Проверяется то же правило, которым пользуется боевой код
 * (`ScreenBrightness.WriteResult`, `AdbClient.execExpect`, `JitRef.readProp`): запись/команда
 * считается успешной только когда (1) её принял API/она отработала и (2) фактически прочитанное
 * значение совпало с ожидаемым. Один факт из двух — НЕ успех.
 */
class Iter67WriteVerdictTest {

    // ───────────────────────── 1. Какие строки содержит исходный код ─────────────────────────

    private val src: String by lazy {
        java.io.File("app/src/main/java/com/example/voiceapp3/tools/ScreenBrightness.kt").readText()
    }

    @Test
    fun `writeSystemInt больше не печатает SUCCESS по одному readback`() {
        // fail-before: строка `VERIFY settings write: … → SUCCESS` стояла под `if (verified)`,
        // то есть печаталась при `putInt == false`, если старое значение уже совпало.
        assertTrue("нет строки с двумя фактами: write= readback_ok=",
            src.contains("readback_ok=\$readbackOk"))
        assertFalse("остался вердикт по одному readback",
            src.contains("if (verified) {\n            AppLog.i(TAG, \"VERIFY settings write"))
    }

    @Test
    fun `WriteResult различает два факта, а не одно булево`() {
        // fail-before: `data class WriteResult(val ok: Boolean, …)` — второй факт отсутствовал.
        val verdict = java.io.File("app/src/main/java/com/example/voiceapp3/tools/WriteVerdict.kt").readText()
        // fail-before: `data class WriteResult(val ok: Boolean, readback: Int?, error: String?)`.
        assertTrue("WriteResult не принимает writeAccepted/readbackOk",
            verdict.contains("val writeAccepted: Boolean") && verdict.contains("val readbackOk: Boolean"))
    }

    // ───────────────────────── 2. Правило вердикта на значениях с ГУ ─────────────────────────

    @Test
    fun `SUCCESS только когда верны оба факта`() {
        assertEquals("SUCCESS", WriteResultVerdict.label(true, true))
        assertTrue(WriteResultVerdict.ok(true, true))
    }

    @Test
    fun `API отказал, readback совпал — это не SUCCESS`() {
        // Живой случай: Settings.System.putInt вернул false, а значение в базе уже было нужным.
        val label = WriteResultVerdict.label(writeAccepted = false, readbackOk = true)
        assertEquals("PARTIAL write=fail readback=ok", label)
        assertFalse("ok при отказе API", WriteResultVerdict.ok(false, true))
        assertFalse(label.contains("SUCCESS"))
    }

    @Test
    fun `API принял, значение не изменилось — это не SUCCESS`() {
        assertEquals("PARTIAL write=ok readback=fail", WriteResultVerdict.label(true, false))
        assertFalse(WriteResultVerdict.ok(true, false))
    }

    @Test
    fun `оба факта ложны — FAILED`() {
        assertEquals("FAILED", WriteResultVerdict.label(false, false))
    }

    // ───────────────────────── 3. execExpect: rc=0 ≠ ожидание ─────────────────────────

    @Test
    fun `rc=0 и вывод не тот — не успех (живой случай getprop=false)`() {
        // fail-before: `AdbClient.classify` печатал SUCCESS по rc=0, значение false игнорировалось.
        assertFalse(ExecExpect.evaluate(rc = 0, out = "false", expect = "true"))
        assertTrue(ExecExpect.evaluate(rc = 0, out = " true\n", expect = "true"))
    }

    @Test
    fun `строка журнала всегда содержит expect и got`() {
        val line = ExecExpect.logLine(rc = 0, out = "false", expect = "true",
            cmd = "getprop dalvik.vm.usejit")
        assertTrue("нет expect=: $line", line.contains("expect=«true»"))
        assertTrue("нет got=: $line", line.contains("got=«false»"))
        assertTrue("нет вердикта FAILED: $line", line.contains("FAILED"))
        assertFalse("ложный SUCCESS: $line", line.contains("→ SUCCESS"))
    }

    @Test
    fun `rc не ноль при совпавшем выводе — тоже не успех`() {
        assertFalse(ExecExpect.evaluate(rc = 1, out = "true", expect = "true"))
        assertTrue(ExecExpect.logLine(1, "true", "true").contains("rc=1"))
    }

    @Test
    fun `execExpect объявлен в AdbClient и пишет expect=got`() {
        val adb = java.io.File("app/src/main/java/com/example/voiceapp3/tools/AdbClient.kt").readText()
        // fail-before: метода `execExpect` не существовало.
        assertTrue("нет fun execExpect", adb.contains("fun execExpect("))
        assertTrue("execExpect не логирует expect/got",
            adb.contains("ExecExpect.logLine(rc, out, expect, command)"))
    }

}
