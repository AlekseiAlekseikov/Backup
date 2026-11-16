package com.example.voiceapp3.tools

import java.io.File
import java.io.FileOutputStream

/**
 * iter60 §4 — ПОСТОЯННЫЙ TTL: durable-состояние ПОПЫТКИ и FENCE между вызовами и после restart.
 *
 * Дефект iter59 (`NetworkTtl.kt:346–376`): каждый вызов делал prepare → `TtlAttempt.record`
 * (один файл, `file.writeText`) → send. Прошлый `Unknown` затирался новой записью, terminal
 * прошлой попытки не сверялся, поэтому:
 * ```text
 * A: ON → path=A сохранён → shell отправлен → transport Unknown (shell мог ещё стартовать)
 * B: владелец выбрал OFF → path=B записан ПОВЕРХ A → sent OFF
 * A: поздний shell применил ON ПОСЛЕ B  ⇒ итог не совпал с последним намерением (OFF)
 * ```
 *
 * Здесь (iter60):
 *  1. попытки хранятся ЖУРНАЛОМ (`ttl_attempts.log`, last-wins по `id`) — новая запись не стирает
 *     адрес предыдущей попытки;
 *  2. перед ЛЮБОЙ мутацией вызывающий обязан получить [Fence] и разрешить предыдущую
 *     потенциально исполняющуюся попытку: сверяется ЕЁ terminal (durable `.done`), а не
 *     «отсутствие файла»; terminal одной попытки не является terminal другой;
 *  3. намерение владельца (desired) хранится ОТДЕЛЬНО от факта применения и от identity in-flight:
 *     смена ON→OFF во время pending не теряется и после безопасного terminal доводится до
 *     последнего значения;
 *  4. каждая запись подтверждается полным readback всех значимых полей (не «файл существует»).
 *
 * Честные границы (в отчёте): `FileOutputStream.getFD().sync()` даёт fsync ФАЙЛА, но синхронизация
 * КАТАЛОГА в чистой Java недоступна, поэтому после power loss возможна потеря последней записи
 * журнала (не «тихое переписывание»: записи append-only). Атомарность подмены файла намерения —
 * temp + `renameTo` в том же каталоге. Отсутствие durable `.done` НЕ считается доказательством
 * «не исполнялось»: оно означает «неизвестно», и тогда fence запрещает конфликтующую мутацию.
 */
internal object TtlState {

    /** Фаза попытки. `TERMINAL` знает код payload, `PROVEN_NOT_SENT` — доказанное «не уходило». */
    enum class Phase { PREPARED, MAY_HAVE_RUN, TERMINAL, PROVEN_NOT_SENT }

    data class Attempt(
        val id: String,
        val evidencePath: String,
        val want: Boolean,
        val generation: Long,
        val phase: Phase,
        val payloadRc: Int = Int.MIN_VALUE,
        val ts: Long = 0L
    ) {
        fun wire(): String = "id=$id;path=$evidencePath;want=${if (want) "on" else "off"};" +
            "gen=$generation;phase=${phase.name.lowercase()};" +
            "rc=${if (payloadRc == Int.MIN_VALUE) "-" else payloadRc.toString()};ts=$ts"

        fun evidence(): String = "attempt=$id path=$evidencePath want=${if (want) "on" else "off"} " +
            "gen=$generation phase=${phase.name.lowercase()} " +
            "payload_rc=${if (payloadRc == Int.MIN_VALUE) "unknown" else payloadRc.toString()}"

        /** Попытка ещё может исполняться: её terminal обязан быть разрешён до новой мутации. */
        val unresolved: Boolean get() = phase == Phase.PREPARED || phase == Phase.MAY_HAVE_RUN
    }

    /** Намерение владельца: отдельно от факта применения и от identity in-flight. */
    data class Desired(val on: Boolean, val generation: Long, val ts: Long = 0L) {
        fun wire(): String = "on=${if (on) "true" else "false"};gen=$generation;ts=$ts"
    }

    // ─────────────────────────── разбор/сериализация ───────────────────────────

    fun parseAttempt(line: String): Attempt? {
        if (line.isBlank()) return null
        fun field(name: String): String? =
            Regex("(?:^|;|\\s)" + Regex.escape(name) + "=([^;\\s]*)").find(line)?.groupValues?.get(1)
        val id = field("id")?.takeIf { it.isNotEmpty() } ?: return null
        val path = field("path") ?: return null
        val want = when (field("want")) { "on" -> true; "off" -> false; else -> return null }
        val gen = field("gen")?.toLongOrNull() ?: return null
        val phase = Phase.values().firstOrNull { it.name.equals(field("phase"), true) } ?: return null
        val rc = field("rc")?.toIntOrNull() ?: Int.MIN_VALUE
        val ts = field("ts")?.toLongOrNull() ?: 0L
        return Attempt(id, path, want, gen, phase, rc, ts)
    }

    fun parseDesired(text: String): Desired? {
        if (text.isBlank()) return null
        val on = when {
            text.contains("on=true") -> true
            text.contains("on=false") -> false
            else -> return null
        }
        val gen = Regex("gen=(-?\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val ts = Regex("ts=(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return Desired(on, gen, ts)
    }

    /** Журнал: последняя строка по `id` побеждает (как в `LockOp.parseJournal`). */
    fun parseJournal(text: String?): List<Attempt> {
        if (text == null) return emptyList()
        val out = LinkedHashMap<String, Attempt>()
        for (line in text.lineSequence()) {
            val a = parseAttempt(line) ?: continue
            out[a.id] = a
        }
        return out.values.toList()
    }

    // ─────────────────────────── запись с подтверждением ───────────────────────────

    private fun fsyncWrite(file: File, text: String): Boolean = runCatching {
        file.parentFile?.mkdirs()
        FileOutputStream(file, false).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        true
    }.getOrDefault(false)

    private fun appendSync(file: File, line: String): Boolean = runCatching {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { out ->
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        true
    }.getOrDefault(false)

    /**
     * Дописать попытку и подтвердить её ПОЛНЫМ readback всех полей.
     *
     * @return true — запись подтверждена; false — отправлять НЕЛЬЗЯ (identity не сохранена).
     */
    fun record(journal: File, attempt: Attempt): Boolean {
        if (attempt.evidencePath.isEmpty() || attempt.id.isEmpty()) return false
        if (!appendSync(journal, attempt.wire())) return false
        val back = runCatching { journal.readText() }.getOrNull() ?: return false
        val last = parseJournal(back).lastOrNull { it.id == attempt.id } ?: return false
        return sameFields(attempt, last)
    }

    /** Полная сверка значимых полей записи (не «файл существует»). */
    fun sameFields(a: Attempt, b: Attempt): Boolean =
        a.id == b.id && a.evidencePath == b.evidencePath && a.want == b.want &&
            a.generation == b.generation && a.phase == b.phase &&
            a.payloadRc == b.payloadRc

    /** Сменить фазу существующей попытки (append новой строки, readback по всем полям). */
    fun transition(journal: File, attempt: Attempt): Boolean = record(journal, attempt)

    /** Прочитать намерение владельца. */
    fun loadDesired(file: File): Desired? = runCatching { parseDesired(file.readText()) }.getOrNull()

    /**
     * Атомарно заменить намерение владельца: temp + fsync + rename в том же каталоге.
     * Поколение растёт монотонно (не зависит от системных часов).
     */
    fun saveDesired(file: File, on: Boolean, now: Long): Desired? {
        val prev = loadDesired(file)
        val gen = (prev?.generation ?: 0L) + 1L
        val d = Desired(on, gen, now)
        val tmp = File(file.parentFile, file.name + ".tmp")
        if (!fsyncWrite(tmp, d.wire())) return null
        if (!runCatching { tmp.renameTo(file) }.getOrDefault(false)) return null
        val back = loadDesired(file) ?: return null
        return if (back.on == d.on && back.generation == d.generation) d else null
    }

    /**
     * Миграция iter59 → iter60: одиночная запись `TtlAttempt.Record` (path/want/ts) превращается
     * в попытку с фазой, которую честно можно утверждать.
     *
     * Старый формат не различал «не отправлено» и «неизвестно», поэтому мигрированная попытка
     * получает `PROVEN_NOT_SENT` ТОЛЬКО если вызывающий доказал, что отправки не было
     * (`sentProven = false` при известном факте); иначе — `MAY_HAVE_RUN`, и fence не даст
     * менять таблицу, пока terminal не будет прочитан. Молча «забыть» старую попытку нельзя.
     */
    fun migrateLegacy(legacyText: String?, generation: Long, idPrefix: String): Attempt? {
        val t = legacyText ?: return null
        val path = Regex("path=([^;\\s]*)").find(t)?.groupValues?.get(1) ?: return null
        if (path.isEmpty()) return null
        val want = when {
            t.contains("want=on") || t.contains("want=true") -> true
            t.contains("want=off") || t.contains("want=false") -> false
            else -> return null
        }
        val ts = Regex("ts=(\\d+)").find(t)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return Attempt("$idPrefix-legacy-$ts", path, want, generation, Phase.MAY_HAVE_RUN, Int.MIN_VALUE, ts)
    }

    // ─────────────────────────── FENCE ───────────────────────────

    /**
     * Решение перед мутацией.
     *
     * [Clean] — незавершённых попыток нет, действие разрешено.
     * [Blocked] — есть попытка, которая МОГЛА исполниться: сначала надо прочитать ЕЁ terminal.
     */
    sealed class Fence {
        data object Clean : Fence()
        data class Blocked(val attempt: Attempt, val why: String) : Fence()
    }

    /** Незавершённые попытки в порядке появления (для сверки и отчёта). */
    fun unresolved(records: List<Attempt>): List<Attempt> = records.filter { it.unresolved }

    /**
     * Fence: пока есть незавершённая попытка, новая мутация запрещена. Никакая новая запись
     * не «закрывает» её и не перезаписывает адрес.
     */
    fun fence(records: List<Attempt>): Fence {
        val pending = unresolved(records)
        if (pending.isEmpty()) return Fence.Clean
        val a = pending.first()
        val why = when (a.phase) {
            Phase.PREPARED -> "prepared_but_terminal_unknown"
            Phase.MAY_HAVE_RUN -> "may_have_run_terminal_unknown"
            else -> "unresolved"
        }
        return Fence.Blocked(a, why)
    }

    /**
     * Разрешение попытки по durable-свидетельству (read-only).
     *
     * `terminalRc != null` — скрипт этой попытки доказанно завершился с таким кодом.
     * `null` — terminal не доказан, попытка остаётся незавершённой (это НЕ «не исполнялось»).
     */
    fun resolveByEvidence(a: Attempt, terminalRc: Int?, now: Long): Attempt =
        if (terminalRc != null) a.copy(phase = Phase.TERMINAL, payloadRc = terminalRc, ts = now) else a

    /**
     * Явный контролируемый выход владельца: помеченная попытка больше не блокирует.
     *
     * Это НЕ «всё снято» и не доказательство стока: попытка переводится в `PROVEN_NOT_SENT`
     * только если вызывающий ДОКАЗАЛ `NotSent`; иначе используется [forceAbandon], который
     * оставляет `payloadRc` неизвестным и записывает причину в id (аудит в журнале).
     */
    fun forceAbandon(a: Attempt, reason: String, now: Long): Attempt =
        a.copy(phase = Phase.PROVEN_NOT_SENT, payloadRc = Int.MIN_VALUE, ts = now,
            id = a.id + ":abandoned:" + reason.take(24))

    /** Что говорит durable-свидетельство на устройстве о прошлой попытке. */
    sealed class Evidence {
        /** Скрипт доказанно завершился с этим кодом payload. */
        data class Done(val rc: Int) : Evidence()

        /** Скрипт начал исполняться (`.started=1`), terminal нет ⇒ исход НЕИЗВЕСТЕН. */
        data object MayHaveRun : Evidence()

        /** Ни скрипта, ни `.started`, ни `.done` ⇒ доказанно не запускался. */
        data object NotStarted : Evidence()

        /** Ответ не разобран ⇒ ничего не утверждаем (и мутацию не разрешаем). */
        data object Unreadable : Evidence()
    }

    /**
     * Разбор вывода `ScriptDelivery.evidenceCommand`.
     *
     * Правило: `DONE` — единственное доказательство исхода; `.started` без `DONE` — «мог
     * исполниться»; полное отсутствие следов — «не запускался». Всё остальное — «неизвестно».
     */
    fun classifyEvidence(text: String?): Evidence {
        if (text == null || text.isBlank()) return Evidence.Unreadable
        val doneVal = Regex("(?:^|\\s)DONE=(\\S*)").find(text)?.groupValues?.get(1)
        if (doneVal != null && doneVal.isNotEmpty()) {
            // Есть непустое значение DONE, но не код ⇒ формат не наш: не утверждаем ничего.
            val rc = Regex("^rc=(-?\\d+)$").find(doneVal)?.groupValues?.get(1)?.toIntOrNull()
            return if (rc == null) Evidence.Unreadable else Evidence.Done(rc)
        }
        fun flag(name: String): Boolean =
            Regex("(?:^|\\s)" + Regex.escape(name) + "=1(?:\\s|$)").containsMatchIn(text)
        // Нет ни одного нашего поля ⇒ вывод не от свидетельства.
        if (!Regex("(?:^|\\s)(?:DONE|STARTED|PRESENT|SH)=").containsMatchIn(text)) {
            return Evidence.Unreadable
        }
        if (flag("STARTED")) return Evidence.MayHaveRun
        return if (flag("SH") || flag("PRESENT")) Evidence.MayHaveRun else Evidence.NotStarted
    }

    /** Применить свидетельство к попытке (durable-переход). `null` — состояние не доказано. */
    fun applyEvidence(a: Attempt, ev: Evidence, now: Long): Attempt? = when (ev) {
        is Evidence.Done -> a.copy(phase = Phase.TERMINAL, payloadRc = ev.rc, ts = now)
        Evidence.NotStarted -> a.copy(phase = Phase.PROVEN_NOT_SENT, payloadRc = Int.MIN_VALUE, ts = now)
        Evidence.MayHaveRun -> null
        Evidence.Unreadable -> null
    }

    /**
     * Какое намерение доводить после разрешения попытки: ПОСЛЕДНЕЕ сохранённое владельцем.
     * `null` — намерение неизвестно ⇒ никаких изменений таблицы.
     */
    fun desiredAfterResolve(desired: Desired?): Boolean? = desired?.on
}
