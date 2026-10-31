package com.example.voiceapp3

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.voiceapp3.tools.AppLog
import com.example.voiceapp3.tools.BgWorker
import com.example.voiceapp3.tools.ScreenBrightness
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CENTRAL_BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {

            val app = context.applicationContext
            AppLog.i(TAG, "Boot broadcast received: $action → starting service + screen_timeout_boot_diag + ensureKeepAwake")
            val serviceIntent = Intent(app, VoiceAssistantService::class.java)
                .putExtra(VoiceAssistantService.EXTRA_BOOT_RESTORE, true)
            app.startForegroundService(serviceIntent)

            // iter16: ПЕРВЫМ ДЕЛОМ на фоне замеряем текущий screen_off_timeout
            // (ДО любых наших записей) — чтобы понять, сбрасывает ли его прошивка.
            BgWorker.post("BootReceiver.screenTimeoutDiag") { screenTimeoutBootDiag(app) }

            // Итерация 11/12: keep-awake применяем при каждом boot-событии
            // (чтобы после перезагрузки screen_off_timeout восстановился до
            // ~24дн без необходимости открывать UI).
            BgWorker.post("BootReceiver.ensureKeepAwake") { ScreenBrightness.ensureKeepAwake(app) }
        }
    }

    /** Диагностика "кто сбрасывает screen_off_timeout" — iter16.
     *  Пишем ДО наших записей: факт-значение, uptime, settings list, dumpsys power,
     *  содержимое settings_system.xml. */
    private fun screenTimeoutBootDiag(ctx: Context) {
        val script = """
            echo "=== SCREEN_TIMEOUT_BOOT: uptime=$(cat /proc/uptime) bootcount=$(getprop ro.boot.bootcount 2>/dev/null) ==="
            echo "-- settings get system screen_off_timeout --"
            settings get system screen_off_timeout 2>&1 || echo settings_err
            echo "-- settings list system | grep -i timeout --"
            settings list system 2>/dev/null | grep -i -E "timeout|screen_off" | head -30
            echo "-- dumpsys power (timeout/wakefulness) --"
            dumpsys power 2>/dev/null | grep -i -E "timeout|wakefulness|screenoff" | head -40
            echo "-- settings_system.xml screen_off_timeout --"
            grep -oE 'screen_off_timeout[^<]*' /data/system/users/0/settings_system.xml 2>/dev/null | head -5 || echo "settings_system.xml not readable"
        """.trimIndent()
        try {
            val pp = Runtime.getRuntime().exec(arrayOf("sh","-c",script))
            val out = (pp.inputStream.bufferedReader().readText() + pp.errorStream.bufferedReader().readText())
            pp.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
            AppLog.i(TAG, "SCREEN_TIMEOUT_BOOT_DIAG:\n$out")
        } catch (t: Throwable) {
            AppLog.w(TAG, "SCREEN_TIMEOUT_BOOT_DIAG failed: ${t.message}")
        }
    }
}
