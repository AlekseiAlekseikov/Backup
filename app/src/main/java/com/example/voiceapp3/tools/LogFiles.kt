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
 * iter33p §4 — собственный лог приложения файлами по датам (требование владельца 09.11).
 *
 * Зачем: в прогоне 1.0.60 лог не сохранился — доказать поведение AVAS/JIT было нечем.
 * Теперь каждая строка, уходящая в logcat через [AppLog], дополнительно пишется в файл
 * `voiceapp3-DD.MM.YYYY.log` (день.месяц.год, как в `voiceapp3-10.11.2026.log`):
 *
 *  • каталог — каталог приложения: `getExternalFilesDir(null)/logs/`
 *    (`/sdcard/Android/data/com.example.voiceapp3/files/logs/`), при недоступном внешнем
 *    хранилище — внутренний `filesDir/logs/`;
 *  • один файл на сутки: в течение дня — append, при смене даты создаётся новый;
 *  • файлы старше [RETENTION_DAYS] суток удаляются при старте (чтобы не забивать `/data`);
 *  • любая ошибка записи — тихий фоллбэк в logcat, без падений и без влияния на остальное.
 */
object LogFiles {

    private const val TAG = "CENTRAL_LogFiles"

    internal const val PREFIX = "voiceapp3-"
    internal const val SUFFIX = ".log"
    internal const val RETENTION_DAYS = 14L

    private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.US)

    @Volatile private var dirRef: File? = null
    @Volatile private var pathRef: String = "(not initialised yet)"

    /** Имя файла строго по образцу владельца: `voiceapp3-DD.MM.YYYY.log`. */
    internal fun fileNameOf(date: LocalDate): String = PREFIX + date.format(FMT) + SUFFIX

    /** Имя файла для момента времени (мс). */
    internal fun fileNameFor(nowMs: Long): String =
        fileNameOf(Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate())

    /** Дата из имени файла; null — если имя не нашего формата (чужие файлы не трогаем). */
    internal fun parseDate(name: String): LocalDate? {
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return null
        val body = name.substring(PREFIX.length, name.length - SUFFIX.length)
        return runCatching { LocalDate.parse(body, FMT) }.getOrNull()
    }

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
     *
     * REVIEW iter33p: недостаточно проверить `getExternalFilesDir != null` — каталог может
     * существовать, но не приниматься (`mkdirs`/запись падают). Поэтому каталог проверяется
     * реальной пробной записью ([writable]).
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

    /** Удаление просроченных файлов в каталоге (не чаще одного раза за старт). */
    internal fun prune(dir: File, nowMs: Long): Int {
        val names = dir.list()?.toList() ?: return 0
        var removed = 0
        for (n in expired(names, Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate())) {
            if (runCatching { File(dir, n).delete() }.getOrDefault(false)) removed++
        }
        return removed
    }

    /** Инициализация при старте: каталог + retention. Возвращает путь каталога. */
    fun prepare(context: Context): String {
        val d = dirFor(context)
        dirRef = d
        val removed = runCatching { prune(d, System.currentTimeMillis()) }.getOrDefault(0)
        pathRef = File(d, fileNameFor(System.currentTimeMillis())).absolutePath
        Log.i(TAG, "LogFiles ready dir=${d.absolutePath} retention_days=$RETENTION_DAYS pruned=$removed")
        return d.absolutePath
    }

    /** Путь активного файла (для UI/диагностики). */
    fun currentPath(): String = pathRef

    /**
     * Бюджет диагностики в `AppLog` может считать строку «записанной», поэтому физическая
     * запись обязана быть видимой: счётчик ошибок и последняя причина доступны без файла.
     */
    private val ioErrorCount = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile private var lastIoError: String = ""

    /** Сколько раз запись в датированный лог не удалась (включая fallback). */
    fun ioErrors(): Long = ioErrorCount.get()

    /** Последняя ошибка записи (для независимого канала: logcat/UI/строка в основном логе). */
    fun lastIoError(): String = lastIoError

    /**
     * Дописать строку в файл текущих суток. Возвращает `true` ТОЛЬКО если запись подтверждена
     * (файл дописан, в том числе через fallback `filesDir/logs`).
     *
     * Ошибки не глушим до конца: они уходят в независимый канал (logcat, `LogFiles.ERR`) и в
     * счётчик [ioErrors], иначе «сохранено» было бы утверждением без подтверждения.
     */
    fun append(context: Context, line: String): Boolean {
        val app = context.applicationContext
        var d = dirRef ?: dirFor(app).also { dirRef = it }
        val now = System.currentTimeMillis()
        var failed: Throwable? = null
        runCatching { write(File(d, fileNameFor(now)), line) }.onFailure {
            failed = it
            Log.w(TAG, "append failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        if (failed != null) {
            // Один раз переключаемся на внутренний каталог и пробуем снова.
            val internal = ensureDir(File(app.filesDir, "logs"))
            if (d.absolutePath != internal.absolutePath) {
                dirRef = internal
                d = internal
                failed = null
                runCatching { write(File(d, fileNameFor(now)), line) }.onFailure {
                    failed = it
                    Log.w(TAG, "append failed (internal too): ${it.javaClass.simpleName}")
                }
            }
        }
        val fail = failed
        if (fail != null) {
            ioErrorCount.incrementAndGet()
            lastIoError = "${fail.javaClass.simpleName}: ${fail.message} " +
                "dir=${d.absolutePath} file=${fileNameFor(now)}"
            Log.e(TAG, "LOGFILES_WRITE_ERROR $lastIoError (сообщение не сохранено)")
            return false
        }
        val target = File(d, fileNameFor(now))
        if (pathRef != target.absolutePath) pathRef = target.absolutePath
        return true
    }

    /**
     * §5.1 iter41 — запись строки в КОНКРЕТНЫЙ каталог датированного лога с подтверждением.
     *
     * Вынесено из [append], чтобы тот же самый production-писатель (имя файла `voiceapp3-DD.MM.
     * YYYY.log`, append, flush) можно было проверить в JVM-тесте на временных каталогах, а не на
     * модели файла. `false` — запись не подтверждена (исключение записи/нет каталога).
     */
    internal fun appendToDir(dir: File, line: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            write(File(dir, fileNameFor(nowMs)), line)
            true
        }.getOrDefault(false)

    private fun write(target: File, line: String) {
        FileWriter(target, true).use { w ->
            w.append(line)
            w.append('\n')
            w.flush()
        }
    }
}
