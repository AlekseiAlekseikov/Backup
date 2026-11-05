package com.example.voiceapp3.tools

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * iter33 (1.0.53) — структурированный лог: маркеры, контекст `run/op/cycle`, выжимка
 * `app.ops.log`, счётчики и подавление шума.
 *
 * Зачем отдельный объект, а не AppLog:
 *  • AppLog — «сырьё» (`app.log`), он не должен знать ни про какие маркеры;
 *  • здесь живёт ВСЯ политика (C1–C8 мандата), чтобы её можно было менять, не трогая писатель;
 *  • писатель остался ОДИН (AppLog.writeRaw, synchronized) — требование C8.2 соблюдено:
 *    строки не перемешиваются между `CENTRAL_Bg-N`.
 *
 * Файлы:
 *  • `app.log`     — рабочий лог (шум свёрнут, см. [trace]);
 *  • `app.ops.log` — выжимка: только маркеры (`SESSION_*`, `LOG_INDEX`, `OP_*`, `CYCLE_*`,
 *    `HEARTBEAT`, `HVAC_*`, `SCRIPT_TRACE`, `LOG_SUMMARY`, все `[ERROR]`/`[WARN]` и `RU:`).
 *
 * ВАЖНО: логирование не выполняет ни одной ADB-команды и не открывает ни одного соединения (C8.5).
 */
object LogKit {
    const val VER = "1.0.53"
    const val OPS_NAME = "app.ops.log"
    private const val OPS_MAX_LINES = 200
    private const val TRACE_PER_SESSION = 20
    private const val CONN_WINDOW_MS = 60_000L
    /** iter33b B4: файл выжимки пишется не на каждый маркер, а не чаще 1 раза в 2 с. */
    private const val OPS_FLUSH_MIN_MS = 2_000L
    /** iter33b B3.1: прежний тег, под которым ADB-трассировку ищут все прежние грепы. */
    private const val ADB_TAG = "CENTRAL_AdbClient"
    /** iter33b B3.2: сколько строк текущей команды держим для повтора при провале. */
    private const val JOB_RING = 40

    /** Один `run` на процесс: 8 hex из nanoTime (не криптография — просто метка сеанса). */
    val run: String = java.lang.Long.toHexString(System.nanoTime() and 0xFFFFFFFFL).padStart(8, '0')

    private val df = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val lock = Any()
    private val opsLines = ArrayDeque<String>(OPS_MAX_LINES)

    @Volatile private var dir: File? = null
    @Volatile private var appCtx: Context? = null
    private val tlOp = ThreadLocal<String?>()
    private val tlCycle = ThreadLocal<Int>()

    // ── счётчики для HEARTBEAT ──
    private val execCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val connCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val sysuiKills = java.util.concurrent.atomic.AtomicInteger(0)
    private val codecRestarts = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastBeatMs = 0L
    @Volatile private var hvacState = "unknown"
    @Volatile private var ttlState = "unknown"
    @Volatile private var videoState = "unknown"

    // ── подавление шума (C3) ──
    private val traceSeen = HashMap<String, Int>()
    private val traceSuppressed = HashMap<String, Int>()
    private val traceLast = HashMap<String, String>()
    @Volatile private var lastSuppressedFlushMs = 0L
    /** iter33b B4: отложенная запись выжимки. */
    @Volatile private var lastOpsFlushMs = 0L
    @Volatile private var opsDirty = false
    /** iter33c C2: досрочный сброс хвоста уже запланирован (один на пачку). */
    @Volatile private var tailFlushScheduled = false
    private val mainHandler: android.os.Handler by lazy {
        android.os.Handler(android.os.Looper.getMainLooper())
    }
    /** iter33b B3.2: строки ТЕКУЩЕЙ команды — при провале печатаются целиком. */
    private val jobRing = ArrayDeque<String>(JOB_RING)
    @Volatile private var jobFailed = false
    /** iter33b B3.3: rc последней команды — попадает в `ADB_TRACE_SUPPRESSED … last=… rc=…`. */
    @Volatile private var lastJobRc = 0

    // ── кольца для LOG_SUMMARY ──
    private class OpRec(val time: String, val action: String, val result: String, val reason: String)
    private val opRing = ArrayDeque<OpRec>(20)
    private val errRing = ArrayDeque<String>(5)

    // ───────────────────────── init ─────────────────────────

    /** Вызывается из AppLog.init: тот же каталог, что у `app.log`. */
    fun attach(ctx: Context, logDir: File) {
        dir = logDir
        appCtx = ctx.applicationContext
        sessionStart(ctx)
    }

    private fun opsFile(): File? {
        val d = dir ?: return null
        runCatching { if (!d.exists()) d.mkdirs() }
        return File(d, OPS_NAME)
    }

    /**
     * Перезапись выжимки целиком (≤ [OPS_MAX_LINES] строк) — так проще держать лимит.
     *
     * iter33b B4: было — `f.writeText(все 200 строк)` на КАЖДЫЙ маркер, и сам `writeText`
     * стоял ВНЕ `synchronized(lock)` (четыре `CENTRAL_Bg-N` могли порвать файл).
     * Стало — не чаще одного раза в [OPS_FLUSH_MIN_MS] мс, а сама запись внутри лока.
     * Принудительно: [heartbeat], [errorSeen], [writeSummary], [flushOpsNow] (onDestroy).
     */
    private fun flushOps(force: Boolean = false) {
        val f = opsFile() ?: return
        val now = SystemClock.uptimeMillis()
        synchronized(lock) {
            if (!force && now - lastOpsFlushMs < OPS_FLUSH_MIN_MS) {
                // iter33c C2: попадание в окно debounce БОЛЬШЕ НЕ теряет хвост. Раньше флаг
                // opsDirty никто не читал, и последняя пачка маркеров лежала в памяти до
                // ближайшего HEARTBEAT (60 с) — при убийстве процесса терялось до минуты выжимки.
                opsDirty = true
                scheduleTailFlush()
                return
            }
            lastOpsFlushMs = now
            opsDirty = false
            runCatching { f.writeText(opsLines.joinToString("\n") + "\n") }
        }
    }

    /**
     * iter33c C2: один отложенный сброс на пачку — ровно через [OPS_FLUSH_MIN_MS] от первого
     * отложенного маркера. Худшая задержка хвоста теперь = [OPS_FLUSH_MIN_MS] (2 с), а не 60 с.
     * Флаг `tailFlushScheduled` не даёт наплодить задачи по числу маркеров.
     */
    private fun scheduleTailFlush() {
        if (tailFlushScheduled) return
        tailFlushScheduled = true
        mainHandler.postDelayed({
            tailFlushScheduled = false
            flushOps(force = true)
        }, OPS_FLUSH_MIN_MS)
    }

    /** Немедленная запись выжимки (onDestroy: терять последние строки нельзя). */
    fun flushOpsNow() = flushOps(force = true)

    private fun ops(line: String) {
        synchronized(lock) {
            opsLines.addLast(line)
            while (opsLines.size > OPS_MAX_LINES) opsLines.removeFirst()
        }
        flushOps(force = false)
    }

    /** Строка-маркер: идёт и в `app.log` (как факт), и в выжимку `app.ops.log`. */
    private fun marker(line: String) {
        AppLog.marker(line)
        ops(line)
    }

    /**
     * iter33 C6: разбор `###STAGE name=… status=…` из вывода скрипта.
     * Печатает `SCRIPT_TRACE script=… stages=N failed=<name>:<reason>` и по строке на шаг.
     * Сырой вывод скрипта при этом сохраняется целиком (ничего не выбрасываем).
     */
    fun scriptTrace(script: String, rawOut: String) {
        val stages = Regex("###STAGE name=([A-Za-z0-9_]+) status=([A-Za-z]+)(?: reason=([^\\n]*))?")
            .findAll(rawOut).map { m ->
                Triple(m.groupValues[1], m.groupValues[2].uppercase(), m.groupValues[3].trim())
            }.toList()
        if (stages.isEmpty()) {
            marker("SCRIPT_TRACE script=$script stages=0 failed=none (маркеров ###STAGE в выводе нет — старый скрипт или канал не дошёл)")
            return
        }
        val failed = stages.firstOrNull { it.second == "FAILED" }
        val failedText = failed?.let { "${it.first}:${it.third.ifEmpty { "?" }}" } ?: "none"
        marker("SCRIPT_TRACE script=$script stages=${stages.size} failed=${kv(failedText)}")
        stages.forEachIndexed { i, st ->
            marker("  STAGE ${i + 1}/${stages.size} ${st.first.padEnd(10)} ${st.second.padEnd(7)} ${kv(st.third.ifEmpty { "-" })}")
        }
        val ruText = if (failed != null)
            "Скрипт $script остановился на шаге «${failed.first}»: ${failed.third.ifEmpty { "причина не указана" }}. Всё, что было до него, выполнено; этот шаг — причина, по которой цифры не появились."
        else
            "Скрипт $script прошёл все ${stages.size} шагов без провалов: причина отсутствия цифр — не в скрипте, смотрите шаги после него."
        ru(ruText)
    }

    // ───────────────────────── контекст ─────────────────────────

    fun currentOp(): String? = tlOp.get()
    fun currentCycle(): Int? = tlCycle.get()

    /** Суффикс контекста для каждой строки `app.log` (C4): `run=… op=… cycle=…`. */
    fun ctxSuffix(): String {
        val op = tlOp.get()
        val cy = tlCycle.get()
        val sb = StringBuilder(" run=")
        sb.append(run)
        if (op != null) sb.append(" op=").append(op)
        if (cy != null) sb.append(" cycle=").append(cy)
        return sb.toString()
    }

    /** Выполнить блок с выставленным `op=` (контекст держится на потоке, но передаётся явно). */
    fun <T> withOp(op: String, block: () -> T): T {
        val prev = tlOp.get()
        tlOp.set(op)
        try { return block() } finally { tlOp.set(prev) }
    }

    fun setThreadOp(op: String?) { tlOp.set(op) }

    fun <T> withCycle(n: Int, block: () -> T): T {
        val prev = tlCycle.get()
        tlCycle.set(n)
        try { return block() } finally { tlCycle.set(prev) }
    }

    fun newOpId(): String =
        java.lang.Long.toHexString((System.nanoTime() xor (System.currentTimeMillis() shl 4)) and 0xFFFFFFFFL)
            .padStart(8, '0')

    // ───────────────────────── SESSION (C2) ─────────────────────────

    private fun sessionStart(ctx: Context) {
        val dev = runCatching { android.os.Build.MODEL + "/" + android.os.Build.BOARD }.getOrDefault("unknown")
        val up = SystemClock.uptimeMillis()
        marker("SESSION_START run=$run ver=$VER dev=$dev uptime=$up")
        marker(
            "LOG_INDEX " +
                "SESSION_START=старт сеанса(run/ver/dev) " +
                "LOG_INDEX=эта шпаргалка " +
                "OP_START=начало действия(op/cat/action/src) " +
                "OP_STEP=шаг действия(step/status/expect/got) " +
                "OP_SUMMARY=итог действия(ровно одна строка; ok/partial/failed/total) " +
                "CYCLE_START/CYCLE_SUMMARY=тик watchdog " +
                "HEARTBEAT=состояние раз в 60с " +
                "HVAC_PROBE/HVAC_RESULT=плагин климата " +
                "SCRIPT_TRACE=разбор шагов скрипта " +
                "LOG_SUMMARY=таблица последних действий " +
                "RU:=пояснение по-русски после машинной строки"
        )
    }

    // ───────────────────────── OP/CYCLE маркеры ─────────────────────────

    fun opStart(op: String, cat: String, action: String, src: String) =
        marker("OP_START op=$op cat=$cat action=$action src=$src")

    fun opStep(op: String, step: Int, total: Int, name: String, status: String,
               expect: String, got: String, ms: Long) =
        marker("OP_STEP op=$op step=$step/$total name=$name status=$status expect=${kv(expect)} got=${kv(got)} ms=$ms")

    /**
     * iter33b D2: `PARTIAL` больше не считается успехом в `steps=`.
     * Старое поле `steps=<ok>/<N>` оставлено (чтобы прежние выжимки читались),
     * рядом появились `ok=`, `partial=`, `failed=`, `total=`.
     */
    fun opSummary(op: String, action: String, result: String, okSteps: Int, partialSteps: Int,
                  failedSteps: Int, total: Int, durMs: Long, reason: String) {
        marker("OP_SUMMARY op=$op action=$action result=$result steps=$okSteps/$total " +
            "ok=$okSteps partial=$partialSteps failed=$failedSteps total=$total dur=$durMs reason=${kv(reason)}")
        synchronized(lock) {
            opRing.addLast(OpRec(df.format(Date()), action, result, reason))
            while (opRing.size > 20) opRing.removeFirst()
        }
    }

    fun cycleStart(n: Int) = marker("CYCLE_START n=$n reason=watchdog")

    fun cycleSummary(n: Int, actions: Int, exec: Int, durMs: Long) =
        marker("CYCLE_SUMMARY n=$n actions=$actions exec=$exec dur=$durMs")

    // ───────────────────────── RU: (C5) ─────────────────────────

    /** Пояснение по-русски: пишется в ОБА файла сразу после машинной строки. */
    fun ru(text: String) {
        AppLog.marker("RU: $text")
        ops("RU: $text")
    }

    // ───────────────────────── HEARTBEAT / LOG_SUMMARY ─────────────────────────

    fun countExec() { execCount.incrementAndGet() }
    fun countConn() { connCount.incrementAndGet() }
    fun countSysuiKill() { sysuiKills.incrementAndGet() }
    fun countCodecRestart() { codecRestarts.incrementAndGet() }

    fun stateHvac(v: String) { hvacState = v }
    fun stateTtl(on: Boolean) { ttlState = if (on) "on" else "off" }
    fun stateVideo(on: Boolean) { videoState = if (on) "on" else "off" }

    /**
     * Обновить состояние для `HEARTBEAT` по префам (без единой ADB-команды, C8.5).
     * Вызывается после каждого действия пользователя и на каждом тике watchdog'а.
     */
    fun heartbeatStateRefresh() {
        val c = appCtx ?: return
        runCatching {
            stateTtl(Prefs2.getBoolean(c, "ttl_65_ativo", false))
            stateVideo(Prefs2.getBoolean(c, "perf_video_60fps", false))
        }
    }

    /** Раз в 60 с: одна строка вместо восьми грепов (C7). */
    fun heartbeat() {
        val now = SystemClock.uptimeMillis()
        val win = if (lastBeatMs == 0L) 60_000L else (now - lastBeatMs).coerceAtLeast(1L)
        lastBeatMs = now
        val exec = execCount.getAndSet(0)
        val conn = connCount.getAndSet(0)
        val perMinExec = if (win in 1..599_999L) Math.round(exec * 60_000.0 / win) else exec
        val perMinConn = if (win in 1..599_999L) Math.round(conn * 60_000.0 / win) else conn
        marker(
            "HEARTBEAT uptime=${now / 1000} exec/min=$perMinExec conn/min=$perMinConn " +
                "hvac=$hvacState ttl=$ttlState video=$videoState " +
                "sysuiKills=${sysuiKills.get()} codecRestarts=${codecRestarts.get()}"
        )
        flushSuppressed(force = true)
        flushOps(force = true)
    }

    /**
     * Выжимка сеанса: вызывается ТОЛЬКО из `onDestroy` сервиса (iter33b B1 — из тика
     * watchdog'а вызов убран) и из пункта диагностики, если он появится в коде.
     *
     * iter33b B1.3: таблица действий пишется в `app.log` (`AppLog.marker`), а НЕ в кольцо
     * выжимки — раньше 20 строк таблицы каждую минуту вытесняли настоящие `OP_SUMMARY`
     * из буфера на 200 строк, и `app.ops.log` переставал быть выжимкой.
     * В `app.ops.log` остаётся шапка `LOG_SUMMARY run=… ops=… failed=…` + не более 5 действий.
     */
    fun writeSummary() {
        // Снимок колец — ОДНИМ synchronized: локальные val нельзя присваивать внутри лямбды.
        val snap = synchronized(lock) { opRing.toList() to errRing.toList() }
        val rows = snap.first
        val errs = snap.second
        val failed = rows.count { it.result != "OK" }
        marker("LOG_SUMMARY run=$run ops=${rows.size} failed=$failed")
        var i = 0
        for (r in rows) {
            i++
            AppLog.marker("  LOG_SUMMARY_ROW #%02d %s %-10s %-8s %s"
                .format(i, r.time, r.action.take(10), r.result, kv(r.reason)))
        }
        for (e in errs) AppLog.marker("  LOG_SUMMARY_ERR ${kv(e)}")
        synchronized(lock) {
            for (r in rows.takeLast(5)) {
                ops("  #${r.time} ${r.action.take(12)} ${r.result} ${kv(r.reason)}")
            }
        }
        flushOps(force = true)
    }

    /** Кольцо ошибок: вызывается из AppLog.e, чтобы `LOG_SUMMARY` их показывал. */
    fun errorSeen(tag: String, msg: String) {
        synchronized(lock) {
            errRing.addLast("$tag: $msg")
            while (errRing.size > 5) errRing.removeFirst()
        }
        // iter33b B4: на любой [ERROR] выжимка пишется немедленно, без 2-секундной задержки.
        flushOps(force = true)
    }

    // ───────────────────────── подавление шума (C3) ─────────────────────────

    /**
     * iter33b B3.1/B3.2: трассировка ADB — отдельный вход, потому что у неё два отличия
     * от [trace]:
     *  1. тег остаётся прежним `CENTRAL_AdbClient` (по нему разбирали логи 1.0.50–1.0.52);
     *  2. строки текущей команды копятся в кольце [JOB_RING], и если команда кончилась
     *     `rc != 0`, исключением или таймаутом ([failJobTrace]) — печатаются ЦЕЛИКОМ,
     *     с hex и ASCII, независимо от лимита [TRACE_PER_SESSION]. Именно отсутствие
     *     hex при новом сбое было главной претензией к свёрнутой трассировке.
     */
    fun traceAdb(kind: String, line: () -> String) {
        val text = line()
        var write = false
        synchronized(lock) {
            if (jobRing.size >= JOB_RING) jobRing.removeFirst()
            jobRing.addLast(text)
            val seen = traceSeen[kind] ?: 0
            if (seen < TRACE_PER_SESSION || jobFailed) {
                traceSeen[kind] = seen + 1
                write = true
            } else {
                traceSuppressed[kind] = (traceSuppressed[kind] ?: 0) + 1
                traceLast[kind] = text
            }
        }
        if (write) AppLog.d(ADB_TAG, text) else flushSuppressed(force = false)
    }

    /** Начало команды: чистое кольцо, флаг провала снят. */
    fun beginJobTrace() {
        synchronized(lock) {
            jobRing.clear()
            jobFailed = false
        }
    }

    /** rc последней команды — уходит в `ADB_TRACE_SUPPRESSED … rc=`. */
    fun noteJobRc(rc: Int) { lastJobRc = rc }

    /**
     * Команда провалилась (rc != 0 / исключение / таймаут / обрыв рукопожатия):
     * её `ADB_TX`/`ADB_RX` печатаются повторно и целиком — подавление на провал не действует.
     */
    fun failJobTrace(rc: Int, why: String) {
        val lines = synchronized(lock) {
            jobFailed = true
            jobRing.toList()
        }
        AppLog.d(ADB_TAG, "ADB_TRACE_REPLAY rc=$rc why=${kv(why)} n=${lines.size} (провал: трассировка не подавляется)")
        for (l in lines) AppLog.d(ADB_TAG, l)
    }

    /**
     * `trace` — пишет строку только первые [TRACE_PER_SESSION] раз на сеанс для данного вида,
     * дальше считает и раз в минуту выдаёт ОДНУ строку `ADB_TRACE_SUPPRESSED n=… last=…`.
     * Так шум сворачивается ЯВНО (со счётчиком), а не молча (C3, запрет «удалять строки молча»).
     * Для ADB-трассировки используйте [traceAdb] (B3) — у неё прежний тег и защита hex при сбое.
     */
    fun trace(kind: String, line: () -> String) {
        synchronized(lock) {
            val seen = traceSeen[kind] ?: 0
            if (seen < TRACE_PER_SESSION) {
                traceSeen[kind] = seen + 1
                AppLog.d("CENTRAL_Trace", line())
                return
            }
            traceSuppressed[kind] = (traceSuppressed[kind] ?: 0) + 1
            traceLast[kind] = line()
        }
        flushSuppressed(force = false)
    }

    /** Раз в минуту — сводка подавленного. */
    fun flushSuppressed(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastSuppressedFlushMs < CONN_WINDOW_MS && traceSuppressed.isEmpty()) return
        if (!force && now - lastSuppressedFlushMs < CONN_WINDOW_MS) return
        lastSuppressedFlushMs = now
        val snapshot = synchronized(lock) {
            val s = traceSuppressed.entries.joinToString(",") { "${it.key}=${it.value}" }
            val last = traceLast.entries.joinToString(",") { "${it.key}:${it.value}" }
            traceSuppressed.clear()
            traceLast.clear()
            s to last
        }
        if (snapshot.first.isEmpty()) return
        marker("ADB_TRACE_SUPPRESSED n=${snapshot.first} rc=$lastJobRc last=${kv(snapshot.second.take(200))}")
    }

    /** Значение для `key=value`-лога: без пробелов и переводов строк. */
    fun kv(s: String): String {
        val t = s.replace('\n', '_').replace('\r', '_').replace(' ', '_')
        return if (t.isEmpty()) "-" else t.take(180)
    }
}
