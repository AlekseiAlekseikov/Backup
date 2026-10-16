package com.example.voiceapp3.nav.search

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Stage NAV-SEARCH (этап 6): онлайн-геокодер Photon.
 *
 * Почему Photon (план §18.5, решение D1/D2): REST GeoJSON-ответ прямо
 * парсится org.json без новых зависимостей; публичный инстанс komoot'а
 * свободен в таких объёмах; единственная точка конфигурации —
 * [SearchEndpoints.SEARCH_URL], при появлении VPS переставляется одной
 * строкой без переделки остального контура.
 *
 * Дисциплина обращения (не злоупотреблять публичным сервером):
 *  · debounce/отмена ведётся в SearchRepository — здесь каждый вызов уже
 *    «финальный»; in-flight запрос прерывается disconnect'ом;
 *  · LRU-кэш ответов (150 запросов × 10 минут) — повторный набор текста из
 *    кэша не ходит в сеть вообще;
 *  · честный User-Agent приложения (server-side рекомендация Photon);
 *  · таймауты 10с/12с; ошибки НЕ прячем — в Log.w и в колбэк onError
 *    (UI показывает честный статус «сеть недоступна…»).
 *
 * Вызовы query() — с любого потока; колбэк приезжает на главный.
 * Потоковая модель — один фоновый пул, гонок нет: generation-токены.
 */
class PhotonSearchClient(private val appContext: Context) {

    interface Callback {
        fun onResult(results: List<SearchResult>)
        fun onError(message: String)
    }

    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "nav-search-net").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var activeConn: HttpURLConnection? = null

    @Volatile
    private var generation = 0

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, Pair<Long, List<SearchResult>>>(
        CACHE_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<Long, List<SearchResult>>>?
        ): Boolean = size > CACHE_SIZE
    }

    /**
     * Запрос подсказок. Отменяет предыдущий in-flight (disconnect + смена
     * поколения — поздние ответы отбрасываются).
     */
    fun query(
        q: String,
        biasLat: Double?,
        biasLon: Double?,
        cb: Callback,
        osmTag: String? = null
    ) {
        val gen = synchronized(this) {
            generation++
            activeConn?.let { c -> executor.execute { runCatching { c.disconnect() } } }
            generation
        }
        val normQuery = q.trim().lowercase(Locale.ROOT)
        val cacheKey = normQuery + "|" + (osmTag ?: "-") + "|" +
            (biasLat?.let { formatBias(it) } ?: "-") + ":" +
            (biasLon?.let { formatBias(it) } ?: "-")
        synchronized(cacheLock) {
            cache[cacheKey]?.let { (ts, results) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) {
                    Log.d(TAG, "cache hit: «$normQuery» (${results.size})")
                    main.post { cb.onResult(results) }
                    return
                }
            }
        }
        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                val sb = StringBuilder(SearchEndpoints.SEARCH_URL)
                    .append("?q=").append(URLEncoder.encode(q.trim(), "UTF-8"))
                    .append("&limit=").append(RESULT_LIMIT)
                if (biasLat != null && biasLon != null) {
                    sb.append("&lat=").append(String.format(Locale.US, "%.5f", biasLat))
                        .append("&lon=").append(String.format(Locale.US, "%.5f", biasLon))
                        .append("&location_bias_scale=").append(BIAS_SCALE)
                }
                osmTag?.let { sb.append("&osm_tag=").append(URLEncoder.encode(it, "UTF-8")) }
                conn = (URL(sb.toString()).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", userAgent())
                    setRequestProperty("Accept", "application/json")
                }
                activeConn = conn
                val code = conn.responseCode
                if (code != 200) {
                    deliverError(gen, "HTTP $code от геокодера", cb)
                    return@execute
                }
                val body = BufferedReader(
                    InputStreamReader(conn.inputStream, Charsets.UTF_8)
                ).use { it.readText() }
                val results = parse(body)
                synchronized(cacheLock) {
                    cache[cacheKey] = System.currentTimeMillis() to results
                }
                deliver(gen) { cb.onResult(results) }
            } catch (e: java.net.SocketTimeoutException) {
                deliverError(gen, "геокодер не ответил (таймаут)", cb)
            } catch (e: java.net.UnknownHostException) {
                deliverError(gen, "нет сети: DNS недоступен", cb)
            } catch (e: java.io.IOException) {
                // disconnect отменённого поколения тоже падает сюда — там молчим
                if (gen == generation) {
                    deliverError(gen, "сети нет: ${e.message ?: "ошибка ввода-вывода"}", cb)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "query crashed", e)
                deliverError(gen, "разбор ответа: ${e.message ?: e.javaClass.simpleName}", cb)
            } finally {
                conn?.let { runCatching { it.disconnect() } }
                if (activeConn === conn) activeConn = null
            }
        }
    }

    private fun deliver(gen: Int, block: () -> Unit) {
        if (gen != generation) return   // устаревшее поколение — тихо уйти
        main.post { if (gen == generation) block() }
    }

    private fun deliverError(gen: Int, message: String, cb: Callback) {
        Log.w(TAG, "search error: $message")
        deliver(gen) { cb.onError(message) }
    }

    private fun parse(body: String): List<SearchResult> {
        val root = JSONObject(body)
        val feats = root.optJSONArray("features") ?: return emptyList()
        val out = ArrayList<SearchResult>(feats.length())
        for (i in 0 until feats.length()) {
            val f = feats.optJSONObject(i) ?: continue
            val props = f.optJSONObject("properties") ?: continue
            val coords = f.optJSONObject("geometry")
                ?.optJSONArray("coordinates") ?: continue
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val name = props.optString("name").takeIf { it.isNotBlank() }
                ?: props.optString("street").takeIf { it.isNotBlank() }
                ?: props.optString("city").takeIf { it.isNotBlank() }
                ?: continue
            val subtitle = listOfNotNull(
                listOfNotNull(
                    props.optString("street").takeIf { it.isNotBlank() },
                    props.optString("housenumber").takeIf { it.isNotBlank() }
                ).joinToString(" ").takeIf { it.isNotBlank() },
                props.optString("district").takeIf { it.isNotBlank() },
                props.optString("city").takeIf { it.isNotBlank() },
                props.optString("country").takeIf { it.isNotBlank() }
            ).distinct().joinToString(" · ")
            val osmType = props.optString("osm_type", "unknown")
            val osmId = props.optLong("osm_id", -1L)
            out += SearchResult(
                key = "osm:$osmType:$osmId",
                name = name,
                subtitle = subtitle,
                lat = lat,
                lon = lon,
                osmValue = props.optString("osm_value")
            )
        }
        return out
    }

    private fun userAgent(): String {
        val pkg = appContext.packageName
        val ver = runCatching {
            appContext.packageManager.getPackageInfo(pkg, 0).versionName
        }.getOrDefault("dev")
        return "GeelyTools/$ver ($pkg; Geely EX2 head unit; navigation search)"
    }

    private fun formatBias(v: Double): String = String.format(Locale.US, "%.2f", v)

    /** Остановка при dispose() хоста (пул демонический, но прибраться надо). */
    fun release() {
        synchronized(this) { generation++; activeConn?.disconnect(); activeConn = null }
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "NavSearchNet"
        private const val RESULT_LIMIT = 8
        private const val BIAS_SCALE = "0.5"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 12_000
        private const val CACHE_SIZE = 150
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
    }
}
