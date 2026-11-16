package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * iter58 §2.3 — РУЧНОЙ JIT И ВОЗВРАТ ИНТЕРНЕТА (исполнимые исходы, которые раньше не проверялись).
 *
 * Дополняет [Iter57JitE2eTest] (там: один payload, `ctl.restart zygote` последним, возврат Wi‑Fi).
 * Здесь — отказы и неопределённости, на которых «успех» чаще всего рисовался:
 *  1. ОТСУТСТВУЮЩИЙ ROOT: payload отклонён устройством (`Permission denied`) — клик не падает,
 *     успех не заявляется, второй цепочки нет, при следующем старте payload не переисполняется;
 *  2. ПОТЕРЯ ОТВЕТА (исход доставки Unknown): ответа транспорта нет — успех не заявляется,
 *     повторный клик не запускает вторую цепочку (`chain_undefined`), а продолжение при старте
 *     процесса возвращает клиентский Wi‑Fi, НЕ переисполняя payload;
 *  3. НЕИЗВЕСТНОЕ состояние Wi‑Fi: клик блокируется ДО записи и отправки (ноль команд на
 *     устройство, ноль записей в хранилище);
 *  4. ОТКАЗ Android API при возврате сети (`setWifiEnabled` вернул false): возврат назван
 *     неудачным, «интернет есть» не заявляется.
 *
 * Чего тесты НЕ доказывают (в отчёте 🟡): настоящий `zygote`, `svc wifi`, `ip rule`, DNS/маршрут
 * и открытие страницы на ГУ. Здесь JVM + fake-shell; ассоциация Wi‑Fi интернетом не считается.
 */
class Iter58JitNetTest {

    private val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
    private fun log(s: String) { logs += s }
    private fun lines(): String = synchronized(logs) { logs.joinToString("\n") }

    @Before
    fun clean() {
        JitDev.reset()
        logs.clear()
        JitOp.resetStateForTest()
    }

    /** Fake-shell с настраиваемым ответом НА PAYLOAD (служебные команды — модель устройства). */
    private class Shell(
        private val payloadRc: Int = 0,
        private val payloadOut: String = ""
    ) : JitRef.JitShell {
        val executed = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            executed += command
            return JitDev.exec(command) { payloadRc to payloadOut }
        }
        override fun mapsExcerpt(): String? = null
        fun payloadCmds(): List<String> = synchronized(executed) {
            executed.filter { it.contains("setprop ctl.restart zygote") }
        }
    }

    @Test
    fun missingRootIsReportedAndDoesNotClaimSuccessOrRetryThePayload() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = Shell(1, "sh: setprop: Permission denied")

        val c = JitOp.click(store, net, shell, want = true, log = ::log)

        assertNull("клик не заблокирован на входе: ${c.blocked}", c.blocked)
        assertTrue("payload был отправлен", c.sent)
        assertEquals("вторая цепочка не отправлена", 1, shell.payloadCmds().size)
        assertEquals("ответа транспорта нет — это не успех: ${c.outcome}",
            JitRef.ChainOutcome.CHANNEL_LOST, c.outcome)
        assertFalse("применение не заявлено: ${lines()}", lines().contains("verified=applied"))

        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()
        assertEquals("payload не переисполняется после отказа", 1, shell.payloadCmds().size)
    }

    @Test
    fun lostTransportReplyKeepsTheChainUnknownAndRestoresWifiWithoutReplaying() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = Shell(-1, "")           // ответ потерян

        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertNull("первый клик не заблокирован: ${c.blocked}", c.blocked)
        assertEquals("payload отправлен один раз", 1, shell.payloadCmds().size)
        assertEquals("ответа транспорта нет — это не успех: ${c.outcome}",
            JitRef.ChainOutcome.CHANNEL_LOST, c.outcome)

        // Вторая цепочка не стартует, пока исход первой не установлен.
        val shell2 = Shell()
        val c2 = JitOp.click(store, net, shell2, want = false, log = ::log)
        assertEquals("второй клик отклонён: ${c2.blocked}", "chain_undefined", c2.blocked)
        assertFalse("вторая цепочка не отправлена", c2.sent)
        assertEquals("payload всё ещё отправлен один раз",
            1, shell.payloadCmds().size + shell2.payloadCmds().size)

        // Возврат клиентского Wi‑Fi — при старте процесса, без переисполнения payload.
        net.wifi = false
        net.requests.clear()
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()
        assertTrue("Wi‑Fi возвращён: ${net.requests} / ${lines()}", net.requests.contains("wifi=true"))
        assertEquals("payload не переисполнен", 1, shell.payloadCmds().size)
    }

    @Test
    fun unknownWifiStateBlocksBeforeAnyWriteOrSend() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, wifiUnreadable = true)
        val shell = Shell()

        val c = JitOp.click(store, net, shell, want = true, log = ::log)

        assertEquals("клик заблокирован: ${c.blocked}", "wifi_unknown", c.blocked)
        assertFalse("скрипт не отправлялся", c.sent)
        assertEquals("ни одной команды на устройство", 0, shell.executed.size)
        assertEquals("сеть не трогалась", emptyList<String>(), net.requests)
        assertEquals("снимок не записан", 0, store.writeCount())
    }

    @Test
    fun refusedWifiRestoreIsReportedAsNotRestored() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = Shell()
        JitOp.click(store, net, shell, want = true, log = ::log)

        net.wifi = false
        net.requests.clear()
        net.setWifiResult = false          // Android отказал в включении
        val sched = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, sched, ::log)
        sched.drain()

        assertFalse("Wi‑Fi не выдан за включённый", net.wifi)
        assertTrue("попытка названа: ${net.requests}", net.requests.contains("wifi=true"))
        assertTrue("неудача названа в журнале: ${lines()}",
            lines().contains("restore") && !lines().contains("internet=true"))
        assertEquals("payload не переисполнен", 1, shell.payloadCmds().size)
    }
}
