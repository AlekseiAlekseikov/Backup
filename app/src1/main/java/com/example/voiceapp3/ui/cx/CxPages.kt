package com.example.voiceapp3.ui.cx


/**
 * Реестр страниц и панелей — замена `TOOLS_PAGES` и `PANELS` из шелла.
 *
 * Ключи те же, что в `TOOLS_TREE` и `MENU`, поэтому сохранённый выбор раздела
 * и маршрутизация событий не меняются.
 *
 * Пять вкладок дока (`telemetry`, `navi`, `carplay`, `aa`, `apps`) возвращают
 * пустой список: в шелле у них `h:''`, содержимое рисуют нативные хосты.
 * Каркас трактует пустой список как панель-якорь.
 */
class CxPages(private val strings: CxStrings) : CxPageProvider {

    override fun title(pageId: String): String = when (pageId) {
        CxScreenLockSound.PAGE_ID -> CxScreenLockSound.TITLE
        CxScreenDevDiagnostics.PAGE_ID -> CxScreenDevDiagnostics.TITLE
        CxScreenPerformance.PAGE_ID -> CxScreenPerformance.TITLE
        "screen" -> strings.get(com.example.voiceapp3.R.string.cx_page_screen)
        "notifications" -> strings.get(com.example.voiceapp3.R.string.cx_page_notifications)
        "status" -> strings.get(com.example.voiceapp3.R.string.cx_page_status)
        "projection" -> strings.get(com.example.voiceapp3.R.string.cx_page_projection)
        "networkTtl" -> strings.get(com.example.voiceapp3.R.string.cx_page_networkTtl)
        "windows" -> strings.get(com.example.voiceapp3.R.string.cx_page_windows)
        CxPanelQuick.TAB_ID -> CxPanelQuick.TITLE
        CxPanelUpdates.TAB_ID -> CxPanelUpdates.TITLE
        "voice" -> "Голос"
        "service" -> "Сервис"
        "dashcam" -> "Видеорегистратор"
        "telemetry" -> "Телеметрия"
        "navi" -> "Навигация"
        "carplay" -> "CarPlay"
        "aa" -> "Android Auto"
        "apps" -> "Приложения"
        "tools" -> "Инструменты"
        else -> pageId
    }

    override fun rows(pageId: String, state: CxState): List<CxRow> = when (pageId) {
        CxScreenLockSound.PAGE_ID -> CxScreenLockSound.rows(strings)
        CxScreenDevDiagnostics.PAGE_ID -> CxScreenDevDiagnostics.rows()
        CxScreenPerformance.PAGE_ID -> CxScreenPerformance.rows()
        "screen" -> CxScreenInterface.screenRows(strings)
        "notifications" -> CxScreenInterface.notificationsRows(strings)
        "status" -> CxScreenInterface.statusRows(strings)
        "projection" -> CxScreenCar.projectionRows(strings)
        "networkTtl" -> CxScreenCar.networkTtlRows(strings)
        "windows" -> CxScreenCar.windowsRows(strings)
        CxPanelQuick.TAB_ID -> CxPanelQuick.rows()
        CxPanelUpdates.TAB_ID -> CxPanelUpdates.rows(state)

        /* Три панели шелла были макетами: `service` — пять зашитых значений и
           кнопка без обработчика, `dashcam` — статичный SVG вместо видео,
           локальный таймер вместо записи и выдуманный список фрагментов,
           `voice` — кнопки с early-return при наличии моста и выдуманные
           ответы каталога. Переносить имитацию нельзя, поэтому панели
           возвращают пустой список до решения заказчика: убрать их из дока,
           оставить только строки с реальным источником или реализовать
           бэкенд. Пустой список каркас трактует как панель-якорь, то есть
           вкладка откроется пустой, а не упадёт. */
        "service", "dashcam", "voice" -> emptyList()

        /* Панели-якоря: содержимое рисуют TelemetryHostController,
           NavigationHostController и ProjectionRenderBridge. */
        "telemetry", "navi", "carplay", "aa", "apps", "tools" -> emptyList()

        else -> emptyList()
    }

    companion object {
        /**
         * Страница, открываемая по умолчанию. В шелле это первый узел дерева
         * «Инструментов» с собственной страницей.
         */
        const val DEFAULT_TOOL = "screen"
        const val DEFAULT_TAB = "quick"
    }
}
