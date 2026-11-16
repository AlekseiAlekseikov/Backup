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

    /** Значение [Result.payloadRc], когда код возврата payload НЕ доказан. */
    const val RC_UNKNOWN: Int = Int.MIN_VALUE

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
        val mayHaveRun: Boolean,
        /** Каким инструментом сверено содержимое: `cksum`/`md5`/`odsum`/`none`. */
        val contentKind: String = "none",
        /**
         * iter53 §6.2: файлы-свидетельства (`<path>`, `<path>.started`, `<path>.done`),
         * ОСТАВЛЕННЫЕ на устройстве, когда исход неизвестен. Пусто — свидетельств не осталось.
         */
        val evidencePath: String = "",
        /**
         * iter59 §7: код ТРАНСПОРТА (ADB/канал), а не код возврата payload.
         * `rc` выше остаётся «лучшим известным ответом», но потребитель, которому важен именно
         * payload, обязан смотреть [payloadRc]: раньше потеря ответа давала в `rc` код канала
         * (`-1`/`1`), и он неотличимо совпадал с кодом скрипта.
         */
        val transportRc: Int = -1,
        /**
         * Код возврата PAYLOAD. [RC_UNKNOWN] — НЕ доказан (не путать с `0 = успех`).
         * Источники по силе: durable `<path>.done` (readback) → маркер завершения этой попытки.
         */
        val payloadRc: Int = RC_UNKNOWN,
        /** Код взят из durable `<path>.done`, а не из stdout. */
        val rcFromDone: Boolean = false
    ) {
        /**
         * iter54 §5: `cksum`/CRC — проверка ЦЕЛОСТНОСТИ заливки, а НЕ криптографическое
         * доказательство. Это поле печатается рядом с `content_checked`, чтобы в журнале не
         * возникало впечатления «содержимое доказано криптографически».
         */
        val contentProof: String get() = when (contentKind) {
            "cksum", "odsum", "md5" -> "integrity_only_not_crypto"
            "direct_marker" -> "no_upload"
            else -> "none"
        }

        val delivered: Boolean get() = state == State.RUN_SENT || state == State.RUN_CONFIRMED
        fun wire(): String =
            "rc=$rc state=${state.name.lowercase()} ${plan.wire()} attempts=$attempts " +
                "size_checked=${if (sizeChecked) 1 else 0} " +
                "content_checked=${if (contentChecked) 1 else 0} " +
                "may_have_run=${if (mayHaveRun) 1 else 0} " +
                "content_kind=$contentKind content_proof=$contentProof " +
                "leftover=${leftoverPath.ifEmpty { "none" }} " +
                "evidence=${evidencePath.ifEmpty { "none" }}"
    }

    /**
     * iter54 §5 — ССЫЛКА НА СВИДЕТЕЛЬСТВО НА УСТРОЙСТВЕ.
     *
     * Это не «доказательство выполнения», а указание, ГДЕ искать факт: путь скрипта и его
     * служебные файлы `.started`/`.done`. Поздняя сверка ([evidenceCommand]) читает их без
     * повторного side effect.
     */
    data class EvidenceRef(
        val path: String,
        val started: String = "unknown",
        val done: String = "unknown",
        val note: String = ""
    ) {
        fun wire(): String = "evidence_path=${path.ifEmpty { "none" }} " +
            "started=${started.ifEmpty { "unknown" }} done=${done.ifEmpty { "unknown" }}" +
            (if (note.isEmpty()) "" else " note=$note")
    }

    /**
     * iter54 §5 — ИСХОД ДОСТАВКИ В ТЕРМИНАХ, КОТОРЫЕ ДОХОДЯТ ДО ПОТРЕБИТЕЛЯ.
     *
     * Проблема, которую это закрывает: `Result` жил только в `AdbClient`, а потребители
     * (звук замка, TTL) получали `Pair<rc, out>`. `RUN_UNKNOWN` при этом исчезал: «ответ
     * потерян» и «команда не выполнена» выглядели одинаково (`rc != 0`), и вызывающий код
     * мог либо повторять опасную операцию, либо заявить успех.
     *
     * Теперь у каждого потребителя есть ТРИ различимых исхода:
     *  • [NotSent] — доказано, что ни один байт не исполнялся (заливка неполна/не создана);
     *  • [Unknown] — могло исполниться, но факта нет: нужна сверка по [EvidenceRef];
     *  • [Finished] — факт завершения есть (`rc`), это НЕ доказательство нужного эффекта.
     */
    sealed interface DeliveryOutcome {
        data class NotSent(val why: String) : DeliveryOutcome

        data class Unknown(
            val operationId: String,
            val evidence: EvidenceRef,
            val why: String
        ) : DeliveryOutcome

        data class Finished(val operationId: String, val rc: Int) : DeliveryOutcome

        fun wire(): String = when (this) {
            is NotSent -> "outcome=not_sent why=$why"
            is Unknown -> "outcome=unknown op=$operationId why=$why ${evidence.wire()}"
            is Finished -> "outcome=finished op=$operationId rc=$rc"
        }
    }

    /**
     * Отображение фактов доставки в исход для потребителя.
     *
     * Правила намеренно консервативны:
     *  • `RUN_CONFIRMED` → [DeliveryOutcome.Finished] (есть rc — и только это);
     *  • `RUN_SENT` при ПРЯМОЙ отправке → тоже `Finished`: ответ транспорта в этом режиме
     *    содержит rc самого скрипта; при заливке — `Unknown` (запуск передан, но `.done` не
     *    прочитан);
     *  • `RUN_UNKNOWN` → `Unknown` с путём-свидетельством, даже если путь пуст;
     *  • всё остальное — `NotSent` (доказанное «не исполнялось»).
     */
    fun outcome(result: Result, operationId: String): DeliveryOutcome = when (result.state) {
        // iter59 §7: наружу отдаётся код PAYLOAD; если он не доказан, а состояние всё же
        // подтверждено, используется известный rc (иначе потребитель получил бы «-1» как успех).
        State.RUN_CONFIRMED -> DeliveryOutcome.Finished(
            operationId,
            if (result.payloadRc != RC_UNKNOWN) result.payloadRc else result.rc
        )
        // iter55 §5.1: «отправлено» ≠ «завершено». Terminal есть только у RUN_CONFIRMED.
        State.RUN_SENT -> DeliveryOutcome.Unknown(
            operationId, EvidenceRef(result.evidencePath.ifEmpty { result.plan.path }),
            "run_sent_terminal_not_proven"
        )
        State.RUN_UNKNOWN -> DeliveryOutcome.Unknown(
            operationId, EvidenceRef(result.evidencePath.ifEmpty { result.plan.path }),
            if (result.mayHaveRun) "stdout_lost_may_have_run" else "state_unknown"
        )
        State.UPLOAD_INCOMPLETE -> DeliveryOutcome.NotSent("upload_incomplete")
        State.TOO_LARGE -> DeliveryOutcome.NotSent("script_too_large")
        State.NOT_SENT -> DeliveryOutcome.NotSent("not_sent")
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

    /**
     * MD5 в hex — второй по доступности инструмент (`md5sum` есть в toybox большинства прошивок).
     *
     * Нужен как fallback, когда `cksum` в системе отсутствует: молча запускать непроверенный
     * файл нельзя (iter53 §6.2.4), но и «проверки нет» — плохой ответ для звука замка.
     */
    fun md5Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * Универсальный fallback без внешних утилит: `od -An -tu1 -v < файл | awk …`.
     *
     * Считается 32-битная полиномиальная сумма по байтам с умножением на 131 и модулем 2^32.
     * `od` и `awk` есть в toybox практически всегда. Это НЕ криптография: цель — обнаружить
     * обрезанную/подменённую заливку, а не защититься от противника.
     */
    fun odsum(bytes: ByteArray): Long {
        var s = 0L
        for (b in bytes) {
            s = (s * 131L + (b.toLong() and 0xffL)) % 4294967296L
        }
        return s
    }

    /**
     * Обёртка ПРЯМОЙ отправки: маркеры до и после скрипта.
     *
     * Зачем: без них «ответ транспорта есть» приходилось трактовать как «скрипт выполнился».
     * Маркер `VAPP_DIRECT_DONE rc=…` делает завершение фактом, а его отсутствие при потерянном
     * ответе — честной неизвестностью (iter54 §5).
     */
    fun directScript(
        script: String,
        nonce: String = newNonce(),
        terminalPath: String = ""
    ): String {
        val n = sanitize(nonce)
        // ПОДОБОЛОЧКА обязательна: `exit N` внутри скрипта иначе завершит весь shell и маркер
        // завершения не напечатается — «ответ есть, а маркера нет» снова стало бы догадкой.
        //
        // iter55 §5.1: маркер несёт НОМЕР ПОПЫТКИ. Без него строка `VAPP_DIRECT_DONE` в теле
        // самого payload (эхо, сообщение об ошибке, чужой вывод) могла быть принята за
        // завершение — terminal должен принадлежать ИМЕННО этой попытке.
        //
        // iter57 §5.1: при заданном [terminalPath] тот же shell оставляет на устройстве `rc=N`
        // в `<path>.done`. Прямая отправка больше не «невосстановима»: после потери ответа
        // terminal читается read-only ([evidenceCommand]), а не угадывается повтором.
        val terminal = if (terminalPath.isEmpty()) "" else
            // iter58 §4: `_VRC` снят один раз СРАЗУ после payload — здесь его не переснимаем.
            // Дефект iter57: второй `_VRC=$?` захватывал код предыдущего `echo`, поэтому при
            // payload `exit 7` в `<path>.done` попадал `rc=0`, и «завершено успешно» выводилось
            // из собственного украшения обёртки, а не из кода возврата скрипта.
            "; echo \"rc=\$_VRC\" > " + terminalPath + ".done 2>/dev/null; " +
                "if [ -e " + terminalPath + ".done ]; then " +
                "echo \"VAPP_DIRECT_TERMINAL_" + n + " written=1\"; else " +
                "echo \"VAPP_DIRECT_TERMINAL_" + n + " written=0\"; fi; exit \$_VRC"
        val body = if (terminalPath.isEmpty()) {
            "( echo VAPP_DIRECT_STARTED_" + n + "; " + script + "\n) ; echo " +
                "\"VAPP_DIRECT_DONE_" + n + " rc=\$?\""
        } else {
            "( echo VAPP_DIRECT_STARTED_" + n + "; " + script + "\n) ; _VRC=\$?; " +
                "echo \"VAPP_DIRECT_DONE_" + n + " rc=\$_VRC\"" + terminal
        }
        return body
    }

    /**
     * Разбор прямой отправки по фактам её вывода.
     *
     * iter55 §5.1: отсутствие маркера завершения — это `RUN_UNKNOWN`, а НЕ «отправлено и
     * выполнено». Прежний код возвращал `RUN_SENT`, и `outcome` превращал его в `Finished`:
     * пустой/обрезанный ответ без единого `ERR_ADB_*` (другой код ошибки, потеря вывода,
     * завершение канала) объявлялся успешным выполнением скрипта.
     *
     * Маркер ищется вместе с НОМЕРОМ ПОПЫТКИ ([nonce]), поэтому строка маркера, напечатанная
     * самим payload или предыдущей попыткой, за terminal не принимается.
     */
    fun directResult(
        name: String,
        script: String,
        rc: Int,
        out: String,
        nonce: String = "",
        prepared: Prepared? = null
    ): Result {
        val plan = prepared?.plan ?: plan(name, script)
        // iter57 §5.1: доказал ли САМ shell, что terminal лёг на устройство. Только в этом случае
        // путь-свидетельство попадает в результат: без файла `Unknown` нечем разрешать, и
        // выдавать nonce из stdout за восстанавливаемое свидетельство нельзя.
        val termWritten = nonce.isNotEmpty() && out.contains(
            "VAPP_DIRECT_TERMINAL_" + sanitize(nonce) + " written=1"
        )
        val evidence = if (termWritten) plan.path else ""
        val lost = out.contains("ERR_ADB_TIMEOUT") || out.contains("ERR_ADB_")
        val marker = if (nonce.isEmpty()) Regex("VAPP_DIRECT_DONE rc=(-?\\d+)")
        else Regex("VAPP_DIRECT_DONE_" + Regex.escape(sanitize(nonce)) + " rc=(-?\\d+)")
        val doneLines = marker.findAll(out).toList()
        return when {
            // Ровно один маркер этой попытки: два и больше — не terminal, а мусор/подделка.
            doneLines.size == 1 -> {
                val payload = doneLines.first().groupValues[1].toIntOrNull()
                Result(
                    payload ?: rc, out, plan, 1,
                    State.RUN_CONFIRMED, true, false, "", false, contentKind = "direct_marker",
                    evidencePath = evidence,
                    // iter59 §7: код маркера — код PAYLOAD; rc транспорта хранится отдельно.
                    transportRc = rc,
                    payloadRc = payload ?: RC_UNKNOWN
                )
            }
            else -> Result(
                rc, out, plan, 1, State.RUN_UNKNOWN, false, false, "", true,
                contentKind = if (lost) "direct_none" else "direct_no_terminal",
                evidencePath = evidence,
                // iter59 §7: без маркера код payload НЕ доказан — в `payloadRc` его нет.
                transportRc = rc, payloadRc = RC_UNKNOWN
            )
        }
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

    // ─────────────────── iter57 §5.1: ПОДГОТОВКА ДО ОТПРАВКИ ───────────────────

    /**
     * ПОДГОТОВЛЕННАЯ доставка: имя, nonce, транспорт и ПУТЬ СВИДЕТЕЛЬСТВА известны ДО того, как
     * на устройство уйдёт первый байт.
     *
     * Зачем это отдельный тип. В iter56 потребитель (звук) писал в журнал `PLANNED` без пути
     * свидетельства, отправлял скрипт и только по возвращении `Unknown` узнавал путь — из
     * результата. Смерть процесса между отправкой и возвратом оставляла возможный старый shell
     * БЕЗ адреса, по которому его terminal можно дочитать: операция оставалась «неизвестной»
     * навсегда, а новый bind при этом мог быть отправлен.
     *
     * Теперь путь входит в [Plan] до отправки (`pathFor(name, nonce)`), потребитель сохраняет его
     * в журнале и передаёт в [com.example.voiceapp3.tools.AdbClient.executePrepared] — то есть
     * отправляется РОВНО тот nonce, чьё свидетельство записано. Повторной «скрытой» identity нет.
     */
    data class Prepared(
        val name: String,
        val nonce: String,
        val script: String,
        val plan: Plan
    ) {
        /**
         * Путь устройства, по которому лежит (или не лежит) свидетельство исполнения:
         * `<path>` — скрипт, `<path>.started`, `<path>.done` — terminal с `rc=`.
         *
         * Для ПРЯМОЙ отправки iter57 тоже пишет device-side `.done` (см. [directScript]):
         * раньше «прямой» путь не оставлял на устройстве ничего, и потерянный ответ нельзя было
         * разрешить иначе, чем повторной опасной отправкой.
         */
        val evidencePath: String get() = plan.path

        fun wire(): String =
            "name=$name nonce=$nonce ${plan.wire()} evidence=${evidencePath.ifEmpty { "none" }}"
    }

    /** Подготовить доставку (без отправки). Один nonce на попытку и на её свидетельство. */
    fun prepare(
        name: String,
        script: String,
        nonce: String = newNonce()
    ): Prepared {
        val safe = sanitize(nonce)
        val plan0 = plan(name, script, safe)
        // Для прямого транспорта путь считается так же: terminal пишется в `<path>.done` той же
        // командой, что исполняет скрипт. Для «слишком большой» — пути нет (отправки не будет).
        val plan = if (plan0.transport == Transport.DIRECT)
            plan0.copy(path = pathFor(name, safe)) else plan0
        return Prepared(sanitize(name), safe, script, plan)
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

    /**
     * Полная команда запуска: сверка размера И содержимого, затем terminal-файл и запуск.
     *
     * iter53 §6.2.4: раньше при отсутствии `cksum` (`CS` пустой) скрипт ВСЁ РАВНО запускался с
     * `checked=0`, то есть «сверено» означало «не сверяли». Теперь:
     *  • содержимое сверяется первым доступным инструментом: `cksum` → `md5sum` → `od|awk`;
     *  • если ни одного нет — скрипт НЕ запускается (`exit 97`), а не «запускается без проверки»;
     *  • перед `sh` пишется `<path>.started`, чтобы поздняя сверка отличала «начал» от «не начал»;
     *  • `rm` файла до запуска выполняется только там, где запуск ДОКАЗАННО не состоялся, и
     *    `rm`+`echo` больше не маскируют код возврата (rc берётся от `rm`).
     */
    fun runCommand(
        path: String,
        expectedBytes: Int,
        expectedCksum: Long,
        expectedMd5: String = "",
        expectedOdsum: Long = -1L
    ): String {
        val odExpr = "{for(i=1;i<=NF;i++){s=(s*131+${'$'}i)%4294967296}} END{printf \"%d\\n\", s}"
        return "chmod 700 $path 2>/dev/null; " +
            "SZ=\$(wc -c < $path 2>/dev/null); " +
            "echo \"SCRIPT_BYTES=\$SZ EXPECTED=$expectedBytes\"; " +
            "if [ \"\$SZ\" != \"$expectedBytes\" ]; then " +
            "echo \"ERR_SCRIPT_UPLOAD size expected=$expectedBytes actual=\$SZ\"; " +
            "rm -f $path 2>/dev/null; _rmc=\$?; [ \"\$_rmc\" = 0 ] && [ ! -e $path ] && RM=1 || RM=0; " +
            "echo \"CLEANUP_PROVEN not_started=1 removed=\$RM rc=\$_rmc\"; exit 99; fi; " +
            "KIND=none; GOT=none; " +
            "if command -v cksum >/dev/null 2>&1; then " +
            "  GOT=\$(cksum < $path 2>/dev/null | awk '{print \$1}'); [ -n \"\$GOT\" ] && KIND=cksum; fi; " +
            "if [ \"\$KIND\" = none ] && command -v md5sum >/dev/null 2>&1; then " +
            "  GOT=\$(md5sum < $path 2>/dev/null | awk '{print \$1}'); [ -n \"\$GOT\" ] && KIND=md5; fi; " +
            (if (expectedOdsum >= 0L) "if [ \"\$KIND\" = none ]; then " +
                "  GOT=\$(od -An -tu1 -v < $path 2>/dev/null | awk '" + odExpr + "'); " +
                "  [ -n \"\$GOT\" ] && KIND=odsum; fi; " else "") +
            "echo \"SCRIPT_CONTENT_KIND=\$KIND GOT=\$GOT EXPECT_CS=$expectedCksum " +
            "EXPECT_MD5=$expectedMd5 EXPECT_OD=${if (expectedOdsum >= 0L) expectedOdsum else -1}\"; " +
            "BAD=0; " +
            "case \"\$KIND\" in " +
            "  cksum) [ \"\$GOT\" != \"$expectedCksum\" ] && BAD=1 ;; " +
            (if (expectedMd5.isNotEmpty()) "  md5) [ \"\$GOT\" != \"$expectedMd5\" ] && BAD=1 ;; " else "") +
            (if (expectedOdsum >= 0L) "  odsum) [ \"\$GOT\" != \"$expectedOdsum\" ] && BAD=1 ;; " else "") +
            "  *) BAD=2 ;; " +
            "esac; " +
            "if [ \"\$BAD\" != 0 ]; then " +
            "  echo \"ERR_SCRIPT_UPLOAD content kind=\$KIND got=\$GOT expect=$expectedCksum/$expectedMd5/" +
            "${if (expectedOdsum >= 0L) expectedOdsum else -1} \"; " +
            "  rm -f $path 2>/dev/null; _rmc=\$?; [ \"\$_rmc\" = 0 ] && [ ! -e $path ] && RM=1 || RM=0; " +
            "  echo \"CLEANUP_PROVEN not_started=1 removed=\$RM rc=\$_rmc\"; " +
            "  if [ \"\$BAD\" = 2 ]; then exit 97; else exit 98; fi; fi; " +
            "echo \"SCRIPT_CONTENT_OK checked=1 kind=\$KIND\"; " +
            "echo \"started=1\" > $path.started 2>/dev/null; " +
            "sh $path; RC=\$?; echo \"rc=\$RC\" > $path.done 2>/dev/null; " +
            "echo \"SCRIPT_RUN_DONE rc=\$RC\"; rm -f $path 2>/dev/null; exit \$RC"
    }

    /**
     * Удаление с ДОКАЗАТЕЛЬСТВОМ: код возврата `rm` и фактическая проверка существования.
     *
     * `withTerminal=false` удаляет ТОЛЬКО скрипт и его служебные файлы, но не `<path>.done`:
     * terminal — свидетельство исхода, и уничтожать его можно лишь там, где исход уже прочитан
     * и учтён (iter53 §6.2.3).
     */
    fun cleanCommand(path: String, withTerminal: Boolean = true): String {
        val targets = if (withTerminal) "$path $path.done $path.started" else "$path $path.started"
        val check = if (withTerminal) "[ -e $path ] || [ -e $path.done ]" else "[ -e $path ]"
        return "rm -f $targets 2>/dev/null; RMC=\$?; " +
            "if $check; then echo \"RM_FAIL rc=\$RMC exists=1\"; else echo \"RM_OK rc=\$RMC exists=0\"; fi"
    }

    /** Read-only: что реально осталось на устройстве по этому пути (для поздней сверки). */
    fun evidenceCommand(path: String): String =
        "echo \"SH=\$([ -e $path ] && echo 1 || echo 0)\"; " +
            "echo \"STARTED=\$([ -e $path.started ] && echo 1 || echo 0)\"; " +
            "echo \"DONE=\$(cat $path.done 2>/dev/null)\"; " +
            "echo \"PRESENT=\$([ -e $path ] && echo 1 || echo 0)\""

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
                plan, 0, State.TOO_LARGE, false, false, "", false,
                transportRc = 1, payloadRc = RC_UNKNOWN)
        }
        if (plan.transport == Transport.DIRECT) {
            // iter54 §5: прямая отправка тоже несёт маркеры начала/завершения — иначе
            // «ответ есть» выдавалось за «скрипт выполнился».
            // Один номер попытки и для маркера, и для разбора: terminal принадлежит ИМЕННО ей.
            val (rc, out) = exec(directScript(script, nonce), timeoutMs, expectRoot)
            return directResult(name, script, rc, out, nonce)
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
            val bytes = script.toByteArray(StandardCharsets.UTF_8)
            val (rcRun, outRun) = exec(
                runCommand(path, plan.bytes, plan.cksum, md5Hex(bytes), odsum(bytes)),
                timeoutMs + 15000, expectRoot
            )
            lastRc = rcRun
            lastOut = outRun
            val explicitNoRun = outRun.contains("ERR_SCRIPT_UPLOAD")
            val sizeMarker = Regex("SCRIPT_BYTES=(\\d+) EXPECTED=(\\d+)").find(outRun)
            val sizeOk = sizeMarker != null &&
                sizeMarker.groupValues[1] == sizeMarker.groupValues[2]
            val contentKind = Regex("SCRIPT_CONTENT_KIND=(\\w+)").find(outRun)
                ?.groupValues?.get(1) ?: "none"
            val contentOk = outRun.contains("SCRIPT_CONTENT_OK checked=1")

            if (explicitNoRun) {
                // Маркер печатается ДО `sh`, а `CLEANUP_PROVEN not_started=1` означает, что удаление
                // тоже проверено. Только здесь `rm` безопасен: запуска ДОКАЗАННО не было.
                val cleaned = cleanup(path, exec, expectRoot)
                if (!outRun.contains("CLEANUP_PROVEN") || cleaned.isNotEmpty()) {
                    // Удаление не доказано (или маркера нет): файл остаётся свидетельством.
                    return Result(
                        rc = rcRun, out = outRun, plan = plan.copy(path = path), attempts = attempt,
                        state = State.UPLOAD_INCOMPLETE, sizeChecked = sizeOk,
                        contentChecked = false, leftoverPath = cleaned, mayHaveRun = false,
                        contentKind = contentKind, evidencePath = if (cleaned.isEmpty()) "" else path,
                        transportRc = rcRun, payloadRc = RC_UNKNOWN
                    )
                }
                continue
            }
            if (outRun.contains("SCRIPT_CONTENT_OK") && sizeOk) {
                val finished = Regex("SCRIPT_RUN_DONE rc=(-?\\d+)").find(outRun)
                if (finished != null) {
                    // Исход известен и прочитан → свидетельства можно убрать (и это проверяется).
                    val leftover = cleanup(path, exec, expectRoot)
                    return Result(
                        rc = finished.groupValues[1].toIntOrNull() ?: rcRun,
                        out = outRun, plan = plan.copy(path = path), attempts = attempt,
                        state = State.RUN_CONFIRMED, sizeChecked = true, contentChecked = contentOk,
                        leftoverPath = leftover, mayHaveRun = false,
                        contentKind = contentKind, evidencePath = if (leftover.isEmpty()) "" else path,
                        transportRc = rcRun,
                        payloadRc = finished.groupValues[1].toIntOrNull() ?: RC_UNKNOWN
                    )
                }
                // Запуск начался, но `SCRIPT_RUN_DONE` не доехал: сначала read-only сверка.
                val rec = reconcile(path, exec, expectRoot)
                if (rec != null) {
                    val leftover = cleanup(path, exec, expectRoot)
                    return Result(
                        rc = rec, out = outRun, plan = plan.copy(path = path), attempts = attempt,
                        state = State.RUN_CONFIRMED, sizeChecked = true, contentChecked = contentOk,
                        leftoverPath = leftover, mayHaveRun = false,
                        contentKind = contentKind, evidencePath = if (leftover.isEmpty()) "" else path,
                        transportRc = rcRun, payloadRc = rec, rcFromDone = true
                    )
                }
                // iter53 §6.2.2: исход НЕИЗВЕСТЕН — свидетельства НЕ удаляются.
                return Result(
                    rc = rcRun, out = outRun, plan = plan.copy(path = path), attempts = attempt,
                    state = State.RUN_UNKNOWN, sizeChecked = true, contentChecked = contentOk,
                    leftoverPath = "", mayHaveRun = true,
                    contentKind = contentKind, evidencePath = path,
                    transportRc = rcRun, payloadRc = RC_UNKNOWN
                )
            }
            // Ни маркера содержимого, ни явного отказа: ответ потерян. Запуск МОГ произойти.
            val reconciled = reconcile(path, exec, expectRoot)
            if (reconciled != null) {
                // Исход доказан terminal-файлом — теперь свидетельства можно убрать.
                val leftover = cleanup(path, exec, expectRoot)
                return Result(
                    rc = reconciled, out = outRun, plan = plan.copy(path = path), attempts = attempt,
                    state = State.RUN_CONFIRMED, sizeChecked = sizeOk, contentChecked = false,
                    leftoverPath = leftover, mayHaveRun = false,
                    contentKind = contentKind, evidencePath = if (leftover.isEmpty()) "" else path,
                    transportRc = rcRun, payloadRc = reconciled, rcFromDone = true
                )
            }
            // iter53 §6.2.2: `cleanup` здесь БОЛЬШЕ НЕ ВЫЗЫВАЕТСЯ. Скрипт и terminal остаются на
            // устройстве: удалённый shell мог стартовать позже, а `.done` мог появиться уже после
            // нашей попытки. Поздняя сверка ([evidenceCommand]) разрешит исход без повторного
            // side effect.
            return Result(
                rc = rcRun, out = outRun, plan = plan.copy(path = path), attempts = attempt,
                state = State.RUN_UNKNOWN, sizeChecked = sizeOk, contentChecked = contentOk,
                leftoverPath = "", mayHaveRun = true,
                contentKind = contentKind, evidencePath = path,
                transportRc = rcRun, payloadRc = RC_UNKNOWN
            )
        }
        // Ни одна попытка не дошла до запуска: это ДОКАЗАННЫЙ «не отправлено/не запущено».
        val leftover = cleanup(path, exec, expectRoot)
        return Result(lastRc, lastOut, plan.copy(path = path), attempts,
            State.UPLOAD_INCOMPLETE, false, false, leftover, false,
            evidencePath = if (leftover.isEmpty()) "" else path,
            transportRc = lastRc, payloadRc = RC_UNKNOWN)
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
        expectRoot: Boolean,
        withTerminal: Boolean = true
    ): String {
        val r = runCatching { exec(cleanCommand(path, withTerminal), 8000, expectRoot) }.getOrNull()
            ?: return path
        if (r.first != 0) return path
        return if (r.second.contains("RM_OK")) "" else path
    }
}
