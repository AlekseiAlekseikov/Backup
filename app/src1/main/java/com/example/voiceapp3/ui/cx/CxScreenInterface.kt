package com.example.voiceapp3.ui.cx

import com.example.voiceapp3.R

/**
 * Страницы группы «Интерфейс» — перенос `cxPageScreen()`,
 * `cxPageNotifications()` и `cxPageStatus()`.
 *
 * Тексты берутся из строкового каталога `CX_S` (`strings_cx.xml`), состав и
 * порядок строк совпадают с исходными генераторами.
 */
object CxScreenInterface {

    // ── «Экран» ─────────────────────────────────────────────────────────

    const val PAGE_SCREEN = "screen"

    fun screenRows(strings: CxStrings): List<CxRow> {
        val r = ArrayList<CxRow>()
        r.add(CxRow.Section(strings.get(R.string.cx_page_sectionScreen)))
        r.add(CxRow.Toggle("imm", "Полный экран",
            strings.get(R.string.cx_desc_imm), def = true))
        r.add(CxRow.Toggle("wake", "Запуск при пробуждении",
            strings.get(R.string.cx_desc_wake)))
        r.add(CxRow.Toggle("cb", "Полоса климата",
            strings.get(R.string.cx_desc_cb)))
        r.add(CxRow.Slider("bright", "Яркость", min = 5, max = 100,
            unit = "%", def = 50, wide = true))
        r.add(CxRow.Toggle("remember", "Запоминать яркость по фарам",
            strings.get(R.string.cx_desc_remember)))
        r.add(CxRow.Segmented("rest", "Время заставки", def = "0", widthDp = 420,
            opts = listOf("0" to "Выкл", "10" to "10 с", "30" to "30 с",
                "60" to "1 мин", "120" to "2 мин")))
        r.add(CxRow.Section(strings.get(R.string.cx_page_sectionTheme)))
        r.add(CxRow.Segmented("gu", strings.get(R.string.cx_page_themeHu),
            def = "light", widthDp = 260,
            opts = listOf("light" to "Светлая", "dark" to "Тёмная")))
        r.add(CxRow.Toggle("follow", "Следовать теме ГУ",
            strings.get(R.string.cx_desc_follow), def = true))
        r.add(CxRow.Info("themeApp", strings.get(R.string.cx_page_themeApp),
            valueFrom = { strings.get(R.string.cx_page_themeAppHint) }))
        r.add(CxRow.ThemeTiles())
        return r
    }

    // ── «Панель уведомлений» ────────────────────────────────────────────

    const val PAGE_NOTIFICATIONS = "notifications"

    fun notificationsRows(strings: CxStrings): List<CxRow> = listOf(
        CxRow.Section(strings.get(R.string.cx_page_sectionNotifications)),
        CxRow.Toggle("notifBatt", "Батарея автомобиля",
            strings.get(R.string.cx_desc_notifBatt)),
        CxRow.Toggle("notifTemp", "Наружная температура",
            strings.get(R.string.cx_desc_notifTemp)),
        CxRow.Toggle("notifWifi", "Wi‑Fi",
            strings.get(R.string.cx_desc_notifWifi)),
        CxRow.Info("notifHelp", strings.get(R.string.cx_page_notifHelpTitle),
            valueFrom = { strings.get(R.string.cx_page_notifHelpText) }),
    )

    // ── «Строка состояния» ──────────────────────────────────────────────

    const val PAGE_STATUS = "status"

    fun statusRows(strings: CxStrings): List<CxRow> = listOf(
        CxRow.Section(strings.get(R.string.cx_page_sectionStatus)),
        CxRow.Toggle("hvacBatt", "Процент заряда",
            strings.get(R.string.cx_desc_hvacBatt)),
        CxRow.Toggle("hvacTemp", "Цифровой климат-контроль",
            strings.get(R.string.cx_desc_hvacTemp)),
        CxRow.Toggle("hvacExtTemp", "Цифровая наружная температура",
            strings.get(R.string.cx_desc_hvacExtTemp)),
        CxRow.Info("statusHelp", strings.get(R.string.cx_page_statusHelpTitle),
            valueFrom = { strings.get(R.string.cx_page_statusHelpText) }),
    )
}
