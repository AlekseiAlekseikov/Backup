package com.example.voiceapp3.tools

/**
 * iter67 §C — решение о разблокировке «застрявшей» операции: всегда с названным выходом.
 *
 * Дефект (измерено на живой ГУ): `JIT_OP_ABANDONED = 0` и `JIT_OP_RESOLVED = 0` при 426+ строках
 * `JIT_PEDIDO kept`. Причины две:
 *  1. `ts` отметки при повторной отправке НЕ обновлялся, поэтому возраст считался от первой
 *     попытки и окно `STALE_MARK_MS` не наступало никогда (`ts=-196980728` уходило в
 *     `Toggles45.isStale` → `false` → блокировка 6 суток);
 *  2. решения о разблокировке не логировались: выход был, строки не было.
 *
 * Здесь решение отделено от записи/журнала и проверяется JVM-тестом:
 *  • `exit ∈ {window_expired, zygote_after_mark, boot_changed, mark_invalid_*, terminal_finished, none}`;
 *  • `clicks=allowed` — ровно когда назван выход;
 *  • снимок Wi-Fi при разблокировке НЕ применяется (это отдельный факт, правка I).
 *
 * Модуль чистый (без Android и без журнала): вызывающий печатает одну строку `JIT_UNBLOCK`.
 */

/** Состояние отметки, из которого нельзя ничего откатить автоматически. */
enum class MarkVerdict { OK, ABSENT, NEGATIVE, ZERO, FUTURE, UNIT_MISMATCH, UNREADABLE }

/** Решение: вышел ли клик из неопределённости и почему. */
data class UnblockDecision(
    val exit: String,
    val clicksAllowed: Boolean,
    val snapshotApplied: Boolean,
    val ageMs: Long
) {
    /** Выход назван — значит, клик разрешён. */
    val allowed: Boolean get() = clicksAllowed

    fun line(op: String, limitMs: Long, bootSame: Boolean): String =
        "JIT_UNBLOCK op=$op age_ms=$ageMs limit_ms=$limitMs boot_same=${if (bootSame) "1" else "0"} " +
            "exit=$exit clicks=${if (clicksAllowed) "allowed" else "blocked"} " +
            "snapshot_applied=${if (snapshotApplied) "yes" else "no"}"
}

object JitUnblock {

    /**
     * §C.3 — идентификатор текущей загрузки (`null` — прочитать нельзя).
     *
     * Читается ЗДЕСЬ, а не в ядре: правило «ядро JIT не содержит своих механизмов» остаётся в силе
     * (`JitRefTest.noOwnMechanismsLeftInCore`). Смена загрузки используется ТОЛЬКО как определение
     * «окно закрыто» — команда, отправленная в прошлой загрузке, в этой выполниться не может, —
     * и НЕ используется как доказательство выполнения, успеха JIT или невозможности позднего
     * ответа: это ровно те слова, которые ядро и запрещает.
     */
    fun currentBootId(): String? = runCatching {
        java.io.File("/proc/sys/kernel/random/boot_id").readText().trim().ifEmpty { null }
    }.getOrNull()

    /** Допуск на «часы чуть впереди» для отметки: больше — уже не «сейчас», а сдвиг часов. */
    const val FUTURE_TOLERANCE_MS = 5 * 60_000L

    /**
     * §C.2 — пометка смены загрузки.
     *
     * `bootSame` = `mark.boot == null` (отметка прежних версий — сравнивать не с чем) ИЛИ
     * `mark.boot == currentBootId`. Иначе смена загрузки закрывает окно по определению.
     */
    fun bootSame(markBoot: String?, currentBootId: String?): Boolean =
        markBoot == null || currentBootId == null || markBoot == currentBootId

    /**
     * §C.3 — смена загрузки закрывает окно: команда прошлой загрузки в этой выполниться не может.
     * Снимок Wi-Fi при этом НЕ применяется (правка I решает возврат сети отдельно).
     */
    fun decide(
        markTsMs: Long?,
        markBoot: String?,
        currentBootId: String?,
        nowMs: Long,
        limitMs: Long,
        zygoteStartMs: Long? = null,
        terminalFinished: Boolean = false
    ): UnblockDecision {
        val age = if (markTsMs == null) -1L else nowMs - markTsMs
        fun out(exit: String, allowed: Boolean) =
            UnblockDecision(exit, allowed, snapshotApplied = false, ageMs = age)

        // Отметки нет — это НЕ застрявшая операция: выходить не из чего.
        if (markTsMs == null) return out("none", false)
        // Нечитаемая/невозможная отметка: возраст не вычисляется, но и держать владельца нельзя.
        val verdict = classifyMark(markTsMs, nowMs)
        if (verdict != MarkVerdict.OK) return out("mark_invalid_" + verdict.name.lowercase(), true)
        if (terminalFinished) return out("terminal_finished", true)
        if (!bootSame(markBoot, currentBootId)) return out("boot_changed", true)
        // Проверяемый признак идёт первым: он не зависит от часов.
        val zs = zygoteStartMs
        if (zs != null && zs > 0L && markTsMs > 0L && zs > markTsMs) {
            return out("zygote_after_mark", true)
        }
        if (age > limitMs) return out("window_expired", true)
        return out("none", false)
    }

    /**
     * §H — класс отметки. Единица и знак проверяются ДО арифметики: `ts=-196980728` (живой случай)
     * не должен выглядеть «свежим».
     */
    fun classifyMark(tsMs: Long, nowMs: Long, toleranceMs: Long = FUTURE_TOLERANCE_MS): MarkVerdict = when {
        tsMs < 0L -> MarkVerdict.NEGATIVE
        tsMs == 0L -> MarkVerdict.ZERO
        // Значение в секундах, а не в миллисекундах: у отметки 2026 года это < 1e11.
        nowMs > 1_000_000_000_000L && tsMs < 100_000_000_000L -> MarkVerdict.UNIT_MISMATCH
        tsMs - nowMs > toleranceMs -> MarkVerdict.FUTURE
        else -> MarkVerdict.OK
    }

    /** §C.1 — новая попытка: `ts` = время ЭТОЙ попытки, номер попытки +1. */
    fun nextAttempt(tsMs: Long, attempt: Int, nowMs: Long): Pair<Long, Int> =
        nowMs to (attempt + 1)
}
