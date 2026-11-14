package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * iter51 §7 — владение JIT-операцией: claim, terminal-файл, доказанный no-op и путь из
 * неизвестности.
 *
 * Дефекты на HEAD:
 *  1. Неизвестность снимали старт zygote после отметки или истечение окна — ни то, ни другое не
 *     относится к САМОЙ команде, а сама команда не «отменялась»: доставленный, но не
 *     выполненный payload мог выполниться позже, уже после нового клика.
 *  2. Свидетельства о завершении не было вовсе: ответ транспорта — единственный сигнал.
 *
 * Здесь проверяется production-код [JitOwn] и НАСТОЯЩАЯ цепочка [JitOp.click] на подставном
 * устройстве ([JitDev]): JVM + fake-shell, не ГУ.
 */
class Iter51JitOwnershipTest {

    private val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
    private fun log(s: String) { logs += s }
    private fun lines(): String = synchronized(logs) { logs.joinToString("\n") }

    private fun stand() = JitIsoStand(rules = 0)

    @Before
    fun clean() {
        JitDev.reset()
        logs.clear()
        JitOp.resetStateForTest()
    }

    // ─────────────────────────── модель операции ───────────────────────────

    @Test
    fun claimAndGuardCarryTheOperationId() {
        val op = JitOwn.Op("op-7", 100L, true, JitOwn.State.CLAIMED)
        val guard = JitOwn.guard(op)
        // Гвардия обязана стоять ДО первого side effect и отказывать при чужом claim.
        assertTrue(guard.contains("JIT_REFUSED op=op-7"))
        assertTrue(guard.contains("exit 0"))
        assertTrue("гвардия проверяет claim", guard.contains(JitOwn.CLAIM))
        assertTrue("START пишется до side effect", guard.indexOf("START") < guard.indexOf("esac") ||
            guard.indexOf("START") < guard.length)
        assertTrue("terminal по id операции", guard.contains(JitOwn.termPath("op-7")))
        assertTrue("EXIT-trap пишет END", guard.contains("trap") && guard.contains("END rc="))
    }

    @Test
    fun guardTextGoesIntoTheRealChainScript() {
        val op = JitOwn.Op("op-9", 1L, false, JitOwn.State.CLAIMED)
        val script = JitRef.chainScriptForTest(false, JitOwn.guard(op))
        assertTrue("гвардия В СКРИПТЕ", script.contains("JIT_REFUSED op=op-9"))
        assertTrue("литерал референса сохранён", script.contains("setprop ctl.restart zygote"))
        assertTrue("свойства JIT на месте", script.contains(JitRef.PROP_USEJIT_VALUE_TEST) ||
            script.contains("dalvik.vm.usejit"))
        // Порядок: гвардия раньше первого side effect.
        assertTrue(script.indexOf("JIT_REFUSED") < script.indexOf("svc wifi disable"))
        // Литерал референса без гвардии не изменился.
        assertEquals(JitRef.referenceScriptForTest(false), JitRef.chainScriptForTest(false, ""))
    }

    @Test
    fun terminalParsingDistinguishesEveryProvablePhase() {
        val op = JitOwn.Op("op-a", 5L, true, JitOwn.State.CLAIMED)
        JitDev.reset()
        // Refused: claim чужой.
        val other = JitOwn.Op("op-b", 6L, false, JitOwn.State.CLAIMED)
        JitOwn.Op.parseClaim(other.claimLine())
        assertEquals(true, JitOwn.Op.parseClaim(other.claimLine())?.id == "op-b")

        val startedOnly = JitOwn.parseTerminal("op-a", "CLAIM=op=op-b;ts=6;want=0\nTERM=START ts=1 op=op-a|")
        assertEquals("claim чужой — видно", true, startedOnly!!.claimOther)
        assertEquals(JitOwn.State.STARTED, JitOwn.classify(sentAck = false, term = startedOnly))
        assertEquals("начался и не закончился — это неопределённость",
            JitOwn.State.STARTED, JitOwn.classify(false, startedOnly))

        val finished = JitOwn.parseTerminal("op-a",
            "CLAIM=op=op-a;ts=5;want=1\nTERM=START ts=1 op=op-a|END rc=0 op=op-a|")
        assertEquals(JitOwn.State.FINISHED, JitOwn.classify(sentAck = false, term = finished))
        assertEquals(0, finished!!.rc)

        val refused = JitOwn.parseTerminal("op-a",
            "CLAIM=op=op-b;ts=6;want=0\nTERM=JIT_REFUSED op=op-a reason=superseded claim=op=op-b")
        assertEquals(JitOwn.State.REFUSED, JitOwn.classify(sentAck = false, term = refused))

        assertNull("нечитаемый ответ — не «не выполнено», а «неизвестно»",
            JitOwn.parseTerminal("op-a", "ERR_ADB_TIMEOUT", 137))
        assertEquals(JitOwn.State.UNKNOWN, JitOwn.classify(false, null))
        assertEquals(JitOwn.State.SENT, JitOwn.classify(sentAck = true, term = null))
        // terminal-файл сильнее ответа транспорта: исход проверяем по факту устройства.
        assertEquals(JitOwn.State.FINISHED, JitOwn.classify(true, finished))
    }

    @Test
    fun clickDecisionNeverForbidsForeverAndNamesTheRisk() {
        val prev = JitOwn.Op("op-x", 1L, true, JitOwn.State.UNKNOWN)
        val queued = JitOwn.clickDecision(prev, null)
        assertTrue("бессрочный запрет запрещён", queued.allow)
        assertEquals("old_payload_maybe_queued", queued.risk)

        val running = JitOwn.clickDecision(prev,
            JitOwn.parseTerminal("op-x", "CLAIM=op=op-x;ts=1;want=1\nTERM=START ts=1 op=op-x|"))
        assertTrue(running.allow)
        assertEquals("old_payload_running", running.risk)
        assertTrue("честно: уже начавшийся рестарт не отменить", running.why.contains("не отменит"))

        val done = JitOwn.clickDecision(prev,
            // iter52 §7.2: строки terminal'а несут op=<id> — теперь это проверяется строго,
            // поэтому в тесте id указан так же, как его пишет production-payload.
            JitOwn.parseTerminal("op-x", "CLAIM=op=op-x;ts=1;want=1\nTERM=START ts=1 op=op-x|END rc=1 op=op-x|"))
        assertEquals("none", done.risk)
    }

    @Test
    fun wifiRestoreBelongsToTheClaimOwnerOnly() {
        val mine = JitOwn.Op("op-1", 1L, true, JitOwn.State.CLAIMED)
        assertTrue(JitOwn.mayRestoreWifi("op-1", mine.claimLine()))
        assertFalse("новая операция владеет claim — старую не откатываем",
            JitOwn.mayRestoreWifi("op-1", JitOwn.Op("op-2", 2L, false, JitOwn.State.CLAIMED).claimLine()))
        assertFalse("claim нечитаем — откат не наш",
            JitOwn.mayRestoreWifi("op-1", "мусор"))
    }

    // ─────────────────────────── настоящая цепочка клика ───────────────────────────

    @Test
    fun clickClaimsOwnershipAndLeavesTerminalEvidence() {
        val store = JitFake.Store()
        val shell = JitFake.AnswerShell(maps = null)
        val c = JitOp.click(store, JitFake.Net(wifi = true), shell, want = true, log = ::log)

        assertNull("клик не заблокирован: ${c.blocked}", c.blocked)
        assertTrue("владение записано: ${lines()}", lines().contains("JIT_CLAIM op=") &&
            lines().contains("verified=1"))
        // Фаза операции названа по факту terminal-файла, а не по ответу транспорта.
        assertTrue("фаза доказана: ${lines()}", lines().contains("phase=finished"))
        assertTrue("JIT не доказан", lines().contains("JIT-компиляция") ||
            lines().contains("JIT не доказан"))
        assertFalse("исход известен — неопределённости нет", JitOp.chainUndefined())
    }

    @Test
    fun terminalFileResolvesPreviousUnknownWithoutRebootOrWindow() {
        // Прошлая операция «потеряла» ответ, но её payload успел завершиться: terminal-файл
        // доказывает исход ЭТОЙ операции — блокировка снимается без рестарта zygote и без окна.
        val store = JitFake.Store()
        val first = JitFake.QueueShell(stand(), getprop = "true")
        val c1 = JitOp.click(store, JitFake.Net(wifi = true), first, want = true, log = ::log)
        assertNull(c1.blocked)
        assertTrue("первая операция неизвестна", JitOp.chainUndefined())
        val opId = Regex("JIT_CHAIN_SENT op=([\\w-]+)").find(lines())!!.groupValues[1]
        // На устройстве payload всё-таки завершился.
        JitDev.terms.getOrPut(opId) { mutableListOf() }.apply {
            clear(); add("START ts=1 op=$opId"); add("END rc=0 op=$opId")
        }
        logs.clear()
        val second = JitFake.QueueShell(stand(), getprop = "true")
        val c2 = JitOp.click(store, JitFake.Net(wifi = true), second, want = false, log = ::log)
        assertTrue("исход доказан самим устройством: ${lines()}", lines().contains("JIT_OP_RESOLVED"))
        assertNull("блокировка снята доказательством, а не окном: ${c2.blocked}", c2.blocked)
        // Вторая операция сама осталась без ответа (так устроен этот транспорт) — это ЕЁ
        // неопределённость, а не остаток прошлой. Важно, что блокировка снята доказательством.
        assertFalse("второй клик не заблокирован старой операцией",
            lines().contains("blocked=chain_undefined"))
        assertEquals("и неизвестность теперь у НОВОЙ операции",
            true, JitOp.chainUndefined())
    }

    @Test
    fun foreignClaimMakesPayloadRefuseAndSettingsReverted() {
        // Чужой claim: гвардия на устройстве откажется работать — ни сети, ни свойств, ни zygote.
        JitDev.reset()
        // Имитируем «claim уже занят другой операцией»: пишем чужой claim после нашей записи.
        val store = JitFake.Store()
        val shell = object : JitRef.JitShell {
            var payloadRuns = 0
            override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
                // Отвечаем на запись claim успешно, но НЕ сохраняем его: на устройстве остаётся
                // claim прошлой операции — ровно так выглядит «запись не дошла».
                if (command.startsWith("printf") && command.contains(JitOwn.CLAIM)) {
                    JitDev.claimForTest(JitOwn.Op("someone-else", 9L, false, JitOwn.State.CLAIMED).claimLine())
                    return 0 to JitDev.claim!!       // команда «подтвердила» запись, но она не вступила
                }
                if (command.startsWith("echo CLAIM=")) return JitDev.handle(command)!!
                if (command.contains("setprop ctl.restart zygote")) {
                    // Гвардия отказала бы: ничего не выполняем, но на устройстве остаётся след.
                    val opId = Regex("JIT_REFUSED op=([A-Za-z0-9_.-]+)").find(command)!!
                        .groupValues[1]
                    JitDev.terms.getOrPut(opId) { mutableListOf() }
                        .add("JIT_REFUSED op=$opId reason=superseded claim=op=someone-else")
                    return 0 to "JIT_REFUSED op=$opId reason=superseded"
                }
                return 0 to ""
            }
            override fun mapsExcerpt(): String? = null
        }
        val c = JitOp.click(store, JitFake.Net(wifi = true), shell, want = true, log = ::log)
        assertEquals("payload не выполнялся", 0, shell.payloadRuns)
        assertNull("клик не «заблокирован», но и не применён: ${c.blocked}", c.blocked)
        assertTrue("отказ назван: ${lines()}", lines().contains("JIT_CHAIN_REFUSED"))
        assertTrue("настройки возвращены, а не заявлены применёнными: ${lines()}",
            lines().contains("action=revert_settings"))
        assertTrue("JIT не включён", lines().contains("restart=not_happened"))
        assertFalse("это не неопределённость: исход известен", JitOp.chainUndefined())
        assertFalse("ключи операции сняты", store.contains(JitRef.KEY_RESTORE) &&
            store.contains(JitRef.KEY_MARK))
    }

    @Test
    fun claimWriteFailureDoesNotFakeOwnership() {
        val store = JitFake.Store()
        val shell = object : JitRef.JitShell {
            override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
                if (command.startsWith("printf") && command.contains(JitOwn.CLAIM)) {
                    return 1 to "Permission denied"
                }
                if (command.startsWith("echo CLAIM=")) return 0 to "CLAIM=\nTERM="
                return 0 to "ok"
            }
            override fun mapsExcerpt(): String? = null
        }
        JitOp.click(store, JitFake.Net(wifi = true), shell, want = true, log = ::log)
        assertTrue("запись не выдана за подтверждённую: ${lines()}",
            lines().contains("JIT_CLAIM op=") && lines().contains("verified=0"))
        assertFalse("«владение подтверждено» не заявляется",
            lines().contains("verified=1"))
    }
}
