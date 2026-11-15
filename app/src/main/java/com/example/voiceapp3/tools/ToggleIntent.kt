package com.example.voiceapp3.tools

/**
 * iter54 §10 — INTENT И ПРИМЕНЕНИЕ: ДВА РАЗНЫХ ФАКТА.
 *
 * Дефект (аудит §5/§10): подтверждением выбора владельца считался `ToolsResult.ok` общего
 * действия. Для TTL это неверно: намерение `ttl65=on` может быть СОХРАНЕНО, а применение —
 * ждать uplink (PARTIAL). UI получал `ok=false` и рисовал «не применено», то есть превращал
 * честное «сохранено, ждёт применения» в ложное «выключено/не сохранено». Обратная ошибка хуже:
 * side effect удался, а запись в хранилище — нет, и UI всё равно показывал успех.
 *
 * Здесь разделены три независимых вопроса:
 *
 * ```
 * (a) intent   — что владелец выбрал (клик);
 * (b) persist  — подтверждено ли СОХРАНЕНИЕ намерения (чтение хранилища после записи);
 * (c) applied  — применён ли side effect (сеть, iptables, bind, VHAL);
 * (d) status   — что показывать: нейтральное «сохранено, ждёт применения», без красного.
 * ```
 *
 * `intent` берётся ТОЛЬКО из readback хранилища (`stored`) и отчёта записи (`saveConfirmed`).
 * `applied` — из статуса действия, и он никогда не переписывает `intent`: сеть не доказала
 * ничего про намерение владельца.
 *
 * Модуль чистый (без Android/JSON): production-путь [ToolsBackend.applyFromUi] и тесты
 * `Iter54ToggleTest` исполняют один и тот же код.
 */
object ToggleIntent {

    /** (b) Подтверждение СОХРАНЕНИЯ намерения. */
    enum class Intent(val wire: String) {
        /** Хранилище после записи вернуло ровно выбранное значение. */
        WRITTEN("written"),

        /** Запись не состоялась ИЛИ хранилище вернуло ДРУГОЕ значение. */
        WRITE_FAILED("write_failed"),

        /** Прочитать хранилище не удалось — это НЕ «не записано» и НЕ «записано». */
        UNKNOWN("unknown");

        companion object {
            fun of(wire: String?): Intent? = values().firstOrNull { it.wire == wire }
        }
    }

    /** (c) Применение side effect. */
    enum class Applied(val wire: String) {
        /** Применено и подтверждено readback самого действия. */
        APPLIED("applied"),

        /** Намерение сохранено, применение НЕ подтверждено (нет uplink/канала/зависимости). */
        PENDING("pending"),

        /** Действие вернуло ошибку. */
        FAILED("failed"),

        /** Сведений о применении нет. */
        UNKNOWN("unknown");

        companion object {
            fun of(wire: String?): Applied? = values().firstOrNull { it.wire == wire }
            fun ofStatus(status: ToolsStep.Status?): Applied = when (status) {
                ToolsStep.Status.OK -> APPLIED
                // PARTIAL/BLOCKED — «не подтверждено», а не «сломано»: ждём сеть/канал/зависимость.
                ToolsStep.Status.PARTIAL, ToolsStep.Status.BLOCKED -> PENDING
                ToolsStep.Status.FAILED -> FAILED
                null -> UNKNOWN
            }
        }
    }

    /**
     * (a)+(b) Факт сохранения намерения.
     *
     * @param want выбранное владельцем значение (`null` — клика не было / не наш ключ)
     * @param stored что реально лежит в хранилище после записи (`null` — прочитать не удалось)
     * @param saveConfirmed отчёт самой записи: `true` — запись сообщила об успехе
     */
    fun intentOf(want: Boolean?, stored: Boolean?, saveConfirmed: Boolean): Intent {
        if (want == null) return Intent.UNKNOWN
        // Хранилище — единственный авторитет: оно отвечает на вопрос «что сохранено».
        if (stored != null) {
            return if (stored == want) {
                // Значение на месте, но запись сообщила о неуспехе => durability не доказана.
                if (saveConfirmed) Intent.WRITTEN else Intent.UNKNOWN
            } else {
                Intent.WRITE_FAILED
            }
        }
        // Прочитать не удалось: отчёт об успехе не превращаем в доказательство, но и неуспех
        // (запись сама сказала «не сохранила») не выдаём за неизвестность.
        return if (saveConfirmed) Intent.UNKNOWN else Intent.WRITE_FAILED
    }

    /** То же для ключей без булевого хранилища (выбор из набора, числа): только отчёт шагов. */
    fun intentOfSteps(persistReportedOk: Boolean?): Intent = when (persistReportedOk) {
        true -> Intent.WRITTEN
        false -> Intent.WRITE_FAILED
        null -> Intent.UNKNOWN
    }

    /**
     * Нейтральная подпись для UI. `unknown` — тоже нейтрально: неизвестность не рисуется
     * красным и не превращается в «выключено».
     */
    fun note(intent: Intent, applied: Applied): String = when {
        intent == Intent.WRITE_FAILED -> "не сохранено (запись не удалась)"
        intent == Intent.UNKNOWN -> "сохранение не подтверждено"
        applied == Applied.PENDING -> "сохранено, ждёт применения"
        applied == Applied.FAILED -> "сохранено, применение не удалось"
        applied == Applied.UNKNOWN -> "сохранено"
        else -> "сохранено и применено"
    }

    /**
     * Нужно ли считать выбор владельца ПОДТВЕРЖДЁННЫМ по снимку.
     *
     * `intent == UNKNOWN` не подтверждает ничего (и не опровергает): держим выбор, пока не
     * истечёт бюджет, и только потом честно называем его неподтверждённым.
     */
    fun confirmsChoice(intent: Intent): Boolean = intent == Intent.WRITTEN
}
