package com.example.voiceapp3.ui.cx

/**
 * Панель «Быстрый доступ» — перенос `PANELS.quick`.
 *
 * Форма событий и состав payload взяты из обработчика клика шелла
 * (делегирование на `.mode`, `.air`, `.heat`, `.seat`, `.seg[data-g="r"]`):
 *
 *   .mode             -> drive.mode    {mode}
 *   .seg[data-g="r"]  -> drive.recup   {value}
 *   .air              -> climate.air   {value, on}
 *   .air.tog          -> climate.recirc {value, on}
 *   .heat             -> climate.heat  {name, on}
 *   .seat             -> climate.seat  {name, level}
 *
 * Иконки — VectorDrawable, сгенерированные из инлайновых SVG шелла
 * (`res/drawable/cx_ic_*.xml`): viewBox 24×24, обводка 1.8, концы скруглены.
 *
 * ОТСТУПЛЕНИЕ ОТ ИСХОДНИКА: подзаголовок панели в шелле — «Режим движения и
 * телефон», но раздела про телефон в разметке нет. Подзаголовок приведён к
 * фактическому содержимому.
 */
object CxPanelQuick {

    const val TAB_ID = "quick"
    const val TITLE = "Быстрый доступ"
    const val SUBTITLE = "Режим движения и климат"

    /** Ключи снимка состояния для подсветки выбора. */
    const val KEY_DRIVE_MODE = "driveMode"
    const val KEY_DRIVE_RECUP = "driveRecup"
    const val KEY_CLIMATE_AIR = "climateAir"
    const val KEY_CLIMATE_RECIRC = "climateRecirc"
    const val KEY_HEAT = "climateHeat"
    const val KEY_SEAT = "climateSeat"

    fun rows(): List<CxRow> = listOf(
        CxRow.Section("Режим движения"),
        CxRow.TileGrid(
            key = "modes",
            kind = CxTileKind.DRIVE_MODE,
            selectedFrom = { s -> setOf(s.string(KEY_DRIVE_MODE, "Комфорт")) },
            tiles = listOf(
                CxTile(value = "Эко", label = "Эко", emoji = "🌱", sub = "max запас"),
                CxTile(value = "Комфорт", label = "Комфорт", emoji = "☁", sub = "баланс"),
                CxTile(value = "Спорт", label = "Спорт", emoji = "⚡", sub = "динамика"),
            ),
        ),
        CxRow.Section("Рекуперация"),
        CxRow.Segmented(
            key = KEY_DRIVE_RECUP,
            title = "",
            def = "Средняя",
            widthDp = 0,
            opts = listOf("Низкая" to "Низкая", "Средняя" to "Средняя",
                "Высокая" to "Высокая"),
        ),
        CxRow.Section("Направление обдува"),
        CxRow.TileGrid(
            key = "air",
            kind = CxTileKind.CLIMATE_AIR,
            selectedFrom = { s ->
                val out = LinkedHashSet<String>()
                s.string(KEY_CLIMATE_AIR, "").takeIf { it.isNotEmpty() }?.let { out.add(it) }
                if (s.bool(KEY_CLIMATE_RECIRC, false)) out.add("Рециркуляция")
                out
            },
            tiles = listOf(
        CxTile(value = "Лицо", label = "Лицо", drawable = "cx_ic_face"),
        CxTile(value = "Ноги", label = "Ноги", drawable = "cx_ic_feet"),
        CxTile(value = "Лицо+Ноги", label = "Лицо+Ноги", drawable = "cx_ic_face_feet"),
        CxTile(value = "Стекло", label = "Стекло", drawable = "cx_ic_defrost"),
        CxTile(value = "Ноги+Стекло", label = "Ноги+Стекло", drawable = "cx_ic_feet_defrost"),
        CxTile(value = "Рециркуляция", label = "Рециркуляция", drawable = "cx_ic_recirc", toggle = true),
            ),
        ),
        CxRow.Section("Обогрев"),
        CxRow.TileGrid(
            key = "heat",
            kind = CxTileKind.CLIMATE_HEAT,
            selectedFrom = { s -> s.list(KEY_HEAT).toSet() },
            tiles = listOf(
        CxTile(value = "Руль", label = "Руль", drawable = "cx_ic_wheel"),
        CxTile(value = "Лобовое стекло", label = "Лобовое", drawable = "cx_ic_windshield"),
        CxTile(value = "Заднее стекло", label = "Заднее", drawable = "cx_ic_rear"),
        CxTile(value = "Стекло Макс", label = "Стекло Макс", drawable = "cx_ic_defrost_max"),
        CxTile(value = "Сиденье водителя", label = "Сиденье Вод.", drawable = "cx_ic_seat_driver", levels = 3, kindOverride = CxTileKind.CLIMATE_SEAT),
        CxTile(value = "Сиденье пассажира", label = "Сиденье Пасс.", drawable = "cx_ic_seat_passenger", levels = 3, kindOverride = CxTileKind.CLIMATE_SEAT),
            ),
        ),
    )
}
