package com.example.voiceapp3.tools

import android.content.Context

/**
 * Read-only диагностика сети/монтирований и TTL-снимки (пассивный канал [DiagShell]).
 *
 * iter65: клик/продолжение/сверка JIT отсюда удалены — механизм референса живёт в [JitMech]
 * (Android-адаптеры — [JitMechAndroid], возврат Wi-Fi — `JitWatchdogService`).
 */
object JitNet {

    private const val TAG = "CENTRAL_JitNet"

    /**
     * iter39 §3.1 — shell ТОЛЬКО для диагностики: пассивный путь транспорта.
     *
     * Отдельный объект, а не [AndroidShell]: клик JIT и сверка должны сохранить прежнее поведение
     * транспорта (включая восстановление канала), а диагностика — не иметь к нему доступа вовсе.
     * Пассивный путь не запускает bootstrap, переподключение, replay и `ctl.restart adbd`; при
     * недоступности возвращает `ERR_ADB_PASSIVE_UNAVAILABLE`, который сборщики показывают как
     * `unavailable`/`channel_not_ready`, а не как «нет root» или «пусто».
     */
    internal object DiagShell : JitRef.JitShell {
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> = runCatching {
            AdbClient.executePassive(command, timeoutMs)
        }.getOrElse { t ->
            Pair(AdbPassive.RC_UNAVAILABLE,
                "ERR_ADB_PASSIVE_UNAVAILABLE reason=exception:${t.javaClass.simpleName}")
        }

        /**
         * iter67c §1: пассивный канал НЕ умеет доставлять скрипты — и не должен уметь.
         * Явный отказ с названной причиной, а не исключение и не молчаливый инлайн: иначе
         * диагностика могла бы выполнить длинную команду в обход своей же политики.
         */
        override fun execScript(name: String, text: String, timeoutMs: Int): Pair<Int, String> =
            Pair(AdbPassive.RC_UNAVAILABLE,
                "ERR_ADB_PASSIVE_UNAVAILABLE reason=script_not_supported")

        /** `/proc/self/maps` читающего процесса — к диагностике сети/монтирований не относится. */
        override fun mapsExcerpt(): String? = null
    }

    // ─────────────────────────── одноразовая read-only диагностика ───────────────────────────

    /** Процессный флаг: право на попытку уже занято (вход в диагностику один раз за процесс). */
    private val diagAttempted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * §5.2 iter41: попытки сбора (не более двух за процесс) и ФАКТ успешного завершения.
     * «Запущен» больше не выдаётся за «собран».
     */
    @Volatile private var diagOnce = JitRef.DiagOnce(2)

    /**
     * §5.1 iter41 — приёмник диагностических строк для боевого пути.
     *
     * Возвращает ФАКТ записи: `AppLog.diagLine` отдаёт результат `LogFiles.append` (датированный
     * файл), а не «вызов принят». `DROPPED` — строку сознательно не писали (бюджет диагностики);
     * `FAILED` — не подтверждено. Сам лог-приёмник ничего решать не может: сеть он не трогает.
     */
    internal object DiagSink : LogBudget.Sink {
        override fun write(line: String): LogBudget.LogWrite {
            val r = runCatching { AppLog.diagLineResult(TAG, line) }
                .getOrElse { t -> LogFiles.Result.fail("io:" + t.javaClass.simpleName) }
            if (r.written) return LogBudget.LogWrite(LogBudget.WriteOutcome.WRITTEN, r.file)
            // Не подтверждено: отличаем «бюджет не пустил» от «запись не удалась».
            val label = LogBudget.diagLabel(line)
            val out = if (LogBudget.isDiagAlways(line) || !AppLog.diagTruncatedFor(label)) {
                LogBudget.WriteOutcome.FAILED
            } else {
                LogBudget.WriteOutcome.DROPPED
            }
            return LogBudget.LogWrite(out, "")
        }

        override fun location(): String = runCatching { LogFiles.currentPath() }.getOrDefault("unknown")

        override fun independent(line: String) {
            // Независимый канал: logcat + обычный (не диагностический) путь лога.
            runCatching { android.util.Log.e("CENTRAL_DiagWrite", line) }
            runCatching { AppLog.e(TAG, line) }
        }
    }

    /** Лок на однократную отложенную запись «канал пока не готов» (§3.4). */
    private val diagFallbackLock = Any()

    /**
     * Одноразовая **read-only** диагностика: монтирования ([JitMountDiag]) и сеть ([InternetDiag]).
     *
     * Требования, которые здесь соблюдены: запуск в фоне (не UI-поток), не чаще одной попытки за
     * процесс, без удержания рабочего потока ожиданием канала, без кнопок, без связи с кликом JIT,
     * без единой изменяющей команды. У каждой части свои метки `*_BEGIN/_SECTION/_DATA/_END` и
     * свои ошибки: провал одной не рушит вторую.
     *
     * Готовность канала: если он ещё не готов, ставится ОДИН обработчик существующего события
     * готовности (`AdbClient.onChannelReadyOnce`) и одна отложенная проверка на 30 с. Бесконечных
     * повторов и нового сторожа нет; при неготовности пишется честный `channel_not_ready`, а не
     * «нет root» и не «нет интернета».
     *
     * §5.2 iter41: успешный сбор фиксируется ТОЛЬКО по факту завершения обоих сборщиков при
     * готовом канале; неудачная попытка не считается собранной и не лишает владельца ограниченной
     * повторной попытки ([JitRef.DiagOnce], максимум 2 внешних набора проверок).
     */
    fun requestDiagOnce(
        app: Context,
        source: String,
        delayMs: Long = 0L,
        netInfo: InternetDiag.NetInfo? = null
    ) {
        // §4.2 iter44: одиночный флаг больше не «сжигает» диагностику навсегда. Раньше вторая
        // попытка после события готовности канала молча ничего не делала. Теперь повторный вход
        // запрещён только когда сбор УЖЕ завершён; число реальных сборов по-прежнему ограничено
        // ([JitRef.DiagOnce], 2 запуска на процесс).
        if (!markDiagAttempted() && diagOnce.completedNow()) {
            AppLog.i(TAG, "DIAG source=$source status=skipped why=already_completed " +
                "runs=${diagOnce.runsUsed()}/${diagOnce.maxRunsAllowed()} " +
                "(сбор уже дал результат: повторный не нужен)")
            return
        }
        val appVersion = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull() ?: "unknown"

        /**
         * Полный сбор (канал готов).
         *
         * §2.7 iter42: «не было исключения» больше НЕ считается успехом. Статус берётся из
         * [JitRef.DiagReport]: критерии — готовый канал, отсутствие секций `unknown`/`timeout`,
         * отсутствие неудачных записей и вердикт записи `full`/`truncated`. Частичный результат
         * честно остаётся частичным и не закрывает ограниченную повторную попытку.
         */
        val runBoth: (String) -> Boolean = { via ->
            val attempt = diagOnce.tryRun()
            if (attempt == null) {
                AppLog.i(TAG, "DIAG via=$via status=exhausted runs=${diagOnce.runsUsed()}/" +
                    "${diagOnce.maxRunsAllowed()} completed=${diagOnce.completedNow()} " +
                    "(попытки сбора исчерпаны: это ограничение, а не «данных нет»)")
                false
            } else {
                val mount = runCatching {
                    JitMountDiag.collectWithReport(DiagShell, true, DiagSink, "$source/$via") {
                        AppLog.diagTruncatedFor("JIT_MOUNT_DIAG")
                    }
                }.onFailure { t ->
                    AppLog.i(TAG, "JIT_MOUNT_DIAG source=$source/$via status=failed " +
                        "reason=collector_exception:${t.javaClass.simpleName} " +
                        "(сбой сборщика, а не результат чтения)")
                }.getOrNull()
                val net = runCatching {
                    InternetDiag.collectWithReport(DiagShell, true, netInfo, DiagSink, "$source/$via", appVersion) {
                        AppLog.diagTruncatedFor("INTERNET_DIAG")
                    }
                }.onFailure { t ->
                    AppLog.i(TAG, "INTERNET_DIAG source=$source/$via status=failed " +
                        "reason=collector_exception:${t.javaClass.simpleName} " +
                        "(сбой сборщика, сеть не менялась)")
                }.getOrNull()
                for (r in listOfNotNull(mount?.info, net?.info)) {
                    AppLog.i(TAG, "DIAG_SECTION_RESULT block=${r.block} status=${r.status} " +
                        "sections=${r.sectionCount} definite=${r.sectionsDefinite} " +
                        "lines=${r.written}/${r.offered} failed=${r.failed} dropped=${r.dropped} " +
                        "written=${r.verdict} sink=«${r.sink}» reason=${r.reason}")
                }
                val ok = mount?.info?.completed == true && net?.info?.completed == true
                val completed = diagOnce.finish(ok)
                val st = when {
                    ok -> "completed"
                    mount == null || net == null -> "failed"
                    mount.info.status == "channel_not_ready" || net.info.status == "channel_not_ready" ->
                        "channel_not_ready"
                    else -> "partial"
                }
                AppLog.i(TAG, "DIAG via=$via run=$attempt/${diagOnce.maxRunsAllowed()} " +
                    "status=$st mount=${mount?.info?.status ?: "failed"} network=${net?.info?.status ?: "failed"} " +
                    "collected=$completed " +
                    "(успех — только при выполнении критериев; частичный сбор остаётся частичным и " +
                    "не закрывает ограниченную повторную попытку)")
                ok
            }
        }
        // §3.4: отложенная честная запись «канал пока не готов» НЕ занимает успешный сбор.
        var fallbackWrote = false
        val treatNotReady: (String) -> Unit = { why ->
            val once = synchronized(diagFallbackLock) {
                if (fallbackWrote) false else { fallbackWrote = true; true }
            }
            if (once) {
                runCatching {
                    JitMountDiag.collect(DiagShell, false, DiagSink, source) {
                        AppLog.diagTruncatedFor("JIT_MOUNT_DIAG")
                    }
                }.onFailure { t -> AppLog.i(TAG, "JIT_MOUNT_DIAG status=unknown reason=${t.javaClass.simpleName}") }
                // §4.2 iter40: канал недоступен, но Android API-часть читается независимо —
                // её и записываем (shell-разделы честно `channel_not_ready`).
                runCatching {
                    InternetDiag.collect(
                        DiagShell, false, netInfo, DiagSink, "$source/fallback:$why", appVersion
                    ) { AppLog.diagTruncatedFor("INTERNET_DIAG") }
                }.onFailure { t ->
                    AppLog.i(TAG, "INTERNET_DIAG status=unknown reason=${t.javaClass.simpleName}")
                }
                AppLog.i(TAG, "DIAG source=$source status=partial_android_only " +
                    "reason=$why collected=${diagOnce.completedNow()} " +
                    "(shell-разделы не собраны: это НЕ успешный сбор; сбор по готовности канала " +
                    "ещё возможен в пределах лимита попыток)")
            }
        }

        /**
         * §2.3 iter43 — оркестрация: один сбор одновременно, ограниченные повторы по факту
         * готовности канала/частичного результата, отмена устаревшей «страховки» после успеха.
         */
        val runner = JitRef.DiagRunner(
            maxAttempts = 2,
            retryDelayMs = 20_000L
        ) { name, delay, task -> BgWorker.postDelayed(name, delay, task) }

        lateinit var attempt: () -> JitRef.DiagRunner.Outcome
        attempt = attempt@{
            BgWorker.assertNotMain("JitNet.requestDiagOnce")
            val ready = runCatching { AdbClient.isChannelReady() }.getOrDefault(false)
            if (!ready) {
                AppLog.i(TAG, "DIAG source=$source status=channel_not_ready action=wait_event " +
                    "(канал приложения ещё не готов: это НЕ отсутствие root; ждём существующее " +
                    "событие готовности, повторов без него нет)")
                runCatching {
                    AdbClient.onChannelReadyOnce {
                        BgWorker.post("JitNet.diagOnReady") {
                            runner.startWithRetry("after_ready_event", attempt)
                        }
                    }
                }
                // Одна ограниченная «страховка»: если канал так и не поднялся, статус будет честным.
                runner.scheduleNotReadyReport("channel_not_ready", 30_000L) {
                    treatNotReady("channel_not_ready_after_30s")
                }
                JitRef.DiagRunner.Outcome.CHANNEL_NOT_READY
            } else {
                val ok = runBoth("attempt")
                if (ok) JitRef.DiagRunner.Outcome.COMPLETED else JitRef.DiagRunner.Outcome.PARTIAL
            }
        }

        val task: () -> Unit = {
            runner.startWithRetry("initial", attempt)
            for (n in runner.notes()) AppLog.i(TAG, n)
        }
        if (delayMs > 0) BgWorker.postDelayed("JitNet.diag", delayMs, task)
        else BgWorker.post("JitNet.diag", task)
    }

    // ─────────────────────── событийные снимки сети: «до/после» (iter42 §3) ───────────────────────

    /**
     * Ограничитель событийных снимков: не чаще 90 с, не больше 6 в час, без дублей.
     * Один экземпляр на процесс.
     */
    private val eventSnaps = JitRef.EventSnapshots()

    /** Ключи в полёте — чтобы поздний debounce одного события не перекрыл другое. */
    private val eventInFlight = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * §3 iter42 — СРАВНИМЫЙ снимок сети по СУЩЕСТВУЮЩЕМУ событию (включение/выключение TTL,
     * смена Wi-Fi/активной сети). Никаких новых кнопок и никаких изменений сети: только чтение.
     *
     * Зачем: в логе владельца 12.11 снимок сети есть ДО включения TTL (18:56) и отсутствует
     * ПОСЛЕ (19:00) — сравнить «до/после» нечем.
     *
     * При шторме событий выполняется ОДИН снимок устойчивого состояния: `postDelayed` на
     * [stableDelayMs], при этом дубли отсекаются, а факт пропуска пишется с причиной.
     * Результат не объявляется заранее: строка `SNAP reason=… status=…` пишется по факту.
     */
    fun requestEventSnapshot(
        app: Context,
        reason: String,
        netInfo: InternetDiag.NetInfo? = null,
        stableDelayMs: Long = 15_000L,
        /** Снимок парный к предыдущему (`ttl_on_pre` → `ttl_on_post`): интервал не режет пару. */
        pairWith: String? = null,
        /** `true` — событие вызвано ДЕЙСТВИЕМ ВЛАДЕЛЬЦА (переключение TTL): своя квота. */
        userEvent: Boolean = false,
        /** Явная метка фазы (из [JitRef.TtlSnapshotFlow]): `pre_confirmed`/`pre_overlap`/`post_after_apply`. */
        phaseLabel: String? = null,
        /**
         * Метка фазы, вычисляемая В МОМЕНТ фактического сбора: «до» может пересёкся с применением
         * TTL, и честная метка зависит от времени сбора, а не от времени заказа.
         */
        phaseLabelProvider: (() -> String)? = null,
        /** Вызывается после фактического сбора (для меток «до»/«после»). */
        onCollected: (() -> Unit)? = null,
        /**
         * §3.1 iter44: вызывается В МОМЕНТ фактического начала сбора, а не при заказе.
         * Именно это время — начало «до»-снимка.
         */
        onCollectStart: (() -> Unit)? = null
    ) {
        val appVersion = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val key = "snap:" + reason.substringBefore(':')
        val now = System.currentTimeMillis()
        val skip = when {
            userEvent && pairWith != null -> eventSnaps.tryAcquireUserPaired(key, now, "snap:$pairWith")
            userEvent -> eventSnaps.tryAcquireUser(key, now)
            pairWith != null -> eventSnaps.tryAcquirePaired(key, now, "snap:$pairWith")
            else -> eventSnaps.tryAcquire(key, now)
        }
        val label = if (phaseLabel != null) " label=$phaseLabel" else ""
        if (skip != null) {
            AppLog.i(TAG, "SNAP reason=$reason status=skipped why=$skip$label " +
                "used_last_hour=${eventSnaps.used()} used_user=${eventSnaps.usedUser()} " +
                "(снимок не делается: ограничение частоты/дубль; состояние сети не менялось)")
            // §3.3 iter43: шторм не должен «съесть» итоговое состояние — планируется ОДИН
            // trailing-снимок после окна частоты (не периодический watchdog, не цикл).
            if (skip.startsWith("rate_limited")) {
                scheduleTrailing(reason, netInfo, appVersion, label)
            }
            return
        }
        if (eventInFlight.putIfAbsent(key, true) != null) {
            eventSnaps.release(key)
            AppLog.i(TAG, "SNAP reason=$reason status=skipped why=in_flight$label " +
                "(второй снимок не нужен)")
            return
        }
        AppLog.i(TAG, "SNAP reason=$reason status=scheduled delay_ms=$stableDelayMs " +
            "pair=${pairWith ?: "none"}$label " +
            "note=\"снимок после стабилизации: одно событие сетевого шторма не даёт серию снимков\"")
        BgWorker.postDelayed("JitNet.eventSnapshot", stableDelayMs) {
            try {
                // §3.1 iter44: фактическое начало сбора (не время заказа).
                runCatching { onCollectStart?.invoke() }
                val report = runCatching {
                    InternetDiag.collectWithReport(
                        DiagShell, AdbClient.isChannelReady(), netInfo, DiagSink,
                        "event:$reason", appVersion
                    ) { AppLog.diagTruncatedFor("INTERNET_DIAG") }
                }.onFailure { t ->
                    AppLog.i(TAG, "SNAP reason=$reason status=failed$label " +
                        "why=collector_exception:${t.javaClass.simpleName} (сеть не менялась)")
                }.getOrNull()
                val labelDone = phaseLabelProvider?.let { p ->
                    runCatching { " label_done=" + p() }.getOrDefault("")
                } ?: ""
                // §3.1 iter44: статическая метка фазы, посчитанная при заказе, может устареть —
                // в лог идёт и она (`label=`), и фактическая на момент сбора (`label_done=`).
                AppLog.i(TAG, "SNAP reason=$reason status=${report?.info?.status ?: "failed"}$label$labelDone " +
                    "sections=${report?.info?.sectionCount ?: 0} written=${report?.info?.verdict ?: "none"} " +
                    "sink=«${report?.info?.sink ?: "none"}» why=${report?.info?.reason ?: "failed"} " +
                    "(это факты, а не диагноз: единый снимок на событие; сеть не менялась)")
            } finally {
                eventInFlight.remove(key)
                eventSnaps.release(key)
                runCatching { onCollected?.invoke() }
            }
        }
    }

    // ───────────────── §3.1 iter43: пара снимков «до/после» включения-выключения TTL ─────────────────

    /** Состояние пары «до/после» для переключения TTL (одна операция на переключение). */
    private val ttlFlow = JitRef.TtlSnapshotFlow()

    internal fun ttlSnapshotForTest(): JitRef.TtlSnapshotFlow.Op? = ttlFlow.currentOp()

    /**
     * Начать пару снимков для РЕАЛЬНОГО переключения TTL.
     *
     * Контракт (§3.1): «до» не задерживает переключение и не гарантируется как состояние строго до
     * изменения — метка (`pre_confirmed`/`pre_overlap`/`pre_late`) вычисляется по фактическим
     * временам и пишется в лог. Отказ сбора не влияет на результат тумблера.
     */
    fun beginTtlSnapshot(app: Context, want: Boolean, netInfo: InternetDiag.NetInfo? = null):
        JitRef.TtlSnapshotFlow.Op {
        val op = ttlFlow.begin(want, System.currentTimeMillis())
        requestEventSnapshot(
            app, "ttl_" + (if (want) "on" else "off") + "_pre", netInfo,
            stableDelayMs = 0L, userEvent = true,
            // §3.1 iter44: «до» начинается тогда, когда сбор РЕАЛЬНО начался, а не при заказе.
            onCollectStart = { ttlFlow.preStarted(op, System.currentTimeMillis()) },
            phaseLabelProvider = { ttlFlow.preLabel(op) },
            onCollected = { ttlFlow.preFinished(op, System.currentTimeMillis()) }
        )
        return op
    }

    /**
     * Завершить пару: зафиксировать ФАКТ применения TTL (его результат) и заказать «после»
     * с ограниченной задержкой стабилизации. Метка `post_after_apply` подтверждает порядок.
     */
    fun finishTtlSnapshot(
        app: Context,
        op: JitRef.TtlSnapshotFlow.Op,
        appliedResult: String,
        netInfo: InternetDiag.NetInfo? = null,
        stableDelayMs: Long = 15_000L
    ) {
        // §3.2 iter44: изменения применяются ТОЛЬКО к своей операции; если за это время началась
        // следующая (быстрый ON/OFF), старая отметка не испортит её состояние.
        val applied = ttlFlow.applied(op, System.currentTimeMillis(), appliedResult)
        ttlFlow.postScheduled(op, System.currentTimeMillis())
        requestEventSnapshot(
            app, "ttl_" + (if (op.want) "on" else "off") + "_post", netInfo,
            stableDelayMs = stableDelayMs, pairWith = "ttl_" + (if (op.want) "on" else "off") + "_pre",
            userEvent = true,
            // Метка считается В МОМЕНТ СБОРА (при заказе она могла бы устареть).
            phaseLabelProvider = { ttlFlow.postLabel(op) },
            onCollected = {
                ttlFlow.postCollected(op, System.currentTimeMillis())
            }
        )
        AppLog.i(TAG, "TTL_SNAP_PAIR applied_to_op=$applied " +
            ttlFlow.describe(op, JitRef.TtlSnapshotFlow.SnapPart.POST, "ordered") +
            " pre_label_now=" + ttlFlow.preLabel(op) +
            " (метка «до» окончательна после сбора; сеть/TTL этим кодом не менялись)")
    }

    internal fun resetTtlSnapshotsForTest() {
        ttlFlow.resetForTest()
        trailingScheduled.set(false)
    }

    /** Не более одного запланированного trailing-снимка одновременно. */
    private val trailingScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * §3.3 iter43 — ОДИН отложенный снимок устойчивого состояния после затихания шторма.
     * Ограничен по времени (≤ окна частоты) и по количеству (один одновременно), без повторов.
     */
    private fun scheduleTrailing(
        reason: String,
        netInfo: InternetDiag.NetInfo?,
        appVersion: String,
        label: String
    ) {
        if (!trailingScheduled.compareAndSet(false, true)) return
        val delay = eventSnaps.remainingMs(System.currentTimeMillis()).coerceIn(1_000L, 90_000L)
        AppLog.i(TAG, "SNAP reason=$reason status=trailing_scheduled delay_ms=$delay$label " +
            "note=\"шторм не теряет итоговое состояние: снимок один, не цикл\"")
        BgWorker.postDelayed("JitNet.eventSnapshotTrailing", delay) {
            trailingScheduled.set(false)
            runCatching {
                InternetDiag.collectWithReport(
                    DiagShell, AdbClient.isChannelReady(), netInfo, DiagSink,
                    "event:trailing:$reason", appVersion
                ) { AppLog.diagTruncatedFor("INTERNET_DIAG") }
            }.onFailure { t ->
                AppLog.i(TAG, "SNAP reason=trailing:$reason status=failed " +
                    "why=${t.javaClass.simpleName}")
            }.onSuccess { r ->
                AppLog.i(TAG, "SNAP reason=trailing:$reason status=${r.info.status} " +
                    "written=${r.info.verdict} sink=«${r.info.sink}» why=${r.info.reason} " +
                    "(устойчивое состояние после шторма; сеть не менялась)")
            }
        }
    }

    /** Только для тестов: сбросить ограничитель событийных снимков. */
    internal fun resetEventSnapshotsForTest() {
        eventSnaps.resetForTest()
        eventInFlight.clear()
    }

    /** Только для тестов: состояние ограничителя. */
    internal fun eventSnapshotsUsedForTest(): Int = eventSnaps.used()

    /** Занять единственную попытку сбора в этом процессе (одноразовый флаг, без повторов). */
    internal fun markDiagAttempted(): Boolean = diagAttempted.compareAndSet(false, true)

    /** Только для тестов: сбросить процессные флаги одноразового сбора. */
    internal fun resetDiagForTest() {
        diagAttempted.set(false)
        diagOnce = JitRef.DiagOnce(2)
    }

    /** Только для тестов/логов: состояние ограниченного сбора (запущен/завершён/исчерпан). */
    internal fun diagStatusForTest(): String = diagOnce.status()
}
