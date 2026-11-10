package com.example.voiceapp3.tools

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager

/**
 * iter33p-fix3/4 — сеть вокруг перезапуска фреймворка (Android-адаптер).
 *
 * Вся логика и переходы живут в [JitNetCore] (без `android.*`, покрыто поведенческими тестами
 * `JitNetCoreTest`). Здесь только связка с устройством: `WifiManager`, `AdbClient`, `AppLog`.
 *
 * Границы (REVIEW iter33p-fix2 §3 + уточнение fix3):
 *  • сетевые действия выполняются ТОЛЬКО как продолжение операции JIT — до рестарта
 *    (компенсация) или при старте процесса, когда фаза операции доказана
 *    ([restorePendingInChain] ← [JitFlow.bootNetAction]);
 *  • обычный холодный старт и обычная перезагрузка ГУ: сеть не трогается вообще, маркер лишь
 *    читается ([pendingMarker], лог `JIT_NET_PENDING … action=none_cold_start`);
 *  • просроченный маркер не выполняет сетевых действий (выбрасывается с `reason=expired`);
 *  • отсутствие `ap0` — штатный признак выключенного AP; неполный вывод пробы — «неизвестно»;
 *  • подтверждение Wi-Fi/AP не выдаётся за «интернет восстановлен»: `link=` и `sanitize_left=`
 *    отдельные поля, маркер снимается только при подтверждении запрошенного.
 */
object JitNet {

    private const val TAG = "CENTRAL_JitNet"

    internal const val PREF_NET_SAVED = JitNetModel.PREF_NET_SAVED
    internal const val PREF_NET_LAST = JitNetModel.PREF_NET_LAST
    internal const val DRAIN_TIMEOUT_S = JitNetModel.DRAIN_TIMEOUT_S
    internal const val WIFI_RESTORE_ATTEMPTS = JitNetModel.WIFI_RESTORE_ATTEMPTS
    internal const val WIFI_RESTORE_PAUSE_MS = JitNetModel.WIFI_RESTORE_PAUSE_MS
    internal const val COMPENSATE_ATTEMPTS = JitNetModel.COMPENSATE_ATTEMPTS
    internal const val SAVED_TTL_MS = JitNetModel.SAVED_TTL_MS

    internal const val PROP_USEJIT = JitNetModel.PROP_USEJIT
    internal const val PROP_USEJIT_PROFILES = JitNetModel.PROP_USEJIT_PROFILES
    internal const val PROP_PERSIST_USEJIT_INIT = JitNetModel.PROP_PERSIST_USEJIT_INIT

    fun parse(saved: String?): JitNetModel.State? = JitNetModel.parse(saved)

    fun isExpired(state: JitNetModel.State, nowMs: Long, ttlMs: Long = SAVED_TTL_MS): Boolean =
        JitNetModel.isExpired(state, nowMs, ttlMs)

    fun parseApProbe(out: String?): Boolean? = JitNetModel.parseApProbe(out)

    fun apProbeCommand(): String = JitNetModel.apProbeCommand()

    internal fun preRestartScript(usejitValue: String): String = JitNetModel.preRestartScript(usejitValue)

    internal fun propsRollbackScript(usejitBefore: String, profilesBefore: String): String =
        JitNetModel.propsRollbackScript(usejitBefore, profilesBefore)

    internal fun sanitizeScript(): String = JitNetModel.sanitizeScript()

    // ─────────────────────────────── связка с устройством ───────────────────────────────

    private fun prefs(app: Context) = object : NetPrefs {
        override fun get(key: String): String? = Prefs2.getString(app, key)
        override fun put(key: String, value: String?): Boolean {
            Prefs2.putString(app, key, value)
            val back = Prefs2.getString(app, key)
            return if (value == null) back == null else back == value
        }
    }

    private fun wifiManager(app: Context): WifiManager? = runCatching {
        app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }.getOrNull()

    /** Сеть только для чтения — состояние (для логов и решения). */
    fun readState(context: Context, nowMs: Long = System.currentTimeMillis()): JitNetModel.State? =
        core(context, nowMs).readState()

    fun apState(): Boolean? = JitNetModel.parseApProbe(probeAp())

    private fun probeAp(): String? = runCatching {
        AdbClient.execute(JitNetModel.apProbeCommand(), 6_000, expectRoot = false).second
    }.getOrNull()

    internal fun linkState(iface: String): String {
        val (code, out) = runCatching {
            AdbClient.execute("ip -o addr show $iface 2>/dev/null", 6_000, expectRoot = false)
        }.getOrDefault(Pair(1, ""))
        if (out.contains("ERR_") || (code != 0 && out.isBlank())) return "unknown"
        return if (out.lineSequence().any { it.contains("inet ") }) "up" else "down"
    }

    private fun core(context: Context, nowMs: Long = System.currentTimeMillis()): JitNetCore {
        val app = context.applicationContext
        return JitNetCore(
            prefs = prefs(app),
            readWifi = { runCatching { wifiManager(app)?.isWifiEnabled }.getOrNull() },
            setWifi = { on -> runCatching { wifiManager(app)?.setWifiEnabled(on) } },
            apProbe = { probeAp() },
            link = { iface -> linkState(iface) },
            runScript = { script, timeout ->
                val (code, out) = runCatching { AdbClient.executeScript("jit_net", script, timeout) }
                    .getOrDefault(Pair(1, ""))
                ScriptOut(code, out)
            },
            log = { line -> AppLog.i(TAG, line) },
            now = { nowMs },
            sleep = { ms -> runCatching { Thread.sleep(ms) } },
            ttlMs = SAVED_TTL_MS
        ).also { c ->
            c.startSoftAp = {
                val wm = wifiManager(app) ?: throw IllegalStateException("no_wifi_service")
                wm.javaClass.getMethod("startSoftAp", WifiConfiguration::class.java).invoke(wm, null)
            }
        }
    }

    fun savedState(context: Context): JitNetModel.State? = parse(Prefs2.getString(context.applicationContext, PREF_NET_SAVED))

    fun clearSaved(context: Context) = prefs(context.applicationContext).let {
        it.put(PREF_NET_SAVED, null)
        it.put(PREF_NET_LAST, null)
    }

    /** Отмена операции ДО сетевых изменений — маркер снимается (возвращать нечего). */
    fun abandonSaved(context: Context, reason: String) {
        core(context).abandon(reason)
    }

    fun saveBeforeRestart(context: Context, nowMs: Long = System.currentTimeMillis()): JitNetModel.State? {
        BgWorker.assertNotMain("JitNet.saveBeforeRestart")
        return core(context, nowMs).saveBeforeRestart()
    }

    data class PreResult(
        val exitCode: Int,
        val output: String,
        val drained: Boolean
    ) {
        val channelOk: Boolean get() = output.isNotBlank() && !output.contains("ERR_")
    }

    fun runPreRestart(context: Context, usejitValue: String): PreResult {
        BgWorker.assertNotMain("JitNet.runPreRestart")
        val out = core(context).runPreRestart(usejitValue)
        return PreResult(out.exitCode, out.output, out.output.contains("drained=yes"))
    }

    /** Прежние runtime-props после отмены (те же два ключа, что ставила подготовка). */
    fun rollbackProps(context: Context, usejitBefore: String, profilesBefore: String): Boolean =
        core(context).rollbackProps(usejitBefore, profilesBefore)

    /**
     * Возврат сохранённой сети как ПРОДОЛЖЕНИЕ операции JIT (не самостоятельная boot-функция).
     * Вызывается только из цепочки тумблера: начало клика и старт процесса при доказанной фазе.
     */
    fun restorePendingInChain(context: Context, reason: String): Recovery {
        BgWorker.assertNotMain("JitNet.restorePendingInChain")
        return core(context).restorePendingInChain(reason)
    }

    /** Только чтение маркера (для честной строки в логе). Сеть не трогается. */
    fun pendingMarker(context: Context): Pair<JitNetModel.State, JitNetModel.TtlPolicy>? =
        core(context).pendingMarker()

    fun restoreAfterRestart(context: Context, saved: JitNetModel.State, attempts: Int = WIFI_RESTORE_ATTEMPTS): RestoreResult {
        BgWorker.assertNotMain("JitNet.restoreAfterRestart")
        val res = core(context).restore(saved, "post_restart", attempts)
        if (res.canClearMarker) clearSaved(context)
        return res
    }

    /**
     * Компенсация в той же операции (watcher не записан/не запустился): возвращаем сеть,
     * zygote не перезапускаем.
     */
    fun compensate(context: Context, saved: JitNetModel.State, why: String): RestoreResult {
        AppLog.i(TAG, "JIT_NET_COMPENSATE start reason=$why saved=${saved.log()} (zygote НЕ перезапускается)")
        val res = core(context).restore(saved, "compensate:$why", COMPENSATE_ATTEMPTS)
        if (res.canClearMarker) clearSaved(context)
        AppLog.i(TAG, "JIT_NET_COMPENSATE done reason=$why ${res.log()} marker=${if (res.canClearMarker) "cleared" else "kept"}")
        return res
    }

    // ───────────────────────── §2.3(а) санация: только точные правила ─────────────────────────

    data class RuleLine(val priority: Int, val spec: String, val raw: String)

    fun parseRules(text: String): List<RuleLine> = text.lineSequence().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@mapNotNull null
        val idx = line.indexOf(':')
        if (idx <= 0) return@mapNotNull null
        val prio = line.substring(0, idx).trim().toIntOrNull() ?: return@mapNotNull null
        RuleLine(prio, line.substring(idx + 1).trim(), line)
    }.toList()

    enum class RuleAction { DELETE, SKIP_NOT_OIF_WLAN0, SKIP_DUPLICATE_PRIORITY, SKIP_MULTIPLE_OIF }

    data class RuleDecision(val rule: RuleLine, val action: RuleAction, val command: String?)

    private fun oifTokens(spec: String): List<String> =
        Regex("(?:^|\\s)oif\\s+(\\S+)").findAll(spec).map { it.groupValues[1] }.toList()

    /**
     * План санации: удаляем правило ТОЛЬКО при однозначной идентификации — ровно один `oif`,
     * он равен `wlan0` (не `wlan0.1`), и priority не встречается у других правил.
     */
    fun sanitizePlan(text: String): List<RuleDecision> {
        val rules = parseRules(text)
        val prioCount = rules.groupingBy { it.priority }.eachCount()
        return rules.map { r ->
            val oifs = oifTokens(r.spec)
            val action = when {
                oifs.size > 1 -> RuleAction.SKIP_MULTIPLE_OIF
                oifs.isEmpty() || oifs[0] != "wlan0" -> RuleAction.SKIP_NOT_OIF_WLAN0
                (prioCount[r.priority] ?: 0) > 1 -> RuleAction.SKIP_DUPLICATE_PRIORITY
                else -> RuleAction.DELETE
            }
            RuleDecision(r, action, if (action == RuleAction.DELETE) "ip rule del ${r.spec}" else null)
        }
    }
}
