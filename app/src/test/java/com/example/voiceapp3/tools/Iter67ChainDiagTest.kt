package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter67 §E — диагностика клика: фаза, отсутствие terminal, потеря канала.
 *
 * Живой факт: после клика 19.11 в журнале НОЛЬ строк `JIT_OP_PHASE` и `JIT_OP_PROBE`, файлов
 * `claim/lock/term` нет, и понять, где оборвалась цепочка, невозможно.
 */
class Iter67ChainDiagTest {

    @org.junit.Before
    fun setUp() {
        // Состояние клика процесс-глобальное: между тестами сбрасываем, как это делают другие наборы.
        JitOp.resetStateForTest()
        JitDev.reset()
        lines.clear()
    }

    private val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
    private fun log(line: String) { lines += line }

    /** Shell, который «теряет канал» на payload и ничего не отвечает на чтение terminal. */
    private class DropShell : JitRef.JitShell {
        val executed = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            executed += command
            return JitDev.exec(command) {
                if (command.contains("setprop ctl.restart zygote")) 137 to "ERR_CHANNEL_LOST"
                else 0 to ""
            }
        }
        override fun mapsExcerpt(): String? = null
        fun payloadCmds(): List<String> = synchronized(executed) {
            executed.filter { it.contains("setprop ctl.restart zygote") }
        }
    }

    @Test
    fun `JIT_OP_PHASE печатается всегда`() {
        val shell = DropShell()
        JitOp.click(JitFake.Store(), JitFake.Net(wifi = true), shell, want = true, log = ::log)
        // fail-before: строки `JIT_OP_PHASE` в журнале не было ни разу за историю машины.
        assertTrue("нет ни одной фазы: $lines", lines.any { it.startsWith("JIT_OP_PHASE op=") })
        val phase = lines.first { it.startsWith("JIT_OP_PHASE") }
        assertTrue("нет phase=: $phase", phase.contains("phase="))
        assertTrue("нет rc=: $phase", phase.contains("rc="))
    }

    @Test
    fun `отсутствие terminal печатается как absent`() {
        val shell = DropShell()
        JitOp.click(JitFake.Store(), JitFake.Net(wifi = true), shell, want = true, log = ::log)
        // fail-before: строки нет; отсутствие файла молча считалось «неизвестно».
        assertTrue("нет строки absent: $lines",
            lines.any { it.startsWith("JIT_OP_PROBE op=") && it.contains("result=absent") })
        val probe = lines.first { it.contains("result=absent") }
        assertTrue("нет пути файла: $probe", probe.contains("file=/data/local/tmp/voiceapp3_jit_"))
        assertTrue("нет оговорки «не доказательство»: $probe",
            probe.contains("НЕ доказательство"))
    }

    @Test
    fun `потеря канала не порождает второй payload`() {
        val shell = DropShell()
        JitOp.click(JitFake.Store(), JitFake.Net(wifi = true), shell, want = true, log = ::log)
        assertEquals("payload должен уйти ровно один раз: ${shell.payloadCmds().size}",
            1, shell.payloadCmds().size)
        assertTrue("нет строки JIT_CHAIN_LOST: $lines", lines.any { it.startsWith("JIT_CHAIN_LOST op=") })
        val lost = lines.first { it.startsWith("JIT_CHAIN_LOST") }
        assertTrue("не назван запрет повтора: $lost", lost.contains("action=no_resend"))
        assertTrue("не назван риск: $lost", lost.contains("payload_maybe_running=1"))
    }

    @Test
    fun `terminal перечитывается отложенно и это видно строкой`() {
        val shell = DropShell()
        // Планировщик-«сразу»: перечитывание должно состояться и назвать попытку.
        var posted = 0L
        val sched = object : JitRef.JitScheduler {
            override fun postDelayed(delayMs: Long, task: () -> Unit) { posted = delayMs; task() }
        }
        JitOp.click(JitFake.Store(), JitFake.Net(wifi = true), shell, want = true, log = ::log,
            scheduler = sched)
        assertEquals("перечитывание должно быть через 5 с", 5_000L, posted)
        assertTrue("нет строки отложенного перечитывания: $lines",
            lines.any { it.contains("attempt=delayed") })
    }

    @Test
    fun `фаза и проба пишутся и при обычном ответе транспорта`() {
        val shell = Iter61LikeShell()
        JitOp.click(JitFake.Store(), JitFake.Net(wifi = true), shell, want = true, log = ::log)
        assertTrue("фаза должна быть и на успешном пути: $lines",
            lines.any { it.startsWith("JIT_OP_PHASE") })
        assertFalse("ложный JIT_CHAIN_LOST на успешном пути: $lines",
            lines.any { it.startsWith("JIT_CHAIN_LOST") })
    }

    private class Iter61LikeShell : JitRef.JitShell {
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> =
            JitDev.exec(command) { 0 to "" }
        override fun mapsExcerpt(): String? = null
    }
}
