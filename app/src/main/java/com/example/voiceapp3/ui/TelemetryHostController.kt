package com.example.voiceapp3.ui

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.example.voiceapp3.TelemetryPrefsMirror
import com.timhss.capyenergy.bridge.TelemetryBridge
import com.timhss.capyenergy.service.TelemetryCollectorService
import io.flutter.embedding.android.FlutterTextureView
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor

/**
 * GeelyTools deviation: Flutter-приложение телеметрии встроено ПРЯМО во
 * вкладку «Телеметрия» WebView-шелла (вместо отдельной полноэкранной
 * TelemetryActivity, как было на первом этапе миграции).
 *
 * Зачем: нижний плавающий док (.tabbar в index.html) живёт внутри WebView;
 * отдельная активность перекрывала его целиком. Здесь FlutterView кладётся
 * поверх WebView с нижним отступом ровно до верхней кромки дока — док остаётся
 * видимым и кликабельным, переключение вкладок работает как обычно.
 *
 * Рендер — FlutterTextureView (не Surface): обычный z-порядок View, ничего не
 * «пробивает» окно (тот же приём, что у projection-оверлеев TextureView).
 *
 * Жизненный цикл:
 *  - show(): зеркалирование настроек GeelyTools (тема/полный экран/климат-бар)
 *    → ленивое создание движка → attach + appIsResumed. Если тема GeelyTools
 *    сменилась с прошлого показа — движок пересоздаётся, Flutter стартует
 *    уже в новой теме (ThemeController читает prefs при старте).
 *  - hide(): view GONE + appIsPaused; движок остаётся тёплым — повторное
 *    открытие вкладки мгновенное.
 *  - dispose(): полный демонтаж (onDestroy активности).
 *
 * Высота дока приходит из JS (telemetry.frame → setDockInsetPx): tabbar
 * меряется getBoundingClientRect × devicePixelRatio — тот же паттерн, что
 * projectionFrame. До первого замера действует запасной отступ.
 */
class TelemetryHostController(private val activity: Activity) {

    private var engine: FlutterEngine? = null
    private var bridge: TelemetryBridge? = null
    private var flutterView: FlutterView? = null
    private var attachedToEngine = false

    /** Палитра (AppThemeId capy), с которой стартовал текущий движок. */
    private var engineThemeId: String? = null

    private var dockInsetPx: Int =
        (DEFAULT_DOCK_INSET_DP * activity.resources.displayMetrics.density).toInt()

    /** Вырез, с которым построен текущий FlutterView (для rebuild-порога). */
    private var viewInsetPx: Int = -1

    var isVisible: Boolean = false
        private set

    /** Нижний отступ (физ. пиксели) под плавающий док; меряет JS шелла. */
    fun setDockInsetPx(px: Int) {
        if (px <= 0 || px > activity.resources.displayMetrics.heightPixels / 2) return
        dockInsetPx = px
        if (flutterView == null) return
        // Вырез задаёт и отступ, и компенсацию плотности (см. createView):
        // заметное изменение требует пересоздать view с новым масштабом.
        if (kotlin.math.abs(px - viewInsetPx) > INSET_EPSILON_PX) rebuildView()
    }

    /** Показать встроенную телеметрию (UI-поток). */
    fun show(destination: String? = null) {
        runCatching {
            val theme = TelemetryPrefsMirror.currentThemeId(activity)
            if (engine != null && theme != engineThemeId) {
                // Палитра GeelyTools сменилась: Flutter читает prefs при
                // старте движка — пересоздаём, чтобы совпала с шеллом.
                destroyEngine()
            }
            TelemetryPrefsMirror.mirror(activity)
            val eng = engine ?: createEngine(theme)
            val view = flutterView ?: createView()
            if (!attachedToEngine) {
                view.attachToFlutterEngine(eng)
                attachedToEngine = true
            }
            view.visibility = View.VISIBLE
            view.bringToFront()
            eng.lifecycleChannel.appIsResumed()
            isVisible = true
            if (!destination.isNullOrBlank()) {
                bridge?.onDestinationRequested(destination)
            }
        }.onFailure { Log.e(TAG, "show failed", it) }
    }

    /** Спрятать (переход на другую вкладку); движок остаётся тёплым. */
    fun hide() {
        if (!isVisible && flutterView?.visibility != View.VISIBLE) return
        flutterView?.visibility = View.GONE
        engine?.lifecycleChannel?.appIsPaused()
        isVisible = false
    }

    fun onActivityResumed() {
        if (isVisible) engine?.lifecycleChannel?.appIsResumed()
    }

    fun onActivityPaused() {
        if (isVisible) engine?.lifecycleChannel?.appIsPaused()
    }

    /** Полный демонтаж — только из MainActivity.onDestroy. */
    fun dispose() {
        runCatching {
            flutterView?.let { view ->
                if (attachedToEngine) view.detachFromFlutterEngine()
                (view.parent as? ViewGroup)?.removeView(view)
            }
            attachedToEngine = false
            flutterView = null
            destroyEngine()
            isVisible = false
        }.onFailure { Log.w(TAG, "dispose failed", it) }
    }

    // ────────────────────────────────────────────────────────────────────────

    private fun createEngine(theme: String): FlutterEngine {
        // Как TelemetryActivity.configureFlutterEngine: сервис-коллектор +
        // TelemetryBridge (единственный мост; projection-мосты — модуль GT).
        TelemetryCollectorService.start(activity.applicationContext)
        val eng = FlutterEngine(activity.applicationContext)
        eng.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint.createDefault())
        bridge = TelemetryBridge(activity, eng)
        engine = eng
        engineThemeId = theme
        return eng
    }

    private fun destroyEngine() {
        flutterView?.let { view ->
            if (attachedToEngine) view.detachFromFlutterEngine()
        }
        attachedToEngine = false
        runCatching { bridge?.dispose() }
        bridge = null
        runCatching { engine?.destroy() }
        engine = null
        engineThemeId = null
    }

    private fun createView(): FlutterView {
        // Capy свёрстан под ПОЛНУЮ высоту экрана: нижний ряд v2 — «четверть
        // высоты», а EfficiencyCard требует ≥200 лог.px и ниже НЕ
        // переформатируется, а переполняется (см. комментарий minHeight в
        // capy_ui/efficiency_card.dart). Вырез под док отнимает ~92 px — без
        // компенсации нижние карточки наезжают текстом друг на друга.
        // Поэтому Flutter рендерится через контекст с пропорционально
        // уменьшенной плотностью: физическая высота меньше, но логическая
        // остаётся как у полного экрана — композиция Capy сохраняется 1:1.
        val dm = activity.resources.displayMetrics
        val screenH = dm.heightPixels
        val scale = (screenH - dockInsetPx).toFloat() / screenH
        val config = android.content.res.Configuration(activity.resources.configuration)
        config.densityDpi = (dm.densityDpi * scale).toInt().coerceAtLeast(72)
        // Язык: шелл GeelyTools — русский всегда, независимо от системной
        // локали ГУ. У Capy нет собственной настройки языка: MaterialApp
        // берёт локали, которые FlutterView шлёт из конфигурации СВОЕГО
        // контекста при attach (sendLocalesToFlutter). Задаём ru здесь —
        // телеметрия локализуется как шелл (у Capy есть полный ru-перевод).
        config.setLocale(java.util.Locale("ru"))
        val ctx = activity.createConfigurationContext(config)
        val view = FlutterView(ctx, FlutterTextureView(ctx))
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { bottomMargin = dockInsetPx }
        root.addView(view, lp)
        flutterView = view
        viewInsetPx = dockInsetPx
        return view
    }

    /**
     * Пересоздать FlutterView под новый вырез/масштаб, не трогая движок
     * (состояние Dart и открытый экран сохраняются).
     */
    private fun rebuildView() {
        val wasVisible = flutterView?.visibility == View.VISIBLE
        flutterView?.let { v ->
            if (attachedToEngine) v.detachFromFlutterEngine()
            (v.parent as? ViewGroup)?.removeView(v)
        }
        attachedToEngine = false
        flutterView = null
        val view = createView()
        engine?.let { eng ->
            view.attachToFlutterEngine(eng)
            attachedToEngine = true
        }
        view.visibility = if (wasVisible) View.VISIBLE else View.GONE
        if (wasVisible) view.bringToFront()
    }

    companion object {
        private const val TAG = "TelemetryHost"

        /** Запасной отступ под док до первого замера из JS (14+~64+14 CSS px). */
        private const val DEFAULT_DOCK_INSET_DP = 92

        /** Дребезг замера дока, не требующий перестройки view. */
        private const val INSET_EPSILON_PX = 4
    }
}
