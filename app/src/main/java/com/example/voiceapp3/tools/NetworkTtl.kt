package com.example.voiceapp3.tools

import com.example.voiceapp3.tools.Prefs2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Сеть — фиксация TTL=65 (IPv4 TTL / IPv6 HL) в mangle/POSTROUTING.
 *
 * P4 (по логу):
 *  - `-w 5` у ВСЕХ вызовов iptables/ip6tables (ожидание xtables lock).
 *  - VERIFY через `iptables -C` (а не по флагу наличия строки).
 *  - Счётчики пакетов до/после (iptables -t mangle -L POSTROUTING -v -n)
 *    + попытка косвенно измерить фактический TTL (ping -t 65 -c 1 1.1.1.1).
 *  - Автоматическое выключение тумблера при провале (toggle-off).
 *  - bootRestore с VERIFY (лог, а не слепой re-apply).
 *  - NetworkStateReceiver (CONNECTIVITY_ACTION / ConnectivityManager.NetworkCallback)
 *    пере-применяет правило при поднятии wlan0.
 *
 * P8: VERIFY-строка после каждой операции; при маркерах ошибок
 * (xtables lock / Permission denied / ERR_ROOT_CHANNEL_UNAVAILABLE) — FAILED.
 */
object NetworkTtl {
    private const val TAG = "CENTRAL_NetworkTtl"
    private const val PREFS_NAME = "voiceapp3"

    // P-фикс: один BroadcastReceiver на процесс — иначе registerReceiver
    // вызывается на каждом onStartCommand и в логе дубли "network receiver registered" x6.
    @Volatile private var networkReceiver: BroadcastReceiver? = null

    const val PREF_TTL_65_ACTIVE = "ttl_65_ativo"

    private const val IPT  = "iptables"
    private const val IPT6 = "ip6tables"
    private const val W    = "-w 5"  // ждём xtables lock до 5 секунд

    /**
 * iter17: строим shell-скрипт с РЕАЛЬНЫМ замером TTL на проводе.
 * Ключевые требования iter17 §6a:
 *  — НИКОГДА не печатать "65" из константы; TTL приходит ИЗ tcpdump или ИЗ iptables -S.
 *  — Формат TTL_CHECK: real=<n> expected=<n> src=... dst=1.1.1.1 iface=... method=tcpdump → VERIFIED
 *    или real=UNKNOWN method=none reason=... → UNVERIFIED
 *  — iptables -C НЕ ВСЕГДА поддерживает TTL target (на некоторых ядрах выдаёт "No chain/target/match")
 *    → используем grep -c по iptables -S как надёжный fallback.
 *  — Замер ДО и ПОСЛЕ ping для счётчиков: TTL_COUNTERS: before=... after=... delta=...
 *  — IPv6: ищем hlim N в tcpdump и hl-set в ip6tables.
 *  — Замер производится на iface текущего дефолтного маршрута.
 */
private fun buildScript(enable: Boolean): String = TtlScript.build(enable)

    private fun getPrefs(context: Context): SharedPreferences = Prefs2.cps(context)

    fun isTtl65Active(context: Context): Boolean =
        getPrefs(context).getBoolean(PREF_TTL_65_ACTIVE, false)

    /** Парсит echo-style `KEY=value` строки из вывода. */
    private fun parseKv(output: String): Map<String, String> =
        output.lineSequence().mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0 || !line.substringBefore('=').matches(Regex("[A-Z0-9_]+"))) null
            else line.substringBefore('=') to line.substring(eq+1).trim()
        }.toMap()

    /** iter33h G1.5: одна строка результата клика — `TTL_RESULT …` (если скрипт её напечатал). */
    private fun ttlResultLine(out: String): String =
        out.lineSequence().firstOrNull { it.startsWith("TTL_RESULT ") }?.trim().orEmpty()

    private fun parseVerifyResult(expected: Boolean, out: String): Pair<ToolsStep.Status, String> {
        val kv = parseKv(out)
        val v4 = kv["TTL_V4"]?.trim()
        val v6 = kv["TTL_V6"]?.trim()
        val v4Verify = kv["VERIFY_V4"]
        val v6Verify = kv["VERIFY_V6"]
        val ttlCheckLine = out.lineSequence().firstOrNull { it.startsWith("TTL_CHECK:") } ?: ""
        val ttlVerdict = when {
            "→ VERIFIED" in ttlCheckLine -> "VERIFIED"
            "→ MISMATCH" in ttlCheckLine -> "MISMATCH"
            "→ INDIRECT" in ttlCheckLine -> "INDIRECT"
            "→ UNVERIFIED" in ttlCheckLine -> "UNVERIFIED"
            else -> ""
        }
        val ttlReal = Regex("real=([^ ]+)").find(ttlCheckLine)?.groupValues?.get(1) ?: "UNKNOWN"
        // iter19 §4.3: при real=UNKNOWN показываем rule_ttl (например "не измерен, правило 65").
        val ttlRuleEarly = Regex("rule_ttl=([^ ]+)").find(ttlCheckLine)?.groupValues?.get(1) ?: ""
        val hasLock = out.lowercase().contains("xtables lock")
        val hasPerm = out.lowercase().contains("permission denied") || out.lowercase().contains("avc: denied")
        val hasNoRoot = out.contains("ERR_ROOT_CHANNEL_UNAVAILABLE")
        val noTarget = out.lowercase().contains("no chain/target/match")

        // iter17: VERIFY допускает "OK-grepc" (когда iptables -C не знает TTL target,
        // но grep -c по -S видит правило). Это нормальный верифицированный вариант.
        val v4Ok: Boolean
        val v6Ok: Boolean
        if (expected) {
            val v4Count = v4?.toIntOrNull() ?: 0
            val v6Count = v6?.toIntOrNull() ?: 0
            v4Ok = v4Count >= 1 && (v4Verify == "OK" || v4Verify == "OK-grepc")
            v6Ok = v6Count >= 1 && (v6Verify == "OK" || v6Verify == "OK-grepc")
        } else {
            v4Ok = (v4 == null || v4 == "0") && v4Verify != "OK" && v4Verify != "OK-grepc"
            v6Ok = (v6 == null || v6 == "0") && v6Verify != "OK" && v6Verify != "OK-grepc"
        }

        val reason = when {
            // iter23 P2.6: честный текст — канал это 5555, а не «второй»/резервный порт.
            hasNoRoot -> "канал недоступен — 5555 не отвечает (uid=1000). Проверьте «Для разработчика» → «Проверить root»."
            hasPerm   -> "Permission denied (нужен root-шелл uid=0)"
            hasLock   -> "xtables lock (iptables занят, попробуйте снова)"
            noTarget  -> "iptables target 'TTL' не поддерживается ядром (модуль отсутствует)"
            expected && ttlVerdict == "MISMATCH" -> "wire TTL mismatch (real=$ttlReal, expected=65)"
            expected && ttlVerdict == "INDIRECT" -> "wire TTL не снят с провода (счётчики подросли, но ttl N в tcpdump не попал) → непрямое подтверждение"
            // iter33h G1.2: если правила нет — это FAILED, а не «применено частично».
            expected && ttlRuleEarly.isBlank() && !v4Ok -> "rule_not_installed: правила TTL в mangle/POSTROUTING нет (TTL_V4=$v4)"
            expected && ttlVerdict == "UNVERIFIED" -> "wire TTL не измерен (real=UNKNOWN${if (ttlRuleEarly.isNotBlank()) ", правило $ttlRuleEarly" else ""}): tcpdump не дал ttl N и счётчики не растут"
            !v4Ok     -> "TTL_V4 mismatch (expected=${if (expected) 1 else 0}, got=$v4 verify=$v4Verify)"
            !v6Ok     -> "TTL_V6 mismatch (expected=${if (expected) 1 else 0}, got=$v6 verify=$v6Verify)"
            else      -> null
        }
        val stepStatus = when {
            reason == null -> ToolsStep.Status.OK
            hasNoRoot -> ToolsStep.Status.BLOCKED
            hasPerm || noTarget -> ToolsStep.Status.FAILED
            expected && ttlVerdict == "MISMATCH" -> ToolsStep.Status.FAILED
            // iter33h G1.2: правило отсутствует ⇒ FAILED (PARTIAL — только когда правило есть,
            // а проводное значение не измерено).
            expected && ttlRuleEarly.isBlank() && !v4Ok -> ToolsStep.Status.FAILED
            expected && ttlVerdict == "INDIRECT" -> ToolsStep.Status.PARTIAL
            else -> ToolsStep.Status.PARTIAL
        }
        return stepStatus to (reason ?: "TTL_V4=$v4 TTL_V6=$v6 VERIFY=$v4Verify/$v6Verify wire=$ttlVerdict(real=$ttlReal)")
    }

    @Volatile private var ttlInFlight = false
    @Volatile private var ttlLastRunAtMs: Long = 0L

    fun setTtl65Active(context: Context, enabled: Boolean): ToolsResult {
        BgWorker.assertNotMain("NetworkTtl.setTtl65Active")
        val t0 = SystemClock.uptimeMillis()
        // iter21 §7: debounce 30s + single-flight — не давать шторму вызовов (в iter19 логе было 22 запуска за 7 с).
        if (ttlInFlight) {
            AppLog.w(TAG, "TTL: setTtl65Active SKIPPED (in-flight enabled=$enabled)")
            val skipStep = ToolsStep(step="settings.ttl65", command="iptables ttl-set", startedAtMs=t0,
                finishedAtMs=SystemClock.uptimeMillis(), exitCode=1, stdout="",
                stderr="skipped: single-flight in-flight", expected="", actual="", status=ToolsStep.Status.BLOCKED)
            return ToolsResult("settings.ttl65", "Фиксация TTL 65", listOf(skipStep))
        }
        val sinceLast = t0 - ttlLastRunAtMs
        if (sinceLast in 0 until 30_000L) {
            AppLog.w(TAG, "TTL: setTtl65Active DEBOUNCE lastRan=${sinceLast}ms ago (<30s)")
            val skipStep = ToolsStep(step="settings.ttl65", command="iptables ttl-set", startedAtMs=t0,
                finishedAtMs=SystemClock.uptimeMillis(), exitCode=1, stdout="",
                stderr="skipped: debounce ${sinceLast}ms", expected="", actual="", status=ToolsStep.Status.BLOCKED)
            return ToolsResult("settings.ttl65", "Фиксация TTL 65", listOf(skipStep))
        }
        synchronized(this) {
            if (ttlInFlight) {
                val skipStep = ToolsStep(step="settings.ttl65", command="iptables ttl-set", startedAtMs=t0,
                    finishedAtMs=SystemClock.uptimeMillis(), exitCode=1, stdout="",
                    stderr="skipped: in-flight", expected="", actual="", status=ToolsStep.Status.BLOCKED)
                return ToolsResult("settings.ttl65", "Фиксация TTL 65", listOf(skipStep))
            }
            ttlInFlight = true
        }
        try {
            ttlLastRunAtMs = SystemClock.uptimeMillis()
            Prefs2.putBoolean(context, PREF_TTL_65_ACTIVE, enabled)

            val script = buildScript(enabled)
            val (exitCode, output) = AdbClient.executeScript("ttl_script", script, 40000)

        val (status, reason) = parseVerifyResult(enabled, output)

        // P8: toggle back OFF if failed and we were trying to turn ON (PARTIAL/BLOCKED оставляем как есть — пользователь должен видеть не-идеал)
        if (enabled && status == ToolsStep.Status.FAILED) {
            AppLog.w(TAG, "TTL: toggle FAILED ($reason) — turning pref OFF to reflect reality")
            Prefs2.putBoolean(context, PREF_TTL_65_ACTIVE, false)
        }
        if (status == ToolsStep.Status.BLOCKED) {
            AppLog.w(TAG, "TTL: toggle BLOCKED ($reason). rootChannel state=${AdbClient.currentState()} port=${AdbClient.currentPort()}")
        }

        val expectedStr = if (enabled) "TTL_V4=1 TTL_V6=1 VERIFY=OK/OK wire-real=65" else "TTL_V4=0 TTL_V6=0 VERIFY=FAIL/FAIL"
        // iter19: печатаем ВСЮ строку TTL_CHECK как есть (не режем reason пробелом),
        // а отдельно достаём поля для структурированного лога. reason между "reason=" и "rule_ttl=" — всё, что внутри.
        val ttlCheckLine = output.lineSequence().firstOrNull { it.startsWith("TTL_CHECK:") } ?: ""
        val ttlReal   = Regex("real=([^ ]+)").find(ttlCheckLine)?.groupValues?.get(1) ?: "UNKNOWN"
        val ttlMethod = Regex("method=([^ ]+)").find(ttlCheckLine)?.groupValues?.get(1) ?: "none"
        val ttlIface  = Regex("iface=([^ ]+)").find(ttlCheckLine)?.groupValues?.get(1) ?: "unknown"
        val ttlSrc    = Regex("src=([^ ]+)").find(ttlCheckLine)?.groupValues?.get(1) ?: "unknown"
        val ttlRule   = Regex("rule_ttl=([^ ]+)").find(ttlCheckLine)?.groupValues?.get(1) ?: "none"
        val ttlHl     = Regex("hl_wire=([^ ]+)").find(ttlCheckLine)?.groupValues?.get(1) ?: ""
        // reason между "reason=" и " rule_ttl=" (может содержать пробелы)
        val ttlReason = run {
            val m = Regex("reason=(.*?) rule_ttl=").find(ttlCheckLine) ?: return@run ""
            m.groupValues[1]
        }
        val ttlArrow  = when {
            "→ VERIFIED" in ttlCheckLine -> "VERIFIED"
            "→ MISMATCH" in ttlCheckLine -> "MISMATCH"
            "→ INDIRECT" in ttlCheckLine -> "INDIRECT"
            "→ UNVERIFIED" in ttlCheckLine -> "UNVERIFIED"
            else -> "UNKNOWN"
        }
        val kv = parseKv(output)
        val actualLines = output.lineSequence().filter {
            it.startsWith("TTL_") || it.startsWith("VERIFY_") || it.startsWith("RULE_") || it.startsWith("TCPDUMP_") || it.startsWith("OUT_IFACE") || it.startsWith("PROBE:") || it.startsWith("TTL_CHECK:") || it.startsWith("TTL_COUNTERS:")
        }.joinToString(" | \n  ")
        // iter19 §4.3: при real=UNKNOWN показать rule_ttl в UI — передаём в step.expected/actual.
        AppLog.i(TAG, "TTL_CHECK (raw line from shell): $ttlCheckLine")
        // iter22 §5.2: числа отдельной строкой — before/after/rc/rule_ttl (значения правила, не строки).
        val numBefore = Regex("before=([^ ]+)").find(ttlCheckLine)?.groupValues?.get(1) ?: "?"
        val numAfter  = Regex("after=([^ ]+)").find(ttlCheckLine)?.groupValues?.get(1) ?: "?"
        val numRc     = Regex(" rc=([^ ]+)").find(ttlCheckLine)?.groupValues?.get(1) ?: "?"
        val numRule   = Regex("rule_ttl=([^ ]+)").find(ttlCheckLine)?.groupValues?.get(1) ?: "?"
        AppLog.i(TAG, "TTL_CHECK: before=$numBefore after=$numAfter rc=$numRc rule_ttl=$numRule")
        AppLog.i(TAG, "TTL_COUNTERS: ${output.lineSequence().firstOrNull { it.startsWith("TTL_COUNTERS:") } ?: "(none)"}")
        AppLog.i(TAG, "TTL_RULE: v4_rule=${kv["RULE_V4_TTL_AFTER"] ?: ttlRule} v6_hl=${kv["RULE_V6_HL_AFTER"]} vfy=${kv["VERIFY_V4"]}/${kv["VERIFY_V6"]}")
        AppLog.i(TAG, "VERIFY TTL: enabled=$enabled rc=$exitCode → $status ($reason)")

        val step = ToolsStep(
            step = "settings.ttl65",
            command = if (enabled) "iptables -w 5 -t mangle -A TTL --ttl-set 65" else "iptables -w 5 -t mangle -D flush",
            startedAtMs = t0,
            finishedAtMs = SystemClock.uptimeMillis(),
            exitCode = exitCode,
            stdout = output,
            stderr = if (status == ToolsStep.Status.OK) "" else (reason ?: "неизвестная ошибка") + " | rootState=${AdbClient.currentState()} port=${AdbClient.currentPort()}",
            expected = expectedStr,
            actual = actualLines,
            status = status
        )
            return ToolsResult("settings.ttl65", "Фиксация TTL 65", listOf(step))
        } finally {
            ttlInFlight = false
        }
    }

    fun bootRestore(context: Context) {
        BgWorker.assertNotMain("NetworkTtl.bootRestore")
        val active = isTtl65Active(context)
        LogKit.stateTtl(active)
        // iter33 B4: сначала ОДНА короткая read-only проверка. Если правило уже стоит —
        // ноль записи (раньше каждый тик заливался скрипт чанками: `rm -f` + `cat >>`×7 + chmod + sh).
        val (cnt4, cnt6) = ttlRuleCounts()
        // iter33b C2: (-1,-1) — «канал не ответил», а НЕ «правила нет». Раньше при включённом
        // тумблере это уходило в полную заливку скрипта чанками на каждом тике. Теперь — SKIP.
        if (cnt4 < 0 || cnt6 < 0) {
            AppLog.i(TAG, "TTL: SKIP (unknown, channel down) v4=$cnt4 v6=$cnt6")
            return
        }
        if (!active) {
            if (cnt4 == 0 && cnt6 == 0) {
                AppLog.i(TAG, "TTL: SKIP (rule absent, toggle off) v4=$cnt4 v6=$cnt6")
                return
            }
            AppLog.i(TAG, "TTL bootRestore: active=false, но правило есть (v4=$cnt4 v6=$cnt6) — снимаю")
        } else if (cnt4 > 0 && cnt6 > 0) {
            AppLog.i(TAG, "TTL: SKIP (rule present) v4=$cnt4 v6=$cnt6")
            return
        }
        val script = buildScript(true)
        val (rc, out) = AdbClient.executeScript("ttl_script", script, 40000)
        val (status, reason) = parseVerifyResult(true, out)
        AppLog.i(TAG, "VERIFY TTL bootRestore: rc=$rc → $status ($reason)")
    }

    /**
     * iter33 B4: read-only счётчики правил TTL/HL. Одна ADB-команда, ничего не пишет.
     * Возвращает (v4_rule_count, v6_rule_count); при недоступности канала — (-1, -1).
     */
    fun ttlRuleCounts(): Pair<Int, Int> = runCatching {
        val (_, out) = AdbClient.execute(
            "iptables -t mangle -S POSTROUTING 2>/dev/null | grep -c ttl-set; " +
                "ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -c hl-set", 5000, expectRoot = false)
        val nums = out.lineSequence().mapNotNull { it.trim().toIntOrNull() }.toList()
        Pair(nums.getOrElse(0) { -1 }, nums.getOrElse(1) { -1 })
    }.getOrDefault(Pair(-1, -1))

    /**
     * Регистрация сетевого ресивера: при поднятии/изменении сети пере-проверяем/
     * пере-применяем правила (чтобы правило пережило перезапуск wlan0).
     *
     * Вызывать один раз из App.onCreate / VoiceAssistantService.onCreate.
     */
    fun registerNetworkReceiver(context: Context) {
        val ctx = context.applicationContext
        // P-фикс: idempotent — если ресивер уже зарегистрирован, не дублировать
        // (иначе на каждом onStartCommand добавлялся ещё один = в логе x6).
        if (networkReceiver != null) {
            AppLog.i(TAG, "network receiver already registered — skip (single instance)")
            return
        }
        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION).apply {
            addAction("android.net.conn.CONNECTIVITY_CHANGE")
            addAction("android.net.wifi.STATE_CHANGE")
        }
        val recv = object : BroadcastReceiver() {
            override fun onReceive(rcvCtx: Context, intent: Intent) {
                BgWorker.post("NetworkTtl.netChange") {
                    val active = isTtl65Active(rcvCtx)
                    if (!active) return@post
                    val cm = rcvCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    val isWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val cap = cm?.getNetworkCapabilities(cm.activeNetwork)
                        cap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    } else {
                        @Suppress("DEPRECATION") cm?.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
                    }
                    // iter22 §5.1: приёмник сети идёт через ТОТ ЖЕ single-flight + окно 30 000 мс,
                    // иначе storm перезапусков (в iter19 — 22 запуска за 7 с).
                    val now = SystemClock.uptimeMillis()
                    synchronized(this@NetworkTtl) {
                        if (ttlInFlight) {
                            AppLog.w(TAG, "TTL_RECEIVER: skipped reason=in_flight (wifi=$isWifi)")
                            return@post
                        }
                        val since = now - ttlLastRunAtMs
                        if (since in 0 until 30_000L) {
                            AppLog.w(TAG, "TTL_RECEIVER: skipped reason=debounce ${since}ms (wifi=$isWifi)")
                            return@post
                        }
                        ttlInFlight = true
                        ttlLastRunAtMs = now
                    }
                    val res = try {
                        AdbClient.executeScript("ttl_script", buildScript(true), 40000)
                    } finally {
                        ttlInFlight = false
                    }
                    val rc = res.first
                    val out = res.second
                    val (status, reason) = parseVerifyResult(true, out)
                    AppLog.i(TAG, "VERIFY TTL networkChange: rc=$rc → $status ($reason)")
                    if (status == ToolsStep.Status.FAILED) {
                        AppLog.w(TAG, "TTL networkChange: failed to re-apply — turning toggle off")
                        Prefs2.putBoolean(rcvCtx, PREF_TTL_65_ACTIVE, false)
                    }
                }
            }
        }
        try {
            ctx.registerReceiver(recv, filter)
            networkReceiver = recv
            AppLog.i(TAG, "network receiver registered (single instance)")
        } catch (t: Throwable) {
            AppLog.e(TAG, "registerNetworkReceiver failed: ${t.message}", t)
        }
    }

    @JvmStatic fun unregisterNetworkReceiver(context: Context) {
        val ctx = context.applicationContext
        val recv = networkReceiver ?: return
        runCatching { ctx.unregisterReceiver(recv) }
        networkReceiver = null
        AppLog.i(TAG, "network receiver unregistered")
    }
}
