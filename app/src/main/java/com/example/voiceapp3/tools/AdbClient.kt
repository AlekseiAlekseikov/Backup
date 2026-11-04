package com.example.voiceapp3.tools

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
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
    /** iter26 B.4: окна сырого чтения — 3 с (в 1.0.47 было 8 с, диагностика держала поток 35 с). */
    private const val RAW_READ_MS = 3000
    /** iter26 B.4: ADBDIAG — один раз за загрузку (флаг в prefs, сброс по boot_id). */
    private const val PREF_DIAG_BOOT = "adbdiag_boot_id"
    /** iter26 B.2 / iter27 D1: adbd создаёт ФАЙЛОВЫЙ сокет; abstract "@adbd" тоже пробуем. */
    private const val ADBD_SOCKET = "adbd"
    private const val ADBD_SOCKET_PATH = "/dev/socket/adbd"
    private const val PROP_DIAG = "persist.sys.hvacdigital.diag"
    /** iter30b F2: свойства, которые читаются всегда (ADB_PROP) и больше не дублируются в ADBDIAG. */
    private val PROPS_ADB = listOf("service.adb.tcp.port", "persist.adb.tcp.port", "init.svc.adbd", "ro.adb.secure", "ro.secure")
    // ── iter28 P0-A: параметры рукопожатия как у настоящего adb (platform-tools, SDK 28-30) ──
    private const val A_VERSION = 0x01000000
    private const val A_MAXDATA = 262144
    /**
     * iter30b F1: параметры пакета, с которыми канал РЕАЛЬНО работал в 1.0.43 —
     * arg1=0x1000 (4096), баннер `host::central-geely`, len=20 (hex считается из них; в логе
     * 1.0.43 hex-дампа не было). Используется как второй вариант пробы.
     */
    private const val A_MAXDATA_LEGACY = 4096
    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_RSAPUBLICKEY = 3
    private const val HOST_BANNER_FEATURES =
        "host::features=cmd,shell_v2,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,abb_exec,remount_shell," +
        "track_app,track_jdwp,sendrecv_v2,sendrecv_v2_brotli,sendrecv_v2_lz4,sendrecv_v2_zstd," +
        "sendrecv_v2_dry_run_send,devicetracker_proto_format,delayed_ack,avarice,apap,ipv6,pushes"
    private const val HOST_BANNER_LEGACY = "host::central-geely"

    // ── iter30 P0-F: повторы и бюджет проб; P0-C: окна снимков строки ядра ──
    private const val DEST_ATTEMPTS = 3           // 3 попытки на адрес (мандат §6)
    private const val DEST_RETRY_PAUSE_MS = 1000L // пауза между попытками — 1 с
    private const val DEST_BUDGET_MS = 32000L     // общий бюджет ADB_DEST, чтобы старт не «висел»
    private const val ROW_WINDOW_1_MS = 500       // первый снимок ADB_ROW — на 500 мс
    private const val ROW_WINDOW_2_MS = 1500      // второй снимок ADB_ROW — на 2000 мс суммарно
    private const val RETRY_AFTER_MS = 60_000L    // отложенный ретрай (мандат §6)
    // iter30 P0-E: свойства, которые приложение пишет само (round-trip без смены смысла)
    private const val PROP_BATT = "persist.sys.hvacdigital.batt"
    private const val PROP_TEMP = "persist.sys.hvacdigital.temp"
    private const val PROP_EXT_TEMP = "persist.sys.hvacdigital.temp_ext"
    private const val PROP_VIA = "persist.sys.hvacdigital.via"

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
    /**
     * iter29 P0-A: РАБОЧИЙ АДРЕС канала. Больше не константа `127.0.0.1`:
     * адрес выбирается пробой `ADB_DEST` (Wi-Fi IPv4 → 127.0.0.1 → ::1) и используется
     * всеми последующими командами (`execAdbSocket`). ПК подключается к 172.20.10.2:5555,
     * наше приложение до 1.0.50 всегда ходило на loopback — это и есть непроверенный признак.
     */
    @Volatile private var workingHost: String = "127.0.0.1"
    /**
     * iter30c F4: вариант пакета, которым адрес ОТВЕТИЛ (0 — 262144+features, 1 — 4096+legacy).
     * Раньше он жил только внутри пробы: реальный канал (`fullProbe` → `execAdbSocket` → `handshake`)
     * всегда шёл вариантом 0, даже если ответил вариант 1. Теперь вариант запоминается и используется.
     */
    @Volatile private var workingVariant: Int = 0
    @Volatile private var channelLoggedHost = ""
    // iter30 P0-C/P0-F: результаты для итогового ADB_VERDICT
    @Volatile private var lastRxQueue: Int = -1
    @Volatile private var listenerOwner: String = "unknown"
    @Volatile private var propWriteOk = false

    enum class ChannelState {
        IDLE,
        BOOTING,
        ROOT_5555,     // рутовый канал на 5555 (штатный путь: adbd уже слушает 5555 и даёт uid=0)
        ROOT_UNIX,     // рутовый канал через локальный unix-сокет "adbd" (iter26 B.2)
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

        // iter29 P0-A0: ПЕРВОЙ строкой после старта AdbWorker — кто мы для ядра/SELinux.
        // Это нужно, чтобы гипотеза H1 (фильтр по контексту пира u:r:system_app:s0) была
        // подтверждена или снята ПО ЛОГУ, а не рассуждением.
        logSelfContext()

        // iter30b F2: adb-свойства читаются ВСЕГДА, вне гейта «один раз за загрузку»:
        // версия «чужой листенер на 5555» держится именно на init.svc.adbd=running,
        // а `ADBDIAG skipped` раньше оставлял лог без этих строк вообще.
        runCatching { logAdbProps() }.onFailure { AppLog.w(TAG, "ADB_PROP failed: ${it.message}") }

        // iter29 P0-B: ключ нужен только ради одной строки ADB_PUBKEY в отчёте.
        // Ни подписи токенов на старте, ни записей в /data/misc/adb/adb_keys, ни AUTH-попыток:
        // ro.secure=0 ⇒ adbd авторизацию не требует (вывод ПК в 1.0.50: /data/misc/adb пуст,
        // adb_keys отсутствует, а uid=0(root) с ПК выдаётся).
        runCatching { AdbAuth.init(appCtx) }.onFailure { AppLog.w(TAG, "ADB_PUBKEY init failed: ${it.message}") }

        // iter29 P0-A: проба по ВСЕМ локальным адресам, не только 127.0.0.1 (гипотеза H2).
        val targets = localTargets()
        AppLog.i(TAG, "ADB_DEST targets=${targets.joinToString(",")} port=$ADB_PORT_DEFAULT " +
            "(connect ${PROBE_CONNECT_MS}ms, окон чтения ${ROW_WINDOW_1_MS}+${ROW_WINDOW_2_MS}ms, " +
            "попыток на адрес $DEST_ATTEMPTS, пауза ${DEST_RETRY_PAUSE_MS}ms, " +
            "CNXN arg0=0x01000000, варианты: v0 arg1=$A_MAXDATA+features / v1 arg1=$A_MAXDATA_LEGACY+legacy)")
        val winner = discoverDestination(targets)

        var p: Probe? = null
        if (winner != null) {
            workingHost = winner.addr
            AppLog.i(TAG, "ADB_CHANNEL host=$workingHost port=$ADB_PORT_DEFAULT")
            val pr = fullProbe()
            p = pr
            AppLog.i(TAG, "ADB_PROBE port=$ADB_PORT_DEFAULT host=$workingHost kind=${pr.kind} dt=${pr.dt}ms uid=${pr.uid.ifEmpty { "-" }}")
            if (pr.kind == KIND_ROOT) {
                channelUp(pr)
                adbFixVerify("after_dest")
                // Канал живой ⇒ листенер на 5555 — это adbd (мы получили от него root-шелл).
                listenerOwner = "adbd"
                // iter30 P0-E: право записи наших props проверяем и в успешной ветке —
                // от него зависит, включаем ли плагин напрямую (`via=app`).
                runCatching { execPropDirect() }.onFailure { AppLog.w(TAG, "EXEC_PROP failed: ${it.message}") }
                replayDeferred()
                logVerdict(true)
                return
            }
            // CNXN пришёл, но канал не root — редкий случай (UP_NONROOT). Дальше только диагноз.
            AppLog.w(TAG, "ADB_DEST note=cnxn_ok_but_not_root host=$workingHost kind=${pr.kind} uid=${pr.uid.ifEmpty { "-" }}")
        } else {
            // P0-C: ни один адрес не ответил — фиксируем это и переходим к диагнозу.
            // Пометка означает: замер сделан БЕЗ активной сессии ПК на 5555 (владелец закрывает её до запуска).
            AppLog.e(TAG, "ADB_DEST result=none working=none")
            AppLog.i(TAG, "ADB_DEST note=probe-without-pc-session (сессия ПК на 5555 закрыта до запуска)")

            // iter30 P0-B: КТО держит 5555 — inode, владелец, ps, ss (мандат §2).
            runCatching { logListenerOwner() }.onFailure { AppLog.w(TAG, "ADB_LISTEN failed: ${it.message}") }

            // iter30b F3: если порт держит НЕ adbd — одна попытка unix-сокета "/dev/socket/adbd".
            // Ровно одна: без цикла, без таймера, без ретраев; при успехе — канал ROOT_UNIX и выход.
            if (listenerOwner == "foreign") {
                val tU = SystemClock.uptimeMillis()
                val ok = runCatching { probeUnixAdbd() }.getOrElse {
                    AppLog.w(TAG, "ADB_UNIX probe crashed: ${it.javaClass.simpleName}: ${it.message}")
                    false
                }
                AppLog.i(TAG, "ADB_UNIX verdict=${if (ok) "up" else "down"} state=${state.get()} uid=${if (ok) "0" else "-"} dt=${SystemClock.uptimeMillis() - tU}ms ns=${unixNs.ifEmpty { "-" }}")
                if (ok) {
                    listenerOwner = "unix"
                    // ВАЖНО: channelUp() здесь звать НЕЛЬЗЯ — он ставит state=ROOT_5555 и увел бы все
                    // команды обратно на мёртвый TCP (runCommand маршрутизирует по state). Канал unix
                    // уже выставлен в tryUnixChannel (state=ROOT_UNIX, unixNs), поэтому только фиксируем.
                    channelUpUnix(SystemClock.uptimeMillis() - tU)
                    adbFixVerify("after_unix")
                    runCatching { execPropDirect() }.onFailure { AppLog.w(TAG, "EXEC_PROP failed: ${it.message}") }
                    replayDeferred()
                    logVerdict(true)
                    // Успех ⇒ отложенный ретрай не нужен (мандат F3).
                    return
                }
            } else {
                // iter30c п.6: причина называется явно, чтобы лог читался без догадок.
                val why = if (listenerOwner == "adbd") "owner_adbd" else "owner_unknown"
                AppLog.i(TAG, "ADB_UNIX skipped reason=$why listener_owner=$listenerOwner (мандат F3: unix-проба только при foreign)")
            }
        }

        // iter28 P0-C: unix-путь из bootstrap убран (SELinux: canRead=false + UnsupportedOperationException).
        // Оставляем одну строку-диагноз, канал — только TCP 5555.
        logUnixSocketFacts()

        // iter29 P0-B: ключи не нужны вовсе — одна строка-факт вместо self-register.
        AppLog.i(TAG, "ADB_KEYS: n/a (ro.secure=0 — adbd не требует ключей)")

        // iter28 P0-D: ADB_TOGGLE из bootstrap убран (1.0.49: NO_EFFECT + риск для USB).

        // P0.1/P0-D: диагноз — один раз за загрузку и ТОЛЬКО если ни один адрес не ответил.
        val failed = p ?: Probe(KIND_SILENT, "", "", 0, "all targets silent: ${targets.joinToString(",")}")
        if (diagAllowedThisBoot()) {
            runCatching { adbDiag(failed) }.onFailure { AppLog.w(TAG, "ADBDIAG failed: ${it.message}") }
        } else {
            AppLog.i(TAG, "ADBDIAG skipped: уже выполнялся в этой загрузке (повторно не растягиваем старт)")
        }

        // Записей в sysfs/tcpip в сборке нет и не будет.
        AppLog.i(TAG, "ADBFIX disabled (iter29: gpio/tcpip/toggle отключены — лог 1.0.46: GPIO 151 гасит USB)")

        // iter30 P0-D: лог самого adbd (вдруг он пишет причину отказа).
        runCatching { logAdbdLogcat() }.onFailure { AppLog.w(TAG, "ADB_LOGCAT failed: ${it.message}") }

        // iter30 P0-E: пишем ли мы свои props без root (и включаем ветку via=app, если да).
        runCatching { execPropDirect() }.onFailure { AppLog.w(TAG, "EXEC_PROP failed: ${it.message}") }

        // iter30 P0-F: итоговый вердикт + один отложенный ретрай через 60 с.
        logVerdict(false)
        scheduleDeferredRetry()

        AppLog.e(TAG, "ADB_SELFTEST: failed targets=${targets.joinToString(",")} kind=${failed.kind} dt=${failed.dt}ms err=${failed.err ?: "-"}")
        AppLog.e(TAG, "ROOT_UNAVAILABLE reason=no_adb_root_channel probe=$ADB_PORT_DEFAULT detail=${failed.err ?: failed.kind}")
        fallbackNoRoot("probe=${failed.kind} ${failed.err ?: ""}")
    }

    // ─────────── iter29 P0-A0/P0-A: свой контекст + проба по всем адресам ───────────

    /** `ADB_SELF`: uid/pid и SELinux-контекст процесса — первая строка AdbWorker. */
    private fun logSelfContext() {
        val uid = android.os.Process.myUid()
        val pid = android.os.Process.myPid()
        var ctx = runCatching { java.io.File("/proc/self/attr/current").readText().trim() }.getOrDefault("")
        if (ctx.isEmpty()) {
            ctx = runCatching { runShellFallback("cat /proc/self/attr/current 2>/dev/null", 1500).trim() }.getOrDefault("")
        }
        // iter30 P0-B2: порядок полей — как в мандате §2а, чтобы строку было легко грепать.
        AppLog.i(TAG, "ADB_SELF scontext=${ctx.ifEmpty { "(unreadable)" }} uid=$uid pid=$pid")
    }

    /**
     * Все локальные адреса, по которым может слушать adbd.
     * Порядок: сначала НЕ-loopback IPv4 (Wi-Fi — как у ПК), затем 127.0.0.1, затем ::1 и IPv6.
     */
    private fun localTargets(): List<String> {
        val v4 = ArrayList<String>()
        val v6 = ArrayList<String>()
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs != null && ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                if (!runCatching { ni.isUp }.getOrDefault(false)) continue
                if (runCatching { ni.isLoopback }.getOrDefault(false)) continue
                for (ia in ni.inetAddresses) {
                    if (ia.isLoopbackAddress || ia.isLinkLocalAddress || ia.isAnyLocalAddress) continue
                    val host = ia.hostAddress ?: continue
                    if (ia is Inet4Address) { if (!v4.contains(host)) v4.add(host) }
                    else if (!v6.contains(host)) v6.add(host)
                }
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "ADB_DEST targets: NetworkInterface failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        // iter30 P0-A: порядок ровно как в мандате §1 —
        // 1) не-loopback IPv4 (Wi-Fi, «другой адрес источника», чем у loopback);
        // 2) 127.0.0.1; 3) ::1; 4) остальные IPv4/IPv6.
        val out = ArrayList<String>()
        out.addAll(v4)
        out.add("127.0.0.1")
        out.add("::1")
        out.addAll(v6)
        return out.distinct()
    }

    /** Результат пробы одного адреса (для `ADB_DEST`). */
    private data class Dest(
        val addr: String,
        val connectMs: Long,
        val connectErr: String?,
        val cmd: String,
        val rx: Int,
        val dt: Long,
        val attempt: Int = 0,
        val variant: Int = 0
    )

    /**
     * Одна попытка на адрес: connect → CNXN → снимки строки ядра (0/500/2000 мс) → разбор ответа.
     * Строка лога: `ADB_DEST attempt=<n> addr=… connect=… rx=… cmd=… dt=… variant=<0|1>`
     * (iter30 P0-A: формат из мандата §1; iter30b F1 добавляет `variant=`).
     *
     * `variant = 0` — `arg1=A_MAXDATA (262144)` + баннер `host::features=…` (как в 1.0.50/1.0.51);
     * `variant = 1` — `arg1=A_MAXDATA_LEGACY (4096)` + `HOST_BANNER_LEGACY` — те самые байты,
     * с которыми канал работал в 1.0.43. Протокол/заголовок/размеры НЕ меняются: это тот же
     * `writeMsg(CNXN, …)`, различаются только arg1 и текст баннера.
     *
     * iter30c F4: эта же формула (`version = A_VERSION`, `maxData = …`, `banner = …`) теперь стоит и в
     * `handshake`, поэтому победивший вариант доходит до реального канала без «пересборки» пакета.
     */
    private fun probeDestination(addr: String, attempt: Int, variant: Int): Dest {
        val t0 = SystemClock.uptimeMillis()
        val s = Socket()
        val tConn = SystemClock.uptimeMillis()
        try {
            s.connect(InetSocketAddress(addr, ADB_PORT_DEFAULT), PROBE_CONNECT_MS)
        } catch (e: Throwable) {
            runCatching { s.close() }
            val err = when (e) {
                is java.net.SocketTimeoutException -> "ETIMEDOUT"
                is java.net.ConnectException -> "ECONNREFUSED"
                is java.net.NoRouteToHostException -> "EHOSTUNREACH"
                else -> e.javaClass.simpleName
            }
            val dt = SystemClock.uptimeMillis() - t0
            AppLog.e(TAG, "ADB_DEST attempt=$attempt variant=$variant addr=$addr connect=$err rx=0 cmd=n/a dt=${dt}ms")
            return Dest(addr, -1, err, "n/a", 0, dt, attempt, variant)
        }
        val connectMs = SystemClock.uptimeMillis() - tConn
        try {
            val os = s.getOutputStream()
            val dis = DataInputStream(s.getInputStream())
            val lport = s.localPort
            val banner = if (variant == 0) HOST_BANNER_FEATURES else HOST_BANNER_LEGACY
            val maxData = if (variant == 0) A_MAXDATA else A_MAXDATA_LEGACY
            val payload = "$banner\u0000".toByteArray(StandardCharsets.UTF_8)
            s.soTimeout = ROW_WINDOW_1_MS
            writeMsg(os, CNXN, A_VERSION, maxData, payload)
            AppLog.d(TAG, "ADB_TX(probe $addr) variant=$variant cmd=CNXN arg0=0x${"%08x".format(A_VERSION)} arg1=$maxData len=${payload.size} hex=${msgHeaderHex(CNXN, A_VERSION, maxData, payload)}")

            val hdr = ByteArray(24)
            var got = 0
            var abort: String? = null

            // P0-C: три снимка строки ядра — до чтения, через 500 мс и через 2000 мс.
            logRow(lport, 0)
            got = readSome(dis, hdr, got)
            if (got < 0) { abort = "EOF"; got = 0 }
            logRow(lport, 500)
            if (abort == null && got < 24) {
                s.soTimeout = ROW_WINDOW_2_MS
                val g2 = readSome(dis, hdr, got)
                if (g2 < 0) { abort = "EOF"; got = 0 } else got = g2
            }
            logRow(lport, 2000)

            val dt = SystemClock.uptimeMillis() - t0
            if (got < 24) {
                val cmd = if (got == 0) "silent" else "short:$got"
                AppLog.e(TAG, "ADB_DEST attempt=$attempt variant=$variant addr=$addr connect=${connectMs}ms rx=$got cmd=$cmd dt=${dt}ms${if (abort != null) " err=$abort" else ""}")
                return Dest(addr, connectMs, abort, cmd, got, dt, attempt, variant)
            }
            val c = ByteBuffer.wrap(hdr, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val name = cmdName(c)
            AppLog.i(TAG, "ADB_DEST attempt=$attempt variant=$variant addr=$addr connect=${connectMs}ms rx=24 cmd=$name hex=${hexOf(hdr, 24)} dt=${dt}ms${if (name == "AUTH") " auth=required" else ""}")
            return Dest(addr, connectMs, null, name, 24, dt, attempt, variant)
        } catch (e: Throwable) {
            val dt = SystemClock.uptimeMillis() - t0
            AppLog.e(TAG, "ADB_DEST attempt=$attempt variant=$variant addr=$addr connect=${connectMs}ms rx=0 cmd=error:${e.javaClass.simpleName} dt=${dt}ms")
            return Dest(addr, connectMs, e.javaClass.simpleName, "error", 0, dt, attempt, variant)
        } finally {
            runCatching { s.close() }
        }
    }

    /** Чтение «сколько придёт в окне»: таймаут окна — это не ошибка, EOF возвращаем как -1. */
    private fun readSome(dis: DataInputStream, hdr: ByteArray, have: Int): Int {
        var got = have
        while (got < hdr.size) {
            val r = try {
                dis.read(hdr, got, hdr.size - got)
            } catch (e: java.net.SocketTimeoutException) {
                return if (got == 0) 0 else got
            } catch (e: Throwable) {
                return if (got == 0) -1 else got
            }
            if (r < 0) return -1
            got += r
        }
        return got
    }

    // ─────────── iter30 P0-C: снимки строки ядра для НАШЕГО соединения ───────────

    /** Строки таблиц ядра: сначала прямым чтением файла, иначе через `sh cat`. */
    private fun procNetLines(table: String): List<String> {
        val viaFile = runCatching { java.io.File(table).readLines() }.getOrNull()
        if (viaFile != null && viaFile.isNotEmpty()) return viaFile
        val out = runCatching { runShellFallback("cat $table 2>/dev/null", 2000) }.getOrDefault("")
        return if (out.isBlank()) emptyList() else out.lines()
    }

    /** Поля строки /proc/net/tcp{,6}: 0 sl, 1 local, 2 rem, 3 st, 4 tx:rx, 5 tr, 6 retrnsmt, 7 uid, 8 timeout, 9 inode. */
    private fun rowFields(line: String): List<String>? {
        val f = line.trim().split(Regex("\\s+"))
        if (f.size < 10) return null
        if (!f[0].endsWith(":")) return null
        return f
    }

    private fun portOfAddr(field: String): String = field.substringAfterLast(':')

    private fun qPair(field: String): Pair<Int, Int> {
        val a = field.split(":")
        return Pair(a.getOrNull(0)?.toIntOrNull(16) ?: -1, a.getOrNull(1)?.toIntOrNull(16) ?: -1)
    }

    /**
     * `ADB_ROW t=<0|500|2000>ms lport=<p> st=<hex|имя> rx_queue=<n> tx_queue=<n> found=<true|false>`
     * rx_queue=0 при живом соединении ⇒ байты уже прочитаны процессом на 5555 и ответа нет.
     * rx_queue>0 ⇒ соединение не читают вообще.
     */
    private fun logRow(lport: Int, tMs: Int) {
        val hexPort = "%04X".format(lport)
        var found = false
        var st = "-"
        var rx = -1
        var tx = -1
        var table = "-"
        for (tb in listOf("/proc/net/tcp6", "/proc/net/tcp")) {
            for (line in procNetLines(tb)) {
                val f = rowFields(line) ?: continue
                if (!portOfAddr(f[2]).equals(hexPort, ignoreCase = true)) continue
                val q = qPair(f[4])
                tx = q.first
                rx = q.second
                st = f[3]
                table = tb
                found = true
                break
            }
            if (found) break
        }
        if (found) lastRxQueue = rx
        val stName = when (st.uppercase()) {
            "01" -> "01/ESTABLISHED"; "0A" -> "0A/LISTEN"; "06" -> "06/TIME_WAIT"
            "08" -> "08/CLOSE_WAIT"; "02" -> "02/SYN_SENT"; "09" -> "09/LAST_ACK"
            else -> st
        }
        AppLog.i(TAG, "ADB_ROW t=${tMs}ms lport=$lport st=$stName rx_queue=$rx tx_queue=$tx found=$found table=$table")
    }

    /**
     * Идём по списку адресов; на каждый — до [attempts] попыток с паузой 1 с (iter30 P0-F).
     * Возвращаем первый адрес, ответивший CNXN (или, в крайнем случае, AUTH).
     */
    private fun discoverDestination(targets: List<String>, attempts: Int = DEST_ATTEMPTS): Dest? {
        val tStart = SystemClock.uptimeMillis()
        var authOnly: Dest? = null
        for (addr in targets) {
            var last: Dest? = null
            for (a in 1..attempts) {
                if (SystemClock.uptimeMillis() - tStart > DEST_BUDGET_MS) {
                    AppLog.w(TAG, "ADB_DEST budget=${DEST_BUDGET_MS}ms exhausted (address=$addr attempt=$a) — прекращаем пробы")
                    if (last != null && last.cmd == "AUTH") authOnly = authOnly ?: last
                    return finishDiscovery(authOnly)
                }
                // iter30b F1: варианты чередуются — попытка 1 → 0, попытка 2 → 1, попытка 3 → 0.
                val variant = if (a % 2 == 1) 0 else 1
                val d = try {
                    probeDestination(addr, a, variant)
                } catch (e: Throwable) {
                    AppLog.e(TAG, "ADB_DEST attempt=$a addr=$addr probe crashed: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
                if (d == null) continue
                last = d
                if (d.cmd == "CNXN") {
                    workingVariant = d.variant
                    AppLog.i(TAG, "ADB_DEST result=${d.addr} working=${d.addr} connect=${d.connectMs}ms attempt=$a variant=${d.variant}")
                    AppLog.i(TAG, "ADB_CHANNEL host=${d.addr} port=$ADB_PORT_DEFAULT variant=${d.variant} (вариант берётся из ADB_DEST — им же пойдёт и рукопожатие)")
                    return d
                }
                if (d.cmd == "AUTH") { authOnly = authOnly ?: d; break }
                if (a < attempts) {
                    AppLog.d(TAG, "ADB_DEST retry addr=$addr next=${a + 1}/$attempts in ${DEST_RETRY_PAUSE_MS}ms (last cmd=${d.cmd} variant=${d.variant})")
                    runCatching { Thread.sleep(DEST_RETRY_PAUSE_MS) }
                }
            }
            if (last != null) {
                AppLog.i(TAG, "ADB_DEST addr=${last.addr} attempts=$attempts variants=0/1 last=${last.cmd} last_variant=${last.variant} connect=${last.connectMs}ms")
            }
        }
        return finishDiscovery(authOnly)
    }

    private fun finishDiscovery(auth: Dest?): Dest? {
        if (auth != null) {
            workingVariant = auth.variant
            AppLog.w(TAG, "ADB_DEST result=${auth.addr} working=${auth.addr} auth=required (AUTH вместо CNXN) variant=${auth.variant}")
            return auth
        }
        return null
    }

    // ─────────── iter30b F2: adb-свойства — вне гейта «один раз за загрузку» ───────────

    /**
     * Пять свойств, на которых держится вывод про листенера и авторизацию.
     * Только чтение (`getprop`), никаких записей в adb-свойства.
     * Строка: `ADB_PROP key=<имя> val=<значение|(empty)> rc=<n>`.
     */
    private fun logAdbProps() {
        for (prop in PROPS_ADB) {
            val (rc, out) = try { runShellFallbackWithRc("getprop $prop", 2500) } catch (t: Throwable) { Pair(1, "EXC:${t.message}") }
            AppLog.i(TAG, "ADB_PROP key=$prop val=${out.trim().ifEmpty { "(empty)" }} rc=$rc")
        }
    }

    // ─────────── iter30 P0-B: КТО слушает 5555 ───────────

    /**
     * `ADB_LISTEN` (inode/uid/st листенера :15B3) → `ADB_OWNER` (поиск `socket:[inode]` в /proc/<pid>/fd)
     * → `ADB_PS` (ps -A по adb/proxy/bridge/tunnel) → `ADB_SS` (ss/netstat).
     * Смысл: если в `ADB_SS` нет `pid/adbd`, а `init.svc.adbd=running` — порт держит кто-то другой.
     */
    private fun logListenerOwner() {
        val hex = "%04X".format(ADB_PORT_DEFAULT)
        var listenerInode = ""
        var seen = 0
        for (tb in listOf("/proc/net/tcp6", "/proc/net/tcp")) {
            for (line in procNetLines(tb)) {
                val f = rowFields(line) ?: continue
                if (!portOfAddr(f[1]).equals(hex, ignoreCase = true)) continue
                seen++
                AppLog.i(TAG, "ADB_LISTEN inode=${f[9]} uid=${f[7]} st=${f[3]} table=$tb local=${f[1]}")
                if (f[3].equals("0A", true) && listenerInode.isEmpty()) listenerInode = f[9]
            }
        }
        if (seen == 0) AppLog.w(TAG, "ADB_LISTEN: строк :15B3 в /proc/net/tcp{,6} нет вообще")

        if (listenerInode.isEmpty() || listenerInode == "0") {
            AppLog.i(TAG, "ADB_OWNER err=no_inode (листенер :15B3 не найден)")
            listenerOwner = "unknown"
        } else {
            val want = "socket:[$listenerInode]"
            var scanned = 0
            var denied = 0
            var found = false
            val pids = java.io.File("/proc").list()?.filter { p -> p.all { it.isDigit() } } ?: emptyList()
            for (p in pids) {
                val fdPath = "/proc/$p/fd"
                val fds = try { java.io.File(fdPath).list() } catch (e: Throwable) { null }
                if (fds == null) { denied++; continue }
                scanned++
                for (fd in fds) {
                    val link = try { android.system.Os.readlink("$fdPath/$fd") } catch (e: Throwable) { null } ?: continue
                    if (link != want) continue
                    val comm = runCatching { java.io.File("/proc/$p/comm").readText().trim() }.getOrDefault("?")
                    AppLog.i(TAG, "ADB_OWNER pid=$p cmd=$comm fd=$fd inode=$listenerInode")
                    listenerOwner = if (comm.contains("adbd")) "adbd" else "foreign"
                    found = true
                    break
                }
                if (found) break
            }
            if (!found) {
                AppLog.i(TAG, "ADB_OWNER err=${if (denied > 0) "denied" else "not_found"} scanned=$scanned denied=$denied inode=$listenerInode")
                listenerOwner = "unknown"
            }
        }

        val (prc, pout) = try {
            runShellFallbackWithRc("ps -A 2>/dev/null | grep -iE 'adb|proxy|bridge|tunnel' | head -10", 3000)
        } catch (t: Throwable) { Pair(1, "EXC:${t.message}") }
        AppLog.i(TAG, "ADB_PS rc=$prc out=${pout.replace("\n", " | ").take(400).ifEmpty { "(empty)" }}")

        for (cmd in listOf(
            "ss -ltnp 2>/dev/null | grep -E '5555|15B3' | head -5",
            "netstat -ltnp 2>/dev/null | grep -E '5555|15B3' | head -5",
        )) {
            val src = cmd.substringBefore(" ")
            val (rc, out) = try { runShellFallbackWithRc(cmd, 3000) } catch (t: Throwable) { Pair(1, "EXC:${t.message}") }
            val hasAdbd = out.contains("adbd")
            AppLog.i(TAG, "ADB_SS src=$src rc=$rc has_adbd=$hasAdbd out=${out.replace("\n", " | ").take(300).ifEmpty { "(empty)" }}")
            if (hasAdbd) listenerOwner = "adbd"
        }
        AppLog.i(TAG, "ADB_LISTEN verdict owner=$listenerOwner inode=${listenerInode.ifEmpty { "-" }}")
    }

    // ─────────── iter30 P0-D: лог самого adbd ───────────

    /** Читаем logcat: ищем `new connection on fd`, `cannot bind`, `Address already in use`, `send_auth_request`. */
    private fun logAdbdLogcat() {
        for ((src, cmd) in listOf(
            "tag_adbd" to "logcat -d -b all -s adbd '*:S' 2>&1 | tail -20",
            "grep" to "logcat -d -b all 2>&1 | grep -iE 'adbd|adb_auth|transport' | tail -20",
        )) {
            val (rc, out) = try { runShellFallbackWithRc(cmd, 6000) } catch (t: Throwable) { Pair(1, "EXC:${t.message}") }
            val txt = out.trim()
            if (txt.contains("Permission denied") && !txt.contains("adbd ")) {
                AppLog.i(TAG, "ADB_LOGCAT src=$src err=denied rc=$rc")
                continue
            }
            val lines = txt.lines().filter { it.isNotBlank() }.take(10)
            val hits = listOf("cannot bind", "Address already in use", "new connection on fd",
                "send_auth_request", "failed to write apacket").filter { txt.contains(it) }
            AppLog.i(TAG, "ADB_LOGCAT src=$src hit=${lines.isNotEmpty()} rc=$rc lines=${lines.size} keys=${hits.joinToString(",").ifEmpty { "-" }}")
            for (l in lines) AppLog.i(TAG, "ADB_LOGCAT src=$src text=${l.take(220)}")
        }
    }

    // ─────────── iter30 P0-E: пишем ли мы свои props без root ───────────

    /**
     * `EXEC_PROP`: уникальное значение в НАШ props + round-trip трёх HVAC-свойств.
     *
     * Отличие от буквы мандата §5: `batt`/`temp`/`ext_temp` — НЕ флаги, а значения, которые
     * SystemUI-плагин показывает в статус-баре (`StatusIcons.kt:724-760` пишет «98.1», «22.0», «9.7»).
     * Записать туда тестовые `42.4` — значит испортить индикацию (зона регресса). Поэтому проверка
     * идёт round-trip’ом: читаем текущее значение и записываем ЕГО ЖЕ назад — доказывает право записи
     * и ничего не меняет на экране.
     */
    private fun execPropDirect() {
        var ok = 0
        // 1) diag — наше собственное свойство: можно и уникальное значение.
        val unique = System.currentTimeMillis().toString()
        val (rc, out, err) = runShellCapture("setprop $PROP_DIAG $unique; getprop $PROP_DIAG", 4000)
        val got = out.trim()
        val match = got == unique
        AppLog.i(TAG, "EXEC_PROP key=diag sent=$unique got=${got.ifEmpty { "(empty)" }} match=$match rc=$rc out=${out.replace("\n", " ").take(120)} err=${err.replace("\n", " ").take(120)}")
        if (match) ok++
        // 2) три HVAC-свойства: round-trip текущего значения.
        for ((key, prop) in listOf(
            "batt" to PROP_BATT,
            "temp" to PROP_TEMP,
            "ext_temp" to PROP_EXT_TEMP,
        )) {
            val cur = runShellFallback("getprop $prop", 2000).trim()
            if (cur.isEmpty()) {
                AppLog.i(TAG, "EXEC_PROP key=$key sent=n/a got=(empty) match=n/a reason=prop_empty (round-trip невозможен, значение ещё не публиковалось)")
                continue
            }
            val (r2, o2, e2) = runShellCapture("setprop $prop '$cur'; getprop $prop", 3000)
            val g2 = o2.trim()
            val m2 = g2 == cur
            AppLog.i(TAG, "EXEC_PROP key=$key sent=$cur got=${g2.ifEmpty { "(empty)" }} match=$m2 rc=$r2 out=${o2.replace("\n", " ").take(100)} err=${e2.replace("\n", " ").take(100)}")
            if (m2) ok++
        }
        propWriteOk = ok >= 1
        AppLog.i(TAG, "EXEC_PROP keys=ok=$ok/3 propwrite=${if (propWriteOk) "ok" else "denied"}")

        // 3) Ветка мандата: при праве записи включить плагин через `via=app`.
        //    Не перетираем уже выставленное состояние монтирования (install/bind) — им управляет
        //    HvacDigitalPlugin, который в этой итерации трогать запрещено (§7 мандата).
        if (propWriteOk) {
            val viaNow = runShellFallback("getprop $PROP_VIA", 2000).trim()
            if (viaNow.isEmpty() || viaNow == "app") {
                val (vr, vo, ve) = runShellCapture("setprop $PROP_VIA app; getprop $PROP_VIA", 3000)
                AppLog.i(TAG, "EXEC_PROP via=app sent=app got=${vo.trim().ifEmpty { "(empty)" }} match=${vo.trim() == "app"} rc=$vr err=${ve.take(120)}")
            } else {
                AppLog.i(TAG, "EXEC_PROP via=app skipped reason=already_set via=$viaNow (состояние плагина не трогаем)")
            }
        } else {
            AppLog.i(TAG, "EXEC_PROP via=app skipped reason=propwrite_denied")
        }
    }

    /** sh-команда с РАЗДЕЛЬНЫМИ stdout и stderr (мандат §5 требует err=… в логе). */
    private fun runShellCapture(cmd: String, timeoutMs: Int): Triple<Int, String, String> = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val outBuf = StringBuilder()
        val errBuf = StringBuilder()
        val t1 = Thread { runCatching { outBuf.append(p.inputStream.bufferedReader().readText()) } }
        val t2 = Thread { runCatching { errBuf.append(p.errorStream.bufferedReader().readText()) } }
        t1.isDaemon = true; t2.isDaemon = true; t1.start(); t2.start()
        val w = Thread { runCatching { p.waitFor() } }
        w.isDaemon = true; w.start(); w.join(timeoutMs.toLong())
        if (w.isAlive) {
            runCatching { p.destroyForcibly() }
            t1.join(300); t2.join(300)
            Triple(137, outBuf.toString().trim(), errBuf.toString().trim() + "(timeout ${timeoutMs}ms)")
        } else {
            t1.join(500); t2.join(500)
            Triple(p.exitValue(), outBuf.toString().trim(), errBuf.toString().trim())
        }
    } catch (t: Throwable) {
        Triple(1, "", "sh-fatal: ${t.message}")
    }

    // ─────────── iter30 P0-F: вердикт и отложенный ретрай ───────────

    /** Одна итоговая строка: чем кончилось и кто виноват по данным лога. */
    private fun logVerdict(up: Boolean) {
        AppLog.i(TAG, "ADB_VERDICT channel=${if (up) "up" else "down"} listener_owner=$listenerOwner " +
            "rx_queue_last=$lastRxQueue propwrite=${if (propWriteOk) "ok" else "denied"}")
    }

    /** Один ретрай через 60 с: если за это время канал появился сам — поднимаем его без перезапуска. */
    private fun scheduleDeferredRetry() {
        val t = Thread {
            try {
                Thread.sleep(RETRY_AFTER_MS)
                if (state.get() == ChannelState.ROOT_5555) {
                    AppLog.i(TAG, "ADB_RETRY t=60s result=skip(channel_up host=$workingHost)")
                    return@Thread
                }
                val targets = localTargets()
                AppLog.i(TAG, "ADB_RETRY t=60s start targets=${targets.joinToString(",")}")
                val w = discoverDestination(targets, attempts = 1)
                if (w == null) {
                    AppLog.i(TAG, "ADB_RETRY t=60s result=silent targets=${targets.joinToString(",")} rx_queue_last=$lastRxQueue")
                    return@Thread
                }
                workingHost = w.addr
                val pr = fullProbe()
                if (pr.kind == KIND_ROOT) {
                    channelUp(pr)
                    adbFixVerify("after_retry")
                    replayDeferred()
                    logVerdict(true)
                    AppLog.i(TAG, "ADB_RETRY t=60s result=up host=$workingHost")
                } else {
                    AppLog.i(TAG, "ADB_RETRY t=60s result=${pr.kind} host=$workingHost uid=${pr.uid.ifEmpty { "-" }}")
                }
            } catch (t2: Throwable) {
                AppLog.w(TAG, "ADB_RETRY failed: ${t2.javaClass.simpleName}: ${t2.message}")
            }
        }
        t.name = "AdbDeferredRetry"
        t.isDaemon = true
        t.start()
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
            // iter30c F4: канал идёт ТЕМ ЖЕ вариантом пакета, которым ответил адрес (ADB_DEST).
            val out = execAdbSocket(
                "id; id -Z 2>/dev/null; getenforce 2>/dev/null",
                ADB_PORT_DEFAULT, PROBE_TOTAL_MS, PROBE_CONNECT_MS, workingVariant,
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
     * iter30b F3: фиксация канала, найденного через unix-сокет.
     * В отличие от [channelUp] НЕ переводит состояние в ROOT_5555 — иначе `runCommand`
     * увёл бы все команды на TCP 5555, который в этой ветке как раз не отвечает.
     */
    private fun channelUpUnix(dt: Long) {
        workingPort.set(ADB_PORT_DEFAULT)
        state.set(ChannelState.ROOT_UNIX)
        AppLog.i(TAG, "ADB_SELFTEST: unix ok uid=0 dt=${dt}ms ns=${unixNs.ifEmpty { "filesystem" }} (TCP 5555 не отвечает — работаем через unix)")
        AppLog.i(TAG, "ADB_CHANNEL unix ns=${unixNs.ifEmpty { "filesystem" }} path=$ADBD_SOCKET_PATH port=-")
    }

    /**
     * P0.1 — `ADBDIAG`: однозначный диагноз мёртвого канала. Всё через AppLog (в файл).
     * Шаги: контроль закрытых портов → сырое чтение от 5555 → таблицы ядра (tcp и tcp6,
     * поиск по hex :15B3) → снимок свойств → проверка записи нашего props без root.
     */
    private fun adbDiag(failed: Probe) {
        AppLog.e(TAG, "ADBDIAG start iter29: failedProbe=${failed.kind} dt=${failed.dt}ms err=${failed.err ?: "-"}")

        // 1) Таблица ядра — только tcp6 (в 1.0.43 слушатель был `:::`), hex :15B3.
        //    Всё остальное (tcp, ss, netstat, 5037, ps adbd, /proc/net/unix, 5556/65011,
        //    raw-чтения, accept-очередь) из диагноза убрано: ответы уже получены в 1.0.47–1.0.50.
        val tbl = "/proc/net/tcp6"
        val cmd = "(awk 'NR==1 || \$2 ~ /:15B3\$/ {print}' $tbl 2>/dev/null || cat $tbl 2>/dev/null) | head -5"
        val (rc, out) = try { runShellFallbackWithRc(cmd, 3000) } catch (t: Throwable) { Pair(1, "EXC:${t.message}") }
        AppLog.e(TAG, "ADBDIAG $tbl rc=$rc listen15B3=${out.lineSequence().any { it.contains(":15B3") }} out=${out.replace('\n', ' ').take(300).ifEmpty { "(empty)" }}")

        // 2) iter30b F2: снимок пяти adb-свойств больше НЕ дублируется здесь — он пишется
        //    всегда строкой `ADB_PROP …` из bootstrap (см. logAdbProps), в т.ч. когда
        //    `ADBDIAG skipped`. Экономит до 5 вызовов getprop в диагнозе.

        // 3) Ключи: читаются ли вообще (записей в adb_keys из приложения НЕТ и не будет).
        val keysReadable = runCatching { java.io.File("/data/misc/adb/adb_keys").canRead() }.getOrDefault(false)
        if (keysReadable) {
            val (krc, kout) = try { runShellFallbackWithRc("wc -l < /data/misc/adb/adb_keys 2>&1", 2500) } catch (t: Throwable) { Pair(1, "EXC:${t.message}") }
            AppLog.e(TAG, "ADBDIAG wc adb_keys rc=$krc val=${kout.trim().ifEmpty { "(empty)" }}")
        } else {
            AppLog.e(TAG, "ADBDIAG wc adb_keys: n/a (файл не читается из uid=1000; ключи не нужны при ro.secure=0)")
        }

        AppLog.e(TAG, "ADBDIAG end")
    }

    // ─────────── iter28 P0-A/P0-B: рукопожатие и авторизация ───────────

    /**
     * Рукопожатие на уже подключённом сокете: CNXN → (AUTH TOKEN → SIGNATURE)* → CNXN.
     * Возвращает null, если пришёл CNXN; иначе — причину.
     * Логи: `ADB_TX cmd=CNXN arg0=… arg1=… variant=<0|1> len=… hex=…` (что отправили) и
     * `ADB_RX attempt=<n> variant=<0|1> cmd=… hex=… dt=…` (что получили).
     */
    private fun handshake(s: Socket, dis: DataInputStream, os: OutputStream, attempt: Int, variant: Int, deadline: Long): String? {
        // iter30c F4: РОВНО та же формула, что в probeDestination (одна на пробу и на канал):
        //   variant 0 → version=A_VERSION (0x01000000), arg1=A_MAXDATA (262144), HOST_BANNER_FEATURES;
        //   variant 1 → version=A_VERSION (0x01000000), arg1=A_MAXDATA_LEGACY (4096), HOST_BANNER_LEGACY.
        // Никакого 0x01000001 здесь больше нет: вариант 1 — это буквально пакет 1.0.43.
        val version = A_VERSION
        val banner = if (variant == 0) HOST_BANNER_FEATURES else HOST_BANNER_LEGACY
        val maxData = if (variant == 0) A_MAXDATA else A_MAXDATA_LEGACY
        val payload = "$banner\u0000".toByteArray(StandardCharsets.UTF_8)
        val hdrHex = msgHeaderHex(CNXN, version, maxData, payload)
        writeMsg(os, CNXN, version, maxData, payload)
        AppLog.i(TAG, "ADB_TX cmd=CNXN arg0=0x${"%08x".format(version)} arg1=$maxData variant=$variant len=${payload.size} hex=$hdrHex")
        var authSteps = 0
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return "silent (CNXN timeout)"
            val tMsg = System.currentTimeMillis()
            s.soTimeout = left.toInt().coerceIn(1, Int.MAX_VALUE)
            val msg = try { readMsg(dis) } catch (e: Throwable) {
                AppLog.e(TAG, "ADB_RX attempt=$attempt variant=$variant cmd=silent hex=(none) dt=${System.currentTimeMillis() - tMsg}ms err=${e.javaClass.simpleName}")
                return "silent (${e.javaClass.simpleName})"
            }
            AppLog.i(TAG, "ADB_RX attempt=$attempt variant=$variant cmd=${cmdName(msg.cmd)} arg0=${msg.arg0} arg1=${msg.arg1} len=${msg.payload.size} hex=${hexOf(msg.payload, minOf(24, msg.payload.size))} dt=${System.currentTimeMillis() - tMsg}ms")
            when (msg.cmd) {
                CNXN -> {
                    val bannerRx = try { String(msg.payload, Charsets.UTF_8).trim() } catch (_: Throwable) { "" }
                    AppLog.i(TAG, "ADB_RX attempt=$attempt cmd=CNXN maxdata=${msg.arg1} device=${bannerRx.take(80)}")
                    return null
                }
                AUTH -> {
                    authSteps++
                    if (authSteps > 4) return "auth loop (более 4 шагов)"
                    when (msg.arg0) {
                        AUTH_TOKEN -> {
                            val sig = AdbAuth.signToken(msg.payload)
                            if (sig == null) {
                                AppLog.e(TAG, "ADB_AUTH token received, но ключа нет — подписать нечем")
                                return "auth no key"
                            }
                            writeMsg(os, AUTH, AUTH_SIGNATURE, 0, sig)
                            AppLog.i(TAG, "ADB_AUTH token received, signature sent, reply=<ждём> sig_len=${sig.size}")
                        }
                        AUTH_SIGNATURE, AUTH_RSAPUBLICKEY -> {
                            val line = AdbAuth.publicKeyLine()
                            AppLog.e(TAG, "ADB_AUTH: adbd просит ключ, которого нет в adb_keys (см. ADB_PUBKEY) — отправляем публичный ключ")
                            if (line == null) return "adbd asks for key, no pubkey"
                            writeMsg(os, AUTH, AUTH_RSAPUBLICKEY, 0, "$line\u0000".toByteArray(Charsets.UTF_8))
                            AppLog.i(TAG, "ADB_AUTH publickey sent len=${line.length}")
                        }
                        else -> return "auth unknown type ${msg.arg0}"
                    }
                }
                else -> AppLog.i(TAG, "ADB_RX attempt=$attempt cmd=${cmdName(msg.cmd)} (игнорируем в рукопожатии)")
            }
        }
    }

    /** hex 24-байтного заголовка того, что отправляем (для отчёта и сверки с платформой). */
    private fun msgHeaderHex(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray): String {
        val bytes = msgBytes(cmd, arg0, arg1, payload)
        return bytes.take(24).joinToString(" ") { "%02x".format(it) }
    }

    private fun msgBytes(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cmd); buf.putInt(arg0); buf.putInt(arg1); buf.putInt(payload.size)
        var sum = 0; for (b in payload) sum += b.toInt() and 0xFF
        buf.putInt(sum); buf.putInt(cmd.inv())
        return buf.array()
    }

    // ─────────── iter28 P0-C: unix-сокет — только факт в лог ───────────

    /** Одна строка: существует ли сокет, читается ли, и что ответил бы connect (мы НЕ подключаемся). */
    private fun logUnixSocketFacts() {
        try {
            val f = java.io.File(ADBD_SOCKET_PATH)
            AppLog.i(TAG, "ADBDIAG unix-socket exists=${f.exists()} canRead=${f.canRead()} path=$ADBD_SOCKET_PATH (в bootstrap не используется: SELinux + Namespace.FILESYSTEM без timeout)")
        } catch (t: Throwable) {
            AppLog.w(TAG, "ADBDIAG unix-socket facts failed: ${t.message}")
        }
    }

    /** iter28 P0-D: после успеха доделываем отложенную работу, не дожидаясь следующего тика. */
    private fun replayDeferred() {
        val ctx = appCtx
        AppLog.i(TAG, "ADB_REPLAY: отложенная работа после восстановления канала (ctx=${ctx != null})")
        if (ctx == null) return
        runCatching { NetworkTtl.bootRestore(ctx) }.onFailure { AppLog.w(TAG, "ADB_REPLAY TTL failed: ${it.message}") }
        runCatching { StatusIcons.bootRestoreHvacDigital(ctx) }.onFailure { AppLog.w(TAG, "ADB_REPLAY HVAC failed: ${it.message}") }
        runCatching { StatusIcons.syncAll(ctx) }.onFailure { AppLog.w(TAG, "ADB_REPLAY sync failed: ${it.message}") }
    }

    private fun hexOf(b: ByteArray, n: Int): String =
        (0 until n.coerceAtMost(b.size)).joinToString(" ") { "%02x".format(b[it]) }.ifEmpty { "(none)" }

    /**
     * Чтение свойств порта adbd — БЕЗ записи. Если в persist.adb.tcp.port застряло
     * чужое значение, только сообщаем: ADB_PORT_STUCK=<v>. Менять его из приложения
     * нельзя (рестарт adbd рвёт соединение), поэтому — не трогаем.
     */
    private fun logAdbPortReadback(port: Int) {
        val per = runShellFallback("getprop persist.adb.tcp.port", 2000).trim()
        val cur = runShellFallback("getprop service.adb.tcp.port", 2000).trim()
        AppLog.i(TAG, "ADB_PORT_READBACK: working=$port persist.adb.tcp.port=${per.ifEmpty { "(empty)" }} service.adb.tcp.port=${cur.ifEmpty { "(empty)" }}")
        if (per.isNotEmpty() && per != ADB_PORT_DEFAULT.toString()) {
            AppLog.w(TAG, "ADB_PORT_STUCK=$per (persist.adb.tcp.port не $ADB_PORT_DEFAULT; НЕ меняем — запись adb-свойств из приложения запрещена)")
        }
    }

    /**
     * Дешёвые команды (pm path, getprop, settings get, grep /proc/mounts) не нуждаются
     * в root и НЕ должны ходить через adb: приложение уже работает как uid=1000(system).
     * Выполняем через Runtime.exec.
     */
    fun shellLocal(command: String, timeoutMs: Int = 4000): Pair<Int, String> {
        return runShellFallbackWithRc(command, timeoutMs)
    }

    /**
     * Умное выполнение: если root-канал жив — через adb; иначе — локальный sh.
     * Только для команд, которым root НЕ нужен (expectRoot=false по смыслу).
     */
    /** Выполнение на текущем канале (TCP 5555 либо unix-сокет) — для adbFixVerify. */
    private fun runOnChannel(command: String, timeoutMs: Int): String {
        return if (state.get() == ChannelState.ROOT_UNIX) execAdbLocalSocket(command, timeoutMs)
        else execAdbSocket(command, ADB_PORT_DEFAULT, timeoutMs)
    }

    fun executeSmart(command: String, timeoutMs: Int = 4000): Pair<Int, String> {
        if (hasRoot()) {
            return execute(command, timeoutMs, expectRoot = false)
        }
        return runShellFallbackWithRc(command, timeoutMs)
    }

    // ─────────── iter26 B.2: канал через локальный unix-сокет ───────────

    /**
     * `android.net.LocalSocket` → `connect("adbd")` → CNXN → через него `OPEN shell:id`.
     *
     * РЕФЕРЕНС: `НЕТ В ЭТАЛОНЕ` (CEX работает только по TCP 5555). Механизм стандартный:
     * adbd слушает abstract unix-сокет "adbd"; доступ к нему у system-uid есть не всегда,
     * поэтому EACCES — штатный исход, а не ошибка.
     *
     * @return true, если канал ответил CNXN и `id` вернул uid=0 (state → ROOT_UNIX)
     */
    /**
     * iter27 D1 (ревью iter26): adbd создаёт **файловый** сокет `/dev/socket/adbd`, а не
     * abstract `@adbd`. Пробуем ОБА пространства по порядку и пишем, какое сработало:
     * `ADBDIAG unix-socket ns=filesystem|abstract …`. Перед подключением логируем
     * `exists/canRead` по пути — чтобы «EACCES» не путать с «сокета нет».
     */
    private fun probeUnixAdbd(): Boolean {
        val t0 = SystemClock.uptimeMillis()
        val f = java.io.File(ADBD_SOCKET_PATH)
        AppLog.i(TAG, "ADBDIAG unix-socket ns=filesystem path=$ADBD_SOCKET_PATH exists=${f.exists()} canRead=${f.canRead()}")

        val attempts = listOf(
            "filesystem" to android.net.LocalSocketAddress(ADBD_SOCKET_PATH, android.net.LocalSocketAddress.Namespace.FILESYSTEM),
            "abstract" to android.net.LocalSocketAddress(ADBD_SOCKET, android.net.LocalSocketAddress.Namespace.ABSTRACT),
        )
        for ((nsName, addr) in attempts) {
            if (tryUnixChannel(nsName, addr, t0)) return true
        }
        unixNs = ""
        return false
    }

    /** Одна попытка подключиться к конкретному unix-адресу и проверить uid. */
    private fun tryUnixChannel(nsName: String, addr: android.net.LocalSocketAddress, t0: Long): Boolean {
        val sock = android.net.LocalSocket()
        return try {
            sock.connect(addr, 2000)
            val os = sock.outputStream
            writeMsg(os, CNXN, 0x01000000, 4096, "host::central-geely\u0000".toByteArray(StandardCharsets.UTF_8))
            sock.soTimeout = 3000
            val dis = DataInputStream(sock.inputStream)
            val msg = try { readMsg(dis) } catch (e: Throwable) {
                AppLog.e(TAG, "ADBDIAG unix-socket ns=$nsName connect=ok rx=0 abort=${e.javaClass.simpleName} dt=${SystemClock.uptimeMillis() - t0}ms")
                return false
            }
            AppLog.e(TAG, "ADBDIAG unix-socket ns=$nsName connect=ok rx=${msg.payload.size + 24} cmd=${cmdName(msg.cmd)} hex=${hexOf(msg.payload, minOf(24, msg.payload.size))} dt=${SystemClock.uptimeMillis() - t0}ms")
            if (msg.cmd != CNXN) return false
            val idOut = try { execAdbLocalSocket("id", 4000, nsName) } catch (e: Throwable) {
                AppLog.w(TAG, "unix-socket ns=$nsName OPEN shell:id failed: ${e.javaClass.simpleName}: ${e.message}")
                return false
            }
            if (isRootUid(idOut)) {
                unixChannelOk = true
                unixNs = nsName
                state.set(ChannelState.ROOT_UNIX)
                AppLog.i(TAG, "ADB_SELFTEST: unix-socket ok ns=$nsName uid=0 dt=${SystemClock.uptimeMillis() - t0}ms — канал найден БЕЗ TCP (ROOT_UNIX)")
                AppLog.i(TAG, "ADBDIAG unix-socket ns=$nsName uid=0 (работаем через unix, TCP 5555 не трогаем)")
                return true
            }
            AppLog.w(TAG, "ADBDIAG unix-socket ns=$nsName connect=ok, но uid не root: ${idOut.take(120)}")
            false
        } catch (e: Throwable) {
            val kind = when (e) {
                is SecurityException -> "EACCES"
                is java.net.ConnectException -> "ECONNREFUSED"
                is java.io.IOException -> "EIO:${e.message}"
                else -> e.javaClass.simpleName
            }
            AppLog.e(TAG, "ADBDIAG unix-socket ns=$nsName connect=${kind} rx=0 dt=${SystemClock.uptimeMillis() - t0}ms")
            false
        } finally {
            runCatching { sock.close() }
        }
    }

    @Volatile private var unixChannelOk = false
    /** Какое пространство имён сработало: "filesystem" | "abstract" | "" (не найдено). */
    @Volatile private var unixNs = ""

    /** Выполнение shell-команды через unix-сокет (используется, когда state == ROOT_UNIX). */
    private fun execAdbLocalSocket(command: String, timeoutMs: Int, nsName: String = unixNs.ifEmpty { "filesystem" }): String {
        val sock = android.net.LocalSocket()
        val addr = if (nsName == "abstract")
            android.net.LocalSocketAddress(ADBD_SOCKET, android.net.LocalSocketAddress.Namespace.ABSTRACT)
        else
            android.net.LocalSocketAddress(ADBD_SOCKET_PATH, android.net.LocalSocketAddress.Namespace.FILESYSTEM)
        try {
            sock.connect(addr, 2000)
            val os = sock.outputStream
            writeMsg(os, CNXN, 0x01000000, 4096, "host::central-geely\u0000".toByteArray(StandardCharsets.UTF_8))
            sock.soTimeout = timeoutMs
            val dis = DataInputStream(sock.inputStream)
            while (true) {
                val m = readMsg(dis)
                if (m.cmd == CNXN) break
                if (m.cmd == AUTH) throw IOException("adbd unix: requires AUTH")
            }
            val payload = ("shell:$command\u0000").toByteArray(StandardCharsets.UTF_8)
            writeMsg(os, OPEN, 1, 0, payload)
            val out = ByteArrayOutputStream()
            var localId = 0
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val left = (deadline - System.currentTimeMillis()).toInt().coerceIn(1, Int.MAX_VALUE)
                sock.soTimeout = left
                val m = readMsg(dis)
                when (m.cmd) {
                    OKAY -> localId = m.arg0
                    WRTE -> { out.write(m.payload); writeMsg(os, OKAY, 1, m.arg0, ByteArray(0)) }
                    CLSE -> { writeMsg(os, CLSE, 1, localId, ByteArray(0)); return out.toString("UTF-8").trim() }
                    else -> {}
                }
            }
            throw IOException("unix shell timeout after ${timeoutMs}ms")
        } finally {
            runCatching { sock.close() }
        }
    }

    // ─────────── iter26 B.1: штатный перезапуск adbd через настройку ───────────

    /** Context нужен только для Settings.Global (WRITE_SECURE_SETTINGS). */
    @Volatile private var appCtx: android.content.Context? = null
    fun attachContext(c: android.content.Context) { if (appCtx == null) appCtx = c.applicationContext }
    @Volatile private var toggleTried = false

    /**
     * `Settings.Global.ADB_ENABLED`: system_server наблюдает это значение и вызывает
     * `UsbDeviceManager.setAdbEnabled()` → перезапуск adbd и его транспортов.
     * Это ровно тот механизм, что тумблер «Отладка по USB» в «Для разработчиков».
     * РЕФЕРЕНС: `НЕТ В ЭТАЛОНЕ` (CEX adbd не перезапускает).
     *
     * Правила мандата: одна попытка за старт, исходное значение восстанавливается
     * в `finally` при любом исходе, включая исключение.
     */
    private fun adbToggleFix(): Boolean {
        val ctx = appCtx
        if (ctx == null) { AppLog.w(TAG, "ADB_TOGGLE skipped: нет Context"); return false }
        if (toggleTried) { AppLog.i(TAG, "ADB_TOGGLE skipped: попытка уже была в этом запуске"); return false }
        toggleTried = true

        val cr = ctx.contentResolver
        val before = try {
            android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.ADB_ENABLED, -1)
        } catch (e: Throwable) { AppLog.w(TAG, "ADB_TOGGLE before: ${e.javaClass.simpleName}: ${e.message}"); -1 }
        AppLog.i(TAG, "ADB_TOGGLE before=$before")
        AppLog.w(TAG, "ADB_TOGGLE: если после этого шага отвалится USB — виноват этот шаг, откатывать его первым")

        var ok = false
        try {
            val w0 = try {
                android.provider.Settings.Global.putInt(cr, android.provider.Settings.Global.ADB_ENABLED, 0)
                true
            } catch (e: Throwable) { AppLog.w(TAG, "ADB_TOGGLE put(0): ${e.javaClass.simpleName}: ${e.message}"); false }
            AppLog.i(TAG, "ADB_TOGGLE write=0 accepted=$w0 (пауза 1500 мс — минимум: на этой ГУ ADB_ENABLED связан с sys.usb.state)")
            // iter27 D3: пауза с выключенным ADB_ENABLED — минимальная (было 3000 мс).
            Thread.sleep(1500)
            val p0 = fullProbe()
            AppLog.i(TAG, "ADB_TOGGLE after=0 probe=${p0.kind} dt=${p0.dt}ms")

            val w1 = try {
                android.provider.Settings.Global.putInt(cr, android.provider.Settings.Global.ADB_ENABLED, 1)
                true
            } catch (e: Throwable) { AppLog.w(TAG, "ADB_TOGGLE put(1): ${e.javaClass.simpleName}: ${e.message}"); false }
            AppLog.i(TAG, "ADB_TOGGLE write=1 accepted=$w1")
            Thread.sleep(5000)
            val p1 = fullProbe()
            AppLog.i(TAG, "ADB_TOGGLE after=1 probe=${p1.kind} dt=${p1.dt}ms uid=${p1.uid.ifEmpty { "-" }}")
            if (p1.kind == KIND_ROOT) { channelUp(p1); ok = true }
            else if (p1.kind == KIND_NONROOT) AppLog.w(TAG, "ADB_TOGGLE: порт отвечает, но шелл не root (UP_NONROOT) — успехом не считаем")
        } catch (e: Throwable) {
            AppLog.w(TAG, "ADB_TOGGLE exception: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            // Возврат исходного значения при ЛЮБОМ исходе (мандат B.1 п.4).
            if (before >= 0) {
                val restored = try {
                    android.provider.Settings.Global.putInt(cr, android.provider.Settings.Global.ADB_ENABLED, before)
                    android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.ADB_ENABLED, -1)
                } catch (e: Throwable) {
                    AppLog.w(TAG, "ADB_TOGGLE restore failed: ${e.javaClass.simpleName}: ${e.message}"); null
                }
                AppLog.i(TAG, "ADB_TOGGLE restore=$before readback=$restored")
            }
            // iter27 D3: сразу после включения обратно — снимок USB-состояния.
            val usbState = runShellFallback("getprop sys.usb.state", 2500).trim()
            val usbConfig = runShellFallback("getprop sys.usb.config", 2500).trim()
            AppLog.i(TAG, "ADB_TOGGLE usb_state=${usbState.ifEmpty { "(empty)" }} usb_config=${usbConfig.ifEmpty { "(empty)" }}")
        }
        AppLog.i(TAG, "ADB_TOGGLE result=${if (ok) "OK" else "NO_EFFECT"}")
        return ok
    }

    /** iter26 B.1 п.6: root реально вернулся — проверяем не порт, а права. */
    private fun adbFixVerify(when_: String) {
        val checks = ArrayList<String>()
        fun chk(name: String, cmd: String, expect: String) {
            try {
                val out = runOnChannel(cmd, 5000).replace('\n', ' ').trim()
                checks.add("$name=${if (out.contains(expect)) "OK" else "FAIL"}(${out.take(80)})")
            } catch (e: Throwable) {
                checks.add("$name=ERR(${e.javaClass.simpleName})")
            }
        }
        chk("id", "id", "uid=0")
        chk("screen_off_timeout", "settings get system screen_off_timeout; echo rc=\$?", "rc=0")
        chk("prop_write", "setprop $PROP_DIAG 48; getprop $PROP_DIAG", "48")
        // iter26: `$?` берём сразу после iptables, а не после head.
        chk("iptables", "iptables -t mangle -S POSTROUTING >/dev/null 2>&1; echo rc=\$?", "rc=0")
        val failed = checks.filter { !it.contains("=OK") }
        AppLog.i(TAG, "ADBFIX verify[$when_]=${if (failed.isEmpty()) "OK" else "PARTIAL"} ${checks.joinToString(" ")}")
    }

    /** Диагноз — не чаще одного раза за загрузку (iter26 B.4). */
    private fun diagAllowedThisBoot(): Boolean {
        val ctx = appCtx
        val bootId = try {
            java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
        } catch (_: Throwable) { "" }
        if (ctx == null || bootId.isEmpty()) return true
        val saved = Prefs2.getString(ctx, PREF_DIAG_BOOT, "") ?: ""
        if (saved == bootId) return false
        Prefs2.putString(ctx, PREF_DIAG_BOOT, bootId)
        AppLog.i(TAG, "ADBDIAG: первая загрузка с этим boot_id=${bootId.take(8)} — полный диагноз разрешён")
        return true
    }

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

        // iter26 B.2: если канал найден через unix-сокет — работаем через него, TCP не трогаем.
        if (st == ChannelState.ROOT_UNIX) {
            val out = try {
                execAdbLocalSocket(cmd, timeoutMs)
            } catch (e: Throwable) {
                val msg = "ERR_ADB_UNIX: ${e.javaClass.simpleName}: ${e.message}"
                AppLog.e(TAG, "VERIFY EXEC unix rc=1 cmd=${shortCmd} → FAILED ($msg)")
                return Pair(1, msg)
            }
            return classify(0, expectRoot, shortCmd, out, 0)
        }

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
    private fun execAdbSocket(command: String, port: Int, timeoutMs: Int, connectTimeoutMs: Int = PROBE_CONNECT_MS, variant: Int = -1): String {
        // iter30c F4: по умолчанию — вариант, которым ответил адрес (workingVariant), а не жёсткий 0.
        // Явное значение (-1 = «не задано») остаётся для особых случаев.
        val effVariant = if (variant < 0) workingVariant else variant
        val payload = ("shell:$command\u0000").toByteArray(StandardCharsets.UTF_8)
        // iter21 §1.3: не валим IllegalArgumentException в общий поток — возвращаем ERR_ADB_TOO_LONG.
        if (payload.size > 4096) {
            return "ERR_ADB_TOO_LONG: cmd_len=${payload.size} > 4096 (use executeScript for long scripts)"
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        Socket().use { s ->
            val host = workingHost
            if (host != channelLoggedHost) {
                channelLoggedHost = host
                AppLog.i(TAG, "ADB_CHANNEL host=$host port=$port variant=$effVariant (канал по адресу из ADB_DEST)")
            } else {
                AppLog.d(TAG, "ADB_CHANNEL host=$host port=$port variant=$effVariant")
            }
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.soTimeout = timeoutMs
            val dis = DataInputStream(s.getInputStream())
            val os = s.getOutputStream()
            // iter28 P0-A/P0-B: рукопожатие как у настоящего adb, с обработкой AUTH.
            AdbAuth.init(appCtx)
            // iter30c п.5: номер попытки больше не зависит от варианта (`variant + 1` давал attempt=2
            // на первой же попытке при варианте 1). Попытка всегда 1, вариант печатается отдельным полем.
            val hsErr = handshake(s, dis, os, attempt = 1, variant = effVariant, deadline = deadline)
            if (hsErr != null) throw IOException("ADB handshake failed: $hsErr")
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
     * iter27 D2 (ревью iter26): ЕДИНАЯ проверка «канал даёт root», чтобы UI и
     * HvacDigitalPlugin не расходились с реальностью. Раньше сравнивали только с
     * ROOT_5555, и при успешном unix-канале владелец видел «канал недоступен».
     */
    @JvmStatic
    fun hasRoot(): Boolean {
        val st = state.get()
        return st == ChannelState.ROOT_5555 || st == ChannelState.ROOT_UNIX
    }

    /** Человеческое имя активного канала для UI. */
    @JvmStatic
    fun channelLabel(): String = when (state.get()) {
        ChannelState.ROOT_5555 -> "TCP $workingHost:$ADB_PORT_DEFAULT"
        ChannelState.ROOT_UNIX -> "unix-сокет ns=${unixNs.ifEmpty { "filesystem" }}"
        ChannelState.NO_ROOT_SH -> "нет (uid=1000, без root)"
        ChannelState.PERM_FAIL -> "нет (root запрещён)"
        ChannelState.BOOTING -> "проверяется…"
        ChannelState.IDLE -> "не проверялся"
    }

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
