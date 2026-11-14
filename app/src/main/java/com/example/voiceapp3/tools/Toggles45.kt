package com.example.voiceapp3.tools

/**
 * iter45 §4/§5/§6/§7/§10 — чистые правила, на которых держатся исправления.
 *
 * Здесь нет ни Android-API, ни shell: всё это покрыто JVM-тестами (`Toggles45Test`), а
 * production-код ([NetworkTtl], [LockSound], [JitOp], ToolsPanel, [AppFileLogger]) вызывает
 * ИМЕННО эти функции. Так «модель» и боевой путь не расходятся.
 */
object Toggles45 {

    /**
     * §5/§6: что записать как ФАКТ применения, не трогая намерение владельца.
     *
     * `pending` — намерение есть, применения нет (нет uplink/root/канала, файл недоступен).
     * Это НЕ выключение функции и НЕ повод менять сохранённое желание.
     */
    fun appliedOutcome(want: Boolean, applied: Boolean): String =
        if (applied) (if (want) "applied" else "off") else "pending"

    /**
     * §4.2 iter46: какое из двух хранилищ (CPS/DPS) считать истиной для boolean.
     *
     * @param cpsValue значение в credential-protected storage (`null` — ключа нет).
     * @param cpsRev   ревизия записи там же (`-1` — ревизии нет, старая установка).
     * @param dpsValue значение в device-protected storage.
     * @param dpsRev   ревизия там же.
     * @param default  значение по умолчанию, если известно только «ключа нет».
     *
     * Правила (доказаны тестами):
     *  1. Ревизий нет нигде (старая установка) → прежняя семантика `cps || dps`: сохранённые
     *     настройки владельца при обновлении не теряются.
     *  2. Ревизии есть → побеждает копия с БОЛЬШЕЙ ревизией (последняя запись). Это и есть
     *     исправление «старое true перебивает новый OFF».
     *  3. Ревизии равны → значения должны совпадать (одна запись в обе копии); если копия
     *     без значения (ключ удалён) — берём значение из другой; если обе без значения —
     *     `default`.
     *  4. Нечитаемая копия (`null` значения при наличии ревизии) не считается «OFF».
     */
    fun pickBoolean(
        cpsValue: Boolean?,
        cpsRev: Long,
        dpsValue: Boolean?,
        dpsRev: Long,
        default: Boolean
    ): Boolean {
        val hasRev = cpsRev >= 0L || dpsRev >= 0L
        if (!hasRev) {
            // Миграция: старое поведение (OR) — не теряем включённое владельцем.
            return when {
                cpsValue == true || dpsValue == true -> true
                cpsValue == false || dpsValue == false -> false
                else -> default
            }
        }
        // Побеждает более новая запись.
        val useCps = cpsRev > dpsRev || (cpsRev == dpsRev && cpsValue != null)
        val winner = if (useCps) cpsValue else dpsValue
        val other = if (useCps) dpsValue else cpsValue
        return winner ?: other ?: default
    }

    /**
     * §4.2 iter46: то же для строк (путь к выбранному WAV и подобное).
     * `null` — значения нет ни в одной копии.
     */
    fun pickString(cpsValue: String?, cpsRev: Long, dpsValue: String?, dpsRev: Long): String? {
        val hasRev = cpsRev >= 0L || dpsRev >= 0L
        if (!hasRev) return cpsValue ?: dpsValue
        val useCps = cpsRev > dpsRev || (cpsRev == dpsRev && cpsValue != null)
        val winner = if (useCps) cpsValue else dpsValue
        val other = if (useCps) dpsValue else cpsValue
        return winner ?: other
    }

    /**
     * §6.1 iter47 — результат согласования копий CPS/DPS для одного ключа.
     *
     * Отличает ЧЕТЫРЕ разных состояния, которые раньше сливались в «bool»:
     *  • [Value]    — подтверждённое значение (ревизия известна);
     *  • [Deleted]  — последняя подтверждённая запись была УДАЛЕНИЕМ (tombstone): старое значение
     *                 из второй копии воскресать не должно;
     *  • [Conflict] — равные ревизии с РАЗНЫМИ значениями (два писателя одновременно): порядок
     *                 неизвестен, выдумывать его нельзя;
     *  • [Unknown]  — значения нет нигде / хранилище не прочитано.
     */
    sealed class Resolved<out T> {
        data class Value<T>(val v: T) : Resolved<T>()
        object Deleted : Resolved<Nothing>()
        object Conflict : Resolved<Nothing>()
        object Unknown : Resolved<Nothing>()
    }

    /**
     * §6.1 iter47: согласование boolean с учётом tombstone и конфликта равных ревизий.
     *
     * @param cpsDeleted признак «последняя запись — удаление» (tombstone) в CPS.
     * @param legacyOr  прежняя семантика `cps || dps` для установок БЕЗ ревизий (миграция).
     */
    fun resolveBoolean(
        cpsValue: Boolean?,
        cpsRev: Long,
        cpsDeleted: Boolean,
        dpsValue: Boolean?,
        dpsRev: Long,
        dpsDeleted: Boolean,
        legacyOr: Boolean = true
    ): Resolved<Boolean> {
        val hasRev = cpsRev >= 0L || dpsRev >= 0L
        if (!hasRev) {
            // Миграция: ревизий нет. Если в копии есть значение — старое поведение.
            if (cpsValue == null && dpsValue == null) return Resolved.Unknown
            return if (legacyOr) Resolved.Value(cpsValue == true || dpsValue == true)
            else Resolved.Value(cpsValue ?: dpsValue ?: false)
        }
        if (cpsRev == dpsRev) {
            // Одна запись обычно пишет обе копии одинаково. Расхождение при равной ревизии —
            // конфликт двух писателей, а НЕ повод выбрать «более удобное» значение.
            val cv = if (cpsDeleted) null else cpsValue
            val dv = if (dpsDeleted) null else dpsValue
            return when {
                cpsDeleted && dpsDeleted -> Resolved.Deleted
                cv != null && dv != null && cv != dv -> Resolved.Conflict
                cv != null -> Resolved.Value(cv)
                dv != null -> Resolved.Value(dv)
                cpsDeleted || dpsDeleted -> Resolved.Deleted
                else -> Resolved.Unknown
            }
        }
        val cpsWins = cpsRev > dpsRev
        val winnerRevDeleted = if (cpsWins) cpsDeleted else dpsDeleted
        val winnerValue = if (cpsWins) cpsValue else dpsValue
        if (winnerRevDeleted) return Resolved.Deleted
        winnerValue?.let { return Resolved.Value(it) }
        // Победившая копия новее, но значения в ней нет и tombstone не выставлен — это
        // частичная запись/сбой чтения, а НЕ «вторая копия права».
        return Resolved.Unknown
    }

    /** §6.1 iter47: то же для строк (путь к WAV и подобное). */
    fun resolveString(
        cpsValue: String?,
        cpsRev: Long,
        cpsDeleted: Boolean,
        dpsValue: String?,
        dpsRev: Long,
        dpsDeleted: Boolean
    ): Resolved<String> {
        val hasRev = cpsRev >= 0L || dpsRev >= 0L
        if (!hasRev) {
            val v = cpsValue ?: dpsValue
            return if (v == null) Resolved.Unknown else Resolved.Value(v)
        }
        if (cpsRev == dpsRev) {
            val cv = if (cpsDeleted) null else cpsValue
            val dv = if (dpsDeleted) null else dpsValue
            return when {
                cpsDeleted && dpsDeleted -> Resolved.Deleted
                cv != null && dv != null && cv != dv -> Resolved.Conflict
                cv != null -> Resolved.Value(cv)
                dv != null -> Resolved.Value(dv)
                cpsDeleted || dpsDeleted -> Resolved.Deleted
                else -> Resolved.Unknown
            }
        }
        val cpsWins = cpsRev > dpsRev
        if (cpsWins && cpsDeleted) return Resolved.Deleted
        if (!cpsWins && dpsDeleted) return Resolved.Deleted
        val v = if (cpsWins) cpsValue else dpsValue
        return if (v == null) Resolved.Unknown else Resolved.Value(v)
    }

    /**
     * §7.2: истекло ли окно жизни незавершённой операции.
     *
     * @param markTs время отметки операции (wall clock, мс); `<= 0` — время неизвестно.
     * @param now текущее время.
     * @param limitMs граница (см. `JitOp.STALE_MARK_MS`).
     * Возвращает `true` только при ДОКАЗАННО известной и достаточно старой отметке: неизвестное
     * время окно не открывает.
     */
    fun isStale(markTs: Long?, now: Long, limitMs: Long): Boolean {
        if (markTs == null || markTs <= 0L) return false
        if (limitMs <= 0L) return false
        val age = now - markTs
        return age >= limitMs
    }

    /**
     * §4: разбор «булева» значения из снимка настроек.
     *
     * Раньше любая непустая строка, кроме "0", считалась ВКЛ — то есть `"false"` включало
     * тумблер. `null` здесь означает «значение не распознано» и НЕ равно OFF.
     */
    fun boolOrNull(raw: String?): Boolean? {
        val s = raw?.trim()?.lowercase() ?: return null
        return when (s) {
            "true", "1", "on", "yes" -> true
            "false", "0", "off", "no", "" -> false
            else -> null
        }
    }

    /**
     * §4: принимать ли пришедший снимок состояния.
     *
     * Поздний ответ, посчитанный раньше уже применённого, откатывал бы тумблеры назад
     * (гонка асинхронных ответов). Снимок без номера считается допустимым — для совместимости.
     */
    fun acceptsSeq(seq: Long?, lastApplied: Long): Boolean =
        seq == null || seq > lastApplied

    /**
     * §10: длина хвоста из `0x00` в конце буфера — признак «дырки» в файле лога.
     * Используется `AppFileLogger`: повреждение сообщается строкой, а не замалчивается.
     */
    fun trailingNulBytes(bytes: ByteArray): Int {
        var i = bytes.size - 1
        var n = 0
        while (i >= 0 && bytes[i] == 0.toByte()) {
            n++
            i--
        }
        return n
    }
}
