package com.example.voiceapp3.ui.cx

import com.google.gson.JsonObject

/**
 * Приёмник событий в нативную часть.
 *
 * Реализация в приложении — одна строка: `{ name, json -> uiBridge.onEvent(name, json) }`.
 * Это сознательно: `UiBridge.onEvent` уже содержит всю маршрутизацию
 * (`settings.change` → `ToolsBackend.applyFromUi`, `audio.*`, `dev.*`,
 * `tools.action`), и дублировать её здесь значило бы завести второй источник
 * истины. `onEvent` после переноса исполняется в фоновом потоке
 * `eventExecutor`, поэтому вызов из UI-потока безопасен.
 */
fun interface CxEventSink {
    fun emit(name: String, json: String)
}

/** Источник снимка настроек. Реализация: `{ toolsBackend.state() }`. */
fun interface CxStateSource {
    fun snapshot(): JsonObject?
}

/**
 * Слой обвязки: единственный мост между нативным интерфейсом и существующим
 * бэкендом. Заменяет три механизма шелла — `cxSet`, `cxAction` и обработчики
 * `native:*`-событий.
 *
 * ## Значения
 *
 * Снимок берётся из [CxStateSource] (это `ToolsBackend.state()`, то есть
 * реестр ГУ), а поверх него кладутся значения, полученные из событий и
 * оптимистичные записи. Порядок ровно тот, что был в шелле:
 * `TOOLS.state` → `SET` → значение по умолчанию из спецификации строки.
 *
 * ## Что здесь появляется вместо JS
 *
 * Событие `ui.theme` больше не существует: его отправлял `applyTheme()`, а
 * формула действующей палитры перенесена в [CxPalettes.effectiveIndex]. Поэтому
 * пересчёт и запись `ui_theme_effective_id` выполняются здесь — иначе движок
 * телеметрии и хром карты остались бы на старой палитре.
 *
 * Событие `telemetry.frame` тоже исчезает: JS мерял `.tabbar` и присылал
 * `bottomInset`. В нативном интерфейсе док — обычный View, и [CxShell] обязан
 * после раскладки сам вызвать `setDockInsetPx` у хостов.
 */
class CxWiring(
    private val events: CxEventSink,
    private val stateSource: CxStateSource,
    private val theme: CxThemeStore,
    private val onSay: ((String) -> Unit)? = null,
) : CxWrite, CxAct, CxNoteSource {

    /**
     * Оптимистичные записи. Чистятся по приходе нового `tools.state`: в шелле
     * `cxSet` писал прямо в `TOOLS.state`, и следующий снимок затирал его
     * фактическим значением из реестра — так работает откат при FAILED.
     */
    private val pending = LinkedHashMap<String, Any>()

    /**
     * Значения, полученные из событий (`update.state`, `projection.presence`,
     * результаты dev-консоли, плашка операции). Живут до следующего значения,
     * снимком не затираются: в снимке `ToolsBackend.state()` их нет.
     */
    private val derived = LinkedHashMap<String, Any>()

    /** Последний снимок `tools.state`; null до первого прихода. */
    private var snapshot: JsonObject? = null

    /** Выбранная вкладка — нужна, чтобы перерисовывать только видимое. */
    var currentTab: String = "quick"

    fun state(): CxState {
        val overlay = LinkedHashMap<String, Any>(derived)
        overlay.putAll(pending)
        return CxState(snapshot ?: stateSource.snapshot(), overlay)
    }

    // ── запись настройки: порт cxSet ────────────────────────────────────

    override fun set(key: String, value: Any) {
        pending[key] = value
        when (key) {
            /* Выбор палитры. В шелле `cxSetTheme(id)` делал три вещи: ставил
               setTheme, вызывал applyTheme() и отправлял cxSet('theme', id).
               Запись идёт тем же единственным каналом, а пересчёт действующей
               палитры и зеркало во Flutter — здесь, потому что события
               ui.theme больше нет. */
            "theme" -> {
                val id = value.toString()
                if (CxPalettes.byId(id) != null) {
                    theme.rememberShell(id)
                    val eff = CxPalettes.effectiveIndex(
                        CxPalettes.indexOfId(id),
                        state().bool("follow", true),
                        state().string("gu", "light") == "dark")
                    CxPalettes.byIndex(eff)?.let {
                        theme.rememberEffective(it.id)
                        theme.onThemeChanged(it.id)
                    }
                    theme.mirror()
                }
                emitSettings(key, value)
            }
            /* Тема ГУ и «следовать теме ГУ» меняют действующую палитру сразу,
               как в шелле: `if(key==='gu'||key==='follow') applyTheme()`. */
            "gu", "follow" -> {
                emitSettings(key, value)
                reapplyEffectiveTheme()
            }
            else -> emitSettings(key, value)
        }
    }

    private fun emitSettings(key: String, value: Any) {
        val o = JsonObject()
        o.addProperty("key", key)
        when (value) {
            is Boolean -> o.addProperty("value", value)
            is Number -> o.addProperty("value", value)
            else -> o.addProperty("value", value.toString())
        }
        events.emit("settings.change", o.toString())
    }

    private fun reapplyEffectiveTheme() {
        val st = state()
        val eff = CxPalettes.effectiveIndex(
            CxPalettes.indexOfId(theme.chosenId()),
            st.bool("follow", true),
            st.string("gu", "light") == "dark")
        CxPalettes.byIndex(eff)?.let {
            theme.rememberEffective(it.id)
            theme.onThemeChanged(it.id)
        }
        theme.mirror()
    }

    // ── действия кнопок: порт cxAction ──────────────────────────────────

    override fun act(action: String, payload: Map<String, Any?>) {
        if (action == CxAct.CLEAR_LOGCAT) {
            /* В шелле `cxDevClearLogs()` не отправлял события, а просто
               перезаписывал текст области вывода. */
            derived[CxPage.DEV_LOGCAT_OUTPUT] = "Журнал очищен."
            return
        }
        if (action in SOUND_ACTIONS) beginBusy(action)

        val o = JsonObject()
        for ((k, v) in payload) when (v) {
            null -> Unit
            is Boolean -> o.addProperty(k, v)
            is Number -> o.addProperty(k, v)
            is List<*> -> {
                val a = com.google.gson.JsonArray()
                for (e in v) if (e != null) a.add(e.toString())
                o.add(k, a)
            }
            else -> o.addProperty(k, v.toString())
        }

        when {
            /* Выбор файла: системный ACTION_OPEN_DOCUMENT на нативной стороне,
               payload не нужен. */
            action == "audio.selectFile" || action == "audio.selectUnlockFile" ->
                events.emit(action, "{}")

            isDirectEvent(action) -> events.emit(action, o.toString())

            /* Остальное — как `tools.action` с полем action: это ветка `else`
               из `cxAction`. */
            else -> {
                val w = JsonObject()
                w.addProperty("action", action)
                events.emit("tools.action", w.toString())
            }
        }
    }

    /** Подписи к действиям, которые показывала плашка статуса операции. */
    private val busyTitles = HashMap<String, String>()

    fun setBusyTitle(action: String, title: String) {
        busyTitles[action] = title
    }

    private fun beginBusy(action: String) {
        derived[CxPage.SOUND_BUSY] = true
        derived[CxPage.SOUND_MSG] = busyTitles[action] ?: "Выполняется…"
    }

    // ── входящие события ────────────────────────────────────────────────

    /**
     * Обработчик пушей из нативной части — замена `window.__nativeEvent` и
     * слушателей `native:*`.
     *
     * @return true, если состояние изменилось и интерфейс надо перерисовать
     */
    fun onNativeEvent(name: String, data: JsonObject?): Boolean {
        when (name) {
            "tools.state" -> {
                snapshot = data
                /* Пришёл readback: оптимистичные записи больше не нужны. Если
                   запись не подтвердилась, в снимке фактическое значение, и
                   контрол откатится к нему сам. */
                pending.clear()
                applySoundResult(data)
                return true
            }
            "update.state" -> {
                if (data == null) return false
                data.get("phase")?.takeIf { !it.isJsonNull }
                    ?.let { derived[CxPanelUpdates.KEY_PHASE] = it.asString }
                data.get("current")?.takeIf { !it.isJsonNull }
                    ?.let { derived[CxPanelUpdates.KEY_CURRENT] = it.asString }
                data.get("version")?.takeIf { !it.isJsonNull }
                    ?.let { derived[CxPanelUpdates.KEY_VERSION] = it.asString }
                data.get("changelog")?.takeIf { !it.isJsonNull }
                    ?.let { derived[CxPanelUpdates.KEY_CHANGELOG] = it.asString }
                data.get("percent")?.takeIf { !it.isJsonNull }
                    ?.let { derived[CxPanelUpdates.KEY_PERCENT] = it.asInt }
                data.get("message")?.takeIf { !it.isJsonNull }
                    ?.let { derived[CxPanelUpdates.KEY_MSG] = it.asString }

                val phase = derived[CxPanelUpdates.KEY_PHASE]?.toString().orEmpty()
                if (phase == CxPanelUpdates.PHASE_AVAILABLE) {
                    /* как в шелле: при доступной версии прогресс сбрасывается */
                    derived[CxPanelUpdates.KEY_PERCENT] = 0
                    onSay?.invoke("Доступна версия v" +
                        (derived[CxPanelUpdates.KEY_VERSION] ?: ""))
                } else if (phase == CxPanelUpdates.PHASE_ERROR) {
                    onSay?.invoke(derived[CxPanelUpdates.KEY_MSG]?.toString()
                        ?: "Ошибка обновления")
                }
                return true
            }
            "projection.presence" -> {
                if (data == null) return false
                data.get("carplay")?.takeIf { !it.isJsonNull }
                    ?.let { derived[CxScreenCar.PROJ_CARPLAY] = it.asString }
                data.get("androidAuto")?.takeIf { !it.isJsonNull }
                    ?.let { derived[CxScreenCar.PROJ_ANDROID_AUTO] = it.asString }
                data.get("carplayAvailable")?.takeIf { !it.isJsonNull }
                    ?.let { derived[CxScreenCar.PROJ_CP_AVAIL] = it.asBoolean }
                data.get("androidAutoAvailable")?.takeIf { !it.isJsonNull }
                    ?.let { derived[CxScreenCar.PROJ_AA_AVAIL] = it.asBoolean }
                return true
            }
            "ui.selectTab" -> {
                data?.get("id")?.takeIf { !it.isJsonNull }
                    ?.let { currentTab = it.asString }
                return true
            }
        }
        return false
    }

    /**
     * Результат команды dev-консоли.
     *
     * В шелле приходил прямым `webView.evaluateJavascript(
     * "window.__onDevCmdResult && …")` из `UiBridge.kt:622`, минуя канал
     * `tools.state`. В нативной версии этот вызов заменяется на
     * `wiring.onDevCommandResult(…)`.
     */
    fun onDevCommandResult(output: String, exitCode: Int) {
        derived[CxPage.DEV_CMD_OUTPUT] = output.ifEmpty {
            "Команда выполнена (вывод пуст, exitCode=$exitCode)"
        }
        derived[CxPage.DEV_CMD_EXIT] = exitCode
    }

    /**
     * Список приложений для модального окна keep-alive.
     *
     * Замена третьего прямого `webView.evaluateJavascript` — в шелле список
     * приходил вызовом `window.__onKeepAliveApps($list)` из `UiBridge.kt:669`,
     * в ответ на событие `perf.getKeepAliveList`. Источник данных —
     * `AppPerformance.getInstalledAppsInfo(activity)`.
     */
    fun onKeepAliveApps(list: List<CxKeepAliveApp>) {
        keepAliveModal?.setApps(list)
    }

    /** Открытое модальное окно keep-alive; null, если закрыто. */
    var keepAliveModal: CxKeepAliveModal? = null

    /** Замена `window.__onDevLogcatResult` из `UiBridge.kt:631`. */
    fun onDevLogcat(logs: String) {
        derived[CxPage.DEV_LOGCAT_OUTPUT] = logs.ifEmpty { "Логи отсутствуют" }
    }

    /**
     * Плашка статуса операции — порт `window.__cxSoundResult`.
     *
     * Один и тот же снимок приходит на каждое действие, поэтому результат
     * показывается один раз: по сигнатуре «действие + итог».
     */
    private var lastResultSig: String? = null

    private fun applySoundResult(data: JsonObject?) {
        val last = data?.get("last")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        val action = last.get("action")?.takeIf { !it.isJsonNull }?.asString ?: return
        if (action !in SOUND_ACTIONS) return
        val summary = last.get("summary")?.takeIf { !it.isJsonNull }?.asString ?: ""
        val sig = "$action|$summary"
        if (sig == lastResultSig) return
        lastResultSig = sig

        val ok = (last.get("ok")?.takeIf { !it.isJsonNull }?.asBoolean == true) ||
            last.get("status")?.takeIf { !it.isJsonNull }?.asString == "ok"
        /* Причина показывается текстом последнего шага: в FAILED бэкенд кладёт
           туда класс исключения, сообщение и цепочку cause. */
        val steps = last.get("steps")?.takeIf { it.isJsonArray }?.asJsonArray
        val st = if (steps != null && steps.size() > 0)
            steps.get(steps.size() - 1).takeIf { it.isJsonObject }?.asJsonObject else null
        fun field(o: JsonObject?, k: String) =
            o?.get(k)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
        val why = field(st, "stderr") ?: field(st, "stdout") ?: summary
        val title = field(last, "title") ?: action

        derived[CxPage.SOUND_BUSY] = false
        derived[CxPage.SOUND_SUCCESS] = ok
        derived[CxPage.SOUND_MSG] = "$title: " + (why ?: if (ok) "применено" else "ошибка")
    }

    // ── подписи под контролами: порт cxResultFor ────────────────────────

    override fun noteFor(key: String): CxNote? {
        val last = snapshot?.get("last")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val action = last.get("action")?.takeIf { !it.isJsonNull }?.asString ?: return null
        val matches = action == "settings.$key" || when (key) {
            "copyUsb" -> action == "audio.copyFromUsb"
            "lockSoundFile" -> action == "audio.selectFile" ||
                action == "audio.selectSoundName" ||
                action == "audio.restaurarSomTravaPersistente"
            "unlockSoundFile" -> action == "audio.selectUnlockFile" ||
                action == "audio.restoreFactoryUnlock"
            "lockSound" -> action == "audio.preview" || action == "settings.lockSound"
            "unlockSound" -> action == "audio.previewUnlock" ||
                action == "settings.unlockSound"
            else -> false
        }
        if (!matches) return null
        val status = last.get("status")?.takeIf { !it.isJsonNull }?.asString
            ?: if (last.get("ok")?.takeIf { !it.isJsonNull }?.asBoolean == true)
                CxNote.STATUS_OK else CxNote.STATUS_FAILED
        val summary = last.get("summary")?.takeIf { !it.isJsonNull }?.asString ?: ""
        return CxNote(summary, status)
    }

    companion object {
        /**
         * Имена событий, которые шелл отправлял НАПРЯМУЮ, а не через
         * `tools.action`.
         *
         * `cxAction` знал только про `audio.*` и `dev.*`, потому что обслуживал
         * одни кнопки `data-cxact`. Остальные элементы имели собственные
         * обработчики: плитки «Быстрого доступа» слали `drive.mode`,
         * `drive.recup`, `climate.air`, `climate.recirc`, `climate.heat`,
         * `climate.seat`, точки цвета — `car.color`, полоса заряда —
         * `car.charging`, карточка машины — `car.hood` и `car.trunk`, кнопки
         * обновлений — `update.check` и `update.install`, модалка keep-alive —
         * `perf.getKeepAliveList` и `perf.saveKeepAlive`.
         *
         * В нативном интерфейсе все они идут через один [CxAct], поэтому набор
         * пришлось собрать целиком. `perf.*` перечислен поимённо: действие
         * `perf.manageKeepAlive` событием НЕ является и должно уйти в
         * `tools.action` (а до этого его перехватывает `CxPage.localAction`).
         */
        private val DIRECT_PREFIXES = listOf(
            "audio.", "dev.", "drive.", "climate.", "car.", "voice.",
            "update.", "projection.", "telemetry.", "settings.",
            "tab.", "option.", "ui.",
        )
        private val DIRECT_NAMES = setOf(
            "perf.getKeepAliveList", "perf.saveKeepAlive",
        )

        fun isDirectEvent(action: String): Boolean =
            action in DIRECT_NAMES || DIRECT_PREFIXES.any { action.startsWith(it) }

        /**
         * Список `CX_SOUND_ACTIONS` из шелла — действия, у которых есть плашка
         * статуса. Перенесён полностью, все шестнадцать.
         */
        val SOUND_ACTIONS: Set<String> = setOf(
            "audio.copyFromUsb", "audio.selectFile", "audio.selectUnlockFile",
            "audio.selectSoundName", "audio.preview", "audio.previewUnlock",
            "audio.restaurarSomTravaPersistente", "audio.restoreFactoryUnlock",
            "settings.lockSound", "settings.unlockSound",
            "dev.probeLockSignal", "dev.probeLockResult", "dev.lockStatus",
            "dev.lockLog", "dev.injectLockEvent", "dev.testUnlockSound",
        )
    }
}
