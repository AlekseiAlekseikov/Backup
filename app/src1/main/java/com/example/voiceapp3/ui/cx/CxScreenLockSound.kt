package com.example.voiceapp3.ui.cx

import com.example.voiceapp3.R

/**
 * Страница «Звуки замка (открытие и закрытие)» — перенос `cxPageLockSound()`.
 *
 * Состав, порядок и тексты строк сохранены один в один. Действия кнопок идут
 * под теми же именами, что и в шелле, поэтому маршрутизация в нативной части
 * (`audio.*` — отдельным событием, `dev.*` — с необязательным `value`,
 * остальное — как `tools.action`) не меняется.
 *
 * Значения строк только для чтения берутся из снимка состояния, а не из
 * локальных переменных экрана: `unlockSoundName`, `lockSoundName`,
 * `lockTriggerStatus`, `lockRoute`, `availableSounds`. Это то самое требование
 * «показывать выбор по чтению из реестра, а не по состоянию интерфейса».
 */
object CxScreenLockSound {

    const val PAGE_ID = "lockSound"

    /** Заголовок страницы — в шелле задан литералом в `cxPageHead`. */
    const val TITLE = "Звуки замка (открытие и закрытие)"

    private const val UNLOCK_DEFAULT_NAME = "По умолчанию (заводской)"
    private const val LOCK_DEFAULT_NAME = "Заводской (Geely OEM)"
    private const val TRIGGER_DEFAULT = "триггер не запущен"
    private const val ROUTE_DEFAULT = "маршрут не определён"
    private const val USB_HINT =
        "Вставьте флешку с файлами .wav, .mp3 или .ogg и нажмите «Копировать»"
    private const val UNLOCK_DESC =
        "Играет при снятии машины с охраны: источник — свойство центрального " +
            "замка, а не состояние дверей. Первое значение после подписки " +
            "считается базовым, повтор состояния звука не даёт."
    private const val EDGE_CHECK_DESC =
        "Значения уходят в тот же тракт, что и реальное событие свойства: " +
            "первое — базовое (звук не играет), переход играет, повтор того же " +
            "значения не играет."

    fun rows(strings: CxStrings): List<CxRow> {
        val r = ArrayList<CxRow>()

        // Плашка статуса длительной операции (#cx-sound-status-banner)
        r.add(CxRow.Banner { s -> s.string(CxPage.SOUND_MSG, "") })

        // ── Блок 0: копирование с USB ────────────────────────────────────
        r.add(CxRow.Section("Копирование аудио с USB-накопителя"))
        r.add(CxRow.Info(
            key = "copyUsb",
            title = "USB-накопитель",
            valueFrom = { USB_HINT },
            note = true,
            action = CxRow.Button("audio.copyFromUsb", "КОПИРОВАТЬ", kind = "accent"),
        ))

        // ── Блок 1: разблокировка (открытие дверей) ──────────────────────
        r.add(CxRow.Section("Звук разблокировки (снятие с охраны)"))
        r.add(CxRow.Toggle(
            key = "unlockSound",
            title = "Звук открытия / разблокировки",
            desc = UNLOCK_DESC,
            def = false,
        ))
        r.add(CxRow.Info(
            key = "unlockSoundFile",
            title = "Звук открытия",
            valueFrom = { s -> nameOf(s, "unlockSoundName", "unlockSoundPath", UNLOCK_DEFAULT_NAME) },
            note = true,
        ))
        r.add(CxRow.Info(
            key = "lockTrigger",
            title = "Сигнал замка (источник события)",
            valueFrom = { s -> s.string("lockTriggerStatus", TRIGGER_DEFAULT) },
            note = true,
        ))
        r.add(CxRow.Info(
            key = "lockRoute",
            title = "Маршрут вывода",
            valueFrom = { s -> s.string("lockRoute", ROUTE_DEFAULT) },
        ))
        r.add(CxRow.Buttons(listOf(
            CxRow.Button("dev.probeLockSignal", "ПОДОБРАТЬ СИГНАЛ", kind = "accent"),
            CxRow.Button("dev.probeLockResult", "РЕЗУЛЬТАТ ПОДБОРА"),
            CxRow.Button("dev.lockStatus", "СОСТОЯНИЕ ТРИГГЕРА"),
            CxRow.Button("dev.lockLog", "ЛОГ ПЕРЕХВАТА"),
        )))
        r.add(CxRow.Info(
            key = "edgeCheck",
            title = "Проверка фильтра по фронту",
            valueFrom = { EDGE_CHECK_DESC },
        ))
        r.add(CxRow.Buttons(listOf(
            CxRow.Button("dev.injectLockEvent", "ПРОВЕРКА: ЗАКРЫТО (0)", inject = "0"),
            CxRow.Button("dev.injectLockEvent", "ПРОВЕРКА: ОТКРЫТО (1)", inject = "1"),
        )))
        r.add(CxRow.Select(
            key = "unlockSoundFile",
            title = "Список скопированных звуков",
            subtitleFrom = { s -> "Доступно: " + s.list("availableSounds").size + " файлов" },
            optionsFrom = { s -> s.list("availableSounds") },
            selectedFrom = { s -> nameOf(s, "unlockSoundName", "unlockSoundPath", "") },
        ))
        r.add(CxRow.Buttons(listOf(
            CxRow.Button("audio.selectUnlockFile", "ВЫБРАТЬ ИЗ ФАЙЛОВ", kind = "accent"),
            CxRow.Button("audio.previewUnlock", "ПРОСЛУШАТЬ"),
            CxRow.Button("audio.restoreFactoryUnlock", "СБРОС К ЗАВОДСКОМУ", kind = "danger"),
        )))

        // ── Блок 2: блокировка (закрытие) ────────────────────────────────
        r.add(CxRow.Section("Звук блокировки замка (закрытие)"))
        r.add(CxRow.Toggle(
            key = "lockSound",
            title = "Кастомный звук замка",
            desc = strings.get(R.string.cx_desc_lockSound),
            def = false,
        ))
        r.add(CxRow.Info(
            key = "lockSoundFile",
            title = "Выбранный звук",
            valueFrom = { s -> nameOf(s, "lockSoundName", "lockSoundPath", LOCK_DEFAULT_NAME) },
            note = true,
        ))
        r.add(CxRow.Select(
            key = "lockSoundFile",
            title = "Список скопированных звуков",
            subtitleFrom = { s -> "Доступно: " + s.list("availableSounds").size + " файлов" },
            optionsFrom = { s -> s.list("availableSounds") },
            selectedFrom = { s -> nameOf(s, "lockSoundName", "lockSoundPath", "") },
        ))
        r.add(CxRow.Buttons(listOf(
            CxRow.Button("audio.selectFile", "ВЫБРАТЬ ИЗ ФАЙЛОВ", kind = "accent"),
            CxRow.Button("audio.preview", "ПРОСЛУШАТЬ"),
            CxRow.Button("audio.restaurarSomTravaPersistente", "СБРОС К ЗАВОДСКОМУ", kind = "danger"),
        )))

        // ── Блок 3: громкость и задержка ─────────────────────────────────
        r.add(CxRow.Section("Настройки громкости и задержки"))
        r.add(CxRow.Slider(
            key = "lockSoundVol", title = "Громкость",
            min = 5, max = 100, unit = "%", def = 80, wide = true,
        ))
        r.add(CxRow.Segmented(
            key = "lockSoundDelay", title = "Задержка звука", def = "0", widthDp = 360,
            opts = listOf("0" to "0 мс", "150" to "150 мс", "300" to "300 мс"),
        ))
        r.add(CxRow.Info(
            key = "lockSoundHelp",
            title = strings.get(R.string.cx_page_lockSoundHelpTitle),
            valueFrom = { strings.get(R.string.cx_page_lockSoundHelpText) },
        ))
        return r
    }

    /**
     * Имя выбранного файла — аналог выражения из шелла: сначала `*SoundName`,
     * иначе basename из `*SoundPath`, иначе подпись по умолчанию.
     */
    private fun nameOf(s: CxState, nameKey: String, pathKey: String, def: String): String {
        s.string(nameKey, "").takeIf { it.isNotEmpty() }?.let { return it }
        val p = s.string(pathKey, "")
        if (p.isNotEmpty()) return p.substringAfterLast('/')
        return def
    }
}
