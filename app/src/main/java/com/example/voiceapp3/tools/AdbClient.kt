package com.example.voiceapp3.tools

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Локальный ADB-клиент — точный повтор механизма из CentralEXAuto:
 * `Lkotlinx/coroutines/scheduling/h;-><init>(I)` (все инстанцирования — с 0x15b3 = 5555,
 * см. cex_smali/w0/k1.smali:880, w0/d0.smali:62, w0/g2.smali:231, w0/u1.smali:121).
 *
 * Ключевые отличия от предыдущей версии (исправление корневой проблемы белого экрана
 * и неработающих root-команд):
 *
 *  1. Команды НИКОГДА не выполняются через Runtime.exec("sh") в качестве основного пути.
 *     sh используется только как последний аварийный фоллбэк с явной пометкой NO_ROOT.
 *  2. iter24 P0.3: ОДИН порт — 5555. Миграции порта нет, второго порта нет: ни
 *     `setprop *.adb.tcp.port`, ни `ctl.restart adbd`, ни записей в adb-свойства
 *     вообще (только `getprop` для лога). В 1.0.44 именно миграция оставила машину
 *     без канала — рестарт adbd изнутри adb-шелла рвал собственную сессию.
 *  3. Всё сетевое — на отдельном рабочем потоке "AdbWorker". Из main только быстрый
 *     put в очередь и callback на Handler(Looper.getMainLooper()).
 *  4. Состояние (выбор рабочего порта, AUTH, connect-refused) логируется по шагам.
 *  5. Для синхронного вызова с фоновых потоков есть executeBlocking() — на main вызывать
 *     ЗАПРЕЩЕНО (стоит guard).
 */
object AdbClient {
    private const val TAG = "CENTRAL_AdbClient"

    const val ADB_PORT_DEFAULT = 5555
    private const val PROBE_CONNECT_MS = 1500
    private const val PROBE_TOTAL_MS = 3000
    /** iter25 P0-D: сырое чтение не должно держать поток 30 с — 8 с достаточно. */
    private const val RAW_READ_MS = 8000
    /** Порт локального adb-СЕРВЕРА (хост-сторона), найден в логе 1.0.46 в LISTEN. */
    private const val ADB_SERVER_PORT = 5037
    private const val PROP_DIAG = "persist.sys.hvacdigital.diag"
    private const val CNXN = 0x4e595343
    private const val AUTH = 0x48545541
    private const val OPEN = 0x4e45504f
    private const val OKAY = 0x4b414b4f
    private const val WRTE = 0x57525445
    private const val CLSE = 0x434c5345

    /**
     * iter24 P0.3: порт РОВНО ОДИН — 5555 (как у клиента эталона).
     * Второго порта и «alt-port retry» больше нет: переключение портов — это и была миграция.
     */
    private val PORTS = intArrayOf(ADB_PORT_DEFAULT)

    private val worker = object : Thread("AdbWorker") {
        init { isDaemon = true }
        override fun run() = workerLoop()
    }

    @Volatile private var started = false
    @Volatile private var ready = false
    private val readyLatch = CountDownLatch(1)

    private data class Job(
        val cmd: String,
        val timeoutMs: Int,
        val expectRoot: Boolean,
        val onResult: (Pair<Int, String>) -> Unit
    )

    private val queue = LinkedBlockingQueue<Job>()
    private val workingPort = AtomicInteger(0)

    enum class ChannelState {
        IDLE,
        BOOTING,
        ROOT_5555,     // рутовый канал на 5555 (штатный путь: adbd уже слушает 5555 и даёт uid=0)
        NO_ROOT_SH,    // нет ADB — все команды идут через Runtime.sh от uid=1000
        PERM_FAIL      // рут невозможен
    }
    private val state = AtomicReference(ChannelState.IDLE)

    /** Ленивый старт потока. Идёмпотентен. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        worker.start()
        AppLog.i(TAG, "worker started (thread=${worker.name})")
    }

    /**
     * Выполнить команду с блокировкой вызывающего потока.
     *
     * Безопасен с любых фоновых потоков. На главном потоке ЗАПРЕЩЁН:
     * в лог уйдёт ошибка и вернётся ERR_MAIN_THREAD_FORBIDDEN. При этом
     * команда будет поставлена в очередь и выполнена в фоне, но вызывающий
     * получит ошибку сразу (не ждёт результат с main).
     *
     * Все клиенты внутри приложения используют именно этот метод.
     */
    @JvmStatic
    fun execute(command: String, timeoutMs: Int = 20000, expectRoot: Boolean = true): Pair<Int, String> {
        start()
        val callerIsMain = Looper.myLooper() == Looper.getMainLooper()
        if (callerIsMain) {
            val shortCmd = command.take(120).replace("\n", " ")
            Log.e(TAG, "AdbClient.execute on main thread — это БАГ (команда: $shortCmd)", Exception("main-thread call"))
            AppLog.e(TAG, "VERIFY THREAD: AdbClient.execute вызван с main! Команда будет выполнена в фоне, но результат не вернётся вызывающему. cmd=$shortCmd")
            // Ставим в очередь на фоновое выполнение и возвращаем ошибку сразу, не блокируя main.
            queue.offer(Job(command, timeoutMs, expectRoot) {
                AppLog.d(TAG, "(async) main-dispatched command completed rc=${it.first} len=${it.second.length}")
            })
            return Pair(1, "ERR_MAIN_THREAD_FORBIDDEN (command queued for background execution)")
        }
        val latch = CountDownLatch(1)
        var res: Pair<Int, String> = Pair(1, "pending")
        queue.offer(Job(command, timeoutMs, expectRoot) { r -> res = r; latch.countDown() })
        val ok = latch.await((timeoutMs + 5000).toLong(), TimeUnit.MILLISECONDS)
        if (!ok) return Pair(137, "ERR_ADB_TIMEOUT after ${timeoutMs}ms")
        return res
    }

    /**
     * Асинхронный вариант (fire-and-forget) — результат игнорируется.
     */
    fun executeAsync(command: String, timeoutMs: Int = 20000, expectRoot: Boolean = true) {
        start()
        queue.offer(Job(command, timeoutMs, expectRoot) { })
    }

    /**
     * iter21 §1: заливка длинного скрипта в /data/local/tmp порциями по ≤3000 байт
     * (обход ADB shell payload-limit 4096 байт, который ломал TTL-скрипт в iter20).
     * Скрипт шлётся через `base64 -d >>` (чище printf-эскейпинга), после записи —
     * сверка размера через `wc -c`, запуск `sh <path>`, удаление файла.
     * Возвращает Pair<rc, out>, где out содержит stdout+stderr выполнения.
     */
    fun executeScript(name: String, script: String, timeoutMs: Int = 40000, expectRoot: Boolean = true): Pair<Int, String> {
        val bytes = script.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= 3500) {
            // Короткий скрипт — напрямую, без накладных расходов.
            return execute(script, timeoutMs, expectRoot)
        }
        val path = "/data/local/tmp/voiceapp3_${name}.sh"
        val sb = StringBuilder()
        sb.append("rm -f $path\n")
        // base64 кодируем весь скрипт в Java, затем льём кусками по 3000 байт (после декода 2250 байт)
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        var pos = 0
        while (pos < b64.length) {
            val chunk = b64.substring(pos, minOf(pos + 3000, b64.length))
            // echo '-n' с base64-чанком, декодируем в файл
            sb.append("echo '$chunk' | base64 -d >> $path\n")
            pos += 3000
        }
        sb.append("chmod 700 $path\n")
        sb.append("SZ=\$(wc -c < $path); echo \"SCRIPT_BYTES=\$SZ HEAD=\$(head -c 40 $path | tr '\\n' ' ')\"\n")
        sb.append("if [ \"\$SZ\" != \"${bytes.size}\" ]; then echo \"ERR_SCRIPT_UPLOAD expected=${bytes.size} actual=\$SZ\"; exit 99; fi\n")
        sb.append("sh $path\n")
        sb.append("RC=\$?; rm -f $path; exit \$RC\n")
        val upCmd = sb.toString()
        if (upCmd.toByteArray(StandardCharsets.UTF_8).size > 3800) {
            // Слишком большой даже upload-скрипт — грузить в несколько прогонов (append)
            return executeScriptMultipart(path, script, timeoutMs, expectRoot)
        }
        return execute(upCmd, timeoutMs + 10000, expectRoot)
    }

    private fun executeScriptMultipart(path: String, script: String, timeoutMs: Int, expectRoot: Boolean): Pair<Int, String> {
        // Пошаговая аппанд-запись (для скриптов ~10+ кб, когда upload-команда тоже >3800 байт)
        val bytes = script.toByteArray(StandardCharsets.UTF_8)
        val chunkSize = 2000
        var total = bytes.size.toLong()
        val (rc0, out0) = execute("rm -f $path; : > $path; echo cleared", 5000, expectRoot)
        if (rc0 != 0) return Pair(rc0, "ERR_SCRIPT_UPLOAD_CLEAR: $out0")
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            val chunk = String(bytes, offset, end - offset, StandardCharsets.UTF_8)
                .replace("'", "'\"'\"'")
            val (rc1, out1) = execute("cat >> $path <<'CEXCHUNK'\n${chunk}\nCEXCHUNK\n", 5000, expectRoot)
            if (rc1 != 0) return Pair(rc1, "ERR_SCRIPT_UPLOAD_CHUNK at offset=$offset: $out1")
            offset = end
        }
        val (rc2, out2) = execute(
            "chmod 700 $path; SZ=\$(wc -c < $path); echo \"SCRIPT_BYTES=\$SZ HEAD=\$(head -c 40 $path | tr '\\n' ' ')\"; " +
            "if [ \"\$SZ\" != \"${bytes.size}\" ]; then echo \"ERR_SCRIPT_UPLOAD expected=${bytes.size} actual=\$SZ\"; exit 99; fi; " +
            "sh $path; RC=\$?; rm -f $path; exit \$RC",
            timeoutMs + 10000, expectRoot)
        return Pair(rc2, out2)
    }

    /**
     * submit — асинхронное выполнение с доставкой результата в [onResult] на main-Looper.
     *
     * Используется из UI-кода (клики по тумблерам, обработчики системных broadcast),
     * когда нужно и выполнить команду в фоне, и обновить UI по результату.
     */
    fun submit(command: String, timeoutMs: Int = 20000, expectRoot: Boolean = true,
               onResult: ((Pair<Int, String>) -> Unit)? = null) {
        start()
        queue.offer(Job(command, timeoutMs, expectRoot) { r ->
            if (onResult != null) {
                Handler(Looper.getMainLooper()).post { onResult(r) }
            }
        })
    }

    // ─────────── worker loop ───────────

    private fun workerLoop() {
        try {
            bootstrap()
        } catch (t: Throwable) {
            Log.e(TAG, "bootstrap fatal", t)
            AppLog.e(TAG, "ADB bootstrap fatal: ${t.message}", t)
        }

        ready = true
        readyLatch.countDown()

        while (true) {
            val job = try { queue.take() } catch (_: InterruptedException) { return }
            val t0 = SystemClock.uptimeMillis()
            val out = try {
                runCommand(job.cmd, job.timeoutMs, job.expectRoot)
            } catch (t: Throwable) {
                Log.e(TAG, "runCommand error", t)
                Pair(1, "ERR_EXEC: ${t.javaClass.simpleName}: ${t.message}")
            }
            val dt = SystemClock.uptimeMillis() - t0
            AppLog.d(TAG, "← execute dt=${dt}ms rc=${out.first} len=${out.second.length} port=${workingPort.get()} state=${state.get()}")
            try {
                Handler(Looper.getMainLooper()).post { job.onResult(out) }
            } catch (_: Throwable) { job.onResult(out) }
        }
    }

    /**
     * iter24 P0.3: подготовка root-канала. ОДИН порт — 5555, ОДНА проба, без ретраев.
     *
     * РЕФЕРЕНС: CEX `Lkotlinx/coroutines/scheduling/h;-><init>(I)` создаётся только с
     * `const/16 0x15b3` (5555) — см. `Lw0/k1;->…` (cex_smali/w0/k1.smali:880),
     * `Lw0/d0;->…` (w0/d0.smali:62), `Lw0/g2;->…` (w0/g2.smali:231), `Lw0/u1;->…`
     * (w0/u1.smali:121). Второй порт в ADB-клиенте эталона отсутствует; единственное
     * место с миграцией — `Lw0/m;->l()` (ensurePortMigrated, cex_out/sources/w0/m.java:481-491),
     * который переписывает service/persist-свойства порта и делает `ctl.restart adbd`.
     * ⇒ `ЭТАЛОН: МЁРТВЫЙ КОД Lw0/m;->l()` — не переносим (разбор в REPORT_iter24.md).
     *
     * Параметры пробы — как в 1.0.43 (там канал реально работал):
     * `connect` 1500 мс, весь бюджет 3000 мс, без ретраев. Никаких записей в adb-свойства.
     *
     * Если проба не дала uid=0 — только ADBDIAG (диагноз). Лечения в 1.0.47 нет:
     * `ЭТАЛОН: CEX MainActivity#b0(Z) / La1/i#run — это РУЧНОЙ переключатель по удержанию,
     * а не автоматика на старте; на нашей ГУ пин 151 гасит USB (лог 1.0.46 + поведение
     * машины) — не переносим.`
     */
    private fun bootstrap() {
        state.set(ChannelState.BOOTING)
        AppLog.i(TAG, "ADB bootstrap: single probe 127.0.0.1:$ADB_PORT_DEFAULT (connect ${PROBE_CONNECT_MS}ms, total ${PROBE_TOTAL_MS}ms, no retries)")

        var p = fullProbe()
        AppLog.i(TAG, "ADB_PROBE port=$ADB_PORT_DEFAULT kind=${p.kind} dt=${p.dt}ms uid=${p.uid.ifEmpty { "-" }}")
        if (p.kind == KIND_ROOT) { channelUp(p); return }

        // P0.1: однозначный диагноз.
        runCatching { adbDiag(p) }.onFailure { AppLog.w(TAG, "ADBDIAG failed: ${it.message}") }

        // iter25 P0: лечения НЕТ. Шаги 1.0.46 (settings adb_enabled / tcpip через сокет /
        // запись в mt_gpio) убраны: GPIO 151 на этой ГУ гасит USB (лог 1.0.46 + поведение
        // машины), а два других шага были no-op/невозможны. Остаётся только диагноз.
        AppLog.i(TAG, "ADBFIX disabled (iter25: gpio/settings/tcpip отключены — лог 1.0.46: GPIO 151 гасит USB)")

        AppLog.e(TAG, "ADB_SELFTEST: failed probes=5555 kind=${p.kind} dt=${p.dt}ms err=${p.err ?: "-"}")
        AppLog.e(TAG, "ROOT_UNAVAILABLE reason=no_adb_root_channel probe=5555 detail=${p.err ?: p.kind}")
        fallbackNoRoot("probe=${p.kind} ${p.err ?: ""}")
    }

    // ─────────── проба канала ───────────

    private const val KIND_ROOT = "ROOT_5555"
    private const val KIND_NONROOT = "UP_NONROOT"
    private const val KIND_SILENT = "silent"
    private const val KIND_REFUSED = "refused"

    private data class Probe(val kind: String, val uid: String, val ctx: String, val dt: Long, val err: String?)

    /**
     * Полная проба канала: `connect(1500)` → CNXN → `OPEN shell:<cmd>`.
     * Бюджет 3000 мс. Классификация: ROOT_5555 / UP_NONROOT / silent / refused / error:<Class>.
     */
    private fun fullProbe(): Probe {
        val t0 = SystemClock.uptimeMillis()
        return try {
            val out = execAdbSocket(
                "id; id -Z 2>/dev/null; getenforce 2>/dev/null",
                ADB_PORT_DEFAULT, PROBE_TOTAL_MS, PROBE_CONNECT_MS,
            )
            val dt = SystemClock.uptimeMillis() - t0
            val root = isRootUid(out)
            val ctxLine = out.lineSequence().drop(1).firstOrNull { it.trimStart().startsWith("u:") }.orEmpty().trim()
            Probe(if (root) KIND_ROOT else KIND_NONROOT,
                out.lineSequence().firstOrNull().orEmpty().trim().take(160),
                ctxLine.ifEmpty { if (root) "uid=0" else "uid!=0" },
                dt, null)
        } catch (e: Throwable) {
            val dt = SystemClock.uptimeMillis() - t0
            Probe(classifyProbeError(e), "", "", dt, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** P0.2: тип ошибки больше НЕ глотается — и в файл лога, и в классификацию. */
    private fun classifyProbeError(e: Throwable): String = when {
        e is java.net.ConnectException -> KIND_REFUSED
        e is java.net.SocketTimeoutException -> KIND_SILENT
        e is java.net.NoRouteToHostException -> "no_route"
        (e.message ?: "").contains("CNXN timeout", ignoreCase = true) -> KIND_SILENT
        (e.message ?: "").contains("command timeout", ignoreCase = true) -> "opened_no_reply"
        else -> "error:${e.javaClass.simpleName}"
    }

    private fun channelUp(p: Probe) {
        workingPort.set(ADB_PORT_DEFAULT)
        state.set(ChannelState.ROOT_5555)
        AppLog.i(TAG, "ADB_SELFTEST: port=5555 ok uid=0 dt=${p.dt}ms ctx=${p.ctx.ifEmpty { "-" }}")
        logAdbPortReadback(ADB_PORT_DEFAULT)
        logInitDiagnostics()
    }

    /**
     * P0.1 — `ADBDIAG`: однозначный диагноз мёртвого канала. Всё через AppLog (в файл).
     * Шаги: контроль закрытых портов → сырое чтение от 5555 → таблицы ядра (tcp и tcp6,
     * поиск по hex :15B3) → снимок свойств → проверка записи нашего props без root.
     */
    private fun adbDiag(failed: Probe) {
        AppLog.e(TAG, "ADBDIAG start iter25: failedProbe=${failed.kind} dt=${failed.dt}ms err=${failed.err ?: "-"}")

        // 1) Контроль: заведомо закрытые порты. Эталон «ОС отказывает быстро».
        for (port in intArrayOf(5556, 65011)) {
            val t0 = SystemClock.uptimeMillis()
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), PROBE_CONNECT_MS) }
                AppLog.e(TAG, "ADBDIAG ctrl port=$port connect=OK dt=${SystemClock.uptimeMillis() - t0}ms (неожиданно открыт)")
            } catch (e: Throwable) {
                AppLog.e(TAG, "ADBDIAG ctrl port=$port ${e.javaClass.simpleName}: ${e.message} dt=${SystemClock.uptimeMillis() - t0}ms")
            }
        }

        // 2) Сырое чтение от 5555: различит CNXN / AUTH / молчание / отказ.
        rawReadProbe()

        // 3) Таблицы ядра: и tcp, и tcp6 (в 1.0.43 слушатель был в tcp6 `:::`), hex :15B3.
        for (tbl in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            val cmd = "(awk 'NR==1 || \$2 ~ /:15B3\$/ {print}' $tbl 2>/dev/null || cat $tbl 2>/dev/null) | head -5"
            val (rc, out) = try {
                runShellFallbackWithRc(cmd, 3000)
            } catch (t: Throwable) { Pair(1, "EXC:${t.message}") }
            val flat = out.replace('\n', ' ').take(300)
            AppLog.e(TAG, "ADBDIAG $tbl rc=$rc listen15B3=${out.lineSequence().any { it.contains(":15B3") }} out=${flat.ifEmpty { "(empty)" }}")
        }
        for (cmd in listOf("ss -ltn 2>/dev/null | head -10", "netstat -ltn 2>/dev/null | head -10")) {
            val (rc, out) = try { runShellFallbackWithRc(cmd, 3000) } catch (t: Throwable) { Pair(1, "EXC:${t.message}") }
            AppLog.e(TAG, "ADBDIAG [${cmd.substringBefore(" ")}] rc=$rc out=${out.replace('\n', ' ').take(240).ifEmpty { "(empty)" }}")
        }

        // 4) Снимок свойств — только чтение.
        for (prop in listOf("service.adb.tcp.port", "persist.adb.tcp.port", "init.svc.adbd", "ro.adb.secure", "ro.secure")) {
            val (rc, out) = try { runShellFallbackWithRc("getprop $prop", 2500) } catch (t: Throwable) { Pair(1, "EXC:${t.message}") }
            AppLog.e(TAG, "ADBDIAG prop $prop rc=$rc val=${out.trim().ifEmpty { "(empty)" }}")
        }

        // 5) Можно ли писать НАШ props из uid=1000 без adb (не глобальные настройки и не
        //    adb-свойства — проверяется только запись persist.sys.hvacdigital.diag).
        val (wRc, wOut) = try {
            runShellFallbackWithRc("setprop $PROP_DIAG 46; getprop $PROP_DIAG", 3000)
        } catch (t: Throwable) { Pair(1, "EXC:${t.message}") }
        AppLog.e(TAG, "ADBDIAG setprop $PROP_DIAG: rc=$wRc readback=${wOut.trim().ifEmpty { "(empty)" }}")

        // P0-E.1: три соединения подряд — adbd обслуживает accept-очередь или нет.
        AppLog.e(TAG, "ADBDIAG raw5555 multi: 3 соединения по 24 байта (окно ${RAW_READ_MS}ms каждое)")
        for (i in 1..3) {
            val t0 = SystemClock.uptimeMillis()
            val r = runCatching { rawReadOnce() }
            val res = r.getOrElse { "exc=${it.javaClass.simpleName}:${it.message}" }
            AppLog.e(TAG, "ADBDIAG raw5555 #$i $res dt=${SystemClock.uptimeMillis() - t0}ms")
        }

        // P0-E.2: на устройстве слушает adb-СЕРВЕР (127.0.0.1:5037) — спросим у него host:devices.
        smartSocketProbe()

        // P0-E.3: кто такой adbd — PID и unix-сокеты (только чтение).
        for ((cmd, name) in listOf(
            "ps -A 2>/dev/null | grep -i adbd || ps 2>/dev/null | grep -i adbd" to "ps adbd",
            "cat /proc/net/unix 2>/dev/null | grep -i adbd" to "unix adbd",
        )) {
            val (rc, out) = try { runShellFallbackWithRc(cmd, 3000) } catch (t: Throwable) { Pair(1, "EXC:${t.message}") }
            AppLog.e(TAG, "ADBDIAG $name rc=$rc out=${out.replace('\n', ' ').take(240).ifEmpty { "(empty)" }}")
        }

        // P0-E.4: состояние USB — ТОЛЬКО ЧТЕНИЕ, для будущего разбора.
        for (prop in listOf("sys.usb.state", "persist.sys.usb.config", "sys.usb.config")) {
            val (rc, out) = try { runShellFallbackWithRc("getprop $prop", 2500) } catch (t: Throwable) { Pair(1, "EXC:${t.message}") }
            AppLog.e(TAG, "ADBDIAG prop $prop rc=$rc val=${out.trim().ifEmpty { "(empty)" }}")
        }

        AppLog.e(TAG, "ADBDIAG end")
    }

    /**
     * Одно сырое соединение с 5555: connect → CNXN → первые 24 байта.
     * Возвращает короткое описание результата (без записи в лог — это делает вызывающий).
     */
    private fun rawReadOnce(): String {
        Socket().use { s ->
            val t0 = SystemClock.uptimeMillis()
            s.connect(InetSocketAddress("127.0.0.1", ADB_PORT_DEFAULT), PROBE_CONNECT_MS)
            val connectDt = SystemClock.uptimeMillis() - t0
            writeMsg(s.getOutputStream(), CNXN, 0x01000000, 4096, "host::central-geely\u0000".toByteArray(StandardCharsets.UTF_8))
            s.soTimeout = RAW_READ_MS
            val buf = ByteArray(24)
            var n = 0
            try {
                val ins = s.getInputStream()
                while (n < 24) {
                    val r = ins.read(buf, n, 24 - n)
                    if (r < 0) break
                    n += r
                }
            } catch (e: Throwable) {
                return "n=$n hex=${hexOf(buf, n)} abort=${e.javaClass.simpleName} connectDt=${connectDt}ms"
            }
            if (n < 24) return "n=$n hex=${hexOf(buf, n)} eof connectDt=${connectDt}ms"
            val cmd = ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            return "n=24 cmd=${cmdName(cmd)} hex=${hexOf(buf, 24)} connectDt=${connectDt}ms"
        }
    }

    /**
     * P0-E.2: умный запрос к adb-серверу на 127.0.0.1:5037 в формате smart-socket
     * (`4 hex-цифры длины` + строка). Только чтение: `host:devices` и `host:version`.
     */
    private fun smartSocketProbe() {
        for (req in listOf("host:version", "host:devices")) {
            val t0 = SystemClock.uptimeMillis()
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", ADB_SERVER_PORT), PROBE_CONNECT_MS)
                    s.soTimeout = 3000
                    val os = s.getOutputStream()
                    os.write("%04x%s".format(req.length, req).toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                    val ins = s.getInputStream()
                    val head = ByteArray(4)
                    var got = 0
                    while (got < 4) {
                        val r = ins.read(head, got, 4 - got)
                        if (r < 0) break
                        got += r
                    }
                    val status = if (got == 4) String(head, StandardCharsets.UTF_8) else "(eof)"
                    val len = if (got == 4) (String(head, StandardCharsets.UTF_8).toIntOrNull(16) ?: 0) else 0
                    val body = ByteArray(len.coerceIn(0, 512))
                    var bn = 0
                    while (bn < body.size) {
                        val r = ins.read(body, bn, body.size - bn)
                        if (r < 0) break
                        bn += r
                    }
                    val payload = if (bn > 0) String(body, 0, bn, StandardCharsets.UTF_8) else ""
                    AppLog.e(TAG, "ADBDIAG 5037 $req → status=$status len=$len body=${payload.replace('\n', '|').take(300).ifEmpty { "(empty)" }} dt=${SystemClock.uptimeMillis() - t0}ms")
                }
            } catch (e: Throwable) {
                AppLog.e(TAG, "ADBDIAG 5037 $req ${e.javaClass.simpleName}: ${e.message} dt=${SystemClock.uptimeMillis() - t0}ms")
            }
        }
    }

    /** Сырое чтение 24 байт от 5555 после CNXN — без разбора протокола. */
    private fun rawReadProbe() {
        val t0 = SystemClock.uptimeMillis()
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", ADB_PORT_DEFAULT), PROBE_CONNECT_MS)
                val connectDt = SystemClock.uptimeMillis() - t0
                val os = s.getOutputStream()
                writeMsg(os, CNXN, 0x01000000, 4096, "host::central-geely\u0000".toByteArray(StandardCharsets.UTF_8))
                s.soTimeout = RAW_READ_MS
                val buf = ByteArray(24)
                var n = 0
                try {
                    val ins = s.getInputStream()
                    while (n < 24) {
                        val r = ins.read(buf, n, 24 - n)
                        if (r < 0) break
                        n += r
                    }
                } catch (e: Throwable) {
                    AppLog.e(TAG, "ADBDIAG raw5555 rx n=$n hex=${hexOf(buf, n)} abort=${e.javaClass.simpleName}: ${e.message} connectDt=${connectDt}ms dt=${SystemClock.uptimeMillis() - t0}ms")
                    return@use
                }
                val dt = SystemClock.uptimeMillis() - t0
                if (n == 0) {
                    AppLog.e(TAG, "ADBDIAG raw5555 rx n=0 EOF (adbd закрыл соединение) connectDt=${connectDt}ms dt=${dt}ms")
                } else if (n < 24) {
                    AppLog.e(TAG, "ADBDIAG raw5555 rx n=$n hex=${hexOf(buf, n)} (короткий ответ) connectDt=${connectDt}ms dt=${dt}ms")
                } else {
                    val cmd = ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    AppLog.e(TAG, "ADBDIAG raw5555 rx n=24 cmd=${cmdName(cmd)} hex=${hexOf(buf, 24)} connectDt=${connectDt}ms dt=${dt}ms")
                }
            }
        } catch (e: Throwable) {
            AppLog.e(TAG, "ADBDIAG raw5555 ${e.javaClass.simpleName}: ${e.message} dt=${SystemClock.uptimeMillis() - t0}ms")
        }
    }

    private fun hexOf(b: ByteArray, n: Int): String =
        (0 until n.coerceAtMost(b.size)).joinToString(" ") { "%02x".format(b[it]) }.ifEmpty { "(none)" }

    private fun fallbackNoRoot(reason: String) {
        state.set(ChannelState.NO_ROOT_SH)
        AppLog.e(TAG, "ADB ИТОГ = NO_ROOT ($reason). Команды пойдут через Runtime.sh uid=1000 (root не гарантирован).")
    }

    /**
     * Выполняет команду на рабочем канале.
     *
     * Жёсткий контракт (false-success ban, P0/P8):
     *  - root-команды (expectRoot=true) НИКОГДА не падают в Runtime.sh uid=1000,
     *    если нет работающего root-канала (состояние ROOT_5555) — сразу
     *    возвращается ERR_ROOT_CHANNEL_UNAVAILABLE.
     *  - alt-port retry делается ТОЛЬКО при IOException на работающем порту (не
     *    при ошибках в выводе), и никогда — для fallback в sh.
     *  - classifyOutput(out) детектирует маркеры провала (Permission denied,
     *    FAILBIND, xtables lock, No such file, must be root, err:, uid!=0,
     *    Read-only file system, adbd error) и превращает их в rc=1 с понятной
     *    причиной.
     *  - Каждый вызов логирует VERIFY EXEC с портом/rc/expectRoot/первыми
     *    буквами команды и вердиктом SUCCESS/FAILED(reason).
     */
    private fun runCommand(cmd: String, timeoutMs: Int, expectRoot: Boolean): Pair<Int, String> {
        val shortCmd = cmd.replace("\\s+".toRegex(), " ").take(160)
        val st = state.get()

        if (expectRoot && st != ChannelState.ROOT_5555) {
            // Жёсткий запрет: root-команда без root-канала — не падаем в sh.
            val reason = "ERR_ROOT_CHANNEL_UNAVAILABLE (state=$st)"
            AppLog.e(TAG, "VERIFY EXEC port=- rc=1 expectRoot=true cmd=${shortCmd} → FAILED ($reason)")
            return Pair(1, reason)
        }

        if (st == ChannelState.ROOT_5555) {
            val port = workingPort.get()
            var out = try {
                execAdbSocket(cmd, port, timeoutMs)
            } catch (e: IOException) {
                AppLog.w(TAG, "VERIFY EXEC port=$port IOE: ${e.javaClass.simpleName}: ${e.message} — retrying alt port")
                // iter24 P0.3: альтернативного порта нет — переключение портов = миграция.
                // IOException на 5555 означает, что канал умер: честно сообщаем и уходим в NO_ROOT.
                state.compareAndSet(ChannelState.ROOT_5555, ChannelState.NO_ROOT_SH)
                val msg = "ERR_ADB_IO: ${e.message} (port 5555 dropped, channel closed)"
                AppLog.e(TAG, "VERIFY EXEC port=$port rc=1 expectRoot=$expectRoot cmd=${shortCmd} → FAILED ($msg)")
                return Pair(1, msg)
            } catch (e: Exception) {
                val msg = "ERR_ADB: ${e.javaClass.simpleName}: ${e.message}"
                AppLog.e(TAG, "VERIFY EXEC port=$port rc=1 expectRoot=$expectRoot cmd=${shortCmd} → FAILED ($msg)")
                return Pair(1, msg)
            }
            return classify(port, expectRoot, shortCmd, out, 0)
        }

        // НЕ-root команда и нет ADB — допускается Runtime.sh (от uid=1000, для отладочных команд).
        val (rc, out) = runShellFallbackWithRc(cmd, timeoutMs)
        return classify(0, expectRoot, shortCmd, out, rc)
    }

    private fun classify(port: Int, expectRoot: Boolean, shortCmd: String, out: String, rc: Int): Pair<Int, String> {
        val (failReason, isFail) = classifyOutput(out, expectRoot)
        val finalRc = if (rc != 0) rc else if (isFail) 1 else 0
        val verdict = if (finalRc == 0) "SUCCESS" else "FAILED (${failReason ?: "rc=$rc"})"
        val portLabel = if (port == 0) "sh" else port.toString()
        AppLog.i(TAG, "VERIFY EXEC port=$portLabel rc=$finalRc expectRoot=$expectRoot cmd=${shortCmd} → $verdict")
        if (finalRc != 0) {
            val tail = out.replace('\n', ' ').take(300)
            AppLog.w(TAG, "VERIFY EXEC tail: $tail")
        }
        return Pair(finalRc, out)
    }

    /**
     * classifyOutput — ищет в выводе известные маркеры провала и возвращает
     * (читаемая причина, true — это провал).
     *
     * Список маркеров из лога и из w0/n:
     *  - Permission denied / avc: denied / Operation not permitted — SELinux/права
     *  - xtables lock — iptables удерживается другим процессом (нужен -w)
     *  - No such file or directory / Read-only file system
     *  - FAILBIND — наш маркер неудачного mount --bind
     *  - err: / Exec error / adbd error / must be root
     *  - sh: / sh: line / /system/bin/sh: — ошибка интерпретатора
     *  - uid=1000 / uid=2000 / uid=0 без полного "uid=0(root)" при expectRoot
     *  - iptables/ip6tables: ... No chain/target/match by that name
     */
    private fun classifyOutput(out: String, expectRoot: Boolean): Pair<String?, Boolean> {
        val lower = out.lowercase()
        return when {
            lower.contains("permission denied") -> "Permission denied" to true
            lower.contains("avc: denied") -> "SELinux avc denied" to true
            lower.contains("operation not permitted") -> "Operation not permitted" to true
            lower.contains("xtables lock") -> "xtables lock held" to true
            lower.contains("no such file or directory") -> "No such file/dir" to true
            lower.contains("read-only file system") -> "Read-only filesystem" to true
            lower.contains("failbind") -> "FAILBIND (mount failed)" to true
            lower.contains("exec error") -> "Exec error" to true
            lower.contains("adbd error") -> "adbd error" to true
            lower.contains("must be root") -> "must be root" to true
            lower.contains("sh:") || lower.contains("/system/bin/sh:") -> "sh error" to true
            lower.contains("no chain/target/match") -> "iptables target missing" to true
            lower.contains("err:") && !lower.contains("errno: 0") -> "err marker in output" to true
            expectRoot && !isRootUid(out) && (lower.contains("uid=") || lower.contains("uid=0")) ->
                // в выводе есть uid-строка, но это не uid=0(root)
                "uid != 0(root) (got: ${findUid(out) ?: "unknown"})" to true
            else -> null to false
        }
    }

    /**
     * isRootUid — строгая проверка на uid=0.
     *
     * `uid=0` в выводе `id` всегда идёт как `uid=0(root)` или как начало
     * `uid=0 gid=0(root)`. Важно не спутать с `uid=1000(system)`, где подстрока
     * "uid=0" тоже встречается в числе 1000 — используем regex \b.
     */
    private fun isRootUid(output: String): Boolean {
        val line = output.lineSequence().firstOrNull { it.trimStart().startsWith("uid=") } ?: return false
        return Regex("""^uid=0(\(root\))?\b""").containsMatchIn(line.trimStart())
    }

    private fun findUid(output: String): String? =
        output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("uid=") }

    private fun runShellFallback(cmd: String, timeoutMs: Int): String = runShellFallbackWithRc(cmd, timeoutMs).second

    private fun runShellFallbackWithRc(cmd: String, timeoutMs: Int): Pair<Int, String> = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh","-c",cmd))
        val worker2 = Thread({ p.waitFor() }, "sh-wait")
        worker2.isDaemon = true; worker2.start()
        worker2.join(timeoutMs.toLong())
        if (worker2.isAlive) { p.destroyForcibly(); Pair(137, "(timeout after ${timeoutMs}ms)") }
        else {
            val out = (p.inputStream.bufferedReader().readText() + "\n" + p.errorStream.bufferedReader().readText()).trim()
            Pair(p.exitValue(), out)
        }
    } catch (t: Throwable) {
        Pair(1, "sh-fatal: ${t.message}")
    }

    /**
     * Подключается к порту, выполняет id и возвращает (id-line, raw) или null если порт/ADB не работает.
     * iter24 P0.2: тип исключения пишется в ФАЙЛ лога, а не в logcat — иначе ECONNREFUSED
     * и «adbd молчит» в отчёте неотличимы.
     */
    private fun tryProbePort(port: Int, connectTimeoutMs: Int, totalTimeoutMs: Int): Pair<String, String>? = try {
        val out = execAdbSocket("id", port, totalTimeoutMs, connectTimeoutMs)
        Pair(out.trim(), out)
    } catch (e: Throwable) {
        AppLog.e(TAG, "ADB_PROBE port=$port ${e.javaClass.simpleName}: ${e.message} (kind=${classifyProbeError(e)})")
        null
    }

    /**
     * Низкоуровневое выполнение одной shell-команды через ADB-сокет.
     * Поведение совпадает с kotlinx.coroutines.scheduling.h (порт CentralEXAuto).
     */
    private fun execAdbSocket(command: String, port: Int, timeoutMs: Int, connectTimeoutMs: Int = PROBE_CONNECT_MS): String {
        val payload = ("shell:$command\u0000").toByteArray(StandardCharsets.UTF_8)
        // iter21 §1.3: не валим IllegalArgumentException в общий поток — возвращаем ERR_ADB_TOO_LONG.
        if (payload.size > 4096) {
            return "ERR_ADB_TOO_LONG: cmd_len=${payload.size} > 4096 (use executeScript for long scripts)"
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), connectTimeoutMs)
            s.soTimeout = timeoutMs
            val dis = DataInputStream(s.getInputStream())
            val os = s.getOutputStream()
            val banner = "host::central-geely\u0000".toByteArray(StandardCharsets.UTF_8)
            writeMsg(os, CNXN, 0x01000000, 4096, banner)
            // Ждём CNXN в ответ; AUTH — ошибка (нужна авторизация по ключу).
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw IOException("ADB CNXN timeout")
                s.soTimeout = left.toInt().coerceIn(1, Int.MAX_VALUE)
                val msg = readMsg(dis)
                when (msg.cmd) {
                    CNXN -> break
                    AUTH -> {
                        val authMsg = "adbd requires RSA auth on port $port (banner=${msg.txt()})"
                        AppLog.e(TAG, "ADB: $authMsg")
                        portDiagnosticDump(authMsg)
                        throw IOException(authMsg)
                    }
                    else -> Log.d(TAG, "ADB $port rx ${cmdName(msg.cmd)} during CNXN (ignored)")
                }
            }
            writeMsg(os, OPEN, 1, 0, payload)
            var localId = 0
            val out = ByteArrayOutputStream()
            while (System.currentTimeMillis() < deadline) {
                val left = deadline - System.currentTimeMillis()
                s.soTimeout = left.toInt().coerceIn(1, Int.MAX_VALUE)
                val msg = readMsg(dis)
                when (msg.cmd) {
                    OKAY -> { localId = msg.arg0 }
                    WRTE -> {
                        out.write(msg.payload)
                        writeMsg(os, OKAY, 1, msg.arg0, ByteArray(0))
                    }
                    CLSE -> {
                        writeMsg(os, CLSE, 1, localId, ByteArray(0))
                        return out.toString("UTF-8").trim()
                    }
                    AUTH -> { val m="adbd AUTH after OPEN on port $port"; AppLog.e(TAG,"ADB: $m"); portDiagnosticDump(m); throw IOException(m) }
                    else -> Log.d(TAG, "ADB $port rx ${cmdName(msg.cmd)} (ignored)")
                }
            }
            throw IOException("ADB command timeout on port $port after ${timeoutMs}ms, got ${out.size()} bytes")
        }
    }

    // ─────────── Protocol ───────────

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val payload: ByteArray) {
        fun txt() = try { String(payload, Charsets.UTF_8).replace("\n","\\n").take(200) } catch (_: Throwable) { hex() }
        fun hex() = payload.take(48).joinToString(" ") { "%02x".format(it) } + if (payload.size > 48) " …(${payload.size})" else ""
    }

    private fun cmdName(c: Int) = when(c) {
        CNXN->"CNXN"; AUTH->"AUTH"; OPEN->"OPEN"; OKAY->"OKAY"; WRTE->"WRTE"; CLSE->"CLSE"
        else -> "0x"+Integer.toHexString(c)
    }

    private fun readMsg(dis: DataInputStream): Msg {
        val hdr = ByteArray(24); dis.readFully(hdr)
        val buf = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = buf.getInt(); val arg0 = buf.getInt(); val arg1 = buf.getInt()
        val len = buf.getInt(); val sum = buf.getInt()
        val magic = buf.getInt()
        check(cmd.inv() == magic) { "Bad ADB magic cmd=0x${Integer.toHexString(cmd)}" }
        val body = ByteArray(len); if (len > 0) dis.readFully(body)
        var s = 0; for (b in body) s += b.toInt() and 0xFF
        check(s == sum) { "Bad ADB checksum ($s vs $sum)" }
        return Msg(cmd, arg0, arg1, body)
    }

    private fun writeMsg(os: OutputStream, cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cmd); buf.putInt(arg0); buf.putInt(arg1); buf.putInt(payload.size)
        var sum = 0; for (b in payload) sum += b.toInt() and 0xFF
        buf.putInt(sum); buf.putInt(cmd.inv())
        os.write(buf.array()); os.write(payload); os.flush()
    }

    /**
     * portDiagnosticDump — снимает диагностический слепок состояния adbd при ошибке.
     * Вызывается из bootstrap когда не удалось поднять порт, и когда AUTH сработал.
     */
    private fun portDiagnosticDump(reason: String) {
        AppLog.e(TAG, "ADB diagnostic dump — reason=$reason")
        val diagCmds = listOf(
            "getenforce" to "selinux",
            "ls -l /dev/socket/adbd 2>&1 || echo '/dev/socket/adbd not found'" to "adbd_socket",
            "netstat -ltnp 2>/dev/null | grep -E '5555|:15B3' || ss -ltnp 2>/dev/null | grep -E '5555|:15B3' || echo 'no listeners'" to "listeners",
            "ps -A 2>/dev/null | grep -i adbd || ps 2>/dev/null | grep adbd" to "adbd_proc",
            "getprop ro.adb.secure; getprop service.adb.tcp.port; getprop persist.adb.tcp.port; getprop init.svc.adbd" to "adbd_props"
        )
        // diag через shell-фоллбэк (не через ADB — он и так сломан).
        for ((c, n) in diagCmds) {
            try {
                val r = runShellFallback(c, 2000).replace('\n',' ').trim()
                AppLog.e(TAG, "ADB diag [$n]: $r")
            } catch (t: Throwable) {
                AppLog.w(TAG, "ADB diag [$n] failed: ${t.message}")
            }
        }
        // logcat adbd
        try {
            val lc = runShellFallback("logcat -b all -d -t 30 -s adbd '*:S' 2>/dev/null | tail -30", 2000)
            AppLog.e(TAG, "ADB logcat adbd tail: $lc")
        } catch (_: Throwable) {}
    }

    private fun logInitDiagnostics() {
        val port = workingPort.get()
        AppLog.i(TAG, "ADB diagnostics: port=$port state=${state.get()}")
        val cmds = listOf(
            "id" to "shell_uid",
            "getprop service.adb.tcp.port; getprop persist.adb.tcp.port" to "adbd_props",
            "getenforce" to "selinux",
            "netstat -ltnp 2>/dev/null | grep -E '5555|:15B3' || ss -ltnp 2>/dev/null | grep -E '5555|:15B3' || (echo 'netstat/ss unavailable')" to "listeners",
            "iptables -t mangle -S POSTROUTING 2>/dev/null | grep -c ttl-set; ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -c hl-set" to "ttl_counts"
        )
        // Блокируем на рабочем потоке до 8с на каждый.
        for ((c, n) in cmds) {
            try {
                val r = execAdbSocket(c, port, 3000).replace('\n',' ').trim()
                AppLog.i(TAG, "ADB diag [$n]: $r")
            } catch (e: Exception) {
                AppLog.w(TAG, "ADB diag [$n]: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** Дождаться готовности канала (вызывающий поток блокируется до ready). */
    fun awaitReady(timeoutMs: Long = 15000): Boolean {
        start()
        return try { readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS); ready } catch (_: Throwable) { false }
    }

    fun currentPort(): Int = workingPort.get()
    fun currentState(): ChannelState = state.get()

    /**
     * Проверка root-канала для UI (вкладка «Инструменты»): выполняет `id` и
     * возвращает короткую сводку — порт, uid строка, статус UP/DOWN с причиной.
     * Никогда не выполняется на main-thread; на случай вызова с main — возвращает
     * ERR_MAIN_THREAD немедленно.
     */
    fun probeRootChannel(): RootProbeResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return RootProbeResult(-1, "ERR_MAIN_THREAD", "main thread", "probe must run off main")
        }
        start()
        if (!ready) awaitReady(15000)
        val port = workingPort.get()
        val st = state.get()
        return try {
            val (rc, out) = execOnChannel("id", timeoutMs = 5000, expectRoot = false)
            val uidLine = out.lineSequence().firstOrNull { it.trimStart().startsWith("uid=") } ?: out.take(120)
            RootProbeResult(port, st.name, uidLine, if (isRootUid(out)) "UP (uid=0)" else "DOWN (not root)")
        } catch (e: Exception) {
            RootProbeResult(port, st.name, e.javaClass.simpleName + ": " + e.message, "DOWN")
        }
    }

    private fun execOnChannel(cmd: String, timeoutMs: Int, expectRoot: Boolean): Pair<Int, String> =
        runCommand(cmd, timeoutMs, expectRoot)

    data class RootProbeResult(
        val port: Int,
        val state: String,
        val uidLine: String,
        val verdict: String
    )
}
