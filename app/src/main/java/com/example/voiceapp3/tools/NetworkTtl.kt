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
 *  — НИКОГДА не печатать "65" из константы; правило читается ИЗ iptables -S (`ttl-set 65`).
 *  — iter33k §3: провод (tcpdump) в этой итерации не снимается; подтверждение — счётчики правил.
 *  — Формат TTL_CHECK: real=UNKNOWN expected=65 … method=counters → SCOPED_OK|OFF_OK|CHECK_OWNER
 *    или real=UNKNOWN method=none reason=... → UNVERIFIED
 *  — iptables -C НЕ ВСЕГДА поддерживает TTL target (на некоторых ядрах выдаёт "No chain/target/match")
 *    → используем grep -c по iptables -S как надёжный fallback.
 *  — Замер ДО и ПОСЛЕ ping для счётчиков: TTL_COUNTERS: before=... after=... delta=...
 *  — IPv6: счётчик правил `hl-set 65` в ip6tables (mangle/POSTROUTING), только со скоупом.
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

    /**
     * iter33m §1 — ЧЕСТНЫЙ разбор итога скрипта.
     *
     * Правила (мандат §1.3):
     *   • ON успешен ТОЛЬКО при `TTL_V4>=1` И `VERIFY_V4=OK` И скоупе `uplink_only=yes` И `variant=uplink`;
     *     нет uplink (`TTL_TRY variant=none reason=no_uplink`) — провал, никакого «применено частично»;
     *   • OFF успешен ТОЛЬКО при `TTL_V4=0 && TTL_V6=0` И `TTL_OFF … result=OK`;
     *     осталось правило — `FAILED` с честной причиной (в логе 07.11 было `PARTIAL` при v4=1 v6=1);
     *   • PARTIAL у TTL больше не возвращается: либо правило подтверждено, либо нет.
     */
    private fun parseVerifyResult(expected: Boolean, out: String): Pair<ToolsStep.Status, String> {
        // iter33n §4.2: ТОЛЬКО строгий разбор `TTL_V4=<n>` / `TTL_V6=<n>` из сводной строки.
        // Было `parseKv(...)`, который для строки `TTL_V4=0 TTL_V6=0 VERIFY_V4=OFF_OK …`
        // возвращал в v4 весь хвост строки, а v6 — "null": отсюда ложное
        // «TTL_OFF не выполнился: осталось правило v4=0 … v6=null» при полностью снятых правилах.
        fun num(key: String): String? =
            Regex("(?:^|\\s|_)$key=(\\d+)").find(out)?.groupValues?.get(1)
        fun flag(key: String): String? =
            Regex("(?:^|\\s)$key=([A-Za-z0-9_-]+)").find(out)?.groupValues?.get(1)
        val v4 = num("TTL_V4")
        val v6 = num("TTL_V6")
        val v4Verify = flag("VERIFY_V4")
        val v6Verify = flag("VERIFY_V6")
        val ttlCheckLine = out.lineSequence().firstOrNull { it.startsWith("TTL_CHECK:") } ?: ""
        val ttlVerdict = when {
            "SCOPED_OK" in ttlCheckLine -> "SCOPED_OK"
            "OFF_OK" in ttlCheckLine -> "OFF_OK"
            "CHECK_OWNER" in ttlCheckLine -> "CHECK_OWNER"
            "→ VERIFIED" in ttlCheckLine -> "VERIFIED"
            "→ MISMATCH" in ttlCheckLine -> "MISMATCH"
            "→ INDIRECT" in ttlCheckLine -> "INDIRECT"
            "→ UNVERIFIED" in ttlCheckLine -> "UNVERIFIED"
            else -> ""
        }
        val tryLine = out.lineSequence().firstOrNull { it.startsWith("TTL_TRY") } ?: ""
        val scopeLine = out.lineSequence().firstOrNull { it.startsWith("TTL_SCOPE") } ?: ""
        val variant = Regex("variant=([^ ]+)").find(tryLine)?.groupValues?.get(1) ?: ""
        val noUplink = variant == "none" && "reason=no_uplink" in tryLine
        val uplinkOnly = "uplink_only=yes" in scopeLine
        val hasLock = out.lowercase().contains("xtables lock")
        val hasPerm = out.lowercase().contains("permission denied") || out.lowercase().contains("avc: denied")
        val hasNoRoot = out.contains("ERR_ROOT_CHANNEL_UNAVAILABLE")
        val noTarget = out.lowercase().contains("no chain/target/match")
        val v4Count = v4?.toIntOrNull() ?: 0
        val v6Count = v6?.toIntOrNull() ?: 0
        val offLine = out.lineSequence().firstOrNull { it.startsWith("TTL_OFF ") } ?: ""
        val offOk = "result=OK" in offLine

        val reason = when {
            // iter23 P2.6: честный текст — канал это 5555, а не «второй»/резервный порт.
            hasNoRoot -> "канал недоступен — 5555 не отвечает (uid=1000). Проверьте «Для разработчика» → «Проверить root»."
            hasPerm   -> "Permission denied (нужен root-шелл uid=0)"
            hasLock   -> "xtables lock (iptables занят) — правило не применилось, повторите"
            noTarget  -> "iptables target 'TTL' не поддерживается ядром (модуль отсутствует)"
            !expected && !(v4 == "0" && v6 == "0" && offOk) ->
                "TTL_OFF не выполнился: осталось правило v4=$v4 v6=$v6 (см. TTL_DEL/TTL_SANITIZED/TTL_FOREIGN)"
            !expected -> null
            noUplink -> "нет uplink (правило без uplink не ставим — ломает CarPlay), TTL_TRY variant=none reason=no_uplink"
            v4Count < 1 || v4Verify != "OK" ->
                "rule_not_installed: правила TTL в mangle/POSTROUTING нет (TTL_V4=$v4 VERIFY_V4=$v4Verify)"
            !uplinkOnly ->
                "правило без скоупа -o <uplink> — запрещено (CarPlay): $scopeLine"
            else -> null
        }
        val status = when {
            reason == null -> ToolsStep.Status.OK
            hasNoRoot -> ToolsStep.Status.BLOCKED
            else -> ToolsStep.Status.FAILED
        }
        val detail = if (reason == null) {
            if (expected) "TTL_V4=$v4 TTL_V6=$v6 VERIFY=$v4Verify/$v6Verify scoped=uplink_only verdict=$ttlVerdict"
            else "TTL_V4=0 TTL_V6=0 VERIFY=$v4Verify/$v6Verify verdict=$ttlVerdict TTL_OFF result=OK"
        } else reason
        return status to detail
    }

    @Volatile private var ttlInFlight = false
    @Volatile private var ttlLastRunAtMs: Long = 0L

    /** iter33o §2.2 — повторы детекта uplink (только для ON), пауза 3 с. */
    internal const val TTL_UPLINK_RETRIES = 2
    internal const val TTL_UPLINK_RETRY_PAUSE_MS = 3_000L

    /** Причина отказа TTL именно «нет uplink» — единственный случай, когда повторяем детект. */
    internal fun isNoUplink(output: String): Boolean =
        output.contains("reason=no_uplink") || output.contains("uplink=none")

    /**
     * iter33n-fix1 §3.1 — окно debounce для ПОВТОРНОГО ON: 3 с (было 30 с).
     * Приёмник сети (`TTL_RECEIVER`) продолжает жить со своим окном 30 с: там шторм
     * переподключений, а не клик владельца. OFF не блокируется никогда (`debounceMs = 0`).
     */
    internal const val TTL_ON_DEBOUNCE_MS = 3_000L

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
        // iter33n-fix1 §3.1: окно debounce — только для ПОВТОРНОГО включения и ≤ 3 с.
        // Лог 08.11: второй клик через 13 с съедался окном 30 с (`skipped: debounce 13424ms`),
        // а выключение вообще не должно блокироваться: OFF — это возврат в сток, его ждут.
        val debounceMs = if (enabled) TTL_ON_DEBOUNCE_MS else 0L
        val sinceLast = t0 - ttlLastRunAtMs
        if (debounceMs > 0L && sinceLast in 0 until debounceMs) {
            AppLog.w(TAG, "TTL: setTtl65Active DEBOUNCE lastRan=${sinceLast}ms ago (<${debounceMs}ms) enabled=$enabled")
            val skipStep = ToolsStep(step="settings.ttl65", command="iptables ttl-set", startedAtMs=t0,
                finishedAtMs=SystemClock.uptimeMillis(), exitCode=1, stdout="",
                stderr="skipped: debounce ${sinceLast}ms (<${debounceMs}ms)", expected="", actual="",
                status=ToolsStep.Status.BLOCKED)
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
            // iter33o §2.2: первый клик 09.11 «съело» тем, что Wi-Fi ещё подключался
            // (`uplink=none, SRC_IP=none`), а через 50 с то же самое прошло. Правила и скрипт
            // НЕ меняем — просто до двух повторов ДЕТЕКТА с паузой 3 с, и только для ON и только
            // когда причина именно «нет uplink».
            var (exitCode, output) = AdbClient.executeScript("ttl_script", script, 40000)
            if (enabled) {
                var attempt = 1
                while (attempt <= TTL_UPLINK_RETRIES && isNoUplink(output)) {
                    Prefs2.putBoolean(context, PREF_TTL_65_ACTIVE, enabled)
                    AppLog.i(TAG, "TTL_UPLINK_RETRY attempt=$attempt/$TTL_UPLINK_RETRIES " +
                        "reason=no_uplink pause_ms=$TTL_UPLINK_RETRY_PAUSE_MS")
                    runCatching { Thread.sleep(TTL_UPLINK_RETRY_PAUSE_MS) }
                    val again = AdbClient.executeScript("ttl_script", script, 40000)
                    exitCode = again.first
                    output = again.second
                    AppLog.i(TAG, "TTL_UPLINK_RETRY attempt=$attempt/$TTL_UPLINK_RETRIES " +
                        "result=" + output.lineSequence().firstOrNull { it.startsWith("TTL_TRY") }
                            ?.let { if (it.contains("reason=no_uplink")) "no_uplink" else "uplink_ok" }
                            ?: "no_try_line")
                    attempt++
                }
            }

        // iter33m §1.2: санация и судьба каждого правила — отдельными строками лога.
        output.lineSequence().firstOrNull { it.startsWith("TTL_SANITIZED") }?.let { AppLog.i(TAG, it.trim()) }
        output.lineSequence().filter { it.startsWith("TTL_DEL") || it.startsWith("TTL_FOREIGN") }
            .forEach { AppLog.i(TAG, it.trim()) }
        output.lineSequence().firstOrNull { it.startsWith("TTL_TRY") }?.let { AppLog.i(TAG, it.trim()) }
        output.lineSequence().firstOrNull { it.startsWith("TTL_ADD") }?.let { AppLog.i(TAG, it.trim()) }
        val (status, reason) = parseVerifyResult(enabled, output)

        // P8: toggle back OFF if failed and we were trying to turn ON (PARTIAL/BLOCKED оставляем как есть — пользователь должен видеть не-идеал)
        if (enabled && status == ToolsStep.Status.FAILED) {
            AppLog.w(TAG, "TTL: toggle FAILED ($reason) — turning pref OFF to reflect reality")
            Prefs2.putBoolean(context, PREF_TTL_65_ACTIVE, false)
        }
        if (status == ToolsStep.Status.BLOCKED) {
            AppLog.w(TAG, "TTL: toggle BLOCKED ($reason). rootChannel state=${AdbClient.currentState()} port=${AdbClient.currentPort()}")
        }

        val expectedStr = if (enabled) "TTL_V4=1 TTL_V6=1 VERIFY=OK/OK rules=scoped" else "TTL_V4=0 TTL_V6=0 rules=none"
        // iter33k §3.4: выключение — эталонное удаление циклом; подтверждаем счётчиками.
        if (!enabled && !output.contains("TTL_OFF")) {
            val (c4, c6) = ttlRuleCounts()
            AppLog.i(TAG, TtlRules.offLine(maxOf(c4, 0), maxOf(c6, 0)))
        }
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
            // iter33k §3.4: строка загрузки печатается и здесь — честно «не восстановлено».
            AppLog.i(TAG, TtlRules.bootLine(0, 0) + " reason=channel_down")
            return
        }
        if (!active) {
            if (cnt4 == 0 && cnt6 == 0) {
                // iter33m §1.2: стартовая санация — нечего снимать, фиксируем факт строкой.
                AppLog.i(TAG, "TTL_SANITIZED removed=0 v4_removed=0 v6_removed=0 reason=start_nothing_to_remove")
                AppLog.i(TAG, "TTL: SKIP (rule absent, toggle off) v4=$cnt4 v6=$cnt6")
                return
            }
            AppLog.i(TAG, "TTL bootRestore: active=false, но правило есть (v4=$cnt4 v6=$cnt6) — снимаю (санация)")
        } else if (cnt4 > 0 && cnt6 > 0) {
            AppLog.i(TAG, "TTL: SKIP (rule present) v4=$cnt4 v6=$cnt6")
            // iter33k §3.4: правило уже стоит (напр. после перезапуска приложения) — фиксируем факт.
            AppLog.i(TAG, TtlRules.bootLine(cnt4, cnt6) + " reason=already_present")
            return
        }
        // iter33m §1.2: boot-ветка ставит правило ТОЛЬКО если тумблер включён; иначе это санация.
        val script = buildScript(active)
        val (rc, out) = AdbClient.executeScript("ttl_script", script, 40000)
        out.lineSequence().firstOrNull { it.startsWith("TTL_SANITIZED") }?.let { AppLog.i(TAG, it.trim()) }
        out.lineSequence().filter { it.startsWith("TTL_DEL") || it.startsWith("TTL_FOREIGN") }
            .forEach { AppLog.i(TAG, it.trim()) }
        out.lineSequence().firstOrNull { it.startsWith("TTL_TRY") }?.let { AppLog.i(TAG, it.trim()) }
        val (status, reason) = parseVerifyResult(active, out)
        // iter33k §3.4: одна строка восстановления при загрузке (правила — со скоупом).
        val after = ttlRuleCounts()
        AppLog.i(TAG, TtlRules.bootLine(after.first, after.second))
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
                    // iter42 §3: снимок устойчивого состояния после переприменения правил по
                    // событию сети (одно событие шторма = один снимок, с ограничением частоты).
                    runCatching {
                        JitNet.requestEventSnapshot(
                            rcvCtx, "ttl_network_change", NetInfoAndroid(rcvCtx)
                        )
                    }
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
