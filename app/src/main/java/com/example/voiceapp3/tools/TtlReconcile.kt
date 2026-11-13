package com.example.voiceapp3.tools

/**
 * iter46 §5.1/§5.2 — сериализация TTL-применения по ПОСЛЕДНЕМУ сохранённому намерению.
 *
 * Дефект iter45 (доказан по коду `NetworkTtl.kt:217-226,241-251`):
 *  • клик отвергался по `ttlInFlight`/debounce **до** сохранения нового намерения, поэтому
 *    быстрый OFF во время ON или во время retry просто терялся — тумблер показывал OFF, а
 *    правила оставались стоять (или наоборот);
 *  • событие сети, пропущенное из-за in-flight/debounce, не приводило к повторной проверке —
 *    «пропущено» означало «потеряно».
 *
 * Здесь описана только ПОЛИТИКА (без Android и shell), поэтому она проверяется JVM-тестами,
 * а `NetworkTtl` вызывает ровно эти функции:
 *  • [onIntent] — любой принятый пользовательский запрос сохраняется ВСЕГДА; если применение
 *    уже идёт, запрос запоминается как «догнать после текущей операции»;
 *  • [onRunFinished] — по завершении применения вызывается ровно одно добивание, если
 *    последнее намерение ещё не достигнуто;
 *  • [onNetworkEvent] — событие сети не теряется: во время применения ставится флаг «догнать»;
 *  • цепочка добиваний ОГРАНИЧЕНА ([maxChain]): никаких бесконечных циклов и лавины команд.
 *
 * Чего эта политика НЕ делает и не может: она не отменяет уже запущенный shell-скрипт.
 * Если OFF пришёл во время выполнения ON, скрипт ON всё равно доработает — поэтому после его
 * завершения обязательно выполняется добивание актуальным (OFF) намерением, и правила
 * снимаются уже им. Мгновенная отмена запущенной команды не заявляется.
 */
class TtlReconcile(private val maxChain: Int = 3) {

    /** Что делать вызывающему после [onIntent]. */
    enum class Decision {
        /** Применять прямо сейчас (применения не идёт). */
        RUN_NOW,
        /** Применение уже идёт: намерение сохранено, будет добивание после него. */
        MARKED_AND_RUNNING
    }

    private val lock = Any()

    /** Идёт ли применение (shell уже запущен). */
    private var running = false
    /** Намерение, которое сейчас применяется. */
    private var runningWant: Boolean? = null
    /** Последнее сохранённое намерение владельца. */
    private var desiredWant: Boolean? = null
    /** Намерение, которое уже подтверждено последним завершённым применением. */
    private var appliedWant: Boolean? = null
    /** Сколько добиваний уже сделано в текущей цепочке. */
    private var chain = 0
    /** Сеть менялась во время применения — нужен ещё один проход. */
    private var networkDirty = false

    fun isRunning(): Boolean = synchronized(lock) { running }

    /**
     * Пользовательский запрос. Сохраняется ВСЕГДА (в т.ч. OFF во время in-flight).
     * Счётчик добиваний сбрасывается: это новое намерение, а не продолжение старой цепочки.
     */
    fun onIntent(want: Boolean): Decision = synchronized(lock) {
        desiredWant = want
        chain = 0
        if (running) Decision.MARKED_AND_RUNNING
        else {
            running = true
            runningWant = want
            Decision.RUN_NOW
        }
    }

    /** Начало применения (для путей, которые не идут через [onIntent]: сеть, boot). */
    fun beginRun(want: Boolean): Boolean = synchronized(lock) {
        if (running) return@synchronized false
        running = true
        runningWant = want
        true
    }

    /**
     * Применение завершилось.
     *
     * @param success подтверждено ли правило после применения (VERIFY).
     * @return намерение, которое нужно применить СЛЕДУЮЩИМ (добивание), или `null`.
     */
    fun onRunFinished(success: Boolean): Boolean? = synchronized(lock) {
        val want = runningWant
        running = false
        runningWant = null
        if (success && want != null) appliedWant = want

        val desired = desiredWant ?: return@synchronized null
        val satisfied = success && want == desired && !networkDirty
        networkDirty = false
        if (satisfied) {
            chain = 0
            return@synchronized null
        }
        // Догоняем последнее намерение — но не бесконечно.
        if (chain >= maxChain) {
            chain = 0
            return@synchronized null
        }
        chain += 1
        running = true
        runningWant = desired
        desired
    }

    /** Событие сети: если применение идёт — запомнить, что нужен ещё проход (не терять). */
    fun onNetworkEvent(): Boolean = synchronized(lock) {
        if (running) {
            networkDirty = true
            false
        } else true
    }

    /** Намерение владельца (для логов и добивания). */
    fun desired(): Boolean? = synchronized(lock) { desiredWant }

    /** Последнее подтверждённое намерение. */
    fun applied(): Boolean? = synchronized(lock) { appliedWant }

    /** Сброс для тестов/нового процесса. */
    fun reset() = synchronized(lock) {
        running = false
        runningWant = null
        desiredWant = null
        appliedWant = null
        chain = 0
        networkDirty = false
    }
}
