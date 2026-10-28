package com.example.voiceapp3.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Структурированный результат одного шага привилегированной операции.
 *
 * Формат повторяет контракт CentralEXAuto: каждый шаг действия известен
 * заранее (step), у него есть исполняемая команда или вызов API, временные
 * границы, код возврата, вывод, ожидаемое и фактическое значение readback.
 * UI показывает [status] кратко, подробности — developer diagnostics.
 *
 * GeelyTools: шаг может быть и чистым Android-API (тогда [exitCode] = 0 при
 * успехе и текст в [stdout] — описание применённого состояния), и shell-
 * командой (тогда [exitCode]/[stdout]/[stderr] — настоящие значения процесса).
 */
data class ToolsStep(
    val step: String,
    val command: String,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
    val expected: String = "",
    val actual: String = "",
    val status: Status
) {

    /** True, если фактическое readback совпало с ожидаемым. */
    val readbackMatches: Boolean
        get() = expected.isEmpty() || actual == expected

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("step", step)
        addProperty("command", command)
        addProperty("start", startedAtMs)
        addProperty("end", finishedAtMs)
        addProperty("durationMs", finishedAtMs - startedAtMs)
        addProperty("exitCode", exitCode)
        if (stdout.isNotEmpty()) addProperty("stdout", stdout)
        if (stderr.isNotEmpty()) addProperty("stderr", stderr)
        if (expected.isNotEmpty()) addProperty("expected", expected)
        if (actual.isNotEmpty()) addProperty("actual", actual)
        addProperty("status", status.wire)
    }

    /** Статус шага; [wire] — значение, которое видит JS. */
    enum class Status(val wire: String) {
        /** Шаг выполнен и readback подтвердил результат. */
        OK("ok"),

        /** Шаг выполнен, но подтверждение получено не полное. */
        PARTIAL("partial"),

        /** Шаг не выполнен: команда/API вернули ошибку. */
        FAILED("failed"),

        /** Шаг невозможен сейчас: нет зависимости, прав или файла. */
        BLOCKED("blocked");

        companion object {
            fun of(applied: Boolean, matches: Boolean = true): Status = when {
                !applied -> FAILED
                matches -> OK
                else -> PARTIAL
            }
        }
    }
}

/**
 * Результат whole-действия: последовательность [steps] в порядке выполнения.
 *
 * [ok] — только если все шаги OK/PARTIAL; статус UI всегда выводится из
 * фактических шагов, поэтому «успех без выполнения» здесь неотличим
 * от реального успеха по construct: пустой результат не считается успехом.
 */
data class ToolsResult(
    val action: String,
    val title: String,
    val steps: List<ToolsStep>
) {

    val ok: Boolean
        get() = steps.isNotEmpty() && steps.all {
            it.status == ToolsStep.Status.OK || it.status == ToolsStep.Status.PARTIAL
        }

    /** Краткий статус для основной панели (подробности — в diagnostics). */
    val summary: String
        get() = when {
            steps.isEmpty() -> "не выполнено"
            ok && steps.all { it.status == ToolsStep.Status.OK } -> "применено"
            ok -> "применено частично"
            steps.any { it.status == ToolsStep.Status.BLOCKED } -> blockedReason()
            else -> steps.last().stderr.ifEmpty { "ошибка: ${steps.last().step}" }
        }

    val statusWire: String
        get() = when {
            steps.isEmpty() -> ToolsStep.Status.FAILED.wire
            ok && steps.all { it.status == ToolsStep.Status.OK } -> ToolsStep.Status.OK.wire
            ok -> ToolsStep.Status.PARTIAL.wire
            steps.any { it.status == ToolsStep.Status.BLOCKED } -> ToolsStep.Status.BLOCKED.wire
            else -> ToolsStep.Status.FAILED.wire
        }

    private fun blockedReason(): String = steps
        .firstOrNull { it.status == ToolsStep.Status.BLOCKED }
        ?.let { it.stderr.ifEmpty { it.stdout }.ifEmpty { "зависимость недоступна" } }
        ?: "зависимость недоступна"

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("action", action)
        addProperty("title", title)
        addProperty("ok", ok)
        addProperty("status", statusWire)
        addProperty("summary", summary)
        add("steps", JsonArray().apply { steps.forEach { add(it.toJson()) } })
    }

    /**
     * Пустой результат для случая «обработчик не нашёл действие».
     * Создаётся только явно — см. [ToolsStep.Status.FAILED].
     */
    companion object {
        fun unknown(action: String): ToolsResult = ToolsResult(
            action = action,
            title = action,
            steps = listOf(
                ToolsStep(
                    step = "dispatch",
                    command = "ToolsBackend.dispatch($action)",
                    startedAtMs = System.currentTimeMillis(),
                    finishedAtMs = System.currentTimeMillis(),
                    exitCode = 1,
                    stderr = "неизвестное действие: $action",
                    status = ToolsStep.Status.FAILED
                )
            )
        )
    }
}

/** Хелпер: обернуть block в шаг с замером времени и перехватом исключения. */
inline fun toolsStep(
    step: String,
    command: String,
    expected: String = "",
    block: () -> Pair<Boolean, String>
): ToolsStep {
    val t0 = System.currentTimeMillis()
    var exit = 0
    var out = ""
    var err = ""
    var applied = false
    try {
        val (ok, actual) = block()
        applied = ok
        out = actual
    } catch (t: Throwable) {
        exit = 1
        err = t.javaClass.simpleName + ": " + (t.message ?: "")
    }
    val t1 = System.currentTimeMillis()
    return ToolsStep(
        step = step,
        command = command,
        startedAtMs = t0,
        finishedAtMs = t1,
        exitCode = exit,
        stdout = out,
        stderr = err,
        expected = expected,
        actual = out,
        status = ToolsStep.Status.of(applied, expected.isEmpty() || out == expected)
    )
}
