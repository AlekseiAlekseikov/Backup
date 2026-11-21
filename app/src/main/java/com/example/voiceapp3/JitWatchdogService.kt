package com.example.voiceapp3

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.example.voiceapp3.tools.AppLog
import com.example.voiceapp3.tools.JitMech
import com.example.voiceapp3.tools.JitMechAndroid

/**
 * iter65 — аналог `com.ex.auto.CentralWatchdogService` референса (только JIT-часть `onCreate`).
 *
 * После `setprop ctl.restart zygote` процесс приложения погибает; система снова рассылает
 * `BOOT_COMPLETED`, [BootReceiver] и `VoiceAssistantService` стартуют этот сервис, и его
 * `onCreate` делает ровно то, что референс:
 *  1. читает `jit_restaurar`, формат `wifi=0|1` (поле `,ap=` старого формата игнорируется);
 *  2. удаляет ключ (one-shot); неверный формат — ключ не трогается;
 *  3. через 15 с, если сервис ещё жив (`T2` референса), в фоне: `wifi=1` и Wi-Fi выключен ⇒
 *     `setWifiEnabled(true)`; лог `JIT: restaurado após o restart (wifi=X); wifi agora=Y`.
 * Точка доступа не трогается (решение владельца). Других обязанностей у сервиса нет: когда
 * возвращать нечего или возврат уже выполнен, он останавливается сам.
 */
class JitWatchdogService : Service() {

    companion object {
        private const val TAG = "CENTRAL_JitWatchdog"

        /** Сервис жив (`CentralWatchdogService.T2`). */
        @Volatile
        var alive: Boolean = false
            private set

        /**
         * Запуск из `BootReceiver` / `VoiceAssistantService.onCreate`. Если система запретит
         * старт фонового сервиса — та же логика выполняется прямо здесь (`path=inline`), чтобы
         * возврат Wi-Fi не потерялся.
         */
        fun start(context: Context, source: String) {
            val app = context.applicationContext
            val started = runCatching { app.startService(Intent(app, JitWatchdogService::class.java)) }
            if (started.isSuccess && started.getOrNull() != null) return
            AppLog.w(TAG, "JIT_WATCHDOG path=inline source=$source " +
                "reason=${started.exceptionOrNull()?.javaClass?.simpleName ?: "start_refused"}")
            runCatching { JitMechAndroid.watchdogStart(app) { true } }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val stopSelfTask = Runnable { stopSelf() }

    override fun onCreate() {
        super.onCreate()
        alive = true
        val pending = runCatching { JitMechAndroid.watchdogStart(applicationContext) { alive } }
            .onFailure { AppLog.w(TAG, "JIT_WATCHDOG exception=${it.javaClass.simpleName}") }
            .getOrNull()
        // Возвращать нечего — сервис не нужен. Иначе живём до возврата (+5 с запаса).
        if (pending == null) main.post(stopSelfTask)
        else main.postDelayed(stopSelfTask, JitMech.RESTORE_DELAY_MS + 5_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        alive = false
        main.removeCallbacks(stopSelfTask)
        super.onDestroy()
    }
}
