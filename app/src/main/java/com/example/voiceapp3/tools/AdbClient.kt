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
 * Локальный ADB-клиент — точный повтор механизма из CentralEXAuto
 * (w0/m.l, w0/n case 0, kotlinx.coroutines.scheduling.h).
 *
 * Ключевые отличия от предыдущей версии (исправление корневой проблемы белого экрана
 * и неработающих root-команд):
 *
 *  1. Команды НИКОГДА не выполняются через Runtime.exec("sh") в качестве основного пути.
 *     sh используется только как последний аварийный фоллбэк с явной пометкой NO_ROOT.
 *  2. iter23 P0: миграция порта УДАЛЕНА полностью. Никаких `setprop *.adb.tcp.port`
 *     и `ctl.restart adbd` — в 1.0.44 именно они оставили машину без канала (рестарт
 *     adbd запускался изнутри adb-шелла и рвал собственную сессию). Работаем по 5555,
 *     65010 — только чтение.
 *  3. Всё сетевое — на отдельном рабочем потоке "AdbWorker". Из main только быстрый
 *     put в очередь и callback на Handler(Looper.getMainLooper()).
 *  4. Состояние (выбор рабочего порта, AUTH, connect-refused) логируется по шагам.
 *  5. Для синхронного вызова с фоновых потоков есть executeBlocking() — на main вызывать
 *     ЗАПРЕЩЕНО (стоит guard).
 */
object AdbClient {
    private const val TAG = "CENTRAL_AdbClient"

    const val ADB_PORT_ROOT = 65010
    const val ADB_PORT_DEFAULT = 5555
    private const val CNXN = 0x4e595343
    private const val AUTH = 0x48545541
    private const val OPEN = 0x4e45504f
    private const val OKAY = 0x4b414b4f
    private const val WRTE = 0x57525445
    private const val CLSE = 0x434c5345

    /**
     * Стратегия выбора порта.
     * iter22 §5.3: ОСНОВНОЙ канал — 5555 (adbd отдаёт uid=0 на 127.0.0.1:5555),
     * 65010 — вторичная попытка (миграция adbd, нужна не всегда).
     */
    private val PORTS = intArrayOf(ADB_PORT_DEFAULT, ADB_PORT_ROOT)

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
        ROOT_65010,    // 65010 уже слушал и дал root (только чтение, порты не трогаем)
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
            AppLog.e(TAG, "PORT65010 bootstrap fatal: ${t.message}", t)
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
     * iter23 P0: подготовка root-канала БЕЗ миграции порта.
     *
     * РЕФЕРЕНС: в эталоне (cex.apk) все исполнители (Lw0/dh#a, Lw0/zf#a,
     * QuickAccessFloaterService#I, CentralWatchdogService#c, Lw0/m#y) жёстко работают
     * по 5555; миграция вынесена в отдельный w0/m#l (ensurePortMigrated) и «на всякий
     * случай» не выполняется.
     *
     * Здесь: только ЧТЕНИЕ состояния + ретраи 5555. Никаких setprop adb.tcp.port и
     * никакого ctl.restart adbd (именно они убивали канал в 1.0.44: рестарт adbd
     * запускался изнутри adb-шелла и рвал собственную сессию).
     *
     * Ретраи: попытка 1 → пауза 2 с → попытка 2 → пауза 5 с → попытка 3 (~20 с суммарно),
     * таймаут CNXN 8 с. 65010 проверяется ТОЛЬКО на чтение — если он уже слушает и даёт
     * root, работаем через него.
     */
    private fun bootstrap() {
        state.set(ChannelState.BOOTING)
        AppLog.i(TAG, "ADB bootstrap: probe 5555 (3 attempts, no port migration — iter23 P0)")

        var lastErr = "no attempt"
        val pauses = intArrayOf(0, 2000, 5000)
        for ((idx, pause) in pauses.withIndex()) {
            if (pause > 0) {
                try { Thread.sleep(pause.toLong()) } catch (_: InterruptedException) {}
            }
            val t0 = SystemClock.uptimeMillis()
            val probe = tryProbePort(ADB_PORT_DEFAULT, 2000, 8000)
            val dt = SystemClock.uptimeMillis() - t0
            if (probe != null) {
                val uidLine = probe.first
                if (isRootUid(uidLine)) {
                    workingPort.set(ADB_PORT_DEFAULT)
                    state.set(ChannelState.ROOT_5555)
                    AppLog.i(TAG, "ADB_SELFTEST: port=5555 ok uid=0 dt=${dt}ms (attempt ${idx + 1})")
                    logAdbPortReadback(5555)
                    logInitDiagnostics()
                    return
                }
                lastErr = "port 5555 reachable but uid is not root: ${uidLine.take(120)}"
                AppLog.w(TAG, "ADB_SELFTEST: port=5555 answered but uid!=0 (attempt ${idx + 1}, dt=${dt}ms)")
            } else {
                lastErr = "port 5555: no CNXN response (dt=${dt}ms)"
                AppLog.w(TAG, "ADB_SELFTEST: port=5555 no answer (attempt ${idx + 1}, dt=${dt}ms)")
            }
        }

        // 65010 — только чтение: если он УЖЕ слушает (например, его подняли вручную), используем.
        val probeRoot = tryProbePort(ADB_PORT_ROOT, 1500, 5000)
        if (probeRoot != null && isRootUid(probeRoot.first)) {
            workingPort.set(ADB_PORT_ROOT)
            state.set(ChannelState.ROOT_65010)
            AppLog.i(TAG, "ADB_SELFTEST: port=65010 ok uid=0 (already listening; port properties NOT touched)")
            logAdbPortReadback(65010)
            logInitDiagnostics()
            return
        }

        AppLog.e(TAG, "ADB_SELFTEST: failed probes=5555,65010 last=$lastErr")
        // iter23 P0.7: одна итоговая строка для UI/отчёта — причина и что именно проверяли.
        AppLog.e(TAG, "ROOT_UNAVAILABLE reason=no_adb_root_channel probe=5555x3+65010 detail=$lastErr")
        adbSelfTestDiagnostics(lastErr)
        fallbackNoRoot(lastErr)
    }

    /**
     * Чтение свойств порта adbd — БЕЗ записи. Если в persist.adb.tcp.port застряло
     * чужое значение, только сообщаем: ADB_PORT_STUCK=<v>. Менять его изнутри сессии
     * нельзя (рестарт adbd рвёт соединение), поэтому — не трогаем.
     */
    private fun logAdbPortReadback(port: Int) {
        val per = runShellFallback("getprop persist.adb.tcp.port", 2000).trim()
        val cur = runShellFallback("getprop service.adb.tcp.port", 2000).trim()
        AppLog.i(TAG, "ADB_PORT_READBACK: working=$port persist.adb.tcp.port=${per.ifEmpty { "(empty)" }} service.adb.tcp.port=${cur.ifEmpty { "(empty)" }}")
        if (per.isNotEmpty() && per != "5555") {
            AppLog.w(TAG, "ADB_PORT_STUCK=$per (persist.adb.tcp.port не 5555; НЕ меняем — рестарт adbd из сессии запрещён)")
        }
    }

    /**
     * iter23 P0.4: диагностика канала БЕЗ root (обычный sh от uid=1000 — этот путь
     * работает всегда). Recv-Q в строке LISTEN покажет, копится ли очередь непринятых
     * соединений; init.svc.adbd=stopped покажет, что adbd не запущен.
     */
    private fun adbSelfTestDiagnostics(reason: String) {
        AppLog.e(TAG, "ADB_SELFTEST diagnostics — reason=$reason")
        val cmds = listOf(
            "getprop init.svc.adbd" to "init.svc.adbd",
            "getprop service.adb.tcp.port" to "service.adb.tcp.port",
            "getprop persist.adb.tcp.port" to "persist.adb.tcp.port",
            "getprop ro.adb.secure" to "ro.adb.secure",
            "ls -l /dev/socket/adbd 2>&1 || echo 'no /dev/socket/adbd'" to "adbd_socket",
            "cat /proc/net/tcp | awk 'NR==1 || \$2 ~ /:15B3\$/ || \$2 ~ /:FDF2\$/' | head -6" to "proc_net_tcp(5555|65010)",
        )
        for ((c, n) in cmds) {
            try {
                val r = runShellFallback(c, 2000).replace('\n', ' ').trim()
                AppLog.e(TAG, "ADB_SELFTEST diag [$n]: ${r.ifEmpty { "(empty)" }}")
            } catch (t: Throwable) {
                AppLog.w(TAG, "ADB_SELFTEST diag [$n] failed: ${t.message}")
            }
        }
    }

    /**
     * iter23 P0.5: дешёвые команды (pm path, getprop, settings get/put system,
     * grep /proc/mounts) не нуждаются в root и НЕ должны ходить через adb:
     * наше приложение уже работает как uid=1000(system). Выполняем через Runtime.exec.
     */
    fun shellLocal(command: String, timeoutMs: Int = 4000): Pair<Int, String> {
        return runShellFallbackWithRc(command, timeoutMs)
    }

    /**
     * Умное выполнение: если root-канал жив — через adb; иначе — локальный sh.
     * Только для команд, которым root НЕ нужен (expectRoot=false по смыслу).
     */
    fun executeSmart(command: String, timeoutMs: Int = 4000): Pair<Int, String> {
        val st = state.get()
        if (st == ChannelState.ROOT_5555 || st == ChannelState.ROOT_65010) {
            return execute(command, timeoutMs, expectRoot = false)
        }
        return runShellFallbackWithRc(command, timeoutMs)
    }

    private fun fallbackNoRoot(reason: String) {
        state.set(ChannelState.NO_ROOT_SH)
        AppLog.e(TAG, "PORT65010 ИТОГ = NO_ROOT ($reason). Команды пойдут через Runtime.sh uid=1000 (root не гарантирован).")
    }

    /**
     * Выполняет команду на рабочем канале.
     *
     * Жёсткий контракт (false-success ban, P0/P8):
     *  - root-команды (expectRoot=true) НИКОГДА не падают в Runtime.sh uid=1000,
     *    если нет работающего root-канала (состояние ROOT_65010/ROOT_5555) — сразу
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

        if (expectRoot && st !in setOf(ChannelState.ROOT_65010, ChannelState.ROOT_5555)) {
            // Жёсткий запрет: root-команда без root-канала — не падаем в sh.
            val reason = "ERR_ROOT_CHANNEL_UNAVAILABLE (state=$st)"
            AppLog.e(TAG, "VERIFY EXEC port=- rc=1 expectRoot=true cmd=${shortCmd} → FAILED ($reason)")
            return Pair(1, reason)
        }

        if (st in setOf(ChannelState.ROOT_65010, ChannelState.ROOT_5555)) {
            val port = workingPort.get()
            var out = try {
                execAdbSocket(cmd, port, timeoutMs)
            } catch (e: IOException) {
                AppLog.w(TAG, "VERIFY EXEC port=$port IOE: ${e.javaClass.simpleName}: ${e.message} — retrying alt port")
                // Только IOException (порт упал) — пробуем другой порт. Не fallback в sh.
                var alt: String? = null
                var altPort: Int = -1
                for (p in PORTS) if (p != port) {
                    try { alt = execAdbSocket(cmd, p, timeoutMs); altPort = p; break } catch (_: Exception) {}
                }
                if (alt != null) {
                    workingPort.set(altPort)
                    AppLog.i(TAG, "VERIFY EXEC switched to port=$altPort after IOException on $port")
                    return classify(altPort, true, shortCmd, alt, 0)
                }
                val msg = "ERR_ADB_IO: ${e.message} (no alt port worked)"
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

    /** Подключается к порту, выполняет id и возвращает (id-line, raw) или null если порт/ADB не работает. */
    private fun tryProbePort(port: Int, connectTimeoutMs: Int, totalTimeoutMs: Int): Pair<String, String>? = try {
        val out = execAdbSocket("id", port, totalTimeoutMs)
        Pair(out.trim(), out)
    } catch (e: Exception) {
        Log.d(TAG, "probe port=$port: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /**
     * Низкоуровневое выполнение одной shell-команды через ADB-сокет.
     * Поведение совпадает с kotlinx.coroutines.scheduling.h (порт CentralEXAuto).
     */
    private fun execAdbSocket(command: String, port: Int, timeoutMs: Int): String {
        val payload = ("shell:$command\u0000").toByteArray(StandardCharsets.UTF_8)
        // iter21 §1.3: не валим IllegalArgumentException в общий поток — возвращаем ERR_ADB_TOO_LONG.
        if (payload.size > 4096) {
            return "ERR_ADB_TOO_LONG: cmd_len=${payload.size} > 4096 (use executeScript for long scripts)"
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 2000)
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
                        AppLog.e(TAG, "PORT65010: $authMsg")
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
                    AUTH -> { val m="adbd AUTH after OPEN on port $port"; AppLog.e(TAG,"PORT65010: $m"); portDiagnosticDump(m); throw IOException(m) }
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
        AppLog.e(TAG, "PORT65010 diagnostic dump — reason=$reason")
        val diagCmds = listOf(
            "getenforce" to "selinux",
            "ls -l /dev/socket/adbd 2>&1 || echo '/dev/socket/adbd not found'" to "adbd_socket",
            "netstat -ltnp 2>/dev/null | grep -E '5555|65010' || ss -ltnp 2>/dev/null | grep -E '5555|65010' || echo 'no listeners'" to "listeners",
            "ps -A 2>/dev/null | grep -i adbd || ps 2>/dev/null | grep adbd" to "adbd_proc",
            "getprop ro.adb.secure; getprop service.adb.tcp.port; getprop persist.adb.tcp.port; getprop init.svc.adbd" to "adbd_props"
        )
        // diag через shell-фоллбэк (не через ADB — он и так сломан).
        for ((c, n) in diagCmds) {
            try {
                val r = runShellFallback(c, 2000).replace('\n',' ').trim()
                AppLog.e(TAG, "PORT65010 diag [$n]: $r")
            } catch (t: Throwable) {
                AppLog.w(TAG, "PORT65010 diag [$n] failed: ${t.message}")
            }
        }
        // logcat adbd
        try {
            val lc = runShellFallback("logcat -b all -d -t 30 -s adbd '*:S' 2>/dev/null | tail -30", 2000)
            AppLog.e(TAG, "PORT65010 logcat adbd tail:\n$lc")
        } catch (_: Throwable) {}
    }

    private fun logInitDiagnostics() {
        val port = workingPort.get()
        AppLog.i(TAG, "PORT65010 diagnostics: port=$port state=${state.get()}")
        val cmds = listOf(
            "id" to "shell_uid",
            "getprop service.adb.tcp.port; getprop persist.adb.tcp.port" to "adbd_props",
            "getenforce" to "selinux",
            "netstat -ltnp 2>/dev/null | grep -E '5555|65010' || ss -ltnp 2>/dev/null | grep -E '5555|65010' || (echo 'netstat/ss unavailable')" to "listeners",
            "iptables -t mangle -S POSTROUTING 2>/dev/null | grep -c ttl-set; ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -c hl-set" to "ttl_counts"
        )
        // Блокируем на рабочем потоке до 8с на каждый.
        for ((c, n) in cmds) {
            try {
                val r = execAdbSocket(c, port, 3000).replace('\n',' ').trim()
                AppLog.i(TAG, "PORT65010 diag [$n]: $r")
            } catch (e: Exception) {
                AppLog.w(TAG, "PORT65010 diag [$n]: ${e.javaClass.simpleName}: ${e.message}")
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
