package com.example.voiceapp3.tools

/**
 * iter63 §P1-2 — чтение состояния файла поколения из вывода TTL-скрипта.
 *
 * Зачем отдельный файл: состояние «файла нет / наш / чужой-нечитаемый / записать не удалось»
 * должно попадать В ЖУРНАЛ (`TTL_APPLY_RESULT`, `TTL_GEN publish`), а не выясняться повторным
 * разбором на месте. Разбор один и тот же для всех вызовов, поэтому вынесен в чистую функцию —
 * её поведение проверяется на JVM-стенде без устройства.
 *
 * Скрипт (`TtlScript._gen_owner_check`) печатает ровно один маркер:
 *  - `VAPP_TTL_GEN_INIT`      — файла не было, опубликовали своё поколение (readback);
 *  - `VAPP_TTL_GEN_OK`        — файл наш, значение совпало;
 *  - `VAPP_TTL_GEN_ADOPT`     — файл был чужим/нечитаемым, восстановили своим (было/стало);
 *  - `VAPP_TTL_GEN_UNWRITABLE`— записать не удалось: причина отказа названа;
 *  - `VAPP_TTL_SUPERSEDED`    — поколение сменилось, скрипт вышел ДО мутации (`exit 90`).
 */
internal object TtlGenDiag {

    private val PREFIXES = listOf(
        "VAPP_TTL_GEN_INIT",
        "VAPP_TTL_GEN_OK",
        "VAPP_TTL_GEN_ADOPT",
        "VAPP_TTL_GEN_UNWRITABLE",
        "VAPP_TTL_SUPERSEDED"
    )

    /**
     * Маркер поколения из вывода скрипта или пустая строка, если скрипт его не печатал
     * (например, ограждение выключено или исполнение не дошло до проверки).
     *
     * @param out полный вывод скрипта.
     * @param file ожидаемый путь файла поколения; маркер другого файла отбрасывается, чтобы в
     *   журнале не оказалось поколение от чужой попытки.
     */
    fun marker(out: String, file: String): String =
        out.lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                PREFIXES.any { line.startsWith(it) } &&
                    (file.isEmpty() || line.contains("file="))
            }
            ?.takeIf { file.isEmpty() || it.contains(file) }
            ?: ""

    /**
     * Команда публикации поколения с ДИАГНОСТИКОЙ состояния файла ДО перезаписи.
     *
     * Выполняет ровно то же, что делал iter62 (`printf … > file; cat file`), плюс перед записью
     * классифицирует, чем файл был: `ours` / `foreign` / `foreign_unreadable` / `absent`.
     * Перезапись не меняет поведение ограждения (иначе сломается `SUPERSEDED`), но в журнале
     * появляется причина, а не «поколение не подтверждено» без объяснения.
     */
    fun publishCommand(gen: Long, file: String): String =
        "if [ -e $file ]; then _c=${'$'}(cat $file 2>/dev/null); " +
            "if [ \"${'$'}_c\" = '$gen' ]; then echo GEN_STATE=ours; " +
            "elif [ -z \"${'$'}_c\" ] || [ ! -r $file ]; then echo GEN_STATE=foreign_unreadable; " +
            "else echo GEN_STATE=foreign; fi; else echo GEN_STATE=absent; fi; " +
            "printf '%s' '$gen' > $file 2>/dev/null; _rb=${'$'}(cat $file 2>/dev/null); " +
            "echo GEN_READBACK=${'$'}_rb file=$file state=${'$'}(echo ${'$'}_c)"

    /**
     * Разбор вывода [publishCommand]: подтверждено ли поколение чтением и что было в файле.
     *
     * Возвращает пару (подтверждено, строка для журнала). Строка всегда содержит путь, прочитанное
     * значение и состояние файла ДО записи — по ней видно, был ли это первый запуск, повтор или
     * чужой файл.
     */
    fun parsePublish(out: String, gen: Long, file: String): Pair<Boolean, String> {
        val state = Regex("GEN_STATE=(\\w+)").find(out)?.groupValues?.get(1) ?: "unknown"
        val back = Regex("GEN_READBACK=(\\S*)").find(out)?.groupValues?.get(1) ?: ""
        val ok = back == gen.toString()
        val why = if (ok) "gen_file_state=$state file=$file readback=$back"
        else "gen_file_state=$state file=$file readback=«${back.take(16)}» action=not_published"
        return ok to why
    }
}