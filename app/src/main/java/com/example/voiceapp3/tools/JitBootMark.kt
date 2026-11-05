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
        val a = tag.toLongOrNull() ?: return false
        val b = cur.toLongOrNull() ?: return false
        return kotlin.math.abs(a - b) <= toleranceMs
    }

    /** Старый формат (число без `:`) — его надо переписать в новый (A2). */
    fun isLegacy(marker: String): Boolean {
        val m = marker.trim()
        return m.isNotEmpty() && m.lastIndexOf(':') < 0 && m.toLongOrNull() != null
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
