package com.example.voiceapp3.tools

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Файловый логгер для отладки root-операций.
 *
 * Пишет ОДНОВРЕМЕННО в два места (без всяких команд от пользователя — файлы
 * появляются сами при старте приложения):
 *   1. /sdcard/Android/data/<package>/files/voiceapp3.log — приватная папка приложения
 *   2. /sdcard/Download/voiceapp3.log                    — общедоступная папка, видна из любого
 *      файл-менеджера на ГУ и из ADB AppControl без рута
 *
 * Ротация: при превышении 256 КБ файл переименовывается в .log.1, старый .log.1 удаляется.
 */
object AppFileLogger {
    private const val TAG = "CENTRAL_AppFileLogger"
    private const val NAME = "voiceapp3.log"
    private const val MAX_SIZE_BYTES = 256 * 1024  // 256 KB
    private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val TS_FMT = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile private var appCtx: Context? = null
    @Volatile private var primaryFile: File? = null
    @Volatile private var mirrorFile: File? = null  // зеркало в /sdcard/Download

    @JvmStatic
    fun init(context: Context) {
        appCtx = context.applicationContext
        try {
            val dir = appCtx!!.getExternalFilesDir(null) ?: appCtx!!.filesDir
            dir.mkdirs()
            primaryFile = File(dir, NAME)
            if (!primaryFile!!.exists()) primaryFile!!.createNewFile()
        } catch (t: Throwable) {
            Log.w(TAG, "primary init failed", t)
        }
        // Зеркало в Download — видно без adb
        try {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dl.mkdirs()
            mirrorFile = File(dl, NAME)
            if (!mirrorFile!!.exists()) mirrorFile!!.createNewFile()
        } catch (t: Throwable) {
            Log.w(TAG, "mirror init failed", t)
        }
        i("LOGGER", "=== Logger init, primary=${primaryFile?.absolutePath} mirror=${mirrorFile?.absolutePath} ===")
        // §10 iter45: честно сообщаем о повреждённом хвосте, а не молча дописываем за ним.
        // Файл НЕ переписываем и НЕ обрезаем: это данные владельца (доказательство дефекта).
        listOfNotNull(primaryFile, mirrorFile).forEach { f ->
            val nul = tailNulBytes(f)
            if (nul > 0) {
                i("LOGGER", "LOG_TAIL_NUL_DETECTED file=«${f.absolutePath}» bytes=$nul " +
                    "action=append_after (не обрезаем: старые записи не переписываются, " +
                    "новые строки пишутся с лока и с fsync)")
            }
        }
    }

    @JvmStatic
    fun logFile(): File? = mirrorFile ?: primaryFile

    @JvmStatic
    fun allFiles(): List<File> = listOfNotNull(primaryFile, mirrorFile)

    /**
     * §10 iter45 — межпроцессный лок на запись.
     *
     * Раньше каждый процесс открывал `FileWriter(f, true)`, ротация делала
     * `renameTo(.1)` + `createNewFile()`, и `fsync` не вызывался вообще. В логе владельца
     * 13.11 в ХВОСТЕ этого файла найден блок из 3296 байт `0x00` ровно на границе смерти
     * процесса (последняя строка 08:05:56.398, следующий старт 08:06:17 после рестарта
     * framework). Такой «дырчатый» хвост даёт запись по смещению без лока (второй писатель
     * укорачивает/пересоздаёт файл, первый продолжает писать со своего offset) и отсутствие
     * fsync на FUSE-хранилище `/storage/emulated`. Утверждать единственную причину нельзя —
     * воспроизведения нет; ниже убраны оба механизма, а повреждение честно детектируется.
     */
    private val ioLock = Any()

    /** Сколько `0x00` подряд в самом хвосте файла (0 — хвост чистый). */
    internal fun tailNulBytes(f: File): Int {
        return runCatching {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                if (len <= 0) return 0
                val probe = kotlin.math.min(len, 64 * 1024L).toInt()
                val buf = ByteArray(probe)
                raf.seek(len - probe)
                raf.readFully(buf)
                Toggles45.trailingNulBytes(buf)
            }
        }.getOrDefault(0)
    }

    private fun rotateIfNeeded(f: File) {
        try {
            if (f.length() < MAX_SIZE_BYTES) return
            val bak = File(f.parentFile, "$NAME.1")
            if (bak.exists()) bak.delete()
            f.renameTo(bak)
            f.createNewFile()
        } catch (_: Throwable) {}
    }

    /**
     * Запись под файловым локом + fsync. `RandomAccessFile` открывается заново на каждую
     * строку (как и прежний `FileWriter`), но смещение берётся под локом, поэтому «дыры»
     * из-за конкурирующего писателя не появляются.
     */
    private fun writeTo(f: File, line: String) {
        synchronized(ioLock) {
            try {
                rotateIfNeeded(f)
                java.io.RandomAccessFile(f, "rw").use { raf ->
                    val ch = raf.channel
                    val lock = runCatching { ch.lock() }.getOrNull()
                    try {
                        raf.seek(raf.length())
                        raf.write(line.toByteArray(Charsets.UTF_8))
                        raf.fd.sync()
                    } finally {
                        runCatching { lock?.release() }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun write(level: String, tag: String, msg: String) {
        val ts = TS_FMT.format(Date())
        val line = "[$ts] $level/$tag: $msg\n"
        primaryFile?.let { writeTo(it, line) }
        mirrorFile?.let { writeTo(it, line) }
    }

    @JvmStatic fun v(tag: String, msg: String) { Log.v(tag, msg); write("V", tag, msg) }
    @JvmStatic fun d(tag: String, msg: String) { Log.d(tag, msg); write("D", tag, msg) }
    @JvmStatic fun i(tag: String, msg: String) { Log.i(tag, msg); write("I", tag, msg) }
    @JvmStatic fun w(tag: String, msg: String) { Log.w(tag, msg); write("W", tag, msg) }
    @JvmStatic fun w(tag: String, msg: String, t: Throwable) { Log.w(tag, msg, t); write("W", tag, "$msg: ${t.stackTraceToString().take(400)}") }
    @JvmStatic fun e(tag: String, msg: String) { Log.e(tag, msg); write("E", tag, msg) }
    @JvmStatic fun e(tag: String, msg: String, t: Throwable) { Log.e(tag, msg, t); write("E", tag, "$msg: ${t.stackTraceToString().take(600)}") }
}
