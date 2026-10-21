package com.example.voiceapp3.nav.offline

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Stage NAV-OFFLINE (этап 5): менеджер офлайн-пакетов поверх MapLibre
 * OfflineManager. Аудит 2026-10-04 (полевые прогоны на ГУ) — цепочки
 * «Пауза»/«Удалить» укреплены, все исключения логируются и видны:
 *
 *  · WEDGE-ЗАЩИТА: listOfflineRegions обёрнут в runCatching — синхронный
 *    бросок движка (mid-teardown JNI) больше не кладёт pushStatesThrottled
 *    навечно (симптом на ГУ: «Пауза не реагирует, интерфейс заморожен»).
 *    Каждый колбэк MapLibre исполняется внутри try/catch → Log.e(TAG, …),
 *    тексты — также в состояние региона (errorText в строке офлайн-листа).
 *  · ОПТИМИСТЫ: на паузу/скачивание/удаление стоит ожидаемая фаза — строка
 *    переключается сразу и подтверждается нативным пересчётом.
 *  · ЗАЩИТА ОТ ДУБЛЕЙ: in-flight создания по каждому id в одном локе,
 *    при повторном тапе лишние регионы одного id вычищаются (cull >2);
 *    нескольких параллельных загрузок одного региона быть не может.
 *  · delete(): сначала INACTIVE (остановить закачку), потом deleteRegion —
 *    удаление активной закачки в некоторых билдах v13.4.1 долбит натив.
 *
 * География каждого региона — TilePyramid (z диапазон пакета); скачиваем
 * ПАРУ регионов за тап — по стилям upstream OpenFreeMap bright + dark:
 * их sources/glyphs/sprites байт-идентичны нашим вендорным asset-стилям —
 * каждый скачанный ресурс по URL-идентичности отдаётся нашей карте
 * с asset://nav_day|night — полный офлайн в обеих темах. Почему НЕ asset://
 * в definition (этап 5 хотфикс): офлайн-движок подвисает на выкачивании
 * стиля по локальному URI — сохраняемые definition держим на https.
 * Регионы эпохи asset:// авто-чистятся при повторном тапе (ниже).
 *
 * Источник истины о том, что реально скачано, — собственная БД
 * OfflineManager (никаких вторых реестров). После убийства процесса статус
 * честно восстанавливается из БД (частично скачанное = «Пауза», постоянно).
 */
class OfflineMapManager private constructor(private val appContext: Context) {

    enum class Phase { NOT_STARTED, PAUSED, DOWNLOADING, DONE }

    data class PackState(
        val id: String,
        val phase: Phase,
        val doneResources: Long = 0,
        val totalResources: Long = 0,
        val doneBytes: Long = 0,
        val tileCount: Long = 0,
        val errorText: String? = null
    ) {
        /** 0..1, если размер известен. */
        val progress01: Float? =
            if (totalResources > 0) doneResources.toFloat() / totalResources.toFloat() else null
    }

    private val manager = OfflineManager.getInstance(appContext)
    private val main = Handler(Looper.getMainLooper())

    /** Подписчики состояний (лист офлайн-настроек). */
    private val listeners = LinkedHashSet<(Map<String, PackState>) -> Unit>()

    /** Последний текст ошибки по региону (в памяти процесса). */
    private val lastErrors = HashMap<String, String>()

    /** Троттлинг пересчёта состояний — наблюдатель стреляет часто. */
    private var refreshPending = false

    /** Ид → in-flight создание (лок против дублей пары день/ночь). */
    private val creating = HashSet<String>()

    /** Ожидаемая фаза после действия (optimistic): id → фаза. Снимается,
     * когда нативный пересчёт подтверждает то же значение. */
    private val pendingPhase = HashMap<String, Phase>()

    init {
        // Потолок счётчика тайлов — страховка от безразмерного региона,
        // не шейпинг: RF/EU-пакеты по z14 далеко за 100k тайлов.
        manager.setOfflineMapboxTileCountLimit(TILE_COUNT_LIMIT)
        provisionFromFactoryDir()
    }

    // ═══════════════════════════════════ подписка ════

    fun addStateListener(l: (Map<String, PackState>) -> Unit) {
        listeners.add(l)
        pushStatesThrottled()
    }

    fun removeStateListener(l: (Map<String, PackState>) -> Unit) {
        listeners.remove(l)
    }

    /** Принудительный пересчёт состояний (для тикера листа настроек). */
    fun forceRefresh() = pushStatesThrottled()

    // ═══════════════════════════════════ пересчёт состояний ════

    private fun pushStatesThrottled() {
        if (refreshPending) return
        refreshPending = true
        main.postDelayed({
            refreshPending = false
            runCatching { refreshNow() }.onFailure {
                Log.e(TAG, "refreshNow crash", it)
            }
        }, REFRESH_THROTTLE_MS)
    }

    private fun refreshNow() {
        // СИНХРОННЫЕ броски движка (Jni teardown) — сюда; обернут в try,
        // иначе dequeue умирал бы молча («Пауза ни на что не влияет» на ГУ).
        try {
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(regions: Array<OfflineRegion>?) {
                    try {
                        aggregate(regions.orEmpty().toList()) { states ->
                            listeners.forEach { l ->
                                runCatching {
                                    main.post { l.invoke(states) }
                                }.onFailure { Log.w(TAG, "state listener failed", it) }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "aggregate crashed", e)
                    }
                }

                override fun onError(error: String) {
                    Log.w(TAG, "listOfflineRegions: $error")
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "listOfflineRegions sync-threw", e)
        }
    }

    /**
     * Группировка по id из метаданных: пакет = пара регионов (день+ночь).
     * Статус каждого региона читаем через getStatus — собираем, когда готовы
     * все ответы.
     */
    private fun aggregate(regions: List<OfflineRegion>, cb: (Map<String, PackState>) -> Unit) {
        val byPack = LinkedHashMap<String, MutableList<OfflineRegion>>()
        regions.forEach { r ->
            val packId = packIdOf(r) ?: return@forEach
            byPack.getOrPut(packId) { mutableListOf() }.add(r)
        }
        val out = LinkedHashMap<String, PackState>()
        val total = byPack.values.sumOf { it.size }
        if (total == 0) {
            cb(out)
            return
        }
        var received = 0
        val lock = Any()
        val statusByRegion = HashMap<OfflineRegion, OfflineRegionStatus>()
        byPack.values.forEach { list ->
            list.forEach { region ->
                val statusCb = object : OfflineRegion.OfflineRegionStatusCallback {
                    override fun onStatus(status: OfflineRegionStatus?) {
                        try {
                            synchronized(lock) { status?.let { statusByRegion[region] = it } }
                        } catch (e: Throwable) {
                            Log.e(TAG, "status cb crashed", e)
                        } finally {
                            doneOne()
                        }
                    }

                    override fun onError(error: String?) = doneOne()

                    private fun doneOne() {
                        val all: Boolean
                        synchronized(lock) {
                            received++
                            all = received == total
                        }
                        if (all) {
                            try {
                                cb(computeStates(byPack, statusByRegion))
                            } catch (e: Throwable) {
                                Log.e(TAG, "computeStates crashed", e)
                            }
                        }
                    }
                }
                try {
                    region.getStatus(statusCb)
                } catch (e: Throwable) {
                    Log.e(TAG, "getStatus sync-threw", e)
                    statusCb.onError(e.message)
                }
            }
        }
    }

    private fun computeStates(
        byPack: Map<String, List<OfflineRegion>>,
        statuses: Map<OfflineRegion, OfflineRegionStatus>
    ): Map<String, PackState> {
        val res = LinkedHashMap<String, PackState>()
        byPack.forEach { (id, regions) ->
            val sts = regions.mapNotNull { statuses[it] }
            val done = sts.sumOf { it.completedResourceCount }
            val total = sts.sumOf { it.requiredResourceCount }
            val bytes = sts.sumOf { it.completedResourceSize }
            val tiles = sts.sumOf { it.completedTileCount }
            val allComplete = regions.isNotEmpty() && sts.size == regions.size &&
                sts.all { it.isComplete }
            val anyActive = sts.any { it.downloadState == OfflineRegion.STATE_ACTIVE }
            val computed = when {
                allComplete -> Phase.DONE
                anyActive -> Phase.DOWNLOADING
                else -> Phase.PAUSED
            }
            // Оптимистичная фаза держится, пока нативный пересчёт её не
            // подтвердит (подтверждение → снять override, больше не мешать).
            val phase = pendingPhase[id]?.let { p ->
                if (p == computed || (computed == Phase.DONE && p == Phase.DOWNLOADING)) {
                    pendingPhase.remove(id)
                    computed
                } else p
            } ?: computed
            res[id] = PackState(
                id = id,
                phase = phase,
                doneResources = done,
                totalResources = total,
                doneBytes = bytes,
                tileCount = tiles,
                errorText = lastErrors[id]
            )
        }
        // Завершённые/overridden id, которым уже не принадлежит запись — чистка.
        pendingPhase.keys.retainAll(byPack.keys)
        return res
    }

    // ═══════════════════════════════════ действия ════

    /** Скачать/возобновить регион каталога (идемпотентно, лок от дублей). */
    fun download(def: OfflineRegionsCatalog.RegionDef) {
        Log.i(TAG, "download(${def.id})")
        lastErrors.remove(def.id)
        val inFlight = synchronized(creating) {
            if (def.id in creating) true else {
                creating += def.id
                false
            }
        }
        if (inFlight) {
            Log.i(TAG, "download(${def.id}): already creating, coalesced")
            pushStatesThrottled()
            return
        }
        pendingPhase[def.id] = Phase.DOWNLOADING
        pushStatesThrottled()
        val finish = {
            synchronized(creating) { creating.remove(def.id) }
            pushStatesThrottled()
        }
        try {
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(regions: Array<OfflineRegion>?) {
                    try {
                        val existing = regions.orEmpty().filter { packIdOf(it) == def.id }
                        when {
                            // Эпоха asset:// definition: пересоздать на https,
                            // иначе resume поднимал бы заведомо подвисший регион.
                            existing.any { !styleIsCurrent(it) } -> {
                                Log.i(TAG, "legacy offline regions for ${def.id} — wipe & recreate")
                                deleteRegionsAndThen(existing) { createPair(def, finish) }
                            }
                            existing.size > 2 -> {
                                // Прибитая гонка прошлого: держать не больше пары яркостей.
                                val (current, dupes) = existing.partition { styleIsCurrent(it) }
                                if (current.size >= 2 || dupes.isEmpty()) {
                                    existing.forEach { r ->
                                        runCatching {
                                            r.setDownloadState(OfflineRegion.STATE_ACTIVE)
                                        }.onFailure { Log.e(TAG, "resume active failed", it) }
                                    }
                                    attachObservers(existing, def.id)
                                    deleteRegionsAndThen(dupes) {}
                                    finish()
                                } else {
                                    deleteRegionsAndThen(existing) { createPair(def, finish) }
                                }
                            }
                            existing.isNotEmpty() -> {
                                existing.forEach { r ->
                                    runCatching {
                                        r.setDownloadState(OfflineRegion.STATE_ACTIVE)
                                    }.onFailure { Log.e(TAG, "resume active failed", it) }
                                }
                                attachObservers(existing, def.id)
                                finish()
                            }
                            else -> createPair(def, finish)
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "download/onList crashed", e)
                        markError(def.id, e.message)
                        finish()
                    }
                }

                override fun onError(error: String) {
                    Log.w(TAG, "download/list: $error")
                    markError(def.id, error)
                    finish()
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "download sync-threw", e)
            markError(def.id, e.message)
            finish()
        }
    }

    /** Поставить на паузу (пакет целиком; ожидаемая фаза мгновенно). */
    fun pause(packId: String) {
        Log.i(TAG, "pause($packId)")
        pendingPhase[packId] = Phase.PAUSED
        pushStatesThrottled()
        applyToPackRegions(packId, { r ->
            runCatching { r.setDownloadState(OfflineRegion.STATE_INACTIVE) }
                .onFailure { Log.e(TAG, "pause failed for region", it) }
        }, {
            pushStatesThrottled()
        })
    }

    /** Удалить пакет целиком: СНАЧАЛА остановка (INACTIVE), потом deleteRegion —
     * в порядке, безопасном для v13 (удаление внутри активной закачки по
     * совместительству собирало нативные краши в полд). */
    fun delete(packId: String) {
        Log.i(TAG, "delete($packId)")
        lastErrors.remove(packId)
        // Ожидаемое: регионы уйдут из списков (ничего не «NOT_STARTED» отсюда).
        pendingPhase.remove(packId)
        try {
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(regions: Array<OfflineRegion>?) {
                    try {
                        val mine = regions.orEmpty().filter { packIdOf(it) == packId }
                        if (mine.isEmpty()) {
                            pushStatesThrottled()
                            return
                        }
                        // 1) немедленная остановка скачивания у всех регионов пакета
                        mine.forEach { r ->
                            runCatching { r.setDownloadState(OfflineRegion.STATE_INACTIVE) }
                                .onFailure { Log.e(TAG, "delete-stop failed", it) }
                        }
                        // 2) само удаление — параллельными колбэками со счётчиком
                        var pending = mine.size
                        val counter = Any()
                        mine.forEach { r ->
                            runCatching { r.setObserver(null) }
                            try {
                                r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                                    override fun onDelete() {
                                        synchronized(counter) {
                                            if (--pending == 0) {
                                                Log.i(TAG, "delete($packId) complete")
                                                pushStatesThrottled()
                                            }
                                        }
                                    }

                                    override fun onError(error: String) {
                                        Log.w(TAG, "delete($packId): $error")
                                        synchronized(counter) {
                                            if (--pending == 0) pushStatesThrottled()
                                        }
                                    }
                                })
                            } catch (e: Throwable) {
                                Log.e(TAG, "delete sync-threw for region", e)
                                synchronized(counter) {
                                    if (--pending == 0) pushStatesThrottled()
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "delete/onList crashed", e)
                    }
                }

                override fun onError(error: String) {
                    Log.w(TAG, "delete/list: $error")
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "delete sync-threw", e)
        }
    }

    /** «Обновить» пакет: дождаться удаления его регионов и перекачать заново.
     * Работает через существующие стойкие пути (stop→delete→create+download),
     * дубли застрахованы creating-локом внутри download(). */
    fun refresh(def: OfflineRegionsCatalog.RegionDef) {
        Log.i(TAG, "refresh(${def.id})")
        try {
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(regions: Array<OfflineRegion>?) {
                    val mine = regions.orEmpty().filter { packIdOf(it) == def.id }
                    if (mine.isEmpty()) {
                        download(def)
                        return
                    }
                    deleteRegionsAndThen(mine) { download(def) }
                }

                override fun onError(error: String) {
                    Log.w(TAG, "refresh/list: $error")
                    markError(def.id, error)
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "refresh sync-threw", e)
            markError(def.id, e.message)
        }
    }

    // ═══════════════════════════════════ создание пары день+ночь ════

    private fun createPair(def: OfflineRegionsCatalog.RegionDef, finish: () -> Unit) {
        val bounds = LatLngBounds.Builder()
            .include(LatLng(def.north, def.east))
            .include(LatLng(def.south, def.west))
            .build()
        val density = appContext.resources.displayMetrics.density
        // ЖИВЁМ ТОЛЬКО на https: asset:// definition вешал движок (хотфикс этапа 5).
        val styles = listOf(OFFLINE_DAY_STYLE_URL, OFFLINE_NIGHT_STYLE_URL)
        var created = 0
        var failed = false
        val countLock = Any()
        fun oneDone() {
            val all: Boolean
            synchronized(countLock) {
                created++
                all = created == styles.size || (failed && created == styles.size)
            }
            if (all) finish()
        }
        styles.forEach { styleUri ->
            val definition = OfflineTilePyramidRegionDefinition(
                styleUri, bounds, def.minZoom, def.maxZoom, density
            )
            try {
                manager.createOfflineRegion(
                    definition,
                    metadataBytes(def.id, styleUri),
                    object : OfflineManager.CreateOfflineRegionCallback {
                        override fun onCreate(region: OfflineRegion) {
                            try {
                                attachObservers(listOf(region), def.id)
                                region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                            } catch (e: Throwable) {
                                Log.e(TAG, "onCreate post-crash", e)
                            }
                            pushStatesThrottled()
                            oneDone()
                        }

                        override fun onError(error: String) {
                            synchronized(countLock) { failed = true }
                            markError(def.id, error)
                            oneDone()
                        }
                    }
                )
            } catch (e: Throwable) {
                Log.e(TAG, "createOfflineRegion sync-threw", e)
                synchronized(countLock) { failed = true }
                markError(def.id, e.message)
                oneDone()
            }
        }
    }

    private fun attachObservers(regions: List<OfflineRegion>, packId: String) {
        regions.forEach { region ->
            region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    if (status.isComplete) region.setObserver(null)
                    pushStatesThrottled()
                }

                override fun onError(error: org.maplibre.android.offline.OfflineRegionError) {
                    // По брифу: задача останавливается (прогресс сохранён в БД
                    // движка), пользователь уведомлён, приложение живо.
                    runCatching { region.setDownloadState(OfflineRegion.STATE_INACTIVE) }
                        .onFailure { Log.w(TAG, "stop-on-error failed", it) }
                    markError(packId, error.message)
                }

                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    runCatching { region.setDownloadState(OfflineRegion.STATE_INACTIVE) }
                    markError(packId, "превышен лимит тайлов ($limit): рамку надо сузить")
                }
            })
        }
    }

    // ═══════════════════════════════════ провизия «в прошивку» ════

    /**
     * Слить заранее собранные офлайн-БД (<filesDir>/../../provision/offline,
     * app-specific: root не нужен), переместить обработанные в merged/.
     * Фабричный BY-пакет попадает туда sideload-скриптом (см. README
     * этапа 5) — взаимодействие с сетью при провизии нулевое.
     */
    private fun provisionFromFactoryDir() {
        runCatching {
            val dir = File(appContext.getExternalFilesDir(null), "provision/offline")
            val pending = dir.listFiles { f -> f.isFile && f.extension.lowercase() == "db" }
                ?.sortedBy { it.name }
                ?.toMutableList()
                ?: return
            if (pending.isEmpty()) return
            mergeNext(dir, pending)
        }.onFailure { Log.e(TAG, "provision scan failed", it) }
    }

    /**
     * v13: mergeOfflineRegions(path, callback) сливает ОДИН файл за вызов;
     * read-only источники SDK сам копирует во внутренний кэш и там же
     * удаляет после слияния. Обработанный файл уезжает в merged/, ошибочный
     * остаётся на месте (повтор при следующем запуске) и не блокирует
     * остальные.
     */
    private fun mergeNext(dir: File, queue: MutableList<File>) {
        val f = queue.removeFirstOrNull() ?: run {
            pushStatesThrottled()
            return
        }
        try {
            manager.mergeOfflineRegions(
                f.absolutePath,
                object : OfflineManager.MergeOfflineRegionsCallback {
                    override fun onMerge(regions: Array<OfflineRegion>?) {
                        Log.i(TAG, "provision merged ${f.name}: ${regions?.size ?: 0} regions")
                        runCatching {
                            val done = File(dir, "merged").apply { mkdirs() }
                            f.renameTo(File(done, f.name))
                        }.onFailure { Log.w(TAG, "provision move failed: ${f.name}", it) }
                        mergeNext(dir, queue)
                    }

                    override fun onError(error: String) {
                        Log.e(TAG, "provision merge failed ${f.name}: $error")
                        mergeNext(dir, queue)
                    }
                }
            )
        } catch (e: Throwable) {
            Log.e(TAG, "provision merge sync-threw ${f.name}", e)
            mergeNext(dir, queue)
        }
    }

    // ═══════════════════════════════════ утилиты ════

    private fun applyToPackRegions(
        packId: String,
        action: (OfflineRegion) -> Unit,
        onApplied: (() -> Unit)? = null
    ) {
        try {
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(regions: Array<OfflineRegion>?) {
                    try {
                        regions.orEmpty().filter { packIdOf(it) == packId }.forEach(action)
                    } catch (e: Throwable) {
                        Log.e(TAG, "apply/$packId crashed", e)
                    }
                    (onApplied ?: ::pushStatesThrottled).invoke()
                }

                override fun onError(error: String) {
                    Log.w(TAG, "apply/list: $error")
                    onApplied?.invoke()
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "apply sync-threw", e)
        }
    }

    private fun packIdOf(region: OfflineRegion): String? = runCatching {
        JSONObject(String(region.metadata, Charsets.UTF_8)).optString("id")
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Тег стиля из метаданных («bright»/«dark» у актуальных, «nav_*.json» у старых). */
    private fun styleTagOf(region: OfflineRegion): String? = runCatching {
        JSONObject(String(region.metadata, Charsets.UTF_8)).optString("style")
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Актуальный ли definition у региона (https-стили яркости); старые — на пересоздание. */
    private fun styleIsCurrent(region: OfflineRegion): Boolean =
        styleTagOf(region) == "bright" || styleTagOf(region) == "dark"

    /** Удалить пачку регионов и вызвать onDone по завершении всех delete-колбэков. */
    private fun deleteRegionsAndThen(regions: List<OfflineRegion>, onDone: () -> Unit) {
        var pending = regions.size
        if (pending == 0) { onDone(); return }
        regions.forEach { r ->
            runCatching { r.setObserver(null) }
            try {
                r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() { if (--pending == 0) onDone() }

                    override fun onError(error: String) {
                        Log.w(TAG, "deleteRegionsAndThen: $error")
                        if (--pending == 0) onDone()
                    }
                })
            } catch (e: Throwable) {
                Log.e(TAG, "deleteRegionsAndThen sync-threw", e)
                if (--pending == 0) onDone()
            }
        }
    }

    private fun metadataBytes(id: String, styleUri: String): ByteArray =
        JSONObject()
            .put("id", id)
            .put("style", styleUri.substringAfterLast('/'))
            .put("schema", META_SCHEMA_VERSION)
            .toString()
            .toByteArray(Charsets.UTF_8)

    private fun markError(id: String, message: String?) {
        Log.e(TAG, "pack $id error: $message")
        val text = message ?: "неизвестная ошибка"
        // Уведомление один раз на изменение текста — протокол ошибок в Log.e
        // идёт непрерывно, а Toast'ы не устроим в фоне спамом.
        if (lastErrors[id] != text) {
            lastErrors[id] = text
            main.post {
                runCatching {
                    android.widget.Toast.makeText(
                        appContext,
                        "Офлайн-пакет «$id»: $text. Загрузка остановлена, прогресс сохранён.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }.onFailure { Log.w(TAG, "toast failed", it) }
            }
        }
        pendingPhase.remove(id)   // оптимист больше не прикрывает реальное состояние
        pushStatesThrottled()
    }

    companion object {
        private const val TAG = "OfflineMaps"
        private const val REFRESH_THROTTLE_MS = 400L
        private const val META_SCHEMA_VERSION = 1

        /** Страховочный потолок тайлов на все пакеты суммарно. */
        private const val TILE_COUNT_LIMIT = 2_000_000L

        /**
         * https-стили для offline-definition (см. KDoc класса). Источники —
         * OpenFreeMap upstream; их ресурсы совпадают с нашими asset-стилями
         * по URL (проверено против bright/dark на 2026-10).
         */
        private const val OFFLINE_DAY_STYLE_URL =
            "https://tiles.openfreemap.org/styles/bright"
        private const val OFFLINE_NIGHT_STYLE_URL =
            "https://tiles.openfreemap.org/styles/dark"

        @Volatile
        private var instance: OfflineMapManager? = null

        fun get(context: Context): OfflineMapManager =
            instance ?: synchronized(this) {
                instance ?: OfflineMapManager(context.applicationContext).also { instance = it }
            }
    }
}
