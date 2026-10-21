package com.example.voiceapp3.ui.cx

/**
 * Карточка машины — перенос `section.card.carCard` из шелла.
 *
 * Живёт в левой колонке основного макета на ВСЕХ вкладках, а не только на
 * «Быстром доступе»: в шелле это отдельный `<section>` рядом с `#content`.
 *
 * Значения приходят из `telemetry.live` (порт `LiveTripVhalReadings`):
 * `ODOMETER`, `HV_BATTERY_SOC`, `BCM_HOOD_STATUS`, `BODY_DOOR_TRUNK_DOOR_POS`,
 * `VEHIC_RKE_LOCK_FEEDBACK`, `EV_CHARGE_PORT_CONNECTED` и др.
 */
object CxCarCard {

    /** Ключи снимка состояния — имена полей `telemetry.live` после разбора. */
    const val KEY_RANGE_KM = "rangeKm"
    const val KEY_BATTERY_PCT = "batteryPct"
    const val KEY_BATTERY_LIMIT = "batteryLimitPct"
    const val KEY_HOOD = "hood"
    const val KEY_TRUNK = "trunk"
    const val KEY_LOCK = "lockFeedback"
    const val KEY_CHARGING = "charging"
    const val KEY_OUT_TEMP = "outTempC"
    const val KEY_PARKED = "parked"

    /** Ключ SharedPreferences, куда `UiBridge` пишет выбор цвета (строка 442-448). */
    const val PREF_CAR_PAINT = "pref_car_paint"

    /**
     * Каталог `CNAMES` и цвета образцов из инлайновых стилей шелла.
     * Порядок сохранён: beige, green, grey, pink, silver, white.
     */
    val PAINTS: List<CxPaint> = listOf(
        CxPaint("beige", "Бежевый", 0xFFE7DCC2.toInt()),
        CxPaint("green", "Зелёный", 0xFFB4D2A4.toInt()),
        CxPaint("grey", "Серый", 0xFF9AA0A4.toInt()),
        CxPaint("pink", "Розовый", 0xFFF2C6CD.toInt()),
        CxPaint("silver", "Серебристый", 0xFFCBD0D4.toInt()),
        CxPaint("white", "Белый", 0xFFF7F7F7.toInt()),
    )

    /** Событие выбора цвета: `emit('car.color', {color: data-c})`. */
    const val ACT_COLOR = "car.color"

    /** События капота и багажника: `emit('car.hood'|'car.trunk', {open: Bool})`. */
    const val ACT_HOOD = "car.hood"
    const val ACT_TRUNK = "car.trunk"

    /**
     * СОБЫТИЕ `car.charging` МЕРТВО — не переносится.
     *
     * В шелле полоса батареи имела `title="Нажмите — симуляция зарядки"` и
     * обработчик `else if(el.id === 'battBar') emit('car.charging',
     * {charging: el.classList.contains('charging')})`. В `UiBridge.kt` ветки
     * `"car.charging"` в `when` НЕТ: упоминание встречается один раз, в
     * заголовочном комментарии-таблице маршрутов (строка 48, «ограничения
     * заряда / ток (car/Ex2Vehicle)»). Комментарий расходится с кодом; по
     * правилу «код — истина» событие никуда не уходит.
     *
     * Полоса заряда поэтому перенесена как индикатор без обработчика нажатия.
     */
    const val ACT_CHARGING_DEAD = "car.charging"

    fun paintById(id: String): CxPaint? = PAINTS.firstOrNull { it.id == id }

    /** Подпись состояния капота/багажника — как `<b>закрыт</b>` в шелле. */
    fun latchLabel(open: Boolean): String = if (open) "открыт" else "закрыт"

    /** Индикатор замка — порт `TEL_LIVE.lockFeedback !== 0 ? 🔒 : 🔓`. */
    fun lockGlyph(feedback: Int): String = if (feedback != 0) "\uD83D\uDD12" else "\uD83D\uDD13"
}

/**
 * Цвет кузова.
 *
 * @property id    значение `data-c`, оно же пишется в `pref_car_paint`
 * @property title подпись из `CNAMES`
 * @property color цвет образца из инлайнового `style="background:…"`
 */
data class CxPaint(val id: String, val title: String, val color: Int)
