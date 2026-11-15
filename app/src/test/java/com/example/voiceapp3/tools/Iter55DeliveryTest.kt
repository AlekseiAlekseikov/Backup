package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter55 §5.1 — TERMINAL ДОЛЖЕН ПРИНАДЛЕЖАТЬ ИМЕННО ЭТОЙ ПОПЫТКЕ.
 *
 * Дефект до правки: прямой ответ транспорта без единого `ERR_ADB_*` трактовался как
 * `RUN_SENT` → `Finished`. Строка `VAPP_DIRECT_DONE rc=…`, напечатанная САМИМ payload (эхо
 * команды, текст ошибки, чужой вывод) или предыдущей попыткой, тоже принималась за завершение.
 *
 * Каждый тест проверяет конечное состояние доставки, а не наличие слова «nonce» в коде.
 */
class Iter55DeliveryTest {

    private fun plan() = ScriptDelivery.plan("x", "echo hi")

    @Test
    fun markerPrintedByPayloadIsNotTerminal() {
        // Скрипт сам печатает строку маркера (эхо/сообщение), затем канал теряет ответ.
        val out = "VAPP_DIRECT_DONE rc=0\n"
        val r = ScriptDelivery.directResult("lock_bind", "echo x", 0, out, nonce = "n1")
        assertEquals("чужой маркер не завершает попытку", ScriptDelivery.State.RUN_UNKNOWN, r.state)
        assertTrue("исход остаётся «могло исполниться»: ${r.wire()}", r.mayHaveRun)
        assertEquals("direct_no_terminal", r.contentKind)
    }

    @Test
    fun twoMarkersOfTheSameAttemptAreNotProof() {
        // Дубликат маркера (двойной вывод, склейка буферов) — это уже не однозначный terminal.
        val out = "VAPP_DIRECT_DONE_n2 rc=0\nVAPP_DIRECT_DONE_n2 rc=0\n"
        val r = ScriptDelivery.directResult("lock_bind", "echo x", 0, out, nonce = "n2")
        assertEquals("два маркера не доказывают завершение", ScriptDelivery.State.RUN_UNKNOWN, r.state)
    }

    @Test
    fun exactlyOneOwnMarkerIsTerminalAndCarriesItsRc() {
        // Завершение ровно этой попытки — факт: rc берётся из маркера, а не из ответа канала.
        val out = "VAPP_DIRECT_STARTED_n3\nработa\nVAPP_DIRECT_DONE_n3 rc=7\n"
        val r = ScriptDelivery.directResult("lock_bind", "echo x", 0, out, nonce = "n3")
        assertEquals("свой terminal — доказанное завершение", ScriptDelivery.State.RUN_CONFIRMED, r.state)
        assertEquals("rc скрипта, а не канала", 7, r.rc)
        assertFalse("позднего запуска уже не будет", r.mayHaveRun)
    }

    @Test
    fun directScriptFramesBothMarkersWithTheAttemptNonce() {
        val s = ScriptDelivery.directScript("echo x", nonce = "abc123")
        assertTrue("старт помечен номером попытки: $s", s.contains("VAPP_DIRECT_STARTED_abc123"))
        assertTrue("завершение помечено номером попытки: $s", s.contains("VAPP_DIRECT_DONE_abc123 rc="))
    }

    @Test
    fun runSentWithoutTerminalIsUnknownNotFinished() {
        // Ни один транспорт: «передано на исполнение» ≠ «выполнено».
        val sent = ScriptDelivery.Result(
            0, "", plan(), 1, ScriptDelivery.State.RUN_SENT, true, false, "", true
        )
        val o = ScriptDelivery.outcome(sent, "op1")
        assertTrue("RUN_SENT не даёт Finished: ${o.wire()}", o is ScriptDelivery.DeliveryOutcome.Unknown)
        assertEquals(
            "причина названа",
            "run_sent_terminal_not_proven",
            (o as ScriptDelivery.DeliveryOutcome.Unknown).why
        )
    }
}
