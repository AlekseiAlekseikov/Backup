package com.example.voiceapp3.tools

import java.io.File

/**
 * iter48 §Д — когда СТАРТОВАЛ текущий процесс `zygote` (wall clock, мс).
 *
 * В iter47 отчёт утверждал, что это читается (`JitNetCore.JitNetState.zygoteStartMs()`), но в
 * production переопределения НЕ было: интерфейсный метод по умолчанию возвращал `null`, и
 * «проверяемый признак» на машине не работал ни разу — работал только таймер. Здесь чтение
 * сделано реальным:
 *
 *  • pid — поиск процесса с `cmdline` = `zygote`/`zygote64` (`/proc/<pid>/cmdline`);
 *  • время старта — поле 22 `starttime` из `/proc/<pid>/stat` (тики с момента загрузки);
 *  • `btime` (wall clock загрузки) считается как `now − uptime`, из `/proc/uptime`.
 *
 * Что это доказывает: «процесс zygote, работающий СЕЙЧАС, стартовал после отметки операции».
 * Чего это НЕ доказывает (и не должно заявляться):
 *  • что команду `setprop ctl.restart zygote` выполнили МЫ (zygote мог перезапуститься сам,
 *    упасть, быть перезапущен системой, а pid мог быть переиспользован);
 *  • что JIT-компиляция произошла;
 *  • что команда не «перенеслась через загрузку» — сравнение идёт только внутри текущей загрузки,
 *    поэтому признаки разных загрузок дополнительно сверяются вызывающим (boot ID/uptime);
 *  • надёжность `btime` при коррекции часов назад: тогда порядок считается недоказанным.
 */
internal object ZygoteStart {

    /** USER_HZ на Android/Linux для этой платформы — 100 (starttime в 10-мс тиках). */
    const val DEFAULT_HZ = 100L

    /** Имена процессов, которые считаются zygote. */
    private val ZYGOTE_NAMES = setOf("zygote", "zygote64")

    fun isZygoteCmdline(line: String): Boolean {
        val first = line.substringBefore('\u0000').trim()
        if (first.isEmpty()) return false
        val base = first.substringAfterLast('/')
        return base in ZYGOTE_NAMES
    }

    /** Поле 22 (`starttime`) из `/proc/<pid>/stat`. Имя процесса в скобках может содержать пробелы. */
    fun parseStarttimeTicks(stat: String?): Long? {
        if (stat == null) return null
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 > stat.length) return null
        val rest = stat.substring(close + 1).trim().split(' ').filter { it.isNotEmpty() }
        // После ") " идёт поле 3 (state); starttime — 22-е поле, т.е. индекс 19 в rest.
        if (rest.size < 20) return null
        return rest[19].toLongOrNull()
    }

    /** Первое число из `/proc/uptime`. */
    fun parseUptimeSec(text: String?): Double? =
        text?.trim()?.substringBefore(' ')?.toDoubleOrNull()

    /**
     * Время старта процесса в wall clock.
     *
     * @param ticks `starttime` из stat (тики с загрузки);
     * @param uptimeSec секунды с загрузки (с дробной частью);
     * @param nowMs текущее wall clock время.
     * @return `null`, если хотя бы один вход не прочитан — догадки не строим.
     */
    fun startMsFrom(ticks: Long?, uptimeSec: Double?, nowMs: Long, hz: Long = DEFAULT_HZ): Long? {
        if (ticks == null || ticks < 0L) return null
        val up = uptimeSec ?: return null
        if (up < 0.0) return null
        if (hz <= 0L) return null
        val btime = nowMs - (up * 1000.0).toLong()
        val started = btime + (ticks * 1000L) / hz
        // Старт «в будущем» относительно текущего времени — часы/вход не согласованы.
        if (started > nowMs + 60_000L) return null
        return started
    }

    /** Найти pid zygote по каталогу `/proc` (только чтение). `null` — не найден/нет доступа. */
    fun findZygotePid(procRoot: File = File("/proc")): Int? {
        val dirs = runCatching {
            procRoot.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } }
        }.getOrNull() ?: return null
        for (d in dirs.sortedBy { it.name.toIntOrNull() ?: Int.MAX_VALUE }) {
            val cmd = runCatching { File(d, "cmdline").readText() }.getOrNull() ?: continue
            if (isZygoteCmdline(cmd)) return d.name.toIntOrNull()
        }
        return null
    }

    /** Полное чтение: `null`, если хоть один источник не прочитан. */
    fun readCurrent(procRoot: File = File("/proc"), nowMs: Long = System.currentTimeMillis()): Long? {
        val pid = findZygotePid(procRoot) ?: return null
        val stat = runCatching { File(procRoot, "$pid/stat").readText() }.getOrNull()
        val up = runCatching { File(procRoot, "uptime").readText() }.getOrNull()
        return startMsFrom(parseStarttimeTicks(stat), parseUptimeSec(up), nowMs)
    }
}
