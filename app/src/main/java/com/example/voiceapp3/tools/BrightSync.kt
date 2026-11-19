package com.example.voiceapp3.tools

/**
 * iter67 §B — яркость: не писать, когда писать нечего, и назвать настоящую причину.
 *
 * Дефект (измерено на живой ГУ): `restoreIfEnabled` при **любом** расхождении делал три записи
 * (`settings put` + `sysfs` + `setprop`), а расхождение возникало каждые 2–3 секунды — 1593 записи
 * за 8 ч 20 мин. Основной вклад в `exec/min` давал `sh -c setprop`, который запускался даже когда
 * системное свойство уже равнялось нужному значению. Причина расхождения при этом не называлась:
 * журнал печатал только `settings=true sysfs=false`.
 *
 * Здесь решение разделено на три исхода и на план записи:
 *  • `IN_SYNC`          — писать нечего;
 *  • `SYSFS_UNREADABLE` — узел не читается, доказательства нет → не пишем ничего;
 *  • `SETTINGS_DIFF` / `SYSFS_DIFF` / `BOTH_DIFF` — пишем ТОЛЬКО расходящееся.
 *
 * Модуль чистый (без Android): проверяется JVM-тестом на тех же значениях, что даёт боевой код
 * (узел `/sys/class/leds/lcd-backlight/brightness` = 67, `Settings.System` = 67).
 */

/** Что именно не совпало. */
enum class SyncState { IN_SYNC, SETTINGS_DIFF, SYSFS_DIFF, SYSFS_UNREADABLE, BOTH_DIFF }

/** Куда писать. `setprop` в план не входит: он не доказательство и стоил один exec на тик. */
data class SyncPlan(val writeSettings: Boolean, val writeSysfs: Boolean) {
    val total: Int get() = (if (writeSettings) 1 else 0) + (if (writeSysfs) 1 else 0)
    val empty: Boolean get() = total == 0
}

/** Итог одного тика: состояние, план, фактические вызовы записи и строки журнала. */
data class SyncOutcome(
    val state: SyncState,
    val applied: SyncPlan,
    val calls: List<String>,
    val lines: List<String>
)

/** Запись и журнал — внешние эффекты, вынесены, чтобы решение можно было проверить без Android. */
interface BrightWriters {
    fun writeSettings(value: Int)
    fun writeSysfs(value: Int)
    fun log(line: String)
}

/**
 * Правило §B.1 без побочных эффектов: `null` означает «прочитать не удалось».
 *
 * Порядок проверок важен: `sysfs == null && settings == want` — это [SyncState.SYSFS_UNREADABLE]
 * (доказательство есть только с одной стороны), а `sysfs == null && settings != want` — это
 * [SyncState.BOTH_DIFF] (не знаем про узел, но настройка точно не та; писать в узел нельзя).
 */
fun syncState(settings: Int?, sysfs: Int?, want: Int): SyncState = when {
    sysfs == null -> if (settings == want) SyncState.SYSFS_UNREADABLE else SyncState.BOTH_DIFF
    settings == null -> SyncState.SETTINGS_DIFF
    settings != want && sysfs != want -> SyncState.BOTH_DIFF
    settings != want -> SyncState.SETTINGS_DIFF
    sysfs != want -> SyncState.SYSFS_DIFF
    else -> SyncState.IN_SYNC
}

/** План записи по состоянию: только расходящееся. */
fun syncPlan(state: SyncState): SyncPlan = when (state) {
    SyncState.IN_SYNC, SyncState.SYSFS_UNREADABLE -> SyncPlan(false, false)
    SyncState.SETTINGS_DIFF -> SyncPlan(true, false)
    SyncState.SYSFS_DIFF -> SyncPlan(false, true)
    SyncState.BOTH_DIFF -> SyncPlan(true, true)
}

/**
 * Состояние процесса между тиками: сколько раз подряд `sysfs` не читался.
 *
 * §B.4: после [limit] подряд `SYSFS_UNREADABLE` — одна строка
 * `BRIGHT_SYSFS_UNREADABLE path=… errno=… action=no_writes` и больше НЕ пытаться писать в узел до
 * перезапуска процесса (иначе каждая попытка — ещё один exec в никуда).
 */
class BrightRestorer(private val limit: Int = 3) {

    private var unreadableStreak = 0
    private var sysfsVetoed = false
    private var unreadableAnnounced = false

    /** Для теста и отчёта: запрещена ли запись в узел до перезапуска процесса. */
    val sysfsWriteForbidden: Boolean get() = sysfsVetoed
    val streakForTest: Int get() = unreadableStreak

    fun runOnce(
        want: Int,
        settings: Int?,
        sysfs: Int?,
        writers: BrightWriters,
        path: String = "",
        errno: String? = null
    ): SyncOutcome {
        val state = syncState(settings, sysfs, want)
        val lines = ArrayList<String>(2)

        // 1. Серия «узел не читается».
        if (state == SyncState.SYSFS_UNREADABLE || (sysfs == null && state == SyncState.BOTH_DIFF)) {
            unreadableStreak++
            if (unreadableStreak >= limit && !unreadableAnnounced) {
                unreadableAnnounced = true
                sysfsVetoed = true
                lines += "BRIGHT_SYSFS_UNREADABLE path=$path errno=${errno ?: "unknown"} " +
                    "streak=$unreadableStreak action=no_writes"
            }
        } else {
            unreadableStreak = 0
        }

        // 2. План: только расходящееся; после вето — без записи в узел.
        var plan = syncPlan(state)
        if (sysfsVetoed && plan.writeSysfs) plan = plan.copy(writeSysfs = false)
        if (state == SyncState.BOTH_DIFF && sysfs == null && plan.writeSysfs) {
            plan = plan.copy(writeSysfs = false)
        }

        // 3. Запись: ноль вызовов, если писать нечего.
        val calls = ArrayList<String>(2)
        if (plan.writeSettings) { writers.writeSettings(want); calls += "settings" }
        if (plan.writeSysfs) { writers.writeSysfs(want); calls += "sysfs" }

        // 4. Одна строка на фактическое расхождение — с названной стороной.
        if (!plan.empty) {
            lines += "BRIGHT_SYNC state=${state.name.lowercase()} want=$want " +
                "settings=${settings ?: "unread"} sysfs=${sysfs ?: "unread"} " +
                "wrote=${calls.joinToString("+")}"
        }
        for (l in lines) writers.log(l)
        return SyncOutcome(state, plan, calls, lines)
    }
}
