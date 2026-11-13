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

    /** §5 iter45: СОХРАНЁННОЕ НАМЕРЕНИЕ владельца. Только пользователь меняет его. */
    const val PREF_TTL_65_ACTIVE = "ttl_65_ativo"

    /**
     * §5 iter45: ФАКТИЧЕСКОЕ применение правил — отдельно от намерения.
     *
     * Раньше одно и то же значение означало и «владелец хочет», и «правило стоит»: при
     * `no_uplink`, отсутствии root, ошибке iptables код писал `ttl_65_ativo=false`, и тумблер
     * сам выключался. Владелец видел «TTL постоянно отключается». Теперь:
     *   `applied` — правило подтверждено VERIFY;
     *   `pending` — намерение ON, применения ЕЩЁ НЕТ (сеть/root/канал недоступны) — повтор
     *               произойдёт по сетевому событию, ограниченно и идемпотентно;
     *   `off`     — правил нет (выключено владельцем или подтверждённо снято).
     */
    const val PREF_TTL_65_APPLIED = "ttl_65_applied"
    /** Причина `pending` — для подписи в интерфейсе (не для изменения намерения). */
    const val PREF_TTL_65_PENDING_REASON = "ttl_65_pending_reason"
    /** Время последней подтверждённой попытки применения (wall clock, мс). */
    const val PREF_TTL_65_APPLIED_AT = "ttl_65_applied_at"
    /** §5.3 iter46: загрузка Android, в которой факт подтверждён (BOOT_COUNT). */
    const val PREF_TTL_65_APPLIED_BOOT = "ttl_65_applied_boot"

    /**
     * §5 iter45: поколение намерения. Пользовательский клик увеличивает его; результат старой
     * попытки, посчитанный до клика, не имеет права ни включить, ни выключить правило.
     */
    private val intentGen = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * §5.1/§5.2 iter46: политика «применять по последнему сохранённому намерению».
     * Реализация — чистый класс [TtlReconcile] (покрыт JVM-тестами), здесь только вызовы.
     */
    private val reconcile = TtlReconcile(maxChain = 3)

    /** Желание владельца (не меняется автоматикой). */
    fun desired(context: Context): Boolean = isTtl65Active(context)

    /** Счётчик загрузок Android — «факт из прошлой загрузки» больше не выдаётся за текущий. */
    private fun bootCount(context: Context): Int = runCatching {
        android.provider.Settings.Global.getInt(
            context.applicationContext.contentResolver,
            android.provider.Settings.Global.BOOT_COUNT, -1
        )
    }.getOrDefault(-1)

    /**
     * Подтверждено ли применение (`pending` ≠ выключено).
     *
     * §5.3 iter46: факт привязан к загрузке и к проверке. Если правило было подтверждено в
     * ПРОШЛОЙ загрузке (или boot нельзя прочитать), «применено» не заявляется: возвращается
     * `pending` — «состояние не проверено в текущей загрузке», даже если значение на диске
     * осталось `applied`. Ложное «применено и подтверждено» по прошлому сеансу исключено.
     */
    fun appliedState(context: Context): String {
        val raw = getPrefs(context).getString(PREF_TTL_65_APPLIED,
            if (isTtl65Active(context)) "pending" else "off") ?: "off"
        if (raw != "applied") return raw
        val stored = getPrefs(context).getLong(PREF_TTL_65_APPLIED_BOOT, -1L)
        val now = bootCount(context).toLong()
        if (stored < 0L || now < 0L || stored != now) return "pending"
        return raw
    }

    fun pendingReason(context: Context): String =
        getPrefs(context).getString(PREF_TTL_65_PENDING_REASON, "") ?: ""

    private fun markApplied(context: Context, applied: Boolean, reason: String) {
        // §5 iter45: решение «applied|pending|off» — в чистых правилах Toggles45 (покрыты тестами).
        Prefs2.putString(context, PREF_TTL_65_APPLIED,
            Toggles45.appliedOutcome(isTtl65Active(context), applied))
        Prefs2.putString(context, PREF_TTL_65_PENDING_REASON, if (applied) "" else reason)
        if (applied) {
            Prefs2.putLong(context, PREF_TTL_65_APPLIED_AT, System.currentTimeMillis())
            // §5.3 iter46: вместе с фактом фиксируется загрузка, в которой он подтверждён.
            Prefs2.putLong(context, PREF_TTL_65_APPLIED_BOOT, bootCount(context).toLong())
        }
        AppLog.i(TAG, "TTL_STATE desired=${desired(context)} applied=${appliedState(context)} " +
            "reason=«${pendingReason(context)}» (намерение владельца не меняется автоматикой)")
    }

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
        // ── §5.1 iter46: НАМЕРЕНИЕ СОХРАНЯЕТСЯ ПЕРВЫМ ──────────────────────────────────────
        // Раньше отказ по `ttlInFlight`/debounce стоял ВЫШЕ сохранения, и быстрый OFF во время
        // ON/retry терялся. Теперь запрос владельца фиксируется всегда, а применение либо
        // выполняется сразу, либо добирается после текущей операции (ограниченное добивание).
        Prefs2.putBoolean(context, PREF_TTL_65_ACTIVE, enabled)
        Prefs2.putString(context, PREF_TTL_65_APPLIED,
            if (enabled) "pending" else Prefs2.getString(context, PREF_TTL_65_APPLIED, "off") ?: "off")
        AppLog.i(TAG, "TTL_INTENT desired=$enabled source=user_click in_flight=${ttlInFlight}")
        val decision = reconcile.onIntent(enabled)
        if (decision == TtlReconcile.Decision.MARKED_AND_RUNNING) {
            AppLog.i(TAG, "TTL_RECONCILE desired=$enabled action=after_current " +
                "(применение уже идёт: намерение сохранено и будет догнано после него; " +
                "мгновенная отмена уже запущенного скрипта не заявляется)")
            val st = ToolsStep(step="settings.ttl65", command="iptables ttl-set", startedAtMs=t0,
                finishedAtMs=SystemClock.uptimeMillis(), exitCode=0, stdout="",
                stderr="", expected=enabled.toString(), actual="queued_after_current",
                status=ToolsStep.Status.PARTIAL)
            return ToolsResult("settings.ttl65", "Фиксация TTL 65", listOf(st))
        }
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
        // §5.1 iter46: отдельного «debounce» больше нет — он ТЕРЯЛ нажатие. Шторм ограничен
        // иначе: single-flight + ограниченная цепочка добиваний ([TtlReconcile.maxChain]).
        synchronized(this) { ttlInFlight = true }
        val myGen = intentGen.incrementAndGet()
        try {
            ttlLastRunAtMs = SystemClock.uptimeMillis()
            AppLog.i(TAG, "TTL_APPLY desired=$enabled gen=$myGen (запуск применения)")
            // §5.1 iter46: единый путь применения (клик, добивание, сеть) — [applyForIntent].
            val ok = applyForIntent(context, enabled, "click")
            val (status, reason) = if (ok) {
                ToolsStep.Status.OK to null
            } else {
                ToolsStep.Status.FAILED to (pendingReason(context).ifEmpty { "not_confirmed" })
            }
            val (out4, out6) = ttlRuleCounts()
            val expectedStr = if (enabled) "TTL_V4=1 TTL_V6=1 VERIFY=OK/OK rules=scoped"
            else "TTL_V4=0 TTL_V6=0 rules=none"
            val actualStr = if (enabled) "TTL_V4=$out4 TTL_V6=$out6 scoped=uplink_only"
            else "TTL_V4=$out4 TTL_V6=$out6"
            val step = ToolsStep(
                step = "settings.ttl65",
                command = if (enabled) "iptables -w 5 -t mangle -A TTL --ttl-set 65"
                else "iptables -w 5 -t mangle -D (только свои правила)",
                startedAtMs = t0,
                finishedAtMs = SystemClock.uptimeMillis(),
                exitCode = if (ok) 0 else 1,
                stdout = "",
                stderr = if (ok) "" else (reason ?: "не подтверждено"),
                expected = expectedStr,
                actual = actualStr,
                status = status
            )
            return ToolsResult("settings.ttl65", "Фиксация TTL 65", listOf(step))
        } finally {
            synchronized(this) { ttlInFlight = false }
            // §5.1 iter46: применение завершилось — если последнее сохранённое намерение ещё не
            // достигнуто (например, во время ON пришёл OFF), ВЫПОЛНЯЕМ ровно одно добивание.
            // Цепочка ограничена внутри TtlReconcile: бесконечных повторов и лавины команд нет.
            val followUp = reconcile.onRunFinished(lastApplyOk)
            if (followUp != null) {
                AppLog.i(TAG, "TTL_RECONCILE follow_up desired=$followUp action=run_after_current " +
                    "(догоняем последнее намерение владельца; уже запущенный скрипт не отменяем)")
                runCatching { applyForIntent(context, followUp, "reconcile") }
            } else {
                AppLog.i(TAG, "TTL_RECONCILE done desired=${reconcile.desired()} applied=${reconcile.applied()} " +
                    "action=none")
            }
        }
    }

    /** Результат последнего применения (для добивания): подтверждено ли состояние. */
    @Volatile private var lastApplyOk = false

    /**
     * Применение по явному намерению (используется кликом, добиванием и сетевым событием).
     * Возвращает подтверждённый результат; намерение владельца не меняет.
     */
    internal fun applyForIntent(context: Context, want: Boolean, source: String): Boolean {
        val script = buildScript(want)
        val t0 = SystemClock.uptimeMillis()
        var (exitCode, output) = AdbClient.executeScript("ttl_script", script, 40000)
        if (want) {
            var attempt = 1
            while (attempt <= TTL_UPLINK_RETRIES && isNoUplink(output)) {
                AppLog.i(TAG, "TTL_UPLINK_RETRY attempt=$attempt/$TTL_UPLINK_RETRIES " +
                    "reason=no_uplink pause_ms=$TTL_UPLINK_RETRY_PAUSE_MS source=$source")
                runCatching { Thread.sleep(TTL_UPLINK_RETRY_PAUSE_MS) }
                val again = AdbClient.executeScript("ttl_script", script, 40000)
                exitCode = again.first
                output = again.second
                attempt++
            }
        }
        val (status, reason) = parseVerifyResult(want, output)
        val ok = status == ToolsStep.Status.OK
        lastApplyOk = ok
        ttlLastRunAtMs = android.os.SystemClock.uptimeMillis()
        // Факт привязывается к текущей подтверждённой конфигурации (см. §5.3): успешный readback
        // ставит `applied`, провал — `pending` с причиной; намерение НЕ меняется.
        markApplied(context, ok, if (ok) "" else reason.ifEmpty { status.wire })
        AppLog.i(TAG, "TTL_APPLY_RESULT source=$source want=$want rc=$exitCode ok=$ok " +
            "desired=${desired(context)} applied=${appliedState(context)}")
        return ok
    }

    fun bootRestore(context: Context) {
        BgWorker.assertNotMain("NetworkTtl.bootRestore")
        val active = isTtl65Active(context)
        LogKit.stateTtl(active)
        // §5.3 iter46: на старте/упоминании факт не подтверждён текущей проверкой — до readback
        // состояние честно `pending` (никаких «применено» по прошлому сеансу).
        if (active) markApplied(context, false, "boot_unverified")
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
            // §5.3 iter46: правило реально прочитано в этой загрузке — вот ТЕПЕРЬ факт `applied`.
            markApplied(context, true, "")
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
        // §5.3 iter46: факт выводится из ФАКТИЧЕСКОГО чтения после применения, а не из вызова.
        val ok = status == ToolsStep.Status.OK &&
            (!active || after.first > 0) && (active || (after.first == 0 && after.second == 0))
        if (!active && after.first <= 0 && after.second <= 0) markApplied(context, true, "")
        else markApplied(context, ok, if (ok) "" else (reason ?: "boot_not_confirmed"))
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
                    // §3.3 iter44: снимок сети НЕ зависит от того, включён ли TTL. Раньше при
                    // выключенном TTL функция выходила ДО снимка, и существенное изменение сети
                    // (ассоциация/IP/маршрут/DNS/validated) вообще не попадало в журнал. Снимок
                    // ограничен частотой и single-flight; при пропуске по частоте запрашивается
                    // ОДИН trailing-снимок после затихания шторма (см. JitNet.scheduleTrailing).
                    runCatching {
                        JitNet.requestEventSnapshot(
                            rcvCtx, "net_change:ttl=" + (if (active) "on" else "off"),
                            NetInfoAndroid(rcvCtx)
                        )
                    }
                    if (!active) return@post
                    val cm = rcvCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    val isWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val cap = cm?.getNetworkCapabilities(cm.activeNetwork)
                        cap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    } else {
                        @Suppress("DEPRECATION") cm?.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
                    }
                    // §5.2 iter46: событие сети НЕ ТЕРЯЕТСЯ. Раньше при in-flight/30-секундном
                    // окне оно просто отбрасывалось, и «пропущено» означало «потеряно»: правила
                    // оставались снятыми до следующего broadcast. Теперь пропуск ставит флаг
                    // «догнать», а добивание выполняется после текущего применения.
                    if (!reconcile.onNetworkEvent()) {
                        AppLog.i(TAG, "TTL_RECEIVER skipped reason=in_flight action=mark_dirty " +
                            "wifi=$isWifi (событие запомнено: будет один добивочный проход, " +
                            "а не потеря)")
                        return@post
                    }
                    val now = SystemClock.uptimeMillis()
                    synchronized(this@NetworkTtl) {
                        val since = now - ttlLastRunAtMs
                        if (since in 0 until 30_000L) {
                            // Не теряем: помечаем, что нужен проход, и планируем ОДИН отложенный
                            // (без цикла и без лавины команд).
                            val delay = 30_000L - since
                            AppLog.i(TAG, "TTL_RECEIVER skipped reason=window sinceMs=$since " +
                                "action=schedule_once delay_ms=$delay wifi=$isWifi")
                            networkRecheckOnce(rcvCtx, delay)
                            return@post
                        }
                        ttlInFlight = true
                        ttlLastRunAtMs = now
                    }
                    val res = try {
                        AdbClient.executeScript("ttl_script", buildScript(true), 40000)
                    } finally {
                        synchronized(this@NetworkTtl) { ttlInFlight = false }
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
                    // §5 iter45: сетевое событие больше НЕ выключает намерение владельца.
                    // Нет интернета/раздачи в этот момент — это доступность, а не желание.
                    if (status == ToolsStep.Status.OK) {
                        markApplied(rcvCtx, true, "")
                    } else {
                        markApplied(rcvCtx, false, reason.ifEmpty { "network_change_failed" })
                        AppLog.i(TAG, "TTL_PENDING reason=«${reason.ifEmpty { "network_change_failed" }}» " +
                            "desired=true applied=pending action=wait_network " +
                            "(тумблер остаётся включённым; повтор — по следующему сетевому событию)")
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

    /** Один отложенный повтор по сетевому событию (без циклов: флаг single-shot). */
    @Volatile private var recheckScheduled = false

    private fun networkRecheckOnce(ctx: Context, delayMs: Long) {
        if (recheckScheduled) return
        recheckScheduled = true
        BgWorker.postDelayed("NetworkTtl.netRecheck", delayMs) {
            recheckScheduled = false
            runCatching { reapplyForNetwork(ctx) }
        }
    }

    /**
     * §5.2 iter46: пере-применение по событию сети (одно событие — один проход).
     * Факт привязывается к текущей проверке: до неё состояние не объявляется «применено».
     */
    private fun reapplyForNetwork(ctx: Context) {
        if (!isTtl65Active(ctx)) {
            AppLog.i(TAG, "TTL_NET_REAPPLY desired=false action=none (владелец выключил — не включаем)")
            return
        }
        markApplied(ctx, false, "network_change_unverified")
        val ok = applyForIntent(ctx, true, "network")
        AppLog.i(TAG, "TTL_NET_REAPPLY ok=$ok desired=true applied=${appliedState(ctx)}")
    }

    @JvmStatic fun unregisterNetworkReceiver(context: Context) {
        val ctx = context.applicationContext
        val recv = networkReceiver ?: return
        runCatching { ctx.unregisterReceiver(recv) }
        networkReceiver = null
        AppLog.i(TAG, "network receiver unregistered")
    }
}
