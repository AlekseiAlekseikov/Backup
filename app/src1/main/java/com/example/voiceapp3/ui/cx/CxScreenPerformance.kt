package com.example.voiceapp3.ui.cx

import android.content.Context

/**
 * Страница «Быстродействие и фоновая работа» — перенос `cxPagePerformance()`.
 *
 * Заголовок в шелле задан литералом, а не через `CX_S.page.perf` (там другое
 * значение, «Быстродействие»), поэтому литерал сохранён.
 */
object CxScreenPerformance {

    const val NODE_ID = "perf"
    const val PAGE_ID = "performance"
    const val TITLE = "Быстродействие и фоновая работа"

    private const val KEEP_ALIVE_TITLE = "Всегда активные приложения (Keep-Alive)"
    private const val KEEP_ALIVE_DESC =
        "Не даёт системе закрывать отмеченные приложения в фоне при потере " +
            "фокуса (например, Spotify во время воспроизведения музыки)."

    private const val VIDEO_DESC =
        "Меняет декодер видео до следующей перезагрузки; после запуска " +
            "возвращается сам. Заметно улучшает воспроизведение видео с " +
            "YouTube в 1080p 60fps."
    private const val JIT_DESC =
        "Магнитола запоминает, как вы пользуетесь приложениями, и через " +
            "несколько минут после каждого запуска сама оптимизирует их для " +
            "ускорения работы."

    /** Действие открытия модального окна списка — `cxOpenKeepAliveModal()`. */
    const val ACT_MANAGE_KEEP_ALIVE = "perf.manageKeepAlive"

    fun rows(): List<CxRow> = listOf(
        CxRow.Toggle("perfVideo", "Оптимизация видео (1080p 60fps)", VIDEO_DESC),
        CxRow.Toggle("perfJit",
            "Учиться и оптимизировать самостоятельно (JIT)", JIT_DESC),
        CxRow.Info(
            key = "keepAlive",
            title = KEEP_ALIVE_TITLE,
            valueFrom = { KEEP_ALIVE_DESC },
            action = CxRow.Button(
                action = ACT_MANAGE_KEEP_ALIVE,
                label = "Управление списком ›",
                kind = "ok",
                /* ОТСТУПЛЕНИЕ ОТ ИСХОДНИКА. В шелле было
                   `(... || []).length || 4`: пустой список даёт 0, а `0 || 4`
                   в JavaScript — это 4, то есть при пустом списке кнопка
                   показывала «(4)». Здесь показывается фактическое число,
                   потому что подпись обязана соответствовать содержимому. */
                labelFrom = { st ->
                    "Управление списком (" + st.list("keepAliveApps").size + ") ›"
                },
            ),
        ),
    )
}
