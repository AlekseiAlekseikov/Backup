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
    private const val TAG = "AppFileLogger"
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
    }

    @JvmStatic
    fun logFile(): File? = mirrorFile ?: primaryFile

    @JvmStatic
    fun allFiles(): List<File> = listOfNotNull(primaryFile, mirrorFile)

    private fun rotateIfNeeded(f: File) {
        try {
            if (f.length() < MAX_SIZE_BYTES) return
            val bak = File(f.parentFile, "$NAME.1")
            if (bak.exists()) bak.delete()
            f.renameTo(bak)
            f.createNewFile()
        } catch (_: Throwable) {}
    }

    private fun writeTo(f: File, line: String) {
        try {
            rotateIfNeeded(f)
            FileWriter(f, true).use { it.append(line) }
        } catch (_: Throwable) {}
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
