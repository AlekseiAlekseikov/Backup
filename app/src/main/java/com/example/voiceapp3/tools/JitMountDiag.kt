package com.example.voiceapp3.tools

/**
 * Одноразовый **read-only** сбор данных о монтированиях — для оценки обратимости `umount -l`
 * (шаг `w0/la.run` в JIT-скрипте).
 *
 * Почему сбор вообще нужен: референс делает общий `umount -l` всех точек, в пути которых есть
 * `/data/app/`. Обратимость этого шага на конкретной ГУ по коду не устанавливается: список
 * монтирований сам по себе её не доказывает. Сбор даёт материал для оценки, а не вердикт.
 *
 * Границы:
 *  • только чтение: ни одной команды, меняющей систему (`umount`, `mount`, `setprop`, `svc`,
 *    `ip rule … del`, `ndc`, `settings`, `kill`, `reboot`) — проверяется тестом по фактически
 *    отправленным строкам;
 *  • тот же транспорт и те же права, что у JIT-цепочки ([JitRef.JitShell] поверх `AdbClient`).
 *    Одинаковый API транспорта НЕ гарантирует одинаковый mount namespace после переподключения,
 *    поэтому namespace фиксируется по каждому вызову и сравнивается с `/proc/1`;
 *  • не чаще одной попытки за процесс; запуск в фоне, без ожидания канала;
 *  • `findmnt` не требуется: только файлы `/proc`.
 *
 * Что считается достоверным: только ЦЕЛОЕ чтение — есть маркеры кода возврата `wc` и `cat`,
 * есть маркер конца вывода, число строк и байт совпадает с тем, что сообщил `wc`. Всё остальное
 * получает `unknown`/`truncated`, а не `ok`/`empty`.
 *
 * Предел проверки: если транспорт потеряет кусок вывода В СЕРЕДИНЕ и при этом сохранит маркеры, а
 * число байт случайно совпадёт (перестановка/замена байт), это не обнаруживается — сравнение идёт
 * по длине, а не по контрольной сумме. Обратного не заявляем.
 */
object JitMountDiag {

    internal const val SECTION_TIMEOUT_MS = 8_000
    internal const val CHUNK_CHARS = 1_500

    /** Условие референса: `grep ' /data/app/' /proc/mounts` — подстрока в строке. */
    internal const val DATA_APP_NEEDLE = " /data/app/"

    internal const val STATUS_OK = "ok"
    internal const val STATUS_EMPTY = "empty"
    internal const val STATUS_PERMISSION_DENIED = "permission_denied"
    internal const val STATUS_CHANNEL_NOT_READY = "channel_not_ready"
    internal const val STATUS_ROOT_UNKNOWN = "root_unknown"
    internal const val STATUS_TIMEOUT = "timeout"
    internal const val STATUS_TRUNCATED = "truncated"
    internal const val STATUS_UNKNOWN = "unknown"

    internal const val BEGIN = "JIT_MOUNT_DIAG_BEGIN"
    internal const val SECTION = "JIT_MOUNT_DIAG_SECTION"
    internal const val DATA = "JIT_MOUNT_DIAG_DATA"
    internal const val END = "JIT_MOUNT_DIAG_END"

    /** Итог по разделу. */
    data class Section(
        val name: String,
        val status: String,
        val rc: Int,
        val rawLines: Int,
        val matchedLines: Int,
        val chunks: Int,
        /** `uid` из `id`, если его удалось прочитать (иначе `null`). */
        val uid: Int? = null,
        /** `true` только при подтверждённом `uid=0`. */
        val root: Boolean? = null,
        /** namespace вызываемых утилит и `/proc/1`, если прочитаны. */
        val nsSelf: String? = null,
        val nsInit: String? = null
    )

    internal data class Raw(val rc: Int, val out: String)

    // ─────────────────────────── команды (только чтение) ───────────────────────────

    /**
     * Раздел 1: кто мы, какой uid и какой mount namespace у вызываемых утилит и у `/proc/1`.
     *
     * `/proc/self` здесь принадлежит процессу-исполнителю транспорта (тому же, что у JIT shell),
     * поэтому «тот же namespace, что у JIT» подтверждается только одинаковым транспортным
     * контекстом — это пишется в лог, а не заявляется.
     */
    internal fun identityCommand(): String =
        "{ id ; } 2>&1; echo \"__JMD_ID_RC=\$?\"; " +
            "{ readlink /proc/self/ns/mnt ; } 2>&1; echo \"__JMD_NSSELF_RC=\$?\"; " +
            "{ readlink /proc/1/ns/mnt ; } 2>&1; echo \"__JMD_NSINIT_RC=\$?\"; " +
            "{ cat /proc/self/uid_map ; } 2>&1; echo \"__JMD_UIDMAP_RC=\$?\"; " +
            "echo \"__JMD_EOF=1\""

    /**
     * Раздел 2–4: строки файла `/proc` — то же, что читает референсный скрипт для `/proc/mounts`
     * (`grep ' /data/app/' /proc/mounts`), но фильтрация выполняется у нас, без `grep` и без цикла
     * `umount`. `wc -lc` даёт число строк И байт: по ним проверяется полнота вывода.
     */
    internal fun fileCommand(path: String): String =
        "{ wc -lc $path ; } 2>&1; echo \"__JMD_WC_RC=\$?\"; " +
            "{ cat $path ; } 2>&1; echo \"__JMD_CAT_RC=\$?\"; " +
            "echo \"__JMD_EOF=1\""

    // ─────────────────────────── разбор ───────────────────────────

    /** Строки, которые видит условие референса `' /data/app/'`. */
    fun filterDataApp(text: String): List<String> =
        text.split('\n').filter { it.isNotBlank() && it.contains(DATA_APP_NEEDLE) }

    internal fun marker(out: String, name: String): Int? =
        Regex("__JMD_${Regex.escape(name)}_RC=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()

    /** Целый ли вывод: маркер конца присутствует. Иначе полноту подтвердить нельзя. */
    internal fun complete(out: String): Boolean = out.contains("__JMD_EOF=1")

    internal fun body(out: String): String =
        out.lines().filterNot { it.startsWith("__JMD_") }.joinToString("\n").trim()

    /**
     * Содержимое `cat` между маркерами: строки ПОСЛЕ `__JMD_WC_RC=…` и ДО `__JMD_CAT_RC=…`.
     * Так строка самого `wc` не попадает в «прочитанный файл» и не искажает подсчёт.
     */
    internal fun catRegion(out: String): String {
        val lines = out.lines()
        val from = lines.indexOfFirst { it.startsWith("__JMD_WC_RC=") }
        val to = lines.indexOfFirst { it.startsWith("__JMD_CAT_RC=") }
        if (from < 0 || to < 0 || to <= from) return ""
        return lines.subList(from + 1, to).joinToString("\n")
    }

    private fun denied(o: String): Boolean =
        o.contains("Permission denied") || o.contains("Operation not permitted") || o.contains("not permitted")

    /**
     * `wc -lc` → (строки, байты). Второе число может отсутствовать (другая реализация `wc`).
     */
    internal fun wcCounts(out: String): Pair<Int?, Int?> {
        val line = out.lines().firstOrNull { it.contains("/proc") || it.trim().matches(Regex("^\\d+.*")) }
        val nums = Regex("(\\d+)").findAll(line ?: out).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        return when {
            nums.size >= 2 -> nums[0] to nums[1]
            nums.size == 1 -> nums[0] to null
            else -> null to null
        }
    }

    /** `uid=0(root)` из `id`. */
    internal fun uidOf(out: String): Int? =
        Regex("uid=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()

    internal fun nsOf(out: String, markerName: String): String? {
        val lines = out.lines()
        val i = lines.indexOfFirst { it.contains("__JMD_${markerName}_RC=") }
        if (i <= 0) return null
        val v = lines[i - 1].trim()
        return if (v.startsWith("mnt:") && !v.contains("No such file")) v else null
    }

    /**
     * Статус файлового раздела. Порядок проверок важен: отказ транспорта не должен становиться
     * `empty`, а потерянный маркер — `ok`.
     *
     * @param wcLines/wcBytes сколько сообщил `wc` (`null` — не разобрать)
     * @param seenLines/seenBytes сколько реально пришло в выводе `cat`
     */
    internal fun fileStatus(
        transportRc: Int,
        out: String,
        wcRc: Int?,
        catRc: Int?,
        wcLines: Int?,
        wcBytes: Int?,
        seenLines: Int,
        seenBytes: Int,
        matched: Int
    ): String {
        if (transportRc == 137 || out.contains("ERR_ADB_TIMEOUT") || out.contains("ERR_TIMEOUT")) return STATUS_TIMEOUT
        if (!complete(out)) return STATUS_UNKNOWN
        if (denied(out)) return STATUS_PERMISSION_DENIED
        if (catRc == null || wcRc == null) return STATUS_UNKNOWN
        if (wcRc != 0 || catRc != 0) {
            return if (out.contains("No such file")) STATUS_ROOT_UNKNOWN else STATUS_UNKNOWN
        }
        if (wcLines == null) return STATUS_UNKNOWN
        // Байты: у `cat` возможен потерянный завершающий перевод строки — допуск 1 байт.
        val byteMismatch = wcBytes != null && kotlin.math.abs(seenBytes - wcBytes) > 1
        if (seenLines != wcLines || byteMismatch) return STATUS_TRUNCATED
        if (matched == 0) return STATUS_EMPTY
        return STATUS_OK
    }

    /**
     * Части для лога. Длинная строка НЕ обрезается молча: делится на куски с явной пометкой, чтобы
     * в логе не получилось «обрезанное, но выглядящее целым».
     */
    fun chunks(text: String, limit: Int = CHUNK_CHARS): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) { out += sb.toString(); sb.setLength(0) }
        }
        for (line in text.split('\n')) {
            if (line.length > limit) {
                var i = 0
                while (i < line.length) {
                    val piece = line.substring(i, minOf(line.length, i + limit))
                    sb.append("⟪продолжение строки $i..${i + piece.length - 1}⟫ ").append(piece)
                    flush()
                    i += piece.length
                }
                continue
            }
            if (sb.isNotEmpty() && sb.length + line.length + 1 > limit) flush()
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        flush()
        return out
    }

    // ─────────────────────────── сбор ───────────────────────────

    /**
     * Собрать и записать в лог. Возвращает сводку по разделам.
     *
     * @param channelReady готовность существующего канала на момент сбора. `false` ⇒ честный
     *   `channel_not_ready` по всем разделам и НИ ОДНОЙ команды (это не «нет root»).
     */
    fun collect(shell: JitRef.JitShell, channelReady: Boolean, log: (String) -> Unit, source: String): List<Section> {
        val t0 = System.currentTimeMillis()
        log(
            "$BEGIN ts=$t0 source=$source mode=read_only transport=jit_shell channel_ready=$channelReady " +
                "note=\"в логе будут пути приложений из /data/app/ — это данные владельца, в " +
                "публичные отчёты их не копируем; mount namespace фиксируется по каждому вызову\""
        )
        if (!channelReady) {
            val none = listOf("identity", "proc_mounts", "self_mountinfo", "init_mountinfo")
                .map { Section(it, STATUS_CHANNEL_NOT_READY, -1, 0, 0, 0) }
            none.forEach {
                log("$SECTION name=${it.name} status=$STATUS_CHANNEL_NOT_READY rc=-1 command=skipped " +
                    "note=\"канал не готов: это НЕ отсутствие root и НЕ результат чтения\"")
            }
            log("$END sections=${none.size} ok=0 channel_not_ready=${none.size} " +
                "elapsed_ms=${System.currentTimeMillis() - t0} system_writes=0")
            return none
        }
        val sections = ArrayList<Section>(4)

        // 1. Кто мы и какой namespace.
        run {
            val raw = exec(shell, identityCommand())
            val idRc = marker(raw.out, "ID")
            val uid = uidOf(raw.out)
            val text = body(raw.out)
            val status = when {
                raw.rc == 137 || raw.out.contains("ERR_ADB_TIMEOUT") -> STATUS_TIMEOUT
                !complete(raw.out) -> STATUS_UNKNOWN
                denied(raw.out) -> STATUS_PERMISSION_DENIED
                idRc == null || idRc != 0 || uid == null -> STATUS_ROOT_UNKNOWN
                else -> STATUS_OK
            }
            val nsSelf = nsOf(raw.out, "NSSELF")
            val nsInit = nsOf(raw.out, "NSINIT")
            val parts = chunks(text)
            log(
                "$SECTION name=identity status=$status rc=${raw.rc} id_rc=$idRc uid=$uid " +
                    "root=${uid == 0} ns_self=${nsSelf ?: "unknown"} ns_init=${nsInit ?: "unknown"} " +
                    "same_ns_as_init=${if (nsSelf != null && nsInit != null) nsSelf == nsInit else "unknown"} " +
                    "lines=${text.lines().count { it.isNotBlank() }} parts=${parts.size} " +
                    "note=\"namespace вызываемых утилит = namespace транспорта; совпадение с JIT " +
                    "подтверждается только одинаковым транспортным контекстом, а не этим логом\""
            )
            parts.forEachIndexed { i, part -> log("$DATA name=identity part=${i + 1}/${parts.size} $part") }
            if (status == STATUS_OK && uid != 0) {
                log("$DATA note=id_ok_but_not_root uid=$uid (успешный `id` не значит uid=0)")
            }
            sections += Section("identity", status, raw.rc, text.lines().count { it.isNotBlank() },
                text.lines().count { it.isNotBlank() }, parts.size, uid, uid?.let { it == 0 }, nsSelf, nsInit)
        }

        // 2–4. Три файла.
        for ((name, path) in listOf(
            "proc_mounts" to "/proc/mounts",
            "self_mountinfo" to "/proc/self/mountinfo",
            "init_mountinfo" to "/proc/1/mountinfo"
        )) {
            val raw = exec(shell, fileCommand(path))
            val catRc = marker(raw.out, "CAT")
            val wcRc = marker(raw.out, "WC")
            val content = catRegion(raw.out)
            val (wcLines, wcBytes) = wcCounts(raw.out)
            val seenLines = content.lines().count { it.isNotBlank() }
            val seenBytes = content.length
            val matched = filterDataApp(content)
            val status = fileStatus(
                raw.rc, raw.out, wcRc, catRc, wcLines, wcBytes, seenLines, seenBytes, matched.size
            )
            val parts = chunks(matched.joinToString("\n"))
            val deltaLines = if (wcLines != null) seenLines - wcLines else null
            val deltaBytes = if (wcBytes != null) seenBytes - wcBytes else null
            log(
                "$SECTION name=$name status=$status rc=${raw.rc} wc_rc=$wcRc cat_rc=$catRc file=$path " +
                    "wc_lines=${wcLines ?: "unknown"} wc_bytes=${wcBytes ?: "unknown"} " +
                    "seen_lines=$seenLines seen_bytes=$seenBytes delta_lines=${deltaLines ?: "n/a"} " +
                    "delta_bytes=${deltaBytes ?: "n/a"} complete=${complete(raw.out)} " +
                    "matched=${matched.size} parts=${parts.size} " +
                    "condition=\"строка содержит '$DATA_APP_NEEDLE' (как grep референса)\""
            )
            parts.forEachIndexed { i, part -> log("$DATA name=$name part=${i + 1}/${parts.size} $part") }
            sections += Section(name, status, raw.rc, wcLines ?: seenLines, matched.size, parts.size)
        }

        log(
            "$END sections=${sections.size} ok=${sections.count { it.status == STATUS_OK }} " +
                "empty=${sections.count { it.status == STATUS_EMPTY }} " +
                "denied=${sections.count { it.status == STATUS_PERMISSION_DENIED }} " +
                "root_unknown=${sections.count { it.status == STATUS_ROOT_UNKNOWN }} " +
                "timeout=${sections.count { it.status == STATUS_TIMEOUT }} " +
                "truncated=${sections.count { it.status == STATUS_TRUNCATED }} " +
                "unknown=${sections.count { it.status == STATUS_UNKNOWN }} " +
                "elapsed_ms=${System.currentTimeMillis() - t0} system_writes=0 " +
                "note=\"данные для оценки обратимости umount -l; сами по себе её не доказывают\""
        )
        return sections
    }

    private fun exec(shell: JitRef.JitShell, cmd: String): Raw = runCatching {
        val (rc, out) = shell.exec(cmd, SECTION_TIMEOUT_MS)
        Raw(rc, out)
    }.getOrElse { t -> Raw(1, "ERR_EXCEPTION:${t.javaClass.simpleName}") }
}
