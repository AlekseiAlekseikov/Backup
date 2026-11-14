package com.example.voiceapp3.tools

import java.nio.charset.StandardCharsets
import kotlin.random.Random

/**
 * iter52 §4 — ДОСТАВКА сгенерированного скрипта: проверяемая, с явными фазами.
 *
 * Что было (iter51) и почему это не годилось:
 *  • `cleanup` отправлял `rm -f '<path>' 2>/dev/null; echo RM_DONE` и считал успехом `rc=0` +
 *    наличие `RM_DONE`. В POSIX `;` код возврата — от `echo`, поэтому неудачное удаление
 *    выглядело успешным. Воспроизведено на настоящем `sh`: файл остался, `RM_DONE` напечатан,
 *    `rc=0`.
 *  • отсутствие текста `ERR_SCRIPT_UPLOAD` трактовалось как «размер сверен» — даже при
 *    timeout/обрыве, когда сверка могла не выполняться.
 *  • сверялся только РАЗМЕР: одинаковая длина не доказывает те же байты.
 *  • имя файла = 8 случайных hex без атомарного резервирования.
 *
 * Что теперь:
 *  1. **Эксклюзивное создание** файла (`set -C`, noclobber): если имя занято — берём новое,
 *     чужой/старый файл не перезаписывается и не удаляется.
 *  2. **Сверка содержимого** через POSIX `cksum` (есть в toybox/coreutils; CRC-32 считается
 *     здесь же в Kotlin, поэтому ожидаемое значение известно заранее). Нет `cksum` на прошивке
 *     → честный `content_checked=0` и сверка только размера, а не подмена на «содержимое верно».
 *  3. **Различение фаз** ([State]): не отправлено / заливка неполна (скрипт ГАРАНТИРОВАННО не
 *     запускался) / сверено и запуск передан / выполнение подтверждено terminal-файлом /
 *     ИСХОД НЕИЗВЕСТЕН (ответ потерян, запуск мог произойти).
 *  4. **Terminal-файл запуска** (`<path>.done` с `rc=N`): после потери ответа состояние читается
 *     короткой read-only командой ([reconcile]) — «не знаю» заменяется фактом, а не догадкой.
 *  5. **Повтор запрещён**, если запуск мог произойти: retry разрешён только при доказанном
 *     «скрипт не исполнялся» (явный маркер отказа ДО `sh`).
 *  6. **Удаление проверяется фактически**: `rm`, затем проверка существования; отказ виден как
 *     `leftover` и не маскируется.
 */
object ScriptDelivery {

    /** Жёсткий предел одного ADB-пакета в ветке `ROOT_5555` (`A_MAXDATA_LEGACY`). */
    const val MAX_PAYLOAD = 4096

    /** Предел «прямой» отправки (меньше лимита на запас обёртки). */
    const val DIRECT_LIMIT = 3500

    /** Верхняя граница разумного размера скрипта. */
    const val MAX_SCRIPT_BYTES = 512 * 1024

    enum class Transport { DIRECT, UPLOAD }

    /**
     * Фаза доставки — то, что МОЖНО утверждать.
     *
     * [UPLOAD_INCOMPLETE] — доказано, что `sh` не выполнялся (маркер печатается до запуска).
     * [RUN_SENT] — содержимое сверено, команда запуска передана; ответ есть.
     * [RUN_CONFIRMED] — terminal-файл операции содержит `rc=…` (факт завершения).
     * [RUN_UNKNOWN] — ответ потерян и terminal не прочитан: запуск МОГ произойти.
     */
    enum class State {
        NOT_SENT, UPLOAD_INCOMPLETE, RUN_SENT, RUN_CONFIRMED, RUN_UNKNOWN, TOO_LARGE
    }

    data class Plan(
        val transport: Transport,
        val bytes: Int,
        val chunks: Int,
        val path: String,
        val cksum: Long,
        val reason: String
    ) {
        fun wire(): String =
            "transport=${transport.name.lowercase()} bytes=$bytes chunks=$chunks " +
                "cksum=$cksum path=${path.ifEmpty { "none" }}" +
                (if (reason.isEmpty()) "" else " reason=$reason")
    }

    data class Result(
        val rc: Int,
        val out: String,
        val plan: Plan,
        val attempts: Int,
        val state: State,
        /** Размер сверен на устройстве (маркер с совпавшими числами). */
        val sizeChecked: Boolean,
        /** Содержимое сверено через `cksum` (а не только длина). */
        val contentChecked: Boolean,
        /** Файл остался на устройстве после попытки удаления (честный факт). */
        val leftoverPath: String,
        /** Запуск МОГ произойти (ответ потерян) — повтор запрещён. */
        val mayHaveRun: Boolean
    ) {
        val delivered: Boolean get() = state == State.RUN_SENT || state == State.RUN_CONFIRMED
        fun wire(): String =
            "rc=$rc state=${state.name.lowercase()} ${plan.wire()} attempts=$attempts " +
                "size_checked=${if (sizeChecked) 1 else 0} " +
                "content_checked=${if (contentChecked) 1 else 0} " +
                "may_have_run=${if (mayHaveRun) 1 else 0} " +
                "leftover=${leftoverPath.ifEmpty { "none" }}"
    }

    // ─────────────────────────── имена и содержимое ───────────────────────────

    fun pathFor(name: String, nonce: String): String =
        "/data/local/tmp/voiceapp3_" + sanitize(name) + "_" + sanitize(nonce) + ".sh"

    fun sanitize(s: String): String =
        s.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString("")

    /** 16 hex вместо 8: вероятность коллизии падает на 8 порядков (и всё равно не «гарантия»). */
    fun newNonce(rnd: Random = Random.Default): String =
        (1..16).map { "0123456789abcdef"[rnd.nextInt(16)] }.joinToString("")

    /**
     * POSIX `cksum` (CRC-32, полином 0x04C11DB7, MSB-first, init 0, финальная инверсия).
     *
     * Нужен, чтобы знать ОЖИДАЕМОЕ значение и сравнить его с выводом `cksum` на устройстве:
     * одинаковая длина не доказывает одинаковые байты.
     */
    fun cksum(bytes: ByteArray): Long {
        var crc = 0L
        for (b in bytes) {
            crc = crc xor ((b.toLong() and 0xffL) shl 24)
            for (i in 0 until 8) {
                crc = if (crc and 0x80000000L != 0L) {
                    ((crc shl 1) xor 0x04C11DB7L) and 0xFFFFFFFFL
                } else {
                    (crc shl 1) and 0xFFFFFFFFL
                }
            }
        }
        // cksum дополняет CRC длиной файла (тоже MSB-first), затем инвертирует.
        var len = bytes.size.toLong()
        while (len != 0L) {
            crc = crc xor ((len and 0xffL) shl 24)
            for (i in 0 until 8) {
                crc = if (crc and 0x80000000L != 0L) {
                    ((crc shl 1) xor 0x04C11DB7L) and 0xFFFFFFFFL
                } else {
                    (crc shl 1) and 0xFFFFFFFFL
                }
            }
            len = len shr 8
        }
        return crc.inv() and 0xFFFFFFFFL
    }

    /** Выбор транспорта по ФАКТИЧЕСКОЙ длине UTF-8. */
    fun plan(name: String, script: String, nonce: String = newNonce()): Plan {
        val bytes = script.toByteArray(StandardCharsets.UTF_8)
        return when {
            bytes.size > MAX_SCRIPT_BYTES -> Plan(
                Transport.UPLOAD, bytes.size, 0, "", 0L, "script_too_large>${MAX_SCRIPT_BYTES}"
            )
            bytes.size <= DIRECT_LIMIT -> Plan(
                Transport.DIRECT, bytes.size, 0, "", cksum(bytes), ""
            )
            else -> Plan(
                Transport.UPLOAD, bytes.size, ScriptChunker.chunks(script).size,
                pathFor(name, nonce), cksum(bytes), ""
            )
        }
    }

    // ─────────────────────────── команды устройства ───────────────────────────

    /**
     * Эксклюзивное создание файла (`set -C` = noclobber).
     *
     * `CREATED` — файл наш и пуст; `EXISTS` — имя занято (чужой/старый файл): НЕ перезаписываем
     * и НЕ удаляем, берём другой nonce.
     */
    fun createCommand(path: String): String =
        "if (set -C; : > " + path + ") 2>/dev/null; then echo CREATED; else echo EXISTS; fi"

    /** Полная команда запуска: сверка размера И содержимого, затем terminal-файл и запуск. */
    fun runCommand(path: String, expectedBytes: Int, expectedCksum: Long): String =
        "chmod 700 $path; " +
            "SZ=\$(wc -c < $path 2>/dev/null); " +
            "CS=\$(cksum < $path 2>/dev/null | awk '{print \$1}'); " +
            "echo \"SCRIPT_BYTES=\$SZ EXPECTED=$expectedBytes CS=\$CS EXPECT_CS=$expectedCksum\"; " +
            "if [ \"\$SZ\" != \"$expectedBytes\" ]; then " +
            "echo \"ERR_SCRIPT_UPLOAD size expected=$expectedBytes actual=\$SZ\"; " +
            "rm -f $path 2>/dev/null; exit 99; fi; " +
            "if [ -n \"\$CS\" ] && [ \"\$CS\" != \"$expectedCksum\" ]; then " +
            "echo \"ERR_SCRIPT_UPLOAD content cksum=\$CS expected=$expectedCksum\"; " +
            "rm -f $path 2>/dev/null; exit 98; fi; " +
            "echo \"SCRIPT_CONTENT_OK checked=\$([ -n \"\$CS\" ] && echo 1 || echo 0)\"; " +
            "sh $path; RC=\$?; echo \"rc=\$RC\" > $path.done 2>/dev/null; " +
            "echo \"SCRIPT_RUN_DONE rc=\$RC\"; rm -f $path 2>/dev/null; exit \$RC"

    /** Удаление с ДОКАЗАТЕЛЬСТВОМ: код возврата `rm` и фактическая проверка существования. */
    fun cleanCommand(path: String): String =
        "rm -f $path $path.done $path.started 2>/dev/null; RMC=\$?; " +
            "if [ -e $path ] || [ -e $path.done ]; then " +
            "echo \"RM_FAIL rc=\$RMC exists=1\"; else echo \"RM_OK rc=\$RMC exists=0\"; fi"

    /** Чтение terminal-файла операции (короткая read-only команда). */
    fun doneCommand(path: String): String =
        "echo \"DONE=\$(cat $path.done 2>/dev/null)\"; " +
            "echo \"PRESENT=\$([ -e $path ] && echo 1 || echo 0)\""

    // ─────────────────────────── доставка ───────────────────────────

    fun deliver(
        name: String,
        script: String,
        timeoutMs: Int,
        expectRoot: Boolean,
        exec: (String, Int, Boolean) -> Pair<Int, String>,
        tries: Int = 3,
        nonce: String = newNonce()
    ): Result {
        val plan = plan(name, script, nonce)
        if (plan.reason.startsWith("script_too_large")) {
            return Result(1, "ERR_SCRIPT_TOO_LARGE: bytes=${plan.bytes} limit=$MAX_SCRIPT_BYTES",
                plan, 0, State.TOO_LARGE, false, false, "", false)
        }
        if (plan.transport == Transport.DIRECT) {
            val (rc, out) = exec(script, timeoutMs, expectRoot)
            val lost = out.contains("ERR_ADB_TIMEOUT") || out.contains("ERR_ADB_")
            return Result(rc, out, plan, 1,
                if (lost) State.RUN_UNKNOWN else State.RUN_SENT,
                true, false, "", lost)
        }

        var lastRc = 1
        var lastOut = ""
        var attempts = 0
        var path = plan.path
        var currentNonce = nonce

        for (attempt in 1..tries) {
            attempts = attempt
            // 1) Эксклюзивное создание: занятое имя не перезаписываем — берём новое.
            var created = false
            // ВАЖНО: `return@repeat` в Kotlin НЕ прерывает `repeat` — он лишь завершает текущую
            // итерацию. Из-за этого после успешного создания цикл шёл дальше, получал `EXISTS`
            // и создавал ВТОРОЙ файл, который потом никто не удалял (найдено тестом на реальном
            // shell: `files2=[voiceapp3_lock_bind_<A>.sh, voiceapp3_lock_bind_<B>.sh]`).
            for (i in 0 until 3) {
                val (rcCreate, outCreate) = exec(createCommand(path), 8000, expectRoot)
                if (rcCreate == 0 && outCreate.contains("CREATED")) {
                    created = true
                    break
                }
                if (outCreate.contains("EXISTS")) {
                    // Имя занято (чужой/старый файл): НЕ трогаем его, берём новое.
                    currentNonce = newNonce()
                    path = pathFor(name, currentNonce)
                    continue
                }
                break
            }
            if (!created) {
                lastRc = 1
                lastOut = "ERR_SCRIPT_UPLOAD_CREATE: $lastOut"
                continue
            }

            // 2) Заливка чанками. Любой отказ — скрипт НЕ запускался (файла целиком нет).
            var chunkFailed = false
            for (c in ScriptChunker.chunks(script)) {
                val (rcChunk, outChunk) = exec(ScriptChunker.chunkCommand(path, c), 15000, expectRoot)
                if (rcChunk != 0) {
                    lastRc = rcChunk
                    lastOut = "ERR_SCRIPT_CHUNK: $outChunk"
                    chunkFailed = true
                    break
                }
            }
            if (chunkFailed) {
                cleanup(path, exec, expectRoot)
                continue
            }

            // 3) Сверка + запуск. Ответ мог потеряться ПОСЛЕ запуска — это отдельная фаза.
            val (rcRun, outRun) = exec(
                runCommand(path, plan.bytes, plan.cksum), timeoutMs + 15000, expectRoot
            )
            lastRc = rcRun
            lastOut = outRun
            val explicitNoRun = outRun.contains("ERR_SCRIPT_UPLOAD")
            val sizeMarker = Regex("SCRIPT_BYTES=(\\d+) EXPECTED=(\\d+)").find(outRun)
            val sizeOk = sizeMarker != null &&
                sizeMarker.groupValues[1] == sizeMarker.groupValues[2]
            val contentOk = outRun.contains("SCRIPT_CONTENT_OK checked=1")

            if (explicitNoRun) {
                // Маркер печатается ДО `sh`: запуска не было, повторять безопасно.
                cleanup(path, exec, expectRoot)
                continue
            }
            if (outRun.contains("SCRIPT_CONTENT_OK") || sizeOk) {
                val finished = Regex("SCRIPT_RUN_DONE rc=(-?\\d+)").find(outRun)
                val leftover = cleanup(path, exec, expectRoot)
                return Result(
                    rc = finished?.groupValues?.get(1)?.toIntOrNull() ?: rcRun,
                    out = outRun,
                    plan = plan.copy(path = path),
                    attempts = attempt,
                    state = if (finished != null) State.RUN_CONFIRMED else State.RUN_UNKNOWN,
                    sizeChecked = sizeOk,
                    contentChecked = contentOk,
                    leftoverPath = leftover,
                    mayHaveRun = finished == null
                )
            }
            // Ни маркера, ни явного отказа: ответ потерян. Запуск МОГ произойти — не повторяем,
            // пробуем прочитать terminal-файл (read-only) и вернуть честную фазу.
            val reconciled = reconcile(path, exec, expectRoot)
            val leftover = cleanup(path, exec, expectRoot)
            return Result(
                rc = reconciled ?: rcRun,
                out = outRun,
                plan = plan.copy(path = path),
                attempts = attempt,
                state = if (reconciled != null) State.RUN_CONFIRMED else State.RUN_UNKNOWN,
                sizeChecked = false,
                contentChecked = false,
                leftoverPath = leftover,
                mayHaveRun = reconciled == null
            )
        }
        // Ни одна попытка не дошла до запуска: это ДОКАЗАННЫЙ «не отправлено/не запущено».
        val leftover = cleanup(path, exec, expectRoot)
        return Result(lastRc, lastOut, plan.copy(path = path), attempts,
            State.UPLOAD_INCOMPLETE, false, false, leftover, false)
    }

    /**
     * Прочитать terminal-файл запуска. `null` — файла нет или прочитать нельзя (НЕ «не выполнено»).
     */
    fun reconcile(
        path: String,
        exec: (String, Int, Boolean) -> Pair<Int, String>,
        expectRoot: Boolean
    ): Int? {
        val r = runCatching { exec(doneCommand(path), 8000, expectRoot) }.getOrNull() ?: return null
        if (r.first != 0 || r.second.contains("ERR_")) return null
        val m = Regex("DONE=rc=(-?\\d+)").find(r.second) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /** Удалить ТОЛЬКО свои файлы; без доказательства удаления вернуть путь как leftover. */
    private fun cleanup(
        path: String,
        exec: (String, Int, Boolean) -> Pair<Int, String>,
        expectRoot: Boolean
    ): String {
        val r = runCatching { exec(cleanCommand(path), 8000, expectRoot) }.getOrNull()
            ?: return path
        if (r.first != 0) return path
        return if (r.second.contains("RM_OK")) "" else path
    }
}
