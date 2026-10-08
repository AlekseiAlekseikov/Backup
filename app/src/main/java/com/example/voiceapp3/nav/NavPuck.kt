package com.example.voiceapp3.nav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.JsonObject
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circlePitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.circlePitchScale
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Stage NAV-MAP (этап 4): «пакет» автомобиля на карте — точка позиции,
 * круг точности (в метрах, масштабируется с питчем) и стрелка курса.
 *
 * Дизайн-обновление (концепт этапа 5 «навигатор»): к облику добавлены
 * светлый синий конус курса (world-scale треугольник перед точкой,
 * полное угловое раскрытие 48°) и экранный пульс-ореол вокруг точки.
 * Обе надстройки — честные: конус рисуется только когда курс валиден
 * (правило capy: GNSS-курс приемлем при скорости ≥ 1,5 км/ч); пульс —
 * просто атмосфера, данные не несёт. На парковке/ползучем режиме —
 * честная точка без выдуманного направления.
 *
 * Иконка курса генерируется в рантайме (Canvas) — отдельный спрайт-атлас
 * не нужен, стиль остаётся чистым вендором OpenFreeMap. При смене стиля
 * (день/ночь) слои переустанавливаются через [install], последний фикс
 * пере-эмитится навигатором сам.
 */
class NavPuck(context: Context) {

    private val density = context.resources.displayMetrics.density

    /** Последняя выданная геометрия точки (переживает перезагрузку стиля). */
    private var lastFeature: Feature? = null

    /** Последняя собранная геометрия конуса (тоже переживает перезагрузку). */
    private var lastCone: FeatureCollection? = null

    @Volatile
    private var style: Style? = null

    /** Акцент пакера — цвет акцента действующей темы (по умолчанию — синий этапа 4). */
    @Volatile
    private var accent: Int = PUCK_BLUE

    /**
     * Перекрасить пакер в акцент новой темы. Если стиль активен — слои
     * перестраиваются немедленно (немаленький круг) и перерисовывается
     * спрайт стрелки; при следующей перезагрузке стиля подхватится само.
     */
    fun setAccentColor(color: Int) {
        if (accent == color) return
        accent = color
        val s = style ?: return
        runCatching {
            if (s.isFullyLoaded) {
                (s.getLayer(ACCURACY_LAYER) as? CircleLayer)?.setProperties(
                    circleColor(color), circleStrokeColor(color)
                )
                (s.getLayer(PULSE_LAYER) as? CircleLayer)?.setProperties(circleColor(color))
                (s.getLayer(CONE_LAYER) as? FillLayer)?.setProperties(fillColor(color))
                (s.getLayer(DOT_LAYER) as? CircleLayer)?.setProperties(circleColor(color))
                // стрелка хранится как Bitmap — перегенерировать под акцент
                s.removeImage(ARROW_IMAGE)
                s.addImage(ARROW_IMAGE, arrowBitmap())
            }
        }.onFailure { Log.i(TAG, "setAccentColor deferred", it) }
    }

    /** Установить источник/слои поверх загруженного стиля. Идемпотентно. */
    fun install(target: Style) {
        style = target
        if (target.getImage(ARROW_IMAGE) == null) {
            target.addImage(ARROW_IMAGE, arrowBitmap())
        }
        if (target.getSource(SOURCE_ID) == null) {
            target.addSource(GeoJsonSource(SOURCE_ID).also { src ->
                lastFeature?.let { src.setGeoJson(it) }
            })
        }
        if (target.getSource(CONE_SOURCE_ID) == null) {
            target.addSource(GeoJsonSource(CONE_SOURCE_ID).also { src ->
                lastCone?.let { src.setGeoJson(it) }
            })
        }

        if (target.getLayer(ACCURACY_LAYER) == null) {
            target.addLayer(
                CircleLayer(ACCURACY_LAYER, SOURCE_ID).withProperties(
                    circleRadius(get(PROP_ACCURACY)),
                    circleColor(accent),
                    circleOpacity(0.16f),
                    circleStrokeColor(accent),
                    circleStrokeWidth(1f),
                    circlePitchScale(Property.CIRCLE_PITCH_SCALE_MAP),
                    circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)
                )
            )
        }
        if (target.getLayer(PULSE_LAYER) == null) {
            target.addLayer(
                // экранный пульс: размер в пикселях, не масштабируем с зумом
                CircleLayer(PULSE_LAYER, SOURCE_ID).withProperties(
                    circleRadius(PULSE_MIN_RADIUS_PX * density),
                    circleColor(accent),
                    circleOpacity(0f)
                )
            )
        }
        if (target.getLayer(CONE_LAYER) == null) {
            target.addLayer(
                FillLayer(CONE_LAYER, CONE_SOURCE_ID).withProperties(
                    fillColor(accent),
                    fillOpacity(0.16f)
                )
            )
        }
        if (target.getLayer(DOT_LAYER) == null) {
            target.addLayer(
                CircleLayer(DOT_LAYER, SOURCE_ID).withProperties(
                    circleRadius(DOT_RADIUS_PX * density),
                    circleColor(accent),
                    circleStrokeColor(Color.WHITE),
                    circleStrokeWidth(DOT_STROKE_PX * density),
                    circleOpacity(1f)
                )
            )
        }
        if (target.getLayer(ARROW_LAYER) == null) {
            target.addLayer(
                SymbolLayer(ARROW_LAYER, SOURCE_ID).withProperties(
                    iconImage(ARROW_IMAGE),
                    iconSize(ARROW_ICON_SCALE),
                    iconRotate(get(PROP_BEARING)),
                    iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    visibility(Property.NONE)
                )
            )
        }
        // Слои пересозданы — вернуть видимость стрелки в соответствие с жизнью.
        lastArrowVisible = null
    }

    /** Новый фикс позиции (UI-поток). Без fix — точку не рисуем. */
    fun update(fix: NavPositionFeed.PositionSnapshot) {
        val lat = fix.latitude ?: return
        val lon = fix.longitude ?: return
        val showArrow = fix.bearingDeg != null &&
            (fix.speedMps ?: 0f) >= HEADING_MIN_SPEED_MPS
        val props = JsonObject().apply {
            addProperty(PROP_ACCURACY, fix.accuracyM ?: 0f)
            addProperty(PROP_BEARING, fix.bearingDeg ?: 0f)
        }
        val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat), props)
        lastFeature = feature
        val coneFC = if (showArrow && fix.bearingDeg != null) {
            FeatureCollection.fromFeatures(
                listOf(Feature.fromGeometry(conePolygon(lat, lon, fix.bearingDeg!!)))
            )
        } else {
            FeatureCollection.fromFeatures(emptyList())
        }
        lastCone = coneFC
        val s = style ?: return
        (s.getSource(SOURCE_ID) as? GeoJsonSource)?.setGeoJson(feature)
        (s.getSource(CONE_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(coneFC)
        setArrowVisible(s, showArrow)
    }

    /** Конус курса: треугольник от позиции вперёд на CONE_LEN_M, ±24°. */
    private fun conePolygon(lat: Double, lon: Double, bearingDeg: Float): Polygon {
        val left = destPoint(lat, lon, CONE_LEN_M, bearingDeg - CONE_HALF_ANGLE_DEG)
        val right = destPoint(lat, lon, CONE_LEN_M, bearingDeg + CONE_HALF_ANGLE_DEG)
        val apex = Point.fromLngLat(lon, lat)
        return Polygon.fromLngLats(
            listOf(listOf(apex, left, right, apex))
        )
    }

    /** Точка на сфере в distM метрах от исходной по курсу brgDeg (WGS-84, сфера). */
    private fun destPoint(lat: Double, lon: Double, distM: Double, brgDeg: Float): Point {
        val d = distM / EARTH_RADIUS_M
        val brg = Math.toRadians(brgDeg.toDouble())
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val lat2 = asin(
            sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(brg)
        )
        val lon2 = lon1 + atan2(
            sin(brg) * sin(d) * cos(lat1),
            cos(d) - sin(lat1) * sin(lat2)
        )
        return Point.fromLngLat(Math.toDegrees(lon2), Math.toDegrees(lat2))
    }

    // ═══════════════════════════════════ пульс-ореол ════

    private val pulseHandler = Handler(Looper.getMainLooper())
    private var pulseRunning = false
    private var pulseStep = 0

    /** Запущен навсегда (до release): дешёвый 130-мс тик поверх постоянной отрисовки. */
    private val pulseTicker = object : Runnable {
        override fun run() {
            if (!pulseRunning) return
            val s = style
            val layer = s?.getLayer(PULSE_LAYER) as? CircleLayer
            if (layer != null) {
                val p = (pulseStep % PULSE_STEPS).toFloat() / PULSE_STEPS
                // проход по ~ease-out кубике (CSS animation-timing-function: ease-out в макете)
                val q = 1f - (1f - p) * (1f - p) * (1f - p)
                val radius = (PULSE_MIN_RADIUS_PX + (PULSE_MAX_RADIUS_PX - PULSE_MIN_RADIUS_PX) * q)
                val alpha = PULSE_MAX_OPACITY * (1f - q)
                runCatching {
                    layer.setProperties(
                        circleRadius(radius * density),
                        circleOpacity(alpha)
                    )
                }
            }
            pulseStep++
            pulseHandler.postDelayed(this, PULSE_FRAME_MS)
        }
    }

    /** Последний режим видимости стрелки (не дёргаем стиль без изменений). */
    private var lastArrowVisible: Boolean? = null

    private fun setArrowVisible(s: Style, visible: Boolean) {
        if (lastArrowVisible == visible) return
        lastArrowVisible = visible
        (s.getLayer(ARROW_LAYER) as? SymbolLayer)
            ?.setProperties(visibility(if (visible) Property.VISIBLE else Property.NONE))
    }

    init {
        pulseRunning = true
        pulseHandler.post(pulseTicker)
    }

    fun release() {
        pulseRunning = false
        pulseHandler.removeCallbacks(pulseTicker)
        style = null
        lastFeature = null
        lastCone = null
        lastArrowVisible = null
    }

    private fun arrowBitmap(): Bitmap {
        val w = (ARROW_SIZE_PX * density).toInt()
        val bmp = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = density
        val cx = w / 2f
        val path = Path().apply {
            moveTo(cx, 4f * p)
            lineTo(w - 12f * p, w - 12f * p)
            lineTo(cx, w - 19f * p)
            lineTo(12f * p, w - 12f * p)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = accent
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * p
            color = Color.WHITE
            strokeJoin = Paint.Join.ROUND
        }
        c.drawPath(path, stroke)
        c.drawPath(path, fill)
        return bmp
    }

    companion object {
        private const val SOURCE_ID = "nav-puck-src"
        private const val CONE_SOURCE_ID = "nav-puck-cone-src"
        private const val ACCURACY_LAYER = "nav-puck-accuracy"
        private const val PULSE_LAYER = "nav-puck-pulse"
        private const val CONE_LAYER = "nav-puck-cone"
        private const val DOT_LAYER = "nav-puck-dot"
        private const val ARROW_LAYER = "nav-puck-arrow"
        private const val ARROW_IMAGE = "nav-puck-arrow-img"
        private const val PROP_ACCURACY = "accuracy"
        private const val PROP_BEARING = "bearing"

        /** Синий концепта этапа 5 («точка» и ореолы из палитры макета). */
        private const val PUCK_BLUE = 0xFF5AA9FF.toInt()
        private const val DOT_RADIUS_PX = 8f
        private const val DOT_STROKE_PX = 3f
        private const val ARROW_SIZE_PX = 48f
        private const val ARROW_ICON_SCALE = 0.75f

        /** Конус курса: длина/раскрытие (world-scale, отточено по концепту). */
        private const val CONE_LEN_M = 90.0
        private const val CONE_HALF_ANGLE_DEG = 24f
        private const val EARTH_RADIUS_M = 6_371_000.0

        /** Пульс-ореол: экранные значения текущего концепта. */
        private const val PULSE_MIN_RADIUS_PX = 12f
        private const val PULSE_MAX_RADIUS_PX = 42f
        private const val PULSE_MAX_OPACITY = 0.30f
        private const val PULSE_STEPS = 20
        private const val PULSE_FRAME_MS = 130L

        /** Правило capy: курс по GNSS валиден со скорости 1,5 км/ч = 0,417 м/с. */
        private const val HEADING_MIN_SPEED_MPS = 0.42f

        private const val TAG = "NavPuck"
    }
}
