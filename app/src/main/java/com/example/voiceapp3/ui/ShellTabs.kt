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
 * Порт прежней JS-логики: MENU, buildTabs, resolveProjectionTabs, arrivedTab,
 * PENDING_DEST, applyProjection и ветка destination в ui.destination.
 * Док (NativeDock) получает список вкладок отсюда; JS вкладки больше не публикует.
 *
 * Выбор вкладки уходит в JS (ui.selectTab), пока содержимое вкладок рисует JS;
 * подтверждение выбора приходит через tab.change → [onSelected].
 *
 * Все вызовы — на UI-потоке.
 */
class ShellTabs(
    /** Тумблер «CarPlay / Android Auto» (TelemetrySettings.projectionBetaEnabled). */
    private val enabled: () -> Boolean,
    /** Публикация видимого списка в нативный док; selected — текущая вкладка. */
    private val publish: (List<NativeDock.Tab>, String) -> Unit,
    /** Выбор вкладки (ui.selectTab → JS select()). */
    private val select: (String) -> Unit,
) {
    companion object {
        /** Полное меню шелла: порядок и подписи прежнего MENU. */
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

    // Присутствие телефона — значения как у прежнего PROJ.
    private var cp = "unknown"
    private var aa = "unknown"
    private var cpAt: Long? = null
    private var aaAt: Long? = null
    private var cpAvail = false
    private var aaAvail = false

    /** Текущая вкладка (по tab.change); до первого события — стартовая «quick». */
    private var selected = "quick"
    /** Флаг: publish был вызван хотя бы раз (шелл/док готовы к select). */
    private var shellEverReady = false

    /** Отложенная цель destination: "carplay" | "androidAuto" (как PENDING_DEST). */
    private var pending: String? = null
    /** Итерация 7 fix: холодный старт — если select приходит раньше готовности шелла,
     * запоминаем цель и применяем при первом refresh (когда док уже подключен). */
    private var pendingSelect: String? = null

    private class Shown(val carplay: Boolean, val aa: Boolean)

    /** Точный порт resolveProjectionTabs (capy, projection_presence_api.dart). */
    private fun resolve(): Shown {
        val en = enabled()
        val c = if (en) cp else "disconnected"
        val a = if (en) aa else "disconnected"
        if (c == "connected" && a == "connected") {
            val x = cpAt
            val y = aaAt
            if (x == null || y == null || x == y) return Shown(carplay = true, aa = true)
            return Shown(carplay = x > y, aa = y > x)
        }
        if (c == "connected") return Shown(carplay = true, aa = false)
        if (a == "connected") return Shown(carplay = false, aa = true)
        // Ни одного CONNECTED: вкладка только при UNKNOWN и доступном сервисе (fallback).
        return Shown(carplay = c == "unknown" && cpAvail, aa = a == "unknown" && aaAvail)
    }

    /** Снимок projection.presence (ключи как в ProjectionPresenceSnapshot.toMap). */
    fun onPresence(json: String) {
        val d = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return
        val prevCp = cp
        val prevAa = aa
        str(d, "carplay")?.let { cp = it }
        str(d, "androidAuto")?.let { aa = it }
        if (d.has("carplayEdgeAt")) cpAt = num(d, "carplayEdgeAt")
        if (d.has("androidAutoEdgeAt")) aaAt = num(d, "androidAutoEdgeAt")
        bool(d, "carplayAvailable")?.let { cpAvail = it }
        bool(d, "androidAutoAvailable")?.let { aaAvail = it }

        // Прибытие телефона — только переход disconnected → connected.
        val arrived = when {
            prevCp == "disconnected" && cp == "connected" -> "carplay"
            prevAa == "disconnected" && aa == "connected" -> "aa"
            else -> null
        }
        refresh()
        if (arrived != null && enabled()) select(arrived)
    }

    /** tab.change из JS: выбранная вкладка. */
    fun onSelected(id: String) {
        selected = id
    }

    /** destination «carplay» / «androidAuto» / «telemetry» / «tools» / «charging» из ui.destination. */
    fun onDestination(destination: String) {
        AppLogLike.i(TAG, "onDestination: requested=\"$destination\"")
        // P-фикс: раньше switch обрабатывал ТОЛЬКО carplay/androidAuto — остальные
        // destination игнорировались (в т.ч. "telemetry" от клика по батарее/температуре).
        // В результате клик по иконке не переключал вкладку — владелец видел
        // последнюю открытую (чаще «Быстродействие» = tools/perf).
        val target = when {
            destination == "carplay" -> "carplay"
            destination == "androidAuto" -> "aa"
            destination == "telemetry" -> "telemetry"
            destination == "tools" || destination == "performance" || destination == "perf" -> "tools"
            destination.contains("charg") -> "telemetry" // charging tab внутри telemetry
            else -> null
        }
        if (target == null) {
            // legacy carplay/aa projection — ждём появления вкладки
            if (destination == "carplay" || destination == "androidAuto") {
                val shown = resolve()
                val id = if (destination == "carplay") "carplay" else "aa"
                val isShown = if (id == "carplay") shown.carplay else shown.aa
                if (isShown) { select(id); selected = id; AppLogLike.i(TAG,"destination: applied $id (carplay/aa ready)") }
                else { pending = destination; AppLogLike.i(TAG,"destination: queued $destination (projection tab not yet shown)") }
            }
            return
        }
        // Холодный старт: select() требует, чтобы док уже был подключен к publish.
        // Если refresh() ещё не вызывался (isFirstRun) — ставим в очередь.
        if (!shellEverReady) {
            pendingSelect = target
            AppLogLike.i(TAG, "destination: queued $target (shell not ready yet)")
            // Для carplay/aa pending тоже заполним:
            if (target == "carplay" || target == "aa") pending = target
            return
        }
        select(target)
        selected = target
        AppLogLike.i(TAG, "destination: applied $target (requested=\"$destination\")")
    }

    /**
     * Пересобрать список вкладок под текущее присутствие и тумблер.
     * Открытая вкладка, которой больше нет, уходит на «quick»; отложенная цель
     * открывается, когда её вкладка появилась (как applyProjection в JS).
     */
    fun refresh() {
        val s = resolve()
        val tabs = MENU.filter { t ->
            when (t.id) {
                "carplay" -> s.carplay
                "aa" -> s.aa
                else -> true
            }
        }
        // При первом refresh применяем отложенный select (холодный старт).
        val firstTime = !shellEverReady
        shellEverReady = true
        publish(tabs, selected)
        if (!s.carplay && selected == "carplay") select("quick")
        if (!s.aa && selected == "aa") select("quick")
        // pendingSelect: применили цель, если док готов.
        val ps = pendingSelect
        if (ps != null) {
            pendingSelect = null
            val tabExists = tabs.any { it.id == ps }
            if (tabExists) {
                select(ps)
                selected = ps
                AppLogLike.i(TAG, "destination: applied $ps (shell ready, firstRefresh=$firstTime)")
            } else {
                AppLogLike.i(TAG, "destination: queued $ps dropped (tab not in MENU)")
            }
        }
        when {
            pending == "carplay" && s.carplay -> {
                pending = null
                select("carplay")
            }
            pending == "androidAuto" && s.aa -> {
                pending = null
                select("aa")
            }
        }
    }

    private fun str(o: JsonObject, key: String): String? =
        runCatching { o.get(key)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()

    private fun num(o: JsonObject, key: String): Long? =
        runCatching { o.get(key)?.takeIf { !it.isJsonNull }?.asLong }.getOrNull()

    private fun bool(o: JsonObject, key: String): Boolean? =
        runCatching { o.get(key)?.takeIf { !it.isJsonNull }?.asBoolean }.getOrNull()
}
