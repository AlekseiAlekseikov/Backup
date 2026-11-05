package com.example.voiceapp3.tools

/**
 * iter33d A1/A2/A4 — опознание загрузки для метки `jit_boot_attempt`.
 *
 * **Что было сломано (A1).** Метка хранила только uptime, а признаком «попытка была в этой
 * загрузке» служило `prev in 1..up`. Это неверно: `SystemClock.elapsedRealtime()` сбрасывается
 * в 0 при ребуте, поэтому метка ПРОШЛОЙ загрузки (всегда ≤ 120 с) может оказаться меньше
 * текущего uptime и неотличима от попытки в этой загрузке. Итог — JIT «через раз»:
 *
 * | ситуация | prev | up | старое поведение | правильное |
 * |---|---|---|---|---|
 * | прошлая загрузка, попытка на 40 с; сейчас проход на 55 с | 40 000 | 55 000 | SKIP (JIT не поднимется) | попытка разрешена |
 * | прошлая загрузка, попытка на 40 с; сейчас проход на 35 с | 40 000 | 35 000 | попытка разрешена | попытка разрешена |
 * | повтор в ТОЙ ЖЕ загрузке (ради чего guard и делался) | 40 000 | 45 000 | SKIP | SKIP |
 *
 * **Как теперь.** Метка = `"<bootTag>:<uptimeMs>"`, где `bootTag` — идентификатор загрузки:
 *  1. содержимое `/proc/sys/kernel/random/boot_id` (обычный файл, разрешений не требует);
 *  2. если недоступен — `bootEpoch = System.currentTimeMillis() - elapsedRealtime()`, округлённый
 *     до секунд. Для epoch-тегов сравнение идёт с допуском [DEFAULT_TOLERANCE_MS] (±60 с), чтобы
 *     подвод часов/NTP не ломал опознание.
 *
 * Здесь только чистая арифметика строк — ни `File`, ни `SystemClock`: их читает `AppPerformance`.
 * Поэтому логика покрыта JVM-тестом [JitBootMarkTest] без Android.
 */
object JitBootMark {

    /** Допуск для `bootEpoch`-тегов (мс). */
    const val DEFAULT_TOLERANCE_MS = 60_000L

    /**
     * iter33e B: префикс fallback-тега. Формат задаётся ТОЛЬКО здесь —
     * `AppPerformance.currentBootTag()` обязан возвращать [epochTag], строковых литералов
     * формата вне этого объекта быть не должно.
     *
     * Почему это было дефектом: `currentBootTag()` возвращал `"epoch1700000000"`, а `sameLoad`
     * делал `tag.toLongOrNull()` ⇒ `null` ⇒ `false`. Допуск ±60 с не работал НИКОГДА, и на
     * прошивке без читаемого `boot_id` опознание загрузки не срабатывало вовсе.
     */
    const val EPOCH_PREFIX = "epoch"

    /**
     * Тег из epoch-времени в МИЛЛИСЕКУНДАХ (`System.currentTimeMillis() - elapsedRealtime()`).
     * В самом теге — СЕКУНДЫ (короче и не зависит от точности часов).
     * Единицы важны: допуск [DEFAULT_TOLERANCE_MS] задан в мс, сравнение идёт в секундах —
     * иначе ±60 000 «мс» превращались бы в ±16 часов и «другая загрузка» считалась бы своей.
     */
    fun epochTag(epochMs: Long): String = EPOCH_PREFIX + (epochMs / 1000L)

    fun stripEpochPrefix(tag: String): String =
        if (tag.startsWith(EPOCH_PREFIX)) tag.removePrefix(EPOCH_PREFIX) else tag

    /** Метка в новом формате. `uptimeMs` пишется для диагностики (в опознании не участвует). */
    fun format(tag: String, uptimeMs: Long): String = "$tag:$uptimeMs"

    /**
     * true — метка из ТЕКУЩЕЙ загрузки, то есть попытка в этой загрузке уже была.
     *
     * Правила (определены для любого входа, исключений нет):
     *  • пустая метка / `":"` / мусор без `:` после непустого тега ⇒ `false` (считаем, что попытки не было);
     *  • старый формат (просто число, без `:`) ⇒ `true` — консервативно, без перезапуска zygote;
     *  • тег совпал точно ⇒ `true`;
     *  • оба тега числовые (epoch) ⇒ `|a-b| <= toleranceMs`;
     *  • иначе теги разные ⇒ `false` (другая загрузка).
     */
    fun sameLoad(marker: String, currentTag: String, toleranceMs: Long = DEFAULT_TOLERANCE_MS): Boolean {
        val m = marker.trim()
        val cur = currentTag.trim()
        if (m.isEmpty() || cur.isEmpty()) return false
        val sep = m.lastIndexOf(':')
        if (sep < 0) {
            // Старый формат: только uptime. Консервативно считаем «эта загрузка».
            return m.toLongOrNull() != null
        }
        val tag = m.substring(0, sep).trim()
        if (tag.isEmpty()) return false
        if (tag == cur) return true
        // iter33e B: сравнение идёт по «голому» значению с ОБЕИХ сторон, поэтому допуск
        // работает и для `epoch…`, и для меток вида `epoch…:40000` этой итерации.
        // Для boot_id (UUID-подобная строка с дефисами) toLongOrNull даёт null ⇒ разные загрузки.
        val a = stripEpochPrefix(tag).toLongOrNull() ?: return false
        val b = stripEpochPrefix(cur).toLongOrNull() ?: return false
        // Тег хранит СЕКУНДЫ ⇒ допуск переводим в секунды (значение и смысл ±60 с сохранены).
        val toleranceSec = (toleranceMs / 1000L).coerceAtLeast(1L)
        return kotlin.math.abs(a - b) <= toleranceSec
    }

    /** Старый формат (число без `:`) — его надо переписать в новый (A2). */
    fun isLegacy(marker: String): Boolean {
        val m = marker.trim()
        return m.isNotEmpty() && m.lastIndexOf(':') < 0 && m.toLongOrNull() != null
    }

    /** iter33e A6: что делать с файлом-намерением `jit_pending_verify`. */
    enum class PendingDecision { NONE, STALE, VERIFY }

    /**
     * Классификация намерения сверки после перезапуска zygote.
     *  • [PendingDecision.NONE]   — файла нет (или пуст): делать нечего;
     *  • [PendingDecision.STALE]  — намерение от ДРУГОЙ загрузки: удалить, `reason=pending_stale`;
     *  • [PendingDecision.VERIFY] — намерение этой загрузки: прочитать `getprop` и напечатать итог.
     *
     * Использует ту же [sameLoad], что и guard маршрута 2 — второй копии логики нет.
     */
    fun pendingDecision(pending: String, currentTag: String): PendingDecision = when {
        pending.isBlank() -> PendingDecision.NONE
        sameLoad(pending, currentTag) -> PendingDecision.VERIFY
        else -> PendingDecision.STALE
    }

    /**
     * iter33f A3/B1 — три исхода сверки после перезапуска zygote.
     *
     * • [VerifyOutcome.OK]      — `runtime == "true"` (JIT поднят);
     * • [VerifyOutcome.FAILED]  — `runtime` прочитан, но не `true` (ответ определённый);
     * • [VerifyOutcome.UNKNOWN] — `runtime` пуст: канал не ответил. Это НЕ провал: намерение
     *   нельзя сжигать, иначе в логе останется ложный `FAILED` там, где нужна определённость.
     *
     * `persist` в решении не участвует — он остаётся в подписи, чтобы обе величины читались
     * из одного места и не приходилось звать `jitProps()` ради лога.
     */
    enum class VerifyOutcome { OK, FAILED, UNKNOWN }

    fun verifyOutcome(runtime: String, persist: String): VerifyOutcome = when {
        runtime == "true" -> VerifyOutcome.OK
        runtime.isEmpty() -> VerifyOutcome.UNKNOWN
        else -> VerifyOutcome.FAILED
    }

    /** Uptime из метки, если он там есть (для лога), иначе -1. */
    fun uptimeOf(marker: String): Long {
        val m = marker.trim()
        if (m.isEmpty()) return -1L
        val sep = m.lastIndexOf(':')
        if (sep < 0) return m.toLongOrNull() ?: -1L
        return m.substring(sep + 1).trim().toLongOrNull() ?: -1L
    }
}
