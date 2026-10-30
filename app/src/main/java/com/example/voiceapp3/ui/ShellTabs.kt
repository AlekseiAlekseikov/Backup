package com.example.voiceapp3.ui

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser

// Объект-обёртка, чтобы не тянуть tools.AppLog в ui-модуль (оставляет логи через Logcat).
private object AppLogLike {
    fun i(tag: String, msg: String) = Log.i(tag, msg)
}
private const val TAG = "CENTRAL_ShellTabs"

/**
 * Вкладки шелла и правило CarPlay / Android Auto — нативно (этап 9b).
 *
 * Док (NativeDock) — обычный Android LinearLayout, строится синхронно в onCreate.
 * После refresh() вкладки уже добавлены в иерархию и кликабельны; отдельного
 * события «готовности от JS» больше нет (весь шелл нативный, JS в этом процессе не
 * участвует). Поэтому pendingSelect-очередь не нужна — onDestination вызывает
 * select(target) сразу.
 *
 * Асинхронно могут прийти только события presence (carplay/aa connected) — для
 * них остаётся очередь `pending` (legacy PENDING_DEST).
 */
class ShellTabs(
    private val enabled: () -> Boolean,
    private val publish: (List<NativeDock.Tab>, String) -> Unit,
    private val select: (String) -> Unit,
) {
    companion object {
        val MENU: List<NativeDock.Tab> = listOf(
            NativeDock.Tab("quick", "⌂", "Быстрый доступ"),
            NativeDock.Tab("voice", "🎙", "Голос"),
            NativeDock.Tab("carplay", "🍎", "CarPlay"),
            NativeDock.Tab("aa", "🤖", "Android Auto"),
            NativeDock.Tab("apps", "▦", "Приложения"),
            NativeDock.Tab("telemetry", "📊", "Телеметрия"),
            NativeDock.Tab("navi", "🧭", "Навигация"),
            NativeDock.Tab("tools", "🧰", "Инструменты"),
            NativeDock.Tab("dashcam", "📹", "Видеорегистратор"),
            NativeDock.Tab("service", "⚙", "Сервис"),
            NativeDock.Tab("updates", "↓", "Обновления"),
        )
    }

    private var cp = "unknown"
    private var aa = "unknown"
    private var cpAt: Long? = null
    private var aaAt: Long? = null
    private var cpAvail = false
    private var aaAvail = false
    private var selected = "quick"

    /** Отложенная цель carplay/aa, которая ждёт появления вклада. */
    private var pending: String? = null

    private class Shown(val carplay: Boolean, val aa: Boolean)

    private fun resolve(): Shown {
        val en = enabled()
        val c = if (en) cp else "disconnected"
        val a = if (en) aa else "disconnected"
        if (c == "connected" && a == "connected") {
            val x = cpAt; val y = aaAt
            if (x == null || y == null || x == y) return Shown(carplay = true, aa = true)
            return Shown(carplay = x > y, aa = y > x)
        }
        if (c == "connected") return Shown(carplay = true, aa = false)
        if (a == "connected") return Shown(carplay = false, aa = true)
        return Shown(carplay = c == "unknown" && cpAvail, aa = a == "unknown" && aaAvail)
    }

    fun onPresence(json: String) {
        val d = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return
        val prevCp = cp; val prevAa = aa
        str(d, "carplay")?.let { cp = it }
        str(d, "androidAuto")?.let { aa = it }
        if (d.has("carplayEdgeAt")) cpAt = num(d, "carplayEdgeAt")
        if (d.has("androidAutoEdgeAt")) aaAt = num(d, "androidAutoEdgeAt")
        bool(d, "carplayAvailable")?.let { cpAvail = it }
        bool(d, "androidAutoAvailable")?.let { aaAvail = it }
        val arrived = when {
            prevCp == "disconnected" && cp == "connected" -> "carplay"
            prevAa == "disconnected" && aa == "connected" -> "aa"
            else -> null
        }
        refresh()
        if (arrived != null && enabled()) select(arrived)
    }

    /** Выбор вкладки от пользовательского тапа по доке / из кода. */
    fun onSelected(id: String) {
        selected = id
    }

    /** destination «carplay»/«androidAuto»/«telemetry»/«tools»/«charging». */
    fun onDestination(destination: String) {
        AppLogLike.i(TAG, "onDestination: requested=\"$destination\"")
        val target = when {
            destination == "carplay" -> "carplay"
            destination == "androidAuto" -> "aa"
            destination == "telemetry" -> "telemetry"
            destination == "tools" || destination == "performance" || destination == "perf" -> "tools"
            destination.contains("charg") -> "telemetry"
            else -> null
        }
        if (target == null) {
            if (destination == "carplay" || destination == "androidAuto") {
                val shown = resolve()
                val id = if (destination == "carplay") "carplay" else "aa"
                val isShown = if (id == "carplay") shown.carplay else shown.aa
                if (isShown) { select(id); selected = id; AppLogLike.i(TAG,"destination: applied $id (carplay/aa ready)") }
                else { pending = destination; AppLogLike.i(TAG,"destination: queued $destination (projection tab not yet shown)") }
            }
            return
        }
        // Шелл нативный — после refresh() док уже собран, select безопасен сразу.
        // Итерация 9 fix: убрана очередь pendingSelect и shellEverReady — они
        // оставались от эпохи JS-шелла и давали ложный лог «queued».
        select(target)
        selected = target
        AppLogLike.i(TAG, "destination: applied $target (requested=\"$destination\", shell=native, sync)")
    }

    fun refresh() {
        val s = resolve()
        val tabs = MENU.filter { t ->
            when (t.id) {
                "carplay" -> s.carplay
                "aa" -> s.aa
                else -> true
            }
        }
        publish(tabs, selected)
        if (!s.carplay && selected == "carplay") select("quick")
        if (!s.aa && selected == "aa") select("quick")
        when {
            pending == "carplay" && s.carplay -> { pending = null; select("carplay") }
            pending == "androidAuto" && s.aa -> { pending = null; select("aa") }
        }
    }

    private fun str(o: JsonObject, key: String): String? =
        runCatching { o.get(key)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()
    private fun num(o: JsonObject, key: String): Long? =
        runCatching { o.get(key)?.takeIf { !it.isJsonNull }?.asLong }.getOrNull()
    private fun bool(o: JsonObject, key: String): Boolean? =
        runCatching { o.get(key)?.takeIf { !it.isJsonNull }?.asBoolean }.getOrNull()
}
