package com.example.voiceapp3.tools

/**
 * iter51 §3.2/§3.3 — доказательство применения звука замка и журнал операции ДО side effect.
 *
 * Два дефекта, которые закрывает этот модуль:
 *
 * 1. **`applied` при «одна цель verified, вторая unknown».** На HEAD `LockSound` требовал лишь
 *    «есть хотя бы один `MOUNTED_OK`» и сравнивал число ФАКТОВ (`MOUNT_FACT` приходит и со
 *    состоянием `unverified`/`unknown`) с числом целей. Две цели, одна из которых не проверена,
 *    давали `complete=true` — то есть интерфейс и реестр заявляли применение, которого никто не
 *    доказал. Здесь доказательство строится ПО КАЖДОЙ цели: ровно один факт на ожидаемую цель,
 *    состояние `verified`, и ни одной лишней/дублированной цели.
 *
 * 2. **Отсутствие записи об операции до необратимого действия.** Реестр пополнялся уже ПОСЛЕ
 *    `mount`, поэтому потеря ответа/смерть процесса оставляли реально созданные слои без учёта.
 *    Здесь операция (id, поколение, желание, stage, точный набор целей) сериализуется и
 *    записывается ДО отправки bind; если запись не подтверждена чтением, bind не отправляется.
 *
 * Чего модуль НЕ обещает: `writeText` без `fsync` не переживает отключение питания — это
 * записано и в отчёте; журнал не отменяет уже отправленный shell и не превращает
 * «неизвестно» в «применено».
 */
internal object LockOp {

    /** Состояния операции. `UNDEFINED` — ответ потерян/процесс умер: нужна сверка, не успех. */
    enum class State { PLANNED, DONE, UNDEFINED, FAILED }

    /**
     * Запись операции.
     *
     * @param targets точный набор целей, прочитанный ДО монтирования (не догадка по глобу).
     */
    data class BindOp(
        val id: String,
        val gen: Long,
        val desired: Boolean,
        val stage: String,
        val targets: List<String>,
        val state: State,
        val reason: String = ""
    ) {
        fun encode(): String = listOf(
            "id=$id",
            "gen=$gen",
            "desired=${if (desired) 1 else 0}",
            "state=${state.name.lowercase()}",
            "stage=$stage",
            "targets=${targets.joinToString(",")}",
            "reason=$reason"
        ).joinToString(";")

        fun wire(): String =
            "id=$id gen=$gen desired=$desired state=${state.name.lowercase()} " +
                "targets=${targets.size} stage_len=${stage.length}" +
                (if (reason.isEmpty()) "" else " reason=$reason")
    }

    /** `null` — запись отсутствует или повреждена (не «операции не было»). */
    fun decode(raw: String?): BindOp? {
        if (raw.isNullOrBlank()) return null
        val map = raw.split(';').mapNotNull { part ->
            val k = part.substringBefore('=', "")
            if (k.isEmpty()) null else k to part.substringAfter('=', "")
        }.toMap()
        val id = map["id"]?.takeIf { it.isNotEmpty() } ?: return null
        val gen = map["gen"]?.toLongOrNull() ?: return null
        val desired = map["desired"] ?: return null
        val state = map["state"]?.let { s ->
            State.values().firstOrNull { it.name.equals(s, true) }
        } ?: return null
        val targets = map["targets"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return BindOp(
            id = id, gen = gen, desired = desired == "1", stage = map["stage"].orEmpty(),
            targets = targets, state = state, reason = map["reason"].orEmpty()
        )
    }

    /**
     * iter52 §5.1 — ЖУРНАЛ КАК СПИСОК ОПЕРАЦИЙ, а не один перезаписываемый файл.
     *
     * На HEAD iter51 запись была одна: следующий запуск перетирал незавершённую операцию, и
     * восстановить по ней ничего было нельзя. Здесь каждая строка — отдельное состояние
     * операции; актуальное состояние операции = ПОСЛЕДНЯЯ строка с её `id`.
     *
     * Формат строки: `<время>|<encode()>`; повреждённая строка пропускается, но считается
     * (`broken`), чтобы «не читается» не превращалось в «операций не было».
     */
    data class Journal(
        val ops: Map<String, BindOp>,
        val broken: Int,
        val lines: Int
    ) {
        /** Операции, по которым исход НЕ разрешён: нужна сверка, а не «всё хорошо». */
        fun unresolved(): List<BindOp> = ops.values.filter {
            it.state == State.PLANNED || it.state == State.UNDEFINED
        }

        fun last(): BindOp? = ops.values.maxByOrNull { it.gen }

        fun wire(): String =
            "ops=${ops.size} unresolved=${unresolved().size} broken=$broken lines=$lines"
    }

    /** Разбор журнала: последняя строка по каждому `id` побеждает. */
    fun parseJournal(text: String?): Journal {
        if (text.isNullOrBlank()) return Journal(emptyMap(), 0, 0)
        var broken = 0
        var lines = 0
        val out = LinkedHashMap<String, BindOp>()
        for (raw in text.lineSequence()) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            lines++
            val payload = t.substringAfter('|', "")
            val op = decode(payload)
            if (op == null) {
                broken++
                continue
            }
            out[op.id] = op
        }
        return Journal(out, broken, lines)
    }

    /**
     * iter54 §7.2 — РЕЗУЛЬТАТ ЧТЕНИЯ ЖУРНАЛА: три различимых факта вместо «журнала нет».
     *
     * Дефект: `readOpJournal` читал файл и на ЛЮБУЮ ошибку возвращал пустой журнал. «Файла нет»
     * (ENOENT — операций не было) и «прочитать нельзя» (EACCES/ошибка ввода-вывода) выглядели
     * одинаково, поэтому при недоступном журнале незавершённая операция просто исчезала из
     * сверки, а `unresolved().isEmpty()` объявляло «всё в порядке».
     *
     * Теперь:
     *  • [Absent] — файла действительно НЕТ (ENOENT): операций не было;
     *  • [Unknown] — файл есть, но не читается (EACCES/EIO/исключение): НЕЛЬЗЯ считать пустым;
     *  • [Readable] — прочитан; `broken > 0` означает повреждённые строки (это тоже не «пусто»).
     */
    sealed interface JournalRead {
        val journal: Journal get() = Journal(emptyMap(), 0, 0)

        data object Absent : JournalRead
        data class Unknown(val why: String) : JournalRead
        data class Readable(val value: Journal) : JournalRead {
            override val journal: Journal get() = value
        }

        fun wire(): String = when (this) {
            is Absent -> "journal=absent (ENOENT: операций не было)"
            is Unknown -> "journal=unknown why=$why (читать нельзя — «пусто» не заявляется)"
            is Readable -> "journal=readable ${value.wire()}" +
                (if (value.broken > 0) " corrupt_lines=${value.broken}" else "")
        }
    }

    /**
     * Прочитать журнал, различая «нет файла»/«не читается»/«прочитан».
     *
     * @param read выполняет фактическое чтение (в production — файл, в тестах — подставное).
     */
    fun readJournal(file: java.io.File?): JournalRead {
        if (file == null) return JournalRead.Unknown("no_path")
        if (!file.exists()) {
            // Каталога нет — это не «журнал пуст», а «среда не та»: сюда писать и читать нельзя.
            return if (file.parentFile?.exists() == false) JournalRead.Unknown("parent_missing")
            else JournalRead.Absent
        }
        val text = try {
            file.readText()
        } catch (t: Throwable) {
            // Linux отдаёт EACCES как FileNotFoundException("… (Permission denied)"): по одному
            // типу исключения «нет прав» и «нет файла» не различить, поэтому смотрим текст.
            val msg = t.message.orEmpty()
            return JournalRead.Unknown(
                when {
                    t is SecurityException -> "eacces"
                    msg.contains("Permission denied", true) -> "eacces"
                    t is java.io.FileNotFoundException -> "enoent_race"
                    else -> "io:" + t.javaClass.simpleName
                }
            )
        }
        return JournalRead.Readable(parseJournal(text))
    }

    /** Строка журнала для добавления: время + закодированное состояние. */
    fun journalLine(op: BindOp, now: Long): String = "$now|${op.encode()}"

    /**
     * iter52 §5.1(3) — вердикт сверки незавершённой операции по выводу restore-скрипта.
     *
     * Почему отдельная функция: решение «что делать с потерянной операцией» должно быть
     * проверяемым на РЕАЛЬНОМ выводе скрипта, а не только в тесте с подставленным результатом.
     *
     *  • [STOCK_RESTORED] — своих слоёв не осталось (сняты сейчас или их не было);
     *  • [FOREIGN_LEFT]  — сверху чужой слой: чужое НЕ трогаем, запись остаётся;
     *  • [UNPROVEN]      — таблица/namespace не прочитаны, идентичность не доказана.
     */
    enum class Reconcile { STOCK_RESTORED, FOREIGN_LEFT, UNPROVEN }

    /**
     * Разбор вывода сверки ПО КАЖДОЙ ОЖИДАЕМОЙ ЦЕЛИ (iter53 §7.2).
     *
     * iter52 возвращал `STOCK_RESTORED` при наличии ХОТЯ БЫ ОДНОГО `STOCK_OK`/`UMOUNTED_OK`, не
     * сверяя, что ответ содержит итог для КАЖДОЙ ожидаемой цели, и проверял `rc` только после
     * поиска успешных маркеров. Частичный/обрезанный ответ либо один успешный `umount` перед
     * последующим отказом давали DONE. Lazy detach тоже не равен доказанному штатному состоянию.
     *
     * Теперь:
     *  • `rc != 0` проверяется ПЕРВЫМ — при ненулевом коде успех не заявляется вообще;
     *  • каждая ожидаемая цель обязана иметь РОВНО ОДИН маркер;
     *  • `UMOUNT_DETACHED` (lazy) и маркеры по целям ВНЕ ожидаемого набора не дают DONE;
     *  • пустой ожидаемый набор → [Reconcile.UNPROVEN]: доказать нечего.
     *
     * @return подробный результат: что именно доказано, чего не хватает, что лишнее.
     */
    data class Reconciled(
        val verdict: Reconcile,
        val expected: List<String>,
        val proven: List<String>,
        val missing: List<String>,
        val extra: List<String>,
        val duplicates: List<String>,
        val detached: List<String>,
        val note: String
    ) {
        fun wire(): String =
            "verdict=${verdict.name.lowercase()} expected=${expected.size} proven=${proven.size} " +
                "missing=${missing.ifEmpty { listOf("none") }.joinToString(",")} " +
                "extra=${extra.ifEmpty { listOf("none") }.joinToString(",")} " +
                "dup=${duplicates.ifEmpty { listOf("none") }.joinToString(",")} " +
                "detached=${detached.ifEmpty { listOf("none") }.joinToString(",")} note=$note"
    }

    private val TARGET_MARKER = Regex("^(STOCK_OK|UMOUNTED_OK|UMOUNT_DETACHED):(\\S+)\\s*(.*)$")

    fun reconcileDetail(rc: Int, out: String, expected: Collection<String>): Reconciled {
        val exp = expected.filter { it.isNotBlank() }.distinct()
        fun r(v: Reconcile, note: String, proven: List<String> = emptyList(),
              missing: List<String> = exp, extra: List<String> = emptyList(),
              dup: List<String> = emptyList(), det: List<String> = emptyList()) =
            Reconciled(v, exp, proven, missing, extra, dup, det, note)

        // 1) Отказы, при которых чужое/неизвестное НЕ трогаем (это не «успех», rc их не отменяет).
        if (out.contains("FOREIGN_MOUNT:") || out.contains("STOCK_FOREIGN_LEFT:"))
            return r(Reconcile.FOREIGN_LEFT, "foreign_above")
        if (out.contains("STOCK_UNPROVEN:") || out.contains("RESTORE_BLOCKED"))
            return r(Reconcile.UNPROVEN, "unproven_marker")
        // 2) Код возврата — ДО поиска успешных маркеров: при rc≠0 «STOCK_OK» ничего не доказывает
        //    (именно этот порядок был дефектом: успех искали первым и находили).
        if (rc != 0) return r(Reconcile.UNPROVEN, "rc=$rc")
        // 3) По маркерам на цель.
        val stock = LinkedHashMap<String, MutableList<String>>()
        for (line in out.lineSequence()) {
            val m = TARGET_MARKER.matchEntire(line.trim()) ?: continue
            stock.getOrPut(m.groupValues[2]) { mutableListOf() }.add(m.groupValues[1])
        }
        val proven = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val dup = mutableListOf<String>()
        val det = mutableListOf<String>()
        for (t in exp) {
            val marks = stock[t] ?: run { missing += t; null } ?: continue
            // `UMOUNTED_OK` — это шаг снятия, `STOCK_OK` — итоговое состояние цели после снятия.
            // Доказательством служит РОВНО ОДИН итоговый `STOCK_OK`; повтор того же маркера или
            // второй итог по той же цели — уже не доказательство (подмена/склейка вывода).
            val finals = marks.count { it == "STOCK_OK" }
            val umounts = marks.count { it == "UMOUNTED_OK" }
            if (finals > 1 || umounts > 1) dup += t
            if (marks.any { it == "UMOUNT_DETACHED" }) det += t
            if (finals == 1) proven += t
        }
        val extra = stock.keys.filter { it !in exp }
        val ok = exp.isNotEmpty() && missing.isEmpty() && dup.isEmpty() &&
            extra.isEmpty() && det.isEmpty() && proven.size == exp.size
        return if (ok) r(Reconcile.STOCK_RESTORED, "all_targets", proven, emptyList())
        else r(Reconcile.UNPROVEN, "incomplete", proven, missing, extra, dup, det)
    }

    /**
     * Совместимость: тот же вердикт, но БЕЗ набора ожидаемых целей доказать нельзя — поэтому
     * при пустом [expected] результат всегда [Reconcile.UNPROVEN] (раньше одиночный `STOCK_OK`
     * считался доказательством «всё снято»).
     */
    fun reconcileVerdict(rc: Int, out: String, expected: Collection<String> = emptyList()): Reconcile =
        reconcileDetail(rc, out, expected).verdict

    /** Состояние журнала, соответствующее вердикту (для честного статуса операции). */
    fun stateForVerdict(v: Reconcile): State = when (v) {
        Reconcile.STOCK_RESTORED -> State.DONE
        Reconcile.FOREIGN_LEFT, Reconcile.UNPROVEN -> State.UNDEFINED
    }

    /**
     * Доказательство по каждой цели.
     *
     * `complete` = набор целей известен достоверно, на каждую ожидаемую цель есть РОВНО один
     * факт `verified`, и нет фактов на цели, которых не было в ожидаемом наборе.
     */
    data class Evidence(
        val expected: Int,
        val verified: Int,
        val unverified: Int,
        val unknown: Int,
        val missing: List<String>,
        val duplicates: List<String>,
        val extra: List<String>,
        val reason: String
    ) {
        val complete: Boolean get() = reason.isEmpty() && missing.isEmpty() &&
            duplicates.isEmpty() && extra.isEmpty() && verified == expected && expected > 0

        fun wire(): String =
            "expected=$expected verified=$verified unverified=$unverified unknown=$unknown " +
                "missing=${missing.size} duplicates=${duplicates.size} extra=${extra.size}" +
                (if (reason.isEmpty()) "" else " reason=$reason")
    }

    /**
     * @param expected цели, прочитанные с устройства ДО монтирования.
     * @param targetsReadable результат чтения списка целей (`ls`): при `false` доказать нечего.
     */
    fun evidence(
        expected: List<String>,
        facts: List<MountInfo.MountFact>,
        targetsReadable: Boolean
    ): Evidence {
        if (!targetsReadable) {
            return Evidence(0, 0, 0, 0, expected, emptyList(), facts.map { it.target }, "targets_unreadable")
        }
        if (expected.isEmpty()) {
            return Evidence(0, 0, 0, 0, emptyList(), emptyList(), facts.map { it.target }, "no_targets")
        }
        val expectedSet = expected.toSet()
        val ours = facts.filter { it.target in expectedSet }
        val extra = facts.map { it.target }.filterNot { it in expectedSet }.distinct()
        val byTarget = ours.groupBy { it.target }
        val duplicates = byTarget.filterValues { it.size > 1 }.keys.toList()
        val verifiedTargets = byTarget.filterValues { fs ->
            fs.size == 1 && fs[0].state == MountInfo.FactState.VERIFIED
        }.keys
        val unverified = ours.count { it.state == MountInfo.FactState.UNVERIFIED }
        val unknown = ours.count { it.state == MountInfo.FactState.UNKNOWN }
        val missing = expected.filterNot { it in verifiedTargets }
        val reason = when {
            duplicates.isNotEmpty() -> "duplicate_targets"
            extra.isNotEmpty() -> "unexpected_targets"
            missing.isNotEmpty() && unverified > 0 -> "unverified_targets"
            missing.isNotEmpty() && unknown > 0 -> "unknown_targets"
            missing.isNotEmpty() -> "not_mounted"
            else -> ""
        }
        return Evidence(
            expected = expected.size,
            verified = verifiedTargets.size,
            unverified = unverified,
            unknown = unknown,
            missing = missing,
            duplicates = duplicates,
            extra = extra,
            reason = reason
        )
    }

    /** Текст для журнала: одна строка, без содержимого WAV и без путей вне carlock. */
    fun evidenceLine(tag: String, e: Evidence): String = "LOCKSOUND_EVIDENCE $tag ${e.wire()}"

    /**
     * Нужно ли вообще отправлять bind: цель должна быть известна ДО монтирования, а журнал —
     * подтверждён записью. Оба условия проверяются вызывающим, это чистый предикат для теста.
     */
    fun maySendBind(desired: Boolean, expected: List<String>, journalConfirmed: Boolean): Pair<Boolean, String> =
        when {
            !desired -> false to "desired_off"
            !journalConfirmed -> false to "journal_not_confirmed"
            expected.isEmpty() -> false to "no_targets"
            else -> true to ""
        }

    // ─────────────────────────── iter53 §7.1: миграция старой записи ───────────────────────────

    /**
     * Результат разбора СТАРОЙ записи `locksound_op.txt` (одна операция в одном файле).
     *
     * Смысл разделения: «файла нет», «запись разобрана» и «файл есть, но не разобран» — ТРИ
     * разных факта. Последний НЕЛЬЗЯ превращать в «операций не было»: под ним может стоять
     * реально созданный слой.
     */
    sealed interface LegacyRead {
        /** Файла нет (или он пуст по факту отсутствия). */
        data object Absent : LegacyRead
        /** Валидная запись: переносится в журнал как есть (незавершённая — со своим состоянием). */
        data class Parsed(val op: BindOp) : LegacyRead
        /** Файл есть и не пуст, но не разобран: сохраняется как операция «неизвестно». */
        data class Unknown(val raw: String, val why: String) : LegacyRead
    }

    /**
     * Разбор старой записи.
     *
     * Принимаются оба встречавшихся вида: строка журнала (`<время>|id=…`) и «голая» строка
     * `id=…;gen=…`. Всё прочее — [LegacyRead.Unknown]. Запись без целей/`stage` допустима: она
     * останется UNDEFINED и попадёт в сверку как «снять нечего — нужен ручной разбор», а не как
     * доказанное отсутствие слоя.
     */
    fun parseLegacyOp(raw: String?): LegacyRead {
        if (raw == null) return LegacyRead.Absent
        val text = raw.trim()
        if (text.isEmpty()) return LegacyRead.Absent
        val payload = if (text.contains('|')) text.substringAfterLast('|').trim() else text
        val op = decode(payload)
            ?: return LegacyRead.Unknown(text.take(300), "not_our_format")
        // Старое состояние `done`/`failed` сохраняется; всё остальное — требует сверки.
        val state = when (op.state) {
            State.DONE, State.FAILED -> op.state
            else -> State.UNDEFINED
        }
        return if (op.targets.isEmpty() || op.stage.isEmpty()) {
            LegacyRead.Parsed(op.copy(state = State.UNDEFINED, reason = "legacy_no_targets"))
        } else {
            LegacyRead.Parsed(op.copy(state = state, reason = "legacy_migrated"))
        }
    }

    /**
     * Как перенести старую запись в журнал: append И ТОЛЬКО ПОТОМ переименование оригинала.
     *
     * Идемпотентность: журнал хранит последнюю строку по `id`, поэтому повторный перенос той же
     * записи (сбой между append и rename) не создаёт дубля операции. Если append не подтвердился
     * чтением, оригинал НЕ переименовывается и не удаляется.
     *
     * @param appended true — строка реально подтверждена чтением журнала.
     */
    fun migrationStep(read: LegacyRead, appended: Boolean): MigrationAction = when (read) {
        is LegacyRead.Absent -> MigrationAction.Nothing
        is LegacyRead.Parsed, is LegacyRead.Unknown ->
            if (appended) MigrationAction.RenameLegacy else MigrationAction.KeepLegacy
    }

    enum class MigrationAction { Nothing, RenameLegacy, KeepLegacy }
}
