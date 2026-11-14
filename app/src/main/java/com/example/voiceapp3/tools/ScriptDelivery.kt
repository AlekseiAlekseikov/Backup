package com.example.voiceapp3.tools

import java.nio.charset.StandardCharsets
import kotlin.random.Random

/**
 * iter51 §3.1 — ДОСТАВКА сгенерированного скрипта на устройство как отдельная, проверяемая
 * единица.
 *
 * Причина появления: bind-скрипт звука замка (`LockMountScripts.bindScript`) — **8935 байт**,
 * restore — **5593 байта на одну цель** и **12794 на двенадцать** (измерено, см.
 * `Iter51DeliveryTest`). Оба отправлялись через `AdbClient.execute`, который в ветке `ROOT_5555`
 * отклоняет payload больше 4096 байт (`ERR_ADB_TOO_LONG`, `AdbClient.kt:2104`) ДО отправки.
 * То есть на этом канале звук замка не мог включиться вообще: скрипт не доезжал до устройства,
 * а «mount не выполнено» выглядело как обычная неудача применения.
 *
 * Здесь собрана вся логика доставки: выбор транспорта по ФАКТИЧЕСКОЙ длине, уникальное имя
 * файла, заливка чанками, сверка размера, запуск и удаление ТОЛЬКО своего файла. Исполнитель
 * команд передаётся снаружи (`exec`), поэтому ровно этот код можно прогнать против fake-ADB,
 * который воспроизводит лимит 4096 и виртуальную файловую систему устройства.
 *
 * Чего этот слой НЕ обещает (см. отчёт iter51):
 *  • отмену уже загруженного/запущенного скрипта: если ответ потерян, скрипт мог выполниться;
 *  • межпроцессной атомарности «залил — запустил»: уникальное имя исключает затирание чужого
 *    файла, но не превращает заливку и запуск в одну транзакцию;
 *  • гарантий ФС устройства при отключении питания.
 */
object ScriptDelivery {

    /** Жёсткий предел одного ADB-пакета в ветке `ROOT_5555` (`A_MAXDATA_LEGACY`). */
    const val MAX_PAYLOAD = 4096

    /**
     * Предел для «прямой» отправки. Меньше [MAX_PAYLOAD] на запас: `AdbClient.execute`
     * оборачивает команду (`echo rc`), а обёртка тоже едет в payload.
     */
    const val DIRECT_LIMIT = 3500

    /** Верхняя граница того, что вообще разумно заливать скриптом (защита от ошибки сборки). */
    const val MAX_SCRIPT_BYTES = 512 * 1024

    enum class Transport {
        /** Одна команда целиком (скрипт мал). */
        DIRECT,

        /** Файл в `/data/local/tmp` + чанки base64 + сверка размера + `sh`. */
        UPLOAD
    }

    data class Plan(
        val transport: Transport,
        val bytes: Int,
        val chunks: Int,
        val path: String,
        val reason: String
    ) {
        fun wire(): String =
            "transport=${transport.name.lowercase()} bytes=$bytes chunks=$chunks path=$path" +
                (if (reason.isEmpty()) "" else " reason=$reason")
    }

    /** Результат доставки: код возврата скрипта и его вывод (stdout+stderr). */
    data class Result(
        val rc: Int,
        val out: String,
        val plan: Plan,
        val attempts: Int,
        val sizeChecked: Boolean,
        val uploadedBytes: Int,
        /** Файл на устройстве остался (удалить не удалось) — честный факт, не успех. */
        val leftoverPath: String
    ) {
        val delivered: Boolean get() = sizeChecked && leftoverPath.isEmpty() || (sizeChecked && rc == 0)
        fun wire(): String =
            "rc=$rc ${plan.wire()} attempts=$attempts size_checked=$sizeChecked " +
                "uploaded=$uploadedBytes leftover=${leftoverPath.ifEmpty { "none" }}"
    }

    /** Уникальное имя файла: имя задачи + случайный суффикс (PID reuse и параллельные запуски). */
    fun pathFor(name: String, nonce: String): String =
        "/data/local/tmp/voiceapp3_" + sanitize(name) + "_" + sanitize(nonce) + ".sh"

    fun sanitize(s: String): String =
        s.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString("")

    fun newNonce(rnd: Random = Random.Default): String =
        (1..8).map { "0123456789abcdef"[rnd.nextInt(16)] }.joinToString("")

    /** Выбор транспорта по ФАКТИЧЕСКОЙ длине UTF-8 (никаких оценок «на глаз»). */
    fun plan(name: String, script: String, nonce: String = newNonce()): Plan {
        val bytes = script.toByteArray(StandardCharsets.UTF_8).size
        return when {
            bytes > MAX_SCRIPT_BYTES -> Plan(
                Transport.UPLOAD, bytes, 0, "", "script_too_large>${MAX_SCRIPT_BYTES}"
            )
            bytes <= DIRECT_LIMIT -> Plan(Transport.DIRECT, bytes, 0, "", "")
            else -> Plan(Transport.UPLOAD, bytes, ScriptChunker.chunks(script).size, pathFor(name, nonce), "")
        }
    }

    /**
     * Доставить и выполнить скрипт.
     *
     * Гарантии:
     *  • при [Transport.UPLOAD] скрипт НЕ запускается, если хотя бы один чанк не принят или
     *    сверка размера не совпала (иначе на устройстве выполнился бы обрезанный скрипт —
     *    для bind это означало бы частично применённый набор монтирований);
     *  • файл создаётся с УНИКАЛЬНЫМ именем, поэтому параллельная/чужая заливка его не затрёт;
     *  • удаляется только свой файл; неудача удаления не выдаётся за успех.
     */
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
                plan, 0, false, 0, "")
        }
        if (plan.transport == Transport.DIRECT) {
            val (rc, out) = exec(script, timeoutMs, expectRoot)
            return Result(rc, out, plan, 1, true, plan.bytes, "")
        }
        val path = plan.path
        var lastRc = 1
        var lastOut = ""
        var attempts = 0
        for (attempt in 1..tries) {
            attempts = attempt
            val (rcClear, outClear) = exec(ScriptChunker.clearCommand(path), 8000, expectRoot)
            if (rcClear != 0) {
                lastRc = rcClear
                lastOut = "ERR_SCRIPT_UPLOAD_CLEAR: $outClear"
                continue
            }
            var sent = 0
            var chunkFailed = false
            for (c in ScriptChunker.chunks(script)) {
                val (rcChunk, outChunk) = exec(ScriptChunker.chunkCommand(path, c), 15000, expectRoot)
                if (rcChunk != 0) {
                    lastRc = rcChunk
                    lastOut = "ERR_SCRIPT_UPLOAD_CHUNK #$sent: $outChunk"
                    chunkFailed = true
                    break
                }
                sent++
            }
            if (chunkFailed) continue
            val (rc, out) = exec(
                ScriptChunker.verifyAndRunCommand(path, plan.bytes), timeoutMs + 10000, expectRoot
            )
            lastRc = rc
            lastOut = out
            if (out.contains("ERR_SCRIPT_UPLOAD")) continue
            // Сверка прошла, скрипт запущен. Свой файл убираем сами: `verifyAndRunCommand` уже
            // делает `rm -f`, но при обрыве ответа он мог не выполниться.
            val leftover = cleanup(path, exec, expectRoot)
            return Result(rc, out, plan, attempt, true, plan.bytes, leftover)
        }
        // Не удалось залить/сверить — скрипт НЕ запускался. Файл (если остался) убираем.
        val leftover = cleanup(path, exec, expectRoot)
        return Result(lastRc, lastOut, plan, attempts, false, 0, leftover)
    }

    /** Удалить ТОЛЬКО свой файл; вернуть его путь, если удалить не удалось. */
    private fun cleanup(
        path: String,
        exec: (String, Int, Boolean) -> Pair<Int, String>,
        expectRoot: Boolean
    ): String {
        val r = runCatching { exec("rm -f '$path' 2>/dev/null; echo RM_DONE", 8000, expectRoot) }
            .getOrNull()
        return if (r == null || r.first != 0 || !r.second.contains("RM_DONE")) path else ""
    }
}
