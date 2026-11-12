package com.example.voiceapp3.tools

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Датированный лог приложения — **ротация целыми закрытыми файлами** (iter42 §2).
 *
 * Что изменилось по сравнению с прежним «тихим trim отдельных строк»:
 *  • потеря полезных событий больше не происходит из-за общего лимита: когда файл вырастает,
 *    он ЗАКРЫВАЕТСЯ и открывается новый (`voiceapp3-DD.MM.YYYY.1.log`), а не режутся строки;
 *  • объём ограничен документированными лимитами (см. [MAX_FILE_BYTES], [MAX_FILES_PER_DAY],
 *    [MAIN_TIER_BYTES]), поэтому «бесконечного» роста нет;
 *  • каждое удаление и каждая ротация **обнаружимы**: они пишутся строкой `LOG_ROTATE …` в новый
 *    (уже открытый) файл и независимо — в logcat;
 *  • шумные низкоуровневые ADB-трассы уходят в ОТДЕЛЬНЫЙ tier ([Tier.VERBOSE]) со своими лимитами,
 *    и не могут вытеснить сеть/JIT/TTL-диагностику;
 *  • каждая запись возвращает [Result]: подтверждённый факт записи И точный файл, куда строка
 *    реально попала (включая фоллбэк external→internal). «Вызов принят» ≠ «записано».
 *
 * Честное ограничение (§2.3): при полностью заполненном разделе, при недоступности ОБОИХ каталогов
 * и при исчерпании лимита нельзя гарантировать сохранность бесконечного потока. Тогда запись
 * отклоняется, а факт отказа сообщается независимо (logcat + `LogFiles.ioErrors()`), и никогда не
 * выдаётся за записанную строку.
 */
object LogFiles {

    private const val TAG = "CENTRAL_LogFiles"

    internal const val PREFIX = "voiceapp3-"
    internal const val VERBOSE_PREFIX = "voiceapp3-verbose-"
    internal const val SUFFIX = ".log"

    /** Период хранения (сутки): старше — удаляется при старте. */
    internal const val RETENTION_DAYS = 14L

    /** Лимит ОДНОГО файла основного лога: после него — ротация, а не обрезка строк. */
    internal const val MAX_FILE_BYTES = 8L * 1024 * 1024          // 8 МиБ

    /** Сколько файлов основного tier допускается на ОДНИ сутки. */
    internal const val MAX_FILES_PER_DAY = 6                       // ≤ 48 МиБ за сутки

    /** Суммарный лимит основного tier по всем датам (при превышении удаляются самые старые). */
    internal const val MAIN_TIER_BYTES = 64L * 1024 * 1024         // 64 МиБ

    /** Лимит одного файла verbose-tier и сколько их держим. */
    internal const val VERBOSE_FILE_BYTES = 4L * 1024 * 1024       // 4 МиБ
    internal const val MAX_VERBOSE_FILES = 4                        // ≤ 16 МиБ

    /** Ротацию не начинаем, если свободного места меньше этого запаса. */
    internal const val FREE_SPACE_MARGIN = 32L * 1024 * 1024       // 32 МиБ

    /** Уровни (tier) лога: полезный журнал и шумные низкоуровневые трассы — раздельно. */
    enum class Tier(val prefix: String, val maxFileBytes: Long, val maxFiles: Int) {
        MAIN(PREFIX, MAX_FILE_BYTES, MAX_FILES_PER_DAY),
        VERBOSE(VERBOSE_PREFIX, VERBOSE_FILE_BYTES, MAX_VERBOSE_FILES)
    }

    /**
     * Итог записи ОДНОЙ строки.
     *
     * @param written запись подтверждена (файл дописан, в т.ч. через фоллбэк)
     * @param file    ТОЧНЫЙ файл, куда строка попала (пусто, если не попала)
     * @param reason  причина отказа (`io:…`, `space:…`, `no_dir`, `dropped:…`) — для независимого канала
     */
    data class Result(
        val written: Boolean,
        val file: String = "",
        val reason: String = ""
    ) {
        companion object {
            fun fail(why: String) = Result(false, "", why)
            fun ok(file: File) = Result(true, file.absolutePath, "")
        }
    }

    private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.US)

    @Volatile private var dirRef: File? = null
    @Volatile private var pathRef: String = "(not initialised yet)"

    /** Имя файла строго по образцу владельца: `voiceapp3-DD.MM.YYYY.log`. */
    internal fun fileNameOf(date: LocalDate): String = PREFIX + date.format(FMT) + SUFFIX

    /** Имя файла для момента времени (мс). */
    internal fun fileNameFor(nowMs: Long): String =
        fileNameOf(Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate())

    /** Имя части verbose-tier: `voiceapp3-verbose-DD.MM.YYYY.log`. */
    internal fun verboseNameFor(nowMs: Long): String =
        VERBOSE_PREFIX + Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())
            .toLocalDate().format(FMT) + SUFFIX

    /**
     * Имя файла части ротации: `<base>.<n>.log`, где `base` — без `.log`.
     * `n` начинается с 1 (`voiceapp3-12.11.2026.1.log`).
     */
    internal fun rotatedName(base: String, n: Int): String =
        base.removeSuffix(SUFFIX) + "." + n + SUFFIX

    /** Дата из имени файла; null — если имя не нашего формата (чужие файлы не трогаем). */
    internal fun parseDate(name: String): LocalDate? {
        if (!name.endsWith(SUFFIX)) return null
        val body = when {
            name.startsWith(VERBOSE_PREFIX) -> name.removeSuffix(SUFFIX).removePrefix(VERBOSE_PREFIX)
            name.startsWith(PREFIX) -> name.removeSuffix(SUFFIX).removePrefix(PREFIX)
            else -> return null
        }
        // Индекс ротации: `12.11.2026.2` → `12.11.2026` (дата всегда DD.MM.YYYY).
        val datePart = if (ROTATED_RE.matches(body)) body.substringBeforeLast('.') else body
        if (!datePart.matches(Regex("\\d{2}\\.\\d{2}\\.\\d{4}"))) return null
        return runCatching { LocalDate.parse(datePart, FMT) }.getOrNull()
    }

    private val ROTATED_RE = Regex("\\d{2}\\.\\d{2}\\.\\d{4}\\.\\d{1,2}")

    /** Просроченные имена: старше [retentionDays] суток относительно [today]. */
    internal fun expired(names: List<String>, today: LocalDate, retentionDays: Long = RETENTION_DAYS): List<String> {
        val edge = today.minusDays(retentionDays)
        return names.filter { n -> parseDate(n)?.isBefore(edge) == true }
    }

    internal fun ensureDir(d: File): File {
        if (!d.exists()) d.mkdirs()
        return d
    }

    /**
     * Каталог логов: внешний `files/logs`, при недоступности — `filesDir/logs`.
     * Проверяется РЕАЛЬНОЙ пробной записью ([writable]), а не только `!= null`.
     */
    fun dirFor(context: Context): File {
        val app = context.applicationContext
        val ext = runCatching { app.getExternalFilesDir(null) }.getOrNull()
        val extDir = ext?.let { File(it, "logs") }
        if (extDir != null) {
            val d = ensureDir(extDir)
            if (writable(d)) return d
            Log.w(TAG, "LogFiles: внешний каталог недоступен для записи (${d.absolutePath}) — фоллбэк внутрь")
        }
        return ensureDir(File(app.filesDir, "logs"))
    }

    /** Реальная проба записи: создать/дописать/удалить пробный файл. */
    internal fun writable(dir: File): Boolean = runCatching {
        if (!dir.isDirectory) return false
        val probe = File(dir, ".probe")
        probe.appendText("")
        probe.delete()
        true
    }.getOrDefault(false)

    /** Свободное место в каталоге (байт); `null` — узнать нельзя. */
    internal fun freeSpace(dir: File): Long? =
        runCatching { dir.usableSpace }.getOrNull()?.takeIf { it > 0 }

    /**
     * Части tier в каталоге, самые новые — первыми.
     *
     * Префикс verbose-tier (`voiceapp3-verbose-`) начинается с префикса основного
     * (`voiceapp3-`), поэтому файлы чужого tier'а явно исключаются: иначе retention/лимит
     * основного журнала считал бы шумные трассы «своими» и мог удалить их первыми.
     */
    internal fun partsOf(dir: File, tier: Tier): List<File> =
        (dir.listFiles()?.toList() ?: emptyList())
            .filter { it.isFile && it.name.startsWith(tier.prefix) && it.name.endsWith(SUFFIX) }
            .filter {
                when (tier) {
                    Tier.MAIN -> !it.name.startsWith(VERBOSE_PREFIX)
                    Tier.VERBOSE -> it.name.startsWith(VERBOSE_PREFIX)
                }
            }
            .sortedByDescending { it.lastModified() }

    /**
     * Удаление просроченных файлов и соблюдение суммарного лимита tier.
     *
     * Возвращает имена удалённых файлов — факт и причина попадают в лог (`LOG_ROTATE removed=…`),
     * поэтому «тихого» исчезновения данных нет.
     */
    /** Старый API (число удалённых) — оставлен для существующих тестов/вызовов. */
    internal fun pruneCount(dir: File, nowMs: Long, tier: Tier = Tier.MAIN): Int =
        pruneTier(dir, nowMs, tier).size

    /** Подробный вариант: возвращает имена удалённых частей (для `LOG_ROTATE removed=…`). */
    internal fun pruneTier(dir: File, nowMs: Long, tier: Tier = Tier.MAIN): List<String> {
        val today = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val names = dir.list()?.toList() ?: return emptyList()
        val removed = ArrayList<String>(4)
        for (n in expired(names, today)) {
            if (runCatching { File(dir, n).delete() }.getOrDefault(false)) removed += n
        }
        // Суммарный лимит tier: удаляем самые старые части сверх лимита.
        val parts = partsOf(dir, tier).sortedBy { it.lastModified() }
        val cap = when (tier) {
            Tier.MAIN -> MAIN_TIER_BYTES
            Tier.VERBOSE -> VERBOSE_FILE_BYTES * MAX_VERBOSE_FILES
        }
        var total = parts.sumOf { it.length() }
        var i = 0
        while (total > cap && i < parts.size) {
            val f = parts[i]
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                total -= len
                removed += f.name
            }
            i++
        }
        return removed
    }

    /** Инициализация при старте: каталог + retention. Возвращает путь каталога. */
    fun prepare(context: Context): String {
        val d = dirFor(context)
        dirRef = d
        val removed = runCatching { pruneTier(d, System.currentTimeMillis(), Tier.MAIN) }.getOrDefault(emptyList())
        val removedVerbose = runCatching { pruneTier(d, System.currentTimeMillis(), Tier.VERBOSE) }
            .getOrDefault(emptyList())
        pathRef = File(d, fileNameFor(System.currentTimeMillis())).absolutePath
        Log.i(
            TAG,
            "LogFiles ready dir=${d.absolutePath} retention_days=$RETENTION_DAYS " +
                "pruned=${removed.size} pruned_verbose=${removedVerbose.size} " +
                "limits main=${MAX_FILE_BYTES}B×$MAX_FILES_PER_DAY/day cap=$MAIN_TIER_BYTES " +
                "verbose=${VERBOSE_FILE_BYTES}B×$MAX_VERBOSE_FILES"
        )
        return d.absolutePath
    }

    /** Путь активного файла (для UI/диагностики). */
    fun currentPath(): String = pathRef

    // ─────────────────────────── счётчики ошибок ───────────────────────────

    private val ioErrorCount = java.util.concurrent.atomic.AtomicLong(0)
    private val rotateCount = java.util.concurrent.atomic.AtomicLong(0)
    private val droppedCount = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile private var lastIoError: String = ""

    fun ioErrors(): Long = ioErrorCount.get()

    /** Сколько раз файл закрывался и открывался новый (ротация). */
    fun rotations(): Long = rotateCount.get()

    /** Сколько строк не удалось записать из-за места/лимита (не «тихий trim»: видно в логе и здесь). */
    fun dropped(): Long = droppedCount.get()

    fun lastIoError(): String = lastIoError

    // ─────────────────────────── запись ───────────────────────────

    /**
     * Дописать строку в файл текущих суток основного tier.
     *
     * @return [Result] с ТОЧНЫМ файлом. Фоллбэк external→internal выполняется внутри и меняет
     *   возвращаемый путь — поэтому вызывающий никогда не додумывает, где оказалась строка.
     */
    fun append(context: Context, line: String): Result = appendTier(context, line, Tier.MAIN)

    /** То же для шумного verbose-tier (отдельные лимиты и отдельное имя файла). */
    fun appendVerbose(context: Context, line: String): Result =
        appendTier(context, line, Tier.VERBOSE)

    private fun appendTier(context: Context, line: String, tier: Tier): Result {
        val app = context.applicationContext
        var dir = dirRef ?: dirFor(app).also { dirRef = it }
        val now = System.currentTimeMillis()
        var attempt = writeAt(dir, line, now, tier)
        if (attempt.written) return attempt
        // Один раз переключаемся на внутренний каталог и пробуем снова.
        val internal = ensureDir(File(app.filesDir, "logs"))
        if (dir.absolutePath != internal.absolutePath) {
            safeLog { Log.w(TAG, "append failed (internal too): ${attempt.reason}") }
            dirRef = internal
            dir = internal
            attempt = writeAt(dir, line, now, tier)
            if (attempt.written) return attempt
        }
        ioErrorCount.incrementAndGet()
        lastIoError = "${attempt.reason} dir=${dir.absolutePath} tier=${tier.name.lowercase()}"
        safeLog { Log.e(TAG, "LOGFILES_WRITE_ERROR $lastIoError (строка НЕ сохранена)") }
        return attempt
    }

    /**
     * Запись в конкретный каталог с ротацией по размеру.
     *
     * Ротация — по ЦЕЛЫМ файлам: текущий закрывается, открывается `<base>.<n>.log`. Строки не режутся.
     * Если места нет — честный отказ (`space:…`), а не молчаливая потеря.
     */
    private fun writeAt(dir: File, line: String, nowMs: Long, tier: Tier): Result {
        if (!dir.isDirectory && !dir.mkdirs()) return Result.fail("no_dir:${dir.absolutePath}")
        val active = activeFile(dir, nowMs, tier) ?: return Result.fail("no_active_file")
        // Ротация ДО записи, чтобы ни одна строка не потерялась.
        val rotate = active.length() + line.length + 1 > tier.maxFileBytes
        if (rotate) {
            val next = nextPart(dir, nowMs, tier)
            if (next == null) {
                droppedCount.incrementAndGet()
                return Result.fail("limit:parts_${tier.maxFiles}")
            }
            rotateCount.incrementAndGet()
            pathRef = next.absolutePath
            // Факт ротации обнаружим в НОВОМ файле (и независимо в logcat).
            val mark = "[${Instant.now()}] [ROTATE] [CENTRAL_LogFiles] LOG_ROTATE tier=" +
                "${tier.name.lowercase()} closed=${active.name} next=${next.name} " +
                "reason=size limit_bytes=${tier.maxFileBytes} parts_max=${tier.maxFiles} " +
                "note=\"ротация целыми файлами: строки не обрезаются; самые старые части " +
                "удаляются по лимиту tier\""
            runCatching { write(next, mark) }
            safeLog { Log.i(TAG, "LOG_ROTATE tier=${tier.name.lowercase()} closed=${active.name} next=${next.name}") }
            return appendRaw(next, line)
        }
        return appendRaw(active, line)
    }

    private fun appendRaw(target: File, line: String): Result = runCatching {
        write(target, line)
        if (pathRef != target.absolutePath) pathRef = target.absolutePath
        Result.ok(target)
    }.getOrElse { t ->
        Result.fail("io:${t.javaClass.simpleName}:${t.message?.take(60) ?: ""}")
    }

    /** Базовое (индекс 0) имя части суток для tier. */
    private fun baseName(tier: Tier, nowMs: Long): String = when (tier) {
        Tier.MAIN -> fileNameFor(nowMs)
        Tier.VERBOSE -> verboseNameFor(nowMs)
    }

    /**
     * Индекс части: 0 — базовый файл, `n` — `<base>.<n>.log`.
     *
     * Разбор строгий (число после базового имени), поэтому индекс ротации нельзя спутать
     * с датой в имени.
     */
    private fun partIndex(name: String, base: String): Int? {
        if (name == base) return 0
        val stem = base.removeSuffix(SUFFIX)
        if (!name.startsWith("$stem.") || !name.endsWith(SUFFIX)) return null
        val mid = name.substring(stem.length + 1, name.length - SUFFIX.length)
        return mid.toIntOrNull()
    }

    /** Существующие части ОДНИХ суток, по возрастанию индекса. */
    private fun tierParts(dir: File, tier: Tier, nowMs: Long): List<Pair<Int, File>> {
        val base = baseName(tier, nowMs)
        return partsOf(dir, tier)
            .mapNotNull { f -> partIndex(f.name, base)?.let { idx -> idx to f } }
            .sortedBy { it.first }
    }

    /**
     * Активный (самый НОВЫЙ по индексу) файл части суток; при переполнении вернёт его же —
     * ротация выше. Сортировка именно по индексу: раньше брался «последний по имени», из-за
     * чего базовый файл снова становился активным после ротации и почти каждая строка
     * открывала новую часть.
     */
    private fun activeFile(dir: File, nowMs: Long, tier: Tier): File? {
        val parts = tierParts(dir, tier, nowMs)
        if (parts.isEmpty()) return File(dir, baseName(tier, nowMs))
        return parts.last().second
    }

    /**
     * Следующая свободная часть суток; `null` — ротация невозможна (нет каталога/места).
     *
     * Всего частей на сутки не больше `tier.maxFiles` (базовый файл + `maxFiles-1` индексов).
     * Если имена исчерпаны — удаляется самая старая часть ЭТОГО ЖЕ tier (факт и имя попадают
     * в лог), а если места меньше запаса — честный отказ.
     */
    private fun nextPart(dir: File, nowMs: Long, tier: Tier): File? {
        val base = baseName(tier, nowMs)
        val used = tierParts(dir, tier, nowMs).map { it.first }.toSet()
        for (n in 1 until tier.maxFiles) {
            if (n !in used) return File(dir, rotatedName(base, n))
        }
        val oldest = tierParts(dir, tier, nowMs).minByOrNull { it.second.lastModified() }?.second
            ?: return null
        val space = freeSpace(dir)
        if (space != null && space < FREE_SPACE_MARGIN) {
            safeLog { Log.w(TAG, "LOG_ROTATE refused reason=free_space space=$space margin=$FREE_SPACE_MARGIN") }
            return null
        }
        if (runCatching { oldest.delete() }.getOrDefault(false)) {
            rotateCount.incrementAndGet()
            safeLog { Log.w(TAG, "LOG_ROTATE removed=${oldest.name} reason=parts_limit tier=${tier.name.lowercase()}") }
            return File(dir, rotatedName(base, tier.maxFiles - 1))
        }
        return null
    }

    /**
     * Принудительная ротация активного файла (iter42 §2.2): вызывается, когда общий лимит
     * скорости/объёма достигнут, чтобы НЕ выбрасывать полезные строки.
     *
     * Файл не удаляется и не обрезается: закрывается текущий, открывается следующая часть.
     * `false` — ротация невозможна (нет каталога/места/лимит частей); вызывающий обязан честно
     * сообщить об отказе, а не выдавать строки за записанные.
     */
    fun rotateNow(context: Context, reason: String = "budget"): Boolean {
        val app = context.applicationContext
        val dir = dirRef ?: dirFor(app).also { dirRef = it }
        val now = System.currentTimeMillis()
        val next = nextPart(dir, now, Tier.MAIN) ?: return false
        rotateCount.incrementAndGet()
        pathRef = next.absolutePath
        val mark = "[${Instant.now()}] [ROTATE] [CENTRAL_LogFiles] LOG_ROTATE tier=main " +
            "next=${next.name} reason=$reason limit_bytes=$MAX_FILE_BYTES parts_max=$MAX_FILES_PER_DAY"
        runCatching { write(next, mark) }
        safeLog { Log.i(TAG, "LOG_ROTATE reason=$reason next=${next.name}") }
        return true
    }

    /** Дописать строку в КОНКРЕТНЫЙ файл (используется ротацией и тестами). */
    internal fun appendToFile(target: File, line: String): Result = appendRaw(target, line)

    /**
     * Запись в КОНКРЕТНЫЙ каталог с подтверждением (production-писатель, используется тестами):
     * имя `voiceapp3-DD.MM.YYYY.log`, append+flush, ротация по размеру.
     */
    internal fun appendToDir(dir: File, line: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        writeAt(dir, line, nowMs, Tier.MAIN).written

    /** То же, но с точным файлом и tier — для тестов реального пути «сборщик → файл». */
    internal fun appendTierForTest(dir: File, line: String, tier: Tier,
                                   nowMs: Long = System.currentTimeMillis()): Result =
        writeAt(dir, line, nowMs, tier)

    /**
     * Служебный logcat-вызов из самого писателя: он НЕ должен ломать запись и не должен
     * порождать рекурсию логирования. Ошибка logcat'а гасится здесь и не влияет на [Result].
     */
    private inline fun safeLog(block: () -> Unit) {
        runCatching(block)
    }

    private fun write(target: File, line: String) {
        FileWriter(target, true).use { w ->
            w.append(line)
            w.append('\n')
            w.flush()
        }
    }
}
