package com.example.voiceapp3.tools

import android.app.PendingIntent
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * P-фикс (раздел 0 из prompt_log_evidence.md): индикаторы батареи/температуры/Wi-Fi
 * НЕ МОГУТ быть читаемыми в smallIcon статус-бара (система принудительно масштабирует
 * smallIcon до 24×24dp и обесцвечивает его). CentralEXAuto решает это через
 * TYPE_APPLICATION_OVERLAY поверх статус-бара.
 *
 * Это аналог QuickAccessFloaterService / WifiFloaterService из CentralEXAuto:
 * полупрозрачная горизонтальная полоса в правом верхнем углу (Gravity.TOP or END),
 * содержащая три сегмента:
 *   • батарея (глиф + "42.4%"), клик → Телеметрия
 *   • температура (глиф термометра + "+7°C"), клик → Телеметрия
 *   • Wi-Fi (глиф дуг + уровень), клик → WIFI_SETTINGS
 * Сегменты прокликабельны по отдельности (каждому свой PendingIntent).
 *
 * Нотификации при этом МОЖНО ОСТАВИТЬ (футпринт в шторке и приоритет сервиса).
 * smallIcon в статус-баре системой масштабируется до 24dp и обесцвечивается —
 * он НЕЧИТАЕМ для тонких значений (используем как мелкий аварийный индикатор
 * «функция жива», не как основной рендер). Основная отрисовка — overlay.
 * ВАЖНО: системная шторка уведомлений лежит слоем ВЫШЕ TYPE_APPLICATION_OVERLAY
 * (так же, как у CentralEXAuto) — при открытой шторке пилюля временно скрывается
 * под ней, это ожидаемое поведение, не баг.
 *
 * Разрешение SYSTEM_ALERT_WINDOW уже есть в манифесте; если appop слетел —
 * OverlayPermission.ensure() сам выдаёт его через AppOpsManager.MODE_ALLOWED
 * (мы platform-signed/priv-app на этом ГУ).
 */
object StatusOverlay {
    private const val TAG = "CENTRAL_StatusOverlay"

    // Координаты и размеры (dp):
    // Итерация 12: увеличены по запросу (были 26/6/12/16 — едва заметны на 1280×720).
    private const val OVERLAY_HEIGHT_DP = 36
    private const val OVERLAY_MARGIN_END_DP = 8
    private const val OVERLAY_MARGIN_TOP_DP = 4
    private const val SEGMENT_PADDING_DP = 8
    private const val TEXT_SIZE_SP = 15f
    private const val ICON_SIZE_DP = 22

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var showing = false
    private var root: LinearLayout? = null
    private var battView: SegmentView? = null
    private var tempView: SegmentView? = null
    private var wifiView: SegmentView? = null
    private var appContext: Context? = null

    /** Сегмент = иконка (глиф) + текст + PendingIntent по тапу. */
    private class SegmentView(ctx: Context) : LinearLayout(ctx) {
        var action: PendingIntent? = null
        val icon = android.widget.ImageView(ctx)
        val text = TextView(ctx)
        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pxPad = dp(ctx, SEGMENT_PADDING_DP)
            setPadding(pxPad, 0, pxPad, 0)
            val isz = dp(ctx, ICON_SIZE_DP)
            icon.layoutParams = LayoutParams(isz, isz)
            addView(icon)
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            lp.marginStart = dp(ctx, 3)
            text.layoutParams = lp
            text.setTextColor(Color.WHITE)
            text.textSize = TEXT_SIZE_SP
            text.setTypeface(Typeface.DEFAULT_BOLD)
            text.setShadowLayer(2f, 0f, 1f, Color.BLACK)
            addView(text)
            isClickable = true
            isFocusable = false
            setOnClickListener { v ->
                try { action?.send() } catch (t: Throwable) { Log.w(TAG, "click send error: ${t.message}") }
            }
            // Тач-источник не потребляется — если попали мимо сегмента, пропускаем.
            isHapticFeedbackEnabled = false
        }
    }

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(c: Context, v: Float) = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    @JvmStatic fun show(ctx: Context) {
        val app = ctx.applicationContext
        appContext = app
        mainHandler.post { showOnMain(app) }
    }

    @JvmStatic fun hide(ctx: Context?) {
        mainHandler.post { hideOnMain() }
    }

    /** Обновить содержимое сегментов. Все параметры nullable (null = сегмент скрыть). */
    @JvmStatic fun update(
        ctx: Context,
        showBatt: Boolean, battText: String?, battIcon: Icon?, battAction: PendingIntent?,
        showTemp: Boolean, tempText: String?, tempIcon: Icon?, tempAction: PendingIntent?,
        showWifi: Boolean, wifiText: String?, wifiIcon: Icon?, wifiAction: PendingIntent?
    ) {
        val app = ctx.applicationContext
        mainHandler.post {
            if (!showing) { showOnMain(app) }
            val r = root ?: return@post
            battView?.visibility = if (showBatt && battText != null) View.VISIBLE else View.GONE
            battView?.let { v ->
                v.text.text = battText
                if (battIcon != null) v.icon.setImageIcon(battIcon)
                v.action = battAction
            }
            tempView?.visibility = if (showTemp && tempText != null) View.VISIBLE else View.GONE
            tempView?.let { v ->
                v.text.text = tempText
                if (tempIcon != null) v.icon.setImageIcon(tempIcon)
                v.action = tempAction
            }
            wifiView?.visibility = if (showWifi && wifiText != null) View.VISIBLE else View.GONE
            wifiView?.let { v ->
                v.text.text = wifiText
                if (wifiIcon != null) v.icon.setImageIcon(wifiIcon)
                v.action = wifiAction
            }
            AppLog.i(TAG, "OVERLAY update batt=$battText temp=$tempText wifi=$wifiText (visible: B=$showBatt T=$showTemp W=$showWifi)")
        }
    }

    private fun statusBarHeightPx(app: Context): Int {
        val resId = app.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) app.resources.getDimensionPixelSize(resId) else dp(app, 24)
    }

    private fun showOnMain(app: Context) {
        if (showing) return
        var path = "fail"
        try {
            // Итерация 11 (ФАКТ 1): гейт canDrawOverlays снят. uid=1000 system_app
            // (sharedUserId=android.uid.system, priv-app, platform-signed) МОЖЕТ
            // добавлять TYPE_APPLICATION_OVERLAY без проверки Settings.canDrawOverlays
            // — ровно так работает CentralEXAuto (TempBattPillFloaterService
            // addView без единой проверки). canDraw логируем только для диагностики.
            val canDraw = runCatching {
                val cls = Class.forName("com.example.voiceapp3.OverlayPermission")
                val m = cls.getMethod("canDraw", Context::class.java)
                (m.invoke(null, app) as? Boolean) ?: false
            }.getOrDefault(false)
            AppLog.i(TAG, "OVERLAY: canDrawOverlays=$canDraw (uid=${android.os.Process.myUid()}); calling addView unconditionally as CEX does")

            val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm == null) { AppLog.e(TAG, "OVERLAY: wm=null"); return@showOnMain }
            val root = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0x66000000.toInt()) // полупрозрачная подложка
            }
            val padV = dp(app, 2)
            root.setPadding(0, padV, 0, padV)

            battView = SegmentView(app)
            tempView = SegmentView(app)
            wifiView = SegmentView(app)
            root.addView(wifiView)
            root.addView(tempView)
            root.addView(battView)

            // Единая декомпиляция: CentralEXAuto v3.3.9
            // com/geely/autoservice/floater/TempBattPillFloaterService.java:
            //   WindowManager.LayoutParams(-2, size, 2038, 264, -3)
            //     2038 = TYPE_APPLICATION_OVERLAY,
            //     264  = 0x108 = FLAG_LAYOUT_IN_SCREEN (0x100) | FLAG_NOT_FOCUSABLE (0x8),
            //     -3   = PixelFormat.TRANSLUCENT.
            // РИСК ДЛЯ ПРОВЕРКИ НА ГУ: без FLAG_NOT_TOUCH_MODAL (0x20) на некоторых
            // прошивках окно может проглатывать тапы в пределах своего окна даже вне
            // своих дочерних View; проверить на ГУ: тап по статус-бару вплотную к
            // пилюле (часы/иконки) должен попадать в систему, а не в нас. Если тапы
            // будут съедены — добавить FLAG_NOT_TOUCH_MODAL (0x20 → flags=296).
            val layoutType = if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY // 2038
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT // 2003
            val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE // = 264 (CEX decompile)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                dp(app, OVERLAY_HEIGHT_DP),
                layoutType,
                flags,
                PixelFormat.TRANSLUCENT // -3
            )
            params.gravity = Gravity.TOP or Gravity.END
            params.x = dp(app, OVERLAY_MARGIN_END_DP)
            // Смещаем ПОД статус-бар, чтобы не перекрывать его и не глотать его тапы.
            // В CentralEXAuto pill живёт с y=49dp — это как раз под статус-баром.
            val sb = statusBarHeightPx(app)
            params.y = sb + dp(app, OVERLAY_MARGIN_TOP_DP)

            wm.addView(root, params)
            this.root = root
            showing = true
            path = "window"
            AppLog.i(TAG, "StatusOverlay shown type=$layoutType flags=${params.flags} fmt=${params.format} path=$path sb=${sb}px y=${params.y}px gravity=${params.gravity} x=${params.x}")
        } catch (t: Throwable) {
            AppLog.e(TAG, "OVERLAY: addView failed path=$path err=${t.javaClass.simpleName}: ${t.message}", t)
        }
        // Итерация 9 fix: убрали запуск OverlayActivity из фоллбека — он был no-op
        // (активити «Слушаю» тут же делала finish()). Реальный фоллбек при отказе
        // overlay-permission — нотификации с ЧИТАЕМЫМИ цифрами в smallIcon
        // (StatusIcons.postNotif выставляет их в makeTextIcon с цифрами).
        if (path != "window") {
            Log.w(TAG, "OVERLAY: window-add failed (path=$path); индикаторы остаются в шторке уведомлений (smallIcon=число)")
        }
        AppLog.i(app, TAG, "OVERLAY: path=$path")
    }

    private fun hideOnMain() {
        if (!showing) return
        try {
            val app = appContext
            val wm = app?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            root?.let { v -> try { wm?.removeView(v) } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
        root = null; battView = null; tempView = null; wifiView = null; showing = false
        Log.i(TAG, "StatusOverlay hidden")
    }
}
