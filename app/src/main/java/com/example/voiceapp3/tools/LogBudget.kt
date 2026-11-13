package com.example.voiceapp3.tools

/**
 * iter39 §3.3 — бюджет строк в датированном логе, отдельно для обычных сообщений и для
 * диагностических блоков.
 *
 * Почему отдельно: общий бюджет (150 КБ / 10 мин) задуман как защита от потока отладочных строк, но
 * под него попадали `INTERNET_DIAG_*` / `JIT_MOUNT_DIAG_*`. Из-за этого сетевые `BEGIN/SECTION/
 * DATA/END` могли исчезнуть молча — ровно та диагностика, ради которой лог и собирается.
 *
 * Что здесь решается (и проверяется тестами на JVM, без Android):
 *  • диагностические блоки не расходуют ОБЩИЙ бюджет и не подменяются его уведомлением;
 *  • у диагностики свой ограниченный объём ([DIAG_BYTES]) и число строк-данных ([DIAG_LINES]);
 *  • при исчерпании пишется ОДНА строка `*_TRUNCATED truncated=true ...` (нумерация частей
 *    остаётся в самих строках `part=i/N`), а дальше содержимое не пишется — молчаливого
 *    исчезновения нет;
 *  • `*_BEGIN`, `*_END` и `*_TRUNCATED` не теряются никогда;
 *  • окно сбрасывается по времени ([WINDOW_MS]), поэтому блоки не пропадают навсегда;
 *  • решения потокобезопасны (конкурирующие записи из разных потоков).
 *
 * Чего это НЕ даёт: гарантии, что физическая запись на устройстве удалась (это проверяется
 * отдельно в `LogFiles`/`AppLog`). Общий бюджет приложения здесь не отключается.
 */
internal object LogBudget {

    /** Общий бюджет файла: 150 КБ за 10 минут (как было). */
    const val BUDGET_BYTES = 150 * 1024L
    const val WINDOW_MS = 10 * 60_000L

    /** Бюджет диагностических блоков: ограниченный, но заметно больше одного блока. */
    const val DIAG_BYTES = 96 * 1024L
    const val DIAG_LINES = 240

    /** Префиксы диагностики в датированном логе. */
    val DIAG_PREFIXES = arrayOf("INTERNET_DIAG_", "JIT_MOUNT_DIAG_")

    /** Строки, которые бюджет диагностики обязан пропустить всегда. */
    private val DIAG_ALWAYS = arrayOf(
        "INTERNET_DIAG_BEGIN", "INTERNET_DIAG_END", "INTERNET_DIAG_TRUNCATED",
        "JIT_MOUNT_DIAG_BEGIN", "JIT_MOUNT_DIAG_END", "JIT_MOUNT_DIAG_TRUNCATED"
    )

    // ─────────────────────────── §5.1 iter41: честный факт записи ───────────────────────────

    /**
     * Что произошло с ОДНОЙ строкой диагностики.
     *
     * Это не «получено сборщиком», а судьба строки в конкретном dated sink:
     *  • [WRITTEN] — запись подтверждена (датированный файл дописан);
     *  • [DROPPED] — строку сознательно НЕ писали: её пропустил бюджет диагностики;
     *  • [FAILED]  — записать не удалось (или подтверждение получить нечем).
     */
    enum class WriteOutcome { WRITTEN, DROPPED, FAILED }

    /**
     * Приёмник строк диагностики. Возвращает ФАКТ записи, а не «принял вызов».
     *
     * `location()` — указуемый файл активного sink на момент вызова: при фоллбэке
     * external→internal он меняется, и тогда части одного блока оказываются в РАЗНЫХ файлах —
     * это видно в сводке (`sink=…`), а не выдаётся за один файл.
     */
    interface Sink {
        /**
         * Записать строку и вернуть ФАКТ записи вместе с ТОЧНЫМ файлом.
         *
         * Файл возвращается каждой записью, а не запрашивается заранее: `LogFiles.append` может
         * переключиться external→internal посреди блока, и тогда части окажутся в разных файлах —
         * это видно в сводке, а не выдаётся за один файл (§2.6 iter42).
         */
        fun write(line: String): LogWrite

        /** Файл, который сейчас считался бы активным (для лога; истина — в [write]). */
        fun location(): String

        /** Независимый канал (logcat/AppLog), когда сама строка блока не подтверждена. */
        fun independent(line: String) {}
    }

    /** Результат записи одной строки: исход + точный файл (пусто, если не записано). */
    data class LogWrite(val outcome: WriteOutcome, val file: String = "")

    /**
     * Учёт фактической записи ОДНОГО диагностического блока.
     *
     * Отвечает на четыре разных вопроса по отдельности: сколько строк предложено, сколько
     * пропущено бюджетом, сколько подтверждено в датированном файле и в каком файле (одном или
     * разных). Только при полном подтверждении в ОДНОМ файле и известном отсутствии усечения
     * разрешено `written=full`; в остальных случаях — честные `truncated`/`partial`/`unknown`.
     */
    class WriteTally(private val sink: Sink) {
        var offered: Int = 0; private set
        var written: Int = 0; private set
        var dropped: Int = 0; private set
        var failed: Int = 0; private set
        private var firstFailure: String? = null

        private val locations = LinkedHashSet<String>()
        /** Строка `…_END`: её собственный факт записи тоже проверяется. */
        var endOutcome: WriteOutcome? = null; private set
        /** Строка `…_END_CONFIRM` (первая после END): её факт записи тоже важен. */
        var endConfirmOutcome: WriteOutcome? = null; private set
        private var endSeen = false

        fun emit(line: String, isEnd: Boolean = false) {
            offered++
            val lw = runCatching { sink.write(line) }
                .getOrElse { LogWrite(WriteOutcome.FAILED, "") }
            when (lw.outcome) {
                WriteOutcome.WRITTEN -> {
                    written++
                    // Точный файл ИМЕННО этой строки (а не «предполагаемый активный»).
                    locations += lw.file.ifEmpty { runCatching { sink.location() }.getOrDefault("unknown") }
                }
                WriteOutcome.DROPPED -> dropped++
                WriteOutcome.FAILED -> {
                    failed++
                    if (firstFailure == null) {
                        firstFailure = "line=${line.take(48).replace('\n', ' ')} at=«" +
                            (lw.file.ifEmpty { "none" }) + "»"
                    }
                }
            }
            if (isEnd) {
                endOutcome = lw.outcome
                endSeen = true
            } else if (endSeen && endConfirmOutcome == null) {
                endConfirmOutcome = lw.outcome
            }
        }

        /** Первая неудачная строка (для причины в отчёте). */
        fun firstFailure(): String = firstFailure ?: "none"

        fun sinkList(): String = locations.joinToString("+").ifEmpty { "none" }

        /**
         * Итоговая метка записи блока.
         *
         * @param truncated `true` — бюджет объявил усечение ИМЕННО этого блока; `null` — неизвестно.
         */
        fun verdict(truncated: Boolean?): String = when {
            offered == 0 -> "none"
            // Ни одна строка не записана и ни одна не пропущена бюджетом — писать некуда.
            written == 0 && dropped == 0 -> "none"
            failed > 0 -> "partial"
            locations.size > 1 -> "partial"
            dropped > 0 -> "truncated"
            truncated == true -> "truncated"
            truncated == null -> "unknown"
            written == offered -> "full"
            else -> "unknown"
        }

        fun reason(truncated: Boolean?): String = when {
            offered == 0 -> "nothing_offered"
            written == 0 && dropped == 0 -> "no_line_written first=$firstFailure"
            failed > 0 -> "write_failed=$failed first=$firstFailure"
            locations.size > 1 -> "split_across_sinks=${sinkList()}"
            dropped > 0 -> "skipped_by_budget=$dropped"
            truncated == true -> "budget_truncated_this_block"
            truncated == null -> "truncation_not_checkable"
            else -> "all_lines_confirmed"
        }

        /** Строки `written=`/`sink=`/`reason=` для `…_END`. */
        fun fields(truncated: Boolean?): String =
            "written=${verdict(truncated)} sink=«${sinkList()}» " +
                "lines=${written}/${offered} skipped_by_budget=$dropped " +
                "write_failed=$failed write_reason=${reason(truncated)}"

        /**
         * iter43 §2.1 — ПРОВИЗИОННЫЕ поля для строки `…_END`.
         *
         * `…_END` физически не может подтвердить саму себя: в момент её формирования неизвестно,
         * в какой файл она попадёт (фоллбэк/ротация могут случиться именно на ней). Поэтому END
         * пишет `written=unconfirmed` и счётчики БЕЗ себя, а итог (`written=…`) сообщается отдельной
         * строкой `…_END_CONFIRM` уже после записи END.
         *
         * Неустранимый предел: последняя строка (`…_END_CONFIRM`) тоже не подтверждает себя —
         * её факт виден только в возвращённом [JitRef.DiagReport] и в независимом канале.
         */
        fun provisionalFields(truncated: Boolean?): String {
            val linesBeforeEnd = (offered - 1).coerceAtLeast(0)
            return "written=unconfirmed lines_before_end=$written/$linesBeforeEnd " +
                "skipped_by_budget=$dropped write_failed=$failed " +
                "sink_hint=«${sinkList()}» truncation=${truncated?.toString() ?: "unknown"} " +
                "note=\"итог сообщается отдельной строкой _END_CONFIRM: END не подтверждает сам себя\""
        }

        /** Итоговые поля для `…_END_CONFIRM` (после записи END). */
        fun confirmFields(block: String, truncated: Boolean?): String =
            "block=$block written=${verdict(truncated)} sink=«${sinkList()}» " +
                "lines=${written}/${offered} skipped_by_budget=$dropped write_failed=$failed " +
                "write_reason=${reason(truncated)} end_written=${endOutcome?.name?.lowercase() ?: "unknown"} " +
                "note=\"эта строка сама себя не подтверждает; её факт — в DiagReport и независимом канале\""
    }

    fun isDiagLine(msg: String): Boolean {
        val m = msg.trimStart()
        for (p in DIAG_PREFIXES) if (m.startsWith(p)) return true
        return false
    }

    fun isDiagAlways(msg: String): Boolean {
        val m = msg.trimStart()
        for (p in DIAG_ALWAYS) if (m.startsWith(p)) return true
        return false
    }

    /** Начало диагностического блока: `…_BEGIN`. */
    fun isDiagBegin(msg: String): Boolean = msg.trimStart().endsWith("_BEGIN") &&
        isDiagLine(msg)

    /** Итоговая строка блока: `…_END`. */
    fun isDiagEnd(msg: String): Boolean = msg.trimStart().startsWith("INTERNET_DIAG_END") ||
        msg.trimStart().startsWith("JIT_MOUNT_DIAG_END")

    /** Метка блока (`INTERNET_DIAG` / `JIT_MOUNT_DIAG`) для строки усечения. */
    fun diagLabel(msg: String): String {
        val m = msg.trimStart()
        return if (m.startsWith("JIT_MOUNT_DIAG")) "JIT_MOUNT_DIAG" else "INTERNET_DIAG"
    }

    /**
     * Решение по строке.
     *
     * @param write писать ли саму строку
     * @param notice отдельная строка-уведомление (пишется независимо от [write]); `null` — нет
     */
    data class Verdict(val write: Boolean, val notice: String? = null)

    /**
     * Общий бюджет приложения (обычные сообщения). Логика та же, что была в `AppLog`:
     * превышение → один `LOG_BUDGET exceeded=… action=trim`, далее одна сводка в минуту.
     * Строки ДЕЙСТВИЙ (`isActionLine`) не расходуют бюджет и не режутся.
     */
    class AppState(private val bytes: Long = BUDGET_BYTES, private val windowMs: Long = WINDOW_MS) {
        private var windowStart = 0L
        private var used = 0L
        private var trim = false
        private var dropped = 0L
        private var lastSummary = 0L

        @Synchronized
        fun decide(lenBytes: Int, nowMs: Long, exempt: Boolean, summaryEveryMs: Long = 60_000L): Verdict {
            if (exempt) return Verdict(true)
            if (windowStart == 0L) windowStart = nowMs
            if (nowMs - windowStart > windowMs) {
                windowStart = nowMs
                used = 0L
                trim = false
                dropped = 0L
            }
            if (!trim) {
                used += lenBytes
                if (used > bytes) {
                    trim = true
                    lastSummary = 0L
                    return Verdict(true, "LOG_BUDGET exceeded=$used action=trim window=10min limit=$bytes")
                }
                return Verdict(true)
            }
            dropped++
            if (nowMs - lastSummary >= summaryEveryMs) {
                lastSummary = nowMs
                return Verdict(false, "LOG_BUDGET action=trim dropped=$dropped bytes=$used (сводка раз в минуту)")
            }
            return Verdict(false)
        }

        @Synchronized
        fun usedBytes(): Long = used

        @Synchronized
        fun droppedLines(): Long = dropped

    }

    /**
     * Бюджет диагностических блоков. Не зависит от [AppState]: строки диагностики не «съедают»
     * общий лимит и не подменяются его уведомлением.
     *
     * **§2.1 iter44 — отбрасывания строк больше НЕТ.** Прежний `DiagState` при исчерпании
     * `DIAG_BYTES`/`DIAG_LINES` ПЕРЕСТАВАЛ писать `SECTION`/`DATA`: физическая потеря данных
     * диагностики, о которой лишь предупреждала строка `TRUNCATED`. Теперь:
     *  • `decide()` всегда возвращает `write=true`;
     *  • при выходе за прежние пороги один раз на блок за окно выдаётся ОТДЕЛЬНАЯ строка
     *    `"<LABEL>_BUDGET_HIGH …"` — она не подменяет данные;
     *  • объём ограничен файловым слоем ([LogFiles]: ротация целыми частями, лимит частей,
     *    retention), то есть конечный объём вместо потери строк;
     *  • `truncated()`/`truncatedFor()` всегда `false`, `droppedLines()` = 0.
     */
    class DiagState(
        private val bytes: Long = DIAG_BYTES,
        private val lines: Int = DIAG_LINES,
        private val windowMs: Long = WINDOW_MS
    ) {
        private var windowStart = 0L
        private var used = 0L
        private var dataLines = 0
        /** §2.1 iter44: отброшенных строк нет — поле оставлено для совместимости отчётов. */
        private var dropped = 0L
        /** Блоки, по которым в этом окне уже писалась строка `*_BUDGET_HIGH`. */
        private val announced = LinkedHashMap<String, Boolean>()
        /** Сколько раз за окно прежний ориентир блока был превышен (справочно). */
        private val overCount = LinkedHashMap<String, Long>()

        @Synchronized
        fun decide(msg: String, lenBytes: Int, nowMs: Long): Verdict {
            if (windowStart == 0L) windowStart = nowMs
            if (nowMs - windowStart > windowMs) {
                windowStart = nowMs
                used = 0L
                dataLines = 0
                announced.clear()
                overCount.clear()
            }
            val label = diagLabel(msg)
            used += lenBytes
            dataLines++
            if (used <= bytes && dataLines <= lines) return Verdict(true)
            overCount[label] = (overCount[label] ?: 0L) + 1L
            if (announced[label] != true) {
                announced[label] = true
                return Verdict(
                    true,
                    label + "_BUDGET_HIGH over_bytes=$used over_lines=$dataLines " +
                        "soft_limit_bytes=$bytes soft_limit_lines=$lines block=$label " +
                        "action=continue_writing dropped=0 " +
                        "(прежний ориентир превышен; строки НЕ отбрасываются — объём ограничен " +
                        "ротацией и retention файлового слоя, потери нет)"
                )
            }
            return Verdict(true)
        }

        @Synchronized
        fun usedBytes(): Long = used

        /** §2.1 iter44: сколько строк отброшено — всегда 0 (строки не отбрасываются). */
        @Synchronized
        fun droppedLines(): Long = dropped

        /** §2.1 iter44: усечения больше НЕТ — оставлено для совместимости сборщиков. */
        @Synchronized
        fun truncated(): Boolean = false

        /** §2.1 iter44: усечения ИМЕННО этого блока нет (строки не отбрасываются). */
        @Synchronized
        fun truncatedFor(label: String): Boolean = false

        /** §2.1 iter44: сколько строк отброшено у блока — всегда 0. */
        @Synchronized
        fun droppedFor(label: String): Long = 0L

        /** §2.1 iter44: сколько раз за окно прежний ориентир блока превышен (справочно). */
        @Synchronized
        fun overBudgetFor(label: String): Long = overCount[label] ?: 0L
    }
}
