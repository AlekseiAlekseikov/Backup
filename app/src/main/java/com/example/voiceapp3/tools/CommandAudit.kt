package com.example.voiceapp3.tools

/**
 * iter67 §F — склейка многошаговых команд без разделителя (P1, но обязательна).
 *
 * Дефект из лога 1.0.59 (09.11):
 *
 * ```
 * cmd=setprop dalvik.vm.usejit true 2>/dev/null setprop dalvik.vm.usejitprofiles true 2>/dev/null
 *     echo RT=$(getprop dalvik.vm.usejit) echo PS=$(getprop persist.dalvik…
 * → SUCCESS
 * ```
 *
 * Если разделители потеряны в самой команде (а не только в усечённой строке журнала), `setprop`
 * получает лишние аргументы и ничего не делает, а `rc=0` от последнего `echo` даёт ложный успех.
 * Здесь это ловится ДО отправки: `missingSeparators` возвращает места склейки.
 *
 * Правило узкое намеренно: проверяются только команды, где встречаются `setprop`/`getprop`, и
 * склейкой считается «слово, пробел(ы), следующее слово, начинающееся с команды» — правильные
 * цепочки (`;`, `&&`, `||`, перевод строки, `$( … )`, `2>/dev/null`) не срабатывают.
 */
object CommandAudit {

    /** Команды, между которыми обязателен разделитель. */
    private val NEXT_CMD = Regex(
        "(?<![;&|\\n\\s])(?:\\s+)(?=(setprop|getprop|echo|svc|umount|sleep|cut|sed|awk|grep)\\s)"
    )

    /** Разделители, которые «склеивают» шаги законно. */
    private val LEGAL_SEP = Regex("(;|&&|\\|\\||\\||\\n|&)")

    /**
     * Места склейки без разделителя. Пустой список — команда в порядке.
     *
     * Возвращается контекст «после: …» — последние 30 символов перед склейкой, чтобы в журнале было
     * видно, где именно потерялся разделитель (payload при этом не печатается целиком).
     */
    fun missingSeparators(cmd: String): List<String> {
        if (!cmd.contains("setprop") && !cmd.contains("getprop")) return emptyList()
        val out = ArrayList<String>(2)
        for (m in NEXT_CMD.findAll(cmd)) {
            // Перевод строки между шагами — законный разделитель (скрипт многострочный).
            if (m.value.contains('\n')) continue
            val before = cmd.substring(0, m.range.first).trimEnd()
            // Уже есть законный разделитель прямо перед склейкой — это нормальная цепочка.
            if (before.isEmpty()) continue
            val tail = before.takeLast(3)
            if (LEGAL_SEP.containsMatchIn(tail)) continue
            // `2>/dev/null setprop …` — потерянный разделитель; `$(getprop a)` — не склейка.
            val cmdWord = m.groupValues[1]
            val opensSub = before.endsWith("$(") || before.endsWith("`")
            if (opensSub) continue
            if (before.endsWith("2>/dev/null") || before.endsWith("1>/dev/null")) {
                out += "после: " + before.takeLast(30)
                continue
            }
            // `setprop a 1` — это аргументы, а не второй шаг: второй шаг требует, чтобы перед ним
            // было ЗАВЕРШЁННОЕ значение (число, `true`/`false`, `null`, кавычка).
            val prevWord = before.substringAfterLast(' ', "")
            val finishedValue = prevWord.isNotEmpty() &&
                (prevWord == "true" || prevWord == "false" || prevWord == "null" ||
                    prevWord.toIntOrNull() != null || prevWord.endsWith("\"") || prevWord.endsWith("'"))
            if (finishedValue && cmdWord != "getprop") out += "после: " + before.takeLast(30)
        }
        return out
    }

    /** Строка журнала для подозрительной команды (`null` — сообщать нечего). */
    fun suspectLine(cmd: String): String? {
        val missing = missingSeparators(cmd)
        if (missing.isEmpty()) return null
        return "CMD_SUSPECT missing_sep=${missing.size} where=«${missing.joinToString(" | ")}» " +
            "action=no_send (без разделителя шаг получает лишние аргументы, а rc=0 от последней " +
            "команды даёт ложный успех)"
    }
}
