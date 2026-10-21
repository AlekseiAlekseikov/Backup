package com.example.voiceapp3.ui.cx

import com.example.voiceapp3.R

/**
 * Страницы группы «Автомобиль» и «Климат» — перенос `cxPageProjection()`,
 * `cxPageNetworkTtl()` и `cxPageWindows()`.
 */
object CxScreenCar {

    // ── «CarPlay / Android Auto» ────────────────────────────────────────

    const val PAGE_PROJECTION = "projection"

    /* Ключи снимка состояния для присутствия проекции. В шелле это был объект
       `PROJ`, который наполнялся событием `projection.presence`; здесь слой
       обвязки обязан класть те же четыре значения в снимок. */
    const val PROJ_CARPLAY = "projCarplay"
    const val PROJ_ANDROID_AUTO = "projAndroidAuto"
    const val PROJ_CP_AVAIL = "projCpAvail"
    const val PROJ_AA_AVAIL = "projAaAvail"

    private const val PROJ_UNKNOWN = "unknown"
    private const val TABS_TITLE = "Вкладки подключения"
    private const val TABS_TEXT =
        "Вкладка подключённого телефона появляется в нижнем доке " +
            "автоматически; здесь настраивается только доступность стека."

    /** Порт `cpLabel(s)` — подписи состояний стека. */
    fun projectionLabel(s: String): String = when (s) {
        "connected" -> "подключено"
        "advertising" -> "ожидание подключения"
        "disconnected" -> "не подключено"
        "unavailable" -> "недоступно на этом ГУ"
        else -> "нет данных"
    }

    fun projectionRows(strings: CxStrings): List<CxRow> = listOf(
        CxRow.Toggle("proj", "CarPlay / Android Auto (бета)",
            strings.get(R.string.cx_desc_proj)),
        CxRow.Info("projCarplayRow", "CarPlay",
            valueFrom = { "" },
            pillFrom = { st ->
                val v = st.string(PROJ_CARPLAY, PROJ_UNKNOWN)
                projectionLabel(v) to if (v == "connected") "ok" else null
            }),
        CxRow.Info("projAaRow", "Android Auto",
            valueFrom = { "" },
            pillFrom = { st ->
                val v = st.string(PROJ_ANDROID_AUTO, PROJ_UNKNOWN)
                projectionLabel(v) to if (v == "connected") "ok" else null
            }),
        CxRow.Info("projTabs", TABS_TITLE, valueFrom = { TABS_TEXT },
            pillFrom = { st ->
                val bound = st.bool(PROJ_CP_AVAIL, false) || st.bool(PROJ_AA_AVAIL, false)
                (if (bound) "телефон подключён" else "нет подключённого телефона") to
                    if (bound) "ok" else null
            }),
    )

    // ── «Фиксация TTL» ──────────────────────────────────────────────────

    const val PAGE_NETWORK_TTL = "networkTtl"

    fun networkTtlRows(strings: CxStrings): List<CxRow> = listOf(
        CxRow.Section(strings.get(R.string.cx_page_sectionTtl)),
        CxRow.Toggle("ttl65", "Фиксация TTL 65 (обход раздачи)",
            strings.get(R.string.cx_desc_ttl65)),
        CxRow.Info("ttlHelp", strings.get(R.string.cx_page_ttlHelpTitle),
            valueFrom = { strings.get(R.string.cx_page_ttlHelpText) }),
    )

    // ── «Окна и двери» ──────────────────────────────────────────────────

    const val PAGE_WINDOWS = "windows"

    fun windowsRows(strings: CxStrings): List<CxRow> = listOf(
        CxRow.Section(strings.get(R.string.cx_page_sectionWindowsVent)),
        CxRow.Toggle("windowDriverOnDoor", "Стекло водителя при открытии двери",
            strings.get(R.string.cx_desc_windowDriverOnDoor)),
        CxRow.Toggle("windowOppositeOnDoor", "Противоположное стекло",
            strings.get(R.string.cx_desc_windowOppositeOnDoor)),
        CxRow.Info("windowsHelp", strings.get(R.string.cx_page_windowsHelpTitle),
            valueFrom = { strings.get(R.string.cx_page_windowsHelpText) }),
    )
}
