package com.example.voiceapp3.tools

/**
 * iter67 §I — возврат сети не зависит ни от исхода payload, ни от пяти минут, ни от claim.
 *
 * Дефект (воспроизведён на живой ГУ): клик 19.11 — `svc wifi disable` выполнился, `setprop` — нет,
 * операция осталась `undefined`, и Wi-Fi НЕ вернулся: «продолжения» не было, а если бы оно было —
 * отказали бы `allowEnable` (отметка старше 5 минут) и `claimGuard` (файла `claim` нет вовсе).
 * Владелец включал сеть руками.
 *
 * Здесь два факта разделены и проверяются JVM-тестом:
 *  • [restoreDecision] — надо ли включать сеть (`restoreNetwork`) и доказан ли исход цепочки
 *    (`chainResolved`). Это РАЗНЫЕ вопросы: включение сети не доказывает выполнения `setprop`, а
 *    недоказанный исход не запрещает вернуть сеть, которую сняли МЫ;
 *  • [uiReason] — программное различие «скрипт не отправлен» (`not_sent`) и «отправлен, ответ
 *    потерян» (`sent_no_reply`). Раньше оба показывались как «не отправлен», что неверно.
 */
object JitNetDecision {

    /**
     * §I.1 — ключ флага «сеть выключил наш скрипт» (`svc wifi disable`).
     *
     * Объявлен здесь, а не в ядре JIT: правило `JitRefTest.noOwnMechanismsLeftInCore` запрещает
     * ядру содержать собственные механизмы, и этот флаг — именно наш, а не референса.
     */
    const val KEY_NET_CUT_BY_US = "jit_we_disarmed_wifi"

    /** Сколько живёт отметка, после которой включать сеть «не по нашей воле» уже нельзя. */
    const val WIFI_ENABLE_MAX_AGE_MS = 5 * 60_000L

    /** Два независимых факта + названная причина. */
    data class RestoreDecision(
        val restoreNetwork: Boolean,
        val why: String,
        val chainResolved: Boolean
    )

    /**
     * §I.2 — правило решения. Порядок проверок важен и проверяется тестами:
     *  • нет снимка — возвращать нечего;
     *  • `wifi=0` — до клика сеть была выключена, включать её мы не имеем права;
     *  • сеть уже включена — включать нечего, но цепочка при этом не «доказана»;
     *  • `weDisarmed` — сеть сняли МЫ: возраст отметки не аргумент;
     *  • иначе — прежние правила (возраст, затем claim, причём «нет файла» ≠ «чужой»).
     */
    fun restoreDecision(
        snap: JitRef.WifiSnapshot?,
        weDisarmed: Boolean,
        markAgeMs: Long?,
        claimKnown: Boolean,
        nowEnabled: Boolean?
    ): RestoreDecision = when {
        snap == null -> RestoreDecision(false, "no_snapshot", false)
        !snap.wifi -> RestoreDecision(false, "was_off", false)
        nowEnabled == true -> RestoreDecision(false, "already_on", true)
        weDisarmed -> RestoreDecision(true, "we_disarmed", true)
        markAgeMs != null && markAgeMs > WIFI_ENABLE_MAX_AGE_MS ->
            RestoreDecision(false, "too_old_and_not_ours", false)
        !claimKnown -> RestoreDecision(true, "claim_absent_not_foreign", true)
        else -> RestoreDecision(true, "continuation", true)
    }

    /**
     * §I.6 — программное различие причин блокировки для UI. `chain_undefined` означает, что скрипт
     * УШЁЛ на устройство и ответ потерян, а не «не отправлен».
     */
    fun uiReason(blocked: String?): String = when (blocked) {
        null -> "none"
        "chain_undefined" -> "sent_no_reply"
        else -> "not_sent"
    }

    /**
     * §I.4 — что показать владельцу, если сеть снял наш клик, а автоматический возврат не выполнен.
     * `null` — показывать нечего (сеть не наша забота или уже включена).
     *
     * Возвращается МАШИННАЯ пара `state`/`text`: панель не парсит русский текст, а состояние
     * различается программно. Красного цвета в UI нет — это требование мандата.
     */
    fun ownerNotice(weDisarmed: Boolean, nowEnabled: Boolean?, snapWifi: Boolean?): Pair<String, String>? {
        if (!weDisarmed) return null
        if (nowEnabled == true) return null
        val state = if (nowEnabled == null) "unread" else "denied"
        return state to "Wi-Fi был выключен кликом; автоматический возврат сети не выполнен " +
            "(состояние сети: " + if (nowEnabled == null) "прочитать нельзя" else "выключена" + ")"
    }

    /** Текст для владельца по причине отказа от автоматического включения (без красного). */
    fun deniedText(why: String): String =
        "Wi-Fi был выключен кликом; автоматический возврат сети не выполнен: причина $why"
}
