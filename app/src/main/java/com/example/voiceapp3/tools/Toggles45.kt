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
