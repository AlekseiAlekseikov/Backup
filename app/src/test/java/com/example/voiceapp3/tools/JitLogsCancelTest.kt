package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Логи и границы действий.
 *
 * 1. В логе РАЗДЕЛЬНО: состояние сети (что было), запрос (что отправлено), ответ/ошибка
 *    транспорта, результат сверки. Ни одна строка не выдаёт запрос за подтверждённый факт.
 * 2. Механизма отмены/компенсации нет (в референсе тоже): снять операцию между кликом и
 *    рестартом нечем, поэтому возврат сети только ВКЛЮЧАЕТ то, что было включено.
 */
class JitLogsCancelTest {

    private fun stand() = JitIsoStand(rules = 0)

    @Test
    fun logSeparatesStateRequestAndTransport() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val log = mutableListOf<String>()
        JitOp.click(store, net, JitFake.QueueShell(stand()).apply { runInline = 1 },
            want = true, waiter = JitFake.Waiter(), log = { log += it })

        val click = log.first { it.startsWith("JIT_CLICK") }
        assertTrue("состояние — отдельным полем", click.contains("net=wifi=1,ap=0"))
        assertTrue("результат записи виден", click.contains("saved=true"))

        val sent = log.first { it.startsWith("JIT_CHAIN_SENT") }
        assertTrue("шаг монтирований есть, как в референсе", sent.contains("mount_step=present"))

        val back = log.first { it.startsWith("JIT_CHAIN_RETURN") }
        assertTrue("ответ транспорта", back.contains("rc=0"))
        assertTrue("рестарт не заявлен", back.contains("restart=not_claimed"))
        assertTrue("интернет не заявлен", back.contains("internet=not_checked"))
    }

    @Test
    fun lostChannelIsLoggedAsErrorNotSuccess() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val log = mutableListOf<String>()
        JitOp.click(store, net, JitFake.QueueShell(stand()), true, JitFake.Waiter(), log = { log += it })
        assertTrue(log.any { it.startsWith("JIT_CHAIN_RETURN") && it.contains("outcome=channel_lost") })
        assertTrue(log.any { it.startsWith("JIT_CHAIN_ERROR") })
        assertTrue("нет выдачи за успех", log.none { it.contains("result=ok") })
    }

    @Test
    fun unreadablePropertyIsLoggedAsUnknown() {
        val store = JitFake.Store()
        store.values[JitRef.KEY_PEDIDO] = true
        val shell = JitFake.QueueShell(stand()).apply { getpropRc = 1 }
        val log = mutableListOf<String>()
        JitOp.verifyApplied(store, shell, log = { log += it })
        assertTrue(log.any { it.startsWith("JIT pedido=") && it.contains("result=unknown") })
        assertTrue(log.any { it.contains("JIT_PEDIDO kept") })
    }

    @Test
    fun restoreOnlyTurnsNetworkOn() {
        val store = JitFake.Store()
        store.values[JitRef.KEY_RESTORE] = "wifi=1,ap=1"
        val net = JitFake.Net(wifi = false, ap = false)
        JitOp.onProcessStart(store, net, JitFake.Waiter())
        assertEquals("ровно два запроса и оба включающие", listOf("ap=start", "wifi=true"), net.requests)
        assertTrue(net.requests.none { it.contains("false") })
    }

    @Test
    fun noCancelOrCompensationMechanismInCode() {
        fun code(rel: String): String =
            listOf(File(rel), File("app/$rel")).first { it.isFile }.readText()

        for (f in listOf("JitNetCore.kt", "JitNet.kt", "AppPerformance.kt")) {
            val c = code("src/main/java/com/example/voiceapp3/tools/$f")
            for (bad in listOf("fun cancel", "COMPENSATE", "compensat", "DISCARD",
                "jit_cancel", "abortOperation", "rollback")) {
                assertFalse("$f: нет механизма отмены «$bad»", c.contains(bad))
            }
        }
        val core = code("src/main/java/com/example/voiceapp3/tools/JitNetCore.kt")
        assertFalse("ядро не выключает Wi-Fi через API", core.contains("setWifi(false)"))
        val net = code("src/main/java/com/example/voiceapp3/tools/JitNet.kt")
        assertFalse("адаптер не выключает Wi-Fi по своей инициативе", net.contains("setWifiEnabled(false)"))
    }
}
