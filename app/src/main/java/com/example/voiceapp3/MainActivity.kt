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
import androidx.appcompat.app.AppCompatActivity
import com.example.voiceapp3.ui.VehicleLiveAdapter
import android.database.Cursor
import android.provider.OpenableColumns
import com.google.gson.JsonObject
import com.example.voiceapp3.ui.UiBridge

/**
 * Оболочка нового интерфейса (фаза 1 — только UI).
 *
 * Экран занимает WebView с assets/ui/index.html — точной копией
 * макета «Geely EX2 ГУ — концепт в стиле Zeekr». Логика подключается
 * на фазе 2 через [UiBridge] (объект GeelyNative в JS).
 *
 * Из старой активности сохранено поведение, не связанное с UI:
 *  - тема окна (AppTheme) и запрет Force Dark;
 *  - запрос RECORD_AUDIO + SYSTEM_ALERT_WINDOW (OverlayPermission);
 *  - запуск VoiceAssistantService спустя ~100 мс после отрисовки;
 *  - перезапуск сервиса после выдачи разрешений.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var shellRoot: ViewGroup
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

    /** Stage NAV-MAP: нативная карта вкладки «Навигация» (MapLibre поверх WebView). */
    private var navHost: com.example.voiceapp3.nav.NavigationHostController? = null

    /** Нативный док вкладок шелла (NativeDock). */
    private var nativeDock: com.example.voiceapp3.ui.NativeDock? = null

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.applySaved(this)
        super.onCreate(savedInstanceState)
        AppTheme.applyToWindow(this)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.decorView.isForceDarkAllowed = false
        }

        setContentView(R.layout.activity_main)

        shellRoot = findViewById(R.id.shellRoot)
        // Фон корня до первой темы (прежний фон WebView, чтобы не мигал белый экран).
        shellRoot.setBackgroundColor(0xFFF0E9DC.toInt())
        // Живой фид движка телеметрии для шелла (telemetry.live + тонкий
        // telemetry.data): навигация, быстрый доступ, карточка батареи.
        vehicleLive = VehicleLiveAdapter(applicationContext) { name, json ->
            if (::bridge.isInitialized) bridge.pushToUi(name, json)
        }
        bridge = UiBridge(this, shellRoot, vehicleLive)
        // GeelyTools deviation: Flutter-телеметрия встроена во вкладку шелла
        // (FlutterView поверх WebView с отступом под плавающий док) — вкладка
        // открывает её сразу, док остаётся видимым.
        telemetryHost = com.example.voiceapp3.ui.TelemetryHostController(this)
        bridge.telemetryHost = telemetryHost
        // Stage NAV-MAP: нативный слой карты открывается вкладкой «Навигация».
        // Двухслойная геометрия: карта под корнем шелла (прозрачным на вкладке
        // «Навигация»), chrome — слоем сверху. Тапы по доку — нативные, без прокси.
        navHost = com.example.voiceapp3.nav.NavigationHostController(this)
        bridge.navHost = navHost
        // Нативный док вкладок: клик → UiBridge.selectTab (W2, без JS).
        // Вырез под док для Flutter-телеметрии и карты:
        // высота дока + 14dp (низ) + 8dp — тот же состав, что у прежнего замера
        // telFrame в JS. Верх дока уходит в JS (dock.geometry) для проекций.
        nativeDock = com.example.voiceapp3.ui.NativeDock(this) { id ->
            bridge.selectTab(id)
        }.also { dock ->
            dock.onGeometryChanged = { heightPx, topPx ->
                val inset = heightPx + dpPx(14) + dpPx(8)
                telemetryHost?.setDockInsetPx(inset)
                navHost?.setDockInsetPx(inset)
                // Верх дока — для высоты видео CarPlay / AA (нативная геометрия).
                bridge.shellGeometry?.setDockTop(topPx.toInt())
            }
        }
        bridge.nativeDock = nativeDock
        // Этап 9b: вкладки шелла и правило CarPlay / AA — нативно (ui/ShellTabs.kt).
        bridge.shellTabs = com.example.voiceapp3.ui.ShellTabs(
            enabled = {
                com.timhss.capyenergy.telemetry.TelemetrySettings(applicationContext).projectionBetaEnabled()
            },
            publish = { tabs, selected ->
                nativeDock?.setTabs(tabs, selected, null)
                nativeDock?.raise()
            },
            select = { id -> bridge.selectTab(id) },
        )
        bridge.shellTabs?.refresh()
        // Нативная вкладка «Быстрый доступ» (ui/QuickPanel.kt): клики уходят в тот
        // же мост onEvent, что и вызовы из прежней HTML-панели.
        bridge.quickPanel = com.example.voiceapp3.ui.QuickPanel(this) { name, json ->
            bridge.onEvent(name, json)
        }
        // Нативная верхняя строка и тосты (ui/TopBarPanel.kt, ui/ToastPanel.kt).
        bridge.topBar = com.example.voiceapp3.ui.TopBarPanel(this)
        bridge.toastPanel = com.example.voiceapp3.ui.ToastPanel(this)
        bridge.climBar = com.example.voiceapp3.ui.ClimBarPanel(this)
        // Нативная геометрия главного экрана (замена JS-замеров, ui/ShellGeometry.kt).
        bridge.shellGeometry = com.example.voiceapp3.ui.ShellGeometry(this, shellRoot, bridge)
        shellRoot.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) bridge.shellGeometry?.refresh(false)
        }
        // Нативная карточка автомобиля (ui/CarCardPanel.kt) на главном экране.
        bridge.carCard = com.example.voiceapp3.ui.CarCardPanel(this) { name, json ->
            bridge.onEvent(name, json)
        }
        // Живой поток и снимок — нативно, без telemetry.request из JS.
        vehicleLive?.startLive()
        bridge.telemetryRequest()
        // Нативная вкладка «Голос» (ui/VoicePanel.kt): кнопки шлют voice.request /
        // voice.micRestart через тот же мост; состояние голоса — из voiceUi сервиса.
        bridge.voicePanel = com.example.voiceapp3.ui.VoicePanel(this) { name, json ->
            bridge.onEvent(name, json)
        }
        // Нативная вкладка «Приложения» (ui/ApplicationsPanel.kt): событий в мост нет,
        // как и у прежней HTML-панели; состояние установки — локальное.
        bridge.appsPanel = com.example.voiceapp3.ui.ApplicationsPanel(this)
        // Нативная вкладка «Видеорегистратор» (этап 6): каркас с заглушкой.
        bridge.dashcamPanel = com.example.voiceapp3.ui.DashcamPanel(this)
        // Нативная вкладка «Сервис» (этап 7): каркас с заглушкой.
        bridge.servicePanel = com.example.voiceapp3.ui.ServicePanel(this)
        // Нативная вкладка «Обновления» (этап 8): проверка и установка — через мост (AppUpdater).
        bridge.updatesPanel = com.example.voiceapp3.ui.UpdatesPanel(this) { name, json ->
            bridge.onEvent(name, json)
        }
        // Нативная вкладка «Инструменты», этап 5a (ui/ToolsPanel.kt): настройки уходят
        // в тот же мост, снимок tools.state приходит из UiBridge.pushToolsState.
        bridge.toolsPanel = com.example.voiceapp3.ui.ToolsPanel(this) { name, json ->
            bridge.onEvent(name, json)
        }
        // Stage CPv2: рендер-мост строится сразу после UiBridge (аналог
        // configureFlutterEngine в capy), статусы уходят в WebView пушами
        // carplay.status / android_auto.status.
        projectionRender = com.example.voiceapp3.ui.ProjectionRenderBridge(this) { name, json ->
            bridge.pushToUi(name, json)
        }
        bridge.projectionRender = projectionRender
        // Этап 9: нативное сообщение области видео (статусы и presence приходят через pushToUi).
        bridge.projectionMessage = com.example.voiceapp3.ui.ProjectionMessagePanel(this)

        // CarPlay / Android Auto (порт capy): экранная половина присутствия.
        // Строится здесь, как ProjectionPresenceBridge в configureFlutterEngine
        // capy; монитор стартует только при включённом тумблере, а снимки
        // уходят в WebView пушем projection.presence.
        com.timhss.capyenergy.projection.ProjectionPresenceBridge.getOrCreate(
            applicationContext
        ) { json ->
            if (::bridge.isInitialized) {
                bridge.pushToUi("projection.presence", json)
                runOnUiThread {
                    bridge.toolsPanel?.onProjectionPresence(json)
                    bridge.shellTabs?.onPresence(json)
                }
            }
        }
        com.timhss.capyenergy.projection.ProjectionPresenceBridge.get(applicationContext)
            ?.setEnabled(
                com.timhss.capyenergy.telemetry.TelemetrySettings(applicationContext)
                    .projectionBetaEnabled()
            )

        // Интерфейс живёт на https://appassets.androidplatform.net —
        // запросы к этому домену перехватываются и отдаются из assets (аналог
        // androidx.webkit WebViewAssetLoader, без зависимости). Сети для
        // загрузки UI не нужно, зато у страницы есть стабильный origin
        // и Referer для внешних запросов (геоданные, OTA-обновления).
        // Stage SCR: «Полный экран» — как applyImmersiveMode в capy: чтение
        // настройки на старте, применение к окну ДО первого кадра. По умолчанию
        // включён (capy: _immersiveEnabled = true).
        bridge.setImmersive(
            getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
                .getBoolean("app_immersive_mode_enabled", true)
        )

        bridge.refreshShellTheme()
        ensurePermissions()
        startVoiceService()
        startTelemetryCollector()
        handleDestination(intent)
    }

    private fun dpPx(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

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
            bridge.shellTabs?.onDestination(dest)
            // W3: авто-открытие зарядки — прямо в нативный telemetry.open (раньше через JS).
            if (dest.contains("charg")) {
                bridge.onEvent("telemetry.open", "{\"destination\":\"charging\"}")
            }
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

    /** Кнопка «Назад»: прежний путь (JS-хук уже удалён) всегда завершал активность. */
    override fun onBackPressed() {
        finish()
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        // Stage SCR: как didChangeAppLifecycleState(resumed) в capy — режим
        // просится у окна на каждом resume: Android восстанавливает панели,
        // когда активность пересоздаётся или системный диалог уходил вперёд.
        bridge.applyImmersive()
        // Stage B5: живой поток телеметрии возвращается вместе с приложением,
        // если вкладка телеметрии была открыта.
        bridge.onAppResumed()
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
        // Stage B5: за скрытым приложением шину не сэмплить.
        bridge.onAppPaused()
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

    /**
     * Результат системного выбора звука (запускается из Kotlin по audio.selectFile / audio.selectUnlockFile с пустым телом).
     * Передаём только имя файла — так же, как HTML-версия (LockSound берёт File(path).name).
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7001 && requestCode != 7002) return
        val uri = if (resultCode == RESULT_OK) data?.data else null
        val name = uri?.let { displayNameOf(it) }
        if (name.isNullOrEmpty()) {
            bridge.toolsPanel?.onSoundPickCancelled()
            return
        }
        val event = if (requestCode == 7001) "audio.selectFile" else "audio.selectUnlockFile"
        bridge.onEvent(event, JsonObject().apply { addProperty("path", name) }.toString())
    }

    private fun displayNameOf(uri: android.net.Uri): String? {
        val cursor: Cursor? = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        }.getOrNull()
        cursor?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
