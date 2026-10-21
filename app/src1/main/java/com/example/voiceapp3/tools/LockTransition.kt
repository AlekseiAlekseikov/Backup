package com.example.voiceapp3.tools

/**
 * Детектор ПЕРЕХОДА состояния центрального замка и дедупликатор воспроизведения.
 *
 * Класс намеренно не знает ни про Android, ни про CarPropertyManager, ни про
 * Context: вся логика «играть / не играть» проверяется на JVM без железа
 * (tools/kotlintests/LockTransitionTest.kt). Единственный вход — значение
 * свойства замка и монотонное время, единственный выход — вердикт.
 *
 * Правила (ТЗ, п.5 — порядок «сначала источник события, потом фильтр»):
 *  1. Первое значение, пришедшее после подписки, считается БАЗОВЫМ и не играет:
 *     машина может быть уже открыта, и звук на «появление состояния» — баг.
 *  2. Играет только переход locked → unlocked (звук открытия) и
 *     unlocked → locked (звук закрытия).
 *  3. Повторное значение тем же состоянием («состояние не изменилось», вторая
 *     команда с брелока без переключения замка) звука не даёт.
 *  4. Внутри окна дребезга [DEBOUNCE_DEFAULT_MS] на один и тот же переход —
 *     ровно одно воспроизведение.
 *  5. Значение, которое не удалось разобрать (null, пустая строка, список),
 *     состояние не меняет и звука не даёт.
 *
 * Геометрия значения: у свойства замка может быть несколько зон (двери), а
 * команда с брелока приходит одной зоной. Поэтому состояние хранится как
 * «сколько зон сейчас в каждом из состояний»: переход считается по изменению
 * счётчиков, а не по отдельной зоне. Это же гасит дубль «событие пришло дважды
 * для разных зон одной команды».
 */
class LockTransition(
    /**
     * Окно подавления дребезга, мс. ТЗ допускает 300..800; 500 мс — середина,
     * достаточно коротко, чтобы два настоящих цикла «закрыл/открыл» не склеились,
     * и достаточно, чтобы CAN-повторы одной команды не дали второй звук.
     */
    private val debounceMs: Long = DEBOUNCE_DEFAULT_MS,
    /**
     * Полярность целочисленного значения свойства. В прошивке ГУ она не
     * документирована (референс CentralEXAuto состояние замка не читает
     * вообще — звук играет система), поэтому полярность задаётся ключом
     * [LockStateTrigger.KEY_LOCK_POLARITY] и проверяется на стенде.
     */
    private val zeroMeansLocked: Boolean = true
) {

    /** Вердикт по одному полученному событию. */
    data class Verdict(
        /** Играть звук открытия (замок перешёл в «открыт»). */
        val playUnlock: Boolean,
        /** Играть звук закрытия (замок перешёл в «закрыт»). */
        val playLock: Boolean,
        /** Разобранное состояние после события; null — значение не разобрано. */
        val state: Boolean?,
        /** Предыдущее агрегированное состояние до события. */
        val previous: Boolean?,
        /** Человекочитаемая причина решения — она же уходит в logcat. */
        val reason: String
    ) {
        val play: Boolean get() = playUnlock || playLock
    }

    /** Сколько зон в каком состоянии; null-значения не учитываются. */
    private val zones = HashMap<Int, Boolean>()

    /** Агрегированное состояние до последнего обработанного события. */
    private var previousState: Boolean? = null
    private var lastPlayAtMs = Long.MIN_VALUE
    private var seeded = false
    private var eventsTotal = 0L
    private var eventsRejected = 0L

    /** Счётчики для диагностики (строка «Сигнал замка» и logcat). */
    val totalEvents: Long get() = eventsTotal
    val rejectedEvents: Long get() = eventsRejected

    /** Текущее агрегированное состояние: true = открыт, false = закрыт, null = неизвестно. */
    val state: Boolean?
        get() = if (zones.isEmpty()) null else zones.values.count { it } > 0

    /** Сброс (переподписка, смена свойства, смена полярности). */
    fun reset() {
        zones.clear()
        lastPlayAtMs = Long.MIN_VALUE
        seeded = false
    }

    /**
     * Разбор значения свойства в состояние замка.
     * @return true = открыт (unlocked), false = закрыт (locked), null = не разобрано.
     */
    fun parseLockValue(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> {
            val i = value.toInt()
            when {
                i == 0 -> !zeroMeansLocked
                i == 1 -> zeroMeansLocked
                // 2 и выше у вендорских свойств часто «неизвестно/недоступно».
                else -> null
            }
        }
        is String -> when (val s = value.trim().lowercase()) {
            "true", "1", "unlock", "unlocked", "open", "otkryto" -> true
            "false", "0", "lock", "locked", "close", "closed", "zakryto" -> false
            else -> s.toIntOrNull()?.let { parseLockValue(it) }
        }
        else -> null
    }

    /**
     * Обработать одно событие свойства замка.
     *
     * @param areaId зона свойства (0 — глобальная); события разных зон одной
     *        команды складываются в одно агрегированное состояние.
     * @param rawValue значение как пришло из CarPropertyValue.getValue().
     * @param nowMs монотонное время (SystemClock.elapsedRealtime()).
     */
    fun onLockEvent(areaId: Int, rawValue: Any?, nowMs: Long): Verdict {
        eventsTotal++
        val parsed = parseLockValue(rawValue)
        if (parsed == null) {
            eventsRejected++
            return Verdict(false, false, null, state, "значение не разобрано: ${describe(rawValue)}")
        }
        zones[areaId] = parsed
        val now = state
        if (!seeded) {
            seeded = true
            previousState = now
            eventsRejected++
            return Verdict(false, false, now, null, "базовое состояние при подписке: ${name(now)} — не играем")
        }
        val prev = previousState
        previousState = now
        if (prev == now) {
            eventsRejected++
            return Verdict(false, false, now, prev, "состояние не изменилось: ${name(now)}")
        }
        val wantUnlock = now == true && prev == false
        val wantLock = now == false && prev == true
        /* lastPlayAtMs == Long.MIN_VALUE — воспроизведения ещё не было;
           вычитать его нельзя: nowMs - Long.MIN_VALUE переполняется в
           отрицательное число и глушит ПЕРВЫЙ же переход. */
        if (lastPlayAtMs != Long.MIN_VALUE && nowMs - lastPlayAtMs < debounceMs) {
            eventsRejected++
            return Verdict(false, false, now, prev, "дребезг: переход ${name(prev)}→${name(now)} подавлен окном ${debounceMs} мс")
        }
        lastPlayAtMs = nowMs
        return if (wantUnlock) {
            Verdict(true, false, now, prev, "переход locked→unlocked (зона $areaId, значение ${describe(rawValue)})")
        } else {
            Verdict(false, true, now, prev, "переход unlocked→locked (зона $areaId, значение ${describe(rawValue)})")
        }
    }

    private fun name(v: Boolean?): String = when (v) {
        null -> "неизвестно"
        true -> "открыт"
        false -> "закрыт"
    }

    private fun describe(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"$v\""
        is Collection<*> -> "[${v.size} элем.]"
        else -> "${v.javaClass.simpleName}=$v"
    }

    companion object {
        /** Значение окна дребезга по умолчанию (ТЗ: 300..800 мс). */
        const val DEBOUNCE_DEFAULT_MS = 500L

        /** Диапазон окна дребезга, который ТЗ считает допустимым. */
        const val DEBOUNCE_MIN_MS = 300L
        const val DEBOUNCE_MAX_MS = 800L

        fun validDebounce(ms: Long): Boolean = ms in DEBOUNCE_MIN_MS..DEBOUNCE_MAX_MS
    }
}
