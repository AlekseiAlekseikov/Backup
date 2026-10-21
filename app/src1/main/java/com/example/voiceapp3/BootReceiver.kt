package com.example.voiceapp3

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.i(TAG, "Boot completed, starting service")
            val serviceIntent = Intent(context, VoiceAssistantService::class.java)
                /* Stage CXT-2: сервис должен вернуть сохранённую яркость
                   («Запоминать яркость по фарам») — порт boot-ветки w(). */
                .putExtra(VoiceAssistantService.EXTRA_BOOT_RESTORE, true)
            context.startForegroundService(serviceIntent)

            /* Звук замка после перезагрузки: ключи ГУ пишутся снова и сверяются
               (LockSound.reapplyAfterBoot), источник события поднимается сразу.
               Прежняя реализация объявляла onBootOrWatchdog, но никто его не
               вызывал — сохранение после перезагрузки фактически не работало. */
            runCatching {
                com.example.voiceapp3.tools.LockSound.reapplyAfterBoot(context.applicationContext)
                com.example.voiceapp3.tools.LockStateTrigger.start(context.applicationContext)
            }.onFailure { Log.w(TAG, "lock sound reapply failed", it) }
        }
    }
}