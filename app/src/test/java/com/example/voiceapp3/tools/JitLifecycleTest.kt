package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Жизненный цикл: shell продолжает работу независимо от клиента; смерть/старт процесса; поздний
 * zygote; чужая причина выключения сети; оба радио были off; timeout без отмены; поздняя команда
 * после нового клика (REVIEW 3-го захода, §0.0 пп.1,2,4 и «Тесты» мандата).
 *
 * Ни один сценарий не должен: возвращать сеть без доказанного завершения скрипта, объявлять
 * операцию завершённой, переписывать снимок или разрешать второй клик.
 */
class JitLifecycleTest {

    private val BOOT = "3d1f5a77-2b4c-4e6d-9f80-11a2b3c4d5e6"

    private fun store(): JitFake.Store = JitFake.Store()

    private fun click(
        store: JitFake.Store,
        net: JitFake.Net,
        shell: JitShell,
        want: Boolean = true,
        log: MutableList<String> = mutableListOf()
    ) = JitOp.click(store, net, shell, want, BOOT, 1_000L, log = { log += it })

    private fun start(
        store: JitFake.Store,
        net: JitFake.Net,
        shell: JitShell,
        waiter: JitFake.Waiter = JitFake.Waiter(),
        log: MutableList<String> = mutableListOf()
    ) = JitOp.onProcessStart(store, net, shell, BOOT, waiter, log = { log += it })

    // ─────────── 1. Ровно сценарий REVIEW: disable → задержка → смерть клиента → поздний zygote ───────────

    @Test
    fun disableThenClientDeathThenLateZygoteRestoresNothing() {
        val store = store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitLifecycleShell(net).apply { answering = false } // таймаут: очередь остаётся

        val res = click(store, net, shell)
        assertEquals(JitOp.ClickResult.STARTED, res.result)
        assertEquals("транспорт не ответил", JitRef.Shell.LOST, store.op()!!.shell)

        // скрипт всё-таки начал исполняться и выключил Wi-Fi (первая команда)
        assertTrue(shell.stepWifiDisable())
        assertFalse(net.wifi)

        // клиент погиб (по любой причине) и стартовал заново в ТОЙ ЖЕ загрузке
        val writes = store.writeCount()
        val raw = store.rawOp()
        val log = mutableListOf<String>()
        val out = start(store, net, shell, log = log)
        assertEquals(
            "сеть выключена, но это НЕ доказательство завершения скрипта",
            JitRef.StartDecision.HOLD_UNSAFE_PHASE, out.decision
        )
        assertTrue("ни одного сетевого запроса", net.requests.isEmpty())
        assertEquals("ноль записей в префы", writes, store.writeCount())
        assertEquals("снимок не переписан", raw, store.rawOp())
        assertTrue(log.any { it.contains("write=none need=owner_decision") })

        // поздний zygote: остаток скрипта доехал уже после старта
        assertTrue(shell.stepRest())
        assertTrue(shell.deviceSteps.contains("drain+setprop+zygote"))
        // повторный старт ничего не меняет — восстановления по-прежнему нет
        val again = start(store, net, shell)
        assertEquals(JitRef.StartDecision.HOLD_UNSAFE_PHASE, again.decision)
        assertTrue(net.requests.isEmpty())
        // и второй клик запрещён: иначе поздняя команда наложилась бы на новое состояние
        assertEquals(JitOp.ClickResult.REFUSED_PENDING, click(store, net, shell).result)
    }

    // ─────────── 2. Сеть выключил кто-то другой, а наш скрипт ещё в очереди ───────────

    @Test
    fun foreignWifiOffIsNotEvidence() {
        val store = store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitLifecycleShell(net).apply { answering = false }
        click(store, net, shell)
        shell.foreignAction(wifi = false)      // пользователь выключил Wi-Fi сам
        val out = start(store, net, shell)
        assertEquals(JitRef.StartDecision.HOLD_UNSAFE_PHASE, out.decision)
        assertTrue("сеть не включаем по чужому действию", net.requests.isEmpty())
    }

    // ─────────── 3. Оба радио были выключены ещё до клика ───────────

    @Test
    fun bothRadiosWereOffDoesNotBecomeDoneByItself() {
        val store = store()
        val net = JitFake.Net(wifi = false, ap = false)
        val shell = JitLifecycleShell(net).apply { answering = false }
        click(store, net, shell)
        assertEquals("сразу DONE не ставим", JitRef.Phase.SENT, store.op()!!.phase)
        assertEquals("второй клик запрещён (скрипт мог остаться в очереди)",
            JitOp.ClickResult.REFUSED_PENDING, click(store, net, shell).result)
        val out = start(store, net, shell)
        assertEquals(JitRef.StartDecision.HOLD_UNSAFE_PHASE, out.decision)
        assertEquals(JitRef.Phase.SENT, store.op()!!.phase)
        assertTrue(net.requests.isEmpty())
    }

    // ─────────── 4. Timeout без отмены: ответа нет, но и «отменено» не считаем ───────────

    @Test
    fun timeoutDoesNotCancelAndBlocksEverything() {
        val store = store()
        val net = JitFake.Net(wifi = true, ap = true)
        val shell = JitLifecycleShell(net).apply { answering = false }
        val res = click(store, net, shell)
        assertEquals(JitRef.ChainOutcome.CHANNEL_LOST, res.run!!.outcome)
        assertEquals("задание осталось в очереди транспорта", 1, shell.queued.size)
        assertEquals(1, shell.accepted)
        // спустя «любое время» (никаких TTL у нас нет) решение то же
        val out = start(store, net, shell, waiter = JitFake.Waiter())
        assertEquals(JitRef.StartDecision.HOLD_UNSAFE_PHASE, out.decision)
        assertEquals("ноль сетевых запросов", 0, net.requests.size)
    }

    // ─────────── 5. Поздняя команда после НОВОГО клика ───────────

    @Test
    fun staleWriterCannotOverwriteNewSnapshot() {
        val store = store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitLifecycleShell(net).apply { answering = true }
        click(store, net, shell)
        assertEquals(JitRef.Shell.REPLIED, store.op()!!.shell)
        assertEquals("после ответа транспорта операция ещё не завершена", JitRef.Phase.SENT,
            store.op()!!.phase)
        assertEquals("второй клик до завершения запрещён",
            JitOp.ClickResult.REFUSED_PENDING, click(store, net, shell).result)

        // Поздний писатель с устаревшей копией снимка (ровно так раньше терялись RESTORED/checked).
        val before = store.rawOp()
        val stale = JitRef.Op(JitRef.Phase.ARMED, true, JitRef.Net(true, false), BOOT, 1L)
        assertFalse("запись по устаревшей копии отклонена",
            JitOp.publishForTest(store, stale, log = {}))
        assertEquals("снимок не тронут", before, store.rawOp())

        // Завершение операции: возврат сети, затем сверка переводит фазу в DONE.
        net.wifi = false
        assertEquals(JitRef.StartDecision.RESTORE, start(store, net, shell).decision)
        assertEquals(JitRef.Phase.RESTORED, store.op()!!.phase)
        start(store, net, shell)
        assertEquals("сверка с доказанным shell переводит операцию в DONE",
            JitRef.Phase.DONE, store.op()!!.phase)
        // и только теперь новый клик разрешён — с новой меткой времени
        val again = JitOp.click(store, net, shell, true, BOOT, 2_000L)
        assertEquals(JitOp.ClickResult.STARTED, again.result)
        assertEquals(2_000L, store.op()!!.ts)
    }

    // ─────────── 6. Клик и старт одновременно ───────────

    @Test
    fun clickAndStartNeverOverlap() {
        val store = store()
        val net = JitFake.Net(wifi = true, ap = true)
        val gate = java.util.concurrent.CyclicBarrier(2)
        val shell = object : JitShell {
            override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
                if (command.startsWith("getprop ")) return 0 to "true"
                Thread.sleep(50)          // держим «транспорт» занятым
                return 0 to "ok"
            }
            override fun cacheVisible(): Boolean? = true
        }
        val results = java.util.Collections.synchronizedList(mutableListOf<String>())
        val t1 = Thread { gate.await(); results += "click:" + click(store, net, shell).result.name }
        val t2 = Thread { gate.await(); results += "start:" + start(store, net, shell).decision.name }
        t1.start(); t2.start(); t1.join(5_000); t2.join(5_000)
        assertEquals(2, results.size)

        // Кто первым занимает единственную операцию — решает планировщик, и оба порядка законны.
        // Проверяем не порядок, а ГАРАНТИИ, которые обязаны держаться при любом из них.
        val clickOutcome = results.single { it.startsWith("click:") }.substringAfter(':')
        val startOutcome = results.single { it.startsWith("start:") }.substringAfter(':')
        assertTrue("клик либо начал операцию, либо получил честный отказ: $clickOutcome",
            clickOutcome == "STARTED" || clickOutcome == "BUSY")
        assertTrue("старт принял решение из допустимых: $startOutcome",
            startOutcome in setOf("IDLE", "HOLD_UNSAFE_PHASE", "RESTORE",
                "NOTHING_TO_RESTORE", "DONE"))
        // оба радио в модель-устройстве включены, значит запросов быть не должно ни от кого:
        // возвращать нечего, а «доступно» ≠ «нужно включить»
        assertTrue("ни одного сетевого запроса: $startOutcome", net.requests.isEmpty())
        // намерение клика не должно быть переписано чужой стороной
        val op = store.op()
        if (clickOutcome == "STARTED") {
            assertTrue("снимок начатой операции не потерян", op != null)
            assertTrue("снимок остался снимком ВКЛЮЧЕНИЯ", op!!.want)
        } else {
            assertEquals("отказ по занятой операции: ноль записей", 0, store.writeCount())
            assertNull("снимка нет: старт только читал", op)
        }
    }

    // ─────────── 7. Повторный старт в том же процессе — не «одна попытка», но и без записи ───────────

    @Test
    fun repeatedStartIsAllowedButWritesNothingWithoutProof() {
        val store = store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitLifecycleShell(net).apply { answering = false }
        click(store, net, shell)
        shell.stepWifiDisable()
        val writes = store.writeCount()
        repeat(3) {
            assertEquals(JitRef.StartDecision.HOLD_UNSAFE_PHASE, start(store, net, shell).decision)
        }
        assertEquals("три разбора старта — ноль записей", writes, store.writeCount())
        assertTrue(net.requests.isEmpty())
    }
}
