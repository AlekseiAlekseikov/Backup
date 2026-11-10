package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33p-fix2 §1/§2 — ПОВЕДЕНЧЕСКИЕ тесты сети (REVIEW iter33p-fix1, замечание 1).
 *
 * Здесь нет ни `contains`, ни `indexOf` по исходникам: тесты гоняют реальные переходы
 * [JitNetCore] и [JitFlow] на подставленных отказах (фейковый Wi-Fi, фейковые скрипты, фейковые
 * префы). Сценарии из ревью:
 *  1. ошибка сохранения состояния → операции нет вовсе (сеть не тронута);
 *  2. ошибка записи намерения → операции нет, маркер снят;
 *  3. отказ подготовки → компенсация в той же операции + откат props;
 *  4. отказ запуска watcher-а → сначала гасим watcher, потом компенсируем;
 *  5. неудачный возврат → маркер сохранён и следующий старт (новый экземпляр ядра на тех же
 *     префах = имитация гибели процесса) реально повторяет возврат;
 *  6. обычный старт без маркера → ни одного сетевого действия;
 *  7. отсутствие `ap0` — штатное «AP выключен», а не «неизвестно».
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

        /** Ответ устройства на скрипт: по умолчанию всё «получилось». */
        var respond: (String) -> ScriptOut = { script ->
            when {
                script.contains("JIT_NET_SANITIZE removed=") -> ScriptOut(0, "JIT_NET_SANITIZE removed=1 skipped=0 left=0 scope=oif_wlan0")
                script.contains("ip_rule_wait=") -> ScriptOut(0, "JIT_NET_PRE ip_rule_wait=1 drained=yes oif_wlan0=0")
                script.contains("JIT_NET_PROPS_ROLLBACK") -> ScriptOut(0, "JIT_NET_PROPS_ROLLBACK rt=false pr=false want_rt=false want_pr=false")
                else -> ScriptOut(0, "OK")
            }
        }

        fun core() = JitNetCore(
            prefs = prefs,
            readWifi = { wifi },
            setWifi = { on ->
                setWifiCalls++
                requestedWifi = on
                if (wifi != null) wifi = on
            },
            apProbe = { apProbe },
            link = { link },
            runScript = { script, _ -> scripts.add(script); respond(script) },
            log = { logs.add(it) },
            now = { nowMs },
            sleep = { sleepCalls++ }
        ).also { c -> c.startSoftAp = { startApErr?.let { throw it } } }

        fun logText(): String = logs.joinToString("\n")
    }

    // 1. Ошибка записи состояния — операции нет, сети не касаемся.
    @Test
    fun unknownStateAbortsWholeOperation() {
        val h = Harness(apProbe = "ERR_ROOT_CHANNEL_UNAVAILABLE")
        assertNull("состояние не должно считаться известным", h.core().saveBeforeRestart())
        assertEquals("Wi-Fi не выключался", 0, h.setWifiCalls)
        assertTrue(h.logText().contains("reason=state_unknown"))
    }

    // 2. Отсутствие ap0 — штатное «AP выключен» (REVIEW §3), JIT не блокируется.
    @Test
    fun absentAp0IsOffNotUnknown() {
        assertEquals(false, JitNetModel.parseApProbe("P=0\nOS=\nA=0"))
        assertEquals(false, JitNetModel.parseApProbe("P=1\nOS=down\nA=0"))
        assertEquals(true, JitNetModel.parseApProbe("P=1\nOS=up\nA=1"))
        assertNull("канал молчит", JitNetModel.parseApProbe(""))
        assertNull(JitNetModel.parseApProbe("ERR_ROOT_CHANNEL_UNAVAILABLE"))

        val h = Harness(apProbe = "P=0\nOS=\nA=0")
        val st = h.core().saveBeforeRestart()
        assertNotNull("операция обязана начинаться при отсутствующем ap0", st)
        assertEquals(false, st!!.ap)
    }

    // 3. Сохранение не подтвердилось чтением обратно — сеть не трогаем.
    @Test
    fun unconfirmedSaveAborts() {
        val h = Harness()
        h.prefs.failWrites = true
        assertNull(h.core().saveBeforeRestart())
        assertEquals(0, h.setWifiCalls)
        assertTrue(h.logText().contains("save_not_confirmed"))
        assertNull("маркер не должен остаться", h.prefs.get(JitNetModel.PREF_NET_SAVED))
    }

    // 4. Ворота операции: порядок «состояние сети → намерение» и оба отказа.
    @Test
    fun gateOrderAndAborts() {
        assertEquals(JitFlow.Gate.ABORT_NO_NET_STATE, JitFlow.gate(netStateKnown = false, intentConfirmed = false))
        assertEquals(JitFlow.Gate.ABORT_NO_NET_STATE, JitFlow.gate(netStateKnown = false, intentConfirmed = true))
        assertEquals(JitFlow.Gate.ABORT_INTENT_WRITE, JitFlow.gate(netStateKnown = true, intentConfirmed = false))
        assertEquals(JitFlow.Gate.PROCEED, JitFlow.gate(netStateKnown = true, intentConfirmed = true))
    }

    // 5. Успешный возврат: Wi-Fi включён по запросу, маркер снят, link подтверждён.
    @Test
    fun successfulRestoreClearsMarker() {
        val h = Harness()
        val core = h.core()
        val saved = core.saveBeforeRestart()!!
        // имитируем гашение Wi-Fi операцией
        core.runPreRestart("true")
        h.wifi = false
        h.link = "down"
        h.respond = { s ->
            when {
                s.contains("JIT_NET_SANITIZE removed=") -> ScriptOut(0, "JIT_NET_SANITIZE removed=1 skipped=0 left=0 scope=oif_wlan0")
                s.contains("ip_rule_wait=") -> ScriptOut(0, "JIT_NET_PRE ip_rule_wait=0 drained=yes oif_wlan0=0")
                else -> ScriptOut(0, "OK")
            }
        }
        h.link = "up"
        val rec = core.recoverOnStart("boot")
        assertEquals(Recovery.RESTORED, rec)
        assertEquals(true, h.requestedWifi)
        assertNull("маркер снят", h.prefs.get(JitNetModel.PREF_NET_SAVED))
        assertTrue(h.logText().contains("JIT_NET_RESTORE wifi=1 attempt=1/3 request=setWifiEnabled(true) state=true"));
    }

    // 6. Wi-Fi не поднялся → маркер сохранён, и СЛЕДУЮЩИЙ СТАРТ реально повторяет возврат.
    @Test
    fun failedRestoreKeepsMarkerAndNextStartRetries() {
        val h = Harness()
        val core = h.core()
        core.saveBeforeRestart()
        h.wifi = false
        h.respond = { s ->
            if (s.contains("JIT_NET_SANITIZE removed=")) ScriptOut(0, "JIT_NET_SANITIZE removed=0 skipped=0 left=2 scope=oif_wlan0")
            else ScriptOut(0, "OK")
        }
        // setWifiEnabled «не срабатывает»: запрос уходит, состояние остаётся false
        val stuck = JitNetCore(
            prefs = h.prefs,
            readWifi = { h.wifi },
            setWifi = { h.setWifiCalls++; h.requestedWifi = it },
            apProbe = { h.apProbe },
            link = { "down" },
            runScript = { s, _ -> h.scripts.add(s); h.respond(s) },
            log = { h.logs.add(it) },
            now = { h.nowMs },
            sleep = { h.sleepCalls++ }
        )
        assertEquals(Recovery.KEPT, stuck.recoverOnStart("boot"))
        assertNotNull("маркер обязан остаться", h.prefs.get(JitNetModel.PREF_NET_SAVED))
        assertEquals("все 3 попытки", 3, h.setWifiCalls)
        assertTrue(h.logText().contains("keep_saved"))

        // ── следующий старт: тот же диск/префы, новый процесс ──
        h.wifi = true
        h.logs.clear()
        val next = JitNetCore(
            prefs = h.prefs,
            readWifi = { h.wifi },
            setWifi = { on -> h.setWifiCalls++; h.requestedWifi = on },
            apProbe = { h.apProbe },
            link = { "up" },
            runScript = { s, _ -> h.scripts.add(s); h.respond(s) },
            log = { h.logs.add(it) },
            now = { h.nowMs },
            sleep = { h.sleepCalls++ }
        )
        assertEquals("повтор на следующем старте обязан случиться", Recovery.RESTORED, next.recoverOnStart("boot"))
        assertNull(h.prefs.get(JitNetModel.PREF_NET_SAVED))
    }

    // 7. Обычный старт без маркера — ни одного сетевого действия.
    @Test
    fun plainStartTouchesNothing() {
        val h = Harness()
        assertEquals(Recovery.NONE, h.core().recoverOnStart("boot"))
        assertEquals(0, h.setWifiCalls)
        assertTrue("ни одного скрипта на устройстве", h.scripts.isEmpty())
        assertTrue(h.logText().contains("reason=no_saved_state"))
    }

    // 8. Просроченный маркер без предмета возврата (`wifi=0`) выбрасывается, сеть не трогаем.
    //    Случай `wifi=1` намеренно решён иначе — см. тест 15: выключенный Wi-Fi это потеря связи.
    @Test
    fun expiredMarkerIsDroppedWhenNothingToRestore() {
        val h = Harness()
        h.prefs.map[JitNetModel.PREF_NET_SAVED] = "wifi=0,ap=0,ts=1000"
        assertEquals(Recovery.EXPIRED, h.core().recoverOnStart("boot"))
        assertEquals(0, h.setWifiCalls)
        assertNull(h.prefs.get(JitNetModel.PREF_NET_SAVED))
    }

    // 9. Отказ ПОСЛЕ подготовки: план отмены — гасить watcher, откатывать props, компенсировать.
    @Test
    fun abortPlanAfterNetCoversEverything() {
        val beforeNet = JitFlow.abortPlan(JitFlow.Stage.BEFORE_NET, propsTouched = false)
        assertFalse("до сети возвращать нечего", beforeNet.compensateNet)
        assertFalse(beforeNet.killWatcher)
        assertTrue("снимок отменённой операции не нужен", beforeNet.discardSnapshot)

        val afterNet = JitFlow.abortPlan(JitFlow.Stage.AFTER_NET_BEFORE_LAUNCH, propsTouched = true)
        assertTrue(afterNet.compensateNet)
        assertTrue(afterNet.rollbackProps)
        assertTrue(afterNet.killWatcher)

        val launch = JitFlow.abortPlan(JitFlow.Stage.LAUNCH_UNCONFIRMED, propsTouched = true)
        assertTrue("неподтверждённый запуск обязан гаситься", launch.killWatcher)
        assertTrue(launch.compensateNet)
    }

    // 10. Подтверждение запуска — по первой строке watcher-а, а не по pgrep.
    @Test
    fun launchConfirmationUsesWatcherMarker() {
        assertFalse(JitFlow.launchConfirmed(null, "JIT_ZYGOTE_ARMED"))
        assertFalse(JitFlow.launchConfirmed("LAUNCHED=1234", "JIT_ZYGOTE_ARMED"))
        assertTrue(JitFlow.launchConfirmed(
            "JIT_ZYGOTE_WATCH start JIT_ZYGOTE_ARMED pid=777 want=true|", "JIT_ZYGOTE_ARMED"))
    }

    // 11. Рестарт «возможен», пока не подтверждено, что watcher снят.
    @Test
    fun restartRiskIsOnlyClearedByConfirmedKill() {
        assertTrue(JitFlow.restartStillPossible("123", true))
        assertTrue(JitFlow.restartStillPossible("", true))
        assertTrue(JitFlow.restartStillPossible("unknown", null))
        assertFalse(JitFlow.restartStillPossible("", false))
    }

    // 12. Компенсация: подтверждение Wi-Fi/AP ≠ «интернет есть» (REVIEW 🟡).
    @Test
    fun restoreResultSeparatesEssentialsFromInternet() {
        val ok = RestoreResult(true, true, false, true, "up", true, 0)
        assertTrue(ok.essentialsConfirmed)
        assertTrue(ok.fullyRestored)
        assertTrue(ok.canClearMarker)

        val noLink = RestoreResult(true, true, false, true, "down", true, 0)
        assertTrue("запрос подтверждён", noLink.essentialsConfirmed)
        assertFalse("но интернета нет — полным восстановлением не называем", noLink.fullyRestored)
        assertTrue(noLink.canClearMarker)

        val unknownLink = RestoreResult(true, true, false, true, "unknown", true, 0)
        assertFalse("при неизвестном канале маркер не снимаем — нужен повтор", unknownLink.canClearMarker)

        val dirtyRules = RestoreResult(true, true, false, true, "up", true, 2)
        assertFalse("правила не слиты — не «полностью»", dirtyRules.fullyRestored)
    }

    // 13. Откат props возвращает прежние значения и подтверждается read-back.
    @Test
    fun propsRollbackIsVerified() {
        val h = Harness()
        val ok = h.core().rollbackProps("false", "")
        assertTrue("откат подтверждён read-back", ok)
        val script = h.scripts.last()
        assertTrue(script.contains("setprop dalvik.vm.usejit \"false\""))
        assertTrue(script.contains("setprop dalvik.vm.usejitprofiles \"\""))
        assertTrue(script.contains("JIT_NET_PROPS_ROLLBACK"))
        // только те же два ключа, что ставила подготовка
        assertFalse(script.contains("persist."))
    }

    // 14. Санация: чужие правила не удаляются, при неоднозначности — отказ.
    @Test
    fun sanitizeKeepsForeignRules() {
        val plan = JitNet.sanitizePlan(
            """
            0:	from all lookup local
            11000:	from all oif wlan0 lookup 1022
            11000:	from all fwmark 0x1 lookup 1033
            12000:	from all oif wlan0.1 lookup 1044
            """.trimIndent()
        )
        assertEquals(JitNet.RuleAction.SKIP_NOT_OIF_WLAN0, plan[0].action)
        assertEquals(JitNet.RuleAction.SKIP_DUPLICATE_PRIORITY, plan[1].action)
        assertEquals(JitNet.RuleAction.SKIP_NOT_OIF_WLAN0, plan[2].action)
        assertEquals(JitNet.RuleAction.SKIP_NOT_OIF_WLAN0, plan[3].action)
        assertTrue("ни одного удаления", plan.none { it.action == JitNet.RuleAction.DELETE })

        val only = JitNet.sanitizePlan("11000:\tfrom all oif wlan0 lookup 1022").single()
        assertEquals(JitNet.RuleAction.DELETE, only.action)
        assertEquals("ip rule del from all oif wlan0 lookup 1022", only.command)
    }

    // ─────────────────── TTL маркера незавершённого возврата (🟡 ревью) ───────────────────
    // Политика: просрочка НЕ выбрасывает данные, если сохранено `wifi=1` — выключенный Wi-Fi это
    // гарантированная потеря связи; не поднимается только точка доступа (видимый hotspot).

    private fun savedMarker(h: Harness, wifi: Int, ap: Int, ageMs: Long) {
        h.prefs.map[JitNetModel.PREF_NET_SAVED] = "wifi=$wifi,ap=$ap,ts=${h.nowMs - ageMs}"
    }

    // 15. Просроченный маркер с `wifi=1`: Wi-Fi включаем, AP не поднимаем, маркер снимаем.
    @Test
    fun expiredMarkerRestoresWifiButNotAp() {
        val h = Harness(wifi = false, apProbe = "P=0\nOS=\nA=0")
        h.startApErr = null
        savedMarker(h, wifi = 1, ap = 1, ageMs = JitNetModel.SAVED_TTL_MS + 1_000)
        val rec = h.core().recoverOnStart("boot")
        assertEquals(Recovery.RESTORED, rec)
        assertEquals("Wi-Fi обязан включиться даже у просроченного маркера", true, h.wifi)
        assertTrue("AP не поднимаем", h.logText().contains("ap=1 state=skipped reason=ttl_expired"))
        assertFalse("запроса startSoftAp быть не должно", h.logText().contains("ap=1 request="))
        assertNull("маркер снят по подтверждению", h.prefs.map[JitNetModel.PREF_NET_SAVED])
    }

    // 16. Просроченный маркер с `wifi=0`: возвращать нечего — данных не держим, сеть не трогаем.
    @Test
    fun expiredMarkerWithWifiOffIsDroppedWithoutNetActions() {
        val h = Harness(wifi = false)
        savedMarker(h, wifi = 0, ap = 0, ageMs = JitNetModel.SAVED_TTL_MS + 1_000)
        val rec = h.core().recoverOnStart("boot")
        assertEquals(Recovery.EXPIRED, rec)
        assertEquals("сеть не трогали", 0, h.setWifiCalls)
        assertNull(h.prefs.map[JitNetModel.PREF_NET_SAVED])
    }

    // 17. Часы ушли назад (типично для ГУ до синхронизации времени) — это НЕ просрочка.
    @Test
    fun clockWentBackwardsIsNotExpiry() {
        val h = Harness(wifi = false, apProbe = "P=0\nOS=\nA=0")
        // ts в будущем на час: теперь `now - ts` отрицательно.
        h.prefs.map[JitNetModel.PREF_NET_SAVED] = "wifi=1,ap=0,ts=${h.nowMs + 3_600_000}"
        assertEquals(
            "часы назад не дают права забыть незавершённый возврат",
            JitNetModel.TtlPolicy.FULL,
            JitNetModel.ttlPolicy(JitNetModel.parse(h.prefs.map[JitNetModel.PREF_NET_SAVED])!!, h.nowMs)
        )
        val rec = h.core().recoverOnStart("boot")
        assertEquals(Recovery.RESTORED, rec)
        assertEquals(true, h.wifi)
    }

    // 18. Свежий маркер — обычная политика FULL.
    @Test
    fun freshMarkerIsFullPolicy() {
        val h = Harness(wifi = false, apProbe = "P=0\nOS=\nA=0")
        savedMarker(h, wifi = 1, ap = 0, ageMs = 1_000)
        val st = JitNetModel.parse(h.prefs.map[JitNetModel.PREF_NET_SAVED])!!
        assertEquals(JitNetModel.TtlPolicy.FULL, JitNetModel.ttlPolicy(st, h.nowMs))
        assertEquals(JitNetModel.TtlPolicy.WIFI_ONLY,
            JitNetModel.ttlPolicy(st, h.nowMs + JitNetModel.SAVED_TTL_MS + 1))
    }
}
