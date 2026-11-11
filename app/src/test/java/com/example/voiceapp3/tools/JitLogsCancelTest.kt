package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Логи и «отмена» (мандат §1.3, §3).
 *
 * 1. В логе РАЗДЕЛЬНО: состояние (что было), запрос (что отправлено), ошибки/неизвестность (что не
 *    удалось прочитать). Одна строка не должна выдавать запрос за факт.
 * 2. Механизма отмены/компенсации у нас нет: снять операцию между кликом и рестартом нечем, поэтому
 *    сеть никогда не выключается «после» — восстановление только включает.
 */
class JitLogsCancelTest {

    private val BOOT = "5c6d7e8f-9a0b-4c1d-8e2f-3a4b5c6d7e8f"

    private fun stand() = JitIsoStand(rules = 0)

    @Test
    fun logSeparatesStateRequestAndErrors() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val log = mutableListOf<String>()
        JitOp.click(store, net, stand().Shell(), true, BOOT, 7L, log = { log += it })
        val snapshot = log.first { it.startsWith("JIT_SNAPSHOT want=") }
        assertTrue("состояние — в отдельной строке", snapshot.contains("net=wifi=1,ap=0"))
        assertTrue("снимок подтверждён", snapshot.contains("atomic=ok"))
        val sent = log.first { it.startsWith("JIT_CHAIN_SENT") }
        assertTrue("запрос — отдельная строка", sent.contains("want=true"))
        assertTrue("в строке запроса рестарт не заявлен", sent.contains("text="))
        val back = log.first { it.startsWith("JIT_CHAIN_RETURN") }
        assertTrue("ответ транспорта помечен как недоказательство", back.contains("restart=unknown"))
    }

    @Test
    fun lostChannelIsLoggedAsErrorNotSuccess() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val log = mutableListOf<String>()
        JitOp.click(store, net, JitFake.QueueShell(stand()), true, BOOT, 7L, log = { log += it })
        val back = log.first { it.startsWith("JIT_CHAIN_RETURN") }
        assertTrue("ошибка канала видна отдельно", back.contains("outcome=channel_lost"))
        assertTrue("и не превращается в «применено»", log.none { it.contains("result=ok") })
    }

    @Test
    fun unknownFactIsLoggedAsUnknown() {
        val store = JitFake.Store()
        val shell = JitFake.QueueShell(stand()).apply { getpropRc = 1 }
        val log = mutableListOf<String>()
        JitOp.verifyApplied(store, shell, true, true, log = { log += it })
        val line = log.first { it.startsWith("JIT_APPLIED") }
        assertTrue("факт — unknown", line.contains("result=unknown"))
        assertTrue("рестарт не заявлен", line.contains("restart=not_claimed"))
        assertTrue("намерение сохранено (не выдаём неизвестность за результат)",
            log.any { it.contains("JIT_PEDIDO kept result=unknown") })
    }

    @Test
    fun restoreOnlyTurnsNetworkOn() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = true)
        val shell = JitFake.QueueShell(stand()).apply { runInline = 1 }
        JitOp.click(store, net, shell, true, BOOT, 7L)
        net.wifi = false
        net.ap = false
        net.requests.clear()
        JitOp.onProcessStart(store, net, shell, BOOT, JitFake.Waiter())
        assertEquals("ровно два запроса и оба включающие", listOf("ap=start", "wifi=true"), net.requests)
        assertTrue("ни одного выключающего запроса", net.requests.none { it.contains("false") })
    }

    @Test
    fun noCancelOrCompensationMechanism() {
        fun code(rel: String): String =
            listOf(File(rel), File("app/$rel")).first { it.isFile }.readLines()
                .filterNot {
                    val t = it.trim()
                    t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
                }.joinToString("\n")
        for (f in listOf("JitNetCore.kt", "JitNet.kt", "AppPerformance.kt")) {
            val c = code("src/main/java/com/example/voiceapp3/tools/$f")
            // HOLD_UNSAFE_PHASE — не механизм отмены, а честный отказ действовать без доказательств
            // (см. отчёт §3); запрещены именно компенсация/отмена/фиктивные решения.
            for (bad in listOf("fun cancel", "COMPENSATE", "compensat", "DISCARD",
                "jit_cancel", "abortOperation", "rollback")) {
                assertFalse("$f: нет механизма отмены «$bad»", c.contains(bad))
            }
        }
        // Сеть выключает только скрипт референса (`svc wifi disable`) — в коде нет setWifi(false).
        val core = code("src/main/java/com/example/voiceapp3/tools/JitNetCore.kt")
        assertFalse("ядро не выключает Wi-Fi через API", core.contains("setWifi(false)"))
        val net = code("src/main/java/com/example/voiceapp3/tools/JitNet.kt")
        assertFalse("адаптер не выключает Wi-Fi по своей инициативе", net.contains("setWifiEnabled(false)"))
    }
}
