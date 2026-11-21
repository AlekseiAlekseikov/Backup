package com.example.voiceapp3.tools

/**
 * Общие типы JIT-панели и read-only диагностики (без Android).
 *
 * iter65: механизм клика/возврата/сверки референса CentralEXAuto 3.3.9 вынесен в [JitMech]
 * (Android-адаптеры — [JitMechAndroid]). Здесь остались только:
 *  • факт `getprop dalvik.vm.usejit` для панели ([Prop], [PropFact], [parseProp]) и положение
 *    тумблера по факту ([confirmedState], [switchPosition], [switchUpdate]);
 *  • интерфейс shell для диагностики ([JitShell]) и read-only диагностика сети/монтирований,
 *    событийные и TTL-снимки ([EventSnapshots], [DiagReport], [DiagRunner], [TtlSnapshotFlow],
 *    [DiagOnce]) — они ничего не пишут и в клике JIT не участвуют.
 * Прежние claim/lock/библиотека, маркер операции, пары CPS/DPS, разблокировка, решение о
 * возврате сети и скрипт-файл цепочки удалены (см. docs/agent/REPORT_1.0.65.md).
 */
object JitRef {

    // ─────────────────────────── интерфейс shell (адаптер — JitNet.DiagShell) ───────────────────────────

    interface JitShell {
        fun exec(command: String, timeoutMs: Int): Pair<Int, String>
        /** Длинный текст файлом. Диагностический канал ([JitNet.DiagShell]) явно отказывает. */
        fun execScript(name: String, text: String, timeoutMs: Int): Pair<Int, String> =
            exec(text, timeoutMs)

        /** `w0/bb`: содержимое `/proc/self/maps` процесса или `null`, если прочитать нельзя. */
        fun mapsExcerpt(): String?
    }

    // ─────────────────────────── чтение `getprop`: факт, а не догадка ───────────────────────────

    enum class Prop { TRUE, FALSE, UNKNOWN }

    data class PropFact(
        val value: Prop,
        val raw: String,
        val rc: Int,
        val reason: String,
        /** iter67 §A: вывод совпал с ожидаемым (`rc==0 && got==expect`). Без ожидания — `false`. */
        val expectOk: Boolean = false
    ) {
        val log: String
            get() = "getprop=${if (raw.isEmpty()) "empty" else raw} rc=$rc " +
                "fact=${value.name.lowercase()} reason=$reason expect_ok=$expectOk"
    }

    /**
     * `w0/bb`: `H1(8000,"getprop dalvik.vm.usejit")` → `trim() == "true"`; любое другое значение
     * (включая пустой вывод при недоступном транспорте) референс трактует как `false`, `rc` не
     * смотрит.
     *
     * ОТЛИЧИЕ (разрешено мандатом): если прочитать НЕЛЬЗЯ (`rc≠0`, `ERR_…`, пусто, многострочный
     * вывод), значение помечается [Prop.UNKNOWN] — «неизвестно», а не «выключено».
     */
    fun parseProp(rc: Int, out: String): PropFact {
        val raw = out.trim()
        if (rc != 0) return PropFact(Prop.UNKNOWN, raw.take(80), rc, "rc_nonzero")
        if (raw.contains("ERR_")) return PropFact(Prop.UNKNOWN, raw.take(80), rc, "transport_error")
        if (raw.contains('\n')) return PropFact(Prop.UNKNOWN, raw.take(80), rc, "multiline")
        return when (raw) {
            "true" -> PropFact(Prop.TRUE, raw, rc, "ok")
            "false" -> PropFact(Prop.FALSE, raw, rc, "ok")
            "" -> PropFact(Prop.UNKNOWN, raw, rc, "empty")
            else -> PropFact(Prop.UNKNOWN, raw.take(80), rc, "unexpected_value")
        }
    }

    /** Состояние для тумблера: `on`/`off` по факту `getprop`, `unknown` — если прочитать нельзя. */
    fun confirmedState(fact: PropFact?): String = when (fact?.value) {
        Prop.TRUE -> "on"
        Prop.FALSE -> "off"
        null, Prop.UNKNOWN -> "unknown"
    }

    /** Положение тумблера: `on`/`off` — по подтверждённому факту, при `unknown` — preference. */
    fun switchPosition(confirmed: String, preference: Boolean): Boolean = when (confirmed) {
        "on" -> true
        "off" -> false
        else -> preference
    }

    /** Что сделать с тумблером после фонового чтения: `true/false` — переставить, `null` — нет. */
    fun switchUpdate(current: Boolean, confirmed: String): Boolean? = when (confirmed) {
        "on" -> if (current) null else true
        "off" -> if (current) false else null
        else -> null
    }


    /**
     * §5.2 iter41 — учёт ОГРАНИЧЕННОГО числа попыток диагностического сбора.
     *
     * Зачем: «сбор запущен» и «необходимый сбор успешно завершён» — разные факты. Раньше флаг
     * выставлялся до выполнения, поэтому неудачный сбор (канал не готов, пассивный доступ не
     * состоялся) навсегда лишал владельца возможности получить данные позже.
     *
     * Что делает: не больше [maxRuns] попыток за процесс; успех фиксируется только по факту
     * завершения сборщиков; повтор не бесконечный, сторожа и новых кнопок нет.
     */
    /**
     * §3 iter42 — ограничитель событийных снимков сети (сравнимых «до/после»).
     *
     * Снимок делается только по СУЩЕСТВУЮЩИМ событиям владельца/системы (изменение TTL, событие
     * Wi-Fi/активной сети). Своих кнопок, включения TTL и любых изменений сети здесь нет.
     *
     * Ограничения: не чаще [minIntervalMs] и не больше [maxPerHour] за час; один снимок на ключ
     * одновременно (без параллельных дублей); при шторме выполняется ОДИН итоговый снимок
     * устойчивого состояния после debounce, а не серия.
     */
    class EventSnapshots(
        private val minIntervalMs: Long = 90_000L,
        private val maxPerHour: Int = 6,
        /** Отдельная квота на ДЕЙСТВИЯ владельца: они не должны теряться из-за авто-лимита. */
        private val userMaxPerHour: Int = 12
    ) {
        private var lastAtMs = 0L
        private var lastKey: String? = null
        private var windowStartMs = 0L
        /**
         * Отдельный флаг начала окна. Раньше окно определялось как `windowStartMs == 0L`, и при
         * первом вызове с `nowMs = 0` (синтетическое время в тестах) окно обнулялось НА КАЖДОМ
         * вызове — часовая квота не набиралась вовсе. На реальном времени это не проявлялось,
         * но правило должно быть корректным при любом старте.
         */
        private var windowStarted = false
        private var inWindow = 0
        private var inWindowUser = 0
        private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        private val log = ArrayList<String>()
        private val maxLog = 32

        /** «После» принимается парным к «до», если прошло не больше 10 минут. */
        private val PAIR_WINDOW_MS = 600_000L

        /**
         * @return `null` — снимок разрешён (можно запускать); иначе причина пропуска.
         */
        fun tryAcquire(key: String, nowMs: Long): String? = acquire(key, nowMs, null)

        /**
         * iter43 §3.2 — снимок по ДЕЙСТВИЮ ВЛАДЕЛЬЦА (переключение TTL).
         *
         * Такие события не должны теряться из-за общего ограничения частоты: у них отдельная
         * квота ([userMaxPerHour]) и нет минимального интервала. Общий часовой лимит и дедуп
         * in-flight продолжают действовать, а каждый пропуск пишется с причиной.
         */
        fun tryAcquireUser(key: String, nowMs: Long): String? = acquire(key, nowMs, null, userEvent = true)

        /** Парный снимок пользовательского события (`_pre` → `_post`). */
        fun tryAcquireUserPaired(key: String, nowMs: Long, pairWith: String): String? =
            acquire(key, nowMs, pairWith, userEvent = true)

        /**
         * Снимок, парный к предыдущему (`pairWith`) — например «до» и «после» РЕАЛЬНОГО
         * изменения TTL. Пара не должна теряться из-за минимального интервала: иначе «после»
         * всегда отбрасывалось бы, и сравнивать было бы не с чем. Ограничение часа при этом
         * действует: шторм по-прежнему не превращается в поток снимков.
         */
        fun tryAcquirePaired(key: String, nowMs: Long, pairWith: String): String? =
            acquire(key, nowMs, pairWith)

        /**
         * База имени пары: `<событие>_pre` ↔ `<событие>_post`. Пара разрешена только внутри
         * ОДНОГО события — иначе «парностью» можно было бы обойти ограничение частоты.
         */
        private fun pairBase(k: String): String? = when {
            k.endsWith("_pre") -> k.removeSuffix("_pre")
            k.endsWith("_post") -> k.removeSuffix("_post")
            else -> null
        }

        private fun acquire(
            key: String,
            nowMs: Long,
            pairWith: String?,
            userEvent: Boolean = false
        ): String? {
            synchronized(this) {
                if (inFlight.putIfAbsent(key, true) != null) return "duplicate_in_flight"
                if (!windowStarted || nowMs - windowStartMs > 3_600_000L) {
                    windowStarted = true
                    windowStartMs = nowMs
                    inWindow = 0
                    inWindowUser = 0
                }
                if (userEvent) {
                    if (inWindowUser >= userMaxPerHour) {
                        inFlight.remove(key)
                        return "user_hour_limit_$userMaxPerHour"
                    }
                } else if (inWindow >= maxPerHour) {
                    inFlight.remove(key)
                    return "hour_limit_$maxPerHour"
                }
                val since = nowMs - lastAtMs
                val paired = pairWith != null && pairWith == lastKey &&
                    pairBase(key) != null && pairBase(key) == pairBase(pairWith) &&
                    since in 0..PAIR_WINDOW_MS
                if (!userEvent && !paired && lastAtMs != 0L && since in 0 until minIntervalMs) {
                    inFlight.remove(key)
                    return "rate_limited ${since}ms<$minIntervalMs"
                }
                lastAtMs = nowMs
                lastKey = key
                inWindow++
                if (userEvent) inWindowUser++
                return null
            }
        }

        fun release(key: String) {
            inFlight.remove(key)
        }

        fun used(): Int = synchronized(this) { inWindow }

        fun usedUser(): Int = synchronized(this) { inWindowUser }

        /** Сколько миллисекунд осталось до разрешённого авто-снимка (для trailing). */
        fun remainingMs(nowMs: Long): Long = synchronized(this) {
            if (lastAtMs == 0L) 0L else (minIntervalMs - (nowMs - lastAtMs)).coerceAtLeast(0L)
        }

        fun note(line: String) {
            synchronized(this) {
                if (log.size >= maxLog) log.removeAt(0)
                log.add(line)
            }
        }

        fun drain(): List<String> = synchronized(this) { log.toList().also { log.clear() } }

        fun resetForTest() {
            synchronized(this) {
                lastAtMs = 0L; lastKey = null; windowStartMs = 0L; windowStarted = false
                inWindow = 0; inWindowUser = 0
                inFlight.clear(); log.clear()
            }
        }
    }

    /**
     * §2.7/§3 iter42 — итог работы ОДНОГО сборщика диагностики.
     *
     * Отличается от «не было исключения»: здесь видно, сколько секций реально получено, сколько
     * строк подтверждено в файле, и только при выполнении явных критериев сбор считается
     * `completed`. Иначе — `partial` с причиной; недоступный канал — `channel_not_ready`.
     */
    data class DiagReport(
        val block: String,
        val sectionCount: Int,
        val sectionsDefinite: Int,
        val offered: Int,
        val written: Int,
        val failed: Int,
        val dropped: Int,
        val verdict: String,
        val sink: String,
        val status: String,
        val reason: String
    ) {
        val completed: Boolean get() = status == "completed"
    }

    /**
     * iter43 §2.3 — ОРКЕСТРАЦИЯ сбора диагностики: единственный запуск, ограниченные повторы,
     * отмена устаревших отложенных задач.
     *
     * Почему отдельный класс: [`DiagOnce`] — это счётчик попыток, а не планировщик. Он не мешал
     * параллельному запуску двух сборов, не планировал повтор после частичного результата и не
     * отменял «страховочную» проверку через 30 с после того, как сбор уже удался.
     *
     * Гарантии:
     *  • одновременно выполняется не более ОДНОГО сбора ([start] возвращает `false`, если занято);
     *  • после успеха ([Outcome.COMPLETED]) повторов нет, а все ранее запланированные задачи
     *    становятся no-op (проверка поколения);
     *  • после `PARTIAL`/`CHANNEL_NOT_READY` — не более [maxAttempts] попыток, каждая через
     *    [retryDelayMs]; после исчерпания — честный статус `exhausted`;
     *  • нет бесконечных циклов и утечек: число запланированных задач ограничено.
     *
     * Время и планировщик передаются снаружи ([post]), поэтому оркестрацию можно проверить
     * синтетическим временем без Android.
     */
    class DiagRunner(
        private val maxAttempts: Int = 3,
        private val retryDelayMs: Long = 20_000L,
        private val post: (name: String, delayMs: Long, task: () -> Unit) -> Unit
    ) {
        enum class Outcome { COMPLETED, PARTIAL, CHANNEL_NOT_READY, FAILED }

        private val lock = Any()
        private var generation = 0
        private var inFlight = false
        private var attempts = 0
        private var done = false
        private var exhausted = false
        private val notes = ArrayList<String>(32)

        fun completedNow(): Boolean = synchronized(lock) { done }
        fun attempts(): Int = synchronized(lock) { attempts }
        fun isExhausted(): Boolean = synchronized(lock) { exhausted }
        fun isBusy(): Boolean = synchronized(lock) { inFlight }
        fun notes(): List<String> = synchronized(lock) { notes.toList() }
        fun resetForTest() = synchronized(lock) {
            generation++; inFlight = false; attempts = 0; done = false; exhausted = false; notes.clear()
        }

        private fun note(s: String) = synchronized(lock) {
            if (notes.size > 48) notes.removeAt(0)
            notes.add(s)
        }

        /**
         * Запустить сбор.
         *
         * @return `false` — сбор не запущен (уже выполнен, идёт другой или попытки исчерпаны);
         *   причина видна в [notes].
         */
        fun start(
            reason: String,
            run: () -> Outcome
        ): Boolean {
            val gen: Int
            synchronized(lock) {
                if (done) { note("DIAG_ORCH reason=$reason status=skipped why=already_completed"); return false }
                if (inFlight) { note("DIAG_ORCH reason=$reason status=skipped why=in_flight"); return false }
                if (attempts >= maxAttempts) {
                    exhausted = true
                    note("DIAG_ORCH reason=$reason status=skipped why=attempts_exhausted " +
                        "attempts=$attempts/$maxAttempts (это ограничение, а не «данных нет»)")
                    return false
                }
                attempts++
                inFlight = true
                // Запоминаем тело попытки ЗДЕСЬ: повтор обязан выполнять ту же операцию.
                runRef = run
                gen = generation
                note("DIAG_ORCH reason=$reason status=started attempt=$attempts/$maxAttempts")
            }
            var outcome = Outcome.FAILED
            try {
                outcome = run()
            } catch (t: Throwable) {
                outcome = Outcome.FAILED
                note("DIAG_ORCH reason=$reason status=failed why=exception:${t.javaClass.simpleName}")
            } finally {
                synchronized(lock) { inFlight = false }
            }
            finish(reason, gen, outcome)
            return true
        }

        private fun finish(reason: String, gen: Int, outcome: Outcome) {
            synchronized(lock) {
                if (generation != gen) {
                    // Пока шёл сбор, состояние сброшено/сменилось — результат не применяем.
                    note("DIAG_ORCH reason=$reason status=stale_result outcome=${outcome.name.lowercase()}")
                    return
                }
                when (outcome) {
                    Outcome.COMPLETED -> {
                        done = true
                        // Устаревшие отложенные задачи (страховка «через 30 с», повторы) отменяются
                        // сменой поколения: они проверяют его перед запуском.
                        generation++
                        note("DIAG_ORCH reason=$reason status=completed attempt=$attempts/$maxAttempts " +
                            "pending_cancelled=true")
                    }
                    /**
                     * §4.2 iter44: `CHANNEL_NOT_READY` — это НЕ израсходованная попытка сбора:
                     * сбора не было вовсе (канал не готов). Иначе при недоступном канале бюджет
                     * выгорал холостыми заходами, и к моменту реальной готовности собирать было
                     * уже нечем. Счётчик возвращается, исчерпание не объявляется.
                     */
                    Outcome.CHANNEL_NOT_READY -> {
                        attempts = (attempts - 1).coerceAtLeast(0)
                        note("DIAG_ORCH reason=$reason status=wait_channel " +
                            "attempt=$attempts/$maxAttempts (попытка не израсходована: сбора не было)")
                    }
                    Outcome.PARTIAL -> {
                        if (attempts >= maxAttempts) {
                            exhausted = true
                            note("DIAG_ORCH reason=$reason status=exhausted " +
                                "outcome=${outcome.name.lowercase()} attempts=$attempts/$maxAttempts")
                        } else {
                            note("DIAG_ORCH reason=$reason status=retry_scheduled " +
                                "outcome=${outcome.name.lowercase()} delay_ms=$retryDelayMs " +
                                "attempt=$attempts/$maxAttempts")
                        }
                    }
                    Outcome.FAILED -> {
                        if (attempts >= maxAttempts) {
                            exhausted = true
                            note("DIAG_ORCH reason=$reason status=exhausted outcome=failed " +
                                "attempts=$attempts/$maxAttempts")
                        } else {
                            note("DIAG_ORCH reason=$reason status=retry_scheduled outcome=failed " +
                                "delay_ms=$retryDelayMs attempt=$attempts/$maxAttempts")
                        }
                    }
                }
            }
            if (outcome != Outcome.COMPLETED && outcome != Outcome.CHANNEL_NOT_READY &&
                attempts < maxAttempts) {
                val myGen = synchronized(lock) { generation }
                post("JitNet.diagRetry", retryDelayMs) {
                    val go = synchronized(lock) {
                        generation == myGen && !done && !inFlight && attempts < maxAttempts
                    }
                    // ВАЖНО: повтор получает ИСХОДНОЕ тело попытки, а не обёртку над собой —
                    // иначе `runRef` указывал бы на самого себя (StackOverflowError).
                    val original = synchronized(lock) { runRef }
                    if (go && original != null) {
                        start("retry_after_${outcome.name.lowercase()}", original)
                    }
                    else note("DIAG_ORCH reason=retry_after_${outcome.name.lowercase()} " +
                        "status=skipped why=stale_or_completed")
                }
            }
        }

        /**
         * Тело следующей попытки задаётся при первом [start]; храним его, чтобы повтор
         * выполнял ТУ ЖЕ операцию (без дублирования логики в вызывающем коде).
         */
        private var runRef: (() -> Outcome)? = null

        /** Запуск с запоминанием тела попытки (используется повторами). */
        fun startWithRetry(reason: String, run: () -> Outcome): Boolean = start(reason, run)

        /**
         * Запланировать «страховочную» проверку готовности канала: если сбор так и не удался,
         * через [delayMs] сообщается честный статус. После успешного сбора задача становится
         * no-op (проверка поколения и [done]).
         */
        fun scheduleNotReadyReport(
            reason: String,
            delayMs: Long,
            report: () -> Unit
        ) {
            val myGen = synchronized(lock) { generation }
            post("JitNet.diagNotReady", delayMs) {
                val go = synchronized(lock) { generation == myGen && !done }
                if (go) {
                    note("DIAG_ORCH reason=$reason status=not_ready_report fired=true")
                    report()
                } else {
                    note("DIAG_ORCH reason=$reason status=not_ready_report fired=false " +
                        "why=completed_or_stale")
                }
            }
        }
    }

    /**
     * iter43 §3.1 — КОНТРАКТ снимков «до/после включения TTL».
     *
     * Реальность: сбор снимка асинхронен и может занять секунды, а применение TTL выполняется
     * сразу — задерживать переключение тумблера на произвольное время недопустимо (это лишило бы
     * владельца управления). Поэтому «до» НЕ объявляется гарантированным состоянием до изменения:
     * каждая пара получает ЯВНУЮ метку по фактам времени:
     *
     *  • `pre_confirmed` — сбор «до» завершился РАНЬШЕ применения TTL;
     *  • `pre_overlap`   — сбор «до» пересёкся с применением (часть данных уже после изменения);
     *  • `pre_late`      — сбор «до» начался ПОСЛЕ применения: это не «до», а повторный снимок.
     *
     * Для «после» метка `post_after_apply` с ID операции, результатом применения и фактической
     * задержкой стабилизации. Отказ/пропуск диагностики НЕ меняет результат тумблера и не
     * задерживает его.
     */
    class TtlSnapshotFlow {
        /** Фаза снимка (не «машина состояний» JIT: только метка для отчёта). */
        enum class SnapPart { PRE, POST }
        data class Op(
            val id: String,
            val want: Boolean,
            val requestedAt: Long,
            var appliedAt: Long? = null,
            var appliedResult: String = "unknown",
            var preStartedAt: Long? = null,
            var preFinishedAt: Long? = null,
            var postScheduledAt: Long? = null,
            var postCollectedAt: Long? = null
        )

        private val lock = Any()
        private var current: Op? = null
        private var counter = 0

        fun begin(want: Boolean, nowMs: Long): Op = synchronized(lock) {
            counter++
            val op = Op("ttl-" + counter, want, nowMs)
            current = op
            op
        }

        /**
         * §3.2 iter44: все отметки привязаны к КОНКРЕТНОЙ операции. При быстром ON/OFF поздний
         * callback предыдущей операции не должен испортить следующую — проверяем `id` и
         * игнорируем чужой op (возвращаем `false`, чтобы вызывающий мог это записать в лог).
         */
        fun preStarted(op: Op, nowMs: Long): Boolean = applyTo(op) { it.preStartedAt = nowMs }
        fun preFinished(op: Op, nowMs: Long): Boolean = applyTo(op) { it.preFinishedAt = nowMs }
        fun applied(op: Op, nowMs: Long, result: String): Boolean = applyTo(op) {
            it.appliedAt = nowMs
            it.appliedResult = result
        }
        fun postScheduled(op: Op, nowMs: Long): Boolean = applyTo(op) { it.postScheduledAt = nowMs }
        fun postCollected(op: Op, nowMs: Long): Boolean = applyTo(op) { it.postCollectedAt = nowMs }

        /** Применить изменение ТОЛЬКО к текущей операции с тем же id. */
        private fun applyTo(op: Op, block: (Op) -> Unit): Boolean = synchronized(lock) {
            val cur = current
            if (cur == null || cur.id != op.id) return false
            block(cur)
            true
        }

        fun currentOp(): Op? = synchronized(lock) { current }

        /**
         * Метка фазы «до» — по ФАКТИЧЕСКИМ временам сбора, а не по времени заказа.
         *
         * §3.1 iter44: `pre_confirmed` НЕ выдаётся, пока сбор не завершён (`pre_unfinished`), и
         * `appliedAt == null` при завершённом сборе означает именно «применение ещё не начиналось»,
         * а не «мы уверены, что успели».
         */
        fun preLabel(op: Op): String {
            val started = op.preStartedAt
            if (started == null) return "pre_unknown"
            if (op.preFinishedAt == null) return "pre_unfinished"
            val applied = op.appliedAt ?: return "pre_confirmed"
            return when {
                started > applied -> "pre_late"
                (op.preFinishedAt ?: Long.MAX_VALUE) > applied -> "pre_overlap"
                else -> "pre_confirmed"
            }
        }

        /**
         * Метка «после». §3.2 iter44: `post_after_apply` означает только «снимок сделан после
         * применения», и НЕ выдаётся за успешное применение: результат применения виден отдельно
         * (`applied_result`), а при неуспехе метка так и говорит.
         */
        fun postLabel(op: Op): String {
            if (op.appliedAt == null) return "post_without_confirmed_apply"
            return when (op.appliedResult) {
                "ok" -> "post_after_apply"
                else -> "post_after_apply_not_ok:" + op.appliedResult
            }
        }

        fun describe(op: Op, part: SnapPart, snapshotLabel: String): String = when (part) {
            SnapPart.PRE -> "op=${op.id} want=${op.want} phase=pre label=${preLabel(op)} " +
                "requested_at=${op.requestedAt} applied_at=${op.appliedAt ?: "n/a"} " +
                "snapshot=$snapshotLabel"
            SnapPart.POST -> "op=${op.id} want=${op.want} phase=post label=${postLabel(op)} " +
                "applied_at=${op.appliedAt ?: "n/a"} applied_result=«${op.appliedResult}» " +
                "scheduled_at=${op.postScheduledAt ?: "n/a"} snapshot=$snapshotLabel"
        }

        fun resetForTest() = synchronized(lock) { current = null; counter = 0 }
    }

    class DiagOnce(private val maxRuns: Int = 2) {
        private val runs = java.util.concurrent.atomic.AtomicInteger(0)
        private val completed = java.util.concurrent.atomic.AtomicBoolean(false)

        /** `null` — попытки исчерпаны; иначе номер попытки (1..[maxRuns]). */
        fun tryRun(): Int? {
            while (true) {
                val cur = runs.get()
                if (cur >= maxRuns) return null
                if (runs.compareAndSet(cur, cur + 1)) return cur + 1
            }
        }

        /** Отметить исход попытки: `ok` — оба сборщика завершились без сбоя при готовом канале. */
        fun finish(ok: Boolean): Boolean {
            if (ok) completed.set(true)
            return completed.get()
        }

        fun completedNow(): Boolean = completed.get()
        fun runsUsed(): Int = runs.get()
        fun maxRunsAllowed(): Int = maxRuns
        fun exhausted(): Boolean = runs.get() >= maxRuns

        fun status(): String = when {
            completed.get() -> "completed"
            exhausted() -> "exhausted"
            runs.get() > 0 -> "started"
            else -> "idle"
        }
    }
}

