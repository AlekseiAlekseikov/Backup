package com.example.voiceapp3.tools

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * Оригинальный механизм заставки («Descanso de Tela») из CentralEXAuto v3.3.9
 * (QuickAccessFloaterService / w0.na / w0.d0 / w0.ye).
 *
 * Принцип:
 * 1. НИКОГДА не трогает Settings.System.SCREEN_OFF_TIMEOUT (иначе SoC Geely уходит в глубокий сон,
 *    вырубая тачскрин на уровне ядра и аудиостек/AVAS).
 * 2. Создает оверлей TYPE_APPLICATION_OVERLAY на весь экран с черным фоном и часами (88sp, цвет #5A5A5A).
 * 3. Приглушает подсветку через sysfs до 12.
 * 4. По тапу на экран мгновенно убирает оверлей и восстанавливает прежнюю яркость.
 */
object ScreenSaverOverlay {
    private const val TAG = "CENTRAL_ScreenSaverOverlay"
    private const val SYSFS_PATH = "/sys/class/leds/lcd-backlight/brightness"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayLayout: LinearLayout? = null
    private var clockView: TextView? = null
    private var savedBrightness: Int = 166
    private var isShowing = false

    private val clockTicker = object : Runnable {
        override fun run() {
            if (!isShowing) return
            try {
                clockView?.text = DateFormat.format("HH:mm", System.currentTimeMillis())
                mainHandler.postDelayed(this, 1000)
            } catch (t: Throwable) {
                Log.w(TAG, "Clock tick error", t)
            }
        }
    }

    @Synchronized
    fun show(context: Context) {
        if (isShowing) return
        val appContext = context.applicationContext
        mainHandler.post {
            try {
                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                if (overlayLayout != null) {
                    try { wm.removeView(overlayLayout) } catch (_: Throwable) {}
                    overlayLayout = null
                }

                savedBrightness = readSysfsBrightness().coerceIn(12, 255)

                val root = LinearLayout(appContext)
                root.orientation = LinearLayout.VERTICAL
                root.setGravity(Gravity.CENTER)
                root.setBackgroundColor(Color.BLACK)
                root.isClickable = true
                root.isFocusable = false
                root.setOnClickListener {
                    dismiss(appContext)
                }

                val tv = TextView(appContext)
                tv.text = DateFormat.format("HH:mm", System.currentTimeMillis())
                tv.textSize = 88f
                tv.setTypeface(tv.typeface, Typeface.BOLD)
                tv.setTextColor(Color.parseColor("#5A5A5A"))
                tv.gravity = Gravity.CENTER

                clockView = tv
                root.addView(tv)
                overlayLayout = root

                // 2038 = TYPE_APPLICATION_OVERLAY, 2003 = TYPE_SYSTEM_ALERT
                val layoutType = if (android.os.Build.VERSION.SDK_INT >= 26) 2038 else 2003

                val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType,
                    flags,
                    android.graphics.PixelFormat.TRANSLUCENT
                )

                wm.addView(root, params)
                isShowing = true

                writeSysfsBrightness(12)
                mainHandler.post(clockTicker)
                Log.i(TAG, "ScreenSaverOverlay displayed (dim to 12)")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to show ScreenSaverOverlay", t)
            }
        }
    }

    @Synchronized
    fun dismiss(context: Context) {
        if (!isShowing && overlayLayout == null) return
        val appContext = context.applicationContext
        mainHandler.post {
            try {
                mainHandler.removeCallbacks(clockTicker)
                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                overlayLayout?.let {
                    try { wm?.removeView(it) } catch (_: Throwable) {}
                }
                overlayLayout = null
                clockView = null
                isShowing = false

                writeSysfsBrightness(savedBrightness)
                Log.i(TAG, "ScreenSaverOverlay dismissed (restored brightness $savedBrightness)")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to dismiss ScreenSaverOverlay", t)
            }
        }
    }

    private fun readSysfsBrightness(): Int {
        return try {
            val s = File(SYSFS_PATH).readText().trim()
            s.toIntOrNull() ?: 166
        } catch (_: Throwable) {
            166
        }
    }

    private fun writeSysfsBrightness(lvl: Int) {
        try {
            File(SYSFS_PATH).writeText(lvl.toString())
        } catch (_: Throwable) {
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "echo $lvl > $SYSFS_PATH"))
            } catch (_: Throwable) {}
        }
    }
}
