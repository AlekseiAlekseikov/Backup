package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33p-fix3/4 — ПОВЕДЕНЧЕСКИЕ тесты сети (REVIEW iter33p-fix2 и iter33p-fix3).
 *
 * Без `contains` по исходникам: гоняются реальные переходы [JitNetCore] и чистые решения
 * [JitFlow] на подставленных отказах (фейковый Wi-Fi, фейковые скрипты, фейковые префы).
 *
 * Главное, что проверяется здесь (REVIEW iter33p-fix3, «Тест обязателен»):
 *  1. клик → `saveBeforeRestart` (wifi=1) → `svc wifi disable` → успешный zygote (ARMED/RESTARTING/
 *     DONE по id операции) → НОВЫЙ ПРОЦЕСС → возврат Wi-Fi по сохранённому состоянию,
 *     БЕЗ второго клика;
 *  2. обычный холодный старт без операции → НИ ОДНОГО сетевого действия;
 *  3. прерывание ДО zygote (намерение есть, фазы нет) → сеть на старте не трогаем;
 *  4. stale/чужой маркер (другая загрузка / другой op id) → сеть не трогаем;
 *  5. отказ возврата после zygote → маркер сохранён, повтор при следующей операции JIT;
 *  6. отмена рестарта сверяется по id операции и по строке RESTARTING.
 *
 * Как моделируются границы процессов: «новый процесс» — это НОВЫЙ экземпляр [JitNetCore] на тех же
 * префах (маркер `jit_net_saved` живёт в префах = на диске), при этом состояние «железа» (Wi-Fi,
 * правила) сохраняется в объекте [Harness]. Именно так выглядит переход «процесс убит рестартом →
 * процесс поднялся заново»: память обнулилась, диск и состояние устройства — нет.
 */
class JitNetCoreTest {

    // ─────────────────────────────── фейки окружения ───────────────────────────────

    private class FakePrefs : NetPrefs {
        val map = mutableMapOf<String, String>()
        var failWrites = false
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?): Boolean {
            if (failWrites) return false
            if (value == null) map.remove(key) else map[key] = value
            return true
        }
    }

    private class Harness(
        val prefs: FakePrefs = FakePrefs(),
        var wifi: Boolean? = true,
        var apProbe: String? = "P=0\nOS=\nA=0",
        var link: String = "up"
    ) {
        val logs = mutableListOf<String>()
        var setWifiCalls = 0
        var requestedWifi: Boolean? = null
        var scripts = mutableListOf<String>()
        var nowMs = 1_000_000L
        var sleepCalls = 0
        var startApErr: Throwable? = UnsupportedOperationException("no_startSoftAp")

        var respond: (String) -> ScriptOut = { script ->
            when {
                script.contains("JIT_NET_SANITIZE removed=") ->
                    ScriptOut(0, "JIT_NET_SANITIZE removed=1 skipped=0 left=0 scope=oif_wlan0")
                script.contains("ip_rule_wait=") ->
                    ScriptOut(0, "JIT_NET_PRE ip_rule_wait=1 drained=yes oif_wlan0=0")
                script.contains("JIT_NET_PROPS_ROLLBACK") ->
                    ScriptOut(0, "JIT_NET_PROPS_ROLLBACK rt=false pr=false want_rt=false want_pr=false")
                else -> ScriptOut(0, "OK")
            }
        }

        /** Новый экземпляр ядра = новый процесс (память новая, префы и «железо» те же). */
        fun core(linkOverride: String? = null) = JitNetCore(
            prefs = prefs,
            readWifi = { wifi },
            setWifi = { on ->
                setWifiCalls++
                requestedWifi = on
                if (wifi != null) wifi = on
            },
            apProbe = { apProbe },
            link = { linkOverride ?: link },
            runScript = { script, _ -> scripts.add(script); respond(script) },
            log = { logs.add(it) },
            now = { nowMs },
            sleep = { sleepCalls++ }
        ).also { c -> c.startSoftAp = { startApErr?.let { throw it } } }

        fun logText(): String = logs.joinToString("\n")
    }

    private fun marker(h: Harness, wifi: Int, ap: Int, ts: Long) {
        h.prefs.map[JitNetModel.PREF_NET_SAVED] = "wifi=$wifi,ap=$ap,ts=$ts"
    }

    /** Строки файла итога watcher-а для операции [op] на фазе «рестарт выполнен». */
    private fun resultDone(op: String, want: String = "true"): String =
        "${JitNetModel.WATCHER_ARMED} op=$op want=$want delay=8 pid=4242|" +
            "${JitNetModel.WATCHER_RESTARTING} op=$op|" +
            "${JitNetModel.WATCHER_DONE} op=$op runtime=$want persist= zygote_restart=ok waited_s=3|"

    // ─────────────── 1. штатное продолжение операции после zygote (главный тест) ───────────────

    /**
     * click → wifi=1 сохранён → `svc wifi disable` → успешный zygote → НОВЫЙ ПРОЦЕСС →
     * возврат Wi-Fi/AP БЕЗ второго клика.
     */
    @Test
    fun zygoteContinuationRestoresWifiWithoutSecondClick() {
        val h = Harness(wifi = true, apProbe = "P=0\nOS=\nA=0")
        val first = h.core()

        // ── шаги 1–2 операции: состояние сохранено и подтверждено ──
        val saved = first.saveBeforeRestart()!!
        assertEquals(true, saved.wifi)
        val op = JitFlow.opId(h.nowMs, "boot-A", "aaa111")

        // ── шаг 5: подготовка сети гасит Wi-Fi (как `svc wifi disable`) ──
        first.runPreRestart("true")
        h.wifi = false
        h.link = "down"

        // ── рестарт zygote состоялся: файл итога несёт ARMED/RESTARTING/DONE нашего op ──
        val result = resultDone(op)
        assertTrue(JitFlow.armedFor(result, op))
        assertTrue(JitFlow.restartingFor(result, op))
        assertTrue(JitFlow.doneFor(result, op))

        // ── наш процесс убит рестартом; поднимается НОВЫЙ процесс ──
        val intentVerified = true                       // намерение наше и от текущей загрузки
        val phaseProven = JitFlow.armedFor(result, op) || JitFlow.restartingFor(result, op) ||
            JitFlow.doneFor(result, op) || JitFlow.abortedFor(result, op)
        assertEquals("после zygote сеть возвращается как продолжение операции",
            JitFlow.BootNetAction.CONTINUE_OPERATION, JitFlow.bootNetAction(intentVerified, phaseProven))

        h.link = "up"
        val second = h.core()
        assertEquals(Recovery.RESTORED, second.restorePendingInChain("post_zygote"))
        assertEquals("Wi-Fi вернулся без второго клика", true, h.wifi)
        assertEquals(true, h.requestedWifi)
        assertNull("маркер снят по подтверждению", h.prefs.get(JitNetModel.PREF_NET_SAVED))
        assertTrue(h.logText().contains("JIT_NET_RESTORE why=chain:post_zygote"))
        assertTrue(h.logText().contains("JIT_NET_CHAIN done context=post_zygote"))
    }

    // 2. Обычный холодный старт без операции — ни одного сетевого действия.
    @Test
    fun coldStartDoesNoNetworkActions() {
        val h = Harness()
        val action = JitFlow.bootNetAction(intentVerified = false, phaseProven = false)
        assertEquals(JitFlow.BootNetAction.NONE_COLD, action)
        // Приложение в этом случае вызывает только чтение маркера:
        assertNull(h.core().pendingMarker())
        assertEquals(0, h.setWifiCalls)
        assertTrue("ни одного скрипта на устройстве", h.scripts.isEmpty())
    }

    // 3. Прерывание ДО zygote: намерение есть, но фазы нет (ARMED не появился) → сеть не трогаем.
    @Test
    fun interruptionBeforeZygoteLeavesNetworkAlone() {
        val h = Harness(wifi = false)
        marker(h, wifi = 1, ap = 0, ts = h.nowMs)
        val op = JitFlow.opId(h.nowMs, "boot-A", "bbb222")
        val emptyResult = ""                    // watcher не успел ничего записать
        val phaseProven = JitFlow.armedFor(emptyResult, op) || JitFlow.restartingFor(emptyResult, op) ||
            JitFlow.doneFor(emptyResult, op) || JitFlow.abortedFor(emptyResult, op)
        assertEquals(JitFlow.BootNetAction.NONE_COLD, JitFlow.bootNetAction(true, phaseProven))
        assertEquals(0, h.setWifiCalls)
        assertTrue(h.scripts.isEmpty())
    }

    // 4. Stale/чужой маркер: результат от ДРУГОГО op id фазу не доказывает.
    @Test
    fun foreignOperationResultDoesNotProvePhase() {
        val ours = JitFlow.opId(1_000, "boot-A", "ccc333")
        val other = JitFlow.opId(2_000, "boot-A", "ddd444")
        val result = resultDone(other)
        val phaseProven = JitFlow.armedFor(result, ours) || JitFlow.restartingFor(result, ours) ||
            JitFlow.doneFor(result, ours) || JitFlow.abortedFor(result, ours)
        assertFalse(phaseProven)
        assertEquals(JitFlow.BootNetAction.NONE_COLD, JitFlow.bootNetAction(true, phaseProven))
        // И «чужая загрузка» тоже не даёт права: intent_verified=false.
        assertEquals(JitFlow.BootNetAction.NONE_COLD, JitFlow.bootNetAction(false, true))
    }

    // 4б. Отмена рестарта тоже считается продолжением: сеть могла остаться изменённой.
    @Test
    fun abortedOperationStillContinuesForCompensation() {
        val op = JitFlow.opId(3_000, "boot-A", "eee555")
        val result = "${JitNetModel.WATCHER_ARMED} op=$op want=true|" +
            "${JitNetModel.WATCHER_ABORTED} op=$op reason=cancel_file|"
        val phaseProven = JitFlow.armedFor(result, op) || JitFlow.abortedFor(result, op)
        assertEquals(JitFlow.BootNetAction.CONTINUE_OPERATION, JitFlow.bootNetAction(true, phaseProven))
        assertEquals(JitFlow.CancelOutcome.CANCELLED, JitFlow.cancelOutcome(result, op))
    }

    // 5. Отказ возврата после zygote: маркер сохранён, повтор — при следующей операции JIT.
    @Test
    fun failedPostZygoteRestoreKeepsMarker() {
        val h = Harness(wifi = false, link = "down")
        marker(h, wifi = 1, ap = 0, ts = h.nowMs)
        val core = JitNetCore(
            prefs = h.prefs, readWifi = { h.wifi }, setWifi = { h.setWifiCalls++ },
            apProbe = { h.apProbe }, link = { "down" },
            runScript = { s, _ -> h.scripts.add(s); h.respond(s) },
            log = { h.logs.add(it) }, now = { h.nowMs }, sleep = { h.sleepCalls++ }
        )
        assertEquals(Recovery.KEPT, core.restorePendingInChain("post_zygote"))
        assertNotNull("маркер обязан остаться", h.prefs.get(JitNetModel.PREF_NET_SAVED))
        assertTrue(h.logText().contains("keep_saved"))

        // Следующая операция JIT (начало клика, уже с исправной сетью) доводит возврат.
        h.wifi = true
        h.link = "up"
        assertEquals(Recovery.RESTORED, h.core().restorePendingInChain("pre"))
        assertNull(h.prefs.get(JitNetModel.PREF_NET_SAVED))
    }

    // ───────────────────────── 2. сохранение состояния и ворота ─────────────────────────

    @Test
    fun unconfirmedSaveAborts() {
        val h = Harness()
        h.prefs.failWrites = true
        assertNull("без подтверждённой записи состояния операции нет", h.core().saveBeforeRestart())
        assertEquals(0, h.setWifiCalls)
        assertTrue("ни одного device-скрипта", h.scripts.isEmpty())
        assertTrue(h.logText().contains("JIT_NET_PRE failed"))
    }

    @Test
    fun gateOrderAndAborts() {
        assertEquals(JitFlow.Gate.ABORT_NO_NET_STATE, JitFlow.gate(false, false))
        assertEquals(JitFlow.Gate.ABORT_NO_NET_STATE, JitFlow.gate(false, true))
        assertEquals(JitFlow.Gate.ABORT_INTENT_WRITE, JitFlow.gate(true, false))
        assertEquals(JitFlow.Gate.PROCEED, JitFlow.gate(true, true))

        val beforeNet = JitFlow.abortPlan(JitFlow.Stage.BEFORE_NET, propsMaybeTouched = true)
        assertFalse(beforeNet.cancelWatcher)
        assertFalse(beforeNet.rollbackProps)
        assertFalse(beforeNet.compensateNet)
        assertTrue(beforeNet.discardSnapshot)

        val afterNet = JitFlow.abortPlan(JitFlow.Stage.AFTER_NET_BEFORE_LAUNCH, propsMaybeTouched = true)
        assertTrue(afterNet.cancelWatcher)
        assertTrue("props могли измениться — откат обязателен", afterNet.rollbackProps)
        assertTrue(afterNet.compensateNet)

        val unconfirmed = JitFlow.abortPlan(JitFlow.Stage.LAUNCH_UNCONFIRMED, propsMaybeTouched = false)
        assertTrue(unconfirmed.cancelWatcher)
        assertFalse(unconfirmed.rollbackProps)
        assertTrue(unconfirmed.compensateNet)
    }

    @Test
    fun noNetStateAborts() {
        val h = Harness(wifi = null)
        assertNull(h.core().saveBeforeRestart())
        assertTrue(h.logText().contains("state_unknown"))
        assertTrue(h.scripts.isEmpty())
    }

    @Test
    fun unknownStateAbortsWholeOperation() {
        val h = Harness(apProbe = "ERR_ROOT_CHANNEL_UNAVAILABLE")
        assertNull(h.core().saveBeforeRestart())
        assertTrue(h.logText().contains("state_unknown"))
    }

    @Test
    fun absentAp0IsOffNotUnknown() {
        val h = Harness(apProbe = "P=0\nOS=\nA=0")
        val st = h.core().saveBeforeRestart()
        assertNotNull(st)
        assertEquals(false, st!!.ap)
        assertEquals(true, st.wifi)
    }

    @Test
    fun partialApProbeIsUnknown() {
        assertNull("P=1 без A=", JitNetModel.parseApProbe("P=1\nOS=up\n"))
        assertNull("нет поля OS=", JitNetModel.parseApProbe("P=0\nA=0"))
        assertNull("нет поля A=", JitNetModel.parseApProbe("P=0\nOS=\n"))
        assertEquals(false, JitNetModel.parseApProbe("P=0\nOS=\nA=0"))
        assertEquals(false, JitNetModel.parseApProbe("P=1\nOS=down\nA=0"))
        assertEquals(true, JitNetModel.parseApProbe("P=1\nOS=up\nA=1"))

        val h = Harness(apProbe = "P=1\nOS=up\n")
        assertNull("неполная проба = неизвестно", h.core().saveBeforeRestart())
        assertTrue(h.scripts.isEmpty())
    }

    // ───────────────────── 3. маркер: TTL, только чтение, цепочка ─────────────────────

    @Test
    fun plainChainTouchesNothing() {
        val h = Harness()
        assertEquals(Recovery.NONE, h.core().restorePendingInChain("pre"))
        assertEquals(0, h.setWifiCalls)
        assertTrue(h.scripts.isEmpty())
        assertTrue(h.logText().contains("reason=no_saved_state"))
    }

    @Test
    fun successfulRestoreClearsMarker() {
        val h = Harness()
        val core = h.core()
        core.saveBeforeRestart()!!
        h.wifi = false
        h.link = "up"
        assertEquals(Recovery.RESTORED, core.restorePendingInChain("pre"))
        assertEquals(true, h.requestedWifi)
        assertNull(h.prefs.get(JitNetModel.PREF_NET_SAVED))
    }

    @Test
    fun expiredMarkerDoesNoNetworkActions() {
        val h = Harness(wifi = false)
        marker(h, wifi = 1, ap = 1, ts = h.nowMs - JitNetModel.SAVED_TTL_MS - 1_000)
        assertEquals(Recovery.EXPIRED, h.core().restorePendingInChain("post_zygote"))
        assertEquals("Wi-Fi НЕ включаем", 0, h.setWifiCalls)
        assertTrue("ни одного скрипта", h.scripts.isEmpty())
        assertNull("маркер выброшен", h.prefs.get(JitNetModel.PREF_NET_SAVED))
        assertTrue(h.logText().contains("без сетевых действий"))
    }

    @Test
    fun clockWentBackwardsIsNotExpiry() {
        val future = JitNetModel.State(wifi = true, ap = false, tsMs = 5_000_000)
        assertEquals(JitNetModel.TtlPolicy.FULL, JitNetModel.ttlPolicy(future, 1_000_000))
        val noTs = JitNetModel.State(wifi = true, ap = false, tsMs = 0)
        assertEquals(JitNetModel.TtlPolicy.FULL, JitNetModel.ttlPolicy(noTs, 9_999_999))
        val old = JitNetModel.State(wifi = true, ap = false, tsMs = 1_000)
        assertEquals(JitNetModel.TtlPolicy.EXPIRED, JitNetModel.ttlPolicy(old, 1_000_000))

        val h = Harness(wifi = false)
        marker(h, wifi = 1, ap = 0, ts = h.nowMs + 3_600_000)
        assertEquals(Recovery.RESTORED, h.core().restorePendingInChain("post_zygote"))
        assertEquals(true, h.wifi)
    }

    @Test
    fun pendingMarkerIsReadOnly() {
        val h = Harness(wifi = false)
        marker(h, wifi = 1, ap = 0, ts = h.nowMs)
        val pending = h.core().pendingMarker()
        assertNotNull(pending)
        assertEquals(true, pending!!.first.wifi)
        assertEquals(JitNetModel.TtlPolicy.FULL, pending.second)
        assertEquals(0, h.setWifiCalls)
        assertTrue("чтение маркера не трогает сеть", h.scripts.isEmpty())
        assertNotNull("и не снимает маркер", h.prefs.get(JitNetModel.PREF_NET_SAVED))
    }

    // ───────────── 4. что считается подтверждённым возвратом (в т.ч. wifi=0) ─────────────

    @Test
    fun restoreResultSeparatesEssentialsFromInternet() {
        val wifiOn = RestoreResult(true, true, false, true, "up", true, 0)
        assertTrue(wifiOn.essentialsConfirmed)
        assertTrue(wifiOn.fullyRestored)
        assertTrue("маркер снимается при полном подтверждении", wifiOn.canClearMarker)

        assertFalse("link=down — не доказательство", wifiOn.copy(link = "down").canClearMarker)
        assertFalse("правила не удалились — маркер остаётся",
            wifiOn.copy(sanitizeLeft = 2).canClearMarker)
        assertFalse(wifiOn.copy(sanitizeLine = false).canClearMarker)
        assertFalse(wifiOn.copy(wifiConfirmed = false).canClearMarker)

        // REVIEW iter33p-fix3 🟡: сохранённый `wifi=0` — отсутствие IPv4 не ошибка и не повод
        // включать Wi-Fi: сеть вернулась ровно в то состояние, что было.
        val wifiOff = RestoreResult(false, true, false, true, "down", false, -1)
        assertTrue("для wifi=0 канал не требуется", wifiOff.fullyRestored)
        assertTrue(wifiOff.canClearMarker)

        // AP-only: сохранён ap=1, wifi=0 — AP подтверждён, канал wlan0 не требуется.
        val apOnly = RestoreResult(false, true, true, true, "down", false, -1)
        assertTrue(apOnly.canClearMarker)

        // wifi=1, но AP подтвердить не удалось — не снимаем.
        assertFalse(RestoreResult(true, true, true, false, "up", true, 0).canClearMarker)
    }

    @Test
    fun markerKeptWhenLinkNotUp() {
        val h = Harness()
        h.core().saveBeforeRestart()
        h.wifi = false
        h.link = "down"
        assertEquals(Recovery.KEPT, h.core().restorePendingInChain("pre"))
        assertNotNull(h.prefs.get(JitNetModel.PREF_NET_SAVED))
    }

    // ───────────────────── 5. идентичность операции и отмена ─────────────────────

    @Test
    fun opIdIsShellSafeAndUnique() {
        val a = JitFlow.opId(1_000_000, "boot tag/../x", "abc123")
        val b = JitFlow.opId(1_000_000, "boot tag/../x", "def456")
        assertTrue("только [A-Za-z0-9-]: $a", a.matches(Regex("[A-Za-z0-9-]+")))
        assertTrue("id операций различаются", a != b)
        assertTrue(a.startsWith("op-"))
    }

    @Test
    fun staleResultDoesNotConfirmNewLaunch() {
        val oldOp = JitFlow.opId(1_000, "b1", "aaa111")
        val newOp = JitFlow.opId(2_000, "b1", "bbb222")
        val stale = "${JitNetModel.WATCHER_ARMED} op=$oldOp want=true|" +
            "${JitNetModel.WATCHER_DONE} op=$oldOp runtime=true zygote_restart=ok|"
        assertFalse("чужой ARMED не подтверждает наш запуск", JitFlow.armedFor(stale, newOp))
        assertFalse(JitFlow.doneFor(stale, newOp))
        assertTrue(JitFlow.armedFor(stale, oldOp))

        val fresh = stale + "${JitNetModel.WATCHER_ARMED} op=$newOp want=true|"
        assertTrue(JitFlow.armedFor(fresh, newOp))
        assertFalse("до DONE нашей операции итог не подтверждён", JitFlow.doneFor(fresh, newOp))
    }

    @Test
    fun cancelOutcomeDistinguishesStates() {
        val op = JitFlow.opId(5_000, "b2", "ccc333")
        val other = JitFlow.opId(6_000, "b2", "ddd444")
        assertEquals(JitFlow.CancelOutcome.CANCELLED,
            JitFlow.cancelOutcome("${JitNetModel.WATCHER_ABORTED} op=$op reason=cancel_file|", op))
        // Строка RESTARTING — доказательство, что отмена не успела (гонка закрыта по факту).
        assertEquals(JitFlow.CancelOutcome.RESTARTED,
            JitFlow.cancelOutcome("${JitNetModel.WATCHER_RESTARTING} op=$op|", op))
        assertEquals(JitFlow.CancelOutcome.RESTARTED,
            JitFlow.cancelOutcome("${JitNetModel.WATCHER_DONE} op=$op zygote_restart=ok|", op))
        assertEquals("чужой id не считается", JitFlow.CancelOutcome.UNKNOWN,
            JitFlow.cancelOutcome("${JitNetModel.WATCHER_DONE} op=$other zygote_restart=ok|", op))
        assertEquals(JitFlow.CancelOutcome.UNKNOWN, JitFlow.cancelOutcome("", op))
        assertEquals(JitFlow.CancelOutcome.UNKNOWN, JitFlow.cancelOutcome(null, op))
    }

    @Test
    fun restartRiskOnlyClearedByCancelled() {
        assertFalse(JitFlow.restartStillPossible(JitFlow.CancelOutcome.CANCELLED))
        assertTrue(JitFlow.restartStillPossible(JitFlow.CancelOutcome.UNKNOWN))
        assertTrue(JitFlow.restartStillPossible(JitFlow.CancelOutcome.RESTARTED))
    }

    @Test
    fun resultFileMustBeAbsentBeforeLaunch() {
        assertTrue(JitFlow.resultFileReady(false))
        assertFalse("файл ещё есть", JitFlow.resultFileReady(true))
        assertFalse("неизвестно — не начинаем", JitFlow.resultFileReady(null))
    }

    // ───────────────────── 6. откат props и санация правил ─────────────────────

    @Test
    fun propsRollbackIsVerified() {
        val h = Harness()
        val core = h.core()
        assertTrue("read-back совпал", core.rollbackProps("false", "false"))
        assertTrue(h.logText().contains("rt=false") && h.logText().contains("pr=false"))

        h.respond = { ScriptOut(0, "JIT_NET_PROPS_ROLLBACK rt=true pr=false want_rt=false want_pr=false") }
        assertFalse("не совпало — это не успех", core.rollbackProps("false", "false"))

        h.respond = { ScriptOut(0, "JIT_NET_PROPS_ROLLBACK rt=false pr=true want_rt=false want_pr=true") }
        assertFalse("want_pr=true не равно pr=false", core.rollbackProps("false", "false"))

        h.respond = { ScriptOut(0, "ERR_ROOT_CHANNEL_UNAVAILABLE") }
        assertFalse("канала нет — тоже не успех", core.rollbackProps("false", "false"))
    }

    @Test
    fun sanitizeKeepsForeignRules() {
        val plan = JitNet.sanitizePlan(
            "11000:\tfrom all oif wlan0 lookup 1022\n" +
                "11001:\tfrom all oif wlan0.1 lookup 1022\n" +
                "11002:\tfrom all oif wlan0 oif wlan1 lookup 5\n" +
                "11003:\tlookup 1000\n"
        )
        val actions = plan.map { it.action }
        assertEquals(JitNet.RuleAction.DELETE, actions[0])
        assertEquals(JitNet.RuleAction.SKIP_NOT_OIF_WLAN0, actions[1])
        assertEquals(JitNet.RuleAction.SKIP_MULTIPLE_OIF, actions[2])
        assertEquals(JitNet.RuleAction.SKIP_NOT_OIF_WLAN0, actions[3])
        assertEquals(listOf("ip rule del from all oif wlan0 lookup 1022"),
            plan.mapNotNull { it.command })
    }
}
