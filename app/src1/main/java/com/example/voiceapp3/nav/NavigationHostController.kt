package com.example.voiceapp3.nav

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Stage NAV-MAP (этап 4 переписи навигации): хост нативного экрана «Навигация».
 *
 * Геометрия — 1:1 по макету этапа 5 (navigator-design.html):
 *  · БЛОК КАРТЫ во весь экран (100vw × 100vh) с закругленными углами (22dp,
 *    как .screen макета), без внешних рамок, отступов и пустых полей.
 *  · ПЛАВАЮЩАЯ ПАНЕЛЬ шелла парит над картой: карта лежит ПОД контентом
 *    активности (mapLayer с index 0), а нативный каркас на этой вкладке
 *    прозрачен целиком — режим navfull, `CxShell.applyNavFull` (раньше это
 *    был класс navfull на body + `UiBridge.applyNavWebViewBg`, красивший фон
 *    виджета WebView). Док шелла рисуется на месте как положено.
 *  · Chrome навигации (фабы/баннер/шторка/карточка точки) — отдельный
 *    прозрачный слой chromeLayer ПОВЕРХ контента; он сам маршрутизирует
 *    события: дочерние виды едят свои тапы, полоса дока проксируется в док
 *    каркаса, остальное — жесты в MapView. Повторю: карта без новых каналов.
 *
 * ЦВЕТА ТОЛЬКО ИЗ ВЫБРАННОЙ ТЕМЫ (NavTheme ← ui.theme/tab.change): панели,
 * текст, кнопки, границы, акценты и стиль карты (день/ночь) обновляются на
 * лету через [onThemeChanged] — перезагрузка активности не нужна.
 *
 * Геолокация приезжает из NavPositionFeed (существующий шов телеметрии);
 * собственных подписок на LocationManager здесь нет.
 */
class NavigationHostController(private val activity: Activity) {

    private val density = activity.resources.displayMetrics.density

    /** Действующая палитра (на старте — последняя запомненная по prefs). */
    private var theme = NavTheme.current(activity)

    /**
     * Док нативного каркаса — цель проксирования тапов по полосе плавающего
     * дока.
     *
     * Был `var webView: WebView?`. Тип расширен до `View?` намеренно и этого
     * достаточно: единственное использование свойства — `dockTarget` у
     * [NavChromeLayer], а он и так объявлен как `View?` и зовёт только
     * `dispatchTouchEvent`. WebView здесь был нужен исключительно как
     * получатель тапов, поэтому с переходом на нативный интерфейс передаётся
     * сам док: `navHost.dockTargetView = cx.shell.dockView()`.
     */
    var dockTargetView: View? = null

    private var container: FrameLayout? = null     // = chromeLayer (поверх всего)
    private var mapLayer: FrameLayout? = null      // WORD_ORDER: под контентом, вся карта
    private var mapFrame: FrameLayout? = null      // клип рамки карты (радиус 22dp)
    private var mapCtl: NavMapController? = null
    private var feed: NavPositionFeed? = null

    private var gpsBanner: TextView? = null
    private var styleButton: ImageView? = null
    private var recenterButton: ImageView? = null
    private var offlineButton: ImageView? = null
    private var offlineSheet: com.example.voiceapp3.nav.offline.OfflineSheetController? = null
    private var searchSheet: com.example.voiceapp3.nav.search.SearchSheetController? = null
    private var placeSheet: com.example.voiceapp3.nav.search.PlaceSheetController? = null

    /** Верхняя строка поиска (макет navigator-app-7): поле «Куда едем?» + 🎙. */
    private var topBar: LinearLayout? = null
    private var sideCol: LinearLayout? = null

    /** Макет `.states`: переключатель состояний карта/поиск/место/навигация/офлайн. */
    private var statesBar: com.example.voiceapp3.nav.StateBarController? = null

    /** Макет `s-drive`: хром ведения с честными данными (маршрут — этап 7). */
    private var driveChrome: com.example.voiceapp3.nav.DriveChromeController? = null

    // ── этап 7: on-device маршрутизация (GraphHopper, без сети/ключей/квот)
    private var graphs: com.example.voiceapp3.nav.routing.GraphManager? = null
    private var routingProvider: com.example.voiceapp3.nav.routing.RoutingProvider? = null
    private var routingSheet: com.example.voiceapp3.nav.routing.RoutingSheetController? = null
    private var routeSession: com.example.voiceapp3.nav.routing.RouteSessionController? = null
    private val routeWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "nav-route").apply { isDaemon = true }
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var rerouteAtMs = 0L

    /** Этап 6: репозиторий поиска переживает смену темы (как mapCtl). */
    private var searchRepo: com.example.voiceapp3.nav.search.SearchRepository? = null
    private var lastSnap: NavPositionFeed.PositionSnapshot? = null
    private var scrimView: View? = null
    private var followFabActive = false

    private var dockInsetPx =
        (DEFAULT_DOCK_INSET_DP * density).toInt()

    var isVisible: Boolean = false
        private set

    /** Нижний вырез под док (физ. пиксели) — тот же замер, что у телеметрии. */
    fun setDockInsetPx(px: Int) {
        if (px <= 0 || px > activity.resources.displayMetrics.heightPixels / 2) return
        if (px == dockInsetPx) return
        dockInsetPx = px
        // Карта сама НЕ уходит от дока (панель над ней); живые отступы — у chrome:
        offlineSheet?.view?.let { v ->
            (v.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.bottomMargin = px + dp(12)
                v.layoutParams = lp
            }
        }
        placeSheet?.view?.let { v ->
            (v.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.bottomMargin = px + dp(12)
                v.layoutParams = lp
            }
        }
    }

    /** Показать карту (UI-поток, из UiBridge.tab.change). */
    fun show() {
        runCatching {
            if (container == null) buildLayer()
            mapLayer?.visibility = View.VISIBLE
            container?.visibility = View.VISIBLE
            container?.bringToFront()
            mapCtl?.onStart()
            mapCtl?.onResume()
            feed?.start()
            isVisible = true
            Log.i(TAG, "nav layer shown")
        }.onFailure { Log.e(TAG, "show failed", it) }
    }

    /** Спрятать (вкладка ушла); слой и камера персистятся, фид останавливается. */
    fun hide() {
        if (!isVisible && container?.visibility != View.VISIBLE) return
        runCatching {
            mapCtl?.persistCamera()
            mapCtl?.onPause()
            mapCtl?.onStop()
            feed?.stop()
            offlineSheet?.hide()
            searchSheet?.hide()
            placeSheet?.hide()
            driveChrome?.hide()
            animateScrim(false)
            container?.visibility = View.GONE
            mapLayer?.visibility = View.GONE
            isVisible = false
            Log.i(TAG, "nav layer hidden")
        }.onFailure { Log.e(TAG, "hide failed", it) }
    }

    fun onActivityResumed() {
        if (isVisible) mapCtl?.onResume()
    }

    fun onActivityPaused() {
        if (isVisible) mapCtl?.onPause()
    }

    /**
     * Немедленный переход к новой теме (событие ui.theme/tab.change из шелла):
     * карте говорится яркость/акцент (стиль день/ночь живёт внутри
     * NavMapController), chrome пересобирается с новой палитрой — без
     * перезагрузки страницы или активности. Вид камеры и пакер сохраняются
     * (mapCtl переживает пересборку слоёв).
     */
    fun onThemeChanged(themeId: String?) {
        themeId ?: return
        val t = NavTheme.byId(themeId)
        if (t.id == theme.id) return
        theme = t
        mapCtl?.onThemeChanged(t.dark, t.accent1)
        if (container == null) return
        rebuildChrome()
    }

    /** Полный демонтаж — только из MainActivity.onDestroy. */
    fun dispose() {
        runCatching {
            teardownChrome()
            searchRepo?.release()
            searchRepo = null
            mapCtl?.onDestroy()
            mapCtl = null
            isVisible = false
        }.onFailure { Log.w(TAG, "dispose failed", it) }
    }

    // ═══════════════════════════════════ сборка и пересборка слоёв ════

    /** Снять chrome-виды и отцепить оба слоя от decorRoot (карта выживает). */
    private fun teardownChrome() {
        runCatching { feed?.dispose() }
        feed = null
        offlineSheet?.release()
        searchSheet?.release()
        placeSheet?.release()
        driveChrome?.release()
        routeSession = null
        runCatching { graphs?.close() }.onFailure { Log.w(TAG, "graphs close", it) }
        graphs = null
        routingProvider = null
        routingSheet = null
        routeWorker.shutdown()
        offlineSheetShown = false
        searchSheetShown = false
        animateScrim(false)
        offlineSheet = null
        searchSheet = null
        placeSheet = null
        statesBar = null
        driveChrome = null
        topBar = null
        sideCol = null
        gpsBanner = null
        styleButton = null
        recenterButton = null
        offlineButton = null
        scrimView = null
        container?.let { (it.parent as? ViewGroup)?.removeView(it) }
        container = null
        mapLayer?.let { (it.parent as? ViewGroup)?.removeView(it) }
        mapLayer = null
        mapFrame = null
    }

    /** Смена темы под живым маршрутом: точки — нормально, медленных пятерен нет. */
    private fun rebuildChrome() {
        runCatching {
            mapCtl?.persistCamera()
            teardownChrome()
            buildLayer()
            if (isVisible) show()
        }.onFailure { Log.w(TAG, "rebuildChrome failed", it) }
    }

    private fun buildLayer() {
        Log.i(TAG, "nav chrome: сборка stage7-fix7")
        // Хозяин карты один на активность (камера/пакер/пин переживают тему).
        val ctl = mapCtl ?: NavMapController(activity).also { mapCtl = it }
        ctl.onThemeChanged(theme.dark, theme.accent1)
        val mapView = if (ctl.isCreated()) ctl.mapView else ctl.createView()
        (mapView.parent as? ViewGroup)?.removeView(mapView)
        val decorRoot = activity.findViewById<ViewGroup>(android.R.id.content)

        // ── слой 1 (под контентом активности): карта во весь экран,
        //    углы 22dp, без рамок и пустых полей ──
        val mLayer = FrameLayout(activity)
        mapLayer = mLayer
        val frame = FrameLayout(activity).apply {
            setBackgroundColor(theme.page1)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(
                        0, 0, view.width, view.height, MAP_CORNER_DP * density
                    )
                }
            }
        }
        mapFrame = frame
        frame.addView(
            mapView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        // Затемнение под поиском (макет .scrim — вертикальный градиент;
        // показывается ТОЛЬКО в состоянии поиска, у офлайн-шторки его нет).
        val scrim = View(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x33080B0F, 0xCC080B0F.toInt())
            )
            alpha = 0f
            visibility = View.GONE
        }
        scrimView = scrim
        frame.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        mLayer.addView(
            frame,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        decorRoot.addView(mLayer, 0)

        // ── слой 2 (поверх контента): прозрачный хром с маршрутизацией тапов ─
        val parent = NavChromeLayer(activity).apply {
            mapTarget = mapView
            dockTarget = this@NavigationHostController.dockTargetView
            dockTopY = { (height.takeIf { it > 0 } ?: Int.MAX_VALUE) - dockInsetPx }
            interactionsLocked = {
                offlineSheet?.isShown() == true || searchSheet?.isShown() == true ||
                    routingSheet?.isShown() == true
            }
        }
        container = parent

        ctl.onFollowChanged = { follow -> setFollowActive(follow) }
        ctl.destinationListener = object : NavMapController.DestinationListener {
            override fun onDestinationChanged(latitude: Double, longitude: Double) {
                driveChrome?.setDestination(latitude, longitude)
                // долгий тап по карте → та же карточка места, что у результата поиска
                openPlaceCard(
                    com.example.voiceapp3.nav.search.PlaceSheetController.Place(
                        name = "Метка на карте",
                        subtitle = String.format(Locale.US, "%.5f, %.5f", latitude, longitude),
                        emoji = "📍",
                        lat = latitude, lon = longitude,
                        distanceText = distanceTextTo(latitude, longitude),
                        favorite = false,
                        isDestination = true
                    )
                )
            }

            override fun onDestinationCleared() {
                driveChrome?.clearDestination()
                placeSheet?.hide()
                stopRouteSession()
            }
        }

        chromeStep("banner+topbar") {
        // ── баннер состояния GPS ──
        gpsBanner = TextView(activity).apply {
            setTextColor(theme.ink)
            textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = pill(theme.glassBanner, 18f, theme.borderHairline)
            visibility = View.GONE
        }
        parent.addView(
            gpsBanner,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(86) }   // под верхней строкой поиска
        )

        // ── верхняя строка поиска (макет .topbar; всегда на карте, кроме режима
        //    поиска): поле «Куда едем?» h58 r19 + кнопка 🎙 (сущ. голосовой шов) ──
        val tb = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar = tb
        val field = TextView(activity).apply {
            text = "Куда едем? Адрес, место или категория"
            setTextColor(theme.textSecondary)
            textSize = 16.5f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            background = pillGrad(theme.panelTop, theme.panelBot, 19f, theme.borderHairline)
            elevation = 8f * density
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
            ).apply { marginEnd = dp(12) }
            attachPressFeedback(this)
            setOnClickListener {
                val s = searchSheet
                if (s == null) {
                    Log.w(TAG, "tap на «Куда едем?»: поисковая шторка не создана — смотри E/NavHost 'chrome step'")
                    android.widget.Toast.makeText(
                        activity, "Поиск не инициализирован — проверьте log NavHost",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } else s.show()
            }
        }
        // иконка лупы слева в поле — через compound drawable (SVG-рендер)
        field.setCompoundDrawablesWithIntrinsicBounds(
            android.graphics.drawable.BitmapDrawable(
                activity.resources,
                ConceptIcons.fabIcon(activity, ConceptIcons.Kind.SEARCH, theme.textSecondary, boxDp = 20f)
            ).apply {
                setBounds(0, 0, dp(22), dp(22))
            }, null, null, null
        )
        field.compoundDrawablePadding = dp(11)
        tb.addView(field)
        // 🎙 микрофон h58 r19, градиент btn — та же тема, что у .mic макета
        tb.addView(ImageView(activity).apply {
            setImageBitmap(ConceptIcons.fabIcon(activity, ConceptIcons.Kind.MIC, theme.onbtn, boxDp = 58f))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = pillGrad(theme.btn, theme.btnShadowed, 19f)
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
            contentDescription = "Голосовой ввод адреса"
            attachPressFeedback(this)
            // 🎙 = диктовка адреса в поле поиска (НЕ вызов ассистента)
            setOnClickListener {
                Log.i(TAG, "tap на topbar 🎙")
                val s = searchSheet
                if (s == null) {
                    Log.w(TAG, "tap на 🎙: поисковая шторка не создана — смотри E/NavHost 'chrome step'")
                    android.widget.Toast.makeText(
                        activity, "Поиск не инициализирован — проверьте log NavHost",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } else s.startVoiceCapture()
            }
        })
        parent.addView(
            tb,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58),
                Gravity.TOP
            ).apply {
                leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(14)
            }
        )
        tb.translationZ = 20f * density
        } // chromeStep banner+topbar

        // ── шторка офлайн-регионов (цветокоррекция: из темы) ──
        var sheet: com.example.voiceapp3.nav.offline.OfflineSheetController? = null
        chromeStep("offline-sheet") {
        val s = com.example.voiceapp3.nav.offline.OfflineSheetController(
            activity,
            com.example.voiceapp3.nav.offline.OfflineMapManager.get(activity),
            density,
            theme
        ).also { offlineSheet = it }
        sheet = s
        // фейд скримом по СУММЕ намерений обеих шторок (параметр колбэка —
        // желаемое состояние; по факту view анимация ещё не завершена):
        // при взаимном вытеснении (поиск ↔ офлайн) скрим не моргает.
        s.onVisibilityChanged = { v ->
            offlineSheetShown = v
            statesBar?.setActive(
                if (v) com.example.voiceapp3.nav.StateBarController.UiState.OFFLINE
                else com.example.voiceapp3.nav.StateBarController.UiState.MAP
            )
            updateScrim()
        }
        } // chromeStep offline-sheet

        // ── этап 6: шторка поиска (тот же визуальный язык, общий скрим) ──
        var search: com.example.voiceapp3.nav.search.SearchSheetController? = null
        chromeStep("search-sheet") {
        val repo = searchRepo
            ?: com.example.voiceapp3.nav.search.SearchRepository(activity).also { searchRepo = it }
        repo.setPosition(lastSnap?.latitude, lastSnap?.longitude)
        val s = com.example.voiceapp3.nav.search.SearchSheetController(
            activity, repo, density, theme
        ).also { searchSheet = it }
        search = s
        s.onVisibilityChanged = { v ->
            if (v) {
                offlineSheet?.hide()   // одна шторка за раз
                placeSheet?.hide()
                driveChrome?.hide()
            }
            searchSheetShown = v
            setChromeHidden(v)         // макет s-search: topbar и колонка фабов уходят
            statesBar?.setActive(
                when {
                    v -> com.example.voiceapp3.nav.StateBarController.UiState.SEARCH
                    placeSheet?.isShown() == true ->
                        com.example.voiceapp3.nav.StateBarController.UiState.PLACE
                    else -> com.example.voiceapp3.nav.StateBarController.UiState.MAP
                }
            )
            updateScrim()
        }
        // выбор результата → пин + камера + карточка места (макет: item → s-place)
        s.onSelect = { r ->
            mapCtl?.let { ctl ->
                val pt = org.maplibre.android.geometry.LatLng(r.lat, r.lon)
                ctl.setDestination(pt)
                ctl.jumpTo(pt)
            }
            s.hide()
            openPlaceCard(
                com.example.voiceapp3.nav.search.PlaceSheetController.Place(
                    name = r.name,
                    subtitle = r.subtitle,
                    emoji = r.emoji,
                    lat = r.lat, lon = r.lon,
                    distanceText = distanceTextTo(r.lat, r.lon),
                    favorite = searchRepo?.isFavorite(r.key) ?: false,
                    isDestination = true,
                    historyItem = r
                )
            )
        }
        } // chromeStep search-sheet

        chromeStep("side-column") {
        // ── правая колонка (макет .side): следование(актив)→зум+/зум−→
        // слои(стиль)→офлайн; у правого края, СТРОГО по вертикальному центру
        // карты (ранее согласованное требование, макет top:88px игнорируется) ──
        val sideCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        this.sideCol = sideCol
        fun addSide(v: ImageView) {
            sideCol.addView(v, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                topMargin = if (sideCol.childCount == 0) 0 else dp(10)
            })
        }
        recenterButton = sideFabIcon(ConceptIcons.Kind.CROSSHAIR, active = true).apply {
            contentDescription = "Следование за автомобилем"
            setOnClickListener { ctl.recenter() }
        }
        followFabActive = true   // контракт NavMapController: старт в режиме follow
        recenterButton!!.background = pillGrad(theme.btn, theme.btnShadowed, 17f)
        addSide(recenterButton!!)

        addSide(sideFabIcon(ConceptIcons.Kind.ZOOM_IN).apply {
            contentDescription = "Приблизить"
            setOnClickListener { ctl.zoomBy(+1f) }
        })
        addSide(sideFabIcon(ConceptIcons.Kind.ZOOM_OUT).apply {
            contentDescription = "Отдалить"
            setOnClickListener { ctl.zoomBy(-1f) }
        })

        // макет: статичная иконка «слои» для переключателя подложки
        // (день/ночь/авто по-прежнему циклятся тем же методом)
        styleButton = sideFabIcon(ConceptIcons.Kind.LAYERS).apply {
            contentDescription = "Подложка день/ночь/авто"
            setOnClickListener { ctl.cycleStyleMode() }
        }
        addSide(styleButton!!)

        offlineButton = sideFabIcon(ConceptIcons.Kind.DOWNLOAD).apply {
            contentDescription = "Офлайн-карты"
            setOnClickListener {
                if (searchSheet?.isShown() == true) searchSheet?.hide()
                placeSheet?.hide()
                sheet?.toggle()
            }
        }
        addSide(offlineButton!!)

        parent.addView(
            sideCol,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                // Макет: у правого края и строго по вертикальному центру карты
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { marginEnd = dp(16) }
        )
        } // chromeStep side-column

        // Левая колонка (спидометр + знак ограничения) НЕ рисуется: по решению
        // пользователя она показывается только в режиме ведения по маршруту
        // (этап 7+), когда появится честный источник maxspeed.
        sheet?.let { s ->
        parent.addView(
            s.view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * SHEET_HEIGHT_FRACTION).toInt(),
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(14); rightMargin = dp(14)
                // макет .sheet{bottom:68px}: витрина над плавающим доком
                bottomMargin = dockInsetPx + dp(12)
            }
        )
        s.view.translationZ = 24f * density
        } // sheet?.let

        // макет #searchSheet: full-screen панель top:14 left/right:14 bottom:0
        search?.let { s ->
        parent.addView(
            s.view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                leftMargin = dp(14); rightMargin = dp(14)
                topMargin = dp(14)
            }
        )
        s.view.translationZ = 26f * density
        } // search?.let

        // ── карточка места (макет #placeSheet): плавающая панель над доком ──
        chromeStep("place-card") {
        val place = com.example.voiceapp3.nav.search.PlaceSheetController(
            activity, density, theme
        ).also { placeSheet = it }
        place.onDrive = { p ->
            // «Поехали →»: этап 7 — реальный маршрут (on-device GraphHopper).
            mapCtl?.setDestination(
                org.maplibre.android.geometry.LatLng(p.lat, p.lon)
            )
            place.hide()
            startRouteTo(p.lat, p.lon)
        }
        place.onShowOnMap = { p ->
            mapCtl?.jumpTo(org.maplibre.android.geometry.LatLng(p.lat, p.lon))
            place.hide()
        }
        place.onRemovePin = { mapCtl?.clearDestination() }
        place.onToggleFavorite = { p ->
            val item = p.historyItem ?: com.example.voiceapp3.nav.search.SearchResult(
                key = "coord:" + String.format(Locale.US, "%.5f,%.5f", p.lat, p.lon),
                name = p.name, subtitle = p.subtitle, lat = p.lat, lon = p.lon
            )
            searchRepo?.toggleFavorite(item) ?: p.favorite
        }
        place.onVisibilityChanged = { v ->
            statesBar?.setActive(
                if (v) com.example.voiceapp3.nav.StateBarController.UiState.PLACE
                else com.example.voiceapp3.nav.StateBarController.UiState.MAP
            )
        }
        parent.addView(
            place.view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(14); rightMargin = dp(14)
                bottomMargin = dockInsetPx + dp(12)
            }
        )
        place.view.translationZ = 25f * density
        } // chromeStep place-card

        // ── этап 7: движок маршрутизации + шторка графа ──
        chromeStep("routing") {
        val gm = com.example.voiceapp3.nav.routing.GraphManager(activity)
            .also { graphs = it }
        routingProvider = com.example.voiceapp3.nav.routing.GraphHopperRoutingProvider(gm)
        // поднимаем граф с диска в фоне — маршрут готов сразу при тапе «Поехали»
        gm.ensureLoaded()
        val rs = com.example.voiceapp3.nav.routing.RoutingSheetController(
            activity, density, theme, gm
        ).also { routingSheet = it }
        rs.onGraphReady = { Log.i(TAG, "граф маршрутов: READY") }
        parent.addView(
            rs.view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(14); rightMargin = dp(14)
                bottomMargin = dockInsetPx + dp(12)
            }
        )
        rs.view.translationZ = 26f * density
        } // chromeStep routing

        // ── макет .states: бар переключения состояний (по явному требованию
        //    пользователя; макетный демо-переключатель → навигация по реальным
        //    состояниям). Прячется при поиске (как в макете .s-search .states) ──
        chromeStep("states-bar") {
            val sb = com.example.voiceapp3.nav.StateBarController(
                activity, density, theme
            ).also { statesBar = it }
            sb.onStateSelected = { st -> applyUiState(st) }
            parent.addView(
                sb.view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { bottomMargin = dockInsetPx + dp(12) }
            )
            sb.view.translationZ = 22f * density
        } // chromeStep states-bar

        // ── макет s-drive: хром ведения (honest-chrome — только реальные
        //    поля; тестовых значений маршрута/лямбд макета не рисуем) ──
        chromeStep("drive-chrome") {
            val dc = com.example.voiceapp3.nav.DriveChromeController(
                activity, density, theme
            ).also { driveChrome = it }
            parent.addView(
                dc.view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            dc.view.translationZ = 19f * density
        } // chromeStep drive-chrome
        syncDriveDestination()

        feed = NavPositionFeed(activity) { snap -> onFix(snap) }

        decorRoot.addView(
            parent,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    // ═══════════════════════════════════ фикс GPS → карта + баннер ════

    private fun onFix(snap: NavPositionFeed.PositionSnapshot) {
        lastSnap = snap
        searchRepo?.setPosition(snap.latitude, snap.longitude)
        mapCtl?.onFix(snap)
        driveChrome?.onFix(snap)
        routeSession?.onFix(snap)
        val text = when {
            !snap.permissionGranted -> "Нет разрешения на геолокацию"
            !snap.providerEnabled -> "GPS выключен: позиция не обновляется"
            !snap.hasFix -> "Поиск спутников…"
            (snap.fixAgeMillis ?: 0L) > FIX_STALE_MS -> "Сигнал GPS нестабилен…"
            else -> null
        }
        gpsBanner?.let { b ->
            if (text == null) {
                b.visibility = View.GONE
            } else {
                if (b.text != text) b.text = text
                b.visibility = View.VISIBLE
            }
        }
    }

    /** Скрим = ИЛИ намеренной видимости любой шторки (флаги из колбэков). */
    private var offlineSheetShown = false
    private var searchSheetShown = false

    private fun updateScrim() {
        // макет: .scrim виден только в состоянии поиска (s-search); у офлайн-
        // шторки затемнения карты нет (блокировка тапов при этом сохраняется).
        animateScrim(searchSheetShown)
    }

    /** Карточка места: показать (скрывая конкурентные шторки). */
    private fun openPlaceCard(p: com.example.voiceapp3.nav.search.PlaceSheetController.Place) {
        offlineSheet?.hide()
        searchSheet?.hide()
        placeSheet?.show(p)
    }

    /** Дистанция «по прямой» до точки от последнего фикса (null — фикса нет). */
    private fun distanceTextTo(lat: Double, lon: Double): String? {
        val snap = lastSnap?.takeIf { it.hasFix } ?: return null
        return com.example.voiceapp3.nav.search.SearchResult.formatDistance(
            com.example.voiceapp3.nav.search.SearchResult.distanceMeters(
                snap.latitude!!, snap.longitude!!, lat, lon
            )
        )
    }

    /** Макет s-search: верхняя строка, правая колонка и бар состояний
     * скрываются в поиске (макет `.s-search .states{opacity:0}`). */
    private fun setChromeHidden(hidden: Boolean) {
        topBar?.visibility = if (hidden) View.GONE else View.VISIBLE
        sideCol?.visibility = if (hidden) View.GONE else View.VISIBLE
        statesBar?.view?.visibility = if (hidden) View.GONE else View.VISIBLE
    }

    /** Макет .states: централизованный перевод UI по выбранной кнопке. */
    private fun applyUiState(st: com.example.voiceapp3.nav.StateBarController.UiState) {
        statesBar?.setActive(st)
        when (st) {
            com.example.voiceapp3.nav.StateBarController.UiState.MAP -> {
                searchSheet?.hide()
                offlineSheet?.hide()
                placeSheet?.hide()
                driveChrome?.hide()
            }
            com.example.voiceapp3.nav.StateBarController.UiState.SEARCH ->
                searchSheet?.show()   // остальное прячет его колбэк видимости
            com.example.voiceapp3.nav.StateBarController.UiState.PLACE -> {
                // точка — реальная: пин назначения, иначе честная метка по GPS;
                // без обоих — не открываем (тестовых координат не рисуем).
                val dest = mapCtl?.currentDestination
                val snap = lastSnap
                val lat = dest?.latitude ?: snap?.latitude
                val lon = dest?.longitude ?: snap?.longitude
                if (lat != null && lon != null) {
                    openPlaceCard(
                        com.example.voiceapp3.nav.search.PlaceSheetController.Place(
                            name = "Метка на карте",
                            subtitle = String.format(Locale.US, "%.5f, %.5f", lat, lon),
                            emoji = "📍",
                            lat = lat, lon = lon,
                            distanceText = distanceTextTo(lat, lon),
                            favorite = false,
                            isDestination = dest != null
                        )
                    )
                } else {
                    Log.i(TAG, "state PLACE: ни пина, ни фикса — карточку не открываем")
                    statesBar?.setActive(
                        com.example.voiceapp3.nav.StateBarController.UiState.MAP
                    )
                }
            }
            com.example.voiceapp3.nav.StateBarController.UiState.DRIVE -> {
                searchSheet?.hide()
                offlineSheet?.hide()
                placeSheet?.hide()
                syncDriveDestination()
                driveChrome?.show()
            }
            com.example.voiceapp3.nav.StateBarController.UiState.OFFLINE -> {
                if (offlineSheet?.isShown() == true) {
                    offlineSheet?.hide()
                } else {
                    searchSheet?.hide()
                    placeSheet?.hide()
                    driveChrome?.hide()
                    offlineSheet?.show()
                }
            }
        }
    }

    // ═══════════════════ этап 7: построение маршрута и сессия ════

    /** «Поехали →»: строим настоящий маршрут on-device. Честные ветвления:
     *  нет GPS-фикса → тост; граф не готов → шторка графа; ошибка движка —
     *  причина в лог и тост. Успех → линия на карте + состояние «Навигация». */
    private fun startRouteTo(lat: Double, lon: Double) {
        val snap = lastSnap
        if (snap == null || !snap.hasFix || snap.latitude == null || snap.longitude == null) {
            Log.w(TAG, "startRouteTo: нет свежего GPS-фикса — не строим")
            toast("Не вижу GPS-позицию — маршрут не построить")
            return
        }
        val from = com.example.voiceapp3.nav.routing.RoutePoint(snap.latitude, snap.longitude)
        val to = com.example.voiceapp3.nav.routing.RoutePoint(lat, lon)
        val gm = graphs
        val provider = routingProvider
        if (gm == null || provider == null) {
            Log.e(TAG, "startRouteTo: chrome routing не собран")
            toast("Маршрутизация недоступна в этой сборке")
            return
        }
        if (!provider.isReady()) {
            Log.i(TAG, "startRouteTo: граф не готов — открываю шторку графа")
            routingSheet?.show()
            return
        }
        toast("Строю маршрут…")
        routeWorker.execute {
            val res = runCatching { provider.route(from, to) }
            mainHandler.post {
                res.onSuccess { route -> startRouteSession(route) }
                    .onFailure { e ->
                        Log.e(TAG, "startRouteTo: построение не удалось", e)
                        when (e) {
                            is com.example.voiceapp3.nav.routing.RoutingError.GraphNotReady ->
                                routingSheet?.show()
                            else -> toast(e.message ?: "маршрут не построен")
                        }
                    }
            }
        }
    }

    private fun startRouteSession(route: com.example.voiceapp3.nav.routing.RouteResult) {
        stopRouteSession()
        drawRouteLine(route)
        routeSession = makeRouteSession(route)
        statesBar?.setActive(com.example.voiceapp3.nav.StateBarController.UiState.DRIVE)
        Log.i(TAG, String.format(
            java.util.Locale.US,
            "маршрут построен: %.1f км, ~%d мин, манёвров %d, альтернатив %d",
            route.distanceM / 1000.0, route.timeMs / 60000,
            route.maneuvers.size, route.alternativesCount))
    }

    private fun stopRouteSession() {
        routeSession = null
        driveChrome?.setNavInfo(null)
        mapCtl?.setRoutePolyline(null)
    }

    private fun drawRouteLine(route: com.example.voiceapp3.nav.routing.RouteResult) {
        mapCtl?.setRoutePolyline(
            route.points.map { org.maplibre.android.geometry.LatLng(it.lat, it.lon) }
        )
    }

    /** Сессия с ПОЛНЫМ колбэком (ведение, off-route с троттлингом, финиш);
     *  используется и при первом старте, и после каждого пересчёта. */
    private fun makeRouteSession(route: com.example.voiceapp3.nav.routing.RouteResult):
            com.example.voiceapp3.nav.routing.RouteSessionController {
        return com.example.voiceapp3.nav.routing.RouteSessionController(
            route,
            object : com.example.voiceapp3.nav.routing.RouteSessionController.Callback {
                override fun onNavUpdate(
                    info: com.example.voiceapp3.nav.routing.RouteSessionController.NavUpdate
                ) {
                    driveChrome?.setNavInfo(
                        com.example.voiceapp3.nav.DriveChromeController.NavInfo(
                            arrowDeg = info.arrowDeg,
                            maneuverText = info.maneuverText,
                            distToManeuverM = info.distToManeuverM,
                            remainM = info.remainM,
                            remainMs = info.remainMs
                        )
                    )
                }

                override fun onOffRoute(snap: NavPositionFeed.PositionSnapshot) {
                    val now = System.currentTimeMillis()
                    val sess = routeSession ?: return
                    if (now - rerouteAtMs < REROUTE_MIN_GAP_MS) {
                        sess.setRerouting(false); return
                    }
                    val d = mapCtl?.currentDestination
                    val provider = routingProvider
                    if (d == null || provider == null ||
                        snap.latitude == null || snap.longitude == null
                    ) {
                        sess.setRerouting(false); return
                    }
                    rerouteAtMs = now
                    val from = com.example.voiceapp3.nav.routing.RoutePoint(
                        snap.latitude, snap.longitude)
                    val to = com.example.voiceapp3.nav.routing.RoutePoint(
                        d.latitude, d.longitude)
                    Log.i(TAG, "пересчёт маршрута после ухода с линии")
                    routeWorker.execute {
                        val res = runCatching { provider.route(from, to) }
                        mainHandler.post {
                            res.onSuccess { newRoute ->
                                if (routeSession != null) {
                                    startRouteSession(newRoute)
                                    toast("Маршрут перестроен")
                                }
                            }.onFailure { e ->
                                Log.e(TAG, "пересчёт не удался", e)
                                toast("Пересчёт не удался: ${e.message}")
                                routeSession?.setRerouting(false)
                            }
                        }
                    }
                }

                override fun onArrived() {
                    Log.i(TAG, "финиш маршрута достигнут")
                    toast("Вы на месте")
                    stopRouteSession()
                }
            }
        )
    }

    /** Хром ведения: раскладать актуальную точку назначения. */
    private fun syncDriveDestination() {
        val d = mapCtl?.currentDestination
        if (d != null) driveChrome?.setDestination(d.latitude, d.longitude)
        else driveChrome?.clearDestination()
    }

    /** Фейд 0.45s скрима под шторку (backdrop-имитация макета). */
    private fun animateScrim(visible: Boolean) {
        val scrim = scrimView ?: return
        scrim.animate().cancel()
        if (visible) {
            scrim.visibility = View.VISIBLE
        }
        scrim.animate()
            .alpha(if (visible) 1f else 0f)
            .setDuration(450)
            .withEndAction { if (!visible) scrim.visibility = View.GONE }
            .start()
    }

    /** Отклик нажатия фаб/плашек (макет .fab:hover): scale 0.94 → 1. */
    private fun attachPressFeedback(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.94f).scaleY(0.94f)
                    .setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate()
                    .scaleX(1f).scaleY(1f).setDuration(180).start()
            }
            false
        }
    }

    /** Подсветка фаб-«следования»: актив = заливка btn темы + иконка onbtn. */
    private fun setFollowActive(following: Boolean) {
        if (followFabActive == following) return
        followFabActive = following
        recenterButton?.apply {
            background = if (following) pillGrad(theme.btn, theme.btnShadowed, 17f)
                else pill(theme.glassFab, 17f, theme.borderHairline)
            setImageBitmap(
                ConceptIcons.fabIcon(
                    activity, ConceptIcons.Kind.CROSSHAIR,
                    if (following) theme.onbtn else theme.ink
                )
            )
        }
    }


    // ═══════════════════════════════════ фабрики chrome (концепт этапа 5) ════

    /** Фаб-карта правой колонки: стекло 52dp, радиус 17, иконка по центру (SVG-рендер). */
    private fun sideFabIcon(kind: ConceptIcons.Kind, active: Boolean = false): ImageView =
        ImageView(activity).apply {
            setImageBitmap(
                ConceptIcons.fabIcon(
                    activity, kind,
                    if (active) theme.onbtn else theme.ink
                )
            )
            background = pill(theme.glassFab, 17f, theme.borderHairline)
            elevation = 8f * density
            foreground = ripple()
            scaleType = ImageView.ScaleType.CENTER_INSIDE   // иконка строго по центру
            setPadding(dp(14), dp(14), dp(14), dp(14))       // 23dp глиф в боксе 52
            attachPressFeedback(this)
        }

    /** Системный borderless-ripple (API 23+). */
    private fun ripple(): Drawable? {
        val out = TypedValue()
        activity.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, out, true
        )
        return if (out.resourceId != 0)
            runCatching { activity.getDrawable(out.resourceId) }.getOrNull()
        else null
    }

    private fun pill(color: Int, radiusDp: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * density
            strokeColor?.let { setStroke(dp(1), it) }
        }

    /** Вертикальный градиент-пилюля (панель/актив-фон из пары цветов темы). */
    private fun pillGrad(top: Int, bottom: Int, radiusDp: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = radiusDp * density
            strokeColor?.let { setStroke(dp(1), it) }
        }

    /** Короткий честный тост (все вызовы — с main-потока). */
    private fun toast(msg: String) {
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    /** Хром строится по шагам: авария одного блока (например, иконки) не
     * должна убивать весь слой — иначе карта остаётся без хрома И без
     * маршрутизации тапов (NavChromeLayer так и не добавляется). */
    private inline fun chromeStep(tag: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.e(TAG, "chrome step '$tag' failed", t) }
    }

    // ═══════════════════════════════════ слой-роутер тапов ════

    /**
     * Прозрачный контейнер chrome поверх контента активности. Дочерние виды
     * сами ловят тапы; а оставшееся пространство НЕ бросаем — иначе карта под
     * контентом недоступна жестам. Распределение по y:
     *  · полоса плавающего дока (ниже dockTopY) → события в док (вкладки);
     *  · остальное → события в MapView (пан/зум/поворот).
     * Когда шторка офлайна открыта — фон блокируем (мокап backdrop).
     */
    private class NavChromeLayer(activity: Activity) : FrameLayout(activity) {
        var mapTarget: View? = null
        var dockTarget: View? = null
        var dockTopY: () -> Int = { Int.MAX_VALUE }
        var interactionsLocked: () -> Boolean = { false }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (super.dispatchTouchEvent(ev)) return true      // дочерний вид «съел»
            if (interactionsLocked()) return true              // открыта шторка — фон немой
            if (ev.y >= dockTopY()) {
                dockTarget?.dispatchTouchEvent(ev)
                return true
            }
            mapTarget?.dispatchTouchEvent(ev)
            return true
        }
    }

    companion object {
        private const val TAG = "NavHost"

        /** Запасной вырез под док до первого замера из JS (как у телеметрии). */
        private const val DEFAULT_DOCK_INSET_DP = 92

        /** Радиус углов блока карты — как .screen{border-radius:22px} макета. */
        private const val MAP_CORNER_DP = 22f

        /** Фикс старше этого возраста считается устаревшим. */
        private const val FIX_STALE_MS = 10_000L

        /** Доля высоты экрана под лист офлайн-карт (макет max-height:62%). */
        private const val SHEET_HEIGHT_FRACTION = 0.62f

        /** Минимальная пауза между автоматическими пересчётами маршрута. */
        private const val REROUTE_MIN_GAP_MS = 15_000L
    }
}
