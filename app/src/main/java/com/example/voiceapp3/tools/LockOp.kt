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
}
