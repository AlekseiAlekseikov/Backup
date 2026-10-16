package com.example.voiceapp3.nav.search

import android.content.Context
import org.json.JSONArray

/**
 * Stage NAV-SEARCH (этап 6): история поиска и избранное — SharedPreferences
 * с JSON (выбор пользователя-заказчика: объёмы — десятки записей, вторую
 * БД не плодим). Записи атомарно перезаписываются целиком — повреждённый
 * файл восстанавливается пустым списком (ничего не скрываем: warn в лог).
 *
 * Лимиты: история до 30 (MRU впереди), избранное до 50. Избранное живёт
 * отдельно от истории: добавленная в ★ запись из истории не выметается.
 */
class SearchHistoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var listener: (() -> Unit)? = null

    fun history(): List<SearchResult> = load(KEY_HISTORY)

    fun favorites(): List<SearchResult> = load(KEY_FAVORITES)

    fun isFavorite(key: String): Boolean = favorites().any { it.key == key }

    /** Записать обращение к результату: MRU в голове, дубль по key поднят, ёмкость 30. */
    fun addToHistory(item: SearchResult) {
        val list = history().toMutableList()
        list.removeAll { it.key == item.key }
        list.add(0, item)
        if (list.size > HISTORY_LIMIT) list.subList(HISTORY_LIMIT, list.size).clear()
        save(KEY_HISTORY, list)
    }

    /** Переключить ★: возврат — новое состояние (true = в избранном). */
    fun toggleFavorite(item: SearchResult): Boolean {
        val list = favorites().toMutableList()
        val existing = list.indexOfFirst { it.key == item.key }
        val added: Boolean
        if (existing >= 0) {
            list.removeAt(existing)
            added = false
        } else {
            list.add(0, item)
            if (list.size > FAVORITES_LIMIT) list.subList(FAVORITES_LIMIT, list.size).clear()
            added = true
        }
        save(KEY_FAVORITES, list)
        return added
    }

    fun removeFavorite(key: String) {
        val list = favorites().toMutableList()
        if (list.removeAll { it.key == key }) save(KEY_FAVORITES, list)
    }

    fun clearHistory() = save(KEY_HISTORY, emptyList())

    // ═══════════════════════════════════ плоская запись ════

    private fun load(key: String): List<SearchResult> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { SearchResult.fromJson(it) }
            }
        }.getOrElse {
            android.util.Log.w(TAG, "prefs blob broken ($key) — reset", it)
            emptyList()
        }
    }

    private fun save(key: String, list: List<SearchResult>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(key, arr.toString()).apply()
        listener?.invoke()
    }

    companion object {
        private const val TAG = "NavSearchStore"
        private const val PREFS = "nav_search"
        private const val KEY_HISTORY = "history"
        private const val KEY_FAVORITES = "favorites"
        private const val HISTORY_LIMIT = 30
        private const val FAVORITES_LIMIT = 50
    }
}
