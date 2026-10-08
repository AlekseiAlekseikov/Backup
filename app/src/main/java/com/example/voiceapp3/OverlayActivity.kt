package com.example.voiceapp3

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * Окно «Слушаю» — стеклянная плашка сверху.
 * Обычный Activity + Theme.Translucent: AppCompat сюда не подмешиваем,
 * иначе появится непрозрачный фон. Цвета берём из AppTheme.isLight().
 */
class OverlayActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var localBroadcastManager: LocalBroadcastManager
    private lateinit var root: View
    private lateinit var pal: Palette

    /* Stage VO2: финальная фаза «Жду обращения» + карточка ответа */
    private lateinit var heroCard: LinearLayout
    private lateinit var micDisc: View
    private lateinit var micIcon: ImageView
    private lateinit var ring1: View
    private lateinit var ring2: View
    private lateinit var eqRow: View
    private var resultShown = false

    private val running = mutableListOf<android.animation.Animator>()
    private val barAnimators = mutableListOf<ValueAnimator>()

    private val autoCloseHandler = Handler(Looper.getMainLooper())
    private val autoClose = Runnable {
        Log.w(TAG, "Автозакрытие: сервис не прислал команду скрыть")
        finish()
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_OVERLAY_UPDATE -> {
                    /* Stage VO2: финал — «Жду обращения» + карточка ответа */
                    if (intent.hasExtra(EXTRA_PHRASE) || intent.hasExtra(EXTRA_REPLY)) {
                        showResult(
                            intent.getStringExtra(EXTRA_PHRASE),
                            intent.getStringExtra(EXTRA_REPLY).orEmpty()
                        )
                        return
                    }
                    val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                    if (text.isNotBlank() && !resultShown) {
                        statusText.animate().alpha(0.4f).setDuration(80).withEndAction {
                            statusText.text = text
                            statusText.animate().alpha(1f).setDuration(120).start()
                        }.start()
                        restartAutoClose()
                    }
                }
                ACTION_OVERLAY_HIDE -> finish()
            }
        }
    }

    /* Цвета макета GeelyTools: voiceHero (.card2/.card/.ink/.muted/.line),
       светлая тема = «Сепия», тёмная = «Полночь». Акцент микрофона #E86A33. */
    private class Palette(
        val heroBg: Int,   /* --card2: фон карточки */
        val pillBg: Int,   /* --card: фон плашки «Привет, Geely» */
        val ink: Int,      /* --ink: статус */
        val muted: Int,    /* --muted: подпись */
        val line: Int,     /* --line: рамка плашки */
        val btnBg: Int,    /* --btn: нейтральный (не live) микрофон */
        val btnOn: Int     /* --onbtn: иконка на нём */
    )

    companion object {
        private const val TAG = "OverlayActivity"
        const val ACTION_OVERLAY_UPDATE = "com.example.voiceapp3.OVERLAY_UPDATE"
        const val ACTION_OVERLAY_HIDE = "com.example.voiceapp3.OVERLAY_HIDE"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PHRASE = "phrase"       /* Stage VO2: финал */
        const val EXTRA_REPLY = "reply"
        private const val AUTO_CLOSE_MS = 15_000L
        private const val RESULT_CLOSE_MS = 2_400L
        private const val ACCENT = "#E86A33"       /* .micBtn.live из макета */
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pal = if (AppTheme.isLight(this)) lightPalette() else darkPalette()

        // Не вызываем AppTheme.applySaved: смена night mode перекрашивает
        // полосу ГУ в чёрный. Тема Translucent, статусбар прозрачный.
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0f)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        if (AppTheme.isLight(this)) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
                )
        /* Центрирование карточки: окно ВСЕГДА на весь экран (WRAP_CONTENT-окна
           activity ненадёжны на разных версиях/эмуляторах — карточка уезжает
           в угол), а сама карточка центрируется корневым FrameLayout
           (layout_gravity=center). Работает одинаково на ГУ и в эмуляторе. */
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.START
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            y = 0
        }

        root = buildUi()
        setContentView(root)
        playEnter()

        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager.registerReceiver(updateReceiver, IntentFilter().apply {
            addAction(ACTION_OVERLAY_UPDATE)
            addAction(ACTION_OVERLAY_HIDE)
        })

        intent?.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
            statusText.text = prettyStatus(it)
        }

        restartAutoClose()
        Log.i(TAG, "Экран «Слушаю» показан light=${AppTheme.isLight(this)}")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
            statusText.text = prettyStatus(it)
        }
        restartAutoClose()
    }

    override fun onDestroy() {
        super.onDestroy()
        autoCloseHandler.removeCallbacks(autoClose)
        running.forEach { it.cancel() }
        running.clear()
        barAnimators.forEach { it.cancel() }
        barAnimators.clear()
        runCatching { localBroadcastManager.unregisterReceiver(updateReceiver) }
        Log.i(TAG, "Экран «Слушаю» закрыт")
    }

    override fun onBackPressed() {
        finish()
    }

    private fun restartAutoClose(ms: Long = AUTO_CLOSE_MS) {
        autoCloseHandler.removeCallbacks(autoClose)
        autoCloseHandler.postDelayed(autoClose, ms)
    }

    /**
     * Stage VO2: финал как в концепте — микрофон гаснет (не live → --btn),
     * эквалайзер и кольца останавливаются, статус «Жду обращения»,
     * снизу появляется карточка «фраза / ответ» (.voiceResp), оверлей
     * закрывается сам через ~2,4 с.
     */
    private fun showResult(phrase: String?, reply: String) {
        if (isDestroyed || isFinishing) return
        resultShown = true

        running.forEach { it.cancel() }
        running.clear()
        barAnimators.forEach { it.cancel() }
        barAnimators.clear()
        ring1.visibility = View.GONE
        ring2.visibility = View.GONE
        eqRow.visibility = View.GONE
        micDisc.background = ovalFill(String.format("#%08X", pal.btnBg))
        micIcon.setColorFilter(pal.btnOn)

        statusText.text = "Жду обращения"

        /* Карточка ответа (.voiceResp): --card, рамка --line, радиус 14 */
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(pal.pillBg)
                setStroke(dp(1), pal.line)
            }
            alpha = 0f
        }
        phrase?.let {
            card.addView(TextView(this).apply {
                text = "«$it»"
                setTextColor(pal.ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
        }
        card.addView(TextView(this).apply {
            text = reply
            setTextColor(pal.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        })
        heroCard.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL }
        )
        card.animate().alpha(1f).setDuration(150).start()

        restartAutoClose(RESULT_CLOSE_MS)
        Log.i(TAG, "Финал: phrase='$phrase' reply='$reply'")
    }

    private fun prettyStatus(text: String): String {
        val t = text.trim()
        if (t.isEmpty() || t == "..." || t == "...слушаю..." ||
            t.equals("слушаю...", true) || t.equals("привет", true)
        ) {
            return "Слушаю…"
        }
        return t
    }

    private fun lightPalette() = Palette(          /* Сепия */
        heroBg = Color.parseColor("#E9E0CF"),
        pillBg = Color.parseColor("#FBF6EC"),
        ink = Color.parseColor("#231C0F"),
        muted = Color.parseColor("#6E6353"),
        line = Color.parseColor("#D8CCB4"),
        btnBg = Color.parseColor("#3A2F1E"),
        btnOn = Color.parseColor("#FBF6EC")
    )

    private fun darkPalette() = Palette(           /* Полночь */
        heroBg = Color.parseColor("#232323"),
        pillBg = Color.parseColor("#151515"),
        ink = Color.parseColor("#F2F2F2"),
        muted = Color.parseColor("#AFAFAF"),
        line = Color.parseColor("#2C2C2C"),
        btnBg = Color.parseColor("#E8E8E8"),
        btnOn = Color.parseColor("#0A0A0A")
    )


    // ------------------------------------------------------------------
    //  Разметка
    // ------------------------------------------------------------------

    /* Разметка «точь-в-точь» как .voiceHero макета:
       вертикальная карточка — микрофон, статус, плашка «Привет, Geely», эквалайзер. */
    private fun buildUi(): View {
        heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(20), dp(16), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(pal.heroBg)
            }
            elevation = dp(12).toFloat()
        }

        heroCard.addView(buildMic(), LinearLayout.LayoutParams(dp(104), dp(104)))

        statusText = TextView(this).apply {
            text = "Слушаю…"
            setTextColor(pal.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            gravity = Gravity.CENTER
        }
        heroCard.addView(
            statusText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        heroCard.addView(
            buildWakePill(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        eqRow = buildEqualizer()
        heroCard.addView(
            eqRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )

        val wrap = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                heroCard,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
            /* Тык мимо карточки — закрыть (окно теперь на весь экран) */
            setOnTouchListener { _, _ -> finish(); true }
        }
        return wrap
    }

    /* Плашка «Привет, Geely» (.wake из макета) */
    private fun buildWakePill(): View = TextView(this).apply {
        text = "«Привет, Geely»"
        setTextColor(pal.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(100).toFloat()
            setColor(pal.pillBg)
            setStroke(dp(1), pal.line)
        }
    }

    /* Микрофон как в макете: диск 84dp #E86A33, поверх — два расходящихся
       кольца (.micBtn.live::before/::after: scale .85→1.25, 1.6s ease-out,
       второе с задержкой .8s). */
    private fun buildMic(): View {
        val box = FrameLayout(this)

        ring2 = ovalStroke(ACCENT, 2)
        ring1 = ovalStroke(ACCENT, 2)
        box.addView(ring2, frameCenter(dp(96), dp(96)))
        box.addView(ring1, frameCenter(dp(96), dp(96)))
        runCatching { pulseRing(ring2, 0) }
        runCatching { pulseRing(ring1, 800) }

        micDisc = View(this).apply { background = ovalFill(ACCENT) }
        box.addView(micDisc, frameCenter(dp(84), dp(84)))

        micIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        box.addView(micIcon, frameCenter(dp(40), dp(40)))
        return box
    }

    /* Эквалайзер как в макете (.eq i): 5 полос #E86A33, ширина 5dp,
       высота колышется 30%→100% за .8s ease-in-out, задержка шагом .15s.
       Полосы растут снизу (align-items:flex-end → pivotY=bottom). */
    private fun buildEqualizer(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val delays = longArrayOf(0, 150, 300, 450, 600)
        for (i in delays.indices) {
            val bar = View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat()
                    setColor(Color.parseColor(ACCENT))
                }
                scaleY = 0.3f
            }
            val lp = LinearLayout.LayoutParams(dp(5), dp(26)).apply {
                leftMargin = if (i == 0) 0 else dp(4)
                gravity = Gravity.BOTTOM
            }
            row.addView(bar, lp)
            bar.post {
                bar.pivotY = bar.height.toFloat()
                val anim = ValueAnimator.ofFloat(0.3f, 1f).apply {
                    duration = 800
                    startDelay = delays[i]
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { bar.scaleY = it.animatedValue as Float }
                    start()
                }
                barAnimators += anim
            }
        }
        return row
    }

    // ------------------------------------------------------------------
    //  Анимация и фон
    // ------------------------------------------------------------------

    private fun playEnter() {
        /* Как в концепте/видео: оверлей появляется сразу (короткое проявление) */
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(150).start()
    }

    private fun pulseRing(view: View, delay: Long) {
        view.post {
            val set = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(view, View.SCALE_X, 0.85f, 1.25f),
                    ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.85f, 1.25f),
                    ObjectAnimator.ofFloat(view, View.ALPHA, 0.9f, 0f)
                )
                duration = 1600
                startDelay = delay
                interpolator = DecelerateInterpolator()   /* ease-out, как @keyframes ring */
            }
            set.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!isDestroyed) pulseRing(view, 0)
                }
            })
            set.start()
            running += set
        }
    }

    private fun ovalFill(color: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(color))
    }

    private fun ovalStroke(color: String, widthDp: Int) = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(widthDp), Color.parseColor(color))
        }
    }

    private fun frameCenter(w: Int, h: Int) = FrameLayout.LayoutParams(w, h).apply {
        gravity = Gravity.CENTER
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
