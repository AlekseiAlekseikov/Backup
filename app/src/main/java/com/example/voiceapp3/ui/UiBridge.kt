package com.example.voiceapp3.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.example.voiceapp3.AppUpdater
import com.example.voiceapp3.BuildConfig
import com.example.voiceapp3.tools.ScreenBrightness
import com.example.voiceapp3.tools.StatusIcons
import com.example.voiceapp3.tools.ToolsBackend
import com.example.voiceapp3.tools.AppLog
import com.example.voiceapp3.tools.ToolsHost
import com.example.voiceapp3.tools.LockSound
import com.example.voiceapp3.tools.AppPerformance
import com.example.voiceapp3.tools.AdbClient
import com.example.voiceapp3.tools.WindowDoors
import com.example.voiceapp3.tools.ToolsResult
import com.example.voiceapp3.TelemetryPrefsMirror
import com.example.voiceapp3.VoiceAssistantService
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Мост между HTML-интерфейсом (assets/ui/index.html) и нативной частью.
 *
 * Все действия пользователя приходят в [onEvent]; каждое из них либо
 * маршрутизируется в реальный backend (движок телеметрии, VHAL-хендлеры,
 * настройки приложения), либо логзируется — полный протокол виден в logcat
 * по тегу UiBridge.
 *
 * Stage CXT: настройки вкладки «Инструменты» живут в реестре
 * [ToolsBackend] (один effective state: SharedPreferences + живой эффект +
 * readback). Интерфейс не держит своё состояние: он рисует снимок
 * `tools.state`, который мост отправляет при загрузке страницы ([appState])
 * и после каждого изменения.
 *
 * Фаза 2 (маршрутизация к существующей логике проекта):
 *   car.trunk / car.hood  -> handlers/TrunkControlHandler
 *   car.lock              -> Ex2Vehicle (центральный замок)
 *   car.color             -> PREF_CAR_PAINT (SharedPreferences)
 *   car.charging          -> ограничения заряда / ток (car/Ex2Vehicle)
 *   drive.mode            -> handlers/DriveModeHandler
 *   drive.recup           -> handlers/RecupModeHandler
 *   climate.air/recirc    -> car/HuClimate (направление обдува)
 *   climate.heat          -> HuClimate + ExteriorLightControlHandler (макс)
 *   climate.seat          -> handlers/SeatClimateHandler
 *   voice.request         -> VoiceAssistantService (старт прослушивания)
 *   voice.micRestart      -> MicPump (перезапуск микрофона)
 *   settings.change       -> ToolsBackend (persist + живой эффект + readback)
 *   tab.change            -> вкладки, вкл. CarPlay/AA (SET.proj)
 *   update.*              -> AppUpdater
 *
 * Обратный канал (Kotlin → JS): [pushToUi] вызывает
 *   window.__nativeEvent(name, json)
 * интерфейс диспатчит CustomEvent 'native:<name>' (см. конец index.html).
 */
private const val PREF_CAR_PAINT = "pref_car_paint"

/* Stage CXT: имена ключей настроек приложения («Полный экран», «Запуск при
   пробуждении», «Полоса климата», CarPlay/AA, тема ГУ и палитра) живут в
   ToolsBackend.Prefs — один реестр на WebView, mirror в Flutter-prefs и
   загрузку состояния. Имя ключа immersive оставлено как в capy
   (AppExperienceController._immersiveKey), чтобы семантика и значение по
   умолчанию совпадали один в один. */

class UiBridge(
    private val activity: Activity,
    private val webView: WebView,
    // GeelyTools deviation: вместо полного WebView-порта телеметрии — тонкий
    // live-фид движка (навигация/кузов/карточка батареи), см. VehicleLiveAdapter.
    private val vehicleLive: VehicleLiveAdapter? = null
) : ToolsHost {

    /**
     * GeelyTools deviation: встроенный Flutter-экран телеметрии — вкладка
     * «Телеметрия» показывает его сразу (без промежуточной кнопки), нижний
     * док шелла остаётся видимым. Задаётся MainActivity после создания.
     */
    @Volatile
    var telemetryHost: TelemetryHostController? = null

    // Stage NAV-MAP: нативная карта навигации поверх шелла — то же
    // встраивание и тот же вырез под док, что у TelemetryHostController.
    var navHost: com.example.voiceapp3.nav.NavigationHostController? = null

    /**
     * Stage B7: управление кузовом (капот/багажник) — тот же путь VHAL,
     * что у голосового TrunkControlHandler. Замок — только индикатор
     * (управления нет ни в проекте, ни в источниках телеметрии).
     */
    private val bodyControl by lazy {
        BodyControlAdapter(activity) { name, json -> pushToUi(name, json) }
    }

    /**
     * Stage UPD: последний релиз, найденный проверкой (update.check).
     * Команда «Установить» (update.install) ставит именно его — как
     * «Сначала проверьте обновление» у любого апдейтера.
     */
    @Volatile
    private var lastRemote: AppUpdater.Remote? = null

    /**
     * Stage CPv2: рендер-мост CarPlay/Android Auto. Ставится MainActivity
     * сразу после создания этого моста (как configureFlutterEngine в capy
     * строит CarplayBridge/AndroidAutoBridge/ProjectionTouchBridge).
     */
    @Volatile
    var projectionRender: ProjectionRenderBridge? = null

    /* ── Stage QA: отправка HUB-команд в VoiceAssistantService ── */

    /** Простая команда с одним строковым режимом (drive/recup/ac_direction/listen). */
    private fun hub(kind: String, mode: String? = null) {
        val i = Intent(VoiceAssistantService.ACTION_HUB)
            .putExtra(VoiceAssistantService.EXTRA_HUB, kind)
        mode?.let { i.putExtra(VoiceAssistantService.EXTRA_MODE, it) }
        activity.sendBroadcast(i)
    }

    /** Универсальная команда: intent + текст + entities (как голосовая фраза). */
    private fun hubIntent(
        intentName: String,
        text: String,
        action: String? = null,
        value: Int? = null,
        direction: String? = null
    ) {
        val i = Intent(VoiceAssistantService.ACTION_HUB)
            .putExtra(VoiceAssistantService.EXTRA_HUB, VoiceAssistantService.HUB_INTENT)
            .putExtra(VoiceAssistantService.EXTRA_INTENT, intentName)
            .putExtra(VoiceAssistantService.EXTRA_TEXT, text)
        action?.let { i.putExtra(VoiceAssistantService.EXTRA_ACTION, it) }
        value?.let { i.putExtra(VoiceAssistantService.EXTRA_VALUE, it) }
        direction?.let { i.putExtra(VoiceAssistantService.EXTRA_DIRECTION, it) }
        activity.sendBroadcast(i)
    }

    /** Режим «Полный экран» из настроек интерфейса (Экран → Полный экран). */
    @Volatile
    var immersive: Boolean = false
        private set

    /**
     * Последняя вкладка, о которой сказал JS (tab.change). Stage B5:
     * признак для запроса данных при старте; Stage B6 — живой поток
     * больше не привязан к вкладке (карточка батареи в шелле видна
     * везде), см. [onAppResumed].
     */
    @Volatile
    var lastUiTab: String? = null
        private set

    /** Приложение на экране? (для решения: анимация в интерфейсе или плашка) */
    @Volatile
    var appResumed: Boolean = true
        private set

    /** Вкладка «Голос» сейчас на экране — анимировать микрофон интерфейса. */
    val isVoiceTabActive: Boolean
        get() = appResumed && lastUiTab == "voice"

    /** Приложение ушло с экрана — погасить живой поток. */
    fun onAppPaused() {
        appResumed = false
        vehicleLive?.stopLive()
    }

    /** Приложение вернулось — возобновить живой поток. */
    fun onAppResumed() {
        appResumed = true
        // Stage B6: карточка батареи живёт в шелле на каждой вкладке,
        // поэтому поток привязан к видимости приложения, а не к вкладке.
        vehicleLive?.startLive()
        // Stage CXT-2: «Запоминать яркость по фарам» — при возвращении в
        // приложение сверяемся с режимом день/ночь (аналог watchdog-шага
        // APK): сменился — применить сохранённую корзину, иначе снять
        // текущий уровень. До включённого тумблера — no-op.
        runCatching { ScreenBrightness.syncOnContext(activity) }
        // Срез №3: синхронизация статус-иконок при возвращении приложения
        // (как watchdog-цикл APK при SCREEN_ON/USER_PRESENT).
        runCatching { StatusIcons.syncAll(activity) }
    }

    /** Единый канал событий из интерфейса. */
    @JavascriptInterface
    fun onEvent(name: String, data: String) {
        Log.d(TAG, "event: $name $data")
        when (name) {
            // Вкладка телеметрии / настройки: движок собирает пакет данных
            // на фоне и сам вернёт его в JS через pushToUi("telemetry.data").
            // Stage B5: живой поток стартует только когда вкладка телеметрии
            // живёт с видимостью приложения (Stage B6: карточка батареи
            // в шелле видна на каждой вкладке).
            "telemetry.request", "settings.request" -> {
                vehicleLive?.request()
                if (name == "telemetry.request") vehicleLive?.startLive()
                // CarPlay/AA: страница перезагрузилась и её состояние сброшено —
                // отдаём последний известный снимок присутствия (как onListen
                // в мосте capy, который всегда начинает с текущего снимка).
                if (name == "telemetry.request") {
                    com.timhss.capyenergy.projection.ProjectionPresenceBridge
                        .get(activity)?.publishLatest()
                    // Stage CPv2: и статусы рендер-мостов (перезагрузка страницы)
                    projectionRender?.publishStatuses()
                }
            }
            // GeelyTools deviation: пункт «Телеметрия» и карточка батареи открывают
            // нативное Flutter-приложение телеметрии (точный порт capy).
            "telemetry.open" -> {
                runCatching {
                    val dest = runCatching {
                        JsonParser.parseString(data).asJsonObject
                            .get("destination")?.asString
                    }.getOrNull()
                    // Встроенный экран: показать с нужным destination и
                    // синхронно подсветить вкладку в доке шелла (JS select
                    // пришлёт tab.change → повторный show() — no-op).
                    activity.runOnUiThread { telemetryHost?.show(dest) }
                    pushToUi("ui.selectTab", "{\"id\":\"telemetry\"}")
                }.onFailure { Log.w(TAG, "telemetry.open failed", it) }
            }
            // Действующая палитра шелла (каталог THEMES index.html ↔
            // AppThemeId capy). JS шлёт её при загрузке страницы и каждой смене
            // темы; она персистится в ОТДЕЛЬНЫЙ ключ и зеркалируется во
            // Flutter-prefs перед стартом движка телеметрии
            // (TelemetryPrefsMirror). Выбор плиток (ui_shell_theme_id) здесь не
            // трогается: при «Следовать теме ГУ» ui.theme приносит тёмный
            // вариант, и запись его в ключ выбора ломала бы обратный ход.
            "ui.theme" -> {
                runCatching {
                    val id = JsonParser.parseString(data).asJsonObject
                        .get("id")?.asString
                    if (!id.isNullOrBlank()) {
                        TelemetryPrefsMirror.rememberEffectiveTheme(activity, id)
                        // Stage NAV-MAP: хром и подложка карты меняют палитру
                        // вслед за шеллом сразу, без перезагрузки.
                        activity.runOnUiThread { navHost?.onThemeChanged(id) }
                    }
                }.onFailure { Log.w(TAG, "ui.theme parse failed", it) }
            }
            // Геометрия плавающего дока: JS меряет .tabbar (rect × dpr) и
            // присылает нижний отступ для встроенного FlutterView — тот же
            // паттерн, что projectionFrame у CarPlay/AA-оверлеев.
            "telemetry.frame" -> {
                runCatching {
                    val inset = JsonParser.parseString(data).asJsonObject
                        .get("bottomInset")?.asDouble
                    if (inset != null && inset.isFinite()) {
                        activity.runOnUiThread {
                            telemetryHost?.setDockInsetPx(inset.toInt())
                            // Stage NAV-MAP: общий вырез и для карты навигации.
                            navHost?.setDockInsetPx(inset.toInt())
                        }
                    }
                }.onFailure { Log.w(TAG, "telemetry.frame parse failed", it) }
            }
            // Stage B7: капот/багажник из карточки машины. Открывает через
            // BodyControlAdapter (порт TrunkControlHandler); закрытие
            // электроприводом на EX2 невозможно — честно отказываем.
            "car.hood", "car.trunk" -> {
                runCatching {
                    val obj: JsonObject = JsonParser.parseString(data).asJsonObject
                    val open = obj.get("open")?.asBoolean == true
                    if (open) {
                        bodyControl.open(if (name == "car.hood") "hood" else "trunk")
                    } else {
                        Log.w(TAG, "$name: закрытие электроприводом недоступно")
                    }
                }.onFailure { Log.w(TAG, "car body parse failed", it) }
            }
            // ── Stage QA: быстрые действия (панель «Быстрый доступ») ──
            // Маршрутизация через HUB-броадкаст VoiceAssistantService:
            // команды проходят тот же IntentHandlerRegistry, что и голос.
            "drive.mode" -> {
                runCatching {
                    val mode = when (JsonParser.parseString(data).asJsonObject.get("mode")?.asString) {
                        "Эко" -> "eco"
                        "Комфорт" -> "comfort"
                        "Спорт" -> "sport"
                        else -> null
                    }
                    if (mode != null) hub(VoiceAssistantService.HUB_DRIVE, mode)
                }.onFailure { Log.w(TAG, "drive.mode parse failed", it) }
            }
            "drive.recup" -> {
                runCatching {
                    val mode = when (JsonParser.parseString(data).asJsonObject.get("value")?.asString) {
                        "Низкая" -> "weak"
                        "Средняя" -> "medium"
                        "Высокая" -> "strong"
                        else -> null
                    }
                    if (mode != null) hub(VoiceAssistantService.HUB_RECUP, mode)
                }.onFailure { Log.w(TAG, "drive.recup parse failed", it) }
            }
            "climate.air" -> {
                runCatching {
                    val mode = when (JsonParser.parseString(data).asJsonObject.get("value")?.asString) {
                        "Лицо" -> "face"
                        "Ноги" -> "floor"
                        "Лицо+Ноги" -> "face_floor"
                        "Стекло" -> "windshield"
                        "Ноги+Стекло" -> "floor_windshield"
                        else -> null
                    }
                    if (mode != null) hub(VoiceAssistantService.HUB_AC_DIRECTION, mode)
                }.onFailure { Log.w(TAG, "climate.air parse failed", it) }
            }
            "climate.recirc" -> {
                runCatching {
                    val on = JsonParser.parseString(data).asJsonObject.get("on")?.asBoolean == true
                    hubIntent(
                        "ac_control",
                        if (on) "рециркуляция" else "выключи рециркуляцию",
                        action = if (on) "set" else "unset"
                    )
                }.onFailure { Log.w(TAG, "climate.recirc parse failed", it) }
            }
            "climate.heat" -> {
                runCatching {
                    val obj = JsonParser.parseString(data).asJsonObject
                    val on = obj.get("on")?.asBoolean == true
                    val act = if (on) "set" else "unset"
                    when (obj.get("name")?.asString) {
                        "Руль" -> hubIntent(
                            "steering_heat",
                            if (on) "включи подогрев руля" else "выключи подогрев руля",
                            action = act
                        )
                        "Лобовое стекло" -> hubIntent(
                            "hvac_defrost",
                            if (on) "включи подогрев лобового стекла" else "выключи подогрев лобового стекла",
                            action = act
                        )
                        "Заднее стекло" -> hubIntent(
                            "hvac_defrost",
                            if (on) "включи подогрев заднего стекла" else "выключи подогрев заднего стекла",
                            action = act
                        )
                        "Стекло Макс" -> hubIntent(
                            "hvac_defrost",
                            if (on) "максимальный обдув стекла" else "выключи максимальный обдув стекла",
                            action = act
                        )
                    }
                }.onFailure { Log.w(TAG, "climate.heat parse failed", it) }
            }
            "climate.seat" -> {
                runCatching {
                    val obj = JsonParser.parseString(data).asJsonObject
                    val name = obj.get("name")?.asString ?: return@runCatching
                    val level = obj.get("level")?.asInt ?: 0
                    val side = if (name.contains("пассаж")) "right" else "left"
                    if (level <= 0) {
                        hubIntent("seat_heat", "выключи подогрев сиденья",
                            action = "unset", direction = side)
                    } else {
                        hubIntent("seat_heat", "подогрев сиденья на $level",
                            action = "set", value = level, direction = side)
                    }
                }.onFailure { Log.w(TAG, "climate.seat parse failed", it) }
            }
            // ── Stage CPv2: команды рендер-моста (эквиваленты методов
            // CarplayBridge/AndroidAutoBridge: activate/deactivate/refresh).
            // Вкладка активна → activate (binding касаний + Surface + рендер),
            // ушла → deactivate (подъём пальцев, R8-освобождение, unbind).
            "projection.activate" -> projectionRender?.activate(
                ProjectionRenderBridge.stackFromWire(
                    JsonParser.parseString(data).asJsonObject.get("stack")?.asString ?: return
                ) ?: return
            )
            "projection.deactivate" -> projectionRender?.deactivate(
                ProjectionRenderBridge.stackFromWire(
                    JsonParser.parseString(data).asJsonObject.get("stack")?.asString ?: return
                ) ?: return
            )
            "projection.refresh" -> projectionRender?.refresh(
                ProjectionRenderBridge.stackFromWire(
                    JsonParser.parseString(data).asJsonObject.get("stack")?.asString ?: return
                ) ?: return
            )
            "voice.request" -> hub(VoiceAssistantService.HUB_LISTEN)
            "voice.micRestart" -> hub(VoiceAssistantService.HUB_RESTART_MIC)
            // ── Stage UPD: вкладка «Обновления» — GitHub Releases ──
            "update.check" -> {
                pushUpdateState("checking")
                AppUpdater.check(activity, object : AppUpdater.Listener {
                    override fun onCheck(result: AppUpdater.CheckResult) {
                        when (result) {
                            is AppUpdater.CheckResult.Available -> {
                                lastRemote = result.remote
                                pushUpdateState(
                                    "available",
                                    versionName = result.remote.versionName,
                                    changelog = result.remote.changelog
                                )
                            }
                            is AppUpdater.CheckResult.UpToDate ->
                                pushUpdateState("uptodate", versionName = AppUpdater.currentVersionName(activity))
                            is AppUpdater.CheckResult.Error ->
                                pushUpdateState("error", message = result.message)
                        }
                    }
                    override fun onProgress(percent: Int, message: String) = Unit
                    override fun onInstallStarted() = Unit
                    override fun onFailure(message: String) = Unit
                })
            }
            "update.install" -> {
                val remote = lastRemote
                if (remote == null) {
                    pushUpdateState("error", message = "Сначала проверьте обновление")
                    return
                }
                pushUpdateState("downloading", percent = 0, message = "Скачиваю ${remote.versionName}…")
                AppUpdater.downloadAndInstall(activity, remote, object : AppUpdater.Listener {
                    override fun onCheck(result: AppUpdater.CheckResult) = Unit
                    override fun onProgress(percent: Int, message: String) {
                        // percent = -1 — сообщение без прогресса (докачка/повтор)
                        pushUpdateState(
                            if (percent >= 100) "installing" else "downloading",
                            percent = percent,
                            message = message
                        )
                    }
                    override fun onInstallStarted() {
                        pushUpdateState("installing", message = "Устанавливаю… приложение перезапустится")
                    }
                    override fun onFailure(message: String) {
                        pushUpdateState("error", message = message)
                    }
                })
            }
            "car.color" -> {
                runCatching {
                    val color = JsonParser.parseString(data).asJsonObject.get("color")?.asString
                    if (color != null) {
                        activity.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
                            .edit().putString(PREF_CAR_PAINT, color).apply()
                    }
                }.onFailure { Log.w(TAG, "car.color parse failed", it) }
            }
            // Stage B6: вкладки поток больше не гасят — только onPause
            // приложения; lastUiTab хранится для возобновления контекста.
            "tab.change" -> {
                runCatching {
                    val obj: JsonObject = JsonParser.parseString(data).asJsonObject
                    val id = obj.get("id")?.asString
                    // Палитра шелла едет прямо в событии — страховка от гонок
                    // порядка (ui.theme при загрузке мог не успеть/потеряться):
                    // запоминаем ДО show(), чтобы зеркало взяло свежее значение.
                    // Это действующая палитра, а не выбор плиток — см. ui.theme.
                    obj.get("theme")?.asString?.let { theme ->
                        TelemetryPrefsMirror.rememberEffectiveTheme(activity, theme)
                    }
                    if (id != null) {
                        lastUiTab = id
                        vehicleLive?.startLive()
                        // Встроенная телеметрия живёт ровно на своей вкладке:
                        // показ/скрытие — на UI-потоке (onEvent зовётся из
                        // JavaBridge-треда WebView).
                        activity.runOnUiThread {
                            if (id == "telemetry") telemetryHost?.show()
                            else telemetryHost?.hide()
                            // Stage NAV-MAP: карта живёт ровно на своей вкладке.
                            if (id == "navi") navHost?.show()
                            else navHost?.hide()
                            // …тема из события сразу доступна: хром и карта
                            // меняют палитру, фон WebView — смотри ниже.
                            obj.get("theme")?.asString?.let { navHost?.onThemeChanged(it) }
                            applyNavWebViewBg(id == "navi", obj.get("theme")?.asString)
                        }
                    }
                }.onFailure { Log.w(TAG, "tab.change parse failed", it) }
            }
            // GeelyTools deviation: команды WebView-экранов телеметрии
            // (sync.now, charge.*, charges.merge, energy.window, history.series,
            // cycle.sessions, places.*, lab.*, tool.act) удалены вместе с
            // экранами — эти операции выполняет Flutter-приложение телеметрии.
            "settings.change" -> {
                runCatching {
                    val obj: JsonObject = JsonParser.parseString(data).asJsonObject
                    val key = obj.get("key")?.asString
                    val element = obj.get("value")
                    if (key != null && element != null) {
                        val value: Any = if (element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) {
                            element.asBoolean
                        } else {
                            element.asString
                        }
                        val result = tools.applyFromUi(key, value)
                        if (result != null) {
                            lastToolsResult = result
                            if (key == "theme") {
                                TelemetryPrefsMirror.rememberShellTheme(activity, value.toString())
                                activity.runOnUiThread { navHost?.onThemeChanged(value.toString()) }
                            }
                            pushToolsState()
                        }
                    }
                }.onFailure { Log.w(TAG, "settings.change parse failed", it) }
            }
            // Срез №4: Звук блокировки замка (§7 ТЗ)
            "audio.copyFromUsb" -> {
                runCatching {
                    val result = LockSound.copyFromUsb(activity)
                    lastToolsResult = result
                    pushToolsState()
                }
            }
            "audio.selectSoundName" -> {
                runCatching {
                    val obj = try { JsonParser.parseString(data).asJsonObject } catch (_: Exception) { null }
                    val name = obj?.get("name")?.asString ?: ""
                    val isLock = obj?.get("isLock")?.asBoolean ?: true
                    val result = LockSound.selectFileByName(activity, name, isLock)
                    lastToolsResult = result
                    pushToolsState()
                }
            }
            "audio.preview" -> {
                runCatching {
                    val result = LockSound.preview(activity)
                    AppLog.logAction(activity, "audio.preview", result)
                    lastToolsResult = result
                    pushToolsState()
                }
            }
            "audio.previewUnlock" -> {
                runCatching {
                    val result = LockSound.playPreview(activity, isLock = false)
                    AppLog.logAction(activity, "audio.previewUnlock", result)
                    lastToolsResult = result
                    pushToolsState()
                }
            }
            "audio.restoreFactoryUnlock" -> {
                runCatching {
                    val result = LockSound.restoreFactorySound(activity, isLock = false)
                    lastToolsResult = result
                    pushToolsState()
                }
            }
            "audio.restaurarSomTravaPersistente" -> {
                runCatching {
                    val result = LockSound.restoreFactory(activity)
                    lastToolsResult = result
                    pushToolsState()
                }
            }
            "audio.selectUnlockFile" -> {
                runCatching {
                    val obj = try { JsonParser.parseString(data).asJsonObject } catch (_: Exception) { null }
                    val path = obj?.get("path")?.asString
                    if (!path.isNullOrEmpty()) {
                        val result = LockSound.selectUnlockFile(activity, path)
                        AppLog.logAction(activity, "audio.selectUnlockFile", result)
                        lastToolsResult = result
                    } else {
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                                type = "audio/*"
                                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                            }
                            activity.startActivityForResult(intent, 7002)
                        } catch (e: Exception) {
                            val wavs = LockSound.discoverWavFiles()
                            if (wavs.isNotEmpty()) {
                                val result = LockSound.selectUnlockFile(activity, wavs.first().absolutePath)
                                lastToolsResult = result
                            }
                        }
                    }
                    pushToolsState()
                }
            }
            "audio.selectFile" -> {
                runCatching {
                    val obj = try { JsonParser.parseString(data).asJsonObject } catch (_: Exception) { null }
                    val path = obj?.get("path")?.asString
                    if (!path.isNullOrEmpty()) {
                        val result = LockSound.selectFile(activity, path)
                        AppLog.logAction(activity, "audio.selectFile", result)
                        lastToolsResult = result
                    } else {
                        // Запуск системного выбора файла через Intent ACTION_OPEN_DOCUMENT / GET_CONTENT
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                                type = "audio/*"
                                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                            }
                            activity.startActivityForResult(intent, 7001)
                        } catch (e: Exception) {
                            val wavs = LockSound.discoverWavFiles()
                            if (wavs.isNotEmpty()) {
                                val result = LockSound.selectFile(activity, wavs.first().absolutePath)
                                lastToolsResult = result
                            }
                        }
                    }
                    pushToolsState()
                }
            }
            "dev.runCommand" -> {
                runCatching {
                    val obj = try { JsonParser.parseString(data).asJsonObject } catch (_: Exception) { null }
                    val cmd = obj?.get("command")?.asString ?: "id"
                    val (exitCode, output) = AdbClient.execute(cmd, 15000)
                    val resObj = com.google.gson.JsonObject().apply {
                        addProperty("exitCode", exitCode)
                        addProperty("output", output)
                    }
                    activity.runOnUiThread {
                        webView.evaluateJavascript("window.__onDevCmdResult && window.__onDevCmdResult($resObj)", null)
                    }
                }
            }
            "dev.getLogcat" -> {
                runCatching {
                    val (exitCode, output) = AdbClient.execute("logcat -d -t 120 -s CentralWatchdog:V LockSound:V WindowDoors:V NetworkTtl:V UiBridge:V ToolsBackend:V", 8000)
                    val escaped = com.google.gson.Gson().toJson(output)
                    activity.runOnUiThread {
                        webView.evaluateJavascript("window.__onDevLogcatResult && window.__onDevLogcatResult($escaped)", null)
                    }
                }
            }
            "dev.testAvasSound" -> {
                runCatching {
                    val path = LockSound.getUnlockSelectedPath(activity)
                    val file = if (path.isNotEmpty()) java.io.File(path) else java.io.File("/vendor/etc/carlock/dajidali.wav")
                    LockSound.playExternalSound(activity, file, LockSound.getVolume(activity))
                }
            }
            "dev.simulateDoorOpen" -> {
                runCatching {
                    WindowDoors.initCarService(activity)
                    // Водительская дверь (зона 1 -> открыта 1)
                    val cpm = WindowDoors.PROP_WINDOW_POS
                    // приоткрываем водительское окно на 10%
                    AdbClient.execute("dumpsys car_service inject-event 0x16400b00 1 1 2>/dev/null")
                    // и сразу вызываем воспроизведение звука
                    val file = java.io.File("/vendor/etc/carlock/dajidali.wav")
                    LockSound.playExternalSound(activity, file, LockSound.getVolume(activity))
                }
            }
            "dev.simulateDoorClose" -> {
                runCatching {
                    WindowDoors.initCarService(activity)
                    AdbClient.execute("dumpsys car_service inject-event 0x16400b00 1 2 2>/dev/null")
                }
            }
            "dev.testNotificationIcons" -> {
                runCatching {
                    StatusIcons.syncAll(activity)
                }
            }
            "perf.getKeepAliveList" -> {
                runCatching {
                    val list = AppPerformance.getInstalledAppsInfo(activity)
                    activity.runOnUiThread {
                        webView.evaluateJavascript("window.__onKeepAliveApps && window.__onKeepAliveApps($list)", null)
                    }
                }
            }
            "perf.saveKeepAlive" -> {
                runCatching {
                    val obj = try { JsonParser.parseString(data).asJsonObject } catch (_: Exception) { null }
                    val pkgsArr = obj?.getAsJsonArray("packages")
                    val pkgs = ArrayList<String>()
                    if (pkgsArr != null) {
                        for (el in pkgsArr) {
                            pkgs.add(el.asString)
                        }
                    }
                    val result = AppPerformance.setKeepAliveApps(activity, pkgs)
                    lastToolsResult = result
                    pushToolsState()
                }
            }
        }
    }

    /** Версия APK для верхней строки («· EX2 v…»). */
    @JavascriptInterface
    fun appVersion(): String = BuildConfig.VERSION_NAME

    /** Stage T5: номер сборки для «О приложении»
     *  (settingsAppVersion(version, build) из SettingsV2). */
    @JavascriptInterface
    fun appBuild(): Int = BuildConfig.VERSION_CODE

    /**
     * Stage SCR: состояние настроек приложения для JS-инициализации страницы
     * (SET.imm/SET.wake при загрузке — до того, как рендер покажет тумблеры).
     */
    @JavascriptInterface
    fun appState(): String {
        val obj = tools.state()
        // Тумблер CarPlay/AA: до первой записи через шелл приоритет у
        // значения движка (его мог включить Flutter-экран телеметрии).
        if (!prefs().contains(ToolsBackend.Prefs.PROJECTION_BETA)) {
            obj.addProperty("proj", projectionBetaState() ?: false)
        }
        return obj.toString()
    }

    /** Настройки приложения живут в тех же prefs, что и цвет машины. */
    private fun prefs() = activity.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)

    /* ── Stage CXT: реестр настроек вкладки «Инструменты» ──
       Единая точка правды (persist + живой эффект + readback). WebView не
       хранит своё состояние: он рисует то, что вернул backend. */
    private val uiHandler = Handler(Looper.getMainLooper())

    private val tools by lazy {
        // Третий аргумент — постить живой эффект окна в UI-поток: применять
        // системные флаги можно только там, а readback обязан идти ПОСЛЕ
        // применения (см. ToolsBackend.onUi).
        ToolsBackend(activity, this) { r -> uiHandler.post(r) }
    }

    /** Результат последнего действия — для статусной строки и диагностики. */
    @Volatile
    private var lastToolsResult: ToolsResult? = null

    override fun applyImmersiveSetting(enabled: Boolean) {
        setImmersive(enabled)
    }

    override fun immersiveWindowFlags(): Int {
        @Suppress("DEPRECATION") // API 28: WindowInsetsController ещё нет
        return activity.window.decorView.systemUiVisibility
    }

    override fun setProjectionBetaSetting(enabled: Boolean) {
        vehicleLive?.setProjectionBeta(enabled)
    }

    override fun projectionBetaState(): Boolean? = runCatching {
        com.timhss.capyenergy.telemetry.TelemetrySettings(activity.applicationContext)
            .projectionBetaEnabled()
    }.getOrNull()

    /**
     * Отправить в JS состояние настроек вкладки (+ результат действия, если
     * он был). JS по этому снимку красит тумблеры, сегменты и статусы —
     * никаких локальных догадок на стороне интерфейса.
     */
    private fun pushToolsState() {
        val state = tools.state()
        lastToolsResult?.let { state.add("last", it.toJson()) }
        pushToUi("tools.state", state.toString())
    }

    /** Дубликат тостов интерфейса — сам тост уже показан в HTML. */
    /**
     * Stage CPv2: прямоугольник видео-области projection-вкладки, в
     * физических пикселях окна (JS: getBoundingClientRect × devicePixelRatio;
     * WebView занимает всё окно, системы координат совпадают). Мост кладёт
     * TextureView-оверлей ровно по этому прямоугольнику.
     */
    @JavascriptInterface
    fun projectionFrame(stack: String, x: Float, y: Float, w: Float, h: Float) {
        projectionRender?.frame(stack, x, y, w, h)
    }

    @JavascriptInterface
    fun toast(message: String) {
        Log.d(TAG, "toast: $message")
    }

    /** Настройка «Полный экран»: скрыть/показать системные панели ГУ. */
    @JavascriptInterface
    fun setImmersive(enabled: Boolean) {
        immersive = enabled
        activity.runOnUiThread { applyImmersive() }
    }

    /**
     * Stage UPD: состояние вкладки «Обновления» в JS.
     * Фазы: checking / available / uptodate / downloading / installing / error.
     */
    private fun pushUpdateState(
        phase: String,
        versionName: String? = null,
        changelog: String? = null,
        percent: Int? = null,
        message: String? = null
    ) {
        val obj = JsonObject()
        obj.addProperty("phase", phase)
        obj.addProperty("current", appVersion())
        versionName?.let { obj.addProperty("version", it) }
        changelog?.let { obj.addProperty("changelog", it) }
        percent?.let { obj.addProperty("percent", it) }
        message?.let { obj.addProperty("message", it) }
        pushToUi("update.state", obj.toString())
    }

    /**
     * Пуш данных из Kotlin в интерфейс.
     * [json] — строка JSON-объекта, например {"battPct":78}.
     */
    fun pushToUi(name: String, json: String) {
        val script = "window.__nativeEvent && window.__nativeEvent('$name', $json)"
        webView.post { webView.evaluateJavascript(script, null) }
    }

    /**
     * Stage NAV-MAP: фон виджета WebView при смене вкладок. Карта макета
     * лежит ПОД WebView, поэтому на вкладке «Навигация» виджет делается
     * прозрачным (сама страница в navfull-режиме тоже прозрачна — класс
     * переключает JS select()), видно только плавающий док и сверху
     * нативный хром. На прочих вкладках фон — page-цвет темы (тех, чтобы
     * при отрисовке-мигании страницы не было белой вспышки).
     */
    private fun applyNavWebViewBg(nav: Boolean, themeId: String?) {
        webView.post {
            if (nav) {
                webView.setBackgroundColor(Color.TRANSPARENT)
            } else {
                webView.setBackgroundColor(
                    com.example.voiceapp3.nav.NavTheme.pageBackdrop(themeId)
                )
            }
        }
    }

    /** Применить текущий режим системных панелей. Вызывать на UI-потоке. */
    fun applyImmersive() {
        val decor = activity.window.decorView
        @Suppress("DEPRECATION") // API 28: WindowInsetsController ещё нет, ГУ на Android 9
        decor.systemUiVisibility = if (immersive) {
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    /**
     * Stage VO: анимация вызова голоса «точь-в-точь как в макете» рисуется
     * самим интерфейсом (панель «Голос»: микрофон + кольца + «Слушаю…» + эквалайзер).
     * Сервис ищет живой мост через [instance]; если интерфейс мёртв —
     * показывает прежний OverlayActivity.
     */
    fun voiceUi(
        phase: String,
        text: String? = null,
        phrase: String? = null,
        reply: String? = null
    ) {
        val obj = JsonObject()
        obj.addProperty("phase", phase)
        text?.let { obj.addProperty("text", it) }
        phrase?.let { obj.addProperty("phrase", it) }
        reply?.let { obj.addProperty("reply", it) }
        pushToUi("voice.ui", obj.toString())
    }

    /** MainActivity.onDestroy: сервис больше не должен считать мост живым. */
    fun dispose() {
        if (companionInstance == this) companionInstance = null
    }

    init { companionInstance = this }

    companion object {
        private const val TAG = "UiBridge"

        /** Единственный живой мост UI (сервис голоса ищет его для voice.ui). */
        @Volatile
        var companionInstance: UiBridge? = null
            private set
    }
}
