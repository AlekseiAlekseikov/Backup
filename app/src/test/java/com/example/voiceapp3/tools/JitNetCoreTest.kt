package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33p (1.0.61) — поведение ОДНОЙ операции JIT и сети вокруг неё.
 *
 * Проверяется исполняемо, на настоящих Kotlin-строках и фейковом канале: снимок, единственная
 * цепочка, фазы по журналу, решения на старте процесса. Ни один тест не читает исходный текст
 * «на память» — статические проверки живут в `JitToggleChainTest`.
 */
class JitNetCoreTest {

    // ───────────────────────────── фейковый мир ─────────────────────────────

    private class MemPrefs : NetPrefs {
        val map = LinkedHashMap<String, String?>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?): Boolean {
            map[key] = value
            return true
        }
    }

    private class World(
        var wifi: Boolean = true,
        var ap: Boolean = false,
        var zygote: String = "1000",
        var journal: String = "",
        var usejit: String = "false",
        var link: String = "up",
        var rules: Int = 0
    ) {
        val prefs = MemPrefs()
        val log = mutableListOf<String>()
        val scripts = mutableListOf<String>()
        var now = 1_000_000L
        var apProbeOutput: String? = null
        var setWifiCalls = 0
        var startApCalls = 0
        /** Порядок сетевых действий (для проверки «AP раньше Wi-Fi», как в `w0/e2.run`). */
        val order = mutableListOf<String>()
        var journalReads = 0
        var purged = 0
        /** По умолчанию любой скрипт отвечает пустой строкой; проба usejit — «false». */
        var scriptResult: (String) -> ScriptOut = { s ->
            ScriptOut(0, if (s.contains("getprop dalvik.vm.usejit")) "RT=false" else "OK")
        }

        fun core(
            ttlMs: Long = JitNetModel.OPERATION_TTL_MS,
            lateWindowMs: Long = JitNetModel.LATE_RESTART_WINDOW_MS,
            attempts: Int = 1
        ): JitNetCore = JitNetCore(
            prefs = prefs,
            readWifi = { wifi },
            setWifi = { on -> setWifiCalls++; order += "wifi"; wifi = on },
            apProbe = { apProbeOutput ?: "P=1\nOS=${if (ap) "up" else "down"}\nA=${if (ap) 1 else 0}" },
            link = { link },
            readZygotePid = { zygote },
            readJournal = { journalReads++; journal },
            purgeJournals = { purged++ },
            runScript = { script, _ -> scripts += script; scriptResult(script) },
            log = { log += it },
            now = { now },
            sleep = {},
            ttlMs = ttlMs,
            wifiAttempts = attempts,
            wifiPauseMs = 0,
            lateWindowMs = lateWindowMs
        ).also { c -> c.startSoftAp = { startApCalls++; order += "ap"; ap = true } }

        fun joined(): String = log.joinToString("\n")
    }

    private fun step(op: String, name: String) = "JITSTEP op=$op step=$name\n"

    // ───────────────────── 1. снимок операции ДО сетевых изменений ─────────────────────

    @Test
    fun snapshotIsWrittenBeforeAnyNetworkChange() {
        val w = World(wifi = true, ap = true, zygote = "777", usejit = "false")
        val op = w.core().saveOperation("bootA", "true", "op-bootA-1")
        assertNotNull("снимок должен сохраниться", op)
        assertEquals("wifi=true", "wifi=${op!!.wifi}")
        assertTrue("AP сохранён", op.ap)
        assertEquals("777", op.zygotePid)
        assertEquals("false", op.usejitBefore)
        // Ни одной сетевой команды при сохранении: только чтения.
        assertTrue("при сохранении не запускается ни одной сетевой команды: ${w.scripts}",
            w.scripts.none { it.contains("svc wifi") || it.contains("ctl.restart") })
        // Запись подтверждена чтением обратно и восстанавливается из префа.
        assertEquals(op.wire(), w.core().currentOp()?.wire())
    }

    @Test
    fun snapshotFailsWhenNetworkStateUnknown() {
        val w = World()
        w.apProbeOutput = "P=1"                       // неполная проба ap0 → неизвестно
        assertNull("неполная проба не даёт снимок", w.core().saveOperation("bootA", "true", "op1"))
        assertNull("снимок не записан", w.prefs.get(JitNetModel.PREF_OP))
        assertTrue("в логе честная причина", w.joined().contains("reason=net_state_unknown"))
    }

    @Test
    fun secondClickDoesNotOverwritePendingOperation() {
        val w = World()
        val core = w.core()
        val first = core.saveOperation("bootA", "true", "op-first")
        assertNotNull(first)
        assertNull("второй клик не затирает незавершённую операцию",
            core.saveOperation("bootA", "false", "op-second"))
        assertEquals("op-first", core.currentOp()?.id)
        assertTrue(w.joined().contains("refused reason=pending_operation"))
    }

    @Test
    fun saveRefusedWhenWriteNotConfirmed() {
        val w = World()
        val bad = object : NetPrefs {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String?): Boolean = false
        }
        val core = JitNetCore(
            prefs = bad, readWifi = { true }, setWifi = {}, apProbe = { "P=0\nOS=down\nA=0" },
            link = { "up" }, readZygotePid = { "1" }, readJournal = { "" }, purgeJournals = { 0 },
            runScript = { _, _ -> ScriptOut(0, "") }, log = { w.log += it }, now = { 1L }, sleep = {}
        )
        assertNull("без подтверждения записи операция не начинается",
            core.saveOperation("bootA", "true", "op1"))
        assertTrue("в логе — отсутствие подтверждения: ${w.joined()}",
            w.joined().contains("failed confirmed=no"))
    }

    // ───────────────────────── 2. единственная цепочка ─────────────────────────

    @Test
    fun chainIsOneScriptWithRestartLast() {
        val w = World()
        val core = w.core()
        val op = core.saveOperation("bootA", "true", "op-1")!!
        val out = core.runChain(op, JitNetModel.journalPath(op.id))
        assertEquals(0, out.exitCode)
        val chains = w.scripts.filter { it.contains("svc wifi disable") }
        assertEquals("ровно один скрипт операции", 1, chains.size)
        val s = chains.single()
        val disable = s.indexOf("svc wifi disable")
        val drain = s.indexOf("grep -c 'oif wlan0'")
        val usejit = s.indexOf("setprop dalvik.vm.usejit true")
        val restart = s.indexOf("setprop ctl.restart zygote")
        assertTrue("есть svc wifi disable", disable > 0)
        assertTrue("есть ожидание слива правил", drain > disable)
        assertTrue("usejit ставится после слива", usejit > drain)
        assertTrue("рестарт после usejit", restart > usejit)
        assertTrue("рестарт — последняя команда",
            s.substring(restart).lines().last { it.isNotBlank() }.trim() == "setprop ctl.restart zygote")
        assertFalse("никакого nohup/watcher", s.contains("nohup"))
        assertFalse("никакого umount чужого /data/app", s.contains("umount"))
        assertFalse("никакого usejitprofiles", s.contains("usejitprofiles"))
        assertFalse("никакого ip rule del", s.contains("ip rule del"))
        assertFalse("никакого pkill", s.contains("pkill"))
    }

    @Test
    fun chainJournalRecordsPhasesForOurOpOnly() {
        val w = World()
        val core = w.core()
        val op = core.saveOperation("bootA", "true", "op-1")!!
        // Скрипт «выполнился»: журнал такой, каким его пишет настоящий sh (см. JitChainScriptTest).
        w.journal = step(op.id, "start") + step(op.id, "wifi_disabled") + step(op.id, "drained") +
            step(op.id, "usejit") + step(op.id, "restart_sent") +
            step("op-OTHER", "restart_sent")
        assertEquals(JitFlow.Phase.RESTART_SENT, core.phaseOf(op))
        val steps = JitNetModel.journalSteps(w.journal, op.id)
        assertTrue("шаг start нашей операции", steps.containsKey(JitNetModel.STEP_START))
        assertFalse("чужая операция не подтверждает нашу",
            JitNetModel.journalSteps(step("op-OTHER", "restart_sent"), op.id).isNotEmpty())
    }

    // ─────────────── 3. продолжение операции после рестарта (без второго клика) ───────────────

    @Test
    fun confirmedRestartReturnsWifiWithoutSecondClick() {
        val w = World(wifi = true, ap = true, zygote = "1000")
        val core = w.core()
        val op = core.saveOperation("bootA", "true", "op-1")!!
        w.journal = step(op.id, "start") + step(op.id, "wifi_disabled") + step(op.id, "usejit") +
            step(op.id, "restart_sent")
        // Рестарт состоялся: pid zygote другой, Wi-Fi после disable выключен.
        w.zygote = "2000"
        w.wifi = false
        w.ap = false
        w.now += 30_000
        val rec = core.reconcilePending("bootA", "start")
        assertEquals(Recovery.CONTINUE, rec)
        assertTrue("Wi-Fi включён обратно", w.wifi)
        assertTrue("AP поднят обратно", w.ap)
        assertEquals("снимок снят после подтверждённого возврата", null, core.currentOp())
        assertTrue(w.joined().contains("reason=restart_confirmed"))
        assertEquals("AP запрошен один раз", 1, w.startApCalls)
        assertTrue("сначала AP, потом Wi-Fi (как в w0/e2.run): ${w.order}",
            w.order.indexOf("ap") >= 0 && w.order.indexOf("ap") < w.order.indexOf("wifi"))
    }

    @Test
    fun coldStartWithoutOperationTouchesNothing() {
        val w = World()
        val core = w.core()
        assertEquals(Recovery.NONE, core.reconcilePending("bootA", "start"))
        assertEquals("ни одного сетевого скрипта", 0, w.scripts.size)
        assertEquals(0, w.setWifiCalls)
        assertEquals(0, w.startApCalls)
        assertTrue(w.joined().contains("незавершённой операции нет"))
    }

    @Test
    fun failedRestoreKeepsSnapshot() {
        val w = World(wifi = true, zygote = "1000")
        val core = w.core(attempts = 2)
        val op = core.saveOperation("bootA", "true", "op-1")!!
        w.journal = step(op.id, "start") + step(op.id, "restart_sent")
        w.zygote = "2000"
        // Wi-Fi не поднимается: запрос есть, состояния нет.
        val failing = World(wifi = true, zygote = "1000")
        val core2 = JitNetCore(
            prefs = w.prefs, readWifi = { false }, setWifi = { failing.setWifiCalls++ },
            apProbe = { "P=0\nOS=down\nA=0" }, link = { "down" }, readZygotePid = { w.zygote },
            readJournal = { w.journal }, purgeJournals = { 0 },
            runScript = { _, _ -> ScriptOut(0, "OIF=0") }, log = { w.log += it },
            now = { w.now }, sleep = {}, wifiAttempts = 2, wifiPauseMs = 0
        )
        val rec = core2.reconcilePending("bootA", "start")
        assertEquals("неподтверждённый возврат — снимок остаётся", Recovery.HELD, rec)
        assertNotNull(w.prefs.get(JitNetModel.PREF_OP))
        assertTrue(w.joined().contains("JIT_OP_KEEP"))
        assertTrue("ноль ложных «готово»", !w.joined().contains("marker=cleared"))
    }

    // ─────────────────── 4. прерывание до/после zygote, поздний рестарт ───────────────────

    @Test
    fun interruptionBeforeZygoteCompensatesOnlyAfterWindow() {
        val w = World(wifi = true, zygote = "1000")
        val core = w.core(lateWindowMs = 60_000)
        val op = core.saveOperation("bootA", "true", "op-1")!!
        w.journal = step(op.id, "start") + step(op.id, "wifi_disabled")
        w.wifi = false
        w.now += 10_000
        assertEquals("внутри окна поздний рестарт ещё возможен",
            Recovery.HELD, core.reconcilePending("bootA", "start"))
        assertEquals("сеть не тронута", false, w.wifi)
        w.now += 60_000
        val rec = core.reconcilePending("bootA", "start")
        assertEquals(Recovery.COMPENSATE, rec)
        assertTrue("Wi-Fi возвращён к сохранённому", w.wifi)
        assertEquals(null, core.currentOp())
        assertTrue(w.joined().contains("reason=restart_not_happening"))
    }

    @Test
    fun chainReturningWithoutRestartCompensatesImmediately() {
        val w = World(wifi = true, zygote = "1000")
        val core = w.core()
        val op = core.saveOperation("bootA", "true", "op-1")!!
        w.journal = step(op.id, "start") + step(op.id, "usejit")
        w.wifi = false
        // Скрипт вернул управление — позднего рестарта быть не может.
        val rec = core.reconcilePending("bootA", "chain_returned", lateRestartPossible = false)
        assertEquals(Recovery.COMPENSATE, rec)
        assertTrue(w.wifi)
        assertTrue("прежнее значение usejit возвращается", w.scripts.any { it.contains("JIT_ROLLBACK") })
    }

    @Test
    fun noEvidenceAndNetworkChangedHoldsForOwner() {
        val w = World(wifi = true, zygote = "1000")
        val core = w.core()
        val op = core.saveOperation("bootA", "true", "op-1")!!
        w.journal = ""            // журнала нет вовсе
        w.wifi = false            // но Wi-Fi выключен: сеть, возможно, трогали
        w.now += 5_000
        assertEquals(Recovery.HELD, core.reconcilePending("bootA", "start"))
        assertFalse("сеть не трогаем", w.wifi)
        assertNotNull("снимок сохранён", w.prefs.get(JitNetModel.PREF_OP))
        assertTrue(w.joined().contains("need=owner_decision"))
    }

    @Test
    fun noJournalButNetworkUntouchedIsDiscarded() {
        val w = World(wifi = true, ap = false)
        val core = w.core()
        core.saveOperation("bootA", "true", "op-1")
        w.journal = ""
        assertEquals(Recovery.DISCARDED, core.reconcilePending("bootA", "start"))
        assertNull("снимок снят без сетевых команд", core.currentOp())
        assertEquals(0, w.setWifiCalls)
        assertTrue(w.joined().contains("reason=net_untouched"))
    }

    @Test
    fun expiredOperationIsHeldNotRestored() {
        val w = World(wifi = true, zygote = "1000")
        val core = w.core(ttlMs = 60_000)
        val op = core.saveOperation("bootA", "true", "op-1")!!
        w.journal = step(op.id, "start") + step(op.id, "restart_sent")
        w.zygote = "2000"
        w.wifi = false
        w.now += 120_000
        assertEquals(Recovery.HELD, core.reconcilePending("bootA", "start"))
        assertEquals("просроченная операция сеть не трогает", false, w.wifi)
        assertNotNull(w.prefs.get(JitNetModel.PREF_OP))
    }

    @Test
    fun rebootCompensatesBecauseLateRestartIsImpossible() {
        val w = World(wifi = true, zygote = "1000")
        val core = w.core()
        val op = core.saveOperation("bootA", "true", "op-1")!!
        w.journal = step(op.id, "start") + step(op.id, "usejit")
        w.wifi = false
        w.now += 5_000
        assertEquals(Recovery.COMPENSATE, core.reconcilePending("bootB", "start"))
        assertTrue(w.wifi)
    }

    @Test
    fun staleForeignJournalDoesNotProvePhase() {
        val w = World(wifi = true, zygote = "1000")
        val core = w.core()
        val op = core.saveOperation("bootA", "true", "op-1")!!
        // Журнал прошлой операции с ДРУГИМ id: фаза не доказана.
        w.journal = step("op-old-123", "restart_sent") + "JITSTEP step=restart_sent\n"
        assertEquals(JitFlow.Phase.NONE, core.phaseOf(op))
        w.wifi = false
        assertEquals(JitFlow.Phase.NONE, core.phaseOf(op))
    }

    @Test
    fun restartVerdictNeedsPidChange() {
        assertEquals("ok", JitFlow.restartVerdict("1000", "2000"))
        assertEquals("no", JitFlow.restartVerdict("1000", "1000"))
        assertEquals("unknown", JitFlow.restartVerdict("", "2000"))
        assertEquals("unknown", JitFlow.restartVerdict("1000", ""))
        assertEquals("unknown", JitFlow.restartVerdict("pid 1000", "2000"))
    }

    // ───────────────────── 5. сеть: запрос ≠ подтверждение ─────────────────────

    @Test
    fun wifiRequestWithoutStateIsNotSuccess() {
        val w = World(wifi = true, zygote = "1000")
        val op = w.core().saveOperation("bootA", "true", "op-1")!!
        w.journal = step(op.id, "start") + step(op.id, "restart_sent")
        w.zygote = "2000"
        var calls = 0
        val core = JitNetCore(
            prefs = w.prefs, readWifi = { false }, setWifi = { calls++ },
            apProbe = { "P=0\nOS=down\nA=0" }, link = { "down" }, readZygotePid = { w.zygote },
            readJournal = { w.journal }, purgeJournals = { 0 },
            runScript = { _, _ -> ScriptOut(0, "OIF=0") }, log = { w.log += it },
            now = { w.now }, sleep = {}, wifiAttempts = 1, wifiPauseMs = 0
        )
        val res = core.restore(op, "unit", 1)
        assertEquals("запрос был", 1, calls)
        assertFalse("но состояние не подтверждено", res.wifiConfirmed)
        assertFalse(res.fullyRestored)
        assertTrue(w.joined().contains("это НЕ «интернет восстановлен»"))
    }

    @Test
    fun apOffIsNotAnErrorWhenItWasOff() {
        val w = World(wifi = true, ap = false)
        val op = w.core().saveOperation("bootA", "true", "op-1")!!
        val res = w.core().restore(op, "unit", 1)
        assertTrue("AP не запрашивали — не ошибка", res.apConfirmed)
        assertTrue("Wi-Fi уже включён", res.wifiConfirmed)
        assertEquals("точка доступа не поднималась", 0, w.startApCalls)
        assertTrue(res.fullyRestored)
    }

    @Test
    fun rulesAreOnlyCountedNeverDeleted() {
        val w = World()
        val core = w.core()
        val op = core.saveOperation("bootA", "true", "op-1")!!
        core.restore(op, "unit", 1)
        val probe = w.scripts.first { it.contains("OIF=") }
        assertTrue("правило только считаем", probe.contains("grep -c 'oif wlan0'"))
        assertFalse("удаления нет", probe.contains("del") || probe.contains("flush"))
        assertTrue("нет ни одной команды ip rule del во всех скриптах",
            w.scripts.none { it.contains("ip rule del") || it.contains("ip rule flush") })
    }

    // ─────────────────── 6. защита снимка от чужих рук ───────────────────

    @Test
    fun clearRefusesForeignOperation() {
        val w = World()
        val core = w.core()
        core.saveOperation("bootA", "true", "op-mine")
        assertFalse("чужой id не снимает снимок", core.clearOp("op-other"))
        assertNotNull(core.currentOp())
        assertTrue(core.clearOp("op-mine"))
        assertNull(core.currentOp())
        assertTrue(w.joined().contains("refused reason=foreign_op"))
    }

    // ───────────────────── 7. вердикт применения JIT ─────────────────────

    @Test
    fun verdictIsSeparateFromFields() {
        assertEquals("applied", JitFlow.verdict("true", "true", true, "ok"))
        assertEquals("unverified", JitFlow.verdict("true", "true", false, "ok"))
        assertEquals("unverified", JitFlow.verdict("true", "true", null, "ok"))
        assertEquals("unknown", JitFlow.verdict("true", "true", true, "no"))
        assertEquals("not_applied", JitFlow.verdict("false", "true", true, "ok"))
        assertEquals("unknown", JitFlow.verdict("", "true", true, "ok"))
        assertEquals("applied", JitFlow.verdict("false", "false", null, "ok"))
    }

    @Test
    fun opIdIsShellSafeAndJournalPathIsOwn() {
        val id = JitFlow.opId(1_700_000_000_000L, "0b1c-uuid", "a/b c")
        assertTrue("id без спецсимволов", id.all { it.isLetterOrDigit() || it == '-' })
        val path = JitNetModel.journalPath(id)
        assertTrue(path.startsWith("/data/local/tmp/"))
        assertTrue(path.endsWith("$id.log"))
        assertFalse("никаких кавычек в пути", path.contains("'") || path.contains(" "))
    }
}
