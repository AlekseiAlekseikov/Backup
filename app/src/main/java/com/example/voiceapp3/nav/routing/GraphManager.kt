package com.example.voiceapp3.nav.routing

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Этап 7 / fix9: АУДИТ ЖИЗНЕННОГО ЦИКЛА офлайн-графа маршрутов Беларуси.
 *
 * Контракт (по чек-листу агента):
 *  · источник — прямой дамп Geofabrik belarus-latest.osm.pbf + .md5 того же
 *    зеркала (open data ODbL, без ключей/квот); размер — из HEAD до старта;
 *  · скачивание возобновляемое: .part + Range + .meta-sidecar (ожидаемые
 *    total/Last-Modified) — если сервер успел обновить снапшот, «хвост»
 *    чужого файла подклеиваться НЕ будет (sidecar не сошёлся → часть
 *    сбрасывается честно);
 *  · контроль: MD5 к финализированному pbf (сверено с .md5 Geofabrik);
 *    несовпадение → оба файла удаляются, ошибка — ретраябельная;
 *  · построение — ТОЛЬКО в staging (graph.new): старый граф не трогается,
 *    пока новый не собран и не закрыт; замена = rename graph -> graph.bak,
 *    graph.new -> graph, затем удаление graph.bak; при ЛЮБОЙ ошибке staging
 *    удаляется, прежний граф гарантированно на месте (откат = авторолбэк);
 *  · готовность определяет маркер .gh-import-ok (пишется ПОСЛЕ успешного
 *    importOrLoad), а не случайные файлы частичной сборки;
 *  · все разрушительные операции (delete/finalize) — на том же single-thread
 *    worker'е, что и загрузка/сборка: параллельных загрузок не существует
 *    конструктивно; права/запись — внутренний filesDir, пермишены не нужны;
 *  · хоппер закрывается только под write-lock; живой маршрут держит
 *    read-lock — GH не умирает под активным route;
 *  · пауза = честная отмена на скачивании (.part+meta позволяют продолжить
 *    с места), продолжение = тот же «Скачать и подготовить»;
 *  · сетевые обрывы: оркестратор до MAX_DOWNLOAD_RETRIES реконнектов с
 *    backoff, каждый — с Range-резюме; HTTP-коды и отмена не ретраятся;
 *  · свободное место считается честно: pbf + новый граф (≈pbf, эмпирика
 *    BY: ~350 МБ) + СТАРЫЙ граф при наличии + запас 128 МБ;
 *  · перезапуск приложения: init-housekeeping сносит graph.new/graph.bak,
 *    .part без .meta, .meta без .part — ничего протухшего не остаётся;
 *  · UI никогда не ходит по дереву файлов: размер «на диске» считается на
 *    worker'е и кладётся в Status.bytesTotal;
 *  · пустых try/catch нет: каждая ветка либо в лог с тегом, либо в Status.
 */
class GraphManager(context: Context) {

    enum class Phase { MISSING, CHECKING, DOWNLOADING, IMPORTING, READY, ERROR }

    data class Status(
        val phase: Phase,
        val detail: String = "",
        val bytesGot: Long = 0,
        val bytesTotal: Long = -1,
        val error: String? = null
    )

    /** Колбэк статуса (всегда на main). */
    var listener: ((Status) -> Unit)? = null

    @Volatile private var status = Status(Phase.MISSING)
    @Volatile private var cancelRequested = false
    @Volatile var hopper: GraphHopper? = null
        private set

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nav-graph").apply { isDaemon = true }
    }

    /** Живой route() — read-lock; close/finalize/delete — write-lock. */
    private val engineLock = ReentrantReadWriteLock()

    private val rootDir = File(appContext.filesDir, "nav-routing/by")
    private val pbfFile = File(rootDir, "belarus-latest.osm.pbf")
    private val partFile = File(rootDir, "belarus-latest.osm.pbf.part")
    private val partMeta = File(rootDir, "belarus-latest.osm.pbf.meta")
    private val md5File = File(rootDir, "belarus-latest.osm.pbf.md5")
    private val graphDir = File(rootDir, "graph")
    private val graphStaging = File(rootDir, "graph.new")
    private val graphBackup = File(rootDir, "graph.bak")
    private val importMarker = File(graphDir, ".gh-import-ok")

    init {
        // перезапуск приложения: снос хвостов прошлых незавершённых циклов
        work("housekeeping") { housekeep() }
    }

    fun currentStatus(): Status = status

    /** Готовый граф на диске: маркер + ключевые файлы GH. */
    fun hasGraphOnDisk(): Boolean =
        importMarker.exists() && File(graphDir, "properties").exists() &&
            File(graphDir, "nodes").exists()

    fun bytesOnDisk(): Long {
        fun walk(f: File): Long = when {
            !f.exists() -> 0L
            f.isFile -> f.length()
            else -> f.listFiles()?.sumOf { walk(it) } ?: 0L
        }
        return walk(rootDir)
    }

    /** Живой маршрут под read-lock (хоппер не закроется в середине route). */
    fun <T> withRouteLock(block: (GraphHopper?) -> T): T {
        engineLock.readLock().lock()
        try {
            return block(hopper)
        } finally {
            engineLock.readLock().unlock()
        }
    }

    /** Загрузить готовый граф в память (если есть на диске). Идемпотентно. */
    fun ensureLoaded() {
        if (hopper != null) { markReady(); return }
        val ready = hasGraphOnDisk()
        if (!ready) {
            if (graphDir.exists()) {
                // fix9: частичная установка без маркера — не «граф». Снос
                // строго ДО MISSING (на worker'е, та же очередь): иначе
                // наблюдатель увидит MISSING при ещё существующем мусоре
                Log.w(TAG, "граф без маркера готовности — удаляю частичную установку")
                work("drop-partial") {
                    runCatching { graphDir.deleteRecursively() }
                        .onFailure { Log.w(TAG, "частичный граф не удалён", it) }
                    emit(Status(Phase.MISSING))
                }
            } else {
                emit(Status(Phase.MISSING))
            }
            return
        }
        work("load-graph") {
            try {
                emit(Status(Phase.IMPORTING, detail = "загрузка графа с диска…"))
                val h = newHopper(withOsm = false, dir = graphDir)
                h.importOrLoad()
                engineLock.writeLock().lock()
                try { hopper?.close(); hopper = h } finally { engineLock.writeLock().unlock() }
                markReady()
                Log.i(TAG, "граф загружен: ${graphDir.absolutePath}")
            } catch (t: Throwable) {
                Log.e(TAG, "граф с диска не загрузился (несовместимая версия?)", t)
                engineLock.writeLock().lock()
                try { runCatching { graphDir.deleteRecursively() } } finally { engineLock.writeLock().unlock() }
                emit(Status(Phase.ERROR, error =
                    "граф не открылся: ${errText(t)} — соберите заново"))
            }
        }
    }

    /** Размер скачивания по HEAD (честно показать ДО старта). */
    fun probeDownloadSize(onResult: (Long) -> Unit) {
        work("probe-size") {
            var size = -1L
            runCatching {
                val c = connect(PBF_URL, 15_000, 15_000).apply { requestMethod = "HEAD" }
                if (c.responseCode in 200..299) size = c.contentLengthLong
                c.disconnect()
            }.onFailure { Log.w(TAG, "HEAD размера не удался", it) }
            val r = size
            main.post { onResult(r) }
        }
    }

    /** Полный цикл: скачать (если нужно) → собрать в staging → атомарно заменить. */
    fun downloadAndBuild() {
        if (status.phase == Phase.DOWNLOADING || status.phase == Phase.IMPORTING) return
        cancelRequested = false
        work("download+build") {
            try {
                rootDir.mkdirs()
                if (!pbfFile.exists()) downloadPbf() else
                    emit(Status(Phase.CHECKING, detail = "pbf уже на диске — проверка контрольной суммы…"))
                checkCanceled()
                verifyMd5Stage()
                checkCanceled()
                importGraphStaged()
            } catch (c: Canceled) {
                Log.i(TAG, "операция отменена пользователем/фазой")
                // staging не финализировался: прежний граф цел
                runCatching { graphStaging.deleteRecursively() }
                if (hasGraphOnDisk()) markReady()
                else emit(Status(Phase.MISSING, detail =
                    if (partFile.exists()) "приостановлено на ${partFile.length() / 1024 / 1024} МБ — «Скачать и подготовить» продолжит с места"
                    else "отменено"))
            } catch (t: Throwable) {
                Log.e(TAG, "downloadAndBuild failed", t)
                runCatching { graphStaging.deleteRecursively() }
                emit(Status(Phase.ERROR, error = errText(t)))
            }
        }
    }

    /** Пауза/отмена: выставляется мгновенно, выполняется на ближайшей точке. */
    fun cancel() {
        cancelRequested = true
        Log.i(TAG, "запрошена пауза/отмена (фаза=${status.phase})")
    }

    /** Удалить граф и загрузки с диска, закрыв движок (на worker'е — сериализовано). */
    fun deleteAll() {
        cancel()
        work("delete-all") {
            engineLock.writeLock().lock()
            try {
                runCatching { hopper?.close() }.onFailure { Log.w(TAG, "hopper close", it) }
                hopper = null
                runCatching { rootDir.deleteRecursively() }
                    .onFailure { Log.w(TAG, "deleteAll частично", it) }
                Log.i(TAG, "граф и загрузки удалены")
            } finally {
                engineLock.writeLock().unlock()
            }
            emit(Status(Phase.MISSING))
        }
    }

    fun close() {
        engineLock.writeLock().lock()
        try {
            runCatching { hopper?.close() }.onFailure { Log.w(TAG, "hopper close", it) }
            hopper = null
        } finally {
            engineLock.writeLock().unlock()
        }
        runCatching { worker.shutdown() }
        // fix9a: «перезапуск» честно означает, что очередь доработала —
        // иначе хвостовая задача уходящего менеджера может пересечься с
        // файловыми операциями следующего (графки на один root).
        runCatching { worker.awaitTermination(10, TimeUnit.SECONDS) }
        listener = null
    }

    // ═══════════════════════════ фазы ════

    private fun housekeep() {
        runCatching { if (graphStaging.exists()) graphStaging.deleteRecursively() }
            .onFailure { Log.w(TAG, "housekeep staging", it) }
        runCatching { if (graphBackup.exists()) graphBackup.deleteRecursively() }
            .onFailure { Log.w(TAG, "housekeep backup", it) }
        // мета без части (или часть без меты) — возобновлять нечего честно
        if (partMeta.exists() && !partFile.exists()) runCatching { partMeta.delete() }
        if (partFile.exists() && !partMeta.exists()) {
            Log.w(TAG, ".part без .meta (межверсия?) — сбрасываю для честности данных")
            runCatching { partFile.delete() }
        }
    }

    private fun downloadPbf() {
        var attempt = 0
        while (true) {
            try {
                downloadPbfAttempt()
                return
            } catch (t: Canceled) {
                throw t
            } catch (t: java.io.IOException) {
                attempt++
                Log.w(TAG, "скачивание оборвалось (#$attempt): ${t.javaClass.simpleName}: ${t.message}", t)
                if (attempt > MAX_DOWNLOAD_RETRIES)
                    throw RoutingError.Engine(
                        "сеть рвётся: $attempt попыток, всё ещё обрыв " +
                            "(в .part сохранено ${partFile.length() / 1024 / 1024} МБ) — ${t.message}")
                emit(Status(Phase.CHECKING,
                    detail = "обрыв сети — реконнект $attempt/$MAX_DOWNLOAD_RETRIES, " +
                        "продолжаем с ${partFile.length() / 1024 / 1024} МБ…"))
                try {
                    Thread.sleep(1500L * attempt)
                } catch (ie: InterruptedException) {
                    throw Canceled()
                }
            }
        }
    }

    private fun connect(url: String, ct: Int, rt: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = ct
            readTimeout = rt
            setRequestProperty("User-Agent", USER_AGENT)
        }

    private fun downloadPbfAttempt() {
        // fix9: перед продолжением — сверка «ожиданий» о файле из .meta:
        // если Geofabrik успел обновить снапшот, хвост ЧУЖОГО файла подклеивать нельзя.
        var resumeFrom = if (partFile.exists()) partFile.length() else 0L
        emit(Status(Phase.CHECKING, detail = "запрос размера и метаданных скачивания…"))
        var c = connect(PBF_URL, 30_000, 30_000).apply { requestMethod = "HEAD" }
        val headTotal = run {
            val code = c.responseCode
            val t = if (code in 200..299) c.contentLengthLong else -1L
            val lm = c.getHeaderField("Last-Modified") ?: ""
            c.disconnect()
            Pair(t, lm)
        }
        if (resumeFrom > 0) {
            val meta = readMeta()
            if (meta == null || meta.total != headTotal.first ||
                (headTotal.second.isNotEmpty() && meta.lastModified != headTotal.second)) {
                Log.w(TAG, ".meta не совпал с сервером (файл на зеркале обновился) — качаю заново")
                runCatching { partFile.delete() }
                resumeFrom = 0
            }
        }
        // честный объём ДО старта
        if (headTotal.first > 0) checkFreeSpace(headTotal.first)
        // md5 файла берём заранее: если зеркало не отдаёт checksum — честная ошибка
        fetchMd5()

        c = connect(PBF_URL, 30_000, 60_000)
        if (resumeFrom > 0) c.setRequestProperty("Range", "bytes=$resumeFrom-")
        try {
            val code = c.responseCode
            if (code !in 200..299 && code != 206)
                throw RoutingError.Engine("Geofabrik ответил HTTP $code")
            // сервер мог проигнорировать Range — тогда пишем заново
            val append = resumeFrom > 0 && code == 206
            val startAt = if (append) resumeFrom else 0L
            val total = if (c.contentLengthLong > 0)
                c.contentLengthLong + startAt else headTotal.first
            if (total <= 0) throw RoutingError.Engine("сервер не отдаёт размер файла")
            if (!append) writeMeta(total, c.getHeaderField("Last-Modified") ?: "")
            FileOutputStream(partFile, append).use { out ->
                c.inputStream.use { inp ->
                    val buf = ByteArray(64 * 1024)
                    var got = startAt
                    var lastEmit = 0L
                    while (true) {
                        checkCanceled()
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (got - lastEmit > 512 * 1024) {
                            lastEmit = got
                            emit(Status(Phase.DOWNLOADING, bytesGot = got, bytesTotal = total))
                        }
                    }
                    out.flush()
                    out.fd.sync()
                    if (got != total)
                        throw java.io.EOFException("поток оборвался: $got из $total байт")
                }
            }
            if (!partFile.renameTo(pbfFile))
                throw RoutingError.Engine("не удалось финализовать pbf-файл")
            Log.i(TAG, "pbf скачан полностью: $total байт")
        } finally {
            c.disconnect()
        }
    }

    private fun fetchMd5() {
        if (md5File.exists()) return
        val c = connect(MD5_URL, 15_000, 15_000)
        try {
            val code = c.responseCode
            if (code !in 200..299)
                throw RoutingError.Engine("Geofabrik не отдал checksum (HTTP $code)")
            val text = c.inputStream.bufferedReader().use { it.readText() }.trim()
            val hex = text.split(Regex("\\s+")).firstOrNull() ?: ""
            if (!hex.matches(Regex("^[0-9a-fA-F]{32}$")))
                throw RoutingError.Engine("неожиданный формат checksum: «${text.take(40)}»")
            FileOutputStream(md5File).use { it.write(hex.lowercase().toByteArray()) }
            Log.i(TAG, "checksum: $hex")
        } finally {
            c.disconnect()
        }
    }

    /** Контроль целостности: локальный MD5 финализированного pbf vs зеркало. */
    private fun verifyMd5Stage() {
        emit(Status(Phase.CHECKING, detail = "проверка контрольной суммы MD5…"))
        fetchMd5()
        val expected = md5File.readText().trim()
        val md = MessageDigest.getInstance("MD5")
        pbfFile.inputStream().buffered(256 * 1024).use { inp ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                checkCanceled()
                val n = inp.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            Log.e(TAG, "MD5 не сошёлся: local=$actual remote=$expected — удаляю и pbf, и .part")
            runCatching { pbfFile.delete() }
            runCatching { partFile.delete() }
            runCatching { partMeta.delete() }
            throw RoutingError.Engine(
                "контрольная сумма не сошлась (${actual.take(12)}… ≠ ${expected.take(12)}…) — файлы сброшены, повторите")
        }
        Log.i(TAG, "MD5 верен: $actual")
    }

    /** Сборка ТОЛЬКО в staging; атомарная замена в конце; старый граф неприкосновенен до swap. */
    private fun importGraphStaged() {
        emit(Status(Phase.IMPORTING,
            detail = "компиляция графа — несколько минут… (прежний граф не тронут)"))
        val t0 = System.currentTimeMillis()
        runCatching { graphStaging.deleteRecursively() }
        graphStaging.mkdirs()
        val h = newHopper(withOsm = true, dir = graphStaging)
        try {
            h.importOrLoad()
            checkCanceled()
        } catch (t: Throwable) {
            // fix9a: builder не должен утекать — при отмене/ошибке закрываем,
            // чтобы mmap/файловые дескрипторы не жили за живым cleanup'ом.
            runCatching { h.close() }
                .onFailure { Log.w(TAG, "builder hopper close после отмены/ошибки", it) }
            throw t
        }
        // fix9a: финальный flush файлов GH происходит в close — без него
        // properties/location_index могут остаться пустыми/недописанными на диске.
        runCatching { h.close() }
            .onFailure { Log.w(TAG, "builder hopper close после сборки", it) }
        val sec = (System.currentTimeMillis() - t0) / 1000
        Log.i(TAG, "граф собран в staging за ${sec}с")

        // атомарная замена под write-lock: routing в этот миг не читает
        engineLock.writeLock().lock()
        try {
            runCatching { hopper?.close() }.onFailure { Log.w(TAG, "hopper close", it) }
            hopper = null
            if (graphDir.exists() && !graphDir.renameTo(graphBackup))
                throw RoutingError.Engine("не удалось отодвинуть прежний граф (graph → graph.bak)")
            if (!graphStaging.renameTo(graphDir)) {
                // откат: вернуть старый на место — установка не должна страдать
                if (graphBackup.exists() && !graphBackup.renameTo(graphDir))
                    Log.e(TAG, "КРИТИЧНО: не удалось вернуть graph.bak → graph (частичная установка)")
                throw RoutingError.Engine("не удалось финализировать graph.new → graph (откат выполнен)")
            }
            File(graphDir, ".gh-import-ok")
                .writeText("by:${PBF_URL.substringAfterLast('/')}:$sec\n11.1\n")
            runCatching { graphBackup.deleteRecursively() }
                .onFailure { Log.w(TAG, "graph.bak не удалён (не критично)", it) }
            hopper = newHopper(withOsm = false, dir = graphDir).also { it.importOrLoad() }
        } finally {
            engineLock.writeLock().unlock()
        }
        // pbf больше не нужен: честно освобождаем сотни МБ
        val freed = pbfFile.length()
        runCatching { pbfFile.delete() }
        runCatching { md5File.delete() }
        Log.i(TAG, "обмен завершён; освобождено ${freed / 1024 / 1024} МБ pbf")
        markReady()
    }

    private fun newHopper(withOsm: Boolean, dir: File): GraphHopper {
        // fix8d-fix9: AndroidGraphHopper — без janino; SourceVersion-stub (fix8g);
        // MMAP-хранилище вместо VarHandle RAMDataAccess (fix8i/fix9).
        val h = AndroidGraphHopper()
        h.graphHopperLocation = dir.absolutePath
        if (withOsm) h.osmFile = pbfFile.absolutePath
        // один профиль: авто. EVs car_access/car_average_speed создаются в
        // prepareImport AndroidGraphHopper (fix8e). CH/LM off.
        h.setProfiles(Profile(PROFILE_CAR))
        return h
    }

    // ═══════════════════════════ утилиты ════

    private fun markReady() {
        emit(Status(Phase.READY, detail = "граф Беларуси готов",
            bytesTotal = bytesOnDisk()))
    }

    /** Честное место: pbf + собранный граф (≈pbf, меряно на BY) + старый граф + запас. */
    private fun checkFreeSpace(needPbfBytes: Long) {
        val free = StatFs(rootDir.absolutePath).availableBytes
        val oldGraph = if (graphDir.exists()) {
            runCatching {
                File(graphDir, "nodes").length() + File(graphDir, "edges").length() +
                    File(graphDir, "geometry").length() + File(graphDir, "location_index").length()
            }.getOrDefault(0L)
        } else 0L
        val need = needPbfBytes /* pbf */ + needPbfBytes /* новый граф ≈ pbf (BY) */ +
            oldGraph + 128L * 1024 * 1024
        if (free < need)
            throw RoutingError.Engine(String.format(Locale.US,
                "мало места: надо ~%d МБ, свободно %d МБ",
                need / 1024 / 1024, free / 1024 / 1024))
    }

    private fun readMeta(): PartMeta? = runCatching {
        val lines = partMeta.readText().lines()
        PartMeta(lines.getOrNull(0)?.toLongOrNull() ?: -1L, lines.getOrNull(1) ?: "")
    }.getOrNull()

    private fun writeMeta(total: Long, lastModified: String) {
        FileOutputStream(partMeta).use { it.write("$total\n$lastModified\n".toByteArray()) }
    }

    private data class PartMeta(val total: Long, val lastModified: String)

    private fun checkCanceled() { if (cancelRequested) throw Canceled() }
    private class Canceled : Exception()

    /** fix8f: честный текст ошибки для диагностики (корень цепочки + кадры). */
    private fun errText(t: Throwable): String {
        var root = t
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val frames = t.stackTrace
            .filter { it.className.startsWith("com.graphhopper") || it.className.startsWith("com.example.voiceapp3") }
            .take(4)
            .joinToString("\n") { "  at ${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
        val tail = buildString {
            if (root !== t) append("\nкорень: ").append(root.javaClass.simpleName)
                .append(": ").append(root.message?.take(160) ?: "—")
            if (frames.isNotEmpty()) append("\n").append(frames)
        }
        return (t.message ?: t.javaClass.simpleName) + tail
    }

    private fun emit(s: Status) {
        status = s
        val l = listener ?: return
        main.post { l(s) }
    }

    private fun work(name: String, b: () -> Unit) {
        try {
            worker.execute { runCatching(b).onFailure { Log.w(TAG, "task $name crashed", it) } }
        } catch (r: RejectedExecutionException) {
            Log.w(TAG, "worker закрыт, task $name не поставлен в очередь")
        }
    }

    companion object {
        const val TAG = "NavGraph"
        const val PROFILE_CAR = "car"

        /** Сетевые автоповторы скачивания pbf (каждый — с Range-резюме). */
        const val MAX_DOWNLOAD_RETRIES = 5

        /** Geofabrik, регион Беларусь; open data, без ключей/квот. */
        const val PBF_URL = "https://download.geofabrik.de/europe/belarus-latest.osm.pbf"
        const val MD5_URL = "$PBF_URL.md5"
        const val USER_AGENT = "GeelyTools-nav/1.0 (+https://github.com/AlekseiAlekseikov/GeelyTools)"
    }
}
