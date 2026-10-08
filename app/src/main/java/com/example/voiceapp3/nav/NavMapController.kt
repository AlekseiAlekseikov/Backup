package com.example.voiceapp3.nav

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import java.util.Calendar
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Stage NAV-MAP (этап 4 переписи навигации): обёртка MapLibre-карты.
 *
 * Владеет MapView и его жизненным циклом (как TelemetryHostController владеет
 * FlutterView), камерой, режимом последования, стилем день/ночь и одним пином
 * назначения. Никаких маршрутов/поиска здесь нет — это этапы 6–8.
 *
 * Стиль — вендорные nav_day.json / nav_night.json в assets/ui/nav/styles/
 * (OpenFreeMap Positron/Dark, атрибуция OSM/OpenFreeMap/OpenMapTiles
 * добавлена в источники стиля — требование публичного инстанса). Рендер — TextureView-режим: не пробивает
 * окно и ложится поверх WebView обычным z-порядком (тот же приём, что
 * FlutterTextureView телеметрии).
 *
 * Состояние персистится в общие prefs шелла («voiceapp3», префикс nav_*).
 */
class NavMapController(private val activity: Activity) {

    interface DestinationListener {
        /** Долгий тап поставил/переставил точку назначения (координаты WGS-84). */
        fun onDestinationChanged(latitude: Double, longitude: Double)

        /** Пин снят (кнопка «Убрать» или замена стиля не требуется). */
        fun onDestinationCleared()
    }

    /** Режим подложки: авто (по часу суток), форс-день, форс-ночь. */
    enum class StyleMode { AUTO, DAY, NIGHT }

    var destinationListener: DestinationListener? = null

    /** Хосту — менять видимость кнопки «центрировать». */
    var onFollowChanged: ((Boolean) -> Unit)? = null

    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val density = activity.resources.displayMetrics.density
    private val puck = NavPuck(activity)

    /** MapView привязан к разделённому жизненному циклу host-контейнера. */
    lateinit var mapView: MapView
        private set

    private var map: MapLibreMap? = null

    /** Последование камеры за позицией; жесты пользователя его снимают. */
    private var follow = true
    val isFollowing: Boolean get() = follow

    /** Камера хоть раз вставала на реальный фикс (иначе — обзорный кадр). */
    private var everHadFixCamera = false

    private var lastFix: NavPositionFeed.PositionSnapshot? = null
    private var destinationLL: LatLng? = null

    /** Текущая точка назначения (null — пин не поставлен). Честный accessor
     * для карточки места/панели состояний (этап navigator-app-7). */
    val currentDestination: LatLng? get() = destinationLL

    // Дефолт — AUTO: подложка следует за выбранной темой приложения
    // (яркость палитры тем из index.html), часовой пояс — только запасной
    // мост, пока тема ещё ни разу не доложилась. Кнопка ☀/☾/⟳ — форс.
    private var styleMode: StyleMode =
        runCatching { StyleMode.valueOf(prefs.getString(KEY_STYLE, null) ?: "AUTO") }
            .getOrDefault(StyleMode.AUTO)

    /** Яркость действующей темы шелла (true = тёмная); null — тема молчит. */
    private var themeDark: Boolean? = null

    private var lastStyleUri: String? = null

    /** Однократная инициализация движка (как у FlutterEngine телеметрии). */
    private var engineInitDone = false

    // ═══════════════════════════════════ создание/жизненный цикл ════

    fun isCreated(): Boolean = ::mapView.isInitialized

    /** Создать MapView; начальная камера выставляется в onMapReady. */
    fun createView(): MapView {
        if (!engineInitDone) {
            // Статическая инициализация нативного ядра: до первого MapView.
            MapLibre.getInstance(activity.applicationContext)
            engineInitDone = true
        }
        val view = MapView(activity)
        mapView = view
        view.onCreate(null)
        view.getMapAsync { m -> onMapReady(m) }
        return view
    }

    private fun onMapReady(m: MapLibreMap) {
        map = m
        restoreCamera(m)
        val ui = m.uiSettings
        ui.isCompassEnabled = true
        ui.isRotateGesturesEnabled = true
        ui.isTiltGesturesEnabled = true
        m.setMaxZoomPreference(MAX_ZOOM)

        // Жестовое движение карты разрывает последование; тап по кнопке
        // «центрировать»/компас-клик его восстанавливают — конвенция навигаторов.
        m.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                setFollow(false)
            }
        }
        // POI-foundation этапа 4: долгий тап ставит точку назначения.
        m.addOnMapLongClickListener { pt ->
            setDestination(LatLng(pt.latitude, pt.longitude))
            true
        }
        applyStyle()
    }

    /** Камера при старте: сохранённая с прошлого запуска, иначе обзор региона. */
    private fun restoreCamera(m: MapLibreMap) {
        val savedLat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull()
        val savedLon = prefs.getString(KEY_LON, null)?.toDoubleOrNull()
        if (savedLat != null && savedLon != null) {
            m.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(savedLat, savedLon))
                        .zoom(
                            prefs.getFloat(KEY_ZOOM, FALLBACK_ZOOM)
                                .toDouble()
                                .coerceIn(MIN_ZOOM, MAX_ZOOM)
                        )
                        .bearing(prefs.getFloat(KEY_BEARING, 0f).toDouble())
                        .tilt(prefs.getFloat(KEY_TILT, 0f).toDouble())
                        .build()
                )
            )
        } else {
            // Без истории — обзорный витринный кадр до первого фикса.
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(OVERVIEW_TARGET, OVERVIEW_ZOOM))
        }
    }

    fun onStart() { if (isCreated()) mapView.onStart() }
    fun onResume() { if (isCreated()) mapView.onResume() }
    fun onPause() { if (isCreated()) mapView.onPause() }
    fun onStop() { if (isCreated()) mapView.onStop() }
    fun onLowMemory() { if (isCreated()) mapView.onLowMemory() }

    fun onDestroy() {
        persistCamera()
        if (isCreated()) mapView.onDestroy()
        puck.release()
        map = null
    }

    // ═══════════════════════════════════ позиция/последование ════

    /** Фикс геолокации от NavPositionFeed (UI-поток, 1 Гц). */
    fun onFix(fix: NavPositionFeed.PositionSnapshot) {
        lastFix = fix
        if (!fix.hasFix) return
        puck.update(fix)
        if (!follow) return
        val m = map ?: return
        val target = LatLng(fix.latitude!!, fix.longitude!!)
        if (!everHadFixCamera) {
            everHadFixCamera = true
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(target, FOLLOW_ZOOM), FIRST_FIX_MS)
        } else {
            // Вращаем карту по курсу только при валидном курсе (>1,5 км/ч —
            // правило capy для heading); ниже порога сохраняем текущий азимут.
            val arrowOk = fix.bearingDeg != null && (fix.speedMps ?: 0f) >= HEADING_MIN_SPEED_MPS
            val cam = CameraPosition.Builder()
                .target(target)
                .zoom(m.cameraPosition.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM))
                .bearing(
                    if (arrowOk) fix.bearingDeg!!.toDouble()
                    else m.cameraPosition.bearing
                )
                .tilt(m.cameraPosition.tilt)
                .build()
            m.easeCamera(CameraUpdateFactory.newCameraPosition(cam), FOLLOW_EASE_MS)
        }
    }

    /** «Центрировать»: вернуть последование и подвести камеру к позиции. */
    fun recenter() {
        setFollow(true)
        val fix = lastFix?.takeIf { it.hasFix } ?: return
        val m = map ?: return
        val zoom = maxOf(m.cameraPosition.zoom, FOLLOW_ZOOM)
        m.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(fix.latitude!!, fix.longitude!!), zoom),
            RECENTER_MS
        )
    }

    /** Шаг зума из правой колонки управления (концепт этапа 5). */
    fun zoomBy(levels: Float) {
        val m = map ?: return
        val target =
            (m.cameraPosition.zoom + levels).coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
        m.animateCamera(CameraUpdateFactory.zoomTo(target), ZOOM_ANIM_MS)
    }

    /**
     * Этап 6 (поиск): показать выбранную точку — как пользовательский жест:
     * последование снимается (так же, как от pan), камера подводится к цели
     * с разумным зумом. recenter() возвращает следование обратно.
     */
    fun jumpTo(pt: LatLng, zoom: Double = JUMP_ZOOM) {
        val m = map ?: return
        setFollow(false)
        val z = maxOf(m.cameraPosition.zoom, zoom).coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(pt, z), JUMP_MS)
    }

    private fun setFollow(value: Boolean) {
        if (follow == value) return
        follow = value
        onFollowChanged?.invoke(value)
    }

    // ═══════════════════════════════════ стиль день/ночь ════

    /** Циклически: день → ночь → авто. Возвращает новый режим. */
    fun cycleStyleMode(): StyleMode {
        styleMode = when (styleMode) {
            StyleMode.DAY -> StyleMode.NIGHT
            StyleMode.NIGHT -> StyleMode.AUTO
            StyleMode.AUTO -> StyleMode.DAY
        }
        prefs.edit().putString(KEY_STYLE, styleMode.name).apply()
        applyStyle()
        return styleMode
    }

    /** Глиф текущего режима для кнопки хоста. */
    fun styleModeGlyph(): String = when (styleMode) {
        StyleMode.DAY -> "☀"
        StyleMode.NIGHT -> "☾"
        StyleMode.AUTO -> "⟳"
    }

    /** Текущий режим подложки (кнопка хоста рисует SVG-иконку — ConceptIcons). */
    fun currentStyleMode(): StyleMode = styleMode

    private fun effectiveIsDay(): Boolean = when (styleMode) {
        StyleMode.DAY -> true
        StyleMode.NIGHT -> false
        StyleMode.AUTO -> themeDark?.let { !it } ?: run {
            // Тема ещё не доложилась (первая секунда старта) — по часам суток.
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            h in AUTO_DAY_START_HOUR until AUTO_DAY_END_HOUR
        }
    }

    /**
     * Тема приложения (ui.theme/tab.change): яркость управляет стилем в AUTO
     * (DAY/NIGHT форс остаются за пользователем), акцент перекрашивает пакер.
     */
    fun onThemeChanged(dark: Boolean, accent: Int) {
        themeDark = dark
        runCatching { puck.setAccentColor(accent) }
            .onFailure { Log.w(TAG, "puck accent failed", it) }
        if (styleMode == StyleMode.AUTO) applyStyle()
    }

    private fun applyStyle() {
        val m = map ?: return
        val uri = if (effectiveIsDay()) DAY_STYLE_URI else NIGHT_STYLE_URI
        if (uri == lastStyleUri) return
        lastStyleUri = uri
        m.setStyle(Style.Builder().fromUri(uri)) { style ->
            puck.install(style)
            installRoute(style)
            installPin(style)
            // Перезагрузка стиля сносила слои — вернуть последнее состояние.
            lastFix?.let { puck.update(it) }
            destinationLL?.let { updatePinFeature(it) }
            lastRoute?.let { updateRouteFeature(it) }
        }
    }

    // ═══════════════════════════════════ пин назначения (POI foundation) ════

    fun setDestination(pt: LatLng) {
        destinationLL = pt
        updatePinFeature(pt)
        destinationListener?.onDestinationChanged(pt.latitude, pt.longitude)
    }

    fun clearDestination() {
        destinationLL = null
        runCatching {
            val s = map?.style ?: return@runCatching
            // КРАШ (этап 5 хотфикс): Feature.fromGeometry(null) падал в нативном
            // GeoJSON-парсере при тапе «Убрать». Правильная очистка источника —
            // пустая FeatureCollection.
            (s.getSource(PIN_SOURCE) as? GeoJsonSource)
                ?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }.onFailure { Log.w(TAG, "clearDestination failed", it) }
        destinationListener?.onDestinationCleared()
    }

    private fun installPin(style: Style) {
        if (style.getImage(PIN_IMAGE) == null) style.addImage(PIN_IMAGE, pinBitmap())
        if (style.getSource(PIN_SOURCE) == null) style.addSource(GeoJsonSource(PIN_SOURCE))
        if (style.getLayer(PIN_LAYER) == null) {
            style.addLayer(
                SymbolLayer(PIN_LAYER, PIN_SOURCE).withProperties(
                    iconImage(PIN_IMAGE),
                    iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    iconSize(PIN_ICON_SCALE)
                )
            )
        }
    }

    private fun updatePinFeature(pt: LatLng) {
        runCatching {
            val s = map?.style ?: return@runCatching
            (s.getSource(PIN_SOURCE) as? GeoJsonSource)?.setGeoJson(
                FeatureCollection.fromFeatures(
                    listOf(Feature.fromGeometry(Point.fromLngLat(pt.longitude, pt.latitude)))
                )
            )
        }.onFailure { Log.w(TAG, "updatePinFeature failed", it) }
    }

    // ═══════════════════════════════════ линия маршрута (этап 7) ════

    /** Последняя полилиния маршрута — переживает перезагрузку стиля. */
    private var lastRoute: List<LatLng>? = null

    /** Показать/обновить линию маршрута; null → убрать. */
    fun setRoutePolyline(points: List<LatLng>?) {
        lastRoute = points
        runCatching {
            val s = map?.style ?: return@runCatching
            (s.getSource(ROUTE_SOURCE) as? GeoJsonSource)?.setGeoJson(
                if (points == null) FeatureCollection.fromFeatures(emptyList())
                else routeRouteFeature(points)
            )
        }.onFailure { Log.w(TAG, "setRoutePolyline failed", it) }
    }

    private fun routeRouteFeature(points: List<LatLng>): FeatureCollection =
        FeatureCollection.fromFeatures(
            listOf(
                Feature.fromGeometry(
                    org.maplibre.geojson.LineString.fromLngLats(
                        points.map { Point.fromLngLat(it.longitude, it.latitude) }
                    )
                )
            )
        )

    private fun updateRouteFeature(points: List<LatLng>) {
        runCatching {
            val s = map?.style ?: return@runCatching
            (s.getSource(ROUTE_SOURCE) as? GeoJsonSource)
                ?.setGeoJson(routeRouteFeature(points))
        }.onFailure { Log.w(TAG, "updateRouteFeature failed", it) }
    }

    private fun installRoute(style: Style) {
        if (style.getSource(ROUTE_SOURCE) == null) style.addSource(GeoJsonSource(ROUTE_SOURCE))
        if (style.getLayer(ROUTE_CASING_LAYER) == null) {
            style.addLayer(
                org.maplibre.android.style.layers.LineLayer(
                    ROUTE_CASING_LAYER, ROUTE_SOURCE
                ).withProperties(
                    org.maplibre.android.style.layers.PropertyFactory.lineColor(ROUTE_CASING_COLOR),
                    org.maplibre.android.style.layers.PropertyFactory.lineWidth(ROUTE_CASING_W),
                    org.maplibre.android.style.layers.PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    org.maplibre.android.style.layers.PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
        }
        if (style.getLayer(ROUTE_LAYER) == null) {
            style.addLayer(
                org.maplibre.android.style.layers.LineLayer(
                    ROUTE_LAYER, ROUTE_SOURCE
                ).withProperties(
                    org.maplibre.android.style.layers.PropertyFactory.lineColor(ROUTE_COLOR),
                    org.maplibre.android.style.layers.PropertyFactory.lineWidth(ROUTE_W),
                    org.maplibre.android.style.layers.PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    org.maplibre.android.style.layers.PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
        }
        // линия выше дорог подложки, но ниже пина/пака (pin добавляется после)
    }

    // ═══════════════════════════════════ персистентность ════

    fun persistCamera() {
        val c = map?.cameraPosition ?: return
        val t = c.target ?: return
        prefs.edit()
            .putString(KEY_LAT, t.latitude.toString())
            .putString(KEY_LON, t.longitude.toString())
            .putFloat(KEY_ZOOM, c.zoom.toFloat())
            .putFloat(KEY_BEARING, c.bearing.toFloat())
            .putFloat(KEY_TILT, c.tilt.toFloat())
            .apply()
    }

    // ═══════════════════════════════════ рантайм-графика пина ════

    private fun pinBitmap(): Bitmap {
        val w = (PIN_W_DP * density).toInt()
        val h = (PIN_H_DP * density).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = density
        val cx = w / 2f
        val r = 12f * p
        val cy = r + 2f * p
        val body = Path().apply {
            addCircle(cx, cy, r, Path.Direction.CW)
            moveTo(cx - r * 0.62f, cy + r * 0.78f)
            lineTo(cx, h - 1f * p)
            lineTo(cx + r * 0.62f, cy + r * 0.78f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = PIN_RED
        }
        c.drawPath(body, fill)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        c.drawCircle(cx, cy, r * 0.42f, dot)
        return bmp
    }

    companion object {
        private const val PREFS = "voiceapp3"
        private const val KEY_LAT = "nav_cam_lat"
        private const val KEY_LON = "nav_cam_lon"
        private const val KEY_ZOOM = "nav_cam_zoom"
        private const val KEY_BEARING = "nav_cam_bearing"
        private const val KEY_TILT = "nav_cam_tilt"
        private const val KEY_STYLE = "nav_style_mode"

        private const val TAG = "NavMap"

        /** Клампы зум-кнопок правой колонки. */
        private const val MIN_MAP_ZOOM = 3.0
        private const val MAX_MAP_ZOOM = 18.5
        private const val ZOOM_ANIM_MS = 300

        /** Этап 6: зум/длительность подводки камеры к выбранному результату поиска. */
        private const val JUMP_ZOOM = 15.5
        private const val JUMP_MS = 800
        internal const val DAY_STYLE_URI = "asset://ui/nav/styles/nav_day.json"
        internal const val NIGHT_STYLE_URI = "asset://ui/nav/styles/nav_night.json"
        private const val AUTO_DAY_START_HOUR = 6
        private const val AUTO_DAY_END_HOUR = 21

        /** Витринный обзор до первого фикса: весь доступный offline-регион (BY). */
        private val OVERVIEW_TARGET = LatLng(53.9, 27.56)
        private const val OVERVIEW_ZOOM = 6.0
        private const val FALLBACK_ZOOM = 14f
        private const val MIN_ZOOM = 3.0
        private const val MAX_ZOOM = 19.0

        /** Рабочий зум последования (улица/район, как у авто-навигаторов). */
        private const val FOLLOW_ZOOM = 16.5
        private const val FIRST_FIX_MS = 700
        private const val FOLLOW_EASE_MS = 450
        private const val RECENTER_MS = 600
        private const val HEADING_MIN_SPEED_MPS = 0.42f

        private const val PIN_SOURCE = "nav-dest-src"
        private const val PIN_LAYER = "nav-dest-pin"
        private const val PIN_IMAGE = "nav-dest-pin-img"
        private const val PIN_ICON_SCALE = 0.9f
        private const val PIN_W_DP = 32f
        private const val PIN_H_DP = 40f
        private const val PIN_RED = 0xFFE5484D.toInt()

        /** Этап 7: линия маршрута — акцентная нить с тёмным кожухом. */
        private const val ROUTE_SOURCE = "nav-route-src"
        private const val ROUTE_LAYER = "nav-route-line"
        private const val ROUTE_CASING_LAYER = "nav-route-casing"
        private const val ROUTE_COLOR = 0xFF4C8DFF.toInt()
        private const val ROUTE_CASING_COLOR = 0xD9101822.toInt()
        private const val ROUTE_W = 5.5f
        private const val ROUTE_CASING_W = 9.5f
    }
}
