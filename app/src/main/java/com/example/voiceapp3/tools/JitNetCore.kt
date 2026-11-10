package com.example.voiceapp3.tools

/**
 * iter33p (1.0.61, перезапуск задачи) — ЧИСТОЕ ядро ОДНОЙ операции JIT и сети вокруг неё.
 *
 * Почему файл переписан, а не дополнен: в fix2…fix4 вокруг `ctl.restart zygote` вырос отдельный
 * 8-секундный watcher, общий файл-отмена и чтение строки `ARMED` как доказательства. Ревью
 * справедливо назвало это лишним механизмом и источником гонок. Здесь осталась ОДНА цепочка,
 * как в референсе (`w0/la.run` → `w0/e2.run`):
 *
 *   1) снимок операции (id, загрузка ГУ, исходные Wi-Fi/AP, цель JIT, pid zygote, прежний usejit)
 *      пишется ОДНОЙ записью и подтверждается чтением ДО сетевых изменений;
 *   2) ОДИН shell-скрипт: `svc wifi disable` → ожидание ухода `oif wlan0` ≤20 с →
 *      `setprop dalvik.vm.usejit` (+ ранее согласованный `persist.dalvik.vm.usejit`) →
 *      `setprop ctl.restart zygote` последней командой; по ходу скрипт дописывает в СВОЙ журнал
 *      строки `JITSTEP op=<id> step=<имя> …`;
 *   3) после подъёма процесса журнал и pid zygote дают ЧЕСТНЫЙ разбор: рестарт подтверждён
 *      сменой pid, а не строкой «restart_sent»; возврат Wi-Fi/AP — продолжение этой же операции.
 *
 * Чего здесь нет и почему:
 *  • нет watcher-а с `sleep`/`nohup`/файлом-отменой — нечего отменять: рестарт это последняя
 *    команда скрипта, после неё нам ничего не нужно;
 *  • нет `pkill`/`pgrep -f`/убийства процессов (границы владельца);
 *  • нет удаления `ip rule`: референс правил не удаляет, а безопасно идентифицировать «своё»
 *    правило на ГУ нечем — только read-only диагностика (`JIT_NET_RULES … action=none`);
 *  • нет `dalvik.vm.usejitprofiles`: в JIT-цепочке `la.run` его нет;
 *  • нет `umount -l` точек `/data/app/`: чужие монтирования не трогаем.
 *
 * Один неопределённый случай честно оставлен человеку: снимок есть, а журнал пуст и сеть уже не
 * та, что была. Тогда — `HOLD_UNKNOWN`, ноль сетевых действий и строка «нужно решение владельца».
 */
object JitNetModel {

    /** Один снимок одной операции (замена прежним `jit_net_saved` + файлу-намерения). */
    internal const val PREF_OP = "jit_op"
    /** Итог последнего разбора (диагностика, не участвует в решениях). */
    internal const val PREF_LAST = "jit_op_last"

    internal const val DRAIN_TIMEOUT_S = 20
    internal const val WIFI_RESTORE_ATTEMPTS = 3
    internal const val WIFI_RESTORE_PAUSE_MS = 3_000L
    internal const val COMPENSATE_ATTEMPTS = 2
    /** Сколько операция считается «свежей» (дальше авто-возврат запрещён — решает владелец). */
    internal const val OPERATION_TTL_MS = 10 * 60_000L
    /**
     * Окно позднего рестарта: скрипт живёт не дольше слива (≤20 с) + запас. Пока окно не истекло,
     * «рестарта не будет» не доказано, и сеть трогать нельзя.
     */
    internal const val LATE_RESTART_WINDOW_MS = 60_000L
    internal const val CHAIN_TIMEOUT_MS = 45_000

    const val PROP_USEJIT = "dalvik.vm.usejit"
    /** Согласованный владельцем init-ключ JIT (использовался и до этой итерации). */
    const val PROP_PERSIST_USEJIT_INIT = "persist.dalvik.vm.usejit"

    /** Журнал шагов операции — НАШ собственный временный файл (границы владельца это разрешают). */
    internal const val JOURNAL_DIR = "/data/local/tmp"
    internal const val JOURNAL_PREFIX = "jitop_"

    fun journalPath(opId: String): String = "$JOURNAL_DIR/$JOURNAL_PREFIX$opId.log"

    /** Снимок операции. Формат — плоские поля через `;`, разбор строгий (никаких подстрок). */
    data class Op(
        val id: String,
        val bootTag: String,
        val wifi: Boolean,
        val ap: Boolean,
        val want: String,
        val zygotePid: String,
        val usejitBefore: String,
        val tsMs: Long
    ) {
        fun wire(): String =
            "op=$id;boot=$bootTag;wifi=${if (wifi) 1 else 0};ap=${if (ap) 1 else 0};want=$want;" +
                "zygote=${zygotePid.ifEmpty { "none" }};rt=${usejitBefore.ifEmpty { "none" }};ts=$tsMs"

        fun log(): String =
            "op=$id boot=$bootTag wifi=${if (wifi) 1 else 0} ap=${if (ap) 1 else 0} want=$want " +
                "zygote_before=${zygotePid.ifEmpty { "none" }} rt_before=${usejitBefore.ifEmpty { "empty" }}"
    }

    fun parseOp(saved: String?): Op? {
        if (saved.isNullOrBlank()) return null
        fun f(name: String): String? =
            Regex("(?:^|;)$name=([^;]*)").find(saved)?.groupValues?.get(1)
        val id = f("op")?.takeIf { it.isNotBlank() } ?: return null
        val boot = f("boot")?.takeIf { it.isNotBlank() } ?: return null
        val want = f("want")?.takeIf { it == "true" || it == "false" } ?: return null
        val wifi = when (f("wifi")) { "1" -> true; "0" -> false; else -> return null }
        val ap = when (f("ap")) { "1" -> true; "0" -> false; else -> return null }
        val zygote = f("zygote").orEmpty().let { if (it == "none") "" else it }
        val rt = f("rt").orEmpty().let { if (it == "none") "" else it }
        val ts = f("ts")?.toLongOrNull() ?: 0L
        return Op(id, boot, wifi, ap, want, zygote, rt, ts)
    }

    /** Разбор read-only пробы `ap0`: `P=` наличие интерфейса, `OS=` operstate, `A=` число адресов. */
    fun parseApProbe(out: String?): Boolean? {
        if (out.isNullOrBlank()) return null
        if (out.contains("ERR_")) return null
        if (!out.contains("P=") || !out.contains("OS=") || !out.contains("A=")) return null
        val p = Regex("P=([01])\\b").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (p == 0) return false
        val a = Regex("A=([0-9]+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return a > 0
    }

    /** Проба AP — только чтение (как `CentralWatchdogService.v()` референса). */
    internal fun apProbeCommand(): String {
        val D = "$"
        return "echo P=${D}([ -d /sys/class/net/ap0 ] && echo 1 || echo 0); " +
            "echo OS=${D}(cat /sys/class/net/ap0/operstate 2>/dev/null); " +
            "echo A=${D}(ip -o -4 addr show ap0 2>/dev/null | grep -c 'inet ')"
    }

    /** Диагностика правил: ТОЛЬКО подсчёт, ни одного `ip rule del|flush`. */
    internal fun rulesProbeCommand(): String {
        val D = "$"
        return "echo OIF=${D}(ip rule show 2>/dev/null | grep -c 'oif wlan0')"
    }

    /**
     * ЕДИНСТВЕННЫЙ скрипт операции (референс `w0/la.run` без `umount` и без `usejitprofiles`).
     *
     * Порядок обязателен: гасим Wi-Fi → ждём слива правил `oif wlan0` (≤20 с) → ставим usejit →
     * перезапуск фреймворка ПОСЛЕДНЕЙ командой. Если рестарт уйдёт раньше слива, после подъёма
     * останутся висящие правила и получится «Wi-Fi подключён, страницы не грузятся».
     *
     * Все строки журнала пишутся ДО рестарта: после `setprop ctl.restart zygote` от скрипта ничего
     * не требуется, поэтому его жизнь после смерти нашего процесса не важна.
     */
    internal fun jitChainScript(opId: String, want: String, journal: String): String {
        val D = "$"
        return """
            #!/system/bin/sh
            OP='$opId'
            J='$journal'
            say() { echo "JITSTEP op=${D}OP ${D}1" >> "${D}J"; }
            say "step=start"
            svc wifi disable >/dev/null 2>&1
            n=${D}(ip rule show 2>/dev/null | grep -c 'oif wlan0')
            say "step=wifi_disabled oif_wlan0=${D}n"
            i=0
            while [ "${D}n" != "0" ] && [ ${D}i -lt $DRAIN_TIMEOUT_S ]; do
              sleep 1
              i=${D}((i+1))
              n=${D}(ip rule show 2>/dev/null | grep -c 'oif wlan0')
            done
            say "step=drained wait=${D}i oif_wlan0=${D}n"
            setprop $PROP_USEJIT $want 2>/dev/null
            say "step=usejit rt=${D}(getprop $PROP_USEJIT) want=$want"
            setprop $PROP_PERSIST_USEJIT_INIT $want 2>/dev/null
            say "step=restart_sent"
            setprop ctl.restart zygote
        """.trimIndent()
    }

    /**
     * Откат ТОЛЬКО прежнего значения usejit (если операция подтверждённо не дошла до рестарта).
     * Ключ тот же, что ставила цепочка; значение возвращается к прочитанному до операции.
     */
    internal fun propsRollbackScript(usejitBefore: String): String {
        val D = "$"
        return """
            #!/system/bin/sh
            # iter33p: операция не дошла до рестарта — возвращаем прежнее значение usejit
            setprop $PROP_USEJIT "${usejitBefore}" 2>/dev/null
            setprop $PROP_PERSIST_USEJIT_INIT "${usejitBefore}" 2>/dev/null
            echo "JIT_ROLLBACK rt=${D}(getprop $PROP_USEJIT) pr=${D}(getprop $PROP_PERSIST_USEJIT_INIT) want_rt=$usejitBefore"
        """.trimIndent()
    }

    // ─────────────────────────────── журнал шагов ───────────────────────────────

    internal const val JOURNAL_TOKEN = "JITSTEP"

    /** Имена шагов (порядок важен: [phaseOf] берёт самый поздний достигнутый). */
    internal const val STEP_START = "start"
    internal const val STEP_WIFI_DISABLED = "wifi_disabled"
    internal const val STEP_DRAINED = "drained"
    internal const val STEP_USEJIT = "usejit"
    internal const val STEP_RESTART_SENT = "restart_sent"

    /**
     * Разбор журнала. Строка засчитывается только если её поле `op` РАВНО id операции: строки
     * прошлых операций (и любые чужие) не подтверждают нашу.
     */
    fun journalSteps(text: String?, opId: String): Map<String, String> {
        if (text.isNullOrBlank() || opId.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (raw in text.split('\n', '|')) {
            val line = raw.trim()
            if (!line.startsWith(JOURNAL_TOKEN + " ")) continue
            val opField = Regex("(?:^|\\s)op=(\\S+)").find(line)?.groupValues?.get(1) ?: continue
            if (opField != opId) continue
            val step = Regex("(?:^|\\s)step=(\\S+)").find(line)?.groupValues?.get(1) ?: continue
            out[step] = line
        }
        return out
    }

    /** Самая поздняя достигнутая фаза операции. */
    fun phaseOf(steps: Map<String, String>): JitFlow.Phase = when {
        steps.containsKey(STEP_RESTART_SENT) -> JitFlow.Phase.RESTART_SENT
        steps.containsKey(STEP_USEJIT) -> JitFlow.Phase.USEJIT_SET
        steps.containsKey(STEP_DRAINED) -> JitFlow.Phase.DRAINED
        steps.containsKey(STEP_WIFI_DISABLED) -> JitFlow.Phase.WIFI_DISABLED
        steps.containsKey(STEP_START) -> JitFlow.Phase.PREPARED
        else -> JitFlow.Phase.NONE
    }
}

/** Хранилище снимка операции: в приложении — SharedPreferences, в тестах — память. */
interface NetPrefs {
    fun get(key: String): String?
    /** `true` — запись подтверждена чтением обратно. */
    fun put(key: String, value: String?): Boolean
}

/** Результат выполнения device-скрипта. */
data class ScriptOut(val exitCode: Int, val output: String) {
    val channelOk: Boolean get() = output.isNotBlank() && !output.contains("ERR_")
}

/**
 * Итог возврата сети. «Подтверждено» относится только к запрошенному состоянию: это НЕ значит
 * «интернет есть» (`link` — отдельный read-only факт).
 */
data class RestoreResult(
    val wifiWasRequested: Boolean,
    val wifiConfirmed: Boolean,
    val apWasRequested: Boolean,
    val apConfirmed: Boolean,
    val link: String,
    /** Число правил `oif wlan0` (диагностика; -1 — данных нет). Правила НЕ удаляются. */
    val oifWlan0: Int
) {
    private val linkRequired: Boolean get() = wifiWasRequested
    private val linkOk: Boolean get() = !linkRequired || link == "up"

    val essentialsConfirmed: Boolean get() = wifiConfirmed && apConfirmed
    val fullyRestored: Boolean get() = essentialsConfirmed && linkOk
    val canClearMarker: Boolean get() = fullyRestored

    fun log(): String = "wifi_confirmed=$wifiConfirmed ap_confirmed=$apConfirmed link=$link " +
        "oif_wlan0=$oifWlan0 essentials=$essentialsConfirmed fully=$fullyRestored " +
        "required=link:${if (linkRequired) 1 else 0} (ip rule: read-only, не удаляем)"
}

/** Итог разбора незавершённой операции. */
enum class Recovery { NONE, CONTINUE, COMPENSATE, DISCARDED, HELD }

/**
 * Чистые решения операции JIT. Ни одного `android.*` — всё проверяется JVM-тестами.
 */
object JitFlow {

    /** Фаза операции, доказанная журналом (не «намерением»). */
    enum class Phase { NONE, PREPARED, WIFI_DISABLED, DRAINED, USEJIT_SET, RESTART_SENT }

    /**
     * Что делать на старте процесса с незавершённой операцией.
     *
     *  • `CONTINUE_RESTORE`   — рестарт фреймворка состоялся (pid zygote сменился): возврат сети —
     *                           продолжение ТОЙ ЖЕ операции, без второго клика;
     *  • `COMPENSATE_RESTORE` — рестарт доказанно не состоится (окно позднего рестарта истекло или
     *                           была перезагрузка ГУ): возвращаем сеть к сохранённому состоянию;
     *  • `DISCARD_NO_EFFECT`  — сеть не менялась (снимок без журнала и Wi-Fi как был): снимок
     *                           просто снимается, сетевых команд нет;
     *  • `HOLD_UNKNOWN`       — доказать нечего: НИЧЕГО не трогаем, снимок остаётся,
     *                           строка `JIT_OP_HOLD … need=owner_decision`;
     *  • `NONE`               — операции нет.
     */
    enum class PendingAction { NONE, CONTINUE_RESTORE, COMPENSATE_RESTORE, DISCARD_NO_EFFECT, HOLD_UNKNOWN }

    /** id операции в шелл-безопасном виде (используется и в имени журнала). */
    fun opId(nowMs: Long, bootTag: String, salt: String): String {
        val clean = { s: String -> s.filter { it.isLetterOrDigit() || it == '-' }.take(12) }
        val tag = clean(bootTag).ifEmpty { "boot" }
        val s = clean(salt).ifEmpty { "0" }
        return "op-$tag-${nowMs.toString(36)}-$s"
    }

    /** Снимок и текущий процесс — одна и та же загрузка ГУ. */
    fun sameBoot(opBootTag: String, currentTag: String): Boolean =
        opBootTag.isNotBlank() && currentTag.isNotBlank() &&
            JitBootMark.sameLoad("${opBootTag}:0", currentTag)

    /** Операция старше [ttlMs] — авто-возврат запрещён (решает владелец). */
    fun expired(ageMs: Long, ttlMs: Long = JitNetModel.OPERATION_TTL_MS): Boolean = ageMs > ttlMs

    /**
     * Решение по незавершённой операции. Аргументы — только read-only факты:
     *  • [phase] — по журналу нашей операции;
     *  • [sameBoot] — загрузка ГУ та же;
     *  • [netAlreadyAsSaved] — Wi-Fi/AP сейчас ровно те, что сохранены (значит сеть не менялась);
     *  • [zygoteRestarted] — `true` (pid сменился), `false` (pid тот же), `null` (нет данных);
     *  • [ageMs] — сколько прошло с сохранения снимка.
     */
    fun pendingAction(
        phase: Phase,
        sameBoot: Boolean,
        netAlreadyAsSaved: Boolean,
        zygoteRestarted: Boolean?,
        ageMs: Long,
        lateWindowMs: Long = JitNetModel.LATE_RESTART_WINDOW_MS,
        ttlMs: Long = JitNetModel.OPERATION_TTL_MS,
        /** `false` — shell-скрипт уже вернул управление: позднего рестарта быть не может. */
        lateRestartPossible: Boolean = true
    ): PendingAction {
        // Скрипт не оставил ни одной строки: сеть либо не тронута, либо доказательств нет.
        if (phase == Phase.NONE) {
            return if (netAlreadyAsSaved) DISCARD else HOLD
        }
        if (expired(ageMs, ttlMs)) return HOLD          // слишком старая операция: решает владелец
        if (!sameBoot) {
            // Перезагрузка ГУ исключает поздний рестарт: остаётся только компенсация сети.
            return if (phase == Phase.RESTART_SENT && zygoteRestarted == true) CONTINUE else COMPENSATE
        }
        if (zygoteRestarted == true && phase != Phase.PREPARED) return CONTINUE
        return when (phase) {
            Phase.PREPARED, Phase.WIFI_DISABLED, Phase.DRAINED, Phase.USEJIT_SET ->
                // Рестарта ещё не было. Пока окно не истекло и скрипт не вернул управление,
                // поздний рестарт ещё возможен — тогда сеть не трогаем.
                if (lateRestartPossible && ageMs <= lateWindowMs) HOLD else COMPENSATE
            Phase.RESTART_SENT ->
                // Рестарт отправлен. Ждём: если процесс жив спустя окно (или скрипт уже
                // завершился) — команда не привела к смене фреймворка.
                if (lateRestartPossible && ageMs <= lateWindowMs) HOLD else COMPENSATE
            Phase.NONE -> if (netAlreadyAsSaved) DISCARD else HOLD
        }
    }

    private val DISCARD = PendingAction.DISCARD_NO_EFFECT
    private val HOLD = PendingAction.HOLD_UNKNOWN
    private val CONTINUE = PendingAction.CONTINUE_RESTORE
    private val COMPENSATE = PendingAction.COMPENSATE_RESTORE

    fun actionLabel(a: PendingAction): String = when (a) {
        PendingAction.NONE -> "none"
        PendingAction.CONTINUE_RESTORE -> "continue_restore"
        PendingAction.COMPENSATE_RESTORE -> "compensate_restore"
        PendingAction.DISCARD_NO_EFFECT -> "discard_no_effect"
        PendingAction.HOLD_UNKNOWN -> "hold_unknown"
    }

    fun phaseLabel(p: Phase): String = p.name.lowercase()

    /**
     * Подтверждение рестарта фреймворка: ТОЛЬКО по смене pid zygote.
     * `ok` — pid другой; `no` — pid тот же; `unknown` — pid не прочитан.
     */
    fun restartVerdict(before: String, now: String): String {
        if (before.isBlank() || now.isBlank()) return "unknown"
        if (!before.all { it.isDigit() } || !now.all { it.isDigit() }) return "unknown"
        return if (before != now) "ok" else "no"
    }

    /**
     * Общий вердикт применения JIT. Поля раздельные, «нет данных» не превращается в успех:
     * getprop — что читается сейчас, maps — виден ли `dalvik-jit-code-cache`, restart — смена pid.
     */
    fun verdict(getprop: String, want: String, maps: Boolean?, restart: String): String = when {
        getprop.isBlank() || want.isBlank() -> "unknown"
        getprop != want -> "not_applied"
        restart != "ok" -> "unknown"          // значение совпало, но фреймворк не перезапущен
        want == "false" -> "applied"          // JIT выключен — проверка кэша не нужна
        maps == true -> "applied"
        else -> "unverified"                  // getprop true, рестарт был, кэш в maps не виден
    }

    /** Можно ли снимать снимок операции: только когда всё запрошенное подтверждено. */
    fun canClearOp(res: RestoreResult): Boolean = res.canClearMarker
}

/**
 * Состояние операции JIT: снимок, журнал, возврат сети. Всё внешнее — лямбды, поэтому «процесс
 * умер», «поздний рестарт», «журнал пуст», «Wi-Fi не поднялся» проверяются обычными JVM-тестами.
 */
class JitNetCore(
    private val prefs: NetPrefs,
    private val readWifi: () -> Boolean?,
    private val setWifi: (Boolean) -> Unit,
    private val apProbe: () -> String?,
    private val link: (String) -> String,
    private val readZygotePid: () -> String?,
    private val readJournal: (String) -> String?,
    private val purgeJournals: (String?) -> Int,
    private val runScript: (String, Int) -> ScriptOut,
    private val log: (String) -> Unit,
    private val now: () -> Long,
    private val sleep: (Long) -> Unit,
    private val ttlMs: Long = JitNetModel.OPERATION_TTL_MS,
    private val wifiAttempts: Int = JitNetModel.WIFI_RESTORE_ATTEMPTS,
    private val wifiPauseMs: Long = JitNetModel.WIFI_RESTORE_PAUSE_MS,
    private val lateWindowMs: Long = JitNetModel.LATE_RESTART_WINDOW_MS
) {
    internal var startSoftAp: () -> Unit = { throw UnsupportedOperationException("no_startSoftAp") }

    fun currentOp(): JitNetModel.Op? = JitNetModel.parseOp(prefs.get(JitNetModel.PREF_OP))

    /** Текущее состояние сети; `null` — канал не даёт ответа (операция не начинается). */
    fun readState(): Pair<Boolean, Boolean>? {
        val wifi = readWifi() ?: return null
        val ap = JitNetModel.parseApProbe(apProbe()) ?: return null
        return wifi to ap
    }

    /**
     * Шаг 1 операции: снимок ДО любых сетевых изменений.
     *
     * Незавершённая операция не перезаписывается: если снимок уже есть и он не «мёртвый»,
     * новый клик получает отказ (второй клик не должен затирать состояние, нужное для возврата).
     */
    fun saveOperation(bootTag: String, want: String, opId: String): JitNetModel.Op? {
        val prev = currentOp()
        if (prev != null) {
            log("JIT_OP_SAVE refused reason=pending_operation " + prev.log() +
                " (сначала разбирается предыдущая операция — см. JIT_OP_*)")
            return null
        }
        val st = readState()
        if (st == null) {
            log("JIT_OP_SAVE refused reason=net_state_unknown " +
                "(канал Wi-Fi/проба ap0 недоступны — сеть не трогаем)")
            return null
        }
        val zpid = runCatching { readZygotePid() }.getOrNull().orEmpty().trim()
        val rt = runCatching { readUsejit() }.getOrDefault("").trim()
        val op = JitNetModel.Op(
            id = opId, bootTag = bootTag, wifi = st.first, ap = st.second,
            want = want, zygotePid = zpid, usejitBefore = rt, tsMs = now()
        )
        val ok = prefs.put(JitNetModel.PREF_OP, op.wire())
        val back = currentOp()
        if (!ok || back?.wire() != op.wire()) {
            prefs.put(JitNetModel.PREF_OP, null)
            log("JIT_OP_SAVE failed confirmed=no want=${op.wire()} got=${back?.wire() ?: "none"} " +
                "(сеть не трогаем, рестарта не будет)")
            return null
        }
        log("JIT_OP_SAVE ${op.log()} confirmed=yes (снимок одной операции, до сетевых изменений)")
        return op
    }

    private fun readUsejit(): String = runCatching {
        runScript("echo RT=\$(getprop ${JitNetModel.PROP_USEJIT})", 5_000).output
    }.getOrDefault("").let { out ->
        Regex("RT=([^\n|]*)").find(out)?.groupValues?.get(1)?.trim().orEmpty()
    }

    /** Снять снимок МОЖНО только свой (по id) — старые задачи не удаляют новую операцию. */
    fun clearOp(opId: String): Boolean {
        val cur = currentOp()
        if (cur == null) return true
        if (cur.id != opId) {
            log("JIT_OP_CLEAR refused reason=foreign_op keep=${cur.id} asked=$opId")
            return false
        }
        val ok = prefs.put(JitNetModel.PREF_OP, null) && currentOp() == null
        log("JIT_OP_CLEAR op=$opId cleared=${if (ok) "yes" else "no"}")
        return ok
    }

    /** Удаление наших старых журналов (кроме текущей операции). Только собственные файлы. */
    fun purgeOldJournals(keepOpId: String?) {
        val n = runCatching { purgeJournals(keepOpId) }.getOrDefault(0)
        log("JIT_JOURNAL purge removed=$n keep=${keepOpId ?: "none"} dir=${JitNetModel.JOURNAL_DIR}")
    }

    /** Запуск ЕДИНСТВЕННОЙ цепочки. Скрипт возвращает управление сам; рестарт — его последняя команда. */
    fun runChain(op: JitNetModel.Op, journal: String): ScriptOut {
        log("JIT_CHAIN_SENT op=${op.id} want=${op.want} journal=$journal " +
            "timeout_ms=${JitNetModel.CHAIN_TIMEOUT_MS} " +
            "order=\"svc wifi disable -> drain oif wlan0 <=${JitNetModel.DRAIN_TIMEOUT_S}s -> " +
            "setprop ${JitNetModel.PROP_USEJIT}=${op.want} -> setprop ctl.restart zygote\"")
        val out = runCatching {
            runScript(JitNetModel.jitChainScript(op.id, op.want, journal), JitNetModel.CHAIN_TIMEOUT_MS)
        }.getOrElse { ScriptOut(1, "ERR_${it.javaClass.simpleName}") }
        log("JIT_CHAIN_DONE op=${op.id} rc=${out.exitCode} channel=${if (out.channelOk) "ok" else "failed"} " +
            "out=\"${out.output.replace('\n', ' ').trim().take(200)}\"")
        return out
    }

    /** Фаза операции по журналу (только чтение). */
    fun phaseOf(op: JitNetModel.Op): JitFlow.Phase {
        val text = runCatching { readJournal(JitNetModel.journalPath(op.id)) }.getOrNull()
        return JitNetModel.phaseOf(JitNetModel.journalSteps(text, op.id))
    }

    /**
     * Разбор незавершённой операции на старте процесса / в начале нового клика.
     *
     * Сетевые команды выполняются ТОЛЬКО по решению [JitFlow.pendingAction]; в состоянии
     * `HOLD_UNKNOWN` их нет вообще.
     */
    fun reconcilePending(
        currentBootTag: String,
        context: String,
        compensateAttempts: Int = wifiAttempts,
        lateRestartPossible: Boolean = true
    ): Recovery {
        val op = currentOp()
        if (op == null) {
            log("JIT_OP_NONE context=$context (незавершённой операции нет — сети не касаемся)")
            return Recovery.NONE
        }
        val phase = phaseOf(op)
        val sameBoot = JitFlow.sameBoot(op.bootTag, currentBootTag)
        val zNow = runCatching { readZygotePid() }.getOrNull().orEmpty().trim()
        val restarted: Boolean? = when (JitFlow.restartVerdict(op.zygotePid, zNow)) {
            "ok" -> true
            "no" -> false
            else -> null
        }
        val cur = readState()
        val asSaved = cur != null && cur.first == op.wifi && cur.second == op.ap
        val age = (now() - op.tsMs).coerceAtLeast(0L)
        val action = JitFlow.pendingAction(
            phase, sameBoot, asSaved, restarted, age, lateWindowMs, ttlMs, lateRestartPossible)
        log("JIT_OP_EVAL context=$context ${op.log()} phase=${JitFlow.phaseLabel(phase)} " +
            "same_boot=$sameBoot age_ms=$age zygote_now=${zNow.ifEmpty { "none" }} " +
            "restart=${JitFlow.restartVerdict(op.zygotePid, zNow)} net_as_saved=$asSaved " +
            "late_restart_possible=$lateRestartPossible " +
            "state=${cur?.let { "wifi=${if (it.first) 1 else 0} ap=${if (it.second) 1 else 0}" } ?: "unknown"} " +
            "action=${JitFlow.actionLabel(action)}")
        return when (action) {
            JitFlow.PendingAction.NONE -> Recovery.NONE
            JitFlow.PendingAction.DISCARD_NO_EFFECT -> {
                clearOp(op.id)
                log("JIT_OP_DISCARD op=${op.id} reason=net_untouched context=$context " +
                    "(снимок снят, сетевых команд не было)")
                Recovery.DISCARDED
            }
            JitFlow.PendingAction.HOLD_UNKNOWN -> {
                prefs.put(JitNetModel.PREF_LAST, "hold phase=${phase.name.lowercase()} age_ms=$age")
                log("JIT_OP_HOLD context=$context op=${op.id} phase=${JitFlow.phaseLabel(phase)} " +
                    "age_ms=$age action=none need=owner_decision " +
                    "(доказательств фазы нет; сеть не трогаем, снимок сохранён, поздний рестарт не исключён)")
                Recovery.HELD
            }
            JitFlow.PendingAction.CONTINUE_RESTORE -> {
                log("JIT_OP_CONTINUE context=$context op=${op.id} reason=restart_confirmed " +
                    "(возврат сети — продолжение этой же операции, второго клика не нужно)")
                restoreAndMaybeClear(op, "chain:$context", wifiAttempts)
            }
            JitFlow.PendingAction.COMPENSATE_RESTORE -> {
                val rollback = runCatching { rollbackProps(op.usejitBefore) }.getOrDefault(false)
                log("JIT_OP_COMPENSATE context=$context op=${op.id} reason=restart_not_happening " +
                    "props_rollback=${if (rollback) "ok" else "unknown"} " +
                    "(возвращаем сеть к сохранённому состоянию, zygote НЕ перезапускаем)")
                restoreAndMaybeClear(op, "compensate:$context", compensateAttempts)
            }
        }
    }

    private fun restoreAndMaybeClear(op: JitNetModel.Op, why: String, attempts: Int): Recovery {
        val res = restore(op, why, attempts)
        prefs.put(JitNetModel.PREF_LAST, res.log() + " why=$why")
        return if (JitFlow.canClearOp(res)) {
            clearOp(op.id)
            log("JIT_OP_DONE op=${op.id} why=$why marker=cleared")
            if (why.startsWith("chain:")) Recovery.CONTINUE else Recovery.COMPENSATE
        } else {
            log("JIT_OP_KEEP op=${op.id} why=$why (подтверждено не всё — снимок остаётся)")
            Recovery.HELD
        }
    }

    /** Возврат прежнего значения usejit (только если операция не дошла до рестарта). */
    fun rollbackProps(usejitBefore: String): Boolean {
        if (usejitBefore.isBlank()) {
            log("JIT_ROLLBACK skipped reason=no_before_value")
            return false
        }
        val out = runScript(JitNetModel.propsRollbackScript(usejitBefore), 20_000)
        val line = out.output.lineSequence().firstOrNull { it.contains("JIT_ROLLBACK ") }?.trim()
        log(line ?: "JIT_ROLLBACK failed rc=${out.exitCode}")
        fun tag(name: String): String? =
            Regex("(?:^|\\s)$name=(\\S+)").find(line.orEmpty())?.groupValues?.get(1)
        // Сравнение по точному тегу: подстрочное `contains("rt=false")` совпадает с `want_rt=false`.
        return tag("rt") == usejitBefore && tag("pr") == usejitBefore
    }

    /** Wi-Fi/AP — только к сохранённому состоянию. Диагностика правил read-only. */
    fun restore(op: JitNetModel.Op, why: String, attempts: Int = wifiAttempts): RestoreResult {
        val rules = runScript(JitNetModel.rulesProbeCommand(), 8_000).output
        val oif = Regex("OIF=([0-9]+)").find(rules)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        log("JIT_NET_RULES oif_wlan0=$oif action=none context=$why " +
            "(правила только считаем: удаление не доказано ни референсом, ни полем)")

        // Порядок как в `w0/e2.run`: сначала точка доступа, затем Wi-Fi.
        val apConfirmed = if (op.ap) restoreAp(why) else {
            log("JIT_NET_RESTORE ap=0 result=skipped reason=was_off context=$why")
            true
        }
        val wifiConfirmed = if (op.wifi) restoreWifi(attempts, why) else {
            log("JIT_NET_RESTORE wifi=0 result=skipped reason=was_off context=$why")
            true
        }
        val linkNow = runCatching { link("wlan0") }.getOrDefault("unknown")
        val res = RestoreResult(
            wifiWasRequested = op.wifi, wifiConfirmed = wifiConfirmed,
            apWasRequested = op.ap, apConfirmed = apConfirmed,
            link = linkNow, oifWlan0 = oif
        )
        log("JIT_NET_RESTORE context=$why saved=wifi=${if (op.wifi) 1 else 0},ap=${if (op.ap) 1 else 0} ${res.log()}")
        return res
    }

    /** Запрос и фактическое состояние пишутся раздельно: успех — только по read-back. */
    private fun restoreWifi(attempts: Int, why: String): Boolean {
        for (attempt in 1..attempts) {
            val before = readWifi()
            var request = "already_on"
            if (before != true) {
                setWifi(true)
                request = "setWifiEnabled(true)"
            }
            val state = readWifi()
            log("JIT_NET_RESTORE wifi=1 attempt=$attempt/$attempts request=$request " +
                "state=${state?.toString() ?: "unknown"} link=${runCatching { link("wlan0") }.getOrDefault("unknown")} " +
                "context=$why")
            if (state == true) return true
            if (attempt < attempts) sleep(wifiPauseMs)
        }
        log("JIT_NET_RESTORE wifi=1 attempts=$attempts result=failed context=$why " +
            "(запрос отправлен, состояние не подтверждено — это НЕ «интернет восстановлен»)")
        return false
    }

    private fun restoreAp(why: String): Boolean {
        val before = JitNetModel.parseApProbe(apProbe())
        if (before == true) {
            log("JIT_NET_RESTORE ap=1 result=already_up context=$why")
            return true
        }
        val err = runCatching { startSoftAp() }.exceptionOrNull()
        val after = JitNetModel.parseApProbe(apProbe())
        log("JIT_NET_RESTORE ap=1 request=${if (err == null) "startSoftAp" else "failed"} " +
            "state=${after?.toString() ?: "unknown"} reason=${err?.javaClass?.simpleName ?: "none"} context=$why")
        return after == true
    }
}
