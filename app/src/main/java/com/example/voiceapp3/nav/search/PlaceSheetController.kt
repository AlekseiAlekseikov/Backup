package com.example.voiceapp3.nav.search

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.voiceapp3.nav.ConceptIcons
import com.example.voiceapp3.nav.NavTheme
import java.util.Locale

/**
 * Stage NAV-DESIGN-7 (navigator-app-7.html), состояние `s-place`:
 * карточка места над доком. Подключается к реальным данным/действиям:
 *  · «Расстояние» — по прямой до последнего GPS-фикса (честно подписано
 *    «по прямой»: времени в пути пока нет — роутер это этап 7);
 *  · «Поехали →» — ставит/подтверждает пин назначения и закрывает карточку
 *    (запуск ведения появится с маршрутом на этапе 7 — кнопка уже на месте);
 *  · «Заехать по пути» из макета НЕ рисуем — «по пути» без активного
 *    маршрута смысла не имеет (демо не копируем);
 *  · «★/☆ В избранное» — тот же контур SearchHistoryStore; состояние
 *    кнопки отражает реальное;
 *  · «Показать на карте» — камера к точке, карточка закрывается;
 *  · «Убрать точку» — только когда эта точка является текущим назначением
 *    (заменяет удалённую одноимённую кнопку старой карточки этапа 4).
 *
 * Долгий тап по карте открывает ТУ ЖЕ карточку с именем «Метка на карте».
 */
class PlaceSheetController(
    private val activity: Activity,
    private val density: Float,
    private val theme: NavTheme
) {

    data class Place(
        val name: String,
        val subtitle: String,
        val emoji: String,
        val lat: Double,
        val lon: Double,
        val distanceText: String?,
        val favorite: Boolean,
        val isDestination: Boolean,
        val historyItem: SearchResult? = null
    )

    /** Корневой view панели (LP хоста: MATCH_PARENT ширина, над доком). */
    val view: LinearLayout

    var onDrive: ((Place) -> Unit)? = null
    var onShowOnMap: ((Place) -> Unit)? = null
    var onRemovePin: (() -> Unit)? = null
    var onToggleFavorite: ((Place) -> Boolean)? = null   // возвращает новое состояние ★

    /** Колбэк видимости для хоста (здесь без скрима — по макету). */
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    private var shown = false
    private var slideAnimating = false
    private var place: Place? = null

    private var headerName: TextView? = null
    private var headerSub: TextView? = null
    private var headerEmoji: TextView? = null
    private var distValue: TextView? = null
    private var coordValue: TextView? = null
    private var favBtn: TextView? = null
    private var removeBtn: TextView? = null

    init {
        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(theme.sheetTop, theme.sheetBot)
            ).apply {
                cornerRadius = dpf(26f)
                setStroke(dp(1), theme.borderHairline)
            }
            visibility = View.GONE
            isClickable = true
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, dpf(26f))
                }
            }
        }
        view.addView(grip())
        view.addView(buildHeader())
        view.addView(buildKvRow())
        view.addView(buildActions())
    }

    // ═══════════════════════════════════ видимость ════

    fun isShown(): Boolean = shown

    fun show(p: Place) {
        place = p
        bind(p)
        if (slideAnimating || shown) return
        slideAnimating = true
        shown = true
        onVisibilityChanged?.invoke(true)
        val h = sheetHeightPx().toFloat()
        view.visibility = View.VISIBLE
        view.translationY = h * OPEN_FROM_FRACTION
        view.animate().cancel()
        view.animate()
            .translationY(0f)
            .setDuration(OPEN_CLOSE_MS)
            .setInterpolator(SHEET_EASE)
            .withEndAction { slideAnimating = false }
            .start()
    }

    fun hide() {
        if (!shown) return
        shown = false
        slideAnimating = false
        view.animate().cancel()
        onVisibilityChanged?.invoke(false)
        val h = sheetHeightPx().toFloat()
        view.animate()
            .translationY(h * OPEN_FROM_FRACTION)
            .setDuration(OPEN_CLOSE_MS)
            .setInterpolator(SHEET_EASE)
            .withEndAction {
                view.visibility = View.GONE
                view.translationY = 0f
            }
            .start()
    }

    fun release() = hide()

    private fun sheetHeightPx(): Int {
        val h = view.layoutParams?.height ?: 0
        return if (h > 0 && h != ViewGroup.LayoutParams.WRAP_CONTENT) h
        else (activity.resources.displayMetrics.heightPixels * 0.32f).toInt()
    }

    // ═══════════════════════════════════ разметка ════

    private fun grip(): View = View(activity).apply {
        background = GradientDrawable().apply {
            setColor(theme.gripColor); cornerRadius = dpf(2.5f)
        }
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    private fun buildHeader(): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(14), dp(14), 0)

            headerEmoji = TextView(activity).apply {
                textSize = 23f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(theme.chipBg)
                    cornerRadius = dpf(15f)
                    setStroke(dp(1), theme.borderHairline)
                }
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                    marginEnd = dp(16)
                }
            }
            addView(headerEmoji)

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                headerName = TextView(activity).apply {
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = (-0.01f)
                    setTextColor(theme.ink)
                    setSingleLine(false)
                }
                addView(headerName)
                headerSub = TextView(activity).apply {
                    textSize = 13f
                    setTextColor(theme.textSecondary)
                    setPadding(0, dp(4), 0, 0)
                }
                addView(headerSub)
            })

            addView(ImageView(activity).apply {
                setImageBitmap(ConceptIcons.fabIcon(
                    activity, ConceptIcons.Kind.CLOSE, theme.textSecondary, boxDp = 40f
                ))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = GradientDrawable().apply {
                    setColor(theme.chipBg)
                    cornerRadius = dpf(13f)
                    setStroke(dp(1), theme.borderHairline)
                }
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                contentDescription = "Закрыть"
                attachPressFeedback(this)
                setOnClickListener { hide() }
            })
        }

    private fun buildKvRow(): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(22), dp(14), dp(22), 0)
            addView(kvChip("Расстояние", 0).also {
                layoutParams = LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(kvChip("Координаты", 1).apply {
                layoutParams = LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) }
            })
        }

    private fun kvChip(label: String, which: Int): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(theme.chipBg)
                cornerRadius = dpf(14f)
                setStroke(dp(1), theme.borderHairline)
            }
            addView(TextView(activity).apply {
                text = label
                textSize = 12f
                setTextColor(theme.textSecondary)
            })
            val valueView = TextView(activity).apply {
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(theme.ink)
                setPadding(0, dp(3), 0, 0)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            if (which == 0) distValue = valueView else coordValue = valueView
            addView(valueView)
        }

    private fun buildActions(): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(16), dp(22), 0)

            addView(TextView(activity).apply {
                text = "Поехали →"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(theme.onbtn)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(13), dp(20), dp(13))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(theme.btn, theme.btnShadowed)
                ).apply { cornerRadius = dpf(16f) }
                attachPressFeedback(this)
                setOnClickListener { place?.let { onDrive?.invoke(it) } }
            })

            favBtn = TextView(activity).apply {
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(theme.ink)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(13), dp(16), dp(13))
                background = GradientDrawable().apply {
                    setColor(theme.chipBg)
                    cornerRadius = dpf(16f)
                    setStroke(dp(1), theme.borderHairline)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(10) }
                attachPressFeedback(this)
                setOnClickListener {
                    val p = place ?: return@setOnClickListener
                    val nowFav = onToggleFavorite?.invoke(p) ?: p.favorite
                    val cur = place ?: return@setOnClickListener
                    place = cur.copy(favorite = nowFav)
                    bindFav(place!!)
                }
            }
            addView(favBtn)

            addView(TextView(activity).apply {
                text = "Показать на карте"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(theme.ink)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(13), dp(16), dp(13))
                background = GradientDrawable().apply {
                    setColor(theme.chipBg)
                    cornerRadius = dpf(16f)
                    setStroke(dp(1), theme.borderHairline)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(10) }
                attachPressFeedback(this)
                setOnClickListener { place?.let { onShowOnMap?.invoke(it) } }
            })

            removeBtn = TextView(activity).apply {
                text = "Убрать точку"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(DANGER)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(13), dp(16), dp(13))
                background = GradientDrawable().apply {
                    setColor(DANGER_SOFT)
                    cornerRadius = dpf(16f)
                    setStroke(dp(1), DANGER_EDGE)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(10) }
                attachPressFeedback(this)
                setOnClickListener { onRemovePin?.invoke() }
            }
            addView(removeBtn)
        }

    // ═══════════════════════════════════ данные ════

    private fun bind(p: Place) {
        headerName?.text = p.name
        headerSub?.text = p.subtitle
        headerEmoji?.text = p.emoji
        distValue?.text = (p.distanceText ?: "—") +
            if (p.distanceText != null) "  · по прямой" else ""
        coordValue?.text = String.format(Locale.US, "%.5f, %.5f", p.lat, p.lon)
        bindFav(p)
        removeBtn?.visibility = if (p.isDestination) View.VISIBLE else View.GONE
    }

    private fun bindFav(p: Place) {
        favBtn?.text = if (p.favorite) "★ В избранном" else "☆ В избранное"
        favBtn?.setTextColor(if (p.favorite) theme.accent1 else theme.ink)
    }

    private fun attachPressFeedback(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f)
                    .setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate()
                    .scaleX(1f).scaleY(1f).setDuration(180).start()
            }
            false
        }
    }

    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpf(v: Float): Float = v * density

    companion object {
        private const val OPEN_CLOSE_MS = 420L
        private const val OPEN_FROM_FRACTION = 1.2f
        private val SHEET_EASE = PathInterpolator(0.2f, 0.9f, 0.25f, 1f)

        private val DANGER = 0xFFFF6B6F.toInt()
        private val DANGER_SOFT = 0x24E5484D.toInt()
        private val DANGER_EDGE = 0x4DE5484D.toInt()
    }
}
