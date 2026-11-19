package com.example.voiceapp3.tools

/**
 * iter67 §G и §D — «включено, но не применено» и «ждёт перезапуска фреймворка» — отдельные,
 * различимые состояния, а не провал.
 *
 * Дефекты (оба с живой ГУ):
 *  • §G: при `pedido=true`, `getprop=true`, `cache=false` итог выглядел как неудача и снимал
 *    `jit_pedido`, хотя не хватало только старта zygote. Замер:
 *    `JIT pedido=true getprop=true code-cache-executavel=false result=cache_missing`;
 *  • §D: состояние тумблера бралось из НАМЕРЕНИЯ (`perf_auto_otimizar`), факт дописывался отдельным
 *    свойством — владелец видел «включено» при `dalvik.vm.usejit=false`.
 *
 * Модуль чистый (без Android): правило проверяется JVM-тестом.
 */

/** Явная модель состояния тумблера. `UNKNOWN` — «прочитать нельзя», это НЕ «выключено». */
enum class JitUiState { ON, OFF, ON_NOT_APPLIED, PENDING_RESTART, UNKNOWN }

/**
 * §G.1 — три различимых результата сверки плюс `unknown` для нечитаемого свойства.
 *  • `applied`         — свойство и JIT-кэш в карте процесса совпали с намерением;
 *  • `pending_restart` — свойство выставлено, кэша нет: не хватает только старта zygote;
 *  • `failed`          — свойство не то, что просили;
 *  • `unknown`         — прочитать нельзя (ни «применено», ни «не применено»).
 */
enum class VerifyOutcome { APPLIED, PENDING_RESTART, FAILED, UNKNOWN }

object JitUi {

    /**
     * §D.1 — состояние тумблера по трём фактам.
     *
     * Порядок проверок важен и проверяется тестами: нечитаемое свойство — всегда `UNKNOWN`
     * (иначе UI показывал «выключено» при недоступном property, что уже случалось).
     */
    fun jitUiState(intent: Boolean, propValue: JitRef.Prop?, cache: Boolean?): JitUiState = when {
        propValue == null || propValue == JitRef.Prop.UNKNOWN -> JitUiState.UNKNOWN
        propValue == JitRef.Prop.TRUE && cache == true -> JitUiState.ON
        propValue == JitRef.Prop.TRUE && cache != true -> JitUiState.PENDING_RESTART
        propValue == JitRef.Prop.FALSE && intent -> JitUiState.ON_NOT_APPLIED
        else -> JitUiState.OFF
    }

    /** §D.3 — текст состояния для владельца. Без красного и без «провала» у ожидания. */
    fun text(state: JitUiState): String = when (state) {
        JitUiState.ON -> "включено"
        JitUiState.OFF -> "выключено"
        JitUiState.ON_NOT_APPLIED -> "включено, не применено"
        JitUiState.PENDING_RESTART -> "включено, ждёт перезапуска фреймворка"
        JitUiState.UNKNOWN -> "неизвестно"
    }

    /** Машинное имя состояния — для свойства `perfJitState` (UI не парсит русский текст). */
    fun wire(state: JitUiState): String = when (state) {
        JitUiState.ON -> "on"
        JitUiState.OFF -> "off"
        JitUiState.ON_NOT_APPLIED -> "on_not_applied"
        JitUiState.PENDING_RESTART -> "pending_restart"
        JitUiState.UNKNOWN -> "unknown"
    }

    /** §D.2 — короткое объяснение для `perfJitWhy`. */
    fun why(state: JitUiState): String = when (state) {
        JitUiState.ON -> "prop_true_cache_true"
        JitUiState.OFF -> "prop_false_intent_false"
        JitUiState.ON_NOT_APPLIED -> "prop_false_intent_true"
        JitUiState.PENDING_RESTART -> "zygote_not_restarted"
        JitUiState.UNKNOWN -> "prop_unreadable"
    }

    /**
     * §G.1 — результат сверки по тем же фактам, что использует ядро.
     * `pedido` — намерение, `propValue` — факт `getprop`, `cache` — исполняемый JIT-кэш в карте.
     */
    fun verifyOutcome(pedido: Boolean, propValue: JitRef.Prop?, cache: Boolean?): VerifyOutcome = when {
        propValue == null || propValue == JitRef.Prop.UNKNOWN -> VerifyOutcome.UNKNOWN
        // «ждёт перезапуска» — это НЕ провал: свойство уже выставлено, не хватает старта zygote.
        pedido && propValue == JitRef.Prop.TRUE && cache == false -> VerifyOutcome.PENDING_RESTART
        propValue == JitRef.Prop.TRUE && cache == true -> VerifyOutcome.APPLIED
        !pedido && propValue == JitRef.Prop.FALSE -> VerifyOutcome.APPLIED
        else -> VerifyOutcome.FAILED
    }

    fun outcomeWire(o: VerifyOutcome): String = when (o) {
        VerifyOutcome.APPLIED -> "applied"
        VerifyOutcome.PENDING_RESTART -> "pending_restart"
        VerifyOutcome.FAILED -> "failed"
        VerifyOutcome.UNKNOWN -> "unknown"
    }

    /**
     * §G.2 — когда намерение СОХРАНЯЕТСЯ.
     *
     * `pending_restart` его не снимает (владелец ничего не должен делать заново — ждём старта
     * фреймворка) и не блокирует клик; `unknown` — тоже (иначе нечитаемое свойство «съедало» запрос).
     */
    fun keepPedido(outcome: VerifyOutcome): Boolean =
        outcome == VerifyOutcome.PENDING_RESTART || outcome == VerifyOutcome.UNKNOWN

    /** §G.3 — строка журнала: `prop=… cache=… result=… why=…`. */
    fun stateLine(pedido: Boolean?, propRaw: String, cache: Boolean?, outcome: VerifyOutcome): String {
        val why = when (outcome) {
            VerifyOutcome.PENDING_RESTART -> "zygote_not_restarted"
            VerifyOutcome.UNKNOWN -> "prop_unreadable"
            VerifyOutcome.FAILED -> "prop_differs_from_intent"
            VerifyOutcome.APPLIED -> "matched"
        }
        return "JIT_STATE pedido=${pedido?.toString() ?: "none"} " +
            "prop=${propRaw.ifEmpty { "empty" }} " +
            "cache=${cache?.toString() ?: "unknown"} " +
            "result=${outcomeWire(outcome)} why=$why"
    }
}
