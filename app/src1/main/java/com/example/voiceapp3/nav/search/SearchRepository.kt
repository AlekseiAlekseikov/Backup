package com.example.voiceapp3.nav.search

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Stage NAV-SEARCH (этап 6): оркестратор поиска (слой логики).
 *
 * Контракт с UI (SearchSheetController): подписка [listen] выдаёт Snapshot
 * немедленно (текущее) и дальше при каждом изменении. Источники:
 *  · координатный ввод → мгновенный синтетический результат «Координаты»;
 *  · запрос от 3+ символов → debounce 350 мс → Photon (клиент сам отменяет
 *    in-flight и кэширует); локальные совпадения по истории/избранному
 *    сливаются с сетевыми (два списка раздельно — секции в UI);
 *  · ошибка сети → честный warn-баннер «сеть недоступна…», показаны только
 *    локальные совпадения (никаких заглушек).
 *  · запрос < 3 символов → только локальные совпадения (в сеть не ходим).
 *
 * Позиция для bias и дистанций приходит сюда последним фиксом (хост
 * подсовывает снимок NavPositionFeed — репозиторий GPS сам не трогает).
 */
class SearchRepository(context: Context) {

    data class Snapshot(
        val query: String,
        val searching: Boolean,
        val online: List<SearchResult>,
        val history: List<SearchResult>,
        val favorites: List<SearchResult>,
        /** null = сеть в порядке/не дергали; текст — честный баннер ошибки. */
        val errorText: String?,
        /** Синтетический результат «Координаты» при распознанном вводе lat,lon. */
        val coordinate: SearchResult?
    )

    private val store = SearchHistoryStore(context)
    private val client = PhotonSearchClient(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())

    private val listeners = LinkedHashSet<(Snapshot) -> Unit>()

    private var query = ""
    private var searching = false
    private var online: List<SearchResult> = emptyList()
    private var errorText: String? = null
    private var pending: Runnable? = null

    @Volatile
    var biasLat: Double? = null; private set

    @Volatile
    var biasLon: Double? = null; private set

    init {
        store.listener = { emit() }
    }

    // ═══════════════════════════════════ подписка ════

    fun listen(l: (Snapshot) -> Unit) {
        listeners += l
        l(snapshot())
    }

    fun unlisten(l: (Snapshot) -> Unit) {
        listeners -= l
    }

    // ═══════════════════════════════════ команды от UI ════

    /** Ввод текста (автодополнение): debounce 350 мс, <3 символов — без сети.
     * Ручной набор отменяет категорийный тег (текст больше не тот запрос). */
    fun setQuery(q: String) {
        if (activeCategory != null && activeCategory?.first != q.trim()) {
            activeCategory = null
        }
        query = q
        errorText = null
        pending?.let { main.removeCallbacks(it) }
        val trimmed = q.trim()
        if (SearchResult.parseCoordinateQuery(trimmed) != null) {
            searching = false
            online = emptyList()
            emit()
            return
        }
        if (trimmed.length < ONLINE_MIN_CHARS) {
            searching = false
            online = emptyList()
            emit()
            return
        }
        searching = true
        emit()
        val r = Runnable {
            fireOnline(trimmed, activeCategory?.takeIf { it.first == trimmed }?.second)
        }
        pending = r
        main.postDelayed(r, DEBOUNCE_MS)
    }

    /** Немедленный поиск (кнопка «Поиск» на клавиатуре — debounce обходится). */
    fun submit() {
        val trimmed = query.trim()
        pending?.let { main.removeCallbacks(it) }
        if (trimmed.length >= ONLINE_MIN_CHARS &&
            SearchResult.parseCoordinateQuery(trimmed) == null
        ) {
            searching = true
            emit()
            fireOnline(trimmed, activeCategory?.second)
        } else {
            emit()
        }
    }

    /**
     * Категорийный тап (чипы вроде «⛽ АЗС», макет navigator-app-7):
     * запрос рисуется текстом (пользователь видит, что ищется), а сетевой
     * вызов ужимается честным тегом OSM (osm_tag Photon) —
     * точнее голого текста («АЗС» = amenity:fuel, не «АЗС» в названии).
     */
    var activeCategory: Pair<String, String>? = null; private set

    fun searchWithCategory(label: String, osmTag: String) {
        activeCategory = label to osmTag
        setQuery(label)
    }

    private fun fireOnline(trimmed: String, osmTag: String? = null) {
        client.query(
            trimmed, biasLat, biasLon,
            object : PhotonSearchClient.Callback {
                override fun onResult(results: List<SearchResult>) {
                    searching = false
                    errorText = null
                    // дедуп против локальных (та же точка в ★/истории не дублируется секцией)
                    val localKeys = (store.favorites() + store.history()).map { it.key }.toSet()
                    online = results.filter { it.key !in localKeys }
                    emit()
                }

                override fun onError(message: String) {
                    searching = false
                    online = emptyList()
                    errorText = message
                    emit()
                }
            },
            osmTag = osmTag
        )
    }

    fun refreshLocal() = emit()

    fun addToHistory(item: SearchResult) = store.addToHistory(item)

    fun toggleFavorite(item: SearchResult): Boolean = store.toggleFavorite(item)

    fun isFavorite(key: String): Boolean = store.isFavorite(key)

    fun clearHistory() = store.clearHistory()

    /** Последний фикс, присланный хостом (bias для геокодера + дистанции в UI). */
    fun setPosition(lat: Double?, lon: Double?) {
        biasLat = lat
        biasLon = lon
    }

    fun release() {
        pending?.let { main.removeCallbacks(it) }
        client.release()
        listeners.clear()
    }

    // ═══════════════════════════════════ внутреннее ════

    private fun snapshot(): Snapshot {
        val trimmed = query.trim()
        val local = if (trimmed.isEmpty()) {
            store.history()
        } else {
            store.history().filter { matches(it, trimmed) }
        }
        val favs = if (trimmed.isEmpty()) {
            store.favorites()
        } else {
            store.favorites().filter { matches(it, trimmed) }
        }
        val onlineFiltered = if (trimmed.length >= ONLINE_MIN_CHARS) online else emptyList()
        return Snapshot(
            query = query,
            searching = searching,
            online = onlineFiltered,
            history = local,
            favorites = favs,
            errorText = errorText,
            coordinate = if (trimmed.isEmpty()) null
            else SearchResult.parseCoordinateQuery(trimmed)
        )
    }

    private fun matches(item: SearchResult, q: String): Boolean {
        val needle = q.lowercase()
        return item.name.lowercase().contains(needle) ||
            item.subtitle.lowercase().contains(needle)
    }

    private fun emit() {
        val snap = snapshot()
        listeners.forEach { l ->
            runCatching { l(snap) }.onFailure { Log.w(TAG, "listener failed", it) }
        }
    }

    companion object {
        private const val TAG = "NavSearch"
        private const val DEBOUNCE_MS = 350L
        private const val ONLINE_MIN_CHARS = 3
    }
}
