package com.example.voiceapp3.tools

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Автономный дисковый логгер функционала GeelyTools:
 * 1. Пишет синхронно и напрямую на flash-накопитель в два места:
 *    - В приватную директорию приложения: `/data/data/com.example.voiceapp3/files/logs/geelytools_events.log`
 *    - На общую память устройства: `/sdcard/GeelyTools/geelytools_events.log` (доступно пользователю напрямую).
 * 2. Ротация файла при достижении 5 МБ.
 * 3. Логи пишутся строго по событиям (переключение тумблеров, вызовы, ошибки).
 */
object AppLog {
    private const val TAG = "GeelyTools"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024L // 5 МБ
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    private fun getInternalLogFile(context: Context): File {
        val dir = File(context.filesDir, "logs").apply { if (!exists()) mkdirs() }
        return File(dir, "geelytools_events.log")
    }

    private fun getExternalLogFile(): File? {
        return try {
            val dir = File(Environment.getExternalStorageDirectory(), "GeelyTools").apply { if (!exists()) mkdirs() }
            File(dir, "geelytools_events.log")
        } catch (_: Throwable) {
            null
        }
    }

    fun i(context: Context, tag: String, message: String) {
        Log.i(tag, message)
        write("INFO", tag, message, null, context)
    }

    fun w(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        write("WARN", tag, message, throwable, context)
    }

    fun e(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        write("ERROR", tag, message, throwable, context)
    }

    fun logToggle(context: Context, key: String, enabled: Boolean, result: ToolsResult) {
        val statusStr = if (result.ok) "SUCCESS" else "FAILED"
        val sb = StringBuilder()
        sb.append("TOGGLE [").append(key).append("] -> ").append(if (enabled) "ON" else "OFF")
            .append(" | Result: ").append(statusStr)
            .append(" | Summary: ").append(result.summary)
        
        if (result.steps.isNotEmpty()) {
            sb.append("\n  Steps detail:")
            for (s in result.steps) {
                sb.append("\n    - Step: ").append(s.step)
                    .append(" [status=").append(s.status.wire)
                    .append(", exitCode=").append(s.exitCode).append("]")
                if (s.stdout.isNotEmpty()) sb.append(" out: ").append(s.stdout)
                if (s.stderr.isNotEmpty()) sb.append(" err: ").append(s.stderr)
            }
        }
        val level = if (result.ok) "INFO" else "ERROR"
        write(level, "ToolsBackend", sb.toString(), null, context)
    }

    fun logAction(context: Context, action: String, result: ToolsResult) {
        val statusStr = if (result.ok) "SUCCESS" else "FAILED"
        val sb = StringBuilder()
        sb.append("ACTION [").append(action).append("] | Title: ").append(result.title)
            .append(" | Result: ").append(statusStr)
            .append(" | Summary: ").append(result.summary)
        
        if (result.steps.isNotEmpty()) {
            for (s in result.steps) {
                sb.append("\n    -> ").append(s.step)
                    .append(": ").append(s.status.wire)
                if (s.stdout.isNotEmpty()) sb.append(" (").append(s.stdout).append(")")
                if (s.stderr.isNotEmpty()) sb.append(" ERROR: ").append(s.stderr)
            }
        }
        val level = if (result.ok) "INFO" else "ERROR"
        write(level, "Action", sb.toString(), null, context)
    }

    private fun write(level: String, tag: String, message: String, throwable: Throwable?, context: Context) {
        synchronized(lock) {
            try {
                val targets = listOfNotNull(getInternalLogFile(context), getExternalLogFile())
                val timestamp = dateFormat.format(Date())

                for (file in targets) {
                    try {
                        if (file.exists() && file.length() > MAX_LOG_SIZE) {
                            val bak = File(file.parentFile, "${file.name}.bak")
                            if (bak.exists()) bak.delete()
                            file.renameTo(bak)
                        }
                        PrintWriter(FileWriter(file, true)).use { out ->
                            out.println("[$timestamp] [$level] [$tag] $message")
                            throwable?.printStackTrace(out)
                            out.flush()
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "AppLog write to ${file.absolutePath} failed: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "AppLog error: ${t.message}")
            }
        }
    }

    fun readTail(context: Context, maxLines: Int = 100): String {
        return try {
            val file = getInternalLogFile(context)
            if (!file.exists()) return "Файл журнала пуст (события еще не зафиксированы)"
            val lines = file.readLines()
            lines.takeLast(maxLines).joinToString("\n")
        } catch (t: Throwable) {
            "Ошибка чтения журнала: ${t.message}"
        }
    }
}
