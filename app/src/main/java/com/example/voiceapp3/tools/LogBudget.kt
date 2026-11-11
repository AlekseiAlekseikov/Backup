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
     */
    class DiagState(
        private val bytes: Long = DIAG_BYTES,
        private val lines: Int = DIAG_LINES,
        private val windowMs: Long = WINDOW_MS
    ) {
        private var windowStart = 0L
        private var used = 0L
        private var dataLines = 0
        private var dropped = 0L
        private var truncatedAnnounced = false

        @Synchronized
        fun decide(msg: String, lenBytes: Int, nowMs: Long): Verdict {
            if (windowStart == 0L) windowStart = nowMs
            if (nowMs - windowStart > windowMs) {
                windowStart = nowMs
                used = 0L
                dataLines = 0
                dropped = 0L
                truncatedAnnounced = false
            }
            // Начало и конец блока не теряются: без них блок в логе не читается.
            if (isDiagAlways(msg)) return Verdict(true)
            if (used + lenBytes > bytes || dataLines >= lines) {
                dropped++
                if (!truncatedAnnounced) {
                    truncatedAnnounced = true
                    val label = diagLabel(msg)
                    return Verdict(
                        false,
                        "$label" + "_TRUNCATED truncated=true dropped=$dropped " +
                            "bytes=$used limit_bytes=$bytes limit_lines=$lines " +
                            "reason=diag_budget_exhausted (дальнейшие строки блока не пишутся; " +
                            "начала/окончания блоков, разделы и сводка сохраняются; " +
                            "общий бюджет приложения не отключался)"
                    )
                }
                return Verdict(false)
            }
            used += lenBytes
            dataLines++
            return Verdict(true)
        }

        @Synchronized
        fun usedBytes(): Long = used

        @Synchronized
        fun droppedLines(): Long = dropped

        @Synchronized
        fun truncated(): Boolean = truncatedAnnounced
    }
}
