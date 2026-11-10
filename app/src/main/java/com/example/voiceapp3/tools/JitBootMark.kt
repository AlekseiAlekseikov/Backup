package com.example.voiceapp3.tools

/**
 * Опознание загрузки ГУ для метки снимка JIT (`jit_restaurar_boot`).
 *
 * Зачем это вообще нужно (мандат §1.3): снимок сети — это ОДНА конкретная операция. После
 * `setprop ctl.restart zygote` процесс приложения умирает и стартует заново; если в префах лежит
 * снимок ПРОШЛОЙ загрузки, применить его нельзя (вернём сеть, которую выключал не этот клик, —
 * то есть отменим чужое решение). Поэтому вместе со снимком пишется тег загрузки, и продолжение
 * операции возможно только при совпадении тега.
 *
 * Тег:
 *  1. содержимое `/proc/sys/kernel/random/boot_id` (обычный файл, прав не требует);
 *  2. если недоступен — `epoch<sec>`, где `sec = (System.currentTimeMillis() - elapsedRealtime())/1000`;
 *     для таких тегов сравнение идёт с допуском ±[DEFAULT_TOLERANCE_MS] (часы/NTP).
 *
 * Здесь только арифметика строк — ни `File`, ни `SystemClock` (их читает `AppPerformance`), поэтому
 * логика покрыта JVM-тестом [JitBootMarkTest] без Android.
 *
 * Ничего не удаляет и не «санирует»: решение по остатку прошлой загрузки принимает владелец.
 */
object JitBootMark {

    /** Допуск для `epoch`-тегов (мс). */
    const val DEFAULT_TOLERANCE_MS = 60_000L

    /** Префикс fallback-тега; формат задаётся ТОЛЬКО здесь (`AppPerformance.currentBootTag()`). */
    const val EPOCH_PREFIX = "epoch"

    /** Тег из epoch-времени в МИЛЛИСЕКУНДАХ (в теге — секунды, допуск задан в мс). */
    fun epochTag(epochMs: Long): String = EPOCH_PREFIX + (epochMs / 1000L)

    fun stripEpochPrefix(tag: String): String =
        if (tag.startsWith(EPOCH_PREFIX)) tag.removePrefix(EPOCH_PREFIX) else tag

    /** Метка в формате `<tag>:<uptimeMs>`; uptime — только для лога, в опознании не участвует. */
    fun format(tag: String, uptimeMs: Long): String = "$tag:$uptimeMs"

    /**
     * true — метка из ТЕКУЩЕЙ загрузки.
     *
     *  • пустая метка/тег ⇒ `false` (загрузку не опознали ⇒ это не наша операция);
     *  • тег совпал точно ⇒ `true`;
     *  • оба тега числовые (epoch) ⇒ `|a-b| <= toleranceMs`;
     *  • иначе ⇒ `false` (другая загрузка ⇒ `need=owner_decision`, ничего не пишем).
     */
    fun sameLoad(marker: String, currentTag: String, toleranceMs: Long = DEFAULT_TOLERANCE_MS): Boolean {
        val m = marker.trim()
        val cur = currentTag.trim()
        if (m.isEmpty() || cur.isEmpty()) return false
        val sep = m.lastIndexOf(':')
        if (sep < 0) return false
        val tag = m.substring(0, sep).trim()
        if (tag.isEmpty()) return false
        if (tag == cur) return true
        val a = stripEpochPrefix(tag).toLongOrNull() ?: return false
        val b = stripEpochPrefix(cur).toLongOrNull() ?: return false
        val toleranceSec = (toleranceMs / 1000L).coerceAtLeast(1L)
        return kotlin.math.abs(a - b) <= toleranceSec
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
