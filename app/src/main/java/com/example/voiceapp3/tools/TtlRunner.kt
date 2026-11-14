package com.example.voiceapp3.tools

import java.util.concurrent.locks.ReentrantLock

/**
 * iter47 §4.2 — ЕДИНЫЙ сериализованный путь применения TTL.
 *
 * Дефекты iter46 (доказаны кодом и повторно проверены на HEAD `66de8de`):
 *  1. `NetworkTtl.setTtl65Active` после добивания (`applyForIntent(followUp, "reconcile")`)
 *     НЕ вызывал `onRunFinished` — координатор навсегда оставался `running=true`, и все
 *     последующие клики писались как «в ожидании», т.е. молча терялись;
 *  2. сетевой receiver и `reapplyForNetwork` применяли скрипт напрямую, минуя координатор, а
 *     независимый `ttlInFlight` мог расходиться с его состоянием → сетевой ON пересекался с
 *     ручным OFF, и «последнее намерение» уже ничего не гарантировало;
 *  3. `bootRestore` имел отдельный путь записи `applied`.
 *
 * Здесь один владелец состояния: любой вход (клик, boot, сеть, возврат root, отложенный
 * повтор) идёт через [request]/[onBackgroundTrigger] и исполняется в ОДНОМ цикле под
 * [runLock]. Параллельных shell-операций TTL в процессе нет по построению.
 *
 * Что гарантируется:
 *  • намерение сохраняется ДО применения, с фактическим результатом записи ([Store.beginIntent]);
 *  • каждый проход завершается в координаторе (`onRunFinished`) и в хранилище (`markApplied`);
 *  • система доходит до ПОСЛЕДНЕГО намерения (догон ограничен [maxPasses]);
 *  • `applied` пишется только если поколение намерения НЕ сменилось за время прохода
 *    (результат старого OFF не «переименовывается» в новый ON и наоборот);
 *  • исключение транспорта = провал прохода, а не «применено».
 *
 * Чего НЕ гарантируется и не заявляется: отмена уже запущенного shell-скрипта. Если во время
 * ON пришёл OFF, скрипт ON доработает, а затем выполняется проход с OFF, который снимает
 * ТОЛЬКО свои правила (это делает транспорт).
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
        /** Применение уже шло: намерение сохранено и будет догнано текущим проходом. */
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

    private val runLock = ReentrantLock()

    @Volatile private var lastResult: Result? = null
    @Volatile private var lastAppliedGen: Long = -1L
    @Volatile private var lastAppliedOk: Boolean = false
    @Volatile private var networkDirty: Boolean = false
    @Volatile private var passesDone: Int = 0

    /** Идёт ли применение прямо сейчас (для диагностики/UI). */
    fun isRunning(): Boolean = runLock.isLocked

    fun last(): Result? = lastResult

    /**
     * Пользовательский клик.
     *
     * Намерение сохраняется ВСЕГДА и первым; только потом пытаемся применить. Если применение
     * уже идёт, проход не запускается параллельно — текущий владелец цикла увидит новое
     * поколение и догонит его сам.
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
        return execute(gen, want, source, intentSaved = gen >= 0L)
    }

    /**
     * Фоновый повод (boot / сетевое событие / возврат root-канала). Намерение НЕ меняется.
     *
     * При `desired=false` проход всё равно допустим: он снимает ТОЛЬКО собственные правила
     * (санация). Никогда не включает Wi-Fi и не трогает чужие цепочки — это свойство транспорта.
     */
    fun onBackgroundTrigger(reason: String): Result? {
        val desired = store.desired()
        if (desired == null) {
            log("TTL_BG_TRIGGER reason=$reason action=none desired=unknown " +
                "why=«${store.desiredProblem()}» " +
                "(намерение прочитать нельзя — вслепую не применяем и не снимаем)")
            return null
        }
        if (runLock.isLocked) {
            // Не теряем: владелец цикла увидит это после текущего прохода.
            networkDirty = true
            log("TTL_BG_TRIGGER reason=$reason action=mark_dirty desired=$desired " +
                "(применение уже идёт: событие не теряется, будет добито текущим циклом)")
            return null
        }
        return execute(store.intentGen(), desired, reason, intentSaved = true)
    }

    private fun execute(gen: Long, want: Boolean, source: String, intentSaved: Boolean): Result {
        val acquired = runLock.tryLock()
        if (!acquired) {
            log("TTL_SERIALIZED source=$source action=queued gen=$gen " +
                "(применение уже идёт: этот проход не запускается параллельно — текущий цикл " +
                "догонит последнее намерение; отмена уже запущенного скрипта не заявляется)")
            // Владелец цикла завершит проход и увидит новое поколение в своей проверке.
            return Result(Request.QUEUED, want, false, "queued_after_current", gen, 0).also {
                lastResult = it
            }
        }
        passesDone = 0
        try {
            var confirmed = false
            var reason = ""
            var passes = 0
            // Сетевое событие требует ПОВТОРНОЙ ПРОВЕРКИ, даже если состояние уже было
            // подтверждено: после смены сети правила могли исчезнуть.
            var forceVerify = false
            while (passes < maxPasses) {
                val curGen = store.intentGen()
                val curWant = store.desired()
                if (curWant == null) {
                    reason = "desired_unknown"
                    break
                }
                // Уже достигнуто этим же поколением — лишних команд не отправляем. Но если было
                // СЕТЕВОЕ событие, конфигурация могла измениться: проверяем заново ([networkDirty]).
                if (curGen == lastAppliedGen && lastAppliedOk && !networkDirty && !forceVerify) {
                    confirmed = true
                    break
                }
                passes++
                passesDone = passes
                forceVerify = false
                val outcome = try {
                    transport.apply(curWant)
                } catch (t: Throwable) {
                    // Исключение = провал, а не «применено».
                    Outcome(false, "exception:${t.javaClass.simpleName}")
                }
                val afterGen = store.intentGen()
                val current = afterGen == curGen
                confirmed = outcome.confirmed && current
                reason = when {
                    !outcome.confirmed -> outcome.reason.ifEmpty { "not_confirmed" }
                    !current -> "superseded_by_newer_intent"
                    else -> ""
                }
                store.markApplied(outcome.confirmed, curWant, curGen, current,
                    if (current) outcome.reason else "superseded")
                log("TTL_PASS n=$passes source=$source want=$curWant gen=$curGen " +
                    "confirmed=${outcome.confirmed} current=$current reason=«$reason» " +
                    "detail=«${outcome.detail}»")
                if (confirmed) {
                    lastAppliedGen = curGen
                    lastAppliedOk = true
                } else {
                    lastAppliedOk = false
                    if (current) lastAppliedGen = -1L
                }
                // Догоняем, только если намерение реально изменилось. Одинаковое намерение
                // повторно не долбим: иначе получаем шторм команд при недоступном канале.
                val newestGen = store.intentGen()
                val newestWant = store.desired()
                val changed = newestGen != curGen && newestWant != null
                if (!changed && !networkDirty) break
                if (networkDirty && !changed) {
                    networkDirty = false
                    if (newestWant == false) break   // OFF при OFF — повторять нечего
                    forceVerify = true               // сеть изменилась — проверяем ещё раз
                }
                networkDirty = false
            }
            networkDirty = false
            // §4.2 п.2: без ПОДТВЕРЖДЁННОГО сохранения намерения факт «применено» не заявляется:
            // после перезапуска состояние разошлось бы с диском.
            if (!intentSaved) confirmed = false
            val res = Result(
                if (intentSaved) Request.STARTED else Request.INTENT_NOT_SAVED,
                want, confirmed, reason, if (confirmed) store.intentGen() else -1L, passes
            )
            lastResult = res
            return res
        } finally {
            runLock.unlock()
        }
    }

    /** Кол-во проходов последнего запроса (для отчёта/тестов). */
    fun passes(): Int = passesDone

    /** Состояние для UI: применённое поколение и флаг. */
    fun appliedGen(): Long = lastAppliedGen
    fun appliedOk(): Boolean = lastAppliedOk

    /** Сброс для тестов. */
    fun resetForTest() {
        lastResult = null
        lastAppliedGen = -1L
        lastAppliedOk = false
        networkDirty = false
        passesDone = 0
    }
}
