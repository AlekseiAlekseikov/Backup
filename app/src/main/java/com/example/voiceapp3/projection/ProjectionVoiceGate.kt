package com.example.voiceapp3.projection

import android.content.Context
import android.util.Log
import com.timhss.capyenergy.projection.PresenceState
import com.timhss.capyenergy.projection.ProjectionPresenceMonitor
import com.timhss.capyenergy.projection.ProjectionPresenceSnapshot

/**
 * Stage CPv2, фикс «музыка CarPlay сразу ставится на паузу».
 *
 * Причина бага была не в projection-коде (presence подсистема capy только
 * читает), а в голосовой подсистеме: wake-word слушает микрофон всегда
 * (PREF_WAKE_WORD по умолчанию true), музыка из CarPlay попадает в микрофон,
 * компактная Vosk-модель ложно срабатывает на ней, и greetThenListen()
 * запрашивает AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE — OEM CarPlay получает
 * AUDIOFOCUS_LOSS и мгновенно ставит музыку на паузу.
 *
 * Гейт держит собственный ProjectionPresenceMonitor (как ProjectionAutoOpen-
 * Supervisor в capy — «each consumer owns its monitor»; только read-only
 * getter и broadcasts, никаких подписок в OEM-сервисах) и отвечает на один
 * вопрос:
 * идёт ли сейчас projection-сессия (CarPlay ИЛИ Android Auto).
 *
 * Пока она идёт, wake-word не стартует сессию: микрофон всё равно слушает
 * музыку телефона, ложные срабатывания гарантированы, а Exclusive-фокус
 * гарантированно глушит воспроизведение. Голос по-прежнему доступен по
 * кнопке/рулю (startRecognition по intent) — это осознанный пользовательский
 * сценарий, как Siri у OEM.
 */
class ProjectionVoiceGate(
    context: Context,
    private val onChanged: (connected: Boolean) -> Unit,
) {
    private val appContext = context.applicationContext

    @Volatile
    private var connected: Boolean = false

    private var monitor: ProjectionPresenceMonitor? = null

    fun isProjectionConnected(): Boolean = connected

    fun start() {
        if (monitor != null) return
        val created = ProjectionPresenceMonitor(appContext) { snapshot ->
            applySnapshot(snapshot)
        }
        monitor = created
        created.start()
        Log.i(TAG, "started (CarPlay/AA connected=$connected)")
    }

    fun stop() {
        monitor?.dispose()
        monitor = null
        connected = false
    }

    private fun applySnapshot(snapshot: ProjectionPresenceSnapshot) {
        val next = snapshot.carplay == PresenceState.CONNECTED ||
            snapshot.androidAuto == PresenceState.CONNECTED
        if (next == connected) return
        connected = next
        Log.i(TAG, "projection session ${if (next) "started" else "ended"} — wake word ${if (next) "suppressed" else "restored"}")
        onChanged(next)
    }

    companion object {
        private const val TAG = "ProjectionVoiceGate"
    }
}
