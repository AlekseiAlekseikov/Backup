package com.example.voiceapp3.tools

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Датированный лог приложения — **ротация целыми закрытыми файлами** (iter42 §2, доработано iter43 §1).
 *
 * Правила, которые здесь жёстко зафиксированы (iter43):
 *
 *  1. **Строки не режутся.** Ограничение объёма — это ЗАКРЫТИЕ текущей части и открытие новой,
 *     а не отбрасывание строк по бюджету. Единственный случай потери — документированное удаление
 *     ЦЕЛОГО закрытого файла (квота места/количество частей) или физическая невозможность записи.
 *  2. **Уникальная возрастающая нумерация частей.** Индекс части — монотонно растущее число на
 *     каждый tier и каждую дату: `voiceapp3-DD.MM.YYYY.N.log` (N ≥ 1). Номер НИКОГДА не
 *     переиспользуется в пределах суток, диапазон номеров не ограничен — ограничено только
 *     КОЛИЧЕСТВО одновременно существующих частей ([Tier.maxFiles]) и суммарные байты.
 *     Активная часть — с максимальным номером; она никогда не удаляется ротацией.
 *  3. **Только свои файлы.** Имя разбирается строго (`voiceapp3-` / `voiceapp3-verbose-`,
 *     `DD.MM.YYYY`, опциональный `.N`, `.log`). Файл `voiceapp3-important.log` или
 *     `voiceapp3-12.11.2026.bak.log` НЕ считается нашим и не удаляется никогда.
 *  4. **Критическая секция.** Выбор файла, проверка места, ротация, удаление старых частей и
 *     запись строки выполняются под общим мьютексом (файл-лок + JVM-лок), поэтому конкурентные
 *     писатели не могут одновременно решить, что они пишут в разные части.
 *  5. **Подтверждение ≠ гарантия.** `Result.written` означает «строка передана файловой системе и
 *     поток сброшен (`flush` + `FileDescriptor.sync` — на Android это fsync)». Это защищает от
 *     потери строки при смерти процесса; это НЕ гарантия при обрыве питания/сбое железа.
 *  6. **Фоллбэк по факту.** Если внешний каталог недоступен, запись уходит во внутренний, и
 *     `Result.file` содержит ФАКТИЧЕСКИЙ файл. Неудачный каталог запоминается, чтобы не платить
 *     за него каждую строку, но периодически пробуется снова.
 */
object LogFiles {

    private const val TAG = "CENTRAL_LogFiles"

    internal const val PREFIX = "voiceapp3-"
    internal const val VERBOSE_PREFIX = "voiceapp3-verbose-"
    internal const val SUFFIX = ".log"

    /** Период хранения (сутки): старше — удаляется. */
    internal const val RETENTION_DAYS = 14L

    /** Лимит ОДНОГО файла: после него — ротация (закрытие части), а не обрезка строк. */
    internal const val MAX_FILE_BYTES = 8L * 1024 * 1024          // 8 МиБ

    /** Сколько частей ОДНОЙ даты держим одновременно (главное ограничение, не «номера»). */
    internal const val MAX_FILES_PER_DAY = 6

    /** Суммарный лимит основного tier по всем датам. */
    internal const val MAIN_TIER_BYTES = 64L * 1024 * 1024         // 64 МиБ

    /** Лимит одного файла verbose-tier и сколько частей держим. */
    internal const val VERBOSE_FILE_BYTES = 4L * 1024 * 1024       // 4 МиБ
    internal const val MAX_VERBOSE_FILES = 4                        // ≤ 16 МиБ

    /** Ротацию не начинаем, если свободного места меньше этого запаса. */
    internal const val FREE_SPACE_MARGIN = 8L * 1024 * 1024         // 8 МиБ

    /** Сколько частей (по всем датам сразу) может существовать на диске: защита от «лавины». */
    internal const val MAX_PARTS_TOTAL = MAX_FILES_PER_DAY * 8      // 48 файлов ≈ 8 суток по 6

    /**
     * Один блок ниже этого размера в байтах физически не записать (нужно ≥ 1 байт).
     * Строка больше [MAX_LINE_BYTES] разбивается на несколько строк с маркером `LOG_LINE_SPLIT`.
     */
    internal const val MAX_LINE_BYTES = 256 * 1024

    /** §2.4 iter44: ретеншн во время работы — не чаще раза в 10 минут на каталог/tier. */
    internal const val RUNTIME_PRUNE_MS = 10 * 60_000L

    /** Уровни (tier): полезный журнал и шумные низкоуровневые трассы — раздельно. */
    enum class Tier(val prefix: String, val maxFileBytes: Long, val maxFiles: Int) {
        MAIN(PREFIX, MAX_FILE_BYTES, MAX_FILES_PER_DAY),
        VERBOSE(VERBOSE_PREFIX, VERBOSE_FILE_BYTES, MAX_VERBOSE_FILES)
    }

    /**
     * Итог записи ОДНОЙ строки.
     *
     * @param written запись подтверждена (файл дописан и сброшен, включая фоллбэк)
     * @param file    ТОЧНЫЙ файл, куда строка попала (пусто, если не попала)
     * @param reason  причина отказа (`io:…`, `space:…`, `no_dir`, …) — уходит в независимый канал
     * @param rotated закрытая часть, если запись вызвала ротацию (`""` — ротации не было)
     */
    data class Result(
        val written: Boolean,
        val file: String = "",
        val reason: String = "",
        val rotated: String = ""
    ) {
        companion object {
            fun fail(why: String) = Result(false, "", why)
            fun ok(file: File, rotated: String = "") = Result(true, file.absolutePath, "", rotated)
        }
    }

    private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.US)

    @Volatile private var dirRef: File? = null
    @Volatile private var pathRef: String = "(not initialised yet)"

    // ─────────────────────────── имена и разбор ───────────────────────────

    /** Имя файла строго по образцу владельца: `voiceapp3-DD.MM.YYYY.log`. */
    internal fun fileNameOf(date: LocalDate): String = PREFIX + date.format(FMT) + SUFFIX

    internal fun fileNameFor(nowMs: Long): String =
        fileNameOf(Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate())

    internal fun verboseNameFor(nowMs: Long): String =
        VERBOSE_PREFIX + Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())
            .toLocalDate().format(FMT) + SUFFIX

    /** Имя файла части ротации: `<base>.<n>.log`. */
    internal fun rotatedName(base: String, n: Int): String =
        base.removeSuffix(SUFFIX) + "." + n + SUFFIX

    private val DATE_RE = Regex("\\d{2}\\.\\d{2}\\.\\d{4}")

    /**
     * Строгий разбор имени СВОЕГО файла.
     *
     * @return `(tier, дата, номер части)`; номер `0` — базовая часть (`…DD.MM.YYYY.log`),
     *   `≥1` — `…DD.MM.YYYY.N.log`. `null` — имя не нашего формата (такие файлы не трогаем).
     */
    internal fun parseOwn(name: String): Triple<Tier, LocalDate, Int>? {
        if (!name.endsWith(SUFFIX)) return null
        val stem = name.removeSuffix(SUFFIX)
        val (tier, body) = when {
            stem.startsWith(VERBOSE_PREFIX) -> Tier.VERBOSE to stem.removePrefix(VERBOSE_PREFIX)
            stem.startsWith(PREFIX) -> Tier.MAIN to stem.removePrefix(PREFIX)
            else -> return null
        }
        val parts = body.split('.')
        // date = 3 части (DD, MM, YYYY); номер части — РОВНО одна дополнительная числовая часть.
        if (parts.size != 3 && parts.size != 4) return null
        if (!DATE_RE.matches(parts.subList(0, 3).joinToString("."))) return null
        val n = if (parts.size == 4) {
            // §2.4 iter44: раньше принимались только 1–3 цифры, а генерация имён номер не
            // ограничивала: `.1000` переставал распознаваться как СВОЯ часть (файл «исчезал» из
            // учёта, а следующая запись могла открыть старую часть заново). Теперь до 9 цифр.
            if (!parts[3].matches(Regex("\\d{1,9}"))) return null
            parts[3].toIntOrNull() ?: return null
        } else 0
        if (parts.size == 4 && n < 1) return null      // `…2026.0.log` — не наш формат
        val date = runCatching {
            LocalDate.parse(parts.subList(0, 3).joinToString("."), FMT)
        }.getOrNull() ?: return null
        return Triple(tier, date, n)
    }

    /** Дата из имени файла; null — если имя не нашего формата (чужие файлы не трогаем). */
    internal fun parseDate(name: String): LocalDate? = parseOwn(name)?.second

    /** Просроченные имена: старше [retentionDays] суток относительно [today]. */
    internal fun expired(names: List<String>, today: LocalDate, retentionDays: Long = RETENTION_DAYS): List<String> {
        val edge = today.minusDays(retentionDays)
        return names.filter { n -> parseDate(n)?.isBefore(edge) == true }
    }

    internal fun ensureDir(d: File): File {
        if (!d.exists()) d.mkdirs()
        return d
    }

    // ─────────────────────────── каталоги ───────────────────────────

    /**
     * Каталог логов: внешний `getExternalFilesDir(null)/logs`, при недоступности — `filesDir/logs`.
     * Проверяется РЕАЛЬНОЙ пробной записью ([writable]), а не только `!= null`.
     */
    fun dirFor(context: Context): File {
        val app = context.applicationContext
        val ext = runCatching { app.getExternalFilesDir(null) }.getOrNull()
        if (ext != null) {
            val d = ensureDir(File(ext, "logs"))
            if (writable(d)) return d
        }
        return ensureDir(File(app.filesDir, "logs"))
    }

    /** Каталог внутреннего фоллбэка (всегда наш `filesDir/logs`). */
    private fun internalDir(context: Context): File =
        ensureDir(File(context.applicationContext.filesDir, "logs"))

    /** Реальная проба записи: создать/дописать/удалить пробный файл. */
    internal fun writable(dir: File): Boolean = runCatching {
        if (!dir.isDirectory) return false
        val probe = File(dir, ".probe-" + System.nanoTime())
        FileOutputStream(probe, true).use { it.write(0x0A) }
        probe.delete()
        true
    }.getOrDefault(false)

    /** Свободное место в каталоге (байт); `null` — узнать нельзя (0 — узнали: места нет). */
    internal fun freeSpace(dir: File): Long? {
        // Несуществующий/недоступный каталог: `usableSpace` штатно отдаёт 0, что читалось бы как
        // «места нет». Поэтому сначала проверяем сам факт каталога, иначе — честное `unknown`.
        if (!dir.isDirectory) return null
        val v = runCatching { dir.usableSpace }.getOrNull() ?: return null
        return v
    }

    // ─────────────────────────── перечисление частей ───────────────────────────

    /**
     * Части tier в каталоге, самые новые — первыми. Включает только СВОИ файлы строгого формата.
     * Файлы других tier, чужие `.log`, `.bak`, `voiceapp3-important.log` — не попадают.
     */
    internal fun partsOf(dir: File, tier: Tier): List<File> =
        (runCatching { dir.listFiles() }.getOrNull()?.toList() ?: emptyList())
            .filter { it.isFile }
            .filter { f -> parseOwn(f.name)?.let { it.first == tier } == true }
            .sortedWith(compareByDescending<File> { parseOwn(it.name)!!.second }
                .thenByDescending { parseOwn(it.name)!!.third })

    /** Части ОДНОЙ даты и tier, по возрастанию номера. */
    private fun partsOfDate(dir: File, tier: Tier, date: LocalDate): List<Pair<Int, File>> =
        partsOf(dir, tier)
            .mapNotNull { f -> parseOwn(f.name)?.let { if (it.second == date) it.third to f else null } }
            .sortedBy { it.first }

    private fun baseName(tier: Tier, date: LocalDate): String =
        if (tier == Tier.MAIN) fileNameOf(date) else VERBOSE_PREFIX + date.format(FMT) + SUFFIX

    // ─────────────────────────── квоты и удаление ───────────────────────────

    /**
     * Удаление ЦЕЛЫХ ЗАКРЫТЫХ частей по явной политике. Возвращает имена удалённых.
     *
     * Порядок: (1) просроченные по дате; (2) превышение суммарного байтового лимита tier;
     * (3) превышение [MAX_PARTS_TOTAL]. Активная часть ([activeName]) никогда не удаляется.
     * Частичные отказы `delete` не считаются удачей: имя попадает в отчёт только при подтверждении.
     */
    internal fun pruneTier(
        dir: File,
        nowMs: Long,
        tier: Tier = Tier.MAIN,
        activeName: String? = null
    ): List<String> {
        val today = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val names = runCatching { dir.list() }.getOrNull()?.toList() ?: return emptyList()
        val removed = ArrayList<String>(4)

        for (n in expired(names, today)) {
            if (n == activeName) continue
            if (runCatching { File(dir, n).delete() }.getOrDefault(false)) removed += n
        }

        // Суммарный лимит tier: удаляем самые старые ЗАКРЫТЫЕ части сверх лимита.
        val parts = partsOf(dir, tier).sortedWith(compareBy<File> { parseOwn(it.name)!!.second }
            .thenBy { parseOwn(it.name)!!.third })
        val cap = when (tier) {
            Tier.MAIN -> MAIN_TIER_BYTES
            Tier.VERBOSE -> VERBOSE_FILE_BYTES * MAX_VERBOSE_FILES
        }
        var total = parts.sumOf { runCatching { it.length() }.getOrDefault(0L) }
        var i = 0
        while (total > cap && i < parts.size) {
            val f = parts[i]
            i++
            if (f.name == activeName) continue
            val len = runCatching { f.length() }.getOrDefault(0L)
            if (runCatching { f.delete() }.getOrDefault(false)) {
                total -= len
                removed += f.name
            }
        }

        // Количество частей по всем датам: не даём «лавине» файлов съесть место.
        val after = partsOf(dir, tier)
        if (after.size > MAX_PARTS_TOTAL) {
            val victims = after.drop(MAX_PARTS_TOTAL)
                .filter { it.name != activeName }
                .sortedWith(compareBy<File> { parseOwn(it.name)!!.second }
                    .thenBy { parseOwn(it.name)!!.third })
            for (f in victims) {
                if (runCatching { f.delete() }.getOrDefault(false)) removed += f.name
            }
        }
        return removed
    }

    /** Старый API (число удалённых) — оставлен для существующих тестов/вызовов. */
    internal fun pruneCount(dir: File, nowMs: Long, tier: Tier = Tier.MAIN): Int =
        pruneTier(dir, nowMs, tier).size

    /** Инициализация при старте: каталог + retention. Возвращает путь каталога. */
    fun prepare(context: Context): String {
        val d = dirFor(context)
        dirRef = d
        val now = System.currentTimeMillis()
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val activeMain = activeName(d, Tier.MAIN, today)
        val activeVerbose = activeName(d, Tier.VERBOSE, today)
        val removed = runCatching { pruneTier(d, now, Tier.MAIN, activeMain) }.getOrDefault(emptyList())
        val removedVerbose = runCatching { pruneTier(d, now, Tier.VERBOSE, activeVerbose) }
            .getOrDefault(emptyList())
        pathRef = File(d, activeMain).absolutePath
        report("LOG_RETENTION dir=${d.absolutePath} days=$RETENTION_DAYS " +
            "removed=${removed.size} removed_verbose=${removedVerbose.size} " +
            "names=«${removed.take(6).joinToString(",")}» " +
            "limits main=${MAX_FILE_BYTES}B×$MAX_FILES_PER_DAY parts cap=$MAIN_TIER_BYTES " +
            "parts_total_max=$MAX_PARTS_TOTAL " +
            "verbose=${VERBOSE_FILE_BYTES}B×$MAX_VERBOSE_FILES " +
            "(удаление — только целых закрытых частей своего формата; причина указана здесь)")
        // §1.3 iter43: лимит виден ВМЕСТЕ с фактическим объёмом и свободным местом, а не сам по себе.
        runCatching { reportTotals(context, "prepare") }
        return d.absolutePath
    }

    /** Имя активной (с максимальным номером) части даты; если частей нет — базовая. */
    internal fun activeName(dir: File, tier: Tier, date: LocalDate): String {
        val parts = partsOfDate(dir, tier, date)
        if (parts.isEmpty()) return baseName(tier, date)
        val n = parts.last().first
        return if (n == 0) baseName(tier, date) else rotatedName(baseName(tier, date), n)
    }

    fun currentPath(): String = pathRef

    // ─────────────────────────── счётчики ───────────────────────────

    private val ioErrorCount = java.util.concurrent.atomic.AtomicLong(0)
    private val rotateCount = java.util.concurrent.atomic.AtomicLong(0)
    private val droppedCount = java.util.concurrent.atomic.AtomicLong(0)
    private val deletedCount = java.util.concurrent.atomic.AtomicLong(0)
    private val splitCount = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile private var lastIoError: String = ""

    fun ioErrors(): Long = ioErrorCount.get()
    fun rotations(): Long = rotateCount.get()
    fun dropped(): Long = droppedCount.get()

    /** Сколько ЧАСТЕЙ удалено по политике (видно и в `LOG_RETENTION`/`LOG_ROTATE`). */
    fun deleted(): Long = deletedCount.get()

    /** Сколько строк было разбито из-за превышения [MAX_LINE_BYTES]. */
    fun splits(): Long = splitCount.get()

    fun lastIoError(): String = lastIoError

    internal fun resetCountersForTest() {
        ioErrorCount.set(0); rotateCount.set(0); droppedCount.set(0)
        deletedCount.set(0); splitCount.set(0); lastIoError = ""
        dirRef = null; pathRef = "(not initialised yet)"
    }

    // ─────────────────────────── критическая секция ───────────────────────────

    private val jvmLock = Any()

    /**
     * Выполнить [block] как ОДНУ критическую операцию: JVM-лок процесса + файл-лок каталога
     * (`dir/.logfiles.lock`, `FileChannel.lock`) для координации между процессами.
     *
     * Файл-лок берётся ТОЛЬКО для координации; если файловая система его не поддерживает,
     * работа продолжается под JVM-локом, а факт пишется в счётчик (не молча).
     */
    private fun <T> critical(dir: File, block: () -> T): T = synchronized(jvmLock) {
        var lockChannel: java.nio.channels.FileChannel? = null
        var fileLock: java.nio.channels.FileLock? = null
        try {
            runCatching {
                val lf = File(dir, ".logfiles.lock")
                lockChannel = java.io.RandomAccessFile(lf, "rw").channel
                fileLock = lockChannel!!.lock()
            }.onFailure { e ->
                // Не молчим: координация между процессами недоступна.
                lastIoError = "lock_unavailable:${e.javaClass.simpleName}"
            }
            block()
        } finally {
            runCatching { fileLock?.release() }
            runCatching { lockChannel?.close() }
        }
    }

    // ─────────────────────────── запись ───────────────────────────

    fun append(context: Context, line: String): Result = appendTier(context, line, Tier.MAIN)

    fun appendVerbose(context: Context, line: String): Result = appendTier(context, line, Tier.VERBOSE)

    /**
     * Запись строки. Внешний каталог → при отказе один раз пробует внутренний.
     * `Result.file` — ФАКТИЧЕСКИЙ файл.
     */
    private fun appendTier(context: Context, line: String, tier: Tier): Result {
        val app = context.applicationContext
        val primary = dirRef ?: runCatching { dirFor(app) }.getOrElse { internalDir(app) }
            .also { dirRef = it }
        val now = System.currentTimeMillis()

        var res = writeAtLocked(primary, line, now, tier)
        if (res.written) return res

        // Один раз пробуем внутренний каталог; факт фоллбэка виден в reason/логе.
        val internal = internalDir(app)
        if (internal.absolutePath != primary.absolutePath) {
            report("LOG_FALLBACK from=«${primary.absolutePath}» to=«${internal.absolutePath}» " +
                "reason=${res.reason} (append failed (internal too) — повтор во внутреннем каталоге; " +
                "фактический файл возвращается в Result.file)")
            res = writeAtLocked(internal, line, now, tier)
            if (res.written) {
                dirRef = internal
                return res
            }
        }
        ioErrorCount.incrementAndGet()
        lastIoError = "${res.reason} dir=«${primary.absolutePath}» tier=${tier.name.lowercase()}"
        report("LOGFILES_WRITE_ERROR ${lastIoError} written=false " +
            "(строка НЕ сохранена; это не «тихий trim», а физический отказ записи)")
        return res
    }

    /**
     * Запись с критической секцией: выбор активной части, проверка места, ротация, запись.
     */
    private fun writeAtLocked(dir: File, line: String, nowMs: Long, tier: Tier): Result {
        if (!dir.isDirectory) {
            val made = runCatching { dir.mkdirs() }.getOrDefault(false) || dir.isDirectory
            if (!made) {
                // §2.4 iter44: отказ каталога обязан быть виден независимо, а не только в Result.
                // Сам счётчик ошибок ведёт `appendTier` (иначе одно событие считалось бы дважды).
                lastIoError = "no_dir:${dir.absolutePath}"
                report("LOGFILES_WRITE_ERROR no_dir dir=«${dir.absolutePath}» " +
                    "tier=${tier.name.lowercase()} written=false " +
                    "(каталог не создан/не является каталогом: строка НЕ сохранена)")
                return Result.fail("no_dir:${dir.absolutePath}")
            }
        }
        return critical(dir) { writeAt(dir, line, nowMs, tier) }
    }

    /**
     * Запись в конкретный каталог с ротацией по размеру (вызывается под критической секцией —
     * см. [writeAtLocked]; тестовые входы используют её же через [appendTierForTest]).
     */
    private fun writeAt(dir: File, line: String, nowMs: Long, tier: Tier): Result {
        if (!dir.isDirectory && !dir.mkdirs()) return Result.fail("no_dir:${dir.absolutePath}")

        // Слишком длинная строка: пишем частями с явным маркером (данные не теряются).
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_LINE_BYTES) return writeSplit(dir, line, nowMs, tier)

        val date = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val base = baseName(tier, date)
        val parts = partsOfDate(dir, tier, date)
        var active = if (parts.isEmpty()) File(dir, base)
        else File(dir, if (parts.last().first == 0) base else rotatedName(base, parts.last().first))

        var rotated = ""
        var pendingPrune: Pair<Int, List<Pair<Int, File>>>? = null
        val need = (if (active.isFile) active.length() else 0L) + bytes.size + 1L
        if (active.isFile && need > tier.maxFileBytes) {
            // Закрываем текущую часть и открываем следующую по НЕПРЕРЫВНОЙ нумерации.
            val nextN = (parts.last().first) + 1
            // §2.4 iter44: ПОРЯДОК ИЗМЕНЁН. Раньше старая часть удалялась ДО попытки записи в
            // новую: при отказе записи терялись и старая часть, и строка. Теперь лимит частей
            // применяется ПОСЛЕ успешной записи (pendingPrune).
            pendingPrune = nextN to parts
            // Место: 0 — знаем, что места нет; null — «узнать нельзя» (НЕ «места достаточно»).
            val free = freeSpace(dir)
            if (free != null && free < FREE_SPACE_MARGIN) {
                droppedCount.incrementAndGet()
                val why = if (free <= 0L) "space:zero" else "space:${free}<${FREE_SPACE_MARGIN}"
                report("LOGFILES_WRITE_ERROR $why dir=«${dir.absolutePath}» tier=${tier.name.lowercase()} " +
                    (if (free <= 0L) "(свободного места нет — запись НЕ выполнена)"
                     else "(меньше запаса — запись НЕ выполнена)"))
                report("LOG_ROTATE skipped=1 reason=$why dir=«${dir.absolutePath}» " +
                    "(старая часть НЕ удалена: новая запись не выполнена)")
                return Result.fail(why)
            }
            if (free == null) {
                report("LOGFILES_SPACE_UNKNOWN dir=«${dir.absolutePath}» tier=${tier.name.lowercase()} " +
                    "action=write_attempted (место неизвестно: запись пробуем, но гарантий нет)")
            }
            rotated = active.name
            active = File(dir, rotatedName(base, nextN))
            rotateCount.incrementAndGet()
        }

        val mark = if (rotated.isNotEmpty()) {
            "[${Instant.now()}] [ROTATE] [CENTRAL_LogFiles] LOG_ROTATE tier=${tier.name.lowercase()} " +
                "date=${date.format(FMT)} closed=$rotated next=${active.name} " +
                "limit_bytes=${tier.maxFileBytes} parts_max=${tier.maxFiles} " +
                "note=\"ротация целыми файлами: строки не обрезаются\""
        } else null

        return try {
            val existed = active.isFile
            FileOutputStream(active, true).use { out ->
                if (!existed && mark != null) {
                    out.write((mark + "\n").toByteArray(Charsets.UTF_8))
                }
                out.write(bytes)
                out.write('\n'.code)
                out.flush()
                runCatching { out.fd.sync() }     // fsync: защита от смерти процесса, не от сбоя питания
            }
            if (pathRef != active.absolutePath) pathRef = active.absolutePath
            // Новая часть записана — только ТЕПЕРЬ можно удалять самые старые закрытые части.
            pendingPrune?.let { (nextN, parts0) -> enforcePartsLimit(dir, tier, date, parts0, nextN) }
            // §2.4 iter44: ретеншн проверяется не только в `prepare`, но и ВО ВРЕМЯ работы.
            maybeRuntimePrune(dir, nowMs, tier)
            Result.ok(active, rotated)
        } catch (t: Throwable) {
            Result.fail("io:${t.javaClass.simpleName}:${t.message?.take(60) ?: ""}")
        }
    }

    /** Когда последний раз выполнялся ретеншн по каталогу — «во время работы». */
    private val lastRuntimePrune = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * §2.4 iter44: ретеншн ВО ВРЕМЯ РАБОТЫ, не чаще раза в [RUNTIME_PRUNE_MS] на каталог/tier.
     * Долгоживущий процесс иначе держал бы просроченные части до следующего `prepare`.
     * Частичные отказы `delete` не считаются удачей — см. `pruneTier`.
     */
    private fun maybeRuntimePrune(dir: File, nowMs: Long, tier: Tier) {
        val key = dir.absolutePath + "#" + tier.name
        val last = lastRuntimePrune[key]
        if (last != null && nowMs - last < RUNTIME_PRUNE_MS) return
        lastRuntimePrune[key] = nowMs
        val date = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val active = activeName(dir, tier, date)
        val removed = runCatching { pruneTier(dir, nowMs, tier, active) }.getOrDefault(emptyList())
        if (removed.isNotEmpty()) {
            deletedCount.addAndGet(removed.size.toLong())
            report("LOG_RETENTION_RUNTIME tier=${tier.name.lowercase()} dir=«${dir.absolutePath}» " +
                "removed=${removed.size} names=«${removed.take(6).joinToString(",")}» " +
                "reason=retention_while_running active_kept=«$active»")
        }
    }

    /**
     * Ограничение КОЛИЧЕСТВА существующих частей одной даты: если новых частей будет больше
     * [Tier.maxFiles], удаляется самая старая закрытая часть. Номер новой части при этом
     * продолжает расти (переиспользования имён нет), поэтому «активная» всегда однозначна.
     */
    private fun enforcePartsLimit(
        dir: File,
        tier: Tier,
        date: LocalDate,
        parts: List<Pair<Int, File>>,
        nextN: Int
    ) {
        val existing = parts.size
        if (existing + 1 <= tier.maxFiles) return
        // Удаляем самые старые закрытые части, пока не влезаем.
        var needRemove = existing + 1 - tier.maxFiles
        for ((_, f) in parts) {
            if (needRemove <= 0) break
            val len = runCatching { f.length() }.getOrDefault(0L)
            if (runCatching { f.delete() }.getOrDefault(false)) {
                needRemove--
                deletedCount.incrementAndGet()
                report("LOG_ROTATE removed=${f.name} reason=parts_limit tier=${tier.name.lowercase()} " +
                    "date=${date.format(FMT)} bytes=$len next_n=$nextN max_parts=${tier.maxFiles} " +
                    "(удалена ЦЕЛАЯ закрытая часть; строки внутри неё больше недоступны)")
            } else {
                report("LOG_ROTATE removed=FAILED name=${f.name} reason=delete_failed " +
                    "tier=${tier.name.lowercase()} (часть не удалена; количество частей превысит лимит)")
                break
            }
        }
    }

    /** Разбиение очень длинной строки: данные сохраняются целиком, факт виден маркером. */
    private fun writeSplit(dir: File, line: String, nowMs: Long, tier: Tier): Result {
        val bytes = line.toByteArray(Charsets.UTF_8)
        // Кусок СТРОГО меньше лимита: иначе «кусок + маркер» снова превышает MAX_LINE_BYTES и
        // writeAt уходит в writeSplit по кругу (это дефект, найденный тестом в iter43).
        val chunkMax = MAX_LINE_BYTES - SPLIT_MARKER_RESERVE
        var offset = 0
        var n = 0
        val total = (bytes.size + chunkMax - 1) / chunkMax
        var written = 0
        val failedParts = ArrayList<Int>(2)
        var firstFail: Result? = null
        var lastOk: Result? = null
        while (offset < bytes.size) {
            val end = minOf(offset + chunkMax, bytes.size)
            // Не режем многобайтовый символ: проверяем БАЙТ НА ПОЗИЦИИ `cut` (продолжение ли он).
            // Проверка `cut-1` пропускала разрез ровно посередине символа (0xD1 | 0x8F), и в файле
            // появлялся U+FFFD — фактическая потеря байтов.
            var cut = end
            while (cut > offset && cut < bytes.size && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
            if (cut == offset) cut = end
            val part = String(bytes, offset, cut - offset, Charsets.UTF_8)
            n++
            // §2.3 iter44: результат КАЖДОЙ части. Раньше возвращался результат последней —
            // строка считалась записанной, даже если первая/средняя часть не записалась.
            val r = writeAt(dir, "${part}LOG_LINE_SPLIT n=$n/$total", nowMs, tier)
            splitCount.incrementAndGet()
            if (r.written) {
                written++
                lastOk = r
            } else {
                failedParts += n
                if (firstFail == null) firstFail = r
                report("LOG_LINE_SPLIT n=$n/$total bytes=${cut - offset} written=false " +
                    "reason=${r.reason} limit=$MAX_LINE_BYTES dir=«${dir.absolutePath}» " +
                    "(часть строки НЕ сохранена; исходная строка не считается записанной)")
            }
            offset = cut
        }
        return when {
            failedParts.isEmpty() && lastOk != null -> lastOk!!
            written == 0 -> Result.fail(
                "split_all_failed:" + (firstFail?.reason ?: "unknown") + ":parts=${failedParts.size}/$total"
            )
            else -> Result(
                false,
                lastOk?.file ?: "",
                "split_partial:parts=${failedParts.joinToString(",")}/$total:" +
                    (firstFail?.reason ?: "unknown"),
                lastOk?.rotated ?: ""
            )
        }
    }

/** Запас под маркер `LOG_LINE_SPLIT n=NN/NN` при разбиении слишком длинной строки. */
    private const val SPLIT_MARKER_RESERVE = 96

    /**
     * Принудительная ротация: закрыть активную часть и перейти к следующей.
     * Возвращает `true` ТОЛЬКО если новый файл действительно создан и в него подтверждена запись
     * маркера (rot|mark). Иначе `false` — и вызывающий обязан честно объявить отказ.
     */
    fun rotateNow(context: Context, reason: String = "budget"): Boolean {
        val app = context.applicationContext
        val dir = dirRef ?: runCatching { dirFor(app) }.getOrElse { internalDir(app) }
            .also { dirRef = it }
        val now = System.currentTimeMillis()
        return critical(dir) {
            val date = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
            val base = baseName(Tier.MAIN, date)
            val parts = partsOfDate(dir, Tier.MAIN, date)
            val nextN = (parts.lastOrNull()?.first ?: 0) + 1
            enforcePartsLimit(dir, Tier.MAIN, date, parts, nextN)
            val next = File(dir, rotatedName(base, nextN))
            val mark = "[${Instant.now()}] [ROTATE] [CENTRAL_LogFiles] LOG_ROTATE tier=main " +
                "date=${date.format(FMT)} next=${next.name} reason=$reason " +
                "limit_bytes=$MAX_FILE_BYTES parts_max=$MAX_FILES_PER_DAY " +
                "note=\"по требованию бюджета: закрыта предыдущая часть, строки не обрезаются\""
            val ok = try {
                val existed = next.isFile
                FileOutputStream(next, true).use { out ->
                    if (!existed) out.write((mark + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                    runCatching { out.fd.sync() }
                }
                next.isFile && next.length() > 0
            } catch (t: Throwable) {
                lastIoError = "rotate_io:${t.javaClass.simpleName}:${t.message?.take(60) ?: ""}"
                false
            }
            if (ok) {
                rotateCount.incrementAndGet()
                pathRef = next.absolutePath
                report("LOG_ROTATE reason=$reason next=${next.name} n=$nextN written=true")
            } else {
                report("LOG_ROTATE reason=$reason next=${next.name} n=$nextN written=false " +
                    "why=$lastIoError (ротация НЕ подтверждена)")
            }
            ok
        }
    }

    // ─────────────────── суммарный учёт: свой объём, чужой, место ───────────────────

    /**
     * Факты по ОДНОМУ каталогу. Важно: [mainBytes]+[verboseBytes] — СВОЙ объём (участвует
     * в лимитах), [legacyBytes]/[otherLogBytes] — файлы, которые мы НЕ считаем своими
     * (`voiceapp3.log` от прежнего писателя, `voiceapp3-important.log` и прочие `.log`).
     * Они не удаляются и не входят в квоту — но их байты занимают диск, поэтому показываются.
     */
    internal data class DirTotal(
        val dir: String,
        val mainParts: Int,
        val mainBytes: Long,
        val verboseParts: Int,
        val verboseBytes: Long,
        val legacyBytes: Long,
        val otherLogBytes: Long,
        val free: Long?,
        val writable: Boolean,
        val listed: Boolean
    ) {
        val ownBytes: Long get() = mainBytes + verboseBytes
        /** `known:123` / `zero` (места нет) / `unknown` (узнать нельзя). */
        val spaceFact: String get() = when {
            free == null -> "unknown"
            free <= 0L -> "zero"
            else -> "known:$free"
        }
        fun line(): String =
            "LOG_TOTALS dir=«$dir» main=$mainBytes B/$mainParts part(s) " +
                "verbose=$verboseBytes B/$verboseParts part(s) own_total=$ownBytes B " +
                "legacy=$legacyBytes B other_logs=$otherLogBytes B (ВНЕ квоты, не удаляются) " +
                "free=$spaceFact writable=$writable listed=$listed"
    }

    /**
     * Учёт по внешнему и внутреннему каталогам. Возвращает ПО ФАКТУ: чего нет/не читается —
     * `listed=false`, а не «ноль байт, значит пусто».
     */
    internal fun totals(context: Context): List<DirTotal> {
        val ext = runCatching { dirFor(context.applicationContext) }.getOrNull()
        val int = internalDir(context.applicationContext)
        val dirs = ArrayList<File>(2)
        if (ext != null) dirs += ext
        if (dirs.none { it.absolutePath == int.absolutePath }) dirs += int
        return totalsOf(dirs)
    }

    /**
     * Тот же учёт, но по ЯВНОМУ списку каталогов — вход для JVM-тестов (Context недоступен).
     * Логика ровно одна: никакой «тестовой» копии правил.
     */
    internal fun totalsOf(dirs: List<File>): List<DirTotal> = dirs.map { d ->
            val listed = runCatching { d.isDirectory && d.list() != null }.getOrDefault(false)
            val all = runCatching { d.listFiles()?.filter { it.isFile } ?: emptyList() }
                .getOrDefault(emptyList())
            val own = all.filter { parseOwn(it.name) != null }
            val main = own.filter { parseOwn(it.name)!!.first == Tier.MAIN }
            val verb = own.filter { parseOwn(it.name)!!.first == Tier.VERBOSE }
            val legacy = all.filter { it.name == LEGACY_NAME }
            val other = all.filter { it.name.endsWith(".log") && parseOwn(it.name) == null &&
                it.name != LEGACY_NAME }
            DirTotal(
                dir = d.absolutePath,
                mainParts = main.size,
                mainBytes = main.sumOf { runCatching { it.length() }.getOrDefault(0L) },
                verboseParts = verb.size,
                verboseBytes = verb.sumOf { runCatching { it.length() }.getOrDefault(0L) },
                legacyBytes = legacy.sumOf { runCatching { it.length() }.getOrDefault(0L) },
                otherLogBytes = other.sumOf { runCatching { it.length() }.getOrDefault(0L) },
                free = freeSpace(d),
                writable = writable(d),
                listed = listed
            )
        }

    /**
     * Пишет учёт в `report()` и возвращает строку-итог.
     *
     * Честная формулировка: [MAIN_TIER_BYTES] и [VERBOSE_FILE_BYTES]×[MAX_VERBOSE_FILES] — лимит
     * НА КАТАЛОГ и tier. Их сумма НЕ является абсолютным пределом: внешний и внутренний каталог
     * считаются отдельно, плюс вне квоты лежат `voiceapp3.log`/`voiceapp3-important.log`.
     */
    fun reportTotals(context: Context, why: String): String {
        val t = totals(context)
        for (d in t) report(d.line())
        val own = t.sumOf { it.ownBytes }
        val legacy = t.sumOf { it.legacyBytes } + t.sumOf { it.otherLogBytes }
        val unlisted = t.count { !it.listed }
        val sum = "LOG_TOTALS_SUM why=$why dirs=${t.size} own=$own B legacy_and_other=$legacy B " +
            "not_listed=$unlisted " +
            "caps=\"main=$MAIN_TIER_BYTES B + verbose=${VERBOSE_FILE_BYTES * MAX_VERBOSE_FILES} B " +
            "НА КАТАЛОГ: это не абсолютный предел, каталогов два\""
        report(sum)
        return sum
    }

    /** Имя файла прежнего писателя (`AppFileLogger`) — вне квоты и вне удаления. */
    internal const val LEGACY_NAME = "voiceapp3.log"

    // ─────────────────────────── тестовые/диагностические входы ───────────────────────────

    /** Дописать строку в КОНКРЕТНЫЙ файл (без ротации) — тонкий вход для тестов. */
    internal fun appendToFile(target: File, line: String): Result = runCatching {
        FileOutputStream(target, true).use { out ->
            out.write((line + "\n").toByteArray(Charsets.UTF_8)); out.flush()
            runCatching { out.fd.sync() }
        }
        Result.ok(target)
    }.getOrElse { t -> Result.fail("io:${t.javaClass.simpleName}") }

    /** Запись в КОНКРЕТНЫЙ каталог (production-писатель) — используется тестами. */
    internal fun appendToDir(dir: File, line: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        writeAtLocked(dir, line, nowMs, Tier.MAIN).written

    /** То же, но с точным файлом и tier — для тестов реального пути «сборщик → файл». */
    internal fun appendTierForTest(
        dir: File,
        line: String,
        tier: Tier,
        nowMs: Long = System.currentTimeMillis()
    ): Result = writeAtLocked(dir, line, nowMs, tier)

    /** Выполнить критическую секцию целиком (тесты конкуренции используют тот же путь). */
    internal fun <T> criticalForTest(dir: File, block: () -> T): T = critical(dir, block)

    /** Сброс состояния между тестами (каталог/счётчики). */
    internal fun resetForTest() = resetCountersForTest()

    /**
     * Независимое сообщение о фактах лога (ротация/удаление/отказ): logcat + внутренний файл-маркер.
     * Не пишет в датированный лог, чтобы не порождать рекурсию.
     */
    private fun report(line: String) {
        safeLog { Log.i(TAG, line) }
        lastFacts.addLast(line)
        while (lastFacts.size > 64) lastFacts.removeFirst()
    }

    private val lastFacts = java.util.ArrayDeque<String>(64)

    /** Последние факты журнала (для отчёта/тестов, без чтения logcat). */
    internal fun lastFacts(): List<String> = synchronized(lastFacts) { lastFacts.toList() }

    private inline fun safeLog(block: () -> Unit) {
        runCatching(block)
    }
}
