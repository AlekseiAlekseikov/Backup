package com.example.voiceapp3.tools

/**
 * Одноразовый **read-only** сбор данных о монтированиях для оценки обратимости `umount -l`
 * (шаг `w0/la.run` в JIT).
 *
 * Зачем: в референсе скрипт делает общий `umount -l` всех точек, в пути которых есть `/data/app/`.
 * Достаточно ли это обратимо на конкретной ГУ, по коду и логам НЕ установлено. Чтобы владельцу не
 * пришлось вручную вводить команды и пересылать скриншоты, приложение один раз за процесс читает
 * те же данные и пишет их в свой датированный лог.
 *
 * Границы:
 *  • только чтение: ни одной команды, меняющей систему (нет `umount`, `mount`, `setprop`, `svc`,
 *    `ip rule … del`, `ndl`, `settings`, `kill`) — это проверяется тестом по фактически
 *    отправленным строкам;
 *  • тот же транспорт и те же права, что у JIT-цепочки ([JitRef.JitShell] поверх `AdbClient`);
 *  • не чаще одной попытки за процесс, запуск — из фона, без ожидания готовности канала;
 *  • `findmnt` не используется: только файлы `/proc`, поэтому отсутствие утилиты не «доказывает»
 *    ничего и не мешает.
 *
 * Чего эти данные НЕ доказывают: совпадение namespace или пустой фильтр сами по себе не
 * подтверждают обратимость. Это материал для оценки, а не вердикт.
 */
object JitMountDiag {

    /** Таймаут одного короткого чтения. */
    internal const val SECTION_TIMEOUT_MS = 8_000

    /** Размер части при выгрузке в лог (строки не рвём). */
    internal const val CHUNK_CHARS = 1_500

    /** Условие референса: `grep ' /data/app/' /proc/mounts` — подстрока в строке. */
    internal const val DATA_APP_NEEDLE = " /data/app/"

    internal const val STATUS_OK = "ok"
    internal const val STATUS_EMPTY = "empty"
    internal const val STATUS_PERMISSION_DENIED = "permission_denied"
    internal const val STATUS_ROOT_UNAVAILABLE = "root_unavailable"
    internal const val STATUS_TIMEOUT = "timeout"
    internal const val STATUS_TRUNCATED = "truncated"
    internal const val STATUS_UNKNOWN = "unknown"

    /** Итог по разделу. */
    data class Section(
        val name: String,
        val status: String,
        val rc: Int,
        val rawLines: Int,
        val matchedLines: Int,
        val chunks: Int
    )

    /** Что прочитали в одном разделе. */
    internal data class Raw(val rc: Int, val out: String)

    // ─────────────────────────── команды (только чтение) ───────────────────────────

    /**
     * Раздел 1: кто мы и в каком mount namespace.
     *
     * `/proc/self` у вызываемых утилит относится к процессу-исполнителю транспорта (тот же, что и
     * у JIT shell), поэтому одинаковый namespace с JIT подтверждается только одинаковым
     * транспортным контекстом — это и пишется в лог.
     */
    internal fun identityCommand(): String =
        "id 2>&1; echo \"__JMD_ID_RC=\$?\"; " +
            "readlink /proc/self/ns/mnt 2>&1; echo \"__JMD_NSSELF_RC=\$?\"; " +
            "readlink /proc/1/ns/mnt 2>&1; echo \"__JMD_NSINIT_RC=\$?\"; " +
            "cat /proc/self/uid_map 2>&1; echo \"__JMD_UIDMAP_RC=\$?\""

    /**
     * Раздел 2: строки `/proc/mounts` — ровно то, что читает референсный скрипт
     * (`grep ' /data/app/' /proc/mounts`), но фильтрация выполняется у нас, без запуска `grep` и
     * без цикла `umount`.
     */
    internal fun mountsCommand(): String =
        "wc -l /proc/mounts 2>&1; echo \"__JMD_WC_RC=\$?\"; " +
            "cat /proc/mounts 2>&1; echo \"__JMD_CAT_RC=\$?\""

    /** Раздел 3/4: `/proc/self/mountinfo` и `/proc/1/mountinfo` (идентификаторы, root, источник). */
    internal fun mountinfoCommand(path: String): String =
        "wc -l $path 2>&1; echo \"__JMD_WC_RC=\$?\"; " +
            "cat $path 2>&1; echo \"__JMD_CAT_RC=\$?\""

    // ─────────────────────────── разбор ───────────────────────────

    /** Строки, которые видит условие референса `' /data/app/'`. */
    fun filterDataApp(text: String): List<String> =
        text.split('\n').filter { it.isNotBlank() && it.contains(DATA_APP_NEEDLE) }

    /**
     * Статус раздела. Код возврата чтения проверяется ОТДЕЛЬНО от результата фильтрации:
     * пустой результат успешного чтения (`empty`) — не то же самое, что отказ в доступе.
     */
    fun statusOf(rc: Int, out: String, matched: Int, expectMatches: Boolean): String {
        val o = out
        if (rc == 137 || o.contains("ERR_ADB_TIMEOUT") || o.contains("ERR_TIMEOUT")) return STATUS_TIMEOUT
        if (o.contains("Permission denied") || o.contains("Operation not permitted") ||
            o.contains("not permitted")
        ) return STATUS_PERMISSION_DENIED
        if (o.contains("No such file") || o.contains("ERR_ADB_CHANNEL_DOWN")) return STATUS_ROOT_UNAVAILABLE
        if (rc != 0) return STATUS_UNKNOWN
        if (expectMatches) {
            if (matched == 0) return STATUS_EMPTY
        } else if (o.isBlank()) {
            return STATUS_EMPTY
        }
        return STATUS_OK
    }

    /** Разбить текст на части по строкам, не разрывая строк (для лога с ограничением). */
    fun chunks(text: String, limit: Int = CHUNK_CHARS): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (line in text.split('\n')) {
            if (sb.isNotEmpty() && sb.length + line.length + 1 > limit) {
                out += sb.toString()
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line.take(limit))
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** Число строк по выводу `wc -l` (первое число), `null` — если не разобрать. */
    internal fun wcCount(out: String): Int? =
        Regex("(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()

    private fun marker(out: String, name: String): Int? =
        Regex("__JMD_${Regex.escape(name)}_RC=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()

    // ─────────────────────────── сбор ───────────────────────────

    /**
     * Собрать и записать в лог. Возвращает сводку по разделам.
     *
     * Ровно четыре коротких чтения; каждое — отдельный вызов, чтобы отказ одного не мешал
     * остальным. Никаких изменяющих команд здесь нет и появиться не может: строки команд —
     * константы этого файла (проверяется тестом).
     */
    fun collect(shell: JitRef.JitShell, log: (String) -> Unit, source: String): List<Section> {
        val t0 = System.currentTimeMillis()
        log(
            "JIT_MOUNT_DIAG_BEGIN ts=${System.currentTimeMillis()} source=$source mode=read_only " +
                "transport=jit_shell expect_root=true note=\"логи содержат пути к приложениям " +
                "из /data/app/ — это данные владельца, в публичные отчёты их не копируем\""
        )
        val sections = ArrayList<Section>(4)

        // 1. Кто мы и какой namespace.
        run {
            val raw = exec(shell, identityCommand())
            val ok = !raw.out.contains("__JMD_ID_RC=") || (marker(raw.out, "ID") ?: 1) == 0
            val body = raw.out.lines()
                .filterNot { it.startsWith("__JMD_") }
                .joinToString("\n")
                .trim()
            val status = statusOf(raw.rc, raw.out, body.lines().count { it.isNotBlank() }, false)
            val idRc = marker(raw.out, "ID")
            val nsSelf = Regex("__JMD_NSSELF_RC=(\\d+)").find(raw.out)?.groupValues?.get(1)?.toIntOrNull()
            val nsInit = Regex("__JMD_NSINIT_RC=(\\d+)").find(raw.out)?.groupValues?.get(1)?.toIntOrNull()
            val parts = chunks(body)
            log(
                "JIT_MOUNT_DIAG_SECTION name=identity status=$status rc=${raw.rc} id_rc=$idRc " +
                    "ns_self_rc=$nsSelf ns_init_rc=$nsInit lines=${body.lines().count { it.isNotBlank() }} " +
                    "parts=${parts.size} note=\"namespace вызываемых утилит = namespace транспорта; " +
                    "одинаковый namespace с JIT подтверждается только одинаковым транспортным " +
                    "контекстом\""
            )
            parts.forEachIndexed { i, part ->
                log("JIT_MOUNT_DIAG_DATA name=identity part=${i + 1}/${parts.size} $part")
            }
            sections += Section("identity", status, raw.rc, body.lines().count { it.isNotBlank() },
                body.lines().count { it.isNotBlank() }, parts.size)
            if (!ok) log("JIT_MOUNT_DIAG note=identity_id_not_root (проверь uid в данных выше)")
        }

        // 2–4. Три файла: mounts, self/mountinfo, init/mountinfo.
        for ((name, path) in listOf(
            "proc_mounts" to "/proc/mounts",
            "self_mountinfo" to "/proc/self/mountinfo",
            "init_mountinfo" to "/proc/1/mountinfo"
        )) {
            val raw = exec(shell, mountinfoCommand(path))
            val catRc = marker(raw.out, "CAT")
            val wcRc = marker(raw.out, "WC")
            val content = raw.out.lines()
                .filterNot { it.startsWith("__JMD_") && it.contains("_RC=") }
                .joinToString("\n")
            val rawLines = wcCount(raw.out) ?: content.lines().count { it.isNotBlank() }
            val matched = filterDataApp(content)
            var status = statusOf(
                raw.rc, raw.out, matched.size, expectMatches = true
            )
            if (catRc != null && catRc != 0 && status == STATUS_OK) status = STATUS_UNKNOWN
            // Усечение: строк в выводе меньше, чем сообщил `wc -l` — значит транспорт/лог обрезал.
            val seen = content.lines().count { it.isNotBlank() }
            val truncated = wcRc == 0 && catRc == 0 && rawLines > 0 && seen < rawLines
            if (truncated && status != STATUS_PERMISSION_DENIED && status != STATUS_TIMEOUT) {
                status = STATUS_TRUNCATED
            }
            val parts = chunks(matched.joinToString("\n"))
            log(
                "JIT_MOUNT_DIAG_SECTION name=$name status=$status rc=${raw.rc} wc_rc=$wcRc " +
                    "cat_rc=$catRc file=$path wc_lines=$rawLines seen_lines=$seen matched=${matched.size} " +
                    "truncated=$truncated parts=${parts.size} " +
                    "condition=\"строка содержит '$DATA_APP_NEEDLE' (как grep референса)\""
            )
            parts.forEachIndexed { i, part ->
                log("JIT_MOUNT_DIAG_DATA name=$name part=${i + 1}/${parts.size} $part")
            }
            sections += Section(name, status, raw.rc, rawLines, matched.size, parts.size)
        }

        val okCount = sections.count { it.status == STATUS_OK }
        log(
            "JIT_MOUNT_DIAG_END sections=${sections.size} ok=$okCount " +
                "empty=${sections.count { it.status == STATUS_EMPTY }} " +
                "denied=${sections.count { it.status == STATUS_PERMISSION_DENIED }} " +
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
