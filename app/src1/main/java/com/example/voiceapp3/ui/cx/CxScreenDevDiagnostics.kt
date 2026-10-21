package com.example.voiceapp3.ui.cx

/**
 * Страница «Диагностика разработчика» — перенос `cxPageDevDiagnostics()`.
 *
 * В шелле узел дерева назывался `dev`, а страница — `devDiagnostics`; оба
 * идентификатора сохранены.
 *
 * Три блока: интерактивная shell/ADB-консоль, проверка звука замка
 * (маршрут, лог перехвата, фронт) и журнал приложения.
 */
object CxScreenDevDiagnostics {

    const val NODE_ID = "dev"
    const val PAGE_ID = "devDiagnostics"
    const val TITLE = "Диагностика разработчика"

    private const val CMD_HINT = "например: iptables -t mangle -L POSTROUTING -v -n"
    private const val CMD_CAPTION =
        "Выполнить команду напрямую в ADB root (сокет 5555) или Shell:"
    private const val CMD_PLACEHOLDER = "Готов к вводу команд..."
    private const val LOG_PLACEHOLDER =
        "Нажмите «ПОКАЗАТЬ ПОСЛЕДНИЙ LOGCAT» для чтения журнала..."

    private const val HOW_TITLE = "Как проверяется"
    private const val HOW_TEXT =
        "«Маршрут» играет выбранный файл тем же путём, каким его сыграет " +
            "триггер замка, и возвращает фактический результат " +
            "setPreferredDevice. «Лог перехвата» показывает каждое событие: " +
            "значение, previous, принято или отклонено и почему."
    private const val EDGE_TITLE = "Проверка фильтра по фронту"
    private const val EDGE_TEXT =
        "Значения уходят в тот же тракт, что и реальное событие свойства: " +
            "первое — базовое (звук не играет), переход играет, повтор того же " +
            "значения не играет."

    /** Быстрые команды — дословно из шелла, порядок сохранён. */
    private val QUICK: List<Pair<String, String>> = listOf(
        "Проверить TTL" to "iptables -t mangle -L POSTROUTING -v -n",
        "Белый список Keep-Alive" to "dumpsys deviceidle whitelist",
        "Статус JIT & Модель" to
            "getprop | grep -E \"dalvik.vm.usejit|ro.product.model\"",
        "Файлы звуков" to "ls -laZ /data/local/tmp/sons/ /system/etc/avas/ 2>&1",
        "Ключи замка" to
            "settings get system lock_car_audio_path; " +
                "settings get system lock_car_audio_enable; " +
                "settings get system geelytools_lock_prop",
        "Кто читает ключ" to
            "grep -rl lock_car_audio /system/framework /system/priv-app " +
                "/system/app /vendor/app 2>/dev/null | head",
        "Процессы" to "ps -ef | grep -E \"mediacodec|voiceapp3\"",
    )

    fun rows(): List<CxRow> {
        val r = ArrayList<CxRow>()

        // ── Блок 1: интерактивная консоль ────────────────────────────────
        r.add(CxRow.Section("Интерактивный Shell & ADB консоль"))
        r.add(CxRow.CommandInput(
            key = "devCmd",
            hint = CMD_HINT,
            caption = CMD_CAPTION,
            runAction = "dev.runCommand",
            valueField = "command",
            quick = QUICK,
        ))
        r.add(CxRow.Console(
            key = "devCmdOut",
            placeholder = CMD_PLACEHOLDER,
            textFrom = { s -> s.string(CxPage.DEV_CMD_OUTPUT, "") },
            textColor = CxPage.CONSOLE_CMD_FG,
            maxHeightDp = 240,
        ))

        // ── Блок 2: звук замка ───────────────────────────────────────────
        r.add(CxRow.Section("Звук замка: маршрут, лог перехвата, проверка фронта"))
        r.add(CxRow.Info(key = "devHow", title = HOW_TITLE, valueFrom = { HOW_TEXT }))
        r.add(CxRow.Buttons(listOf(
            CxRow.Button("dev.testUnlockSound", "Проверить маршрут звука"),
            CxRow.Button("dev.lockLog", "Лог перехвата замка"),
            CxRow.Button("dev.lockStatus", "Состояние триггера"),
            CxRow.Button("dev.probeLockSignal", "Подобрать сигнал (15 с)"),
            CxRow.Button("dev.probeLockResult", "Результат подбора"),
            CxRow.Button("audio.preview", "Прослушать звук закрытия (салон)"),
        )))
        r.add(CxRow.Info(key = "devEdge", title = EDGE_TITLE, valueFrom = { EDGE_TEXT }))
        r.add(CxRow.Buttons(listOf(
            CxRow.Button("dev.injectLockEvent", "Подать «закрыто» (0)", inject = "0"),
            CxRow.Button("dev.injectLockEvent", "Подать «открыто» (1)", inject = "1"),
            CxRow.Button("dev.testNotificationIcons", "Обновить иконки статус-бара"),
        )))

        // ── Блок 3: журнал приложения ────────────────────────────────────
        r.add(CxRow.Section("Системные свойства и логи приложения"))
        r.add(CxRow.Buttons(listOf(
            CxRow.Button("dev.getLogcat", "ПОКАЗАТЬ ПОСЛЕДНИЙ LOGCAT", kind = "accent"),
            CxRow.Button(CxAct.CLEAR_LOGCAT, "ОЧИСТИТЬ"),
        )))
        r.add(CxRow.Console(
            key = "devLogOut",
            placeholder = LOG_PLACEHOLDER,
            textFrom = { s -> s.string(CxPage.DEV_LOGCAT_OUTPUT, "") },
            textColor = CxPage.CONSOLE_LOG_FG,
            maxHeightDp = 260,
        ))
        return r
    }
}
