package com.example.voiceapp3.ui

import com.google.gson.JsonObject
import com.google.gson.JsonParser

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

    /** Отложенная цель destination: "carplay" | "androidAuto" (как PENDING_DEST). */
    private var pending: String? = null

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

    /** destination «carplay» / «androidAuto» из ui.destination. */
    fun onDestination(destination: String) {
        if (destination != "carplay" && destination != "androidAuto") return
        val shown = resolve()
        val id = if (destination == "carplay") "carplay" else "aa"
        val isShown = if (id == "carplay") shown.carplay else shown.aa
        if (isShown) select(id) else pending = destination
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
        publish(tabs, selected)
        if (!s.carplay && selected == "carplay") select("quick")
        if (!s.aa && selected == "aa") select("quick")
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
