package com.example.voiceapp3

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.example.voiceapp3.ui.VehicleLiveAdapter
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

    private lateinit var webView: WebView
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

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.applySaved(this)
        super.onCreate(savedInstanceState)
        AppTheme.applyToWindow(this)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.decorView.isForceDarkAllowed = false
        }

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        // Живой фид движка телеметрии для шелла (telemetry.live + тонкий
        // telemetry.data): навигация, быстрый доступ, карточка батареи.
        vehicleLive = VehicleLiveAdapter(applicationContext) { name, json ->
            if (::bridge.isInitialized) bridge.pushToUi(name, json)
        }
        bridge = UiBridge(this, webView, vehicleLive)
        // GeelyTools deviation: Flutter-телеметрия встроена во вкладку шелла
        // (FlutterView поверх WebView с отступом под плавающий док) — вкладка
        // открывает её сразу, док остаётся видимым.
        telemetryHost = com.example.voiceapp3.ui.TelemetryHostController(this)
        bridge.telemetryHost = telemetryHost
        // Stage NAV-MAP: нативный слой карты открывается вкладкой «Навигация».
        // Двухслойная геометрия макета: карта под прозрачным WebView (100vw ×
        // 100vh, панель шелла парит на prechu), chrome — слоем сверху; для
        // проксирования тапов по доку хосту нужен сам webView.
        navHost = com.example.voiceapp3.nav.NavigationHostController(this)
            .also { it.webView = webView }
        bridge.navHost = navHost
        // Stage CPv2: рендер-мост строится сразу после UiBridge (аналог
        // configureFlutterEngine в capy), статусы уходят в WebView пушами
        // carplay.status / android_auto.status.
        projectionRender = com.example.voiceapp3.ui.ProjectionRenderBridge(this) { name, json ->
            bridge.pushToUi(name, json)
        }
        bridge.projectionRender = projectionRender
        setupWebView()

        // CarPlay / Android Auto (порт capy): экранная половина присутствия.
        // Строится здесь, как ProjectionPresenceBridge в configureFlutterEngine
        // capy; монитор стартует только при включённом тумблере, а снимки
        // уходят в WebView пушем projection.presence.
        com.timhss.capyenergy.projection.ProjectionPresenceBridge.getOrCreate(
            applicationContext
        ) { json -> if (::bridge.isInitialized) bridge.pushToUi("projection.presence", json) }
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

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(APP_ORIGIN + "/ui/index.html")
        }

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

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // file:///android_asset доступен и без этого флага, но старые
            // сборки WebView на Android 9 лучше не удивлять.
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            // Не масштабировать интерфейс системным шрифтом ГУ.
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        // Фон стартовой темы макета (Сепия), чтобы не мигал белый экран.
        webView.setBackgroundColor(0xFFF0E9DC.toInt())
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER

        webView.addJavascriptInterface(bridge, "GeelyNative")
        webView.webViewClient = object : WebViewClient() {
            /**
             * Assets по https-домену. Всё, что начинается с
             * APP_ORIGIN, читается из apk; остальное (геоданные,
             * обновления) идёт в сеть как обычно.
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.host != APP_HOST) return super.shouldInterceptRequest(view, request)
                var path = url.path?.removePrefix("/") ?: return notFound()
                if (path.isBlank()) path = "ui/index.html"
                return try {
                    val opened = assets.open(path)
                    WebResourceResponse(mimeOf(path), null, opened)
                } catch (e: Exception) {
                    notFound()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                // Наш домен грузится сам (через перехват выше); внешние ссылки
                // не открываем: приложение — это его UI.
                return request?.url?.host != APP_HOST
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d(
                    "WebViewUI",
                    "${message.message()} (${message.sourceId()}:${message.lineNumber()})"
                )
                return true
            }
        }
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
     * или полноэкранный навигатор (window.__nativeBack), иначе выходим.
     */
    override fun onBackPressed() {
        if (!::webView.isInitialized) {
            super.onBackPressed()
            return
        }
        webView.evaluateJavascript("(window.__nativeBack ? window.__nativeBack() : false)") { value ->
            if (value != "true") finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        if (::webView.isInitialized) {
            webView.onResume()
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
        if (::webView.isInitialized) {
            webView.onPause()
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
        if (::webView.isInitialized) webView.destroy()
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

        /** Служебный https-домен для UI (см. loadUrl в onCreate). */
        private const val APP_HOST = "appassets.androidplatform.net"
        private const val APP_ORIGIN = "https://$APP_HOST"

        /** MIME по расширению для отдачи assets. */
        private fun mimeOf(path: String): String = when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".ttf") -> "font/ttf"
            else -> "application/octet-stream"
        }

        private fun notFound(): WebResourceResponse =
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null)
    }
}
