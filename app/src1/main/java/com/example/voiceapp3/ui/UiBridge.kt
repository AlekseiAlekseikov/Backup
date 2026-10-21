package com.example.voiceapp3.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
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
import com.example.voiceapp3.tools.ToolsStep
import com.example.voiceapp3.tools.LockStateTrigger
import com.example.voiceapp3.tools.LockSoundPlayer
import android.os.SystemClock
import com.example.voiceapp3.TelemetryPrefsMirror
import com.example.voiceapp3.VoiceAssistantService
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.example.voiceapp3.ui.cx.CxEventSink
import com.example.voiceapp3.ui.cx.CxKeepAliveApp
import com.example.voiceapp3.ui.cx.CxStateSource
import com.example.voiceapp3.ui.cx.CxUi

/**
 * Мост между нативным интерфейсом (пакет `ui.cx`) и нативной частью.
 *
 * Все действия пользователя приходят в [onEvent]; каждое из них либо
 * маршрутизируется в реальный backend (движок телеметрии, VHAL-хендлеры,
 * настройки приложения), либо логзируется — полный протокол виден в logcat
 * по тегу UiBridge.
 *
 * Stage CXT: настройки вкладки «Инструменты» живут в реестре
 * [ToolsBackend] (один effective state: SharedPreferences + живой эффект +
 * readback). Интерфейс не держит своё состояние: он рисует снимок
 * `tools.state`, который мост отправляет по запросу каркаса ([snapshot])
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
 * Обратный канал (Kotlin → интерфейс): [pushToUi] вызывает
 * `CxUi.onNativeEvent(name, json)` — замена `window.__nativeEvent` и
 * слушателей CustomEvent 'native:<name>'.
 *
 * Реализует [CxEventSink] (действия интерфейса) и [CxStateSource] (первый
 * снимок), поэтому `MainActivity` передаёт мост в каркас напрямую.
 */
private const val PREF_CAR_PAINT = "pref_car_paint"

/* Stage CXT: имена ключей настроек приложения («Полный экран», «Запуск при
   пробуждении», «Полоса климата», CarPlay/AA, тема ГУ и палитра) живут в
   ToolsBackend.Prefs — один реестр на интерфейс, mirror в Flutter-prefs и
   загрузку состояния. Имя ключа immersive оставлено как в capy
   (AppExperienceController._immersiveKey), чтобы семантика и значение по
   умолчанию совпадали один в один. */

class UiBridge(
    private val activity: Activity,
    private val cx: CxUi,
    // GeelyTools deviation: вместо полного WebView-порта телеметрии — тонкий
    // live-фид движка (навигация/кузов/карточка батареи), см. VehicleLiveAdapter.
    private val vehicleLive: VehicleLiveAdapter? = null
) : ToolsHost, CxEventSink, CxStateSource {

    /**
     * [CxEventSink]: действия интерфейса идут тем же единственным каналом,
     * что и раньше из JS. Имя и форма payload не изменились, поэтому
     * [dispatchEvent] правок не потребовал.
     */
    override fun emit(name: String, json: String) = onEvent(name, json)

    /**
     * Активный стек проекции — то имя, которое шелл присылал первым аргументом
     * `projectionFrame(stack, …)`. Каркас его не знает и знать не должен: он
     * меряет только прямоугольник, а соответствие стеку ведёт мост по событиям
     * `projection.activate` / `projection.deactivate`.
     */
    @Volatile
    private var activeProjectionStack: String? = null

    init {
        /* Геометрия видео-оверлея CarPlay/Android Auto. Замена
           @JavascriptInterface projectionFrame: каркас публикует
           прямоугольник слоя-якоря после каждой раскладки, мост подставляет
           имя активного стека. Пока стек не активирован — ничего не делаем:
           оверлея нет, и класть его некуда. */
        cx.onProjectionFrame = { rect ->
            activeProjectionStack?.let { projectionFrame(it, rect) }
        }
    }

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

    /**
     * Поток обработки событий интерфейса. Один поток — порядок событий
     * сохраняется, параллельных обращений к shell-каналу не возникает.
     */
    private val eventExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "geelytools-events").apply { isDaemon = true }
        }

    /**
     * Единый канал событий из интерфейса.
     *
     * Тело ВЫНЕСЕНО в [dispatchEvent] намеренно. Ветки ниже ходят в
     * shell-канал [AdbClient] с таймаутами 8…60 секунд, поэтому любое нажатие
     * подвешивало бы UI-поток на время таймаута — а интерфейс теперь живёт
     * именно в этом потоке, а не в отдельном JavaBridge-треде WebView.
     * Обратная связь приходит тем же единственным каналом
     * `pushToUi("tools.state")`.
     */
    fun onEvent(name: String, data: String) {
        Log.d(TAG, "event: $name $data")
        eventExecutor.execute {
            try {
                dispatchEvent(name, data)
            } catch (t: Throwable) {
                Log.e(TAG, "dispatchEvent($name) упал: " +
                    com.example.voiceapp3.tools.describeThrowable(t), t)
            }
        }
    }

    /** Обработчик событий; всегда выполняется в потоке [eventExecutor]. */
    private fun dispatchEvent(name: String, data: String) {
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
            "projection.activate" -> {
                val stack = JsonParser.parseString(data).asJsonObject
                    .get("stack")?.asString ?: return
                val s = ProjectionRenderBridge.stackFromWire(stack) ?: return
                /* Имя стека запоминаем до активации: геометрию оверлея каркас
                   публикует по раскладке, независимо от порядка событий. */
                activeProjectionStack = stack
                projectionRender?.activate(s)
            }
            "projection.deactivate" -> {
                val stack = JsonParser.parseString(data).asJsonObject
                    .get("stack")?.asString ?: return
                val s = ProjectionRenderBridge.stackFromWire(stack) ?: return
                if (activeProjectionStack == stack) activeProjectionStack = null
                projectionRender?.deactivate(s)
            }
            "projection.refresh" -> {
                val s = ProjectionRenderBridge.stackFromWire(
                    JsonParser.parseString(data).asJsonObject.get("stack")?.asString ?: return
                ) ?: return
                projectionRender?.refresh(s)
            }
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
                        // eventExecutor).
                        activity.runOnUiThread {
                            if (id == "telemetry") telemetryHost?.show()
                            else telemetryHost?.hide()
                            // Stage NAV-MAP: карта живёт ровно на своей вкладке.
                            if (id == "navi") navHost?.show()
                            else navHost?.hide()
                            // …тема из события сразу доступна: хром и карта
                            // меняют палитру. Фон виджета-подложки больше не
                            // нужен: страницу вкладки рисует каркас, а она у
                            // вкладок с нативным хостом прозрачна сама.
                            obj.get("theme")?.asString?.let { navHost?.onThemeChanged(it) }
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
                    lastToolsResult = LockSound.playPreview(activity, isLock = false)
                    pushToolsState()
                }
            }
            "audio.restoreFactoryUnlock" -> {
                runCatching {
                    lastToolsResult = LockSound.restoreFactoryUnlock(activity)
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
            /* Выбор файла через системный ACTION_OPEN_DOCUMENT. Прежний
               ACTION_GET_... давал разовый грант, а файл нужен после
               перезагрузки (boot-переприменение). */
            "audio.selectUnlockFile", "audio.selectFile" -> {
                runCatching {
                    val isLock = name == "audio.selectFile"
                    val obj = try { JsonParser.parseString(data).asJsonObject } catch (_: Exception) { null }
                    val fileName = obj?.get("name")?.asString ?: ""
                    if (fileName.isNotEmpty()) {
                        lastToolsResult = LockSound.selectFileByName(activity, fileName, isLock)
                    } else {
                        lastToolsResult = openDocumentPicker(isLock)
                    }
                    pushToolsState()
                }
            }
            /* Диагностика источника события замка (п.2 ТЗ): подбор свойства,
               снимок состояния, лог перехвата и инъекция значения для проверки
               фильтра по фронту без железа. */
            "dev.probeLockSignal" -> {
                runCatching {
                    val msg = LockStateTrigger.startProbe(activity)
                    lastToolsResult = lockDiagResult("dev.probeLockSignal", "Подбор сигнала замка", msg, true)
                    pushToolsState()
                }
            }
            "dev.probeLockResult" -> {
                runCatching {
                    val lines = if (LockStateTrigger.isProbing()) {
                        listOf("подбор ещё идёт — нажмите кнопку позже")
                    } else {
                        LockStateTrigger.probeResult().ifEmpty { listOf("подбор не запускался") }
                    }
                    lastToolsResult = lockDiagResult(
                        "dev.probeLockResult", "Результат подбора сигнала",
                        lines.joinToString("\n"), !LockStateTrigger.isProbing()
                    )
                    pushToolsState()
                }
            }
            "dev.lockStatus" -> {
                runCatching {
                    LockStateTrigger.start(activity)
                    lastToolsResult = lockDiagResult(
                        "dev.lockStatus", "Состояние триггера замка",
                        LockStateTrigger.statusLine(activity) + "\nмаршрут: " + LockSoundPlayer.routeReport(activity),
                        true
                    )
                    pushToolsState()
                }
            }
            "dev.lockLog" -> {
                runCatching {
                    val log = LockStateTrigger.lockLog()
                    lastToolsResult = lockDiagResult(
                        "dev.lockLog", "Лог перехвата состояний замка",
                        if (log.isEmpty()) "событий ещё не было" else log.joinToString("\n"),
                        true
                    )
                    pushToolsState()
                }
            }
            "dev.injectLockEvent" -> {
                runCatching {
                    val obj = try { JsonParser.parseString(data).asJsonObject } catch (_: Exception) { null }
                    val value = obj?.get("value")?.asString ?: "0"
                    LockStateTrigger.start(activity)
                    val report = LockStateTrigger.injectForTest(activity, value)
                    lastToolsResult = lockDiagResult(
                        "dev.injectLockEvent", "Проверка фильтра по фронту (значение $value)", report, true
                    )
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
                        cx.wiring.onDevCommandResult(output, exitCode)
                        cx.refresh()
                    }
                }
            }
            "dev.getLogcat" -> {
                runCatching {
                    val (exitCode, output) = AdbClient.execute("logcat -d -t 120 -s CentralWatchdog:V LockSound:V WindowDoors:V NetworkTtl:V UiBridge:V ToolsBackend:V", 8000)
                    activity.runOnUiThread {
                        cx.wiring.onDevLogcat(output)
                        cx.refresh()
                    }
                }
            }
            /* Проверка маршрута вывода: играем выбранный файл разблокировки тем
               же путём, каким его сыграет триггер замка, и возвращаем честный
               отчёт (включая результат setPreferredDevice). */
            "dev.testUnlockSound" -> {
                runCatching {
                    val path = LockSound.getUnlockSelectedPath(activity)
                        .ifEmpty { LockSound.getSelectedPath(activity) }
                    if (path.isEmpty()) {
                        lastToolsResult = lockDiagResult(
                            "dev.testUnlockSound", "Проверка маршрута звука",
                            "файл не выбран: ключи ${LockSound.KEY_UNLOCK_PATH} и ${LockSound.KEY_LOCK_PATH} пусты",
                            false
                        )
                    } else {
                        val f = java.io.File(path)
                        val report = LockSoundPlayer.play(
                            activity, f, LockSound.getVolume(activity), LockSound.getDelayMs(activity)
                        )
                        lastToolsResult = lockDiagResult(
                            "dev.testUnlockSound", "Проверка маршрута звука ($path)", report,
                            !report.startsWith("воспроизведение не удалось") && !report.startsWith("файл ")
                        )
                    }
                    pushToolsState()
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
                        cx.wiring.onKeepAliveApps(keepAliveApps(list))
                        cx.refresh()
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

    /**
     * Версия APK для верхней строки («· EX2 v…»).
     *
     * В шелле её забирал JS (`N.appVersion()` в `updVer()` и в подписи
     * карточки соединения). Теперь каркас получает её строкой через
     * `CxStrings` — см. `CxShell.bindTopBar`, ключ `appVersion`.
     */
    fun appVersion(): String = BuildConfig.VERSION_NAME

    /**
     * Stage T5: номер сборки.
     *
     * ВНИМАНИЕ, расхождение кода и комментария: комментарий обещал
     * «settingsAppVersion(version, build) из SettingsV2», но ни `appBuild`,
     * ни `settingsAppVersion` в `index.html` не встречаются ни разу — вызова
     * не было и до перехода на нативный интерфейс. Аннотация снята, метод
     * оставлен как есть: он публичный, и удалять чужой код без спроса нельзя.
     * Потребителя у него нет.
     */
    fun appBuild(): Int = BuildConfig.VERSION_CODE

    /**
     * Stage SCR: состояние настроек приложения для первого снимка — каркас
     * забирает его синхронно при построении, до первой отрисовки тумблеров.
     *
     * Был `@JavascriptInterface fun appState(): String` (JS звал его при
     * инициализации страницы). Стал реализацией [CxStateSource], поэтому
     * возвращает объект, а не строку: разбор JSON туда-обратно был нужен
     * только для передачи через JS-мост.
     */
    override fun snapshot(): JsonObject {
        val obj = tools.state()
        // Тумблер CarPlay/AA: до первой записи через интерфейс приоритет у
        // значения движка (его мог включить Flutter-экран телеметрии).
        if (!prefs().contains(ToolsBackend.Prefs.PROJECTION_BETA)) {
            obj.addProperty("proj", projectionBetaState() ?: false)
        }
        /* Верхняя строка карточки соединения («Подключено к машине · EX2 v…»):
           в шелле JS звал `N.appVersion()` напрямую (index.html:2671 и
           `updVer()` в 2019), теперь значение приезжает в снимке — ключ
           `appVersion` читает `CxShell.bindTopBar`. */
        if (!obj.has("appVersion")) obj.addProperty("appVersion", appVersion())
        return obj
    }

    /** Настройки приложения живут в тех же prefs, что и цвет машины. */
    private fun prefs() = activity.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)

    /* ── Срез «Звуки замка»: выбор файла и диагностика источника события ── */

    /**
     * Системный выбор аудиофайла. `ACTION_OPEN_DOCUMENT` +
     * `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` — без этого право чтения живёт
     * только до перезагрузки, а boot-переприменение звука её обязано пережить.
     * Результат приходит в [MainActivity.onActivityResult].
     */
    private fun openDocumentPicker(isLock: Boolean): ToolsResult {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(
                android.content.Intent.EXTRA_MIME_TYPES,
                arrayOf("audio/*", "application/octet-stream")
            )
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        return try {
            activity.startActivityForResult(
                intent, if (isLock) REQ_PICK_LOCK_AUDIO else REQ_PICK_UNLOCK_AUDIO
            )
            ToolsResult(
                action = if (isLock) "audio.selectFile" else "audio.selectUnlockFile",
                title = if (isLock) "Звук блокировки" else "Звук разблокировки",
                steps = listOf(
                    ToolsStep(
                        step = "open_document",
                        command = "startActivityForResult(ACTION_OPEN_DOCUMENT, audio/*, " +
                            "FLAG_GRANT_READ_URI_PERMISSION|FLAG_GRANT_PERSISTABLE_URI_PERMISSION)",
                        startedAtMs = SystemClock.elapsedRealtime(),
                        finishedAtMs = SystemClock.elapsedRealtime(),
                        exitCode = 0,
                        stdout = "системный выбор файла открыт",
                        status = ToolsStep.Status.OK
                    )
                )
            )
        } catch (t: Throwable) {
            val why = t.javaClass.name + ": " + (t.message ?: "<нет message>") +
                (t.cause?.let { " | cause: " + it.javaClass.name + ": " + it.message } ?: "")
            AppLog.e(activity, "GeelyTools", "ACTION_OPEN_DOCUMENT не запустился: $why", t)
            ToolsResult(
                action = if (isLock) "audio.selectFile" else "audio.selectUnlockFile",
                title = if (isLock) "Звук блокировки" else "Звук разблокировки",
                steps = listOf(
                    ToolsStep(
                        step = "open_document",
                        command = "startActivityForResult(ACTION_OPEN_DOCUMENT)",
                        startedAtMs = SystemClock.elapsedRealtime(),
                        finishedAtMs = SystemClock.elapsedRealtime(),
                        exitCode = 1,
                        stderr = why,
                        status = ToolsStep.Status.FAILED
                    )
                )
            )
        }
    }

    /** Обработка результата системного выбора файла; true если запрос наш. */
    fun onAudioPicked(requestCode: Int, resultCode: Int, data: android.content.Intent?): Boolean {
        if (requestCode != REQ_PICK_LOCK_AUDIO && requestCode != REQ_PICK_UNLOCK_AUDIO) return false
        val isLock = requestCode == REQ_PICK_LOCK_AUDIO
        val uri = if (resultCode == android.app.Activity.RESULT_OK) data?.data else null
        lastToolsResult = if (uri == null) {
            ToolsResult(
                action = if (isLock) "audio.selectFile" else "audio.selectUnlockFile",
                title = if (isLock) "Звук блокировки" else "Звук разблокировки",
                steps = listOf(
                    ToolsStep(
                        step = "open_document",
                        command = "onActivityResult(requestCode=$requestCode, resultCode=$resultCode)",
                        startedAtMs = SystemClock.elapsedRealtime(),
                        finishedAtMs = SystemClock.elapsedRealtime(),
                        exitCode = 1,
                        stderr = "файл не выбран (resultCode=$resultCode, data=${data ?: "null"}) — запись не выполнялась",
                        status = ToolsStep.Status.FAILED
                    )
                )
            )
        } else {
            LockSound.selectFromUri(activity, uri, isLock)
        }
        pushToolsState()
        return true
    }

    /** Одношаговый результат диагностики (не привилегированная операция). */
    private fun lockDiagResult(action: String, title: String, report: String, ok: Boolean): ToolsResult =
        ToolsResult(
            action = action,
            title = title,
            steps = listOf(
                ToolsStep(
                    step = "diagnostics",
                    command = action,
                    startedAtMs = SystemClock.elapsedRealtime(),
                    finishedAtMs = SystemClock.elapsedRealtime(),
                    exitCode = if (ok) 0 else 1,
                    stdout = report,
                    stderr = if (ok) "" else report,
                    status = if (ok) ToolsStep.Status.OK else ToolsStep.Status.FAILED
                )
            )
        )

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

    /**
     * Stage CPv2: прямоугольник видео-области projection-вкладки в физических
     * пикселях окна. Мост кладёт TextureView-оверлей ровно по нему.
     *
     * Был `@JavascriptInterface`: координаты приходили из JS как
     * `getBoundingClientRect × devicePixelRatio`, и системы координат
     * совпадали потому, что WebView занимал всё окно. Теперь их измеряет
     * каркас — `CxShell.projectionFramePx()` по слою-якорю, который занимает
     * всё окно точно так же, — поэтому совпадение сохраняется, а плотность
     * экрана не умножается: значения View уже физические.
     *
     * Имя стека каркас не знает (его определяет слой активации проекции),
     * поэтому `MainActivity` берёт его сам:
     *
     *     cx.onProjectionFrame = { r -> bridge.projectionFrame(stack, r) }
     */
    fun projectionFrame(stack: String, rect: IntArray) {
        projectionRender?.frame(
            stack, rect[0].toFloat(), rect[1].toFloat(),
            rect[2].toFloat(), rect[3].toFloat()
        )
    }

    /**
     * Дубликат тостов интерфейса в logcat — сам тост показывает каркас
     * (`CxUi.onSay`), как раньше его показывал HTML.
     */
    fun toast(message: String) {
        Log.d(TAG, "toast: $message")
    }

    /** Настройка «Полный экран»: скрыть/показать системные панели ГУ. */
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
     *
     * Замена `window.__nativeEvent(name, json)` через `evaluateJavascript`.
     * Маршалинг на UI-поток сохранён: раньше его делал `webView.post`, теперь
     * `runOnUiThread`, потому что [CxUi.onNativeEvent] перерисовывает View.
     */
    fun pushToUi(name: String, json: String) {
        activity.runOnUiThread { cx.onNativeEvent(name, json) }
    }

    /**
     * Преобразование ответа `AppPerformance.getInstalledAppsInfo` в список
     * каркаса. Форма совпадает поле в поле (`label`, `packageName`,
     * `enabled`) — раньше этот JsonArray сериализовался в строку и
     * разбирался уже в JS, теперь разбирается здесь.
     */
    private fun keepAliveApps(list: com.google.gson.JsonArray): List<CxKeepAliveApp> {
        val out = ArrayList<CxKeepAliveApp>(list.size())
        for (e in list) {
            if (!e.isJsonObject) continue
            val o = e.asJsonObject
            out.add(
                CxKeepAliveApp(
                    label = o.get("label")?.takeIf { !it.isJsonNull }?.asString
                        ?: o.get("packageName")?.asString.orEmpty(),
                    packageName = o.get("packageName")?.asString.orEmpty(),
                    enabled = o.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                )
            )
        }
        return out
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

        /** Коды запросов системного выбора аудиофайла (ACTION_OPEN_DOCUMENT). */
        const val REQ_PICK_LOCK_AUDIO = 7001
        const val REQ_PICK_UNLOCK_AUDIO = 7002

        /** Единственный живой мост UI (сервис голоса ищет его для voice.ui). */
        @Volatile
        var companionInstance: UiBridge? = null
            private set
    }
}
