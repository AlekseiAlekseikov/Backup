package com.example.voiceapp3.tools

/**
 * iter33p-fix2 §1/§2 — ЧИСТОЕ ядро сети вокруг рестарта фреймворка.
 *
 * Почему отдельный файл: REVIEW iter33p-fix1 (замечание 1) справедливо указал, что тесты сети
 * проверяли исходный текст. Здесь нет ни `android.*`, ни `AdbClient`: все внешние эффекты —
 * лямбды, поэтому сценарии «процесс убили между disable и компенсацией», «следующий старт»,
 * «Wi-Fi не поднялся», «ap0 отсутствует штатно» прогоняются обычными JVM-тестами.
 *
 * Инварианты (iter33p-fix3 + fix4; границы владельца — REVIEW iter33p-fix2 §3, уточнение fix3):
 *  1. состояние сети сохраняется ДО любых изменений и подтверждается чтением обратно;
 *  2. возврат сети выполняется ТОЛЬКО как продолжение конкретной операции JIT:
 *     • до рестарта — компенсация внутри той же операции ([restorePendingInChain], `why=compensate:*`);
 *     • после рестарта — при следующем запуске НАШЕГО процесса, но только если есть
 *       доказательство фазы этой операции ([JitFlow.bootNetAction] ← `JIT_ZYGOTE_ARMED|DONE|ABORTED
 *       op=<id>` для id из файла-намерения);
 *     • обычный холодный старт (нет намерения или фаза не доказана) — НИ ОДНОГО сетевого действия
 *       ([pendingMarker] только читает маркер для честной строки в логе);
 *  3. «запрос ≠ состояние»: в логе отдельно `request=`, `state=`, `link=`;
 *  4. отсутствие `ap0` — ШТАТНЫЙ признак выключенной точки доступа; неполный вывод пробы —
 *     «неизвестно» (операция не начинается);
 *  5. подтверждение Wi-Fi/AP не равно «интернет есть»: маркер снимается только при подтверждении
 *     всего, что было запрошено (для сохранённого `wifi=0` наличие IPv4 не требуется);
 *  6. отмена запуска watcher-а — только через СОБСТВЕННЫЙ файл-отмену с нашим `op=<id>` и
 *     СОБСТВЕННЫЙ скрипт: ни `pkill`, ни `kill`, ни поиск по подстроке не используются.
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
     * Что означает ПРОСРОЧЕННЫЙ маркер (REVIEW iter33p-fix1 🟡 про TTL + fix2 §3).
     *
     * Зачем TTL: маркер — «незавершённый возврат», а не «настройка навсегда». За его пределами
     * водитель мог сам поменять Wi-Fi/точку доступа, и откат его выбора — уже вмешательство за
     * рамками разрешённой операции.
     *
     * Политики БЕЗ сетевых действий:
     *  • `FULL`    — маркер свежий: возврат допустим, но только как продолжение операции JIT;
     *  • `EXPIRED` — маркер просрочен: он выбрасывается с честной строкой `reason=expired`,
     *                сеть НЕ трогается.
     * Часы назад (`now < ts`) и `ts=0` просрочкой не считаются: нет доказательства устаревания.
     */
    enum class TtlPolicy { FULL, EXPIRED }

    fun ttlPolicy(state: State, nowMs: Long, ttlMs: Long = SAVED_TTL_MS): TtlPolicy = when {
        state.tsMs <= 0L -> TtlPolicy.FULL            // время неизвестно — не выдумываем просрочку
        nowMs < state.tsMs -> TtlPolicy.FULL          // часы назад — не просрочка
        nowMs - state.tsMs <= ttlMs -> TtlPolicy.FULL
        else -> TtlPolicy.EXPIRED
    }

    fun ttlPolicyLabel(p: TtlPolicy): String = when (p) {
        TtlPolicy.FULL -> "full"
        TtlPolicy.EXPIRED -> "expired_drop"
    }

    /**
     * Разбор read-only пробы `ap0`:
     *   `P=1|0` — интерфейс существует; `OS=<operstate>`; `A=<1|0>` — есть ли IPv4.
     * Возвращает `true` (AP поднят), `false` (AP выключен — в т.ч. когда интерфейса НЕТ: для
     * выключенной точки доступа это штатно) или `null` (неизвестно).
     *
     * REVIEW iter33p-fix2 🟡: неполный/битый вывод не должен молча означать «AP выключен».
     * Поэтому обязательны ВСЕ три поля, а `P=1` без числового `A` — это `null`, а не ноль.
     */
    fun parseApProbe(out: String?): Boolean? {
        if (out.isNullOrBlank()) return null
        if (out.contains("ERR_")) return null
        if (!out.contains("P=") || !out.contains("OS=") || !out.contains("A=")) return null
        val p = Regex("P=([01])\\b").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (p == 0) return false
        val a = Regex("A=([0-9]+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return a > 0
    }

    /** Проба AP — только чтение (никаких `svc`, `startSoftAp`, `settings put`). */
    internal fun apProbeCommand(): String {
        val D = "$"
        return "echo P=${D}([ -d /sys/class/net/ap0 ] && echo 1 || echo 0); " +
            "echo OS=${D}(cat /sys/class/net/ap0/operstate 2>/dev/null); " +
            "echo A=${D}(ip -o -4 addr show ap0 2>/dev/null | grep -c 'inet ')"
    }

    // ─────────────── watcher рестарта: собственный скрипт, отмена без `kill` ───────────────

    internal const val WATCHER_RESULT_PATH = "/data/local/tmp/jit_zygote_result.txt"
    /** Файл-отмена: внутри обязан быть `op=<id>` операции, иначе отмена не считается своей. */
    internal const val WATCHER_CANCEL_PATH = "/data/local/tmp/jit_zygote_cancel"
    internal const val WATCHER_SCRIPT_PATH = "/data/local/tmp/jit_zygote_watch.sh"

    internal const val WATCHER_ARMED = "JIT_ZYGOTE_ARMED"
    internal const val WATCHER_ABORTED = "JIT_ZYGOTE_ABORTED"
    /** Пишется непосредственно ПЕРЕД `ctl.restart` — доказательство, что отмена уже не успеет. */
    internal const val WATCHER_RESTARTING = "JIT_ZYGOTE_RESTARTING"
    internal const val WATCHER_DONE = "JIT_ZYGOTE_DONE"

    /**
     * Device-side watcher рестарта фреймворка.
     *
     * REVIEW iter33p-fix2 §1–§3 + fix3:
     *  1. `JIT_ZYGOTE_ARMED op=<id>` — ПЕРВАЯ строка, ДО `sleep` и ДО `ctl.restart`;
     *  2. каждая строка несёт `op=<id>`: старый результат не может подтвердить новую операцию;
     *  3. отмена — только собственный файл, и его СОДЕРЖИМОЕ обязано содержать наш `op=<id>`
     *     (`grep -q "op=$OP"`): чужой/пустой файл отменой не считается;
     *  4. `JIT_ZYGOTE_RESTARTING op=<id>` пишется непосредственно перед `setprop ctl.restart zygote`:
     *     это закрывает недоказуемую гонку «отмена пришла между проверкой и рестартом» — увидев
     *     эту строку, приложение честно считает рестарт состоявшимся, а не отменённым;
     *  5. итог после подъёма — `JIT_ZYGOTE_DONE op=<id> …`.
     *
     * Параметры запуска: `$1 = op id`, `$2 = want (true|false)`, `$3 = delay_s`, `$4 = settle_s`.
     */
    internal fun watcherScript(): String {
        val D = "$"
        return """
            #!/system/bin/sh
            # Собственный скрипт (не системный). Параметры: 1=op 2=want 3=delay_s 4=settle_s
            OP=${D}1
            V=${D}2
            DELAY=${D}3
            SETTLE=${D}4
            OUT=$WATCHER_RESULT_PATH
            CANCEL=$WATCHER_CANCEL_PATH
            # 1) Подтверждение запуска — ДО задержки, чтобы приложение успело его прочитать.
            echo "$WATCHER_ARMED op=${D}OP want=${D}V delay=${D}{DELAY:-8} pid=${D}${D}" > ${D}OUT
            sleep ${D}{DELAY:-8}
            # 2) Отмена: только наш собственный файл И только с нашим op. Ничего не убиваем.
            if [ -f ${D}CANCEL ] && grep -q "op=${D}OP" ${D}CANCEL 2>/dev/null; then
              echo "$WATCHER_ABORTED op=${D}OP reason=cancel_file" >> ${D}OUT
              exit 0
            fi
            ZP0=${D}(pidof zygote 2>/dev/null)
            SS0=${D}(pidof system_server 2>/dev/null)
            # 3) Фиксируем момент перехода: после этой строки отмена уже невозможна.
            echo "$WATCHER_RESTARTING op=${D}OP" >> ${D}OUT
            setprop ctl.restart zygote
            i=0
            while [ ${D}i -lt 180 ]; do
              sleep 1
              i=${D}((i+1))
              ZP=${D}(pidof zygote 2>/dev/null)
              SS=${D}(pidof system_server 2>/dev/null)
              if [ -n "${D}ZP" ] && [ "${D}ZP" != "${D}ZP0" ] && [ -n "${D}SS" ] && [ "${D}SS" != "${D}SS0" ]; then break; fi
            done
            sleep ${D}{SETTLE:-5}
            RT=${D}(getprop dalvik.vm.usejit)
            PS=${D}(getprop persist.dalvik.vm.usejit)
            echo "$WATCHER_DONE op=${D}OP runtime=${D}RT persist=${D}PS want=${D}V zygote_restart=ok waited_s=${D}i" >> ${D}OUT
        """.trimIndent()
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

    /**
     * Нужно ли требовать живой канал (`inet` на `wlan0`).
     *
     * REVIEW iter33p-fix3 🟡: если сохранено `wifi=0` (Wi-Fi был выключен ДО операции), то
     * отсутствие IPv4 — НЕ ошибка: сеть возвращена именно в то состояние, которое было. Требовать
     * `link=up` в этом случае означало бы бесконечный ложный retry и попытки включить Wi-Fi,
     * которого у водителя не было.
     */
    val linkRequired: Boolean get() = wifiWasRequested

    /** То же для санации правил: без запрошенного Wi-Fi правила `oif wlan0` не наша забота. */
    val sanitizeRequired: Boolean get() = wifiWasRequested

    private val linkOk: Boolean get() = !linkRequired || link == "up"
    private val sanitizeOk: Boolean get() = !sanitizeRequired || (sanitizeLine && sanitizeLeft == 0)

    /**
     * Сеть в запрошенном состоянии, и всё, что было запрошено, подтверждено.
     *
     * REVIEW iter33p-fix2 🟡: раньше маркер снимался при `link=down`/`sanitize_left>0`; теперь
     * снятие требует полного подтверждения — но только по тем параметрам, которые реально
     * менялись (см. [linkRequired]/[sanitizeRequired]).
     */
    val fullyRestored: Boolean get() = essentialsConfirmed && linkOk && sanitizeOk

    /**
     * Маркер незавершённого возврата можно снимать: подтверждено всё запрошенное, не подтвердилось —
     * маркер остаётся, и возврат повторится при следующей операции JIT.
     */
    val canClearMarker: Boolean get() = fullyRestored

    fun log(): String = "wifi_confirmed=$wifiConfirmed ap_confirmed=$apConfirmed link=$link " +
        "sanitize=${if (sanitizeLine) "line" else "none"} sanitize_left=$sanitizeLeft " +
        "essentials=$essentialsConfirmed fully=$fullyRestored " +
        "required=link:${if (linkRequired) 1 else 0},sanitize:${if (sanitizeRequired) 1 else 0}"
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

    /**
     * Что обязано произойти при отказе на данном шаге.
     *
     * `cancelWatcher` — НЕ `kill`: отмена делается файлом-отменой, который читает наш же скрипт
     * (REVIEW iter33p-fix2 §3: `pkill`/`kill` и поиск по подстроке запрещены).
     */
    data class AbortPlan(
        val cancelWatcher: Boolean,
        val rollbackProps: Boolean,
        val compensateNet: Boolean,
        val discardSnapshot: Boolean
    )

    fun abortPlan(stage: Stage, propsMaybeTouched: Boolean): AbortPlan = when (stage) {
        Stage.BEFORE_NET -> AbortPlan(false, false, false, true)
        Stage.AFTER_NET_BEFORE_LAUNCH -> AbortPlan(true, propsMaybeTouched, true, true)
        Stage.LAUNCH_UNCONFIRMED -> AbortPlan(true, propsMaybeTouched, true, true)
    }

    // ─────────────────── идентичность операции (REVIEW iter33p-fix2 §2) ───────────────────

    /** Id операции в шелл-безопасном виде; каждая строка watcher-а несёт `op=<id>`. */
    fun opId(nowMs: Long, bootTag: String, salt: String): String {
        val clean = { s: String -> s.filter { it.isLetterOrDigit() || it == '-' }.take(12) }
        val tag = clean(bootTag).ifEmpty { "boot" }
        val s = clean(salt).ifEmpty { "0" }
        return "op-$tag-${nowMs.toString(36)}-$s"
    }

    /**
     * Строка с [token] и `op=<opId>` присутствует в выводе.
     *
     * Вывод делим и по `\n`, и по `|`: адаптер читает файл как `tr '\n' '|'`, иначе строки
     * склеиваются и старый результат подтверждает новую операцию.
     */
    private fun hasLine(out: String?, token: String, opId: String): Boolean =
        out != null && out.split('\n', '|').any { it.contains(token) && it.contains("op=$opId") }

    fun armedFor(resultFileContent: String?, opId: String): Boolean =
        hasLine(resultFileContent, JitNetModel.WATCHER_ARMED, opId)

    fun doneFor(resultFileContent: String?, opId: String): Boolean =
        hasLine(resultFileContent, JitNetModel.WATCHER_DONE, opId)

    fun abortedFor(resultFileContent: String?, opId: String): Boolean =
        hasLine(resultFileContent, JitNetModel.WATCHER_ABORTED, opId)

    fun restartingFor(resultFileContent: String?, opId: String): Boolean =
        hasLine(resultFileContent, JitNetModel.WATCHER_RESTARTING, opId)

    /**
     * Итог отмены запуска.
     *
     * `UNKNOWN` — «доказать не могу»: ни отмены, ни перехода к рестарту, ни итога нашей операции
     * в файле нет. В этом состоянии нельзя утверждать «рестарта не будет», поэтому маркер и
     * снимок AVAS сохраняются.
     */
    enum class CancelOutcome { CANCELLED, RESTARTED, UNKNOWN }

    /**
     * Разбор файла итога для отмены. `RESTARTED` выставляется по строке `JIT_ZYGOTE_RESTARTING`,
     * которую скрипт пишет НЕПОСРЕДСТВЕННО перед `setprop ctl.restart zygote`: так гонка «отмена
     * пришла между проверкой файла и рестартом» перестаёт быть недоказуемой — она видна в файле.
     */
    fun cancelOutcome(resultFileContent: String?, opId: String): CancelOutcome = when {
        abortedFor(resultFileContent, opId) -> CancelOutcome.CANCELLED
        restartingFor(resultFileContent, opId) -> CancelOutcome.RESTARTED
        doneFor(resultFileContent, opId) -> CancelOutcome.RESTARTED
        else -> CancelOutcome.UNKNOWN
    }

    /** Возможен ли ещё поздний рестарт этой операции. */
    fun restartStillPossible(outcome: CancelOutcome): Boolean = outcome != CancelOutcome.CANCELLED

    /** Файл итога готов к новой операции: старого результата не осталось. */
    fun resultFileReady(stillExists: Boolean?): Boolean = stillExists == false

    // ─────────────── сеть как продолжение операции (REVIEW iter33p-fix3, уточнение) ───────────────

    /** Что делать с сетью при запуске процесса. */
    enum class BootNetAction {
        /** Обычный холодный старт: намерения нет либо фаза операции не доказана — сеть не трогаем. */
        NONE_COLD,
        /** Продолжение конкретной операции JIT после рестарта zygote: возвращаем сохранённое. */
        CONTINUE_OPERATION
    }

    /**
     * Разрешён ли возврат сети при старте процесса.
     *
     * Рестарт zygote убивает наш процесс, поэтому продолжение операции физически происходит при
     * следующем запуске — это часть той же операции, а не самостоятельная boot-функция.
     * Условия (оба обязательны):
     *  • [intentVerified] — файл-намерение наш и от ТЕКУЩЕЙ загрузки (`JitBootMark.pendingDecision`);
     *  • [phaseProven] — в файле итога есть строка нашей операции: `ARMED` (рестарт планировался),
     *    `RESTARTING`/`DONE` (рестарт состоялся) или `ABORTED` (рестарт отменён, но сеть могла
     *    остаться изменённой — компенсация тоже нужна).
     *
     * Чего это НЕ разрешает: применять JIT, повторять `ctl.restart`, выполнять сетевые команды на
     * обычной перезагрузке ГУ (там намерения нет либо загрузка другая → `NONE_COLD`).
     */
    fun bootNetAction(intentVerified: Boolean, phaseProven: Boolean): BootNetAction =
        if (intentVerified && phaseProven) BootNetAction.CONTINUE_OPERATION else BootNetAction.NONE_COLD

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
        // Точное сравнение тегов: `want_rt=false` СОДЕРЖИТ `rt=false`, и подстрочная проверка
        // считала неудавшийся откат успешным (ловушка найдена тестом `propsRollbackIsVerified`).
        fun tag(name: String): String? =
            Regex("(?:^|[\\s|])$name=([^\\s|]+)").find(line)?.groupValues?.get(1)
        return tag("rt") == usejitBefore && tag("pr") == profilesBefore
    }

    /** Возврат сети ТОЛЬКО из сохранённого состояния. */
    fun restore(saved: JitNetModel.State, why: String, attempts: Int = wifiAttempts): RestoreResult {
        val san = runScript(JitNetModel.sanitizeScript(), 25_000)
        val line = san.output.lineSequence().firstOrNull { it.contains("JIT_NET_SANITIZE removed=") }
        val left = Regex("left=([0-9]+)").find(line.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val sanitizeLine = line != null && !san.output.contains("ERR_")
        san.output.lineSequence().filter { it.contains("JIT_NET_SANITIZE") }.forEach { log(it.trim()) }

        val wifiConfirmed = if (saved.wifi) restoreWifi(attempts) else {
            log("JIT_NET_RESTORE wifi=0 state=skipped reason=was_off")
            true
        }
        val apConfirmed = if (saved.ap) restoreAp() else {
            log("JIT_NET_RESTORE ap=0 state=skipped reason=was_off")
            true
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
    /**
     * Возврат сохранённого состояния сети как продолжение операции JIT.
     *
     * Вызывается ТОЛЬКО из двух мест (оба — внутри цепочки тумблера):
     *  • начало клика (`why=chain:pre`) — компенсация хвоста прошлой операции;
     *  • старт нашего процесса, когда фаза операции доказана (`why=chain:post_zygote`,
     *    решение принимает [JitFlow.bootNetAction]).
     *
     * Просроченный маркер НЕ возвращает сеть: он выбрасывается с `reason=expired`.
     */
    fun restorePendingInChain(reason: String): Recovery {
        val saved = savedState()
        if (saved == null) {
            log("JIT_NET_CHAIN skipped reason=no_saved_state context=$reason")
            return Recovery.NONE
        }
        val policy = JitNetModel.ttlPolicy(saved, now(), ttlMs)
        if (policy == JitNetModel.TtlPolicy.EXPIRED) {
            clearSaved()
            log("JIT_NET_CHAIN skipped reason=expired age_ms=${now() - saved.tsMs} " +
                "policy=${JitNetModel.ttlPolicyLabel(policy)} saved=${saved.log()} context=$reason " +
                "(просроченный маркер выбрасывается без сетевых действий)")
            return Recovery.EXPIRED
        }
        val res = restore(saved, "chain:$reason")
        prefs.put(JitNetModel.PREF_NET_LAST, "${res.log()} context=$reason")
        if (res.canClearMarker) {
            clearSaved()
            log("JIT_NET_CHAIN done context=$reason marker=cleared retry=no")
            return Recovery.RESTORED
        }
        log("JIT_NET_CHAIN keep_saved state=${res.log()} context=$reason " +
            "(маркер остаётся: повтор — при следующей операции JIT)")
        return Recovery.KEPT
    }

    /**
     * Только чтение: состояние маркера и что с ним будет. Сетевых действий нет — используется на
     * обычном холодном старте для честной строки в логе.
     */
    fun pendingMarker(): Pair<JitNetModel.State, JitNetModel.TtlPolicy>? =
        savedState()?.let { it to JitNetModel.ttlPolicy(it, now(), ttlMs) }

}
