package com.example.voiceapp3.ui

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import android.content.Intent
import android.graphics.Color
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
 *   tab.change            -> выбранная вкладка (ShellTabs)
 *   update.*              -> AppUpdater
 *
 * Обратный канал (Kotlin → JS): [pushToUi] вызывает
 *   window.__nativeEvent(name, json)
 * интерфейс диспатчит CustomEvent 'native:<name>' (см. конец index.html).
 */
private const val PREF_CAR_PAINT = "pref_car_paint"
/** Тумблер «Автообновление» (вкладка «Обновления»): true — проверять и ставить сами. */
const val PREF_UPDATE_AUTO = "update.auto"

/* Stage CXT: имена ключей настроек приложения («Полный экран», «Запуск при
   пробуждении», «Полоса климата», CarPlay/AA, тема ГУ и палитра) живут в
   ToolsBackend.Prefs — один реестр на WebView, mirror в Flutter-prefs и
   загрузку состояния. Имя ключа immersive оставлено как в capy
   (AppExperienceController._immersiveKey), чтобы семантика и значение по
   умолчанию совпадали один в один. */

class UiBridge(
    private val activity: Activity,
    private val shellRoot: ViewGroup,
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

    /** Нативный док вкладок шелла (замена HTML #tabbar, см. NativeDock). */
    var nativeDock: NativeDock? = null
    /** Этап 9b: вкладки шелла и правило CarPlay / AA (нативно). */
    var shellTabs: ShellTabs? = null

    /** Нативная вкладка «Быстрый доступ» (замена HTML-панели quick, см. QuickPanel). */
    var quickPanel: QuickPanel? = null
    var carCard: CarCardPanel? = null
    /** Нативная геометрия главного экрана (замена JS-замеров). */
    var shellGeometry: ShellGeometry? = null
    /** Нативная верхняя строка и тосты (замена HTML .topbar и #toast). */
    var topBar: TopBarPanel? = null
    var toastPanel: ToastPanel? = null
    /** Нативная полоса климата (замена HTML #climBar). */
    var climBar: ClimBarPanel? = null

    /** Нативная вкладка «Голос» (замена HTML-панели voice, см. VoicePanel). */
    var voicePanel: VoicePanel? = null

    /** Нативная вкладка «Приложения» (замена HTML-панели apps, см. ApplicationsPanel). */
    var appsPanel: ApplicationsPanel? = null
    /** Нативная вкладка «Видеорегистратор» (каркас, DashcamPanel). */
    var dashcamPanel: DashcamPanel? = null
    /** Нативная вкладка «Сервис» (каркас, ServicePanel). */
    var servicePanel: ServicePanel? = null
    /** Нативная вкладка «Обновления» (UpdatesPanel): получает снимки update.state. */
    var updatesPanel: UpdatesPanel? = null
    /** Этап 9: сообщение над видео CarPlay / AA (статусы, рамка, активация). */
    var projectionMessage: ProjectionMessagePanel? = null

    /** Нативная вкладка «Инструменты», этап 5a (см. ToolsPanel). */
    var toolsPanel: ToolsPanel? = null

    /**
     * Stage B7: управление кузовом (капот/багажник) — тот же путь VHAL,
     * что у голосового TrunkControlHandler. Замок — только индикатор
     * (управления нет ни в проекте, ни в источниках телеметрии).
     */
    private val bodyControl by lazy {
        BodyControlAdapter(activity) { name, json ->
            // Результат открытия показываем нативным тостом: JS этого события не слушает.
            if (name == "telemetry.actionDone") {
                runCatching {
                    val msg = JsonParser.parseString(json).asJsonObject.get("message")?.asString
                    if (!msg.isNullOrBlank()) activity.runOnUiThread {
                        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { Log.w(TAG, "actionDone parse failed", it) }
            } else {
                pushToUi(name, json)
            }
        }
    }

    /**
     * Stage UPD: последний релиз, найденный проверкой (update.check).
     * Команда «Установить» (update.install) ставит именно его — как
     * «Сначала проверьте обновление» у любого апдейтера.
     */
    @Volatile
    private var lastRemote: AppUpdater.Remote? = null

    /** Автообновление: повторная проверка раз в 6 ч, пока приложение открыто. */
    private val updateHandler = Handler(Looper.getMainLooper())
    private val autoCheckTask: Runnable = Runnable {
        if (autoUpdateEnabled()) {
            startUpdateCheck()
            updateHandler.postDelayed(autoCheckTask, AUTO_CHECK_INTERVAL_MS)
        }
    }
    /** Скачивание или установка идут: новые запуски не стартуем. */
    @Volatile private var updateBusy = false
    /** Версия, для которой автоустановка уже запущена; сбрасывается при ошибке — повтор на следующей проверке. */
    private var lastAutoInstallCode = -1

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

    /** Тап по фразе каталога «Голос»: сервис обрабатывает её как распознанную речь. */
    private fun hubPhrase(text: String?) {
        val i = Intent(VoiceAssistantService.ACTION_HUB)
            .putExtra(VoiceAssistantService.EXTRA_HUB, VoiceAssistantService.HUB_PHRASE)
            .putExtra(VoiceAssistantService.EXTRA_TEXT, text.orEmpty())
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
        com.example.voiceapp3.tools.BgWorker.post("UiBridge.onResume") {
            runCatching { ScreenBrightness.syncOnContext(activity) }
            runCatching { StatusIcons.syncAll(activity) }
        }
    }

    /**
     * Снимок телеметрии и присутствие проекций (раньше — telemetry.request из JS
     * при загрузке страницы и при выборе вкладки «Телеметрия»).
     */
    fun telemetryRequest() {
        vehicleLive?.request()
        com.timhss.capyenergy.projection.ProjectionPresenceBridge.get(activity)?.publishLatest()
        projectionRender?.publishStatuses()
    }

    /** Единый канал событий из интерфейса. */
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
                    selectTab("telemetry")
                }.onFailure { Log.w(TAG, "telemetry.open failed", it) }
            }
            // Действующая палитра шелла (каталог THEMES index.html ↔
            // AppThemeId capy). JS шлёт её при загрузке страницы и каждой смене
            // темы; она персистится в ОТДЕЛЬНЫЙ ключ и зеркалируется во
            // Flutter-prefs перед стартом движка телеметрии
            // (TelemetryPrefsMirror). Выбор плиток (ui_shell_theme_id) здесь не
            // трогается: при «Следовать теме ГУ» ui.theme приносит тёмный
            // вариант, и запись его в ключ выбора ломала бы обратный ход.
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
                projectionRender?.activate(
                    ProjectionRenderBridge.stackFromWire(
                        JsonParser.parseString(data).asJsonObject.get("stack")?.asString ?: return
                    ) ?: return
                )
                wireStack(data)?.let { s -> activity.runOnUiThread { projectionMessage?.setActive(s) } }
            }
            "projection.deactivate" -> {
                projectionRender?.deactivate(
                    ProjectionRenderBridge.stackFromWire(
                        JsonParser.parseString(data).asJsonObject.get("stack")?.asString ?: return
                    ) ?: return
                )
                wireStack(data)?.let { s -> activity.runOnUiThread { projectionMessage?.setInactive(s) } }
            }
            "projection.refresh" -> projectionRender?.refresh(
                ProjectionRenderBridge.stackFromWire(
                    JsonParser.parseString(data).asJsonObject.get("stack")?.asString ?: return
                ) ?: return
            )
            "voice.request" -> hub(VoiceAssistantService.HUB_LISTEN)
            "voice.phrase" -> runCatching {
                val text = JsonParser.parseString(data).asJsonObject.get("text")?.asString
                hubPhrase(text)
            }.onFailure { Log.w(TAG, "voice.phrase parse failed", it) }
            "voice.micRestart" -> hub(VoiceAssistantService.HUB_RESTART_MIC)
            // ── Stage UPD: вкладка «Обновления» — GitHub Releases ──
            "update.check" -> startUpdateCheck()
            "update.install" -> startUpdateInstall()
            "update.auto" -> setAutoUpdate(data)
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
            // Нативные вкладки (быстрый доступ, голос): JS публикует геометрию
            // #content (физ. px) и активность каждой вкладки; нативная панель
            // накрывает #content, когда её вкладка активна.
            // Геометрия пустого слота #carSlot (якорь нативной карточки, до переноса главного экрана).
            "toast" -> {
                runCatching {
                    val msg = JsonParser.parseString(data).asJsonObject.get("message")?.asString
                    if (!msg.isNullOrBlank()) activity.runOnUiThread { toastPanel?.show(msg) }
                }.onFailure { Log.w(TAG, "toast parse failed", it) }
            }
            // GeelyTools deviation: команды WebView-экранов телеметрии
            // (sync.now, charge.*, charges.merge, energy.window, history.series,
            // cycle.sessions, places.*, lab.*, tool.act) удалены вместе с
            // экранами — эти операции выполняет Flutter-приложение телеметрии.
            "settings.change" -> {
                // P1: applyFromUi для TTL/perf/mount/brightness — тяжёлые adb/iptables/VHAL.
                // Парсим JSON на UI-потоке (быстро), работу — в BgWorker, результат возвращаем на main.
                val parsed = runCatching {
                    val obj: JsonObject = JsonParser.parseString(data).asJsonObject
                    val key = obj.get("key")?.asString
                    val element = obj.get("value")
                    if (key == null || element == null) null
                    else {
                        val value: Any = if (element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) {
                            element.asBoolean
                        } else {
                            element.asString
                        }
                        key to value
                    }
                }.getOrElse { Log.w(TAG, "settings.change parse failed", it); null }
                if (parsed != null) {
                    val (key, value) = parsed
                    if (key == "theme") {
                        TelemetryPrefsMirror.rememberShellTheme(activity, value.toString())
                    }
                    com.example.voiceapp3.tools.BgWorker.run("UiBridge.settings.change/$key", {
                        tools.applyFromUi(key, value)
                    }) { res ->
                        res.getOrNull()?.let { result ->
                            lastToolsResult = result
                            pushToolsState()
                        }
                        res.exceptionOrNull()?.let { Log.w(TAG, "settings.change $key failed", it) }
                    }
                }
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
                val cmd = runCatching {
                    (JsonParser.parseString(data).asJsonObject?.get("command")?.asString) ?: "id"
                }.getOrDefault("id")
                com.example.voiceapp3.tools.BgWorker.run("UiBridge.dev.runCommand", {
                    AdbClient.execute(cmd, 15000)
                }) { res ->
                    val (rc, out) = res.getOrDefault(1 to "ERR: ${res.exceptionOrNull()?.message}")
                    activity.runOnUiThread { toolsPanel?.onDevCmdResult(rc, out) }
                }
            }
            "dev.getLogcat" -> {
                com.example.voiceapp3.tools.BgWorker.run("UiBridge.dev.getLogcat", {
                    AdbClient.execute("logcat -d -t 120 -s CENTRAL_*:V", 8000)
                }) { res ->
                    val (_, out) = res.getOrDefault(1 to "ERR: ${res.exceptionOrNull()?.message}")
                    activity.runOnUiThread { toolsPanel?.onLogcat(out) }
                }
            }
            "dev.checkRoot" -> {
                com.example.voiceapp3.tools.BgWorker.run("UiBridge.dev.checkRoot", {
                    AdbClient.probeRootChannel()
                }) { res ->
                    val r = res.getOrNull()
                    val msg = if (r != null)
                        "PORT65010: connect → ${if(r.port>0)"OK on port ${r.port}" else "no port"}\n" +
                        "handshake CNXN → OK\n" +
                        "id → ${r.uidLine}\n" +
                        "state=${r.state}\n" +
                        "ИТОГ = ${r.verdict}"
                    else "ERR: ${res.exceptionOrNull()?.message}"
                    activity.runOnUiThread { toolsPanel?.onDevCmdResult(0, msg) }
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
                com.example.voiceapp3.tools.BgWorker.post("UiBridge.dev.simulateDoorOpen") {
                    runCatching {
                        WindowDoors.initCarService(activity)
                        AdbClient.execute("dumpsys car_service inject-event 0x16400b00 1 1 2>/dev/null")
                    }
                    // playExternalSound работает с MediaPlayer/AudioTrack — можно на main, но запуск из фона безопаснее
                    Handler(Looper.getMainLooper()).post {
                        val file = java.io.File("/vendor/etc/carlock/dajidali.wav")
                        LockSound.playExternalSound(activity, file, LockSound.getVolume(activity))
                    }
                }
            }
            "dev.simulateDoorClose" -> {
                com.example.voiceapp3.tools.BgWorker.post("UiBridge.dev.simulateDoorClose") {
                    runCatching {
                        WindowDoors.initCarService(activity)
                        AdbClient.execute("dumpsys car_service inject-event 0x16400b00 1 2 2>/dev/null")
                    }
                }
            }
            "dev.testNotificationIcons" -> {
                com.example.voiceapp3.tools.BgWorker.post("UiBridge.dev.testNotif") {
                    runCatching { StatusIcons.syncAll(activity) }
                }
            }
            "perf.getKeepAliveList" -> {
                com.example.voiceapp3.tools.BgWorker.run("UiBridge.perf.appList", {
                    AppPerformance.getInstalledAppsInfo(activity)
                }) { res ->
                    val list = res.getOrDefault(emptyList())
                    activity.runOnUiThread { toolsPanel?.onKeepAliveApps(list.toString()) }
                }
            }
            "perf.saveKeepAlive" -> {
                val pkgs = runCatching {
                    val arr = JsonParser.parseString(data).asJsonObject?.getAsJsonArray("packages")
                    val list = ArrayList<String>()
                    arr?.forEach { list.add(it.asString) }
                    list
                }.getOrDefault(ArrayList())
                com.example.voiceapp3.tools.BgWorker.run("UiBridge.perf.saveKeepAlive", {
                    AppPerformance.setKeepAliveApps(activity, pkgs)
                }) { res ->
                    res.getOrNull()?.let { result ->
                        lastToolsResult = result
                        pushToolsState()
                    }
                }
            }
        }
    }

    /** Версия APK для верхней строки («· EX2 v…»). */
    fun appVersion(): String = BuildConfig.VERSION_NAME

    /** Stage T5: номер сборки для «О приложении»
     *  (settingsAppVersion(version, build) из SettingsV2). */
    fun appBuild(): Int = BuildConfig.VERSION_CODE

    /**
     * Stage SCR: состояние настроек приложения для JS-инициализации страницы
     * (SET.imm/SET.wake при загрузке — до того, как рендер покажет тумблеры).
     */
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
        activity.runOnUiThread { shellTabs?.refresh() }
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
        // W3: действующая палитра считается нативно (раньше — JS applyTheme).
        refreshShellTheme()
        pushToUi("tools.state", state.toString())
        // Нативная панель «Инструменты» (5a) получает тот же снимок в UI-потоке.
        val snapshot = state.toString()
        activity.runOnUiThread { toolsPanel?.onToolsState(snapshot) }
    }

    /**
     * W3: действующая палитра шелла считается нативно (раньше — JS applyTheme).
     * Тёмная, если включены «Следовать теме ГУ» и тема ГУ тёмная; иначе — выбранная плитка.
     * Выбор по умолчанию совпадает с инструментами («sepia»).
     *
     * Аудит §5.1: БАГ БЫЛ — `gu` читалось из SharedPreferences
     * (`ui_head_unit_theme_mode`), а это значение, выставленное сегментным
     * переключателем «Светлая/Тёмная» на этой же странице, а не фактическая
     * тема ГУ. Правильный источник — Configuration.uiMode
     * (UI_MODE_NIGHT_YES = «ночь/фары включены»), читаемый через
     * HuNightMode.isNight(). Тема ГУ для мануального режима (`gu`) теперь
     * применяется только когда follow = false.
     */
    fun refreshShellTheme() {
        val prefs = activity.getSharedPreferences(ToolsBackend.Prefs.FILE, Context.MODE_PRIVATE)
        val follow = prefs.getBoolean(ToolsBackend.Prefs.THEME_FOLLOW_HU, true)
        val chosen = prefs.getString(TelemetryPrefsMirror.KEY_SHELL_THEME, null)
            ?.takeIf { it in TelemetryPrefsMirror.THEME_IDS } ?: "sepia"
        val id: String = when {
            follow && com.example.voiceapp3.tools.HuNightMode.isNight(activity) -> "dark"
            follow -> chosen
            else -> {
                // Ручной режим: «Светлая/Тёмная» сегментом gu (как было).
                val gu = prefs.getString(ToolsBackend.Prefs.HEAD_UNIT_THEME, "light") ?: "light"
                if (gu == "dark") "dark" else chosen
            }
        }
        applyShellTheme(id)
    }

    /** Слушатель живых смен темы ГУ — подцепляется один раз при создании UiBridge. */
    private val huNightListener: () -> Unit = {
        android.util.Log.i("UiBridge", "HU night-mode changed → refreshing shell theme")
        activity.runOnUiThread { refreshShellTheme() }
    }

    private fun applyShellTheme(id: String) {
        TelemetryPrefsMirror.rememberEffectiveTheme(activity, id)
        activity.runOnUiThread {
            navHost?.onThemeChanged(id)
            applyShellBackground(lastUiTab == "navi", id)
            nativeDock?.setTheme(id)
            quickPanel?.setTheme(id)
            carCard?.setTheme(id)
            topBar?.setTheme(id)
            toastPanel?.setTheme(id)
            voicePanel?.setTheme(id)
            appsPanel?.setTheme(id)
            dashcamPanel?.setTheme(id)
            servicePanel?.setTheme(id)
            updatesPanel?.setTheme(id)
            toolsPanel?.setTheme(id)
        }
    }

    /** Дубликат тостов интерфейса — сам тост уже показан в HTML. */
    /**
     * Выбор вкладки (W2): вызывается из нативного дока, из карточки/баннера и из
     * ShellTabs. Раньше шёл круг через JS (ui.selectTab → select → tab.change).
     * Тема — действующая палитра из TelemetryPrefsMirror (её пишет ui.theme).
     */
    fun selectTab(id: String) {
        lastUiTab = id
        vehicleLive?.startLive()
        if (id == "telemetry") telemetryRequest()
        val themeId = com.example.voiceapp3.TelemetryPrefsMirror.currentThemeId(activity)
        activity.runOnUiThread {
            topBar?.setVisible(id != "carplay" && id != "aa")
            shellGeometry?.setTab(id, fresh = true)
            climBar?.setTabAllows(id != "navi")
            if (id == "telemetry") telemetryHost?.show() else telemetryHost?.hide()
            if (id == "navi") navHost?.show() else navHost?.hide()
            navHost?.onThemeChanged(themeId)
            applyShellBackground(id == "navi", themeId)
            syncProjectionTab(id)
            shellTabs?.onSelected(id)
            nativeDock?.setSelected(id, themeId)
            nativeDock?.raise()
        }
    }

    /** Прямоугольник карточки автомобиля (физ. px); нулевой — скрыть. */
    fun applyCarFrame(x: Float, y: Float, w: Float, h: Float) {
        activity.runOnUiThread { carCard?.onFrame(x, y, w, h) }
    }

    /** Прямоугольник #content для нативной вкладки (физ. px). */
    fun applyContentFrame(tab: String, active: Boolean, x: Float, y: Float, w: Float, h: Float, fresh: Boolean) {
        activity.runOnUiThread {
            when (tab) {
                "quick" -> quickPanel?.onFrame(active, x, y, w, h, fresh)
                "voice" -> voicePanel?.onFrame(active, x, y, w, h, fresh)
                "apps" -> appsPanel?.onFrame(active, x, y, w, h, fresh)
                "dashcam" -> dashcamPanel?.onFrame(active, x, y, w, h, fresh)
                "service" -> servicePanel?.onFrame(active, x, y, w, h, fresh)
                "updates" -> updatesPanel?.onFrame(active, x, y, w, h, fresh)
                "tools" -> toolsPanel?.onFrame(active, x, y, w, h, fresh)
            }
        }
    }

    /**
     * Stage CPv2: прямоугольник видео-области projection-вкладки, в
     * физических пикселях окна (JS: getBoundingClientRect × devicePixelRatio;
     * WebView занимает всё окно, системы координат совпадают). Мост кладёт
     * TextureView-оверлей ровно по этому прямоугольнику.
     */
    fun projectionFrame(stack: String, x: Float, y: Float, w: Float, h: Float) {
        projectionRender?.frame(stack, x, y, w, h)
        activity.runOnUiThread { projectionMessage?.setRect(stack, x, y, w, h) }
    }

    /** Имя стека из команды projection.* ({"stack":"carplay"|"androidAuto"}). */
    /** Стек проекции, активный на текущей вкладке (null — нет). Переход — по ребру вкладки. */
    private var activeProjection: String? = null

    /**
     * Правило CarPlay / Android Auto на стороне Kotlin (раньше — syncProjectionActivation в JS).
     * Вызывается на каждый tab.change; реагирует только на смену стека.
     */
    private fun syncProjectionTab(tabId: String) {
        val want = when (tabId) {
            "carplay" -> "carplay"
            "aa" -> "androidAuto"
            else -> null
        }
        if (want == activeProjection) return
        activeProjection?.let { old ->
            ProjectionRenderBridge.stackFromWire(old)?.let { projectionRender?.deactivate(it) }
            activity.runOnUiThread { projectionMessage?.setInactive(old) }
        }
        want?.let { new ->
            ProjectionRenderBridge.stackFromWire(new)?.let { projectionRender?.activate(it) }
            activity.runOnUiThread { projectionMessage?.setActive(new) }
        }
        activeProjection = want
    }

    private fun wireStack(data: String): String? =
        runCatching { JsonParser.parseString(data).asJsonObject.get("stack")?.asString }.getOrNull()

    fun toast(message: String) {
        Log.d(TAG, "toast: $message")
    }

    /** Настройка «Полный экран»: скрыть/показать системные панели ГУ. */
    fun setImmersive(enabled: Boolean) {
        immersive = enabled
        activity.runOnUiThread { applyImmersive() }
    }

    // ── Автообновление ─────────────────────────────────────────────────────

    private fun autoUpdateEnabled(): Boolean = prefs().getBoolean(PREF_UPDATE_AUTO, false)

    /** Тумблер: вкл. — сразу проверка и далее раз в 6 ч; выкл. — таймер снимаем. */
    private fun setAutoUpdate(json: String) {
        val enabled = runCatching {
            JsonParser.parseString(json).asJsonObject.get("enabled")?.asBoolean
        }.getOrNull() ?: return
        prefs().edit().putBoolean(PREF_UPDATE_AUTO, enabled).apply()
        lastAutoInstallCode = -1
        if (enabled) scheduleAutoCheck(0L) else updateHandler.removeCallbacks(autoCheckTask)
        activity.runOnUiThread { updatesPanel?.setAutoUpdateChecked(enabled) }
    }

    private fun scheduleAutoCheck(delayMs: Long) {
        updateHandler.removeCallbacks(autoCheckTask)
        if (autoUpdateEnabled()) updateHandler.postDelayed(autoCheckTask, delayMs)
    }

    /** Проверка обновления. Нашлась версия и тумблер вкл. — ставим её без нажатия. */
    private fun startUpdateCheck() {
        if (updateBusy) return
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
                        val code = result.remote.versionCode
                        if (autoUpdateEnabled() && code != lastAutoInstallCode) {
                            lastAutoInstallCode = code
                            startUpdateInstall()
                        }
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

    /** Скачать и установить последнюю найденную версию (кнопка или автоустановка). */
    private fun startUpdateInstall() {
        val remote = lastRemote
        if (remote == null) {
            pushUpdateState("error", message = "Сначала проверьте обновление")
            return
        }
        if (updateBusy) return
        updateBusy = true
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
                updateBusy = false
                pushUpdateState("installing", message = "Устанавливаю… приложение перезапустится")
            }
            override fun onFailure(message: String) {
                updateBusy = false
                if (lastAutoInstallCode == remote.versionCode) lastAutoInstallCode = -1
                pushUpdateState("error", message = message)
            }
        })
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
        // Нативная вкладка «Обновления» получает тот же снимок в UI-потоке.
        activity.runOnUiThread { updatesPanel?.onUpdateState(obj.toString()) }
    }

    /**
     * Пуш данных из Kotlin в интерфейс.
     * [json] — строка JSON-объекта, например {"battPct":78}.
     */
    fun pushToUi(name: String, json: String) {
        // Этап 9: статусы и presence питают нативное сообщение над видео.
        if (name == "carplay.status" || name == "android_auto.status" || name == "projection.presence") {
            activity.runOnUiThread { projectionMessage?.onEvent(name, json) }
        }
        // Карточка автомобиля читает телеметрию напрямую (тот же пуш, что уходит в JS).
        if (name == "telemetry.data" || name == "telemetry.live") {
            activity.runOnUiThread { carCard?.onTelemetry(name, json) }
        }
        if (name == "telemetry.live") {
            activity.runOnUiThread { topBar?.onTelemetry(name, json) }
        }
        // Живой поток (1 Гц+) в JS больше никто не читает: карточка — нативная.
        // W3: JS-слушателей пушей больше нет — всё, что нужно, уже получено нативно выше.
    }

    /**
     * Фон корня шелла: на вкладке «Навигация» прозрачный (карта лежит под ним),
     * на прочих — page-цвет действующей темы.
     */
    private fun applyShellBackground(nav: Boolean, themeId: String?) {
        activity.runOnUiThread {
            shellRoot.setBackgroundColor(
                if (nav) Color.TRANSPARENT
                else com.example.voiceapp3.nav.NavTheme.pageBackdrop(themeId)
            )
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
        // Нативная вкладка «Голос» получает то же событие напрямую (UI-поток).
        activity.runOnUiThread { voicePanel?.onVoiceUi(phase, text, phrase, reply) }
    }

    /** MainActivity.onDestroy: сервис больше не должен считать мост живым. */
    fun dispose() {
        updateHandler.removeCallbacks(autoCheckTask)
        if (companionInstance == this) companionInstance = null
    }

    init {
        companionInstance = this
        // Тумблер включён до запуска: первая проверка через 30 с (сеть успевает подняться).
        if (autoUpdateEnabled()) scheduleAutoCheck(30_000L)
        // Аудит §5.1: живая смена темы ГУ (день↔ночь по фарам) — без перезапуска приложения.
        com.example.voiceapp3.tools.HuNightMode.observe(activity, huNightListener)
    }

    companion object {
        private const val TAG = "CENTRAL_UiBridge"
        private const val AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

        /** Единственный живой мост UI (сервис голоса ищет его для voice.ui). */
        @Volatile
        var companionInstance: UiBridge? = null
            private set
    }
}
