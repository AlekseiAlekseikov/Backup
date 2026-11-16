package com.example.voiceapp3.tools

import java.io.File

/**
 * iter59 §6 — ПОСТОЯННЫЙ TTL: durable identity попытки и правило повтора.
 *
 * Зачем отдельный маленький объект: оба решения (записать адрес свидетельства ДО отправки и
 * «можно ли повторять») обязаны быть проверяемыми в JVM-стенде, а не жить внутри Android-класса
 * [NetworkTtl], который в стенде не компилируется. Поэтому решение вынесено в чистые функции,
 * а production их ВЫЗЫВАЕТ (см. `NetworkTtl.runTransport`).
 *
 * Дефекты iter58, которые закрываются:
 *  1. `NetworkTtl.kt:363–387` — повтор выполнялся через `AdbClient.executeScript` (`Pair<Int,String>`),
 *     то есть без различения «скрипт не доставлен» / «доставлен, исход неизвестен» / «завершён».
 *     Повтор после Unknown мог выполнить мутацию второй раз.
 *  2. Адрес свидетельства попытки нигде не сохранялся до отправки: потеря ответа оставляла
 *     `iptables`-изменение без адреса, по которому terminal можно прочитать.
 */
internal object TtlAttempt {

    /** Что делать после очередной доставки. */
    enum class Decision {
        /** Скрипт доказанно завершён и НЕ применил правила (нет uplink): повтор разрешён. */
        RETRY,

        /** Повторять нельзя: исход неизвестен либо скрипт уже применил правила. */
        STOP,

        /** Готово: попыток больше не нужно (скрипт завершён и uplink был). */
        DONE
    }

    /**
     * Правило повтора — по ТИПИЗИРОВАННОМУ исходу доставки, а не по `Pair`.
     *
     * Повтор оправдан только когда доказано, что скрипт завершился (`Finished`) и сам сообщил
     * `no_uplink` (то есть гарантированно ничего не изменил). Любой другой случай останавливает
     * попытки: «не отправлено» повторит вызывающий проход, «неизвестно» требует readback.
     */
    fun decide(outcome: ScriptDelivery.DeliveryOutcome, out: String, attempt: Int, max: Int): Decision {
        if (attempt >= max) return Decision.STOP
        return when (outcome) {
            is ScriptDelivery.DeliveryOutcome.Finished ->
                if (isNoUplink(out)) Decision.RETRY else Decision.DONE
            else -> Decision.STOP
        }
    }

    /** Скрипт TTL печатает `TTL_TRY variant=none scope="" reason=no_uplink`. */
    fun isNoUplink(out: String): Boolean = out.contains("reason=no_uplink")

    /** Поля записи попытки; вынесены, чтобы тест проверял формат, а не «наличие файла». */
    data class Record(val path: String, val want: Boolean, val ts: Long) {
        fun wire(): String = "path=$path\nwant=${if (want) "on" else "off"}\nts=$ts\n"

        fun evidence(): String = "path=$path want=${if (want) "on" else "off"} ts=$ts"
    }

    fun parse(text: String): Record? {
        val p = Regex("(?m)^path=(\\S+)$").find(text)?.groupValues?.get(1) ?: return null
        val w = Regex("(?m)^want=(on|off)$").find(text)?.groupValues?.get(1) ?: return null
        val t = Regex("(?m)^ts=(\\d+)$").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        if (p.isEmpty()) return null
        return Record(p, w == "on", t)
    }

    /**
     * Записать identity попытки ДО отправки и ПОДТВЕРДИТЬ чтением.
     *
     * @return true — запись подтверждена; false — отправлять нельзя (иначе поздний `iptables`
     *   остался бы без адреса свидетельства).
     */
    fun record(file: File, path: String, want: Boolean, now: Long): Boolean {
        if (path.isEmpty()) return false
        val rec = Record(path, want, now)
        val written = runCatching {
            file.parentFile?.mkdirs()
            file.writeText(rec.wire())
        }.isSuccess
        if (!written) return false
        val back = runCatching { file.readText() }.getOrNull() ?: return false
        return parse(back) == rec
    }

    /** Прочитать последнюю записанную попытку (для отчёта/следующего прохода). */
    fun last(file: File): Record? = runCatching { parse(file.readText()) }.getOrNull()
}
