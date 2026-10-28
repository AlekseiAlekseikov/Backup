package com.example.voiceapp3

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import com.timhss.capyenergy.bridge.TelemetryBridge
import com.timhss.capyenergy.service.TelemetryCollectorService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

/**
 * Flutter-приложение телеметрии (точный порт capy) внутри GeelyTools.
 *
 * Порт capy MainActivity (com.timhss.capyenergy.MainActivity). GeelyTools
 * deviations (зафиксированы в отчёте миграции):
 *  - регистрируется только [TelemetryBridge]; мосты CarplayBridge /
 *    AndroidAutoBridge / ProjectionPresenceBridge / ProjectionTouchBridge не
 *    регистрируются — CarPlay/Android Auto остаётся модулем GeelyTools
 *    (WebView-шелл + ProjectionRenderBridge), поэтому проекционные вкладки
 *    Flutter-шелла не появляются (presence-канал молчит);
 *  - перед стартом движка Dart настройки общего меню GeelyTools (тема,
 *    «Полный экран», «Полоса климата») зеркалируются в FlutterSharedPreferences
 *    ([TelemetryPrefsMirror]) — источник истины остаётся за GeelyTools.
 */
class TelemetryActivity : FlutterActivity() {
    private var telemetryBridge: TelemetryBridge? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        TelemetryPrefsMirror.mirror(this)
        applyThemeWindowBackground()
        super.onCreate(savedInstanceState)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        TelemetryCollectorService.start(applicationContext)
        val bridge = TelemetryBridge(this, flutterEngine)
        telemetryBridge = bridge
        consumeDestination(intent, bridge)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeDestination(intent, telemetryBridge)
    }

    /**
     * Reads the destination a launcher outside the app asked for, and removes
     * it from the intent. The activity keeps its launch intent, so an extra
     * left in place would re-open that screen on every configuration change.
     */
    private fun consumeDestination(intent: Intent?, bridge: TelemetryBridge?) {
        val destination = intent?.getStringExtra(TelemetryBridge.EXTRA_DESTINATION) ?: return
        intent.removeExtra(TelemetryBridge.EXTRA_DESTINATION)
        bridge?.onDestinationRequested(destination)
    }

    override fun onResume() {
        super.onResume()
        applyThemeWindowBackground()
    }

    private fun applyThemeWindowBackground() {
        try {
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val colorHex = prefs.getString("flutter.launch_bg_color", null)
            if (colorHex != null) {
                val color = Color.parseColor(colorHex)
                window.setBackgroundDrawable(ColorDrawable(color))
                window.decorView.setBackgroundColor(color)
            }
        } catch (_: Exception) {
            // Ignored when prefs are unavailable or parsing fails
        }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        telemetryBridge?.dispose()
        telemetryBridge = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
