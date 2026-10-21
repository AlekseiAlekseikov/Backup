package com.example.voiceapp3.ui.cx

/**
 * Панель «Обновления» — перенос `updView()` / `updLiveView()` / `updZoneHTML()`.
 *
 * ВАЖНО про исходник. Статичная разметка `PANELS.updates` с зашитыми «EX2 ·
 * v1.1.0 доступно», «Текущая версия v1.0.16» и «Размер обновления 1,2 ГБ» —
 * это демо-макет ТОЛЬКО для браузера: `updView()` начинается с
 * `if(!window.GeelyNative) return PANELS.updates.h`. В приложении работает
 * `updLiveView()`, который целиком строится из состояния `UPD`, наполняемого
 * событием `update.state` от `AppUpdater` (репозиторий
 * AlekseiAlekseikov/GeelyTools, ассет app-platform-signed.apk, докачка,
 * проверка подписи, PackageInstaller).
 *
 * Поэтому здесь перенесён живой вариант, а не демо-разметка: значения берутся
 * из снимка состояния, а состав строк зависит от фазы — как в `updZoneHTML()`.
 * Перерисовка на смену фазы соответствует `updPaint()`.
 */
object CxPanelUpdates {

    const val TAB_ID = "updates"
    const val TITLE = "Обновления"
    const val SUBTITLE = "ПО автомобиля"

    /** Ключи снимка состояния — поля события `update.state`. */
    const val KEY_PHASE = "updPhase"
    const val KEY_CURRENT = "updCurrent"
    const val KEY_VERSION = "updVersion"
    const val KEY_CHANGELOG = "updChangelog"
    const val KEY_PERCENT = "updPercent"
    const val KEY_MSG = "updMsg"
    /** Текущая версия приложения — в шелле `GeelyNative.appVersion()`. */
    const val KEY_APP_VERSION = "appVersion"

    /** Фазы из `UPD.state`; значения совпадают со строками в шелле. */
    const val PHASE_IDLE = "idle"
    const val PHASE_CHECKING = "checking"
    const val PHASE_AVAILABLE = "available"
    const val PHASE_DOWNLOADING = "downloading"
    const val PHASE_UPTODATE = "uptodate"
    const val PHASE_ERROR = "error"

    private const val FALLBACK_VERSION = "1.0.16"

    /** Действия — те же имена событий, что слал шелл. */
    const val ACT_CHECK = "update.check"
    const val ACT_INSTALL = "update.install"

    /**
     * Строки панели по текущему снимку.
     *
     * Порядок соответствует `updLiveView()`: карточка версии, затем зона,
     * состояние которой определяется фазой, затем тумблер автообновления.
     */
    fun rows(state: CxState): List<CxRow> {
        val phase = state.string(KEY_PHASE, PHASE_IDLE)
        val cur = state.string(KEY_CURRENT, "")
        val ver = state.string(KEY_VERSION, "")
        val appVer = state.string(KEY_APP_VERSION, FALLBACK_VERSION)
        val r = ArrayList<CxRow>()

        // updHeadHTML(): «EX2 · v<версия>» + подпись источника
        r.add(CxRow.Info(
            key = "updHead",
            title = "EX2 · v" + appVer,
            valueFrom = { "Обновления — GitHub Releases" },
        ))

        when (phase) {
            PHASE_CHECKING -> r.add(CxRow.Buttons(listOf(
                CxRow.Button(ACT_CHECK, "Проверяю…"),
            )))

            PHASE_AVAILABLE -> {
                val notes = state.string(KEY_CHANGELOG, "")
                if (notes.isNotEmpty()) {
                    r.add(CxRow.Section("Что нового"))
                    r.add(CxRow.Info("updNotes", "", valueFrom = { notes }))
                }
                r.add(CxRow.Info("updCur", "Текущая версия",
                    valueFrom = { "v$appVer" }))
                r.add(CxRow.Info("updNew", "Новая версия",
                    valueFrom = { "v$ver" }))
                r.add(CxRow.Buttons(listOf(
                    CxRow.Button(ACT_INSTALL, "Установить сейчас", kind = "accent"),
                )))
            }

            PHASE_DOWNLOADING -> {
                val pct = state.int(KEY_PERCENT, 0).coerceIn(0, 99)
                val msg = state.string(KEY_MSG, "Скачивание…")
                r.add(CxRow.Info("updPct", "$pct%", valueFrom = { msg }))
                r.add(CxRow.Progress("updProgress",
                    valueFrom = { st -> st.int(KEY_PERCENT, 0) }))
                r.add(CxRow.Buttons(listOf(
                    CxRow.Button(ACT_CHECK, "Проверить обновление"),
                )))
            }

            PHASE_UPTODATE -> {
                r.add(CxRow.Info("updOk", "Обновлений нет",
                    valueFrom = { if (cur.isNotEmpty()) cur else "Установлена последняя версия" }))
                r.add(CxRow.Buttons(listOf(
                    CxRow.Button(ACT_CHECK, "Проверить обновление"),
                )))
            }

            PHASE_ERROR -> {
                r.add(CxRow.Info("updErr", "Ошибка проверки",
                    valueFrom = { state.string(KEY_MSG, "Не удалось получить список выпусков") }))
                r.add(CxRow.Buttons(listOf(
                    CxRow.Button(ACT_CHECK, "Повторить", kind = "accent"),
                )))
            }

            else -> r.add(CxRow.Buttons(listOf(
                CxRow.Button(ACT_CHECK, "Проверить обновление", kind = "accent"),
            )))
        }

        /* Тумблер автообновления в шелле нёс только data-n (подпись для тоста)
           и не имел ключа настройки, то есть никуда не писал. Здесь ключ задан
           явно: control без записи был бы неактивным, а это запрещено. */
        r.add(CxRow.Toggle("autoUpdate", "Автообновление", def = true))
        return r
    }
}
