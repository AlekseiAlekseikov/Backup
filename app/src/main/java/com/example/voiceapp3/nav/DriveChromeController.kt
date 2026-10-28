package com.example.voiceapp3.nav

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.voiceapp3.nav.search.SearchResult
import java.util.Locale

/**
 * navigator-app-7.html, состояние `s-drive` — ХРОМ ВЕДЕНИЯ, выбранный
 * пользователем вариант «honest-chrome»: разметка макета, но только
 * честные данные (маршрутизатор — этап 7, маршрута нет):
 *  · панель манёвра (top-left): расстояние ДО ПИНА ПО ПРЯМОЙ и стрелка
 *    по пеленгу (поворачивается к точке относительно курса GPS);
 *    подпись-признак «нет маршрута — прямое направление»;
 *  · левая колонка: скорость по GPS (км/ч); «лимит» — «—» (нет источника,
 *    тестовых значений не рисуем);
 *  · чип поездки: время с момента входа в состояние (MM:SS).
 * Все цвета — из NavTheme. Геометрии по макету: top-left grid 86dp,
 * скорость — левая колонка снизу у дока.
 */
class DriveChromeController(
    private val activity: Activity,
    private val density: Float,
    private val theme: NavTheme
) {

    /** Корневой контейнер (FULLSCREEN, прозрачный; клики — только панели). */
    val view: FrameLayout = FrameLayout(activity).apply {
        isClickable = false
        visibility = View.GONE
    }

    /** Колбэк видимости для бара состояний/скрима хоста. */
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    private var shown = false
    private var slideAnimating = false

    private var arrowIcon: ImageView? = null
    private var mainLine: TextView? = null
    private var subLine: TextView? = null
    private var speedValue: TextView? = null
    private var tripValue: TextView? = null

    private var tripStartedAt = 0L
    private var destinationSet = false
    private var lastSnap: NavPositionFeed.PositionSnapshot? = null
    private var destLat = 0.0
    private var destLon = 0.0

    /** Этап 7: состояние активного маршрута от RouteSessionController
     *  (null → честный режим «прямая к метке», как раньше). */
    data class NavInfo(
        val arrowDeg: Float,
        val maneuverText: String,
        val distToManeuverM: Double?,
        val remainM: Double,
        val remainMs: Long
    )
    private var navInfo: NavInfo? = null

    fun setNavInfo(info: NavInfo?) {
        navInfo = info
        renderSnap()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!shown) return
            renderTrip()
            clock.postDelayed(this, 1000)
        }
    }
    private val clock = Handler(Looper.getMainLooper())

    init {
        view.addView(buildManeuverPanel())
        view.addView(buildSpeedPanel())
    }

    // ═══════════════════════════════════ видимость ════

    fun isShown(): Boolean = shown

    fun show() {
        if (slideAnimating || shown) return
        slideAnimating = true
        shown = true
        tripStartedAt = System.currentTimeMillis()
        onVisibilityChanged?.invoke(true)
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.animate().cancel()
        view.animate().alpha(1f)
            .setDuration(OPEN_CLOSE_MS).setInterpolator(SHEET_EASE)
            .withEndAction { slideAnimating = false }
            .start()
        renderSnap()
        clock.post(ticker)
    }

    fun hide() {
        if (!shown) return
        shown = false
        slideAnimating = false
        clock.removeCallbacks(ticker)
        view.animate().cancel()
        onVisibilityChanged?.invoke(false)
        view.animate().alpha(0f)
            .setDuration(OPEN_CLOSE_MS).setInterpolator(SHEET_EASE)
            .withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
            }
            .start()
    }

    fun release() = hide()

    // ═══════════════════════════════════ панели ════

    private fun pillBg(radius: Float) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(theme.panelTop, theme.panelBot)
    ).apply {
        cornerRadius = radius * density
        setStroke(dp(1), theme.borderHairline)
    }

    private fun buildManeuverPanel(): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(16), dp(12))
            background = pillBg(18f)
            // макет .maneuver: слева под topbar
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                leftMargin = dp(14); topMargin = dp(64)
            }

            // стрелка-пеленг (вращается к точке назначения)
            arrowIcon = ImageView(activity).apply {
                setImageBitmap(ConceptIcons.fabIcon(
                    activity, ConceptIcons.Kind.ARROW_UP, theme.onbtn, boxDp = 44f
                ))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(theme.btn, theme.btnShadowed)
                ).apply { cornerRadius = dpf(14f) }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                    .apply { marginEnd = dp(12) }
            }
            addView(arrowIcon)

            // строки: «До метки · N км» + «прямое направление · маршрут — этап 7»
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                mainLine = TextView(activity).apply {
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(theme.ink)
                    setSingleLine(true)
                }
                addView(mainLine)
                subLine = TextView(activity).apply {
                    textSize = 12f
                    setTextColor(theme.textSecondary)
                    setSingleLine(true)
                    setPadding(0, dp(2), 0, 0)
                }
                addView(subLine)
            })
        }

    private fun buildSpeedPanel(): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = pillBg(18f)
            // макет .leftcol: у ЛЕВОГО края снизу над доком
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                leftMargin = dp(14); bottomMargin = dockPad()
            }

            // скорость (GPS)
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                speedValue = TextView(activity).apply {
                    textSize = 30f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(theme.ink)
                }
                addView(speedValue)
                addView(TextView(activity).apply {
                    text = " км/ч"
                    textSize = 12f
                    setTextColor(theme.textSecondary)
                    setPadding(0, 0, 0, dp(5))
                })
            })
            // честный лимит: источника maxspeed пока нет — ни одна цифра тут
            // не должна притворяться знаком ограничения
            addView(TextView(activity).apply {
                text = "лимит · —"
                textSize = 11f
                setTextColor(theme.textSecondary)
                setPadding(0, dp(1), 0, dp(6))
            })
            // чип поездки (реальное время в состоянии ведения)
            addView(TextView(activity).apply {
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(theme.ink)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = GradientDrawable().apply {
                    setColor(theme.chipBg)
                    cornerRadius = dpf(10f)
                    setStroke(dp(1), theme.borderHairline)
                }
                tripValue = this
            })
        }

    private fun dockPad(): Int = dp(96)

    // ═══════════════════════════════════ данные ════

    /** Новый фикс GPS + смена точки назначения (null — пин снят). */
    fun onFix(snap: NavPositionFeed.PositionSnapshot) {
        lastSnap = snap
        renderSnap()
    }

    fun setDestination(lat: Double, lon: Double) {
        destLat = lat; destLon = lon; destinationSet = true
        renderSnap()
    }

    fun clearDestination() {
        destinationSet = false
        renderSnap()
    }

    private fun renderSnap() {
        val snap = lastSnap
        // скорость: только по реальному фиксу
        speedValue?.text = when {
            snap == null || !snap.hasFix || snap.speedMps == null -> "—"
            else -> String.format(Locale.US, "%d", (snap.speedMps * 3.6f).toInt())
        }
        // панель манёвра: этап 7 — если есть активный маршрут (GraphHopper
        // on-device), показываем РЕАЛЬНЫЕ данные: следующий манёвр,
        // остаток по маршруту, масштабированное время без трафика.
        val nav = navInfo
        if (nav != null) {
            val distTxt = nav.distToManeuverM?.let {
                "через ${SearchResult.formatDistance(it)} · "
            } ?: ""
            mainLine?.text = "$distTxt${nav.maneuverText}"
            val mins = (nav.remainMs / 60_000L).coerceAtLeast(0)
            subLine?.text = "осталось ${SearchResult.formatDistance(nav.remainM)} · " +
                "~$mins мин · без учёта трафика"
            arrowIcon?.rotation = nav.arrowDeg   // стрелка манёвра абсолютна
            return
        }
        // панель манёвра: честная прямая дистанция + пеленг до пина
        if (!destinationSet) {
            mainLine?.text = "Назначение не выбрано"
            subLine?.text = "поставьте точку: долгий тап по карте или поиск"
            arrowIcon?.rotation = 0f
        } else if (snap == null || !snap.hasFix ||
            snap.latitude == null || snap.longitude == null
        ) {
            mainLine?.text = "До метки · —"
            subLine?.text = "жду GPS-фикс для прямой дистанции"
            arrowIcon?.rotation = 0f
        } else {
            val dist = SearchResult.distanceMeters(
                snap.latitude, snap.longitude, destLat, destLon
            )
            mainLine?.text = "До метки · ${SearchResult.formatDistance(dist)}"
            subLine?.text = "прямое направление · маршрут появится на этапе 7"
            arrowIcon?.rotation = bearingTo(snap.latitude, snap.longitude, destLat, destLon) -
                (snap.bearingDeg ?: 0f)
        }
    }

    private fun renderTrip() {
        val sec = ((System.currentTimeMillis() - tripStartedAt) / 1000L).coerceAtLeast(0)
        tripValue?.text = String.format(Locale.US, "⏱ %d м %02d с", sec / 60, sec % 60)
    }

    /** Аппаратный пеленг (0..360, от севера по часовой) между двумя точками. */
    private fun bearingTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val r1 = Math.toRadians(lat1); val r2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = kotlin.math.sin(dLon) * kotlin.math.cos(r2)
        val x = kotlin.math.cos(r1) * kotlin.math.sin(r2) -
                kotlin.math.sin(r1) * kotlin.math.cos(r2) * kotlin.math.cos(dLon)
        return ((Math.toDegrees(kotlin.math.atan2(y, x).toDouble()) + 360.0) % 360.0).toFloat()
    }

    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpf(v: Float): Float = v * density

    companion object {
        private const val TAG = "NavDriveChrome"
        private const val OPEN_CLOSE_MS = 380L
        private val SHEET_EASE = PathInterpolator(0.2f, 0.9f, 0.25f, 1f)
    }
}
