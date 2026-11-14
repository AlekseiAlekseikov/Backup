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
        val raw = Prefs2.getString(context, PREF_TTL_65_APPLIED,
            if (isTtl65Active(context)) "pending" else "off") ?: "off"
        if (raw != "applied") return raw
        val stored = Prefs2.getLong(context, PREF_TTL_65_APPLIED_BOOT, -1L)
        val now = bootCount(context).toLong()
        if (stored < 0L || now < 0L || stored != now) return "pending"
        // §4.2 п.4 iter47: факт принадлежит ПОКОЛЕНИЮ намерения. Если намерение успели
        // изменить (новый клик), старый «applied» относится к другому желанию — не заявляем.
        val factGen = Prefs2.getLong(context, PREF_TTL_65_APPLIED_GEN, -1L)
        val nowGen = Prefs2.getLong(context, PREF_TTL_65_INTENT_GEN, 0L)
        if (factGen >= 0L && nowGen > 0L && factGen != nowGen) return "pending"
        return raw
    }

    fun pendingReason(context: Context): String =
        Prefs2.getString(context, PREF_TTL_65_PENDING_REASON, "") ?: ""

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

    /**
     * §4.6 iter47: намерение читается ТОЛЬКО через согласование копий (`Prefs2.readBoolean`),
     * а не напрямую из CPS — иначе дефект §6 «старое true из второй копии» возвращался бы в
     * production-путь TTL. Конфликт равных ревизий = «неизвестно» → OFF не выдумываем, но и
     * ON не подтверждаем: `false` здесь означает лишь «достоверного ON нет».
     */
    fun isTtl65Active(context: Context): Boolean =
        when (val r = Prefs2.readBoolean(context, PREF_TTL_65_ACTIVE)) {
            is Toggles45.Resolved.Value -> r.v
            Toggles45.Resolved.Deleted -> false
            Toggles45.Resolved.Conflict -> {
                AppLog.w(TAG, "TTL_ACTIVE_CONFLICT key=$PREF_TTL_65_ACTIVE " +
                    "(CPS и DPS имеют равные ревизии с разными значениями: применяем как «не ON», " +
                    "ничего не перезаписывая автоматически)")
                false
            }
            Toggles45.Resolved.Unknown -> false
        }

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

    @Volatile private var ttlLastRunAtMs: Long = 0L

    /** iter33o §2.2 — повторы детекта uplink (только для ON), пауза 3 с. */
    internal const val TTL_UPLINK_RETRIES = 2
    internal const val TTL_UPLINK_RETRY_PAUSE_MS = 3_000L

    /** Причина отказа TTL именно «нет uplink» — единственный случай, когда повторяем детект. */
    internal fun isNoUplink(output: String): Boolean =
        output.contains("reason=no_uplink") || output.contains("uplink=none")

    /**
     * iter33n-fix1 §3.1 — окно debounce для ПОВТОРНОГО ON: 3 с (было 30 с).
     * §4.2 iter47: клики это окно больше НЕ использует — оно ТЕРЯЛО нажатие. Окно осталось
     * только для сетевого шторма (в receiver), и даже там событие не теряется: оно помечается
     * и добирается одним отложенным проходом.
     */
    internal const val TTL_ON_DEBOUNCE_MS = 3_000L

    // ───────────────────────── §4.2 iter47: единственный путь применения ─────────────────────

    /** Поколение намерения: растёт при каждой ПОДТВЕРЖДЁННОЙ записи намерения. */
    private const val PREF_TTL_65_INTENT_GEN = "ttl_65_intent_gen"

    /** Поколение, к которому относится подтверждённый факт `applied`. */
    private const val PREF_TTL_65_APPLIED_GEN = "ttl_65_applied_gen"

    private val runner: TtlRunner by lazy {
        TtlRunner(
            transport = object : TtlRunner.Transport {
                override fun apply(want: Boolean): TtlRunner.Outcome = runTransport(want, "runner")
            },
            store = object : TtlRunner.Store {
                override fun desired(): Boolean? {
                    val r = Prefs2.readBoolean(appCtxRef ?: return null, PREF_TTL_65_ACTIVE)
                    return when (r) {
                        is Toggles45.Resolved.Value -> r.v
                        Toggles45.Resolved.Deleted -> false
                        // Конфликт/неизвестность: НЕ выдумываем ни ON, ни OFF.
                        Toggles45.Resolved.Conflict, Toggles45.Resolved.Unknown -> null
                    }
                }

                override fun intentGen(): Long =
                    appCtxRef?.let { Prefs2.getLong(it, PREF_TTL_65_INTENT_GEN, 0L) } ?: 0L

                override fun beginIntent(want: Boolean): Long {
                    val c = appCtxRef ?: return -1L
                    // §4.2 п.1: сначала НАМЕРЕНИЕ, с фактическим результатом записи.
                    val save = Prefs2.putBooleanEx(c, PREF_TTL_65_ACTIVE, want)
                    if (!save.confirmed) {
                        AppLog.w(TAG, "TTL_INTENT_WRITE_FAILED want=$want ${save.wire()} " +
                            "(намерение НЕ подтверждено: «применено» заявлять нельзя)")
                        return -1L
                    }
                    if (save.partial) {
                        AppLog.i(TAG, "TTL_INTENT_PARTIAL want=$want ${save.wire()} " +
                            "(записалась только одна копия — вторая будет согласована следующей записью)")
                    }
                    val gen = Prefs2.getLong(c, PREF_TTL_65_INTENT_GEN, 0L) + 1L
                    Prefs2.putLong(c, PREF_TTL_65_INTENT_GEN, gen)
                    Prefs2.putString(c, PREF_TTL_65_APPLIED,
                        if (want) "pending" else Prefs2.getString(c, PREF_TTL_65_APPLIED, "off") ?: "off")
                    if (want) Prefs2.putString(c, PREF_TTL_65_PENDING_REASON, "applying")
                    return gen
                }

                override fun markApplied(
                    ok: Boolean,
                    want: Boolean,
                    gen: Long,
                    current: Boolean,
                    reason: String
                ) {
                    val c = appCtxRef ?: return
                    // §4.2 п.4: факт принадлежит ПОКОЛЕНИЮ намерения. Результат прохода, во
                    // время которого намерение сменилось, не переименовывается в новый факт.
                    if (!current) {
                        Prefs2.putString(c, PREF_TTL_65_APPLIED, "pending")
                        Prefs2.putString(c, PREF_TTL_65_PENDING_REASON, "superseded")
                        AppLog.i(TAG, "TTL_APPLIED_PENDING gen=$gen reason=superseded " +
                            "(намерение сменилось во время прохода: результат относится к старому " +
                            "желанию и фактом нового не становится)")
                        return
                    }
                    markAppliedInternal(c, ok, reason, gen)
                }

                override fun conflict(): Boolean =
                    appCtxRef?.let {
                        Prefs2.readBoolean(it, PREF_TTL_65_ACTIVE) == Toggles45.Resolved.Conflict
                    } ?: false

                override fun desiredProblem(): String = "unreadable_or_conflict"
            },
            log = { line -> AppLog.i(TAG, line) }
        )
    }

    /** Контекст приложения для лямбд раннера (ставится при первом обращении/инициализации). */
    @Volatile private var appCtxRef: Context? = null

    private fun ensureCtx(context: Context): Context {
        val app = context.applicationContext
        appCtxRef = app
        return app
    }

    /**
     * Исполнение ОДНОГО применения (единственное место, где TTL вызывает shell).
     *
     * Результат — только подтверждённая проверка: ON требует `TTL_V4>=1 && VERIFY_V4=OK &&
     * uplink_only=yes`, OFF — отсутствия СВОИХ правил. Ответ транспорта/успешный rc сам по
     * себе применением не считается.
     */
    private fun runTransport(want: Boolean, source: String): TtlRunner.Outcome {
        val t0 = SystemClock.uptimeMillis()
        val script = buildScript(want)
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
        ttlLastRunAtMs = SystemClock.uptimeMillis()
        AppLog.i(TAG, "TTL_APPLY_RESULT source=$source want=$want rc=$exitCode ok=$ok " +
            "ms=${ttlLastRunAtMs - t0} reason=«${reason ?: ""}»")
        return TtlRunner.Outcome(ok, reason ?: if (ok) "" else status.wire)
    }

    /** Факт применения: значение + поколение + загрузка (все три проверяются при чтении). */
    private fun markAppliedInternal(context: Context, applied: Boolean, reason: String, gen: Long) {
        val save = Prefs2.putStringEx(context, PREF_TTL_65_APPLIED,
            Toggles45.appliedOutcome(isTtl65Active(context), applied))
        Prefs2.putString(context, PREF_TTL_65_PENDING_REASON, if (applied) "" else reason)
        if (applied) {
            Prefs2.putLong(context, PREF_TTL_65_APPLIED_AT, System.currentTimeMillis())
            Prefs2.putLong(context, PREF_TTL_65_APPLIED_BOOT, bootCount(context).toLong())
            Prefs2.putLong(context, PREF_TTL_65_APPLIED_GEN, gen)
        }
        AppLog.i(TAG, "TTL_STATE desired=${desired(context)} applied=${appliedState(context)} " +
            "gen=$gen reason=«${pendingReason(context)}» save=«${save.wire()}» " +
            "(намерение владельца не меняется автоматикой)")
    }

    /**
     * Пользовательский клик. §4.2 iter47: сохранение намерения и применение — один путь
     * ([TtlRunner.request]); отдельный `ttlInFlight` удалён, параллельных shell-операций нет.
     */
    fun setTtl65Active(context: Context, enabled: Boolean): ToolsResult {
        BgWorker.assertNotMain("NetworkTtl.setTtl65Active")
        val app = ensureCtx(context)
        val t0 = SystemClock.uptimeMillis()
        val res = runner.request(enabled, "click")
        val (status, detail) = when (res.request) {
            TtlRunner.Request.CONFLICT -> ToolsStep.Status.BLOCKED to "storage_conflict"
            TtlRunner.Request.INTENT_NOT_SAVED -> ToolsStep.Status.FAILED to "intent_not_saved"
            TtlRunner.Request.QUEUED -> ToolsStep.Status.PARTIAL to "queued_after_current"
            TtlRunner.Request.STARTED ->
                if (res.confirmed) ToolsStep.Status.OK to null
                else ToolsStep.Status.FAILED to res.reason.ifEmpty { "not_confirmed" }
        }
        val (out4, out6) = ttlRuleCounts()
        val step = ToolsStep(
            step = "settings.ttl65",
            command = if (enabled) "iptables -w 5 -t mangle -A TTL --ttl-set 65"
            else "iptables -w 5 -t mangle -D (только свои правила)",
            startedAtMs = t0,
            finishedAtMs = SystemClock.uptimeMillis(),
            exitCode = if (status == ToolsStep.Status.OK) 0 else 1,
            stdout = "",
            stderr = detail ?: "",
            expected = if (enabled) "TTL_V4=1 TTL_V6=1 VERIFY=OK/OK rules=scoped"
            else "TTL_V4=0 TTL_V6=0 rules=none",
            actual = "TTL_V4=$out4 TTL_V6=$out6 gen=${res.gen} passes=${res.passes} " +
                "desired=${desired(app)} applied=${appliedState(app)}",
            status = status
        )
        return ToolsResult("settings.ttl65", "Фиксация TTL 65", listOf(step))
    }

    fun bootRestore(context: Context) {
        BgWorker.assertNotMain("NetworkTtl.bootRestore")
        val app = ensureCtx(context)
        val active = isTtl65Active(app)
        LogKit.stateTtl(active)
        // §5.3 iter46/§4.2 iter47: до проверки факт не подтверждён — честный `pending`.
        if (active) markAppliedInternal(app, false, "boot_unverified", -1L)
        val (cnt4, cnt6) = ttlRuleCounts()
        if (cnt4 < 0 || cnt6 < 0) {
            AppLog.i(TAG, "TTL: SKIP (unknown, channel down) v4=$cnt4 v6=$cnt6")
            AppLog.i(TAG, TtlRules.bootLine(0, 0) + " reason=channel_down")
            return
        }
        if (!active && cnt4 == 0 && cnt6 == 0) {
            AppLog.i(TAG, "TTL_SANITIZED removed=0 v4_removed=0 v6_removed=0 reason=start_nothing_to_remove")
            AppLog.i(TAG, "TTL: SKIP (rule absent, toggle off) v4=$cnt4 v6=$cnt6")
            markAppliedInternal(app, true, "", -1L)
            return
        }
        // §4.2: boot идёт ТЕМ ЖЕ путём, что клик/сеть (проход раннера + проверка правил).
        val res = runner.onBackgroundTrigger("boot")
        val after = ttlRuleCounts()
        AppLog.i(TAG, TtlRules.bootLine(after.first, after.second) + " reason=boot_pass " +
            "active=$active result=${res?.request} confirmed=${res?.confirmed}")
        if (res == null) {
            // Намерение прочитать нельзя — ничего не заявляем и ничего не снимаем вслепую.
            AppLog.i(TAG, "TTL_BOOT action=none desired=unknown (правила не тронуты)")
        }
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
                    // §4.2 iter47: сетевое событие идёт ТЕМ ЖЕ путём, что клик — через
                    // сериализованный раннер. Отдельного `ttlInFlight` больше нет, поэтому
                    // сетевой ON не может пересечься с ручным OFF: проходы не параллельны.
                    val now = SystemClock.uptimeMillis()
                    val since = now - ttlLastRunAtMs
                    if (since in 0 until 30_000L) {
                        // Шторм переподключений: проход не запускаем, но событие НЕ теряем —
                        // один отложенный проход по последнему намерению.
                        AppLog.i(TAG, "TTL_RECEIVER deferred reason=window sinceMs=$since " +
                            "action=schedule_once delay_ms=${30_000L - since} wifi=$isWifi")
                        networkRecheckOnce(rcvCtx, 30_000L - since)
                        return@post
                    }
                    val res = runner.onBackgroundTrigger("network:ttl=on")
                    AppLog.i(TAG, "TTL_RECEIVER ran result=${res?.request} confirmed=${res?.confirmed} " +
                        "desired=${desired(rcvCtx)} applied=${appliedState(rcvCtx)} wifi=$isWifi")
                    if (res != null && !res.confirmed) {
                        AppLog.i(TAG, "TTL_PENDING reason=«${res.reason}» desired=true applied=pending " +
                            "action=wait_network (тумблер остаётся включённым; повтор — по следующему " +
                            "событию сети, не по таймеру)")
                    }
                    runCatching {
                        JitNet.requestEventSnapshot(
                            rcvCtx, "ttl_network_change", NetInfoAndroid(rcvCtx)
                        )
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
        // §4.2: отложенный повтор — тот же путь. При OFF проход снимает только свои правила
        // (санация) и НИКОГДА не включает Wi-Fi; включать TTL «на всякий случай» нечего.
        val res = runner.onBackgroundTrigger("network_recheck")
        AppLog.i(TAG, "TTL_NET_REAPPLY result=${res?.request} confirmed=${res?.confirmed} " +
            "desired=${desired(ctx)} applied=${appliedState(ctx)}")
    }

    @JvmStatic fun unregisterNetworkReceiver(context: Context) {
        val ctx = context.applicationContext
        val recv = networkReceiver ?: return
        runCatching { ctx.unregisterReceiver(recv) }
        networkReceiver = null
        AppLog.i(TAG, "network receiver unregistered")
    }
}
