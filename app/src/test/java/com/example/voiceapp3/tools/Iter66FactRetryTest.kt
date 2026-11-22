package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter66 — факт `getprop dalvik.vm.usejit` для тумблера после загрузки ГУ.
 *
 * На ГУ 22.11 15:48 (загрузка) единственное чтение на старте упало по таймауту 8 с (канал ADB
 * переподключался), ответ пришёл через 4 с и был выброшен — тумблер час показывал «состояние
 * неизвестно (getprop не прочитан: rc_nonzero)». Теперь неизвестный факт перечитывается
 * ограниченное число раз (5/15/30/60 с), только чтением.
 */
class Iter66FactRetryTest {

    private class Sch : JitMech.Scheduler {
        val tasks = ArrayDeque<Pair<Long, () -> Unit>>()
        val delays = mutableListOf<Long>()
        override fun postDelayed(delayMs: Long, task: () -> Unit) { delays += delayMs; tasks.addLast(delayMs to task) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().second() }
    }

    private val lines = mutableListOf<String>()

    @Test
    fun stopsAtTheFirstKnownValue() {
        val sch = Sch(); val answers = ArrayDeque(listOf<Boolean?>(null, null, false))
        val known = mutableListOf<Boolean>()
        JitMech.retryFact(sch, { answers.removeFirst() }, { known += it }, { lines += it })
        sch.drain()
        assertEquals(listOf(5_000L, 15_000L, 30_000L), sch.delays)
        assertEquals(listOf(false), known)
        assertEquals(3, lines.count { it.startsWith("JIT_FACT_RETRY attempt=") })
        assertTrue(lines.last(), lines.last().startsWith("JIT_FACT_RETRY attempt=3/4 getprop=false"))
    }

    @Test
    fun givesUpAfterFourAttemptsWithoutAnyLoop() {
        val sch = Sch(); var reads = 0
        JitMech.retryFact(sch, { reads++; null }, { error("не должно") }, { lines += it })
        sch.drain()
        assertEquals(JitMech.FACT_RETRY_DELAYS_MS, sch.delays)
        assertEquals(4, reads)
        assertTrue(lines.last(), lines.last().startsWith("JIT_FACT_RETRY gave_up attempts=4"))
    }

    @Test
    fun readerExceptionCountsAsUnknown() {
        val sch = Sch(); var n = 0
        val known = mutableListOf<Boolean>()
        JitMech.retryFact(sch, { if (n++ == 0) throw RuntimeException("x") else true }, { known += it }, { lines += it })
        sch.drain()
        assertEquals(listOf(true), known)
    }

    @Test
    fun bootProbeRetriesOnlyAnUnknownFactAndOnlyByReading() {
        val dir = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val a = File(dir, "com/example/voiceapp3/tools/AppPerformance.kt").readText()
        val probe = a.substringAfter("fun jitBootProbe(context: Context)").substringBefore("\n    fun ")
        assertTrue(probe, probe.contains("if (fact.value == JitRef.Prop.UNKNOWN) scheduleJitFactRetry("))
        val retry = a.substringAfter("private fun scheduleJitFactRetry(").substringBefore("\n    private fun ")
            .substringBefore("\n    fun ")
        assertTrue(retry, retry.contains("JitMech.retryFact("))
        assertTrue("после прочтения — обычная сверка (если есть jit_pedido)", retry.contains("jitPendingVerify("))
        assertFalse("только чтение", retry.contains("setprop") || retry.contains("AdbClient.execute("))
    }
}
