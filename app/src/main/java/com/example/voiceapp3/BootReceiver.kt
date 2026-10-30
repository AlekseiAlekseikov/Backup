package com.example.voiceapp3

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.voiceapp3.tools.AppLog
import com.example.voiceapp3.tools.ScreenBrightness

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CENTRAL_BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {

            AppLog.i(TAG, "Boot broadcast received: $action → starting service + ensureKeepAwake")
            val serviceIntent = Intent(context, VoiceAssistantService::class.java)
                .putExtra(VoiceAssistantService.EXTRA_BOOT_RESTORE, true)
            context.startForegroundService(serviceIntent)
            // Проводим keep-awake (применение SCREEN_OFF_TIMEOUT) ПОСЛЕ ребута.
            ScreenBrightness.ensureKeepAwake(context)
        }
    }
}