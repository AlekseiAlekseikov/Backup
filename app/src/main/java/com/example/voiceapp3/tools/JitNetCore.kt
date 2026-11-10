package com.example.voiceapp3.tools

/**
 * iter33p-fix2 §1/§2 — ЧИСТОЕ ядро сети вокруг рестарта фреймворка.
 *
 * Почему отдельный файл: REVIEW iter33p-fix1 (замечание 1) справедливо указал, что тесты сети
 * проверяли исходный текст. Здесь нет ни `android.*`, ни `AdbClient`: все внешние эффекты —
 * лямбды, поэтому сценарии «процесс убили между disable и компенсацией», «следующий старт»,
 * «Wi-Fi не поднялся», «ap0 отсутствует штатно» прогоняются обычными JVM-тестами.
 *
 * Инварианты:
 *  1. состояние сети сохраняется ДО любых изменений и подтверждается чтением обратно;
 *  2. маркер `jit_net_saved` — это отдельное состояние НЕЗАВЕРШЁННОГО возврата: он снимается
 *     только когда запрошенное состояние подтверждено; пока он есть — следующий старт обязан
 *     попробовать возврат снова (даже если файла-намерения JIT уже нет);
 *  3. «запрос ≠ состояние»: в логе отдельно `request=`, `state=`, `link=`;
 *  4. отсутствие `ap0` — ШТАТНЫЙ признак выключенной точки доступа, а не «неизвестно»;
 *     неизвестно — только когда канал не отвечает;
 *  5. подтверждение Wi-Fi/AP не равно «интернет есть»: `link=` и `sanitize_left=` пишутся отдельно.
 */
object JitNetModel {

    internal const val PREF_NET_SAVED = "jit_net_saved"
    internal const val PREF_NET_LAST = "jit_net_last"     // итог последнего возврата (диагностика)
    internal const val DRAIN_TIMEOUT_S = 20
    internal const val WIFI_RESTORE_ATTEMPTS = 3
    internal const val WIFI_RESTORE_PAUSE_MS = 3_000L
    internal const val COMPENSATE_ATTEMPTS = 2
    internal const val SAVED_TTL_MS = 10 * 60_000L

    const val PROP_USEJIT = "dalvik.vm.usejit"
    const val PROP_USEJIT_PROFILES = "dalvik.vm.usejitprofiles"
    const val PROP_PERSIST_USEJIT_INIT = "persist.dalvik.vm.usejit"

    /** Состояние сети: `wifi=1,ap=0,ts=<epochMs>`. */
    data class State(val wifi: Boolean, val ap: Boolean, val tsMs: Long) {
        fun log(): String = "wifi=${if (wifi) 1 else 0} ap=${if (ap) 1 else 0}"
        fun wire(): String = "wifi=${if (wifi) 1 else 0},ap=${if (ap) 1 else 0},ts=$tsMs"
    }

    fun parse(saved: String?): State? {
        if (saved.isNullOrBlank()) return null
        val w = Regex("wifi=([01])").find(saved)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val a = Regex("ap=([01])").find(saved)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val ts = Regex("ts=([0-9]+)").find(saved)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return State(wifi = w == 1, ap = a == 1, tsMs = ts)
    }

    fun isExpired(state: State, nowMs: Long, ttlMs: Long = SAVED_TTL_MS): Boolean =
        state.tsMs > 0L && nowMs - state.tsMs > ttlMs

    /**
     * Что делать с ПРОСРОЧЕННЫМ маркером (REVIEW iter33p-fix1, 🟡 про 10-минутный TTL).
     *
     * Зачем TTL вообще: маркер — это «незавершённый возврат», а не «настройка навсегда». Если
     * приложение не запускалось сутки, водитель уже мог сам поменять Wi-Fi/точку доступа, и
     * молчаливая отмена его выбора — это уже вмешательство за пределами разрешённой операции.
     *
     * Почему TTL не может просто удалять данные:
     *  • сохранено `wifi=1` — выключенный Wi-Fi это ГАРАНТИРЕННАЯ потеря связи, а включение
     *    возвращает состояние, которое было ДО операции. Поэтому по истечении TTL такой маркер
     *    исполняется в урезанном виде: Wi-Fi включаем, точку доступа (видимый пользователю
     *    hotspot) уже НЕ поднимаем — это единственная часть, где просрочка действительно опасна;
     *  • сохранено `wifi=0` — возвращать нечего, маркер выбрасываем (`DROP`);
     *  • часы ушли назад (типично для ГУ сразу после загрузки, до синхронизации времени) —
     *    это НЕ просрочка: `now < ts` не даёт права забыть незавершённый возврат.
     */
    enum class TtlPolicy { FULL, WIFI_ONLY, DROP }

    fun ttlPolicy(state: State, nowMs: Long, ttlMs: Long = SAVED_TTL_MS): TtlPolicy = when {
        state.tsMs <= 0L -> TtlPolicy.FULL            // время неизвестно — не выдумываем просрочку
        nowMs < state.tsMs -> TtlPolicy.FULL          // часы назад — не просрочка
        nowMs - state.tsMs <= ttlMs -> TtlPolicy.FULL
        state.wifi -> TtlPolicy.WIFI_ONLY
        else -> TtlPolicy.DROP
    }

    /** Политика просрочки в виде строки для лога (без неё непонятно, почему AP не поднимали). */
    fun ttlPolicyLabel(p: TtlPolicy): String = when (p) {
        TtlPolicy.FULL -> "full"
        TtlPolicy.WIFI_ONLY -> "expired_wifi_only"
        TtlPolicy.DROP -> "expired_drop"
    }

    /**
     * Разбор read-only пробы `ap0`:
     *   `P=1|0` — интерфейс существует; `OS=<operstate>`; `A=<1|0>` — есть ли IPv4.
     * Возвращает `true` (AP поднят), `false` (AP выключен — в том числе когда интерфейса НЕТ,
     * это штатный случай для выключенной точки доступа) или `null` (канал не ответил).
     */
    fun parseApProbe(out: String?): Boolean? {
        if (out.isNullOrBlank()) return null
        if (out.contains("ERR_")) return null
        val p = Regex("P=([01])").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (p == 0) return false
        val a = Regex("A=([0-9]+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return a > 0
    }

    /** Проба AP — только чтение (никаких `svc`, `startSoftAp`, `settings put`). */
    internal fun apProbeCommand(): String {
        val D = "$"
        return "echo P=${D}([ -d /sys/class/net/ap0 ] && echo 1 || echo 0); " +
            "echo OS=${D}(cat /sys/class/net/ap0/operstate 2>/dev/null); " +
            "echo A=${D}(ip -o -4 addr show ap0 2>/dev/null | grep -c 'inet ')"
    }

    /** Скрипт подготовки (`w0/la.run`): svc wifi disable → drain `oif wlan0` ≤20 с → setprop. */
    internal fun preRestartScript(usejitValue: String): String {
        val D = "$"
        return """
            #!/system/bin/sh
            # iter33p-fix2 §2.2 — как `w0/la.run`: svc wifi disable → drain oif wlan0 → setprop
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

    /**
     * Откат runtime-props, если операция прервана ПОСЛЕ `setprop` (сеть/`usejit` уже изменены).
     * Меняются только те же два ключа, что ставил [preRestartScript]; значение возвращается
     * к прочитанному до операции, успех — по read-back в лог `JIT_NET_PROPS_ROLLBACK`.
     */
    internal fun propsRollbackScript(usejitBefore: String, profilesBefore: String): String {
        val D = "$"
        return """
            #!/system/bin/sh
            # iter33p-fix2 §1.4 — прежние runtime-props (откат отменённой операции)
            setprop $PROP_USEJIT "$usejitBefore" 2>/dev/null
            setprop $PROP_USEJIT_PROFILES "$profilesBefore" 2>/dev/null
            echo "JIT_NET_PROPS_ROLLBACK rt=${D}(getprop $PROP_USEJIT) pr=${D}(getprop $PROP_USEJIT_PROFILES) want_rt=$usejitBefore want_pr=$profilesBefore"
        """.trimIndent()
    }

    /** Санация: удаление только по полному spec, при неоднозначности — отказ. */
    internal fun sanitizeScript(): String {
        val D = "$"
        return """
            #!/system/bin/sh
            # iter33p-fix2 §2.3а — точное удаление только `oif wlan0`; при неоднозначности НЕ удаляем
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
}

/** Хранилище «состояние сети до операции» (в приложении — префы, в тестах — память). */
interface NetPrefs {
    fun get(key: String): String?
    /** `true` — запись реально подтверждена (commit/чтение обратно). */
    fun put(key: String, value: String?): Boolean
}

/** Результат выполнения device-скрипта. */
data class ScriptOut(val exitCode: Int, val output: String) {
    val channelOk: Boolean get() = output.isNotBlank() && !output.contains("ERR_")
}

/**
 * Итог возврата сети. Намеренно НЕ называется «всё восстановлено»:
 * подтверждаются только запрошенные состояния, а `link`/`sanitize` — отдельные факты.
 */
data class RestoreResult(
    val wifiWasRequested: Boolean,
    val wifiConfirmed: Boolean,
    val apWasRequested: Boolean,
    val apConfirmed: Boolean,
    val link: String,
    val sanitizeLine: Boolean,
    val sanitizeLeft: Int
) {
    /** Запрошенное состояние подтверждено (это НЕ значит «интернет есть»). */
    val essentialsConfirmed: Boolean get() = wifiConfirmed && apConfirmed

    /** Сеть в запрошенном состоянии и канал выглядит живым (`inet` на wlan0) и правила слиты. */
    val fullyRestored: Boolean
        get() = essentialsConfirmed && link == "up" && sanitizeLine && sanitizeLeft == 0

    /** Маркер можно снимать: запрошенное состояние подтверждено и link не «неизвестно». */
    val canClearMarker: Boolean get() = essentialsConfirmed && link != "unknown"

    fun log(): String = "wifi_confirmed=$wifiConfirmed ap_confirmed=$apConfirmed link=$link " +
        "sanitize=${if (sanitizeLine) "line" else "none"} sanitize_left=$sanitizeLeft " +
        "essentials=$essentialsConfirmed fully=$fullyRestored"
}

/** Что делать на старте с маркером незавершённого возврата. */
enum class Recovery { NONE, EXPIRED, RESTORED, KEPT }

/**
 * Чистые решения цепочки тумблера JIT (REVIEW iter33p-fix1 §2): «можно ли начинать», «что делать
 * при отказе», «что считается подтверждённым запуском». Вынесены из [AppPerformance], чтобы
 * сценарии «ошибка записи намерения», «отказ подготовки», «неподтверждённый запуск» и «следующий
 * старт» проверялись тестами, а не чтением кода.
 */
object JitFlow {

    /** Где остановились — от этого зависит набор компенсаций. */
    enum class Stage { BEFORE_NET, AFTER_NET_BEFORE_LAUNCH, LAUNCH_UNCONFIRMED }

    /** Можно ли начинать операцию (сеть ещё не тронута ни в одном из отказов). */
    enum class Gate { PROCEED, ABORT_NO_NET_STATE, ABORT_INTENT_WRITE }

    fun gate(netStateKnown: Boolean, intentConfirmed: Boolean): Gate = when {
        !netStateKnown -> Gate.ABORT_NO_NET_STATE
        !intentConfirmed -> Gate.ABORT_INTENT_WRITE
        else -> Gate.PROCEED
    }

    /** Что обязано произойти при отказе на данном шаге. */
    data class AbortPlan(
        val killWatcher: Boolean,
        val rollbackProps: Boolean,
        val compensateNet: Boolean,
        val discardSnapshot: Boolean
    )

    fun abortPlan(stage: Stage, propsTouched: Boolean): AbortPlan = when (stage) {
        // Сеть не менялась: возвращать нечего, watcher-а нет, снимок AVAS не нужен.
        Stage.BEFORE_NET -> AbortPlan(false, false, false, true)
        // Сеть уже погашена и props, возможно, изменены.
        Stage.AFTER_NET_BEFORE_LAUNCH -> AbortPlan(true, propsTouched, true, true)
        // Запуск не подтвердился — сначала гарантированно гасим watcher (иначе поздний рестарт).
        Stage.LAUNCH_UNCONFIRMED -> AbortPlan(true, propsTouched, true, true)
    }

    /**
     * Подтверждён ли запуск watcher-а. `pgrep` на прошивке может отсутствовать, поэтому
     * единственный надёжный признак — первая строка самого watcher-а ([JitNetCore] не при чём:
     * токен пишет скрипт `jitZygoteWatcherScript`).
     */
    fun launchConfirmed(resultFileContent: String?, armedToken: String): Boolean =
        resultFileContent?.contains(armedToken) == true

    /** Итог гашения watcher-а: рестарт возможен только если процесс/скрипт не подтверждены снятыми. */
    fun restartStillPossible(alivePid: String?, scriptPresent: Boolean?): Boolean =
        !(alivePid.isNullOrBlank() && scriptPresent == false)

    /** Можно ли снимать маркер незавершённого возврата сети. */
    fun canClearNetMarker(res: RestoreResult): Boolean = res.canClearMarker
}

class JitNetCore(
    private val prefs: NetPrefs,
    private val readWifi: () -> Boolean?,
    private val setWifi: (Boolean) -> Unit,
    private val apProbe: () -> String?,
    private val link: (String) -> String,
    private val runScript: (String, Int) -> ScriptOut,
    private val log: (String) -> Unit,
    private val now: () -> Long,
    private val sleep: (Long) -> Unit,
    private val ttlMs: Long = JitNetModel.SAVED_TTL_MS,
    private val wifiAttempts: Int = JitNetModel.WIFI_RESTORE_ATTEMPTS,
    private val wifiPauseMs: Long = JitNetModel.WIFI_RESTORE_PAUSE_MS
) {
    fun savedState(): JitNetModel.State? = JitNetModel.parse(prefs.get(JitNetModel.PREF_NET_SAVED))

    fun clearSaved() {
        prefs.put(JitNetModel.PREF_NET_SAVED, null)
        prefs.put(JitNetModel.PREF_NET_LAST, null)
    }

    /** Текущее состояние; null — если Wi-Fi-сервис или канал AP не дают ответа. */
    fun readState(): JitNetModel.State? {
        val wifi = readWifi() ?: return null
        val ap = JitNetModel.parseApProbe(apProbe()) ?: return null
        return JitNetModel.State(wifi, ap, now())
    }

    /**
     * Сохранить состояние ДО изменений. null — сохранить не удалось: вызывающий обязан прервать
     * операцию, ничего не выключая.
     */
    fun saveBeforeRestart(): JitNetModel.State? {
        val st = readState()
        if (st == null) {
            log("JIT_NET_PRE skipped reason=state_unknown (канал/сервис недоступен — сеть не трогаем)")
            return null
        }
        val ok = prefs.put(JitNetModel.PREF_NET_SAVED, st.wire())
        val back = savedState()
        if (!ok || back == null || back.wifi != st.wifi || back.ap != st.ap) {
            log("JIT_NET_PRE failed reason=save_not_confirmed want=${st.wire()} " +
                "got=${back?.wire() ?: "none"} (сеть не трогаем)")
            prefs.put(JitNetModel.PREF_NET_SAVED, null)
            return null
        }
        log("JIT_NET_PRE ${st.log()} source=wifi_manager+ap0_probe saved=${st.wire()} confirmed=yes")
        return st
    }

    /** Операция отменена ДО сетевых изменений — маркер не нужен. */
    fun abandon(reason: String) {
        if (savedState() != null) {
            clearSaved()
            log("JIT_NET_PRE cleared reason=$reason (сеть не менялась)")
        }
    }

    /** Подготовка: `svc wifi disable` → drain → setprop. */
    fun runPreRestart(usejitValue: String): ScriptOut {
        val out = runScript(JitNetModel.preRestartScript(usejitValue), 45_000)
        val waitLine = out.output.lineSequence().firstOrNull { it.contains("ip_rule_wait=") }?.trim().orEmpty()
        val wait = Regex("ip_rule_wait=([0-9]+)").find(waitLine)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val drained = waitLine.contains("drained=yes")
        log("JIT_NET_PRE ip_rule_wait=$wait drained=${if (drained) "yes" else "no"} rc=${out.exitCode} " +
            "channel=${if (out.channelOk) "ok" else "failed"}")
        return out
    }

    /** Откат runtime-props к прочитанным до операции (после отмены). */
    fun rollbackProps(usejitBefore: String, profilesBefore: String): Boolean {
        val out = runScript(JitNetModel.propsRollbackScript(usejitBefore, profilesBefore), 20_000)
        val line = out.output.lineSequence().firstOrNull { it.contains("JIT_NET_PROPS_ROLLBACK") }
            ?.trim().orEmpty()
        log(line.ifEmpty { "JIT_NET_PROPS_ROLLBACK failed rc=${out.exitCode} channel=${if (out.channelOk) "ok" else "failed"}" })
        return line.contains("rt=$usejitBefore") && line.contains("pr=$profilesBefore")
    }

    /** Возврат сети ТОЛЬКО из сохранённого состояния. */
    fun restore(
        saved: JitNetModel.State,
        why: String,
        attempts: Int = wifiAttempts,
        allowAp: Boolean = true
    ): RestoreResult {
        val san = runScript(JitNetModel.sanitizeScript(), 25_000)
        val line = san.output.lineSequence().firstOrNull { it.contains("JIT_NET_SANITIZE removed=") }
        val left = Regex("left=([0-9]+)").find(line.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val sanitizeLine = line != null && !san.output.contains("ERR_")
        san.output.lineSequence().filter { it.contains("JIT_NET_SANITIZE") }.forEach { log(it.trim()) }

        val wifiConfirmed = if (saved.wifi) restoreWifi(attempts) else {
            log("JIT_NET_RESTORE wifi=0 state=skipped reason=was_off")
            true
        }
        val apConfirmed = when {
            !saved.ap -> { log("JIT_NET_RESTORE ap=0 state=skipped reason=was_off"); true }
            // Просроченный маркер: hotspot не поднимаем (см. JitNetModel.ttlPolicy).
            !allowAp -> { log("JIT_NET_RESTORE ap=1 state=skipped reason=ttl_expired"); true }
            else -> restoreAp()
        }
        val linkNow = link("wlan0")
        val res = RestoreResult(
            wifiWasRequested = saved.wifi, wifiConfirmed = wifiConfirmed,
            apWasRequested = saved.ap, apConfirmed = apConfirmed,
            link = linkNow, sanitizeLine = sanitizeLine, sanitizeLeft = left
        )
        log("JIT_NET_RESTORE why=$why saved=${saved.log()} ${res.log()}")
        return res
    }

    /** Wi-Fi: запрос и фактическое состояние пишутся отдельно; успех — только по `state`. */
    private fun restoreWifi(attempts: Int): Boolean {
        for (attempt in 1..attempts) {
            val before = readWifi()
            var request = "already_on"
            if (before != true) {
                setWifi(true)
                request = "setWifiEnabled(true)"
            }
            val state = readWifi()
            log("JIT_NET_RESTORE wifi=1 attempt=$attempt/$attempts request=$request " +
                "state=${state?.toString() ?: "unknown"} link=${link("wlan0")}")
            if (state == true) return true
            if (attempt < attempts) sleep(wifiPauseMs)
        }
        log("JIT_NET_RESTORE wifi=1 attempts=$attempts result=failed (запрос отправлен, состояние не подтверждено)")
        return false
    }

    /** AP: только если он был поднят до операции; успех — по пробе после запроса. */
    private fun restoreAp(): Boolean {
        val before = JitNetModel.parseApProbe(apProbe())
        if (before == true) {
            log("JIT_NET_RESTORE ap=1 state=already_up")
            return true
        }
        val err = runCatching { startSoftAp() }.exceptionOrNull()
        val after = JitNetModel.parseApProbe(apProbe())
        log("JIT_NET_RESTORE ap=1 request=${if (err == null) "startSoftAp" else "failed"} " +
            "state=${after?.toString() ?: "unknown"} reason=${err?.let { it.javaClass.simpleName } ?: "none"}")
        return after == true
    }

    /** Переопределяется адаптером на устройстве (`WifiManager.startSoftAp`). */
    internal var startSoftAp: () -> Unit = { throw UnsupportedOperationException("no_startSoftAp") }

    /**
     * Старт приложения: если маркер незавершённого возврата есть — пробуем вернуть сеть.
     * Нет маркера — НИ ОДНОГО сетевого действия.
     */
    fun recoverOnStart(reason: String): Recovery {
        val saved = savedState()
        if (saved == null) {
            log("JIT_NET_RESTORE skipped reason=no_saved_state context=$reason")
            return Recovery.NONE
        }
        val policy = JitNetModel.ttlPolicy(saved, now(), ttlMs)
        if (policy == JitNetModel.TtlPolicy.DROP) {
            clearSaved()
            log("JIT_NET_RESTORE skipped reason=expired age_ms=${now() - saved.tsMs} " +
                "policy=${JitNetModel.ttlPolicyLabel(policy)} saved=${saved.log()} context=$reason")
            return Recovery.EXPIRED
        }
        if (policy == JitNetModel.TtlPolicy.WIFI_ONLY) {
            log("JIT_NET_RESTORE policy=${JitNetModel.ttlPolicyLabel(policy)} " +
                "age_ms=${now() - saved.tsMs} saved=${saved.log()} context=$reason " +
                "(Wi-Fi возвращаем, AP не поднимаем)")
        }
        val res = restore(saved, "start:$reason", allowAp = policy == JitNetModel.TtlPolicy.FULL)
        prefs.put(JitNetModel.PREF_NET_LAST, "${res.log()} context=$reason")
        if (res.canClearMarker) {
            clearSaved()
            log("JIT_NET_RESTORE done context=$reason marker=cleared retry=no")
            return Recovery.RESTORED
        }
        log("JIT_NET_RESTORE keep_saved state=${res.log()} context=$reason (повторим на следующем старте)")
        return Recovery.KEPT
    }
}
