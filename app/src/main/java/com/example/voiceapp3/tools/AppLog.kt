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
 * Развёрнутый логгер GeelyTools — наш собственный компонент для отладки.
 *
 * Автоматически пишет ВЕСЬ лог в файл:
 *     /sdcard/Android/data/com.example.voiceapp3/files/voiceapp3.log
 * (на Android 9 не требует никаких разрешений). Фоллбэк — data/data/<pkg>/files/logs/voiceapp3.log.
 * При достижении 2 МБ старый файл переименовывается в voiceapp3.log.bak.
 *
 * Дополнительно дублирует в Logcat — это не отключается, но основной способ
 * получить лог: просто достать файл с ГУ через MTP/файловый менеджер.
 *
 * Сообщения, залоггированные ДО вызова init(), накапливаются во внутреннем
 * кольцевом буфере и пишутся в файл сразу после init().
 *
 * Теги модулей:
 *   CENTRAL_AdbClient   — работа с портом 65010 и root-adb
 *   CENTRAL_StatusIcons — иконки статус-бара, VHAL чтения
 *   CENTRAL_Perf        — JIT/zygote, vcodec 60fps
 *   CENTRAL_TTL         — iptables TTL 65
 *   CENTRAL_Brightness  — яркость/таймаут экрана
 *   CENTRAL_LockSound   — звук блокировки
 *   CENTRAL_Tools       — тумблеры ToolsBackend
 *   CENTRAL_Init        — стартовая инициализация
 */
object AppLog {
    private const val TAG_BASE = "CENTRAL"
    private const val LOG_NAME = "voiceapp3.log"
    private const val MAX = 2L * 1024 * 1024
    private const val EARLY_CAP = 512
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @Volatile private var ctx: Context? = null
    @Volatile private var inited = false
    @Volatile private var primaryPath: String = "(not initialised yet)"
    @Volatile private var processStartUptimeMs: Long = 0L

    /** iter16: шапка, которая ДОЛЖНА быть в начале любого лога — даже после
     *  ротации (когда лог-файл создаётся заново). В шапку попадают BUILD-строка
     *  и CENTRAL_Init из App.onCreate — так после ротации не теряется версия. */
    private val headerLines = ArrayDeque<String>(8)
    @Volatile private var headerWritten = false

    /** Кольцевой буфер сообщений до init(). */
    private val early = ArrayDeque<String>(EARLY_CAP)

    // ───────────────────── init ─────────────────────
    @JvmStatic fun init(c: Context) {
        // Ставим UEH в САМОМ НАЧАЛЕ init — ДО любых других действий, чтобы
        // перехватить падения даже в самом init (ранние сбои).
        try {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            if (defaultHandler == null || defaultHandler !is UehBridge) {
                Thread.setDefaultUncaughtExceptionHandler(UehBridge(defaultHandler))
            }
        } catch (t: Throwable) {
            Log.w(TAG_BASE, "AppLog: failed to install UEH: ${t.message}")
        }
        processStartUptimeMs = android.os.Process.getStartUptimeMillis()
        // ВСЕГДА сначала ставим ctx и флаг inited в try, чтобы при падении init
        // логгер хотя бы не терял сообщения (продолжал копить в early и в Logcat).
        val appCtx = try { c.applicationContext } catch (t: Throwable) { c }
        ctx = appCtx
        synchronized(lock) {
            if (inited) return
            var initErr: Throwable? = null
            try {
                // Пытаемся создать primary path на внешнем storage; если он не доступен —
                // фоллбэк на внутренний filesDir/logs/. Оба пути инициализируем сразу,
                // чтобы внешний сбой не убил внутреннюю запись.
                val extDir = try { appCtx.getExternalFilesDir(null) } catch (_: Throwable) { null }
                val intDir = File(appCtx.filesDir, "logs").apply { runCatching { mkdirs() } }
                val primary: File = when {
                    extDir != null -> { runCatching { if (!extDir.exists()) extDir.mkdirs() }; File(extDir, LOG_NAME) }
                    else -> File(intDir, LOG_NAME)
                }
                primaryPath = primary.absolutePath
                // Пишем первое сообщение; writeRaw сам умеет фоллбэк в intDir при ошибке.
                writeRaw("INFO", "CENTRAL_Init",
                    "AppLog.init pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()} " +
                    "thread=${Thread.currentThread().name} file=$primaryPath intDir=${intDir.absolutePath}", null)
                flushEarly()
                inited = true
                Log.i(TAG_BASE, "AppLog.init path=$primaryPath ok=true")
            } catch (t: Throwable) {
                initErr = t
                Log.e(TAG_BASE, "AppLog.init FAIL path=$primaryPath err=${t.message}", t)
                // Даже при падении — оставить inited=false, сообщения продолжат копиться в early.
                // Logcat всегда работает.
            }
            // Дополнительная запись во внутренний файл на случай если внешний не создался.
            try {
                val intDir = File(appCtx.filesDir, "logs").apply { runCatching { mkdirs() } }
                val intFile = File(intDir, LOG_NAME)
                if (intFile.parentFile?.exists() == true) {
                    val line = "[${df.format(Date())}] [${Thread.currentThread().name}] [INIT] [CENTRAL_Init] AppLog init internal path=${intFile.absolutePath} err=${initErr?.message}"
                    PrintWriter(FileWriter(intFile, true)).use { it.println(line); it.flush() }
                    if (primaryPath != intFile.absolutePath) primaryPath = "${primaryPath}|${intFile.absolutePath}"
                }
            } catch (_: Throwable) {}
        }
        // Тяжёлый init (getprop/iptables/prefs) делаем на фоне, оборачиваем весь в try.
        Thread({
            try { logInitHeavy(appCtx) }
            catch (t: Throwable) { Log.e(TAG_BASE, "logInitHeavy FAIL ${t.message}", t) }
        }, "AppLog-init").start()
    }

    /** Диагностическая информация при старте (в фоне, не блокирует UI). */
    private fun logInitHeavy(appCtx: Context) {
        val tag = "CENTRAL_Init"
        try {
            val pm = appCtx.packageManager
            val pi = pm.getPackageInfo(appCtx.packageName, 0)
            val verCode = pi.longVersionCode; val verName = pi.versionName
            val sb = StringBuilder("=== App INIT ===\n")
            sb.append("  pkg=${appCtx.packageName} v=$verName($verCode)\n")
            sb.append("  pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()} root=${android.os.Process.myUid()==0}\n")
            sb.append("  board=${android.os.Build.BOARD} model=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT}\n")
            // Порт adb и root-доступность — через наш же AdbClient? Нет, он может быть не готов.
            // Используем shell с таймаутом в отдельном потоке, чтобы не зависнуть.
            sb.append("  shellId=${runCmd("id")}\n")
            sb.append("  adb.tcp.port(svc/persist)=${runCmd("getprop service.adb.tcp.port; getprop persist.adb.tcp.port")}\n")
            sb.append("  iptables ttl-set count=${runCmd("iptables -t mangle -S POSTROUTING 2>/dev/null | grep -c ttl-set || echo 0")}\n")
            sb.append("  --- prefs (voiceapp3) ---\n")
            val prefNames = listOf(
                "status_icon_battery", "status_icon_temp_ext", "status_icon_wifi",
                "hvacdigital_batt", "hvacdigital_temp", "hvacdigital_temp_ext",
                "ttl_65_ativo", "perf_video_60fps", "perf_auto_otimizar",
                "lock_sound_enabled", "remember_brightness", "screen_rest_timeout_sec"
            )
            val prefs = appCtx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
            for (k in prefNames) sb.append("    $k = ${prefs.all[k]}\n")
            i(tag, sb.toString())
        } catch (t: Throwable) {
            e(tag, "logInit failed: ${t.message}", t)
        }
    }

    private fun runCmd(cmd: String, timeoutMs: Long = 3000L): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh","-c",cmd))
        val worker = Thread({ p.waitFor() }, "AppLog-cmd")
        worker.isDaemon = true; worker.start(); worker.join(timeoutMs)
        if (worker.isAlive) { p.destroy(); "(cmd timed out after ${timeoutMs}ms)" }
        else (p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText())
            .replace('\n', ' ').trim().ifEmpty { "(empty)" }
    } catch (t: Throwable) { "(cmd err: ${t.message})" }

    // ───────────── обычные уровни ─────────────
    // Все методы не требуют Context (используют сохранённый из init).
    @JvmStatic fun i(tag: String, m: String) { Log.i(tag, m); enqueueOrWrite("INFO", tag, m, null) }
    @JvmStatic fun w(tag: String, m: String, th: Throwable? = null) { Log.w(tag, m, th); enqueueOrWrite("WARN", tag, m, th) }
    @JvmStatic fun e(tag: String, m: String, th: Throwable? = null) { Log.e(tag, m, th); enqueueOrWrite("ERROR", tag, m, th) }
    @JvmStatic fun d(tag: String, m: String) { Log.d(tag, m); enqueueOrWrite("DEBUG", tag, m, null) }

    // Совместимость со старыми вызовами, где передавали Context — игнорируем c.
    @JvmStatic fun i(c: Context, t: String, m: String) { i(t, m) }
    @JvmStatic fun w(c: Context, t: String, m: String, th: Throwable? = null) { w(t, m, th) }
    @JvmStatic fun e(c: Context, t: String, m: String, th: Throwable? = null) { e(t, m, th) }
    @JvmStatic fun d(c: Context, t: String, m: String) { d(t, m) }

    @JvmStatic fun logInit(c: Context) { /* уже вызван внутри init(), оставлен для совместимости */ }

    // ───────────── ENTER/EXIT/FAIL ─────────────
    fun enter(tag: String, fn: String, args: String = "") {
        d(tag, "→ $fn(${args}) tid=${android.os.Process.myTid()}")
    }
    fun exitOk(tag: String, fn: String, res: Any? = "ok") {
        d(tag, "← $fn = ${res?.toString()?.take(500) ?: "null"}")
    }
    fun exitErr(tag: String, fn: String, t: Throwable) {
        e(tag, "← $fn ОШИБКА: ${t.javaClass.simpleName}: ${t.message}", t)
    }
    fun exitErr(tag: String, fn: String, reason: String) {
        e(tag, "← $fn ОШИБКА: $reason")
    }

    /** Toggle-лог (тумблеры в Tools). */
    fun logToggle(c: Context, key: String, en: Boolean, r: ToolsResult) {
        val sb = StringBuilder("TOGGLE [$key] → ${if(en)"ON"else"OFF"} | ${if(r.ok)"✓ SUCCESS"else"✗ FAILED"} | ${r.summary}")
        for (s in r.steps) {
            sb.append("\n    • ${s.step}  [${s.status.wire} exit=${s.exitCode}]")
            if (s.stdout.isNotEmpty()) sb.append("\n        out: ${s.stdout.take(600)}")
            if (s.stderr.isNotEmpty()) sb.append("\n        err: ${s.stderr.take(600)}")
        }
        if (r.ok) i("CENTRAL_Tools", sb.toString()) else e("CENTRAL_Tools", sb.toString())
    }
    fun logAction(c: Context, act: String, r: ToolsResult) {
        val sb = StringBuilder("ACTION [$act] | ${r.title} | ${if(r.ok)"✓ SUCCESS"else"✗ FAILED"} | ${r.summary}")
        for (s in r.steps) {
            sb.append("\n    • ${s.step}: ${s.status.wire}")
            if (s.stdout.isNotEmpty()) sb.append(" (${s.stdout.take(400)})")
            if (s.stderr.isNotEmpty()) sb.append(" ERROR: ${s.stderr.take(400)}")
        }
        if (r.ok) i("CENTRAL_Tools", sb.toString()) else e("CENTRAL_Tools", sb.toString())
    }

    // ───────────── internal ─────────────
    /** До init() — складываем в буфер; после init() — пишем сразу. */
    private fun enqueueOrWrite(lvl: String, tag: String, msg: String, th: Throwable?) {
        if (!inited) synchronized(lock) {
            if (!inited) {
                val line = formatLine(lvl, tag, msg, th)
                early.addLast(line)
                while (early.size > EARLY_CAP) early.removeFirst()
                return
            }
        }
        writeRaw(lvl, tag, msg, th)
    }

    private fun flushEarly() {
        // Вызывается из synchronized(lock), уже держим лок.
        var thrown: Throwable? = null
        val out = openWriter() ?: return
        try {
            out.println("# ---- early log (${early.size} lines) ----")
            while (early.isNotEmpty()) {
                out.println(early.removeFirst())
            }
            out.println("# ---- /early log ----")
            out.flush()
        } catch (t: Throwable) { thrown = t }
        try { out.close() } catch (_: Throwable) {}
        if (thrown != null) Log.e(TAG_BASE, "flushEarly: ${thrown.message}")
    }

    /** Прямая запись строки в файл (только после init). */
    private fun writeRaw(lvl: String, tag: String, msg: String, th: Throwable?) {
        val c = ctx ?: return
        var thrown: Throwable? = null
        val line = formatLine(lvl, tag, msg, null)
        // iter16: собираем "шапку" (BUILD/App INIT) — пишем её в начало каждого
        // нового/повёрнутого файла, чтобы после ротации версия не терялась.
        if (isHeaderLine(tag, msg)) {
            synchronized(lock) {
                if (headerLines.size >= 8) headerLines.removeFirst()
                headerLines.addLast(line)
            }
        }
        synchronized(lock) {
            var out: PrintWriter? = null
            var target: File? = null
            try {
                target = pickFile(c)
                rotateIfNeeded(target)
                out = PrintWriter(FileWriter(target, true))
                out.println(line)
                th?.printStackTrace(out)
                out.flush()
            } catch (x: Throwable) {
                thrown = x
                // Фоллбэк в data/data
                try {
                    val f2 = File(c.filesDir, "logs").apply { runCatching { if(!exists()) mkdirs() } }.let { File(it, LOG_NAME) }
                    rotateIfNeeded(f2)
                    PrintWriter(FileWriter(f2, true)).use {
                        it.println(line); th?.printStackTrace(it); it.flush()
                    }
                    target = f2
                } catch (xx: Throwable) { Log.e(TAG_BASE, "write failed both: ${x.message} / ${xx.message}") }
            } finally {
                try { out?.close() } catch (_: Throwable) {}
            }
            if (thrown == null && target != null && target.absolutePath != primaryPath) {
                primaryPath = target.absolutePath
            }
        }
    }

    private fun formatLine(lvl: String, tag: String, msg: String, _th: Throwable?): String {
        val now = android.os.SystemClock.uptimeMillis()
        val dt = now - processStartUptimeMs
        val tname = Thread.currentThread().name.take(14)
        return "[${df.format(Date())}] [+${dt}ms] [$tname] [$lvl] [$tag] $msg"
    }

    /** Синхронная запись строки в файл — БЕЗ Logcat и без очереди (для UEH:
     *  после написания процесс умирает, нельзя полагаться на планирование). */
    private fun writeRawSync(line: String, th: Throwable?) {
        val c = ctx ?: return
        try {
            val dir = (try { c.getExternalFilesDir(null) } catch (_: Throwable) { null }) ?: c.filesDir
            runCatching { if (!dir.exists()) dir.mkdirs() }
            val f = File(dir, LOG_NAME)
            FileWriter(f, true).use { fw ->
                fw.write(line)
                fw.write("\n")
                if (th != null) PrintWriter(fw).use { pw -> th.printStackTrace(pw); pw.flush() }
                fw.flush()
            }
        } catch (_: Throwable) {
            try {
                val f = File(c.filesDir, "logs").apply { runCatching { if (!exists()) mkdirs() } }.let { File(it, LOG_NAME) }
                FileWriter(f, true).use { fw ->
                    fw.write(line)
                    fw.write("\n")
                    if (th != null) PrintWriter(fw).use { pw -> th.printStackTrace(pw); pw.flush() }
                    fw.flush()
                }
            } catch (_: Throwable) {}
        }
    }

    /** UncaughtExceptionHandler-мост: пишет стек в файл СИНХРОННО, затем
     *  делегирует дефолтному handler'у (который убьёт процесс и укажет систему). */
    private class UehBridge(val delegate: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val sb = StringBuilder("\n=== UNCAUGHT EXCEPTION on thread '${t.name}' (tid=${t.id}) ===\n")
                var cur: Throwable? = e
                var depth = 0
                while (cur != null && depth < 5) {
                    sb.append("Cause #$depth: ${cur.javaClass.name}: ${cur.message}\n")
                    val frames = cur.stackTrace
                    val n = minOf(frames.size, 40)
                    for (i in 0 until n) sb.append("    at ${frames[i]}\n")
                    if (frames.size > n) sb.append("    ... ${frames.size - n} more\n")
                    cur = cur.cause; depth++
                }
                val line = "[${df.format(Date())}] [CRASH] [${t.name}] EXCEPTION: $sb"
                AppLog.writeRawSync(line, e)
            } catch (_: Throwable) {}
            delegate?.uncaughtException(t, e)
        }
    }

    private fun pickFile(c: Context): File {
        val dir = (try { c.getExternalFilesDir(null) } catch (_: Throwable) { null }) ?: c.filesDir
        runCatching { if (!dir.exists()) dir.mkdirs() }
        return File(dir, LOG_NAME)
    }

    private fun intFile(c: Context): File =
        File(c.filesDir, "logs").apply { runCatching { if(!exists()) mkdirs() } }.let { File(it, LOG_NAME) }

    /** Маркеры строк-шапки — их мы сохраняем в headerLines и пишем в начало
     *  каждого нового (после ротации) файла, чтобы шапка с версией не терялась. */
    private fun isHeaderLine(tag: String, msg: String): Boolean {
        if (tag == "App" && msg.startsWith("BUILD:")) return true
        if (tag == "App" && msg.startsWith("=== Application.onCreate")) return true
        if (tag == "CENTRAL_Init") return true
        if (msg.startsWith("Application.onCreate done")) return true
        return false
    }

    private fun writeHeaderToFile(f: File) {
        if (headerLines.isEmpty()) return
        try {
            FileWriter(f, true).use { fw ->
                // помечаем "шапка после ротации"
                fw.write(formatLine("INFO", TAG_BASE, "──── rotated log header (${headerLines.size} lines) ────", null))
                fw.write("\n")
                for (line in headerLines) { fw.write(line); fw.write("\n") }
                fw.flush()
            }
        } catch (_: Throwable) {}
    }

    private fun rotateIfNeeded(f: File) {
        if (f.exists() && f.length() > MAX) {
            val bak = File(f.parentFile, "${f.name}.bak")
            runCatching { if (bak.exists()) bak.delete(); f.renameTo(bak) }
            // Новый файл: пишем шапку в начало, чтобы после ротации версия не
            // терялась (iter16: жалоба владельца на потерянный BUILD после
            // ротации в логе, начинающемся с +34376мс).
            writeHeaderToFile(f)
        } else if (!f.exists() && headerLines.isNotEmpty()) {
            // Свежесозданный файл — тоже со шапкой.
            writeHeaderToFile(f)
        }
    }

    private fun openWriter(): PrintWriter? {
        val c = ctx ?: return null
        return try {
            val f = pickFile(c); rotateIfNeeded(f); PrintWriter(FileWriter(f, true))
        } catch (_: Throwable) {
            try { val f = intFile(c); rotateIfNeeded(f); PrintWriter(FileWriter(f, true)) }
            catch (_: Throwable) { null }
        }
    }

    /** Путь к активному файлу лога (для UI/debug). */
    @JvmStatic fun logPath(): String = primaryPath

    /** Последние N строк лога (для отображения из UI). */
    @JvmStatic fun readTail(c: Context, n: Int = 300): String = try {
        val f = pickFile(c).takeIf { it.exists() } ?: intFile(c).takeIf { it.exists() }
        if (f == null) "log empty ($primaryPath)" else f.readLines().takeLast(n).joinToString("\n")
    } catch(t: Throwable) { "read err: ${t.message}" }
}
