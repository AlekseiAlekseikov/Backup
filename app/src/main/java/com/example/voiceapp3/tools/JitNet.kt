package com.example.voiceapp3.tools

import android.content.Context
import android.net.wifi.WifiManager

/**
 * iter33p-fix1 §1/§2 — сеть вокруг перезапуска фреймворка, ровно как в референсе
 * (CentralEXAuto 3.3.9: `w0/la.run` до рестарта → `w0/e2.run` после подъёма).
 *
 * Что важно после REVIEW iter33p:
 *  • состояние сети сохраняется ДО любых сетевых изменений, и сохранение проверяется чтением
 *    обратно; если сохранить нельзя — вызывающий обязан ПРЕРВАТЬ операцию (никакого
 *    `svc wifi disable` без плана возврата);
 *  • любой отказ ПОСЛЕ отключения Wi-Fi компенсируется в той же операции
 *    ([compensate]) — без рестарта zygote;
 *  • возврат сети не привязан к успешной JIT-верификации: он выполняется из `jitPendingVerify`
 *    сразу после решения о намерении (в том числе при `STALE` и при недоступном канале);
 *  • сохранённое состояние НЕ удаляется, пока возврат не подтверждён чтением состояния;
 *  • санация удаляет правило только по полному набору селекторов (`ip rule del <spec>`), а при
 *    неоднозначности (дубликат priority, несколько `oif`, чужой интерфейс) — отказывается удалять;
 *  • лог различает ЗАПРОС (`request=`) и ФАКТИЧЕСКОЕ состояние (`state=`).
 */
object JitNet {

    private const val TAG = "CENTRAL_JitNet"

    /** Сохранённое состояние сети: `wifi=1,ap=0,ts=<epochMs>` (формат референса + время). */
    internal const val PREF_NET_SAVED = "jit_net_saved"

    /** Сколько ждём слива правил `oif wlan0` после `svc wifi disable` (как в `la.run`). */
    internal const val DRAIN_TIMEOUT_S = 20

    /** Возврат Wi-Fi после подъёма: до 3 попыток с паузой 3 с. */
    internal const val WIFI_RESTORE_ATTEMPTS = 3
    internal const val WIFI_RESTORE_PAUSE_MS = 3_000L

    /** Сколько попыток при компенсации внутри той же операции (канал и так под вопросом). */
    internal const val COMPENSATE_ATTEMPTS = 2

    /** Сохранённое состояние старше 10 минут не применяем (это уже не наш сценарий). */
    internal const val SAVED_TTL_MS = 10 * 60_000L

    internal const val PROP_USEJIT = "dalvik.vm.usejit"
    internal const val PROP_USEJIT_PROFILES = "dalvik.vm.usejitprofiles"
    internal const val PROP_PERSIST_USEJIT_INIT = "persist.dalvik.vm.usejit"

    data class State(val wifi: Boolean, val ap: Boolean, val tsMs: Long) {
        fun log(): String = "wifi=${if (wifi) 1 else 0} ap=${if (ap) 1 else 0}"
        /** Формат хранения (совместим с `wifi=…,ap=…` референса + метка времени). */
        fun wire(): String = "wifi=${if (wifi) 1 else 0},ap=${if (ap) 1 else 0},ts=$tsMs"
    }

    /** Разбор сохранённого состояния; null — если строки нет/она битая. */
    fun parse(saved: String?): State? {
        if (saved.isNullOrBlank()) return null
        val w = Regex("wifi=([01])").find(saved)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val a = Regex("ap=([01])").find(saved)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val ts = Regex("ts=([0-9]+)").find(saved)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return State(wifi = w == 1, ap = a == 1, tsMs = ts)
    }

    fun isExpired(state: State, nowMs: Long, ttlMs: Long = SAVED_TTL_MS): Boolean =
        state.tsMs > 0L && nowMs - state.tsMs > ttlMs

    fun savedState(context: Context): State? =
        parse(Prefs2.getString(context.applicationContext, PREF_NET_SAVED))

    fun clearSaved(context: Context) =
        Prefs2.putString(context.applicationContext, PREF_NET_SAVED, null)

    /**
     * Текущее состояние. `ap` определяется по `ap0`: если канал недоступен (ошибка/`ERR_*`),
     * состояние НЕ считается известным — возвращаем null, и вызывающий прерывает операцию.
     */
    fun readState(context: Context, nowMs: Long = System.currentTimeMillis()): State? {
        val wifi = runCatching {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
        }.getOrNull() ?: return null
        val ap = apState() ?: return null
        return State(wifi, ap, nowMs)
    }

    /** `true/false` — состояние ap0 известно; `null` — канал недоступен, судить нельзя. */
    fun apState(): Boolean? {
        val (code, out) = runCatching {
            AdbClient.execute("ip -o addr show ap0 2>/dev/null", 6_000, expectRoot = false)
        }.getOrDefault(Pair(1, ""))
        if (out.contains("ERR_") || (code != 0 && out.isBlank())) return null
        return out.lineSequence().any { it.contains("inet ") }
    }

    /**
     * §2.1 — сохранить состояние ПЕРЕД любыми сетевыми изменениями. Возвращает null, если
     * состояние не прочиталось или не подтвердилось чтением из префов: тогда операцию JIT
     * нужно прервать, ничего не выключая.
     */
    fun saveBeforeRestart(context: Context, nowMs: Long = System.currentTimeMillis()): State? {
        val app = context.applicationContext
        val st = readState(app, nowMs)
        if (st == null) {
            AppLog.i(TAG, "JIT_NET_PRE skipped reason=state_unknown (канал/сервис недоступен — сеть не трогаем)")
            return null
        }
        Prefs2.putString(app, PREF_NET_SAVED, st.wire())
        val readBack = savedState(app)
        if (readBack == null || readBack.wifi != st.wifi || readBack.ap != st.ap) {
            AppLog.i(TAG, "JIT_NET_PRE failed reason=save_not_confirmed want=${st.wire()} " +
                "got=${readBack?.wire() ?: "none"} (сеть не трогаем)")
            clearSaved(app)
            return null
        }
        AppLog.i(TAG, "JIT_NET_PRE ${st.log()} source=wifi_manager saved=${st.wire()} confirmed=yes")
        return st
    }

    // ─────────────────────────── §2.2 подготовка (до рестарта) ───────────────────────────

    /**
     * Скрипт подготовки (механизм `la.run`): погасить Wi-Fi, дождаться слива правил `oif wlan0`
     * (≤20 с) и только потом поднять props. Сам рестарт делает watcher (`AppPerformance.kt`).
     */
    internal fun preRestartScript(usejitValue: String): String {
        val D = "$"
        return """
            #!/system/bin/sh
            # iter33p-fix1 §2.2 — как `w0/la.run`: svc wifi disable → drain oif wlan0 → setprop
            svc wifi disable >/dev/null 2>&1
            i=0
            n=1
            while [ ${D}i -lt $DRAIN_TIMEOUT_S ]; do
              n=${D}(ip rule show 2>/dev/null | grep -c 'oif wlan0')
              [ "${D}n" = "0" ] && break
              sleep 1
              i=${D}((i+1))
            done
            setprop $PROP_USEJIT $usejitValue 2>/dev/null
            setprop $PROP_USEJIT_PROFILES $usejitValue 2>/dev/null
            echo "JIT_NET_PRE ip_rule_wait=${D}i drained=${D}([ "${D}n" = "0" ] && echo yes || echo no) oif_wlan0=${D}n"
            echo "RT=${D}(getprop $PROP_USEJIT)"
            echo "PS=${D}(getprop $PROP_PERSIST_USEJIT_INIT)"
        """.trimIndent()
    }

    data class PreResult(
        val exitCode: Int,
        val output: String,
        val ipRuleWait: Int,
        val drained: Boolean,
        val runtime: String,
        val persist: String
    ) {
        /** Канал реально работал (нет `ERR_*` и есть вывод). */
        val channelOk: Boolean get() = output.isNotBlank() && !output.contains("ERR_")
    }

    fun runPreRestart(context: Context, usejitValue: String): PreResult {
        BgWorker.assertNotMain("JitNet.runPreRestart")
        val (code, out) = runCatching {
            AdbClient.executeScript("jit_net_pre", preRestartScript(usejitValue), 45_000)
        }.getOrDefault(Pair(1, ""))
        fun tag(t: String): String =
            out.lineSequence().firstOrNull { it.trimStart().startsWith("$t=") }?.substringAfter('=')?.trim().orEmpty()
        val waitLine = out.lineSequence().firstOrNull { it.contains("ip_rule_wait=") }?.trim().orEmpty()
        val wait = Regex("ip_rule_wait=([0-9]+)").find(waitLine)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val drained = waitLine.contains("drained=yes")
        val res = PreResult(code, out, wait, drained, tag("RT"), tag("PS"))
        AppLog.i(TAG, "JIT_NET_PRE ip_rule_wait=$wait drained=${if (drained) "yes" else "no"} " +
            "rc=$code channel=${if (res.channelOk) "ok" else "failed"} " +
            "runtime=${res.runtime.ifEmpty { "empty" }} persist=${res.persist.ifEmpty { "empty" }}")
        return res
    }

    // ───────────────────────── §2.3(а) санация: только точные правила ─────────────────────────

    data class RuleLine(val priority: Int, val spec: String, val raw: String)

    /** Разбор `ip rule show`: `1000:\tfrom all fwmark 0x… lookup 1001`. */
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
     * План санации: удаляем правило ТОЛЬКО если оно однозначно опознано —
     * ровно один `oif` и он равен `wlan0`, и priority не встречается у других правил.
     * В остальных случаях — отказ (чужие правила не трогаем).
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
            RuleDecision(
                r, action,
                if (action == RuleAction.DELETE) "ip rule del ${r.spec}" else null
            )
        }
    }

    /**
     * Скрипт санации: перед каждым удалением список перечитывается (приоритеты могут устареть),
     * удаление — по полному набору селекторов (`ip rule del <spec>`), при неоднозначности — отказ.
     */
    internal fun sanitizeScript(): String {
        val D = "$"
        return """
            #!/system/bin/sh
            # iter33p-fix1 §2.3а — точное удаление только `oif wlan0`; при неоднозначности НЕ удаляем
            removed=0
            skipped=0
            i=0
            while [ ${D}i -lt 8 ]; do
              i=${D}((i+1))
              line=${D}(ip rule show 2>/dev/null | grep 'oif wlan0' | head -n1)
              [ -z "${D}line" ] && break
              prio=${D}(echo "${D}line" | cut -d: -f1 | tr -d ' ')
              spec=${D}(echo "${D}line" | cut -d: -f2- | sed 's/^[[:space:]]*//')
              dup=${D}(ip rule show 2>/dev/null | cut -d: -f1 | tr -d ' ' | grep -cx "${D}prio")
              oifs=${D}(echo "${D}line" | grep -o 'oif [^ ]*' | wc -l | tr -d ' ')
              oif=${D}(echo "${D}line" | grep -o 'oif [^ ]*' | head -n1 | sed 's/^oif //')
              if [ "${D}dup" != "1" ] || [ "${D}oifs" != "1" ] || [ "${D}oif" != "wlan0" ]; then
                if [ "${D}dup" != "1" ]; then why=duplicate_priority
                elif [ "${D}oifs" != "1" ]; then why=multiple_oif
                else why=not_wlan0; fi
                echo "JIT_NET_SANITIZE skipped reason=${D}why priority=${D}prio dup=${D}dup oif=${D}oifs"
                skipped=${D}((skipped+1))
                break
              fi
              if ip rule del ${D}spec 2>/dev/null; then
                removed=${D}((removed+1))
                echo "JIT_NET_SANITIZE deleted priority=${D}prio spec=${D}{spec}"
              else
                echo "JIT_NET_SANITIZE failed priority=${D}prio spec=${D}{spec}"
                break
              fi
            done
            left=${D}(ip rule show 2>/dev/null | grep -c 'oif wlan0')
            echo "JIT_NET_SANITIZE removed=${D}removed skipped=${D}skipped left=${D}left scope=oif_wlan0"
        """.trimIndent()
    }

    // ───────────────────────────── §2.3 возврат после подъёма ─────────────────────────────

    data class RestoreResult(
        val wifiConfirmed: Boolean,
        val apConfirmed: Boolean,
        val sanitizeDone: Boolean
    ) {
        val allConfirmed: Boolean get() = wifiConfirmed && apConfirmed
        fun log(): String =
            "wifi_confirmed=$wifiConfirmed ap_confirmed=$apConfirmed sanitize=$sanitizeDone"
    }

    /**
     * Возврат сети ТОЛЬКО из сохранённого состояния. Используется и после рестарта (`jitPendingVerify`),
     * и для компенсации в той же операции ([compensate]).
     */
    fun restoreAfterRestart(context: Context, saved: State, attempts: Int = WIFI_RESTORE_ATTEMPTS): RestoreResult {
        val app = context.applicationContext
        val sanitizeDone = runCatching {
            val (_, out) = AdbClient.executeScript("jit_net_sanitize", sanitizeScript(), 25_000)
            out.lineSequence().filter { it.contains("JIT_NET_SANITIZE") }
                .forEach { AppLog.i(TAG, it.trim()) }
            out.contains("JIT_NET_SANITIZE removed=") && !out.contains("ERR_")
        }.onFailure { AppLog.w(TAG, "JIT_NET_SANITIZE failed: ${it.javaClass.simpleName}") }.getOrDefault(false)

        val wifiConfirmed = if (saved.wifi) restoreWifi(app, attempts) else {
            AppLog.i(TAG, "JIT_NET_RESTORE wifi=0 state=skipped reason=was_off")
            true
        }
        val apConfirmed = if (saved.ap) restoreAp(app) else {
            AppLog.i(TAG, "JIT_NET_RESTORE ap=0 state=skipped reason=was_off")
            true
        }
        val res = RestoreResult(wifiConfirmed, apConfirmed, sanitizeDone)
        AppLog.i(TAG, "JIT_NET_RESTORE saved=${saved.log()} ${res.log()}")
        return res
    }

    /**
     * Компенсация внутри той же операции (watcher не записался, канал отвалился): возвращаем сеть,
     * ничего не перезапуская. Снимок не удаляем — его снимет вызывающий по подтверждению.
     */
    fun compensate(context: Context, saved: State, why: String): RestoreResult {
        AppLog.i(TAG, "JIT_NET_COMPENSATE start reason=$why saved=${saved.log()} (zygote НЕ перезапускается)")
        val res = restoreAfterRestart(context, saved, attempts = COMPENSATE_ATTEMPTS)
        AppLog.i(TAG, "JIT_NET_COMPENSATE done reason=$why ${res.log()}")
        return res
    }

    /** Wi-Fi: запрос + фактическое состояние (запрос ≠ результат). */
    private fun restoreWifi(context: Context, attempts: Int): Boolean {
        val wm = runCatching {
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        }.getOrNull() ?: run {
            AppLog.i(TAG, "JIT_NET_RESTORE wifi=1 request=skipped state=unknown reason=no_wifi_service")
            return false
        }
        for (attempt in 1..attempts) {
            val request = runCatching { if (wm.isWifiEnabled) "already_on" else "setWifiEnabled(true):${wm.setWifiEnabled(true)}" }
                .getOrDefault("error")
            val state = runCatching { wm.isWifiEnabled }.getOrNull()
            val link = linkState("wlan0")
            AppLog.i(TAG, "JIT_NET_RESTORE wifi=1 attempt=$attempt/$attempts request=$request " +
                "state=${state?.toString() ?: "unknown"} link=$link")
            if (state == true) return true
            if (attempt < attempts) runCatching { Thread.sleep(WIFI_RESTORE_PAUSE_MS) }
        }
        AppLog.i(TAG, "JIT_NET_RESTORE wifi=1 attempts=$attempts result=failed")
        return false
    }

    /** `up` — на интерфейсе есть IPv4 (`inet `), `down` — нет, `unknown` — канал молчит. */
    internal fun linkState(iface: String): String {
        val (code, out) = runCatching {
            AdbClient.execute("ip -o addr show $iface 2>/dev/null", 6_000, expectRoot = false)
        }.getOrDefault(Pair(1, ""))
        if (out.contains("ERR_") || (code != 0 && out.isBlank())) return "unknown"
        return if (out.lineSequence().any { it.contains("inet ") }) "up" else "down"
    }

    /** AP: механизм референса (`startSoftAp`), с честной причиной отказа. */
    private fun restoreAp(context: Context): Boolean {
        when (apState()) {
            true -> {
                AppLog.i(TAG, "JIT_NET_RESTORE ap=1 state=already_up")
                return true
            }
            null -> AppLog.i(TAG, "JIT_NET_RESTORE ap=1 request=skipped reason=state_unknown")
            false -> Unit
        }
        val err = runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.javaClass.getMethod("startSoftAp", android.net.wifi.WifiConfiguration::class.java)
                .invoke(wm, null)
            "ok"
        }.exceptionOrNull()
        val state = apState()
        AppLog.i(TAG, "JIT_NET_RESTORE ap=1 request=${if (err == null) "startSoftAp" else "failed"} " +
            "state=${state?.toString() ?: "unknown"} " +
            "reason=${err?.let { it.javaClass.simpleName + ": " + (it.message ?: "") } ?: "none"}")
        return state == true
    }
}
