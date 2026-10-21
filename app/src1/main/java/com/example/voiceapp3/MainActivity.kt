package com.example.voiceapp3

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.example.voiceapp3.ui.VehicleLiveAdapter
import com.example.voiceapp3.ui.UiBridge
import com.example.voiceapp3.ui.cx.CxEventSink
import com.example.voiceapp3.ui.cx.CxPalettes
import com.example.voiceapp3.ui.cx.CxHostsAdapter
import com.example.voiceapp3.ui.cx.CxStateSource
import com.example.voiceapp3.ui.cx.CxThemeStoreMirror
import com.example.voiceapp3.ui.cx.CxUi
import com.example.voiceapp3.tools.LockSound
import com.example.voiceapp3.tools.LockStateTrigger

/**
 * Оболочка интерфейса.
 *
 * Экран занимает нативный каркас [CxUi] (пакет `ui.cx`) — порт макета
 * «Geely EX2 ГУ — концепт в стиле Zeekr» на View. Логика подключается через
 * [UiBridge]: мост реализует `CxEventSink`/`CxStateSource`, а каркас отдаёт
 * ему действия и забирает снимок настроек.
 *
 * WebView здесь больше нет и быть не может: приложение подписано платформенным
 * ключом и declares `sharedUserId="android.uid.system"`, то есть работает под
 * uid 1000, а `WebViewFactory` (AOSP 9, строки 231-236) безусловно бросает
 * `UnsupportedOperationException("For security reasons, WebView is not allowed
 * in privileged processes")` для uid 0/1000/1001/1002/1004. Обхода нет:
 * `android:process` наследует uid, `/system` закрыт dm-verity.
 *
 * Из старой активности сохранено поведение, не связанное с UI:
 *  - тема окна (AppTheme) и запрет Force Dark;
 *  - запрос RECORD_AUDIO + SYSTEM_ALERT_WINDOW (OverlayPermission);
 *  - запуск VoiceAssistantService спустя ~100 мс после отрисовки;
 *  - перезапуск сервиса после выдачи разрешений.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var cx: CxUi
    private lateinit var bridge: UiBridge
    // GeelyTools deviation: тонкий live-фид движка телеметрии для шелла
    // (навигация/кузов/карточка батареи); экраны телеметрии — TelemetryActivity.
    private lateinit var vehicleLive: VehicleLiveAdapter

    // Stage CPv2: рендер-мост CarPlay/AA (capy CarplayBridge+AndroidAutoBridge+
    // ProjectionTouchBridge → ProjectionRenderBridge). Живёт с активностью,
    // как мосты capy с configureFlutterEngine.
    private var projectionRender: com.example.voiceapp3.ui.ProjectionRenderBridge? = null

    /** Встроенный Flutter-экран телеметрии (вкладка «Телеметрия» шелла). */
    private var telemetryHost: com.example.voiceapp3.ui.TelemetryHostController? = null

    /** Stage NAV-MAP: нативная карта вкладки «Навигация» (MapLibre под каркасом). */
    private var navHost: com.example.voiceapp3.nav.NavigationHostController? = null

    /** Хранилище палитры — делегирует в TelemetryPrefsMirror. */
    private lateinit var themeStore: CxThemeStoreMirror

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.applySaved(this)
        super.onCreate(savedInstanceState)
        AppTheme.applyToWindow(this)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.decorView.isForceDarkAllowed = false
        }

        // Живой фид движка телеметрии (telemetry.live + тонкий telemetry.data):
        // навигация, быстрый доступ, карточка батареи.
        vehicleLive = VehicleLiveAdapter(applicationContext) { name, json ->
            if (::bridge.isInitialized) bridge.pushToUi(name, json)
        }
        // GeelyTools deviation: Flutter-телеметрия встроена во вкладку
        // (FlutterView в слое-якоре каркаса с отступом под плавающий док) —
        // вкладка открывает её сразу, док остаётся видимым.
        telemetryHost = com.example.voiceapp3.ui.TelemetryHostController(this)
        // Stage NAV-MAP: нативный слой карты открывается вкладкой «Навигация».
        // Двухслойная геометрия макета: карта под прозрачным каркасом (режим
        // navfull — CxShell.applyNavFull), chrome — слоем сверху; для
        // проксирования тапов по доку хосту нужен сам док, он задаётся ниже.
        navHost = com.example.voiceapp3.nav.NavigationHostController(this)
        themeStore = CxThemeStoreMirror(this, navHost)

        /* Каркас создаётся ДО моста, но каналы ему нужны сразу, поэтому оба
           замыкаются на `bridge` через лямбды: обращение произойдёт не раньше
           первого действия пользователя или первого снимка, а к тому моменту
           поле уже инициализировано. Проверка `::bridge.isInitialized`
           оставлена там, где вызов может прийти из фонового потока. */
        cx = CxUi(
            activity = this,
            events = CxEventSink { name, json ->
                if (::bridge.isInitialized) bridge.onEvent(name, json)
            },
            stateSource = CxStateSource {
                if (::bridge.isInitialized) bridge.snapshot() else null
            },
            themeStore = themeStore,
            hosts = CxHostsAdapter(telemetryHost, navHost),
            onSay = { message ->
                if (::bridge.isInitialized) bridge.toast(message)
            },
        )
        bridge = UiBridge(this, cx, vehicleLive)
        bridge.telemetryHost = telemetryHost
        bridge.navHost = navHost

        /* Действующая палитра применяется к активности ДО setContentView.
           Атрибуты cx* определены только в темах Theme.GeelyTools*, а без
           setTheme активность осталась бы под Theme.VoiceApp проекта, где их
           нет: CxTheme.color вернул бы fallback — непрозрачный чёрный, и
           экран был бы чёрным при живых View (ровно то, что видно на
           скриншоте с эмулятора: эмодзи цветные, всё остальное чёрное).
           CxPalettes.apply написан ровно для этого (см. его KDoc), но при
           старте не вызывался. follow/gu берутся из первого снимка: мост к
           этому моменту создан, а View ещё нет, так что setTheme успеет. */
        val boot = bridge.snapshot()
        CxPalettes.apply(
            this, themeStore,
            boot?.get("follow")?.takeIf { !it.isJsonNull }?.asBoolean ?: true,
            boot?.get("gu")?.takeIf { !it.isJsonNull }?.asString == "dark",
        )

        /* Макет ставится до ProjectionRenderBridge: в нём живут TextureView-
           оверлеи carplaySurface и aautoSurface, которые рендер-мост находит
           через activity.findViewById в своём конструкторе, причём поля у
           него не nullable. До setContentView findViewById вернул бы null и
           активность упала бы уже на создании рендер-моста. Заменили только
           WebView на контейнер cxRoot — каркас собирается программно и
           добавляется туда ниже. */
        setContentView(R.layout.activity_main)
        /* Док каркаса — цель проксирования тапов слоя хрома карты. Раньше сюда
           передавался webView целиком (свойство расширено до View?). */
        navHost?.dockTargetView = cx.dockView()
        // Stage CPv2: рендер-мост строится сразу после UiBridge (аналог
        // configureFlutterEngine в capy), статусы уходят в каркас пушами
        // carplay.status / android_auto.status.
        projectionRender = com.example.voiceapp3.ui.ProjectionRenderBridge(this) { name, json ->
            bridge.pushToUi(name, json)
        }
        bridge.projectionRender = projectionRender

        /* Каркас добавляется в контейнер cxRoot последним из детей, поэтому
           TextureView-оверлеи проекции остаются поверх него — порядок тот же,
           что был с WebView. */
        findViewById<FrameLayout>(R.id.cxRoot).addView(
            cx.root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        /* Первый снимок настроек и старт живых потоков — замена
           emit('ui.ready') + emit('telemetry.request') при загрузке страницы.
           Обязательно после setContentView: каркас публикует отступ под док и
           прямоугольник проекции по раскладке, а до неё они нулевые. */
        cx.requestInitialState()
        /* Звук замка: переприменение ключей после перезагрузки + источник
           события. Отложено на 3 с — к этому моменту car_service и adbd уже
           поднимаются, а UI-поток не блокируется. */
        startLockSoundPipeline()

        // CarPlay / Android Auto (порт capy): экранная половина присутствия.
        // Строится здесь, как ProjectionPresenceBridge в configureFlutterEngine
        // capy; монитор стартует только при включённом тумблере, а снимки
        // уходят в каркас пушем projection.presence.
        com.timhss.capyenergy.projection.ProjectionPresenceBridge.getOrCreate(
            applicationContext
        ) { json -> if (::bridge.isInitialized) bridge.pushToUi("projection.presence", json) }
        com.timhss.capyenergy.projection.ProjectionPresenceBridge.get(applicationContext)
            ?.setEnabled(
                com.timhss.capyenergy.telemetry.TelemetrySettings(applicationContext)
                    .projectionBetaEnabled()
            )

        // Stage SCR: «Полный экран» — как applyImmersiveMode в capy: чтение
        // настройки на старте, применение к окну ДО первого кадра. По умолчанию
        // включён (capy: _immersiveEnabled = true).
        bridge.setImmersive(
            getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
                .getBoolean("app_immersive_mode_enabled", true)
        )


        ensurePermissions()
        startVoiceService()
        startTelemetryCollector()
        handleDestination(intent)
    }

    /**
     * Capy Energy при подключении зарядки открывает приложение с extra
     * "destination" (AutoOpenLauncher). Приложение уже запущено (singleTop),
     * поэтому intents приходят в [onNewIntent] — показываем вкладку «Зарядка».
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDestination(intent)
    }

    private fun handleDestination(intent: Intent?) {
        val dest = intent?.getStringExtra("destination") ?: return
        Log.i(TAG, "destination extra: $dest")
        if (::bridge.isInitialized) {
            bridge.pushToUi("ui.destination", "{\"destination\":\"$dest\"}")
        }
    }

    /**
     * Телеметрия (Capy Energy): запускаем сборщик при открытии приложения,
     * если включён автозапуск (та же проверка, что в TelemetryBootReceiver
     * для старта по BOOT_COMPLETED / MY_PACKAGE_REPLACED).
     */
    private fun startTelemetryCollector() {
        handler.postDelayed({
            runCatching {
                if (com.timhss.capyenergy.telemetry.TelemetrySettings(applicationContext)
                        .autoStartOnBoot()
                ) {
                    com.timhss.capyenergy.service.TelemetryCollectorService
                        .start(applicationContext)
                } else {
                    Log.i(TAG, "Автозапуск телеметрии выключен в настройках")
                }
            }.onFailure { Log.w(TAG, "Не удалось запустить сборщик телеметрии", it) }
        }, 400)
    }

    /** Как в старой версии: сервис стартует вскоре после отрисовки UI. */
    private fun startVoiceService() {
        handler.postDelayed({
            Log.i(TAG, "Starting service after UI render")
            applicationContext.startForegroundService(
                Intent(applicationContext, VoiceAssistantService::class.java)
            )
        }, 100)
    }

    private fun ensurePermissions() {
        OverlayPermission.ensure(this)

        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (needed.isNotEmpty()) {
            Log.i(TAG, "Запрашиваю разрешения: $needed")
            requestPermissions(needed.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return

        val granted = grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            Log.i(TAG, "Разрешения выданы, перезапускаю сервис")
            restartService()
        } else {
            Log.w(TAG, "Микрофон запрещён — ассистент работать не будет")
        }
    }

    private fun restartService() {
        val intent = Intent(applicationContext, VoiceAssistantService::class.java)
        runCatching { applicationContext.stopService(intent) }
        handler.postDelayed({
            applicationContext.startForegroundService(intent)
        }, 500)
    }

    /**
     * Кнопка «Назад»: сначала даём интерфейсу шанс закрыть модалку
     * или полноэкранный навигатор, иначе выходим.
     *
     * Было асинхронно: `evaluateJavascript(window.__nativeBack)` с колбэком.
     * Стало синхронно — `CxShell.onBackPressed()` закрывает модальное окно и
     * возвращает true, если закрыл, поэтому ответ известен сразу.
     */
    override fun onBackPressed() {
        if (!::cx.isInitialized || !cx.onBackPressed()) super.onBackPressed()
    }

    /**
     * Результат системного выбора аудиофайла (`ACTION_OPEN_DOCUMENT`):
     * копируем содержимое URI в /data/local/tmp/sons и пишем ключи реестра.
     * Без этого обработчика выбор файла в шелле просто пропадал — прежняя
     * реализация запускала `ACTION_GET_CONTENT` и результат не принимала.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (::bridge.isInitialized && bridge.onAudioPicked(requestCode, resultCode, data)) {
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /**
     * Переприменение звука замка после перезагрузки и старт источника события.
     *
     * Значения `settings system` переживают перезагрузку, но прошивка ГУ может
     * сбрасывать свой ключ, поэтому boot-ветка пишет его снова и сверяет
     * readback. Триггер замка стартует всегда: он же сторож применения
     * (LockStateTrigger.watchdog → LockSound.ensureApplied).
     */
    private fun startLockSoundPipeline() {
        handler.postDelayed({
            runCatching {
                LockSound.reapplyAfterBoot(applicationContext)
                LockStateTrigger.start(applicationContext)
            }.onFailure {
                Log.w(TAG, "startLockSoundPipeline", it)
            }
        }, 3000)
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        /* Возврат приложения — тот же повод перепроверить ключи замка, что и
           boot: сторож живёт в потоке триггера, а не в UI. */
        startLockSoundPipeline()
        if (::cx.isInitialized) {
            cx.onResumed()
            // Stage SCR: как didChangeAppLifecycleState(resumed) в capy — режим
            // просится у окна на каждом resume: Android восстанавливает панели,
            // когда активность пересоздаётся или системный диалог уходил вперёд.
            bridge.applyImmersive()
            // Stage B5: живой поток телеметрии возвращается вместе с приложением,
            // если вкладка телеметрии была открыта.
            bridge.onAppResumed()
        }
        // CarPlay/AA, как onActivityResumed в мосте capy: рёбра подключения,
        // ушедшие пока приложение было в фоне, до нас не дошли — перечитываем
        // геттеры обоих стеков.
        com.timhss.capyenergy.projection.ProjectionPresenceBridge.get(applicationContext)
            ?.onActivityResumed()
        // Stage CPv2: render-хосты переприменяют режим на resume (posted —
        // только detach-порядок нагрузочный, см. onPause).
        projectionRender?.onActivityResumed()
        // Встроенная телеметрия: resumed-состояние движку Flutter, если
        // вкладка телеметрии активна.
        telemetryHost?.onActivityResumed()
        // Stage NAV-MAP: то же для нативной карты, если активна «Навигация».
        navHost?.onActivityResumed()
    }

    override fun onPause() {
        // Stage CPv2, R4 (как MainActivity.onPause в capy): release CarPlay,
        // release Android Auto и подъём активных touch-жестов — СИНХРОННО,
        // до super.onPause(). OEM CarplayActivity строит свою SurfaceView
        // после того, как эта пауза вернётся; detach, прилетевший позже,
        // разрушит только что созданное EGL-состояние и оставит экран чёрным.
        projectionRender?.onActivityPausedSynchronously()
        telemetryHost?.onActivityPaused()
        navHost?.onActivityPaused()
        isResumed = false
        if (::cx.isInitialized) {
            cx.onPaused()
            // Stage B5: за скрытым приложением шину не сэмплить.
            bridge.onAppPaused()
        }
        super.onPause()
    }

    override fun onDestroy() {
        // Stage CPv2: dispose рендер-моста до UiBridge (аналог
        // cleanUpFlutterEngine в capy): хосты отпускают binder-связи,
        // touch-контроллер снимает свои binding-и.
        projectionRender?.dispose()
        projectionRender = null
        // Встроенная телеметрия: detach view + dispose моста + destroy движка
        // (аналог cleanUpFlutterEngine в TelemetryActivity).
        telemetryHost?.dispose()
        telemetryHost = null
        // Stage NAV-MAP: демонтаж MapView и фида геолокации.
        navHost?.dispose()
        navHost = null
        if (::bridge.isInitialized) bridge.dispose()
        if (::vehicleLive.isInitialized) vehicleLive.dispose()
        if (::cx.isInitialized) cx.onStopped()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_PERMISSIONS = 1001

        /**
         * Stage SCR: на переднем плане ли приложение. Читает wake-приёмник
         * сервиса, чтобы не поднимать активность, которая уже открыта.
         */
        @Volatile
        var isResumed = false
            private set

    }
}
