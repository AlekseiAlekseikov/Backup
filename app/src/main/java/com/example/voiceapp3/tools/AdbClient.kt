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
 *  2. Миграция на порт 65010 (`ctl.restart adbd`) производится ЧЕРЕЗ ADB-сокет 5555,
 *     а не через Runtime.sh (у uid=1000 system_app нет права на ctl.restart через sh,
 *     но есть через adb shell, где adbd работает от root).
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

    /** Стратегия выбора порта как в w0/n.case 0: сначала кэш, потом 65010, потом 5555. */
    private val PORTS = intArrayOf(ADB_PORT_ROOT, ADB_PORT_DEFAULT)

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
        ROOT_65010,    // основной канал — рутовый порт 65010
        ROOT_5555,     // рутовый канал на 5555 (миграция не прошла, но 5555 даёт root)
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
        @Volatile var res: Pair<Int, String> = Pair(1, "pending")
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
     * Первоначальная подготовка root-канала. Пытается в точности повторить то,
     * что делает w0/m.l в CentralEXAuto:
     *  1. Проверить, работает ли уже какой-то из портов (65010, 5555) через ADB-сокет.
     *  2. Если работает и даёт id=0(root) — отлично, готово.
     *  3. Если 65010 не слушает — попробовать УЖЕ СУЩЕСТВУЮЩИЙ ADB-сокет (5555) чтобы
     *     запустить скрипт миграции (setprop + ctl.restart adbd).
     *  4. Подождать прослушивания 65010 после рестарта adbd.
     *  5. Если ни один порт не даёт root — падаем в NO_ROOT_SH (Runtime.sh как аварийка).
     */
    private fun bootstrap() {
        state.set(ChannelState.BOOTING)
        AppLog.i(TAG, "PORT65010 bootstrap starting on thread=${Thread.currentThread().name}")

        // Шаг 1: попробовать порты «как есть» (как в w0/n — сначала рабочий порт, потом 65010, потом 5555)
        val probePorts = mutableListOf<Int>()
        val cached = workingPort.get()
        if (cached != 0) probePorts.add(cached)
        for (p in PORTS) if (p != cached) probePorts.add(p)

        for (port in probePorts) {
            val t0 = SystemClock.uptimeMillis()
            AppLog.d(TAG, "PORT65010 probe port=$port (connect + CNXN)...")
            val probe = tryProbePort(port, connectTimeoutMs = 1500, totalTimeoutMs = 3000)
            val dt = SystemClock.uptimeMillis() - t0
            if (probe != null) {
                workingPort.set(port)
                val (uid, _) = probe
                if (isRootUid(uid)) {
                    state.set(if (port == ADB_PORT_ROOT) ChannelState.ROOT_65010 else ChannelState.ROOT_5555)
                    AppLog.i(TAG, "PORT65010 ИТОГ = UP on port=$port, shell uid=$uid (dt=${dt}ms) — root канал готов")
                    logInitDiagnostics()
                    return
                } else {
                    AppLog.w(TAG, "PORT65010 port=$port reachable but shell NOT root: $uid (dt=${dt}ms)")
                }
            } else {
                AppLog.w(TAG, "PORT65010 port=$port unreachable (dt=${dt}ms)")
            }
        }

        // Шаг 2: ни один порт не дал root с ходу. Пробуем поднять 65010 через 5555.
        // ТОНКОСТЬ: даже если 5555 не даёт root через shell — его CNXN мог бы пройти.
        // У многих adbd можно слать shell-команды до setuid, если слушает на loopback.
        // Проверим ещё раз 5555 отдельно без ожидания uid=0 — для выполнения setprop/ctl.restart.
        AppLog.i(TAG, "PORT65010 миграция: пробуем выполнить setprop+ctl.restart adbd через ADB 5555...")
        val migrateScript = """
            cur="$(getprop service.adb.tcp.port)"
            per="$(getprop persist.adb.tcp.port)"
            echo "PORT65010: step1 cur=$cur per=$per"
            if [ "$cur" = "65010" ] && [ "$per" = "65010" ]; then
              echo "PORT65010: step2 props already 65010"
            else
              setprop persist.adb.tcp.port 65010
              setprop service.adb.tcp.port 65010
              echo "PORT65010: step2 setprop rc=$? -> $(getprop persist.adb.tcp.port)/$(getprop service.adb.tcp.port)"
              (sleep 1; setprop ctl.restart adbd >/dev/null 2>&1) &
              echo "PORT65010: step3 ctl.restart adbd dispatched"
            fi
            echo "PORT65010: step4 id=$(id)"
        """.trimIndent()

        var migrateOut: String? = null
        var migratedVia: Int? = null
        for (port in intArrayOf(ADB_PORT_DEFAULT, ADB_PORT_ROOT)) {
            migrateOut = try {
                execAdbSocket(migrateScript, port, 10000)
            } catch (e: Exception) {
                AppLog.w(TAG, "PORT65010 migrate script via port=$port: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (migrateOut != null) { migratedVia = port; break }
        }

        if (migrateOut != null) {
            AppLog.i(TAG, "PORT65010 migrate via port=$migratedVia output:\n$migrateOut")
        } else {
            AppLog.e(TAG, "PORT65010 migrate: не удалось запустить скрипт ни через 5555, ни через 65010 (ADB не работает)")
            // Падаем в Runtime.sh как фоллбэк последней надежды
            fallbackNoRoot("ADB unreachable on 5555/65010 — shell uid=$(runShellFallback("id", 1000))")
            return
        }

        // Шаг 3: ждём, пока 65010 поднимется (до 10 секунд, 20 попыток по 500мс).
        AppLog.i(TAG, "PORT65010 ожидание слушателя 127.0.0.1:65010 (до 10с)...")
        val deadline = SystemClock.uptimeMillis() + 10000
        var ok = false
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                val probe = tryProbePort(ADB_PORT_ROOT, connectTimeoutMs = 500, totalTimeoutMs = 2000)
                if (probe != null) {
                    val (uid, _) = probe
                    AppLog.i(TAG, "PORT65010: слушатель появился id=$uid")
                    if (isRootUid(uid)) {
                        workingPort.set(ADB_PORT_ROOT); state.set(ChannelState.ROOT_65010); ok = true; break
                    }
                }
            } catch (_: Throwable) {}
            Thread.sleep(500)
        }

        if (ok) {
            AppLog.i(TAG, "PORT65010 ИТОГ = UP on port=65010 (root)")
            logInitDiagnostics()
            return
        }

        // Шаг 4: 65010 не поднялся, но 5555 работал — возможно через него и есть root,
        // работаем через 5555.
        val probe5555 = tryProbePort(ADB_PORT_DEFAULT, 1000, 3000)
        if (probe5555 != null) {
            val (uid, _) = probe5555
            if (isRootUid(uid)) {
                workingPort.set(ADB_PORT_DEFAULT); state.set(ChannelState.ROOT_5555)
                AppLog.w(TAG, "PORT65010 ИТОГ = UP на порту 5555 (не 65010!) но shell uid=$uid — работаем через 5555")
                logInitDiagnostics()
                return
            }
            AppLog.e(TAG, "PORT65010 FAILED: порт 5555 reachable но shell uid=$uid (не root)")
            portDiagnosticDump("port 5555 reachable but uid=$uid (not root)")
            fallbackNoRoot("ADB reachable but not root (uid=$uid)")
            return
        }

        AppLog.e(TAG, "PORT65010 FAILED: ни 65010 ни 5555 не дают root. Смотри PORT65010 строки выше.")
        portDiagnosticDump("all ports failed to give root")
        fallbackNoRoot("adbd unreachable after ctl.restart")
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
        val shortCmd = cmd.replace("\s+".toRegex(), " ").take(160)
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
        require(payload.size <= 4096) { "ADB shell command too long: ${payload.size} > 4096" }
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
