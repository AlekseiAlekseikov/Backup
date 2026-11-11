package com.example.voiceapp3.tools

/**
 * Тег загрузки ГУ — только `epoch<sec>` (fallback, когда `/proc` недоступен).
 *
 * Что здесь ОСТАЛОСЬ и почему: `HvacDigitalPlugin.currentBootTag()` (климат) и JIT используют один
 * тег загрузки; читает его `HvacDigitalPlugin`, а `epochTag` остаётся здесь как единственное место
 * формата fallback-тега.
 *
 * Что УДАЛЕНО: `sameLoad()` с допуском ±60 с и `format()`/`stripEpochPrefix()`. Причина —
 * REVIEW §0.1(1): допуск по часам не доказывает «та же загрузка» (NTP, ручная правка времени), и
 * решение «вернуть сеть» на нём принимать нельзя. JIT теперь сравнивает теги строго и только
 * `boot_id`-вида (`JitRef.bootMatch`): `epoch…`-теги дают `UNKNOWN` ⇒ ничего не применяем.
 *
 * Ничего не удаляет и не «санирует»: решение по остатку прошлой загрузки принимает владелец.
 */
object JitBootMark {

    /** Префикс fallback-тега. */
    const val EPOCH_PREFIX = "epoch"

    /** Тег из epoch-времени в МИЛЛИСЕКУНДАХ (в теге — секунды). */
    fun epochTag(epochMs: Long): String = EPOCH_PREFIX + (epochMs / 1000L)
}
