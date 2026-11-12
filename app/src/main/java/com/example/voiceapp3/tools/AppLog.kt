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
 *   CENTRAL_AdbClient   — root-канал 127.0.0.1:5555 (диагностика ADBDIAG, лечение ADBFIX)
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
    // iter33 C8.4: 16 МБ и ДВА архива (app.log.1, app.log.2) — старые вытесняются.
    private const val MAX = 16L * 1024 * 1024
    private const val MAX_ARCHIVES = 2
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
                // iter33: выжимка app.ops.log + SESSION_START/LOG_INDEX в обоих файлах.
                runCatching { LogKit.attach(appCtx, primary.parentFile ?: appCtx.filesDir) }
                // iter33p §4: свой лог по датам — `voiceapp3-DD.MM.YYYY.log` в каталоге приложения
                // (`files/logs`), retention 14 суток. Те же строки, что идут в logcat.
                runCatching {
                    val logDir = LogFiles.prepare(appCtx)
                    Log.i(TAG_BASE, "AppLog.daily path=$logDir name=${LogFiles.fileNameFor(System.currentTimeMillis())}")
                }
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

    /**
     * iter33 C8.1: строка-маркер (`OP_*`, `SESSION_*`, `HEARTBEAT`, …) — пишется тем же
     * единственным синхронизированным писателем, что и всё остальное, поэтому строки
     * разных потоков не перемешиваются. Уровень INFO, тег CENTRAL_Mark.
     */
    @JvmStatic
    fun marker(line: String) {
        Log.i("CENTRAL_Mark", line)
        enqueueOrWrite("INFO", "CENTRAL_Mark", line, null)
    }

    // ───────────── обычные уровни ─────────────
    // Все методы не требуют Context (используют сохранённый из init).
    @JvmStatic fun i(tag: String, m: String) { Log.i(tag, m); enqueueOrWrite("INFO", tag, m, null) }
    @JvmStatic fun w(tag: String, m: String, th: Throwable? = null) { Log.w(tag, m, th); enqueueOrWrite("WARN", tag, m, th) }
    @JvmStatic fun e(tag: String, m: String, th: Throwable? = null) {
        Log.e(tag, m, th)
        // iter33 C7: ошибки попадают в кольцо для LOG_SUMMARY (и никогда не подавляются).
        runCatching { LogKit.errorSeen(tag, m) }
        enqueueOrWrite("ERROR", tag, m, th)
    }
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
    private fun enqueueOrWrite(lvl: String, tag: String, msg: String, th: Throwable?): Boolean =
        enqueueOrWriteLine(lvl, tag, msg, th).written

    private fun enqueueOrWriteLine(lvl: String, tag: String, msg: String, th: Throwable?): LogFiles.Result {
        if (!inited) synchronized(lock) {
            if (!inited) {
                val line = formatLine(lvl, tag, msg, th)
                early.addLast(line)
                while (early.size > EARLY_CAP) early.removeFirst()
                // «положено в буфер до init» ≠ «записано в датированный файл».
                return LogFiles.Result.fail("buffered_before_init")
            }
        }
        return writeRaw(lvl, tag, msg, th)
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

    // ─── iter43 §1.1: лимита скорости на основной журнал БОЛЬШЕ НЕТ ────────────────────────
    //
    // Раньше здесь жил «бюджет» 150 КиБ / 10 минут: по превышении он либо резал строки
    // (`action=trim`, потеряно 127 строк в логе владельца 12.11), либо (iter42) ротировал файл
    // каждые ~150 КиБ и, кроме того, ПОДМЕНЯЛ очередную полезную строку уведомлением
    // `LOG_BUDGET …`. Объём журнала ограничен РАЗМЕРОМ частей и их количеством
    // (`LogFiles.MAX_FILE_BYTES` × `MAX_FILES_PER_DAY` + суммарный cap), поэтому отдельный
    // лимит по скорости ничего не защищает, а только уничтожает историю.
    //
    // Теперь: каждая строка пишется как есть; уведомление (если оно нужно) — ОТДЕЛЬНАЯ запись,
    // результат каждой записи учитывается отдельно; факт отказа объявляется, но не подменяет
    // исходное событие.

    private val linesWritten = java.util.concurrent.atomic.AtomicLong(0)
    private val writeFailures = java.util.concurrent.atomic.AtomicLong(0)
    private val legacyFailures = java.util.concurrent.atomic.AtomicLong(0)
    private val noticeDropped = java.util.concurrent.atomic.AtomicLong(0)

    /** Счётчики журнала: видны в `LOG_STATS` и в отчёте, без чтения logcat. */
    internal fun logStats(): String =
        "LOG_STATS lines=$linesWritten failed=$writeFailures legacy_failed=$legacyFailures " +
            "notice_unwritten=$noticeDropped io_errors=${LogFiles.ioErrors()} " +
            "rotations=${LogFiles.rotations()} parts_deleted=${LogFiles.deleted()} " +
            "split=${LogFiles.splits()} file=«${LogFiles.currentPath()}»"

    /**
     * iter33n §4.5 — префиксы строк ДЕЙСТВИЙ, которые бюджет не имеет права выбрасывать.
     * Список ровно из мандата: `OP_*`, `TOGGLE*`, `TTL_*`, `EXT_PROBE*`, `FLAG_*`, `JIT_*`, `ADB_*`,
     * плюс `BUILD:`/`SESSION_START`/`LOG_BUDGET` (шапка и сам факт обрезки).
     */
    private val ACTION_PREFIXES = arrayOf(
        "OP_", "TOGGLE", "TTL_", "EXT_PROBE", "FLAG_", "JIT_", "ADB_", "BUILD:", "SESSION_START",
        "LOG_BUDGET", "CHANNEL_LOST", "ADB_RECOVERY", "SCRIPT_UPLOAD",
    )

    /** §3.3: диагностический блок в датированном логе (свой ограниченный бюджет). */
    internal fun isDiagLine(msg: String): Boolean = LogBudget.isDiagLine(msg)

    /** Единственный экземпляр бюджета диагностики (живёт на процесс). */
    private val diagBudget = LogBudget.DiagState()

    /** Для тестов/диагностики: сколько строк диагностики усечено в текущем окне. */
    internal fun diagDropped(): Long = diagBudget.droppedLines()

    /** Для тестов/диагностики: был ли факт усечения объявлен (любой блок). */
    internal fun diagTruncated(): Boolean = diagBudget.truncated()

    /** §3.1 iter40: усечение ИМЕННО этого блока (`INTERNET_DIAG` / `JIT_MOUNT_DIAG`). */
    internal fun diagTruncatedFor(label: String): Boolean = diagBudget.truncatedFor(label)

    /**
     * iter42 §2.4 — шумные НИЗКОУРОВНЕВЫЕ ADB-трассы.
     *
     * Это не полезные события, а побайтовые подробности транспорта (`ADB_TX`/`ADB_RX`/hex/повторы
     * рукопожатия). Они уходят в ОТДЕЛЬНЫЙ ротационный tier ([LogFiles.appendVerbose]) со своими
     * лимитами и НЕ расходуют бюджет основного журнала — иначе именно они вытесняли сеть/TTL/JIT
     * (в логе владельца 12.11: `LOG_BUDGET exceeded=153744 action=trim`, дальше dropped=127).
     *
     * Факты транспорта остаются в основном логе: `ADB_STATE`, `ADB_SELFTEST`, `ADB_RECOVERY`,
     * `CHANNEL_LOST`, `SCRIPT_UPLOAD`, `VERIFY EXEC`, `ADB_PORT_READBACK` и сводка
     * `ADB_VERBOSE_STATS`.
     */
    internal fun isVerboseLine(msg: String): Boolean {
        val m = msg.trimStart()
        for (p in VERBOSE_PREFIXES) if (m.startsWith(p)) return true
        // «→ execute …», «← execute …» — тоже низкоуровневые трассы вызовов.
        return m.startsWith("→ execute") || m.startsWith("← execute")
    }

    private val VERBOSE_PREFIXES = arrayOf(
        "ADB_TX", "ADB_RX", "ADB_TRACE_REPLAY", "ADB_ROW", "ADB_CHANNEL", "ADB_PROBE",
        "ADB_DEST", "ADB_CONST", "ADB_CNXN", "ADB_OPEN", "ADB_AUTH"
    )

    private val verboseWritten = java.util.concurrent.atomic.AtomicLong(0)
    private val verboseFailed = java.util.concurrent.atomic.AtomicLong(0)

    /** Для лога/тестов: сколько низкоуровневых строк ушло в verbose-tier и сколько не удалось. */
    internal fun verboseStats(): String =
        "ADB_VERBOSE_STATS lines=${verboseWritten.get()} failed=${verboseFailed.get()} " +
            "tier=verbose file=${runCatching { LogFiles.currentPath() }.getOrDefault("unknown")}"

    /** Строка действия (exempt-список §4.5). */
    internal fun isActionLine(msg: String): Boolean {
        val m = msg.trimStart()
        for (p in ACTION_PREFIXES) if (m.startsWith(p)) return true
        // строки вида «Марк: OP_STEP …» и «RU: …» не считаем действиями — они дублируют OP_*.
        return false
    }

    /**
     * Нужна ли строка в основном журнале — решает ТОЛЬКО наличие контекста.
     *
     * iter43 §1.1: функция оставлена для совместимости сигнатуры; ограничение объёма теперь
     * делается исключительно ротацией по размеру ([LogFiles]), поэтому здесь нет ни «trim»,
     * ни подмены строки уведомлением.
     */
    internal fun budgetAllow(len: Int): Boolean = ctx != null

    /**
     * Прямая запись строки в файл (только после init).
     *
     * §5.1 iter41: возвращает `true` ТОЛЬКО если датированный лог подтвердил запись этой строки
     * ([LogFiles.append]). `false` — строка не попала в датированный sink: её пропустил бюджет,
     * она осталась в буфере до init, либо запись не удалась/не подтверждена.
     */
    private fun writeRaw(lvl: String, tag: String, msg: String, th: Throwable?): LogFiles.Result {
        val c = ctx ?: return LogFiles.Result.fail("no_context")
        // §2.4 iter42: низкоуровневые ADB-трассы — в отдельный verbose-tier, мимо основного журнала.
        if (isVerboseLine(msg)) {
            val line = formatLine(lvl, tag, msg, null)
            val r = runCatching { LogFiles.appendVerbose(c, line) }
                .getOrElse { t -> LogFiles.Result.fail("io:" + t.javaClass.simpleName) }
            if (r.written) verboseWritten.incrementAndGet() else {
                verboseFailed.incrementAndGet()
                writeFailures.incrementAndGet()
                announceFailure("verbose", r)
            }
            return r
        }

        val line = formatLine(lvl, tag, msg, null)
        val lineBytes = line.toByteArray(Charsets.UTF_8).size

        // (1) Шапка (BUILD/INIT) запоминается ДО записи: она попадает в начало нового файла.
        if (isHeaderLine(tag, msg)) {
            synchronized(lock) {
                if (headerLines.size >= 8) headerLines.removeFirst()
                headerLines.addLast(line)
            }
        }

        // (2) Второй (legacy) писатель `voiceapp3.log`: ограничен 16 МиБ × 2 архива.
        //     Пишется ДО основной записи и НЕ подменяет её; его отказ учитывается отдельно.
        val legacyOk = writeLegacy(c, line, th)
        if (!legacyOk) {
            legacyFailures.incrementAndGet()
            writeFailures.incrementAndGet()
        }

        // (3) Диагностические блоки идут по СВОЕМУ бюджету; при отказе пишется ОТДЕЛЬНАЯ строка
        //     `*_TRUNCATED`, а не подмена исходной записи.
        if (isDiagLine(msg)) {
            val v = diagBudget.decide(msg, lineBytes, android.os.SystemClock.elapsedRealtime())
            if (!v.write) {
                if (v.notice != null && linesDiagNoticeWritten(v.notice)) {
                    val notice = formatLine("INFO", "CENTRAL_LogDiag", v.notice, null)
                    val nr = runCatching { LogFiles.append(c, notice) }
                        .getOrElse { t -> LogFiles.Result.fail("io:" + t.javaClass.simpleName) }
                    if (!nr.written) {
                        noticeDropped.incrementAndGet()
                        announceFailure("diag_notice", nr)
                    } else linesWritten.incrementAndGet()
                }
                writeFailures.incrementAndGet()
                return LogFiles.Result.fail("dropped:diag_budget")
            }
        }

        // (4) ОСНОВНАЯ запись: ровно исходная строка (никакой подмены уведомлением).
        val r = runCatching { LogFiles.append(c, line) }
            .getOrElse { t -> LogFiles.Result.fail("io:" + t.javaClass.simpleName) }
        if (r.written) {
            linesWritten.incrementAndGet()
            if (primaryPath != r.file && primaryPath != "(not initialised yet)") {
                // Путь сменился (фоллбэк external→internal) — фиксируем ФАКТ, а не предположение.
                primaryPath = r.file
            }
        } else {
            writeFailures.incrementAndGet()
            announceFailure("main", r)
        }
        if (!legacyOk) {
            // Отказ legacy-писателя объявляется отдельной строкой основного журнала (не подменой).
            val note = formatLine("WARN", TAG_BASE,
                "LOG_LEGACY_FAILED file=«${pickFile(c).absolutePath}» (запись в legacy-файл не " +
                    "подтверждена; датированный журнал ведётся отдельно)", null)
            val nr = runCatching { LogFiles.append(c, note) }
                .getOrElse { t -> LogFiles.Result.fail("io:" + t.javaClass.simpleName) }
            if (nr.written) linesWritten.incrementAndGet() else noticeDropped.incrementAndGet()
        }
        return r
    }

    /**
     * Отказ записи сообщается НЕЗАВИСИМО (logcat + счётчики) и, если возможно, отдельной строкой
     * датированного журнала. Исходное событие при этом не выдаётся за записанное.
     */
    private fun announceFailure(where: String, r: LogFiles.Result) {
        runCatching {
            Log.e(TAG_BASE, "LOG_WRITE_FAILED where=$where reason=${r.reason} " +
                "written=false lines=${linesWritten.get()} failed=${writeFailures.get()}")
        }
        val c = ctx ?: return
        val note = formatLine("ERROR", TAG_BASE,
            "LOG_WRITE_FAILED where=$where reason=${r.reason} (строка НЕ сохранена в " +
                "датированном журнале; см. LOG_STATS)", null)
        val nr = runCatching { LogFiles.append(c, note) }
            .getOrElse { t -> LogFiles.Result.fail("io:" + t.javaClass.simpleName) }
        if (nr.written) linesWritten.incrementAndGet() else noticeDropped.incrementAndGet()
    }

    /**
     * §2.6 iter42 — запись строки диагностики с подтверждением и ТОЧНЫМ файлом.
     * Возвращает результат production-пути (`LogFiles.append`), а не «принято в очередь».
     */
    @JvmStatic
    fun diagLineResult(tag: String, m: String): LogFiles.Result {
        Log.i(tag, m)
        return enqueueOrWriteLine("INFO", tag, m, null)
    }

    /**
     * §5.1 iter41 — строка диагностики с ПОДТВЕРЖДЕНИЕМ записи в датированный лог.
     *
     * Отличается от [i] только тем, что возвращает факт записи: `true` — датированный файл
     * дописан, `false` — нет (бюджет/ошибка/буфер). Это ровно то, чего не хватало: раньше
     * `Boolean` из `LogFiles.append` терялся, и сводка блока могла утверждать `written=full`,
     * ничего не проверив.
     */
    @JvmStatic
    fun diagLine(tag: String, m: String): Boolean = diagLineResult(tag, m).written

    /** Однократность уведомления об усечении: одна строка на факт, а не на каждую запись. */
    private val diagNoticeSeen = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun linesDiagNoticeWritten(notice: String): Boolean =
        diagNoticeSeen.putIfAbsent(notice, true) == null

    /** Физическая запись готовой строки (файл + fallback). Используется уведомлениями бюджета. */
    private fun writeRawLine(c: Context?, line: String, th: Throwable?): Boolean {
        val cx = c ?: return false
        synchronized(lock) {
            var out: java.io.PrintWriter? = null
            try {
                val target = pickFile(cx)
                rotateIfNeeded(target)
                out = java.io.PrintWriter(java.io.FileWriter(target, true))
                out.println(line)
                th?.printStackTrace(out)
                out.flush()
            } catch (x: Throwable) {
                try {
                    val f2 = java.io.File(cx.filesDir, "logs")
                        .apply { runCatching { if (!exists()) mkdirs() } }
                        .let { java.io.File(it, LOG_NAME) }
                    rotateIfNeeded(f2)
                    java.io.PrintWriter(java.io.FileWriter(f2, true)).use {
                        it.println(line); th?.printStackTrace(it); it.flush()
                    }
                } catch (xx: Throwable) {
                    Log.e(TAG_BASE, "write(RawLine) failed both: ${x.message} / ${xx.message}")
                }
            } finally {
                try { out?.close() } catch (_: Throwable) {}
            }
        }
        return runCatching { LogFiles.append(cx, line).written }.getOrDefault(false)
    }

    private fun formatLine(lvl: String, tag: String, msg: String, _th: Throwable?): String {
        val now = android.os.SystemClock.uptimeMillis()
        val dt = now - processStartUptimeMs
        val tname = Thread.currentThread().name.take(14)
        // iter33 C4: сквозной контекст `run=`/`op=`/`cycle=` в КАЖДОЙ строке.
        // В маркерах эти поля уже есть — второй раз не добавляем.
        val ctx = try {
            if (msg.contains(" run=")) "" else LogKit.ctxSuffix()
        } catch (_: Throwable) { "" }
        return "[${df.format(Date())}] [+${dt}ms] [$tname] [$lvl] [$tag] $msg$ctx"
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

    /**
     * Второй (legacy) писатель `voiceapp3.log` в каталоге приложения — ограничен
     * [MAX] × ([MAX_ARCHIVES] + 1) архива. Возвращает ФАКТ записи (не «вызов принят»).
     *
     * iter43: результат учитывается отдельно от датированного журнала; отказ не скрывается.
     */
    private fun writeLegacy(c: Context, line: String, th: Throwable?): Boolean {
        synchronized(legacyLock) {
            var out: PrintWriter? = null
            return try {
                val target = pickFile(c)
                rotateIfNeeded(target)
                out = PrintWriter(FileWriter(target, true))
                out.println(line)
                th?.printStackTrace(out)
                out.flush()
                true
            } catch (x: Throwable) {
                try {
                    val f2 = intFile(c)
                    rotateIfNeeded(f2)
                    PrintWriter(FileWriter(f2, true)).use { it.println(line); th?.printStackTrace(it); it.flush() }
                    primaryPath = f2.absolutePath
                    true
                } catch (xx: Throwable) {
                    runCatching { Log.e(TAG_BASE, "AppLog legacy write failed both: ${x.message} / ${xx.message}") }
                    false
                }
            } finally {
                runCatching { out?.close() }
            }
        }
    }

    private val legacyLock = Any()

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
            // iter33 C8.4: сдвигаем .1 → .2, текущий → .1; архивов не больше MAX_ARCHIVES.
            runCatching {
                val parent = f.parentFile
                for (i in MAX_ARCHIVES downTo 1) {
                    val cur = File(parent, "${f.name}.$i")
                    if (!cur.exists()) continue
                    if (i == MAX_ARCHIVES) { cur.delete(); continue }
                    cur.renameTo(File(parent, "${f.name}.${i + 1}"))
                }
                f.renameTo(File(parent, "${f.name}.1"))
            }
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

    /** iter33: каталог рабочего лога — в нём же живёт выжимка `app.ops.log`. */
    @JvmStatic fun dirOf(c: Context): File = pickFile(c).parentFile ?: c.filesDir

    /** Путь к активному файлу лога (для UI/debug). */
    @JvmStatic fun logPath(): String = primaryPath

    /** Последние N строк лога (для отображения из UI). */
    @JvmStatic fun readTail(c: Context, n: Int = 300): String = try {
        val f = pickFile(c).takeIf { it.exists() } ?: intFile(c).takeIf { it.exists() }
        if (f == null) "log empty ($primaryPath)" else f.readLines().takeLast(n).joinToString("\n")
    } catch(t: Throwable) { "read err: ${t.message}" }
}
