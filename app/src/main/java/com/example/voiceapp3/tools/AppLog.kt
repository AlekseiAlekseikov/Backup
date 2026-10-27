package com.example.voiceapp3.tools

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GeelyTools logger. Пишет:
 *  1) ВСЕГДА в Logcat (не отключается, главный способ отладки на ГУ).
 *  2) В файл android/data/com.example.voiceapp3/files/voiceapp3.log
 *     (внешний filesDir — работает на Android 9 без доп. разрешений,
 *     именно туда писал и старый voiceapp3).
 *  3) Фоллбэк: если (2) не открылся — во внутренний filesDir/logs/.
 */
object AppLog {
    private const val TAG = "GeelyTools"
    private const val LOG_NAME = "voiceapp3.log"
    private const val MAX = 2L * 1024 * 1024
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    @Volatile private var ctx: Context? = null
    @Volatile private var inited = false

    @JvmStatic fun init(c: Context) { ctx = c.applicationContext; Log.i(TAG, "AppLog.init -> log will be in getExternalFilesDir/voiceapp3.log") }

    fun i(c: Context, t: String, m: String) { Log.i(t, m); write("INFO", t, m, null, c) }
    fun w(c: Context, t: String, m: String, th: Throwable? = null) { Log.w(t, m, th); write("WARN", t, m, th, c) }
    fun e(c: Context, t: String, m: String, th: Throwable? = null) { Log.e(t, m, th); write("ERROR", t, m, th, c) }
    fun d(c: Context, t: String, m: String) { Log.d(t, m); write("DEBUG", t, m, null, c) }

    fun logToggle(c: Context, key: String, en: Boolean, r: ToolsResult) {
        val sb = StringBuilder("TOGGLE [$key] -> ${if(en)"ON"else"OFF"} | ${if(r.ok)"SUCCESS"else"FAILED"} | ${r.summary}")
        for (s in r.steps) {
            sb.append("\n    - ${s.step} [${s.status.wire} exit=${s.exitCode}]")
            if (s.stdout.isNotEmpty()) sb.append(" out: ${s.stdout.take(200)}")
            if (s.stderr.isNotEmpty()) sb.append(" err: ${s.stderr.take(300)}")
        }
        val msg = sb.toString()
        if (r.ok) Log.i("ToolsBackend", msg) else Log.e("ToolsBackend", msg)
        write(if(r.ok)"INFO"else"ERROR", "ToolsBackend", msg, null, c)
    }
    fun logAction(c: Context, act: String, r: ToolsResult) {
        val sb = StringBuilder("ACTION [$act] | ${r.title} | ${if(r.ok)"SUCCESS"else"FAILED"} | ${r.summary}")
        for (s in r.steps) {
            sb.append("\n    -> ${s.step}: ${s.status.wire}")
            if (s.stdout.isNotEmpty()) sb.append(" (${s.stdout.take(200)})")
            if (s.stderr.isNotEmpty()) sb.append(" ERROR: ${s.stderr.take(300)}")
        }
        val msg = sb.toString()
        if (r.ok) Log.i("Action", msg) else Log.e("Action", msg)
        write(if(r.ok)"INFO"else"ERROR", "Action", msg, null, c)
    }

    @JvmStatic fun i(t: String, m: String) { Log.i(t, m); ctx?.let{ write("INFO", t, m, null, it)} }
    @JvmStatic fun w(t: String, m: String) { w(t,m,null) }
    @JvmStatic fun w(t: String, m: String, th: Throwable?) { Log.w(t,m,th); ctx?.let{ write("WARN",t,m,th,it)} }
    @JvmStatic fun e(t: String, m: String) { e(t,m,null) }
    @JvmStatic fun e(t: String, m: String, th: Throwable?) { Log.e(t,m,th); ctx?.let{ write("ERROR",t,m,th,it)} }
    @JvmStatic fun d(t: String, m: String) { Log.d(t,m); ctx?.let{ write("DEBUG",t,m,null,it)} }

    /** Внешний файл: <sdcard>/Android/data/<pkg>/files/voiceapp3.log
     *  (на Android 9 доступен без каких-либо разрешений — это getExternalFilesDir). */
    private fun extFile(c: Context): File? = try {
        c.getExternalFilesDir(null)?.let { dir ->
            if (!dir.exists()) dir.mkdirs()
            File(dir, LOG_NAME)
        }
    } catch (t: Throwable) { Log.e(TAG, "extFile failed: ${t.message}"); null }
    private fun intFile(c: Context) =
        File(c.filesDir, "logs").apply { runCatching { if(!exists()) mkdirs() } }.let { File(it, LOG_NAME) }

    private fun write(lvl: String, tag: String, msg: String, th: Throwable?, c: Context) = synchronized(lock) {
        if (ctx == null) ctx = c.applicationContext
        try {
            val f = extFile(c) ?: intFile(c)
            try {
                if (f.exists() && f.length() > MAX) {
                    val bak = File(f.parentFile, "${f.name}.bak")
                    runCatching { if(bak.exists()) bak.delete(); f.renameTo(bak) }
                }
                PrintWriter(FileWriter(f, true)).use {
                    it.println("[${df.format(Date())}] [$lvl] [$tag] $msg")
                    th?.printStackTrace(it)
                    it.flush()
                }
            } catch (x: Throwable) {
                // Внешний не открылся — пробуем внутренний
                val f2 = intFile(c)
                try {
                    if (f2.exists() && f2.length() > MAX) { val bak=File(f2.parentFile,"${f2.name}.bak"); runCatching{if(bak.exists())bak.delete(); f2.renameTo(bak)} }
                    PrintWriter(FileWriter(f2, true)).use {
                        it.println("[${df.format(Date())}] [$lvl] [$tag] $msg")
                        th?.printStackTrace(it); it.flush()
                    }
                } catch (xx: Throwable) { Log.e(TAG, "write failed both: ${x.message} / ${xx.message}") }
            }
            if (!inited) {
                inited = true
                val wrote = extFile(c) ?: intFile(c)
                Log.i(TAG, "AppLog active: ${wrote.absolutePath} exists=${wrote.exists()} size=${wrote.length()}")
            }
        } catch (x: Throwable) {
            Log.e(TAG, "AppLog fatal: ${x.message}")
        }
    }

    fun readTail(c: Context, n: Int = 300): String = try {
        val f = extFile(c) ?: intFile(c)
        if(!f.exists()) "log file empty (${f.absolutePath})"
        else f.readLines().takeLast(n).joinToString("\n")
    } catch(t: Throwable) { "read err: ${t.message}" }
}
