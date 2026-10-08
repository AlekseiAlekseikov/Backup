package com.timhss.capyenergy.projection

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Экранная половина CarPlay / Android Auto — порт `ProjectionPresenceBridge`
 * из capy (MainActivity.configureFlutterEngine) на мост UiBridge.
 *
 * В capy эта пара каналов (`com.timhss.capyenergy/projection` + EventChannel
 * `.../projection/presence`) держит вкладки оболочки в курсе того, что
 * сообщают OEM-стеки о телефоне. Здесь тот же монитор [ProjectionPresenceMonitor]
 * и та же логика, только снимок уходит в WebView пушем
 * `projection.presence` вместо EventChannel.
 *
 * Жизненный цикл — как у Flutter-моста:
 *  - монитор стартует при первом включении тумблера, не в конструкторе
 *    («Nothing binds until Dart asks»: ничего не биндится, пока читатель
 *    не попросил);
 *  - выключение тумблера НЕ убивает монитор — в capy Dart просто отменяет
 *    подписку (EventChannel.onCancel → sink=null), и события больше никуда
 *    не идут. Здесь то же самое: [enabled] гейтит публикацию, а UI один раз
 *    получает unknown, чтобы вкладки ушли;
 *  - включение обратно повторяет onListen: пуш текущего снимка + start()
 *    (холодное чтение — телефон, воткнутый пока тумблер был выключен).
 *
 * Сервисная половина (авто-открытие приложения при подключении телефона) —
 * [com.timhss.capyenergy.service.ProjectionAutoOpenSupervisor], у неё свой
 * монитор; половинки пересекаются намеренно и не могут разойтись, потому что
 * читают один класс монитора по одному правилу рёбер.
 */
class ProjectionPresenceBridge private constructor(
    private val appContext: Context,
    private val publishJson: (String) -> Unit,
) {
    private val lock = Any()

    private var monitor: ProjectionPresenceMonitor? = null

    /**
     * Аналог `sink != null` во Flutter-мосте: пока выключен, монитор может
     * работать (как в capy), но снимки не публикуются.
     */
    @Volatile
    private var enabled = false

    /**
     * Включение/выключение тумблера «CarPlay / Android Auto» живьём.
     *
     * Idempotent, как setEnabled супервизора: два вызывающих — настройка при
     * старте и тумблер читателя.
     */
    fun setEnabled(enabled: Boolean) {
        val toStart: ProjectionPresenceMonitor?
        synchronized(lock) {
            if (this.enabled == enabled) return
            this.enabled = enabled
            if (enabled) {
                val existing = monitor
                if (existing == null) {
                    val created = ProjectionPresenceMonitor(appContext) { snapshot ->
                        publish(snapshot)
                    }
                    monitor = created
                    toStart = created
                } else {
                    // Повторный onListen: сначала то, что известно сейчас…
                    publish(existing.snapshot())
                    toStart = existing
                }
            } else {
                toStart = null
            }
        }
        if (enabled) {
            toStart?.start()
        } else {
            // Dart при выключении делает _presence = unknown и bound = false —
            // вкладки уходят немедленно, что бы ни сообщал монитор дальше.
            publish(ProjectionPresenceSnapshot.unknown)
        }
    }

    /**
     * Повторное чтение геттеров: рёбра, ушедшие пока приложение было в фоне,
     * до нас не дошли. Как onActivityResumed во Flutter-мосте.
     */
    fun onActivityResumed() {
        synchronized(lock) { monitor }?.refresh()
    }

    /**
     * Пуш последнего известного снимка — после перезагрузки страницы UI
     * (state WebView сброшен, монитор в Kotlin жив). Как onListen, который
     * всегда начинает с текущего снимка.
     */
    fun publishLatest() {
        val m = synchronized(lock) { monitor }
        publish(m?.snapshot() ?: ProjectionPresenceSnapshot.unknown)
    }

    private fun publish(snapshot: ProjectionPresenceSnapshot) {
        if (!enabled && snapshot !== ProjectionPresenceSnapshot.unknown) {
            // Подписки нет — событие теряется, как `val events = sink ?: return`.
            // unknown публикуется всегда: это способ сказать «вкладок нет».
            return
        }
        runCatching {
            publishJson(JSONObject(snapshot.toMap()).toString())
        }.onFailure { Log.w(TAG, "publish failed", it) }
    }

    companion object {
        private const val TAG = "ProjectionPresence"

        @Volatile
        private var instance: ProjectionPresenceBridge? = null

        fun get(context: Context): ProjectionPresenceBridge? = instance

        fun getOrCreate(
            context: Context,
            publishJson: (String) -> Unit,
        ): ProjectionPresenceBridge {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: ProjectionPresenceBridge(
                    context.applicationContext,
                    publishJson,
                ).also { instance = it }
            }
        }
    }
}
