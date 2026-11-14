package com.example.voiceapp3.tools

import java.util.concurrent.locks.ReentrantLock

/**
 * iter48 §Б — ЕДИНСТВЕННЫЙ исполнитель TTL: клик, boot, сетевое событие, возврат root-канала.
 *
 * Дефекты iter47 (видны в коде на `6a944c0`, воспроизводятся тестами):
 *  1. `execute()` пропускал проход по условию
 *     `curGen == lastAppliedGen && lastAppliedOk && !networkDirty` — фоновый повод, пришедший
 *     ПОСЛЕ успешного применения того же поколения, не доходил до транспорта. Событие было,
 *     проверки — нет; а после смены сети правила на месте не остаются.
 *  2. Граница «проверка → unlock»: поток-повод видел `runLock.isLocked == true`, ставил
 *     `networkDirty = true`, а владелец цикла в этот момент уже вышел из `while` и в `finally`
 *     ничего не перепроверял → повод терялся молча.
 *  3. Исчерпание `maxPasses` отбрасывало непроверенное последнее намерение.
 *
 * Как устроено теперь:
 *  • сериализация — [gate] (`ReentrantLock.tryLock`): проходы не идут параллельно, выигравший
 *    владеет циклом до опустошения очереди;
 *  • поводы копятся в СЧЁТЧИКЕ [pending] и НИКОГДА не «просматриваются»: цикл снимает счётчик
 *    и обязан выполнить по нему проход; проверка «мы уже применяли это поколение» удалена —
 *    каждый вход в цикл подтверждает текущее состояние заново;
 *  • после `unlock()` владелец ПЕРЕПРОВЕРЯЕТ счётчик (`drainPending()`); гонка «повод пришёл
 *    между последней проверкой и unlock» закрыта: повод либо сам заберёт свободный [gate],
 *    либо увидит `pending > 0`, которое владелец прочитает после unlock;
 *  • бюджет проходов не теряет работу: остаток исполняется продолжением того же цикла до
 *    жёсткого предела ([hardPassLimit]); при достижении предела это пишется в журнал прямо
 *    (`TTL_PASS stop reason=budget`) — «применено» не заявляется, намерение владельца остаётся;
 *  • `applied` = только подтверждённый транспорт + не сменившееся поколение + сохранённое
 *    намерение; отсутствие uplink/root даёт `confirmed=false` (состояние pending), ON
 *    владельца при этом не переписывается.
 *
 * Чего НЕ гарантируется и не заявляется:
 *  • отмена уже отправленного shell-скрипта: OFF во время ON исполняется СЛЕДУЮЩИМ проходом,
 *    а снимает он только свои правила (это свойство транспорта, см. `TtlScript`);
 *  • межпроцессная защита: [gate] — только внутри JVM. Второй процесс (или второй экземпляр
 *    приложения) этим lock не сериализуется; это ограничение записано и в журнал, и в отчёт.
 */
class TtlRunner(
    private val transport: Transport,
    private val store: Store,
    private val log: (String) -> Unit,
    private val maxPasses: Int = 3
) {

    /** Исполнение одного применения. Возвращает ФАКТ проверки, а не «команда отправлена». */
    interface Transport {
        fun apply(want: Boolean): Outcome
    }

    data class Outcome(val confirmed: Boolean, val reason: String = "", val detail: String = "")

    /** Хранилище намерения/факта. Реализация — `NetworkTtl.PrefsStore` (CPS+DPS через Prefs2). */
    interface Store {
        /** Текущее сохранённое намерение владельца; `null` — прочитать достоверно нельзя. */
        fun desired(): Boolean?

        /** Поколение намерения (растёт на каждой подтверждённой записи намерения). */
        fun intentGen(): Long

        /**
         * Сохранить намерение и вернуть его поколение. `-1` — запись НЕ подтверждена
         * (тогда применять всё равно пробуем, но факт применения не может стать `applied`).
         */
        fun beginIntent(want: Boolean): Long

        /** Записать факт. [current] = поколение намерения не менялось за время прохода. */
        fun markApplied(ok: Boolean, want: Boolean, gen: Long, current: Boolean, reason: String)

        /** Конфликт равных ревизий во внешнем хранилище (UI должен показать «неизвестно»). */
        fun conflict(): Boolean = false

        /** Причина, по которой прочитать намерение не удалось (для журнала). */
        fun desiredProblem(): String = ""
    }

    /** Что произошло с запросом. */
    enum class Request {
        /** Применение выполнено этим вызовом (успешно или нет — см. [last]). */
        STARTED,
        /** Применение уже шло: намерение сохранено и будет догнано текущим циклом. */
        QUEUED,
        /** Намерение сохранить не удалось, применение не начиналось. */
        INTENT_NOT_SAVED,
        /** Хранилище в конфликте: порядок неизвестен, ничего не меняем. */
        CONFLICT
    }

    data class Result(
        val request: Request,
        val want: Boolean,
        val confirmed: Boolean,
        val reason: String,
        /** Поколение применённого/запрошенного намерения (`-1` — не подтверждено). */
        val gen: Long,
        val passes: Int
    )

    private val gate = ReentrantLock()

    @Volatile private var lastResult: Result? = null
    @Volatile private var lastAppliedGen: Long = -1L
    @Volatile private var lastAppliedOk: Boolean = false

    /** Сколько внешних поводов ещё не обслужено. Счётчик, а не флаг: два повода ≠ один. */
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)

    /** Проходов в текущем цикле (для отчёта/тестов). */
    @Volatile private var passesDone: Int = 0

    /** Жёсткий предел проходов одного цикла — защита от «шторма намерений», не от поводов. */
    private val hardPassLimit: Int get() = maxOf(maxPasses, 1) * 4

    fun isRunning(): Boolean = gate.isLocked

    fun last(): Result? = lastResult

    fun appliedGen(): Long = lastAppliedGen

    fun appliedOk(): Boolean = lastAppliedOk

    /** Кол-во поводов, ещё не обслуженных циклом (диагностика). */
    fun pendingWork(): Int = pending.get()

    /**
     * Пользовательский клик.
     *
     * Намерение сохраняется ВСЕГДА и первым; только потом пытаемся применить. Если применение
     * уже идёт, параллельного прохода нет: повод встаёт в [pending], а текущий цикл обязан его
     * обслужить (в том числе после `unlock()` — см. [drainPending]). Возвращается `QUEUED` —
     * это честный ответ «намерение сохранено, применение идёт», а не «применено».
     */
    fun request(want: Boolean, source: String): Result {
        if (store.conflict()) {
            log("TTL_INTENT_CONFLICT source=$source action=none " +
                "(копии CPS/DPS имеют равные ревизии с разными значениями: порядок неизвестен, " +
                "ничего не перезаписываем)")
            return Result(Request.CONFLICT, want, false, "storage_conflict", -1L, 0).also {
                lastResult = it
            }
        }
        val gen = store.beginIntent(want)
        if (gen < 0L) {
            log("TTL_INTENT_NOT_SAVED source=$source want=$want action=apply_anyway " +
                "(намерение НЕ подтверждено хранилищем: применять пробуем, но факт «применено» " +
                "заявлять нельзя)")
        } else {
            log("TTL_INTENT source=$source want=$want gen=$gen")
        }
        pending.incrementAndGet()
        return enterCycle(gen, want, source, intentSaved = gen >= 0L)
    }

    /**
     * Фоновый повод (boot / сетевое событие / возврат root-канала / добор). Намерение НЕ меняется.
     *
     * Повод ВСЕГДА означает проверку текущего состояния, даже если это поколение уже было
     * подтверждено: сеть могла смениться, правила — исчезнуть, процесс — перезапуститься.
     * При `desired=false` проход также допустим: он снимает ТОЛЬКО свои правила (санация).
     * Включать Wi-Fi, трогать чужие цепочки и менять намерение транспорт не имеет права.
     */
    fun onBackgroundTrigger(reason: String): Result? {
        val desired = store.desired()
        if (desired == null) {
            log("TTL_BG_TRIGGER reason=$reason action=none desired=unknown " +
                "why=«${store.desiredProblem()}» " +
                "(намерение прочитать нельзя — вслепую не применяем и не снимаем)")
            return null
        }
        pending.incrementAndGet()
        return enterCycle(store.intentGen(), desired, reason, intentSaved = true)
    }

    // ───────────────────────────── цикл ─────────────────────────────

    private fun enterCycle(gen: Long, want: Boolean, source: String, intentSaved: Boolean): Result {
        if (gate.isHeldByCurrentThread) {
            // ReentrantLock пустил бы тот же поток повторно, и проход вложился бы в себя
            // (транспорт/приёмник события вызывает раннер из своего же прохода). Вложенный
            // проход запрещён: повод учитывается счётчиком и обслуживается ВНЕШНИМ циклом.
            log("TTL_REENTRANT source=$source action=mark_dirty gen=$gen pending=${pending.get()} " +
                "(повод пришёл изнутри текущего прохода того же потока: повторный вход запрещён, " +
                "повод учтён и будет обслужен внешним циклом)")
            return Result(Request.QUEUED, want, false, "reentrant_marked_dirty", gen, passesDone)
                .also { lastResult = it }
        }
        if (!gate.tryLock()) {
            // Не теряем: повод уже в [pending]. Владелец увидит его в своей проверке ДО выхода
            // и ещё раз — после unlock (drainPending), либо заберёт [gate] сам.
            log("TTL_SERIALIZED source=$source action=mark_dirty gen=$gen pending=${pending.get()} " +
                "(применение уже идёт: параллельного shell нет; повод учтён счётчиком и будет " +
                "обслужен текущим циклом; отмена уже запущенного скрипта не заявляется)")
            return Result(Request.QUEUED, want, false, "queued_after_current", gen, passesDone).also {
                lastResult = it
            }
        }
        try {
            return drainLoop(gen, want, source, intentSaved)
        } finally {
            gate.unlock()
            drainPending()
        }
    }

    /**
     * Продолжение после `unlock()`: закрывает границу «последняя проверка → освобождение lock».
     * Если повод пришёл в этот момент, он либо уже забрал свободный [gate] (тогда tryLock ниже
     * не удастся и продолжение не нужно), либо будет обслужен здесь.
     */
    private fun drainPending() {
        var guard = 0
        while (pending.get() > 0) {
            if (++guard > hardPassLimit) {
                log("TTL_DRAIN stop reason=guard pending=${pending.get()} " +
                    "(предел продолжений в этом вызове; повод остаётся в счётчике и будет " +
                    "обслужен следующим кликом/boot/сетевым событием)")
                return
            }
            if (!gate.tryLock()) return   // владелец есть — он сам увидит pending
            try {
                val want = store.desired()
                if (want == null) {
                    log("TTL_DRAIN skip reason=desired_unknown pending=${pending.get()}")
                    pending.set(0)
                    return
                }
                drainLoop(store.intentGen(), want, "drain", intentSaved = true)
            } finally {
                gate.unlock()
            }
        }
    }

    private fun drainLoop(gen: Long, want: Boolean, source: String, intentSaved: Boolean): Result {
        var passes = 0
        var total = 0
        var confirmed = false
        var reason = ""
        var lastWant = want
        var lastGen = gen
        passesDone = 0
        while (true) {
            // Снимаем ВСЕ накопленные поводы: этот проход отвечает на каждый из них.
            val external = pending.getAndSet(0)
            val curWant = store.desired()
            if (curWant == null) {
                reason = "desired_unknown"
                log("TTL_PASS skip reason=desired_unknown action=none external=$external")
                break
            }
            val curGen = store.intentGen()
            passes++
            total++
            passesDone = passes
            lastWant = curWant
            lastGen = curGen
            val outcome = try {
                transport.apply(curWant)
            } catch (t: Throwable) {
                // Исключение = провал, а не «применено».
                Outcome(false, "exception:${t.javaClass.simpleName}")
            }
            val afterGen = store.intentGen()
            val current = afterGen == curGen
            // §Б: без ПОДТВЕРЖДЁННОГО сохранения намерения «применено» не заявляется.
            val ok = outcome.confirmed && current && intentSaved
            reason = when {
                !outcome.confirmed -> outcome.reason.ifEmpty { "not_confirmed" }
                !current -> "superseded_by_newer_intent"
                !intentSaved -> "intent_not_saved"
                else -> ""
            }
            store.markApplied(outcome.confirmed && intentSaved, curWant, curGen, current,
                if (current) outcome.reason else "superseded")
            confirmed = ok
            if (ok) {
                lastAppliedGen = curGen
                lastAppliedOk = true
            } else {
                lastAppliedOk = false
                lastAppliedGen = -1L
            }
            log("TTL_PASS n=$passes source=$source want=$curWant gen=$curGen " +
                "external=$external confirmed=$ok current=$current reason=«$reason» " +
                "detail=«${outcome.detail}»")

            // Нужен ли ещё проход? Только по РЕАЛЬНОЙ причине:
            //  • пришёл новый внешний повод (клик/сеть/root) — он ещё не обслужен;
            //  • намерение изменилось во время прохода.
            // Одинаковое намерение без нового повода повторно не долбим (иначе шторм команд при
            // недоступном канале): состояние остаётся pending до следующего повода.
            val intentChanged = store.intentGen() != curGen
            // Перечитываем СЧЁТЧИК (а не значение `external`, снятое ДО прохода): повод мог
            // прийти во время самого прохода — например, сетевое событие внутри транспорта.
            val moreWork = pending.get() > 0 || intentChanged
            if (!moreWork) break
            if (passes >= maxPasses) {
                if (total >= hardPassLimit) {
                    log("TTL_PASS stop reason=budget passes=$passes total=$total " +
                        "intentChanged=$intentChanged action=pending_until_next_trigger " +
                        "(предел продолжений: «применено» не заявляется, намерение сохранено)")
                    break
                }
                log("TTL_PASS budget passes=$passes total=$total action=continue " +
                    "(осталась работа: новый повод/новое намерение — продолжаем, не теряем)")
            }
        }
        val res = Result(
            if (!intentSaved) Request.INTENT_NOT_SAVED else Request.STARTED,
            lastWant,
            confirmed,
            reason,
            if (confirmed) lastGen else -1L,
            passes
        )
        lastResult = res
        return res
    }

    /** Кол-во проходов последнего цикла (для отчёта/тестов). */
    fun passes(): Int = passesDone

    /** Сброс для тестов. */
    fun resetForTest() {
        lastResult = null
        lastAppliedGen = -1L
        lastAppliedOk = false
        passesDone = 0
        pending.set(0)
    }
}
