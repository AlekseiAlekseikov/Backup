package com.example.voiceapp3.tools

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager

/**
 * Android-адаптеры для ядра [JitOp]. Решений здесь нет — только те вызовы, что есть в референсе:
 * `WifiManager.isWifiEnabled()` / `setWifiEnabled()`, чтение `/proc/self/maps` текущего процесса,
 * `getprop`, префы проекта.
 *
 * Точки доступа ГУ (`ap0`, `startSoftAp`, `WIFI_AP_STATE_CHANGED`, `wifi_state==14`), `CarplayMonitor`,
 * `connadaptor_modo` и ConnAdaptor в JIT не переносятся (решения владельца).
 *
 * Сетевые команды — только внутри цепочки клика и её продолжения после рестарта фреймворка;
 * `ip rule` только читается; `ctl.restart zygote` — единственная команда рестарта.
 */
object JitNet {

    private const val TAG = "CENTRAL_JitNet"

    internal const val KEY_PEDIDO = JitRef.KEY_PEDIDO
    internal const val KEY_AUTO = JitRef.KEY_AUTO
    internal const val KEY_RESTORE = JitRef.KEY_RESTORE
    internal const val PROP_USEJIT = JitRef.PROP_USEJIT
    internal const val RESTORE_DELAY_MS = JitRef.RESTORE_DELAY_MS
    internal const val CHAIN_TIMEOUT_MS = JitRef.CHAIN_TIMEOUT_MS

    private val log: (String) -> Unit = { AppLog.i(TAG, it) }

    /** Планировщик отложенного возврата сети: тот же смысл, что `postDelayed` у handler-а сервиса. */
    internal object AndroidScheduler : JitRef.JitScheduler {
        override fun postDelayed(delayMs: Long, task: () -> Unit) {
            BgWorker.postDelayed("JitNet.restoreWifi", delayMs, task)
        }
    }

    // ─────────────────────────── shell ───────────────────────────

    internal object AndroidShell : JitRef.JitShell {
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> = runCatching {
            AdbClient.execute(command, timeoutMs)
        }.getOrElse { t -> Pair(1, "ERR_EXCEPTION:${t.javaClass.simpleName}") }

        /**
         * `w0/bb`: `BufferedReader(FileInputStream("/proc/self/maps"))` → строки.
         * Ошибка чтения ⇒ `null` (в референсе `catchall_67`).
         */
        override fun mapsExcerpt(): String? = runCatching {
            java.io.File("/proc/self/maps").readText()
        }.getOrNull()
    }

    // ─────────────────────────── префы ───────────────────────────

    /**
     * Префы операции поверх хранилищ проекта (`Prefs2.cps`/`Prefs2.dps`).
     *
     * Ключи и порядок — как у одного `Editor` референса. Отличия (только локально для JIT, общая
     * семантика `Prefs2` не меняется):
     *  • запись выполняется в КАЖДОЕ хранилище отдельно и отчитывается по каждому
     *    (`commit` + readback ВСЕХ трёх ключей);
     *  • `readRestore()` показывает, что именно увидит продолжение (порядок CPS→DPS, как у
     *    `getString`) и есть ли конфликт между хранилищами;
     *  • `remove(key, onlyStores)` не трогает хранилища, куда наша запись не прошла.
     */
    internal class AndroidStore(
        private val cpsProvider: () -> SharedPreferences?,
        private val dpsProvider: () -> SharedPreferences?
    ) : JitRef.JitStore {

        /**
         * Боевой конструктор. Провайдеры вынесены в параметры, чтобы ТОТ ЖЕ код можно было
         * проверить тестами на реалистичном `SharedPreferences` (отдельные память и диск,
         * «новый процесс»), а не только на фейке хранилища.
         */
        constructor(app: Context) : this(
            { try { Prefs2.cps(app) } catch (t: Throwable) { null } },
            { try { Prefs2.dps(app) } catch (t: Throwable) { null } }
        )

        private fun cps(): SharedPreferences? = runCatching { cpsProvider() }.getOrNull()
        private fun dps(): SharedPreferences? = runCatching { dpsProvider() }.getOrNull()

        /** Порядок тот же, что в [getString]: CPS первым. `distinct()` — если DPS недоступен. */
        private fun stores(): List<Pair<String, SharedPreferences>> {
            val out = ArrayList<Pair<String, SharedPreferences>>(2)
            cps()?.let { out += "cps" to it }
            dps()?.let { d -> if (out.none { it.second === d }) out += "dps" to d }
            return out
        }

        override fun getString(key: String): String? {
            val a = runCatching { cps()?.getString(key, null) }.getOrNull()
            val b = runCatching { dps()?.getString(key, null) }.getOrNull()
            if (a != null && b != null && a != b) {
                log("JIT_STORE conflict key=$key cps=\"${a.take(40)}\" dps=\"${b.take(40)}\" used=cps")
            }
            return a ?: b
        }

        override fun getBool(key: String): Boolean? {
            val a = runCatching { cps()?.let { if (it.contains(key)) it.getBoolean(key, false) else null } }
                .getOrNull()
            val b = runCatching { dps()?.let { if (it.contains(key)) it.getBoolean(key, false) else null } }
                .getOrNull()
            if (a != null && b != null && a != b) {
                log("JIT_STORE conflict key=$key cps=$a dps=$b used=cps")
            }
            return a ?: b
        }

        override fun contains(key: String): Boolean =
            runCatching { stores().any { it.second.contains(key) } }.getOrDefault(false)

        override fun putSnapshot(want: Boolean, snap: JitRef.WifiSnapshot): JitRef.SnapshotWrite {
            val all = stores()
            if (all.isEmpty()) {
                log("JIT_STORE snapshot attempted=0 reason=no_store")
                return JitRef.SnapshotWrite(want, snap, emptyList())
            }
            val reports = ArrayList<JitRef.StoreReport>(all.size)
            for ((name, s) in all) {
                var commit = "false"
                var err: String? = null
                try {
                    commit = if (
                        s.edit()
                            .putBoolean(KEY_PEDIDO, want)
                            .putBoolean(KEY_AUTO, want)
                            .putString(KEY_RESTORE, snap.wire())
                            .commit()
                    ) "ok" else "false"
                } catch (t: Throwable) {
                    commit = "exception:${t.javaClass.simpleName}"
                    err = t.message?.take(60)
                }
                // readback ВСЕХ трёх ключей: чтение одного Wi-Fi-ключа не доказывает остальных.
                val readback = runCatching {
                    val pedidoOk = s.contains(KEY_PEDIDO) && s.getBoolean(KEY_PEDIDO, !want) == want
                    val autoOk = s.contains(KEY_AUTO) && s.getBoolean(KEY_AUTO, !want) == want
                    val restoreOk = s.getString(KEY_RESTORE, null) == snap.wire()
                    pedidoOk && autoOk && restoreOk
                }.getOrDefault(false)
                reports += JitRef.StoreReport(name, commit, readback, err)
            }
            val w = JitRef.SnapshotWrite(want, snap, reports)
            log(
                "JIT_STORE snapshot keys=$KEY_PEDIDO,$KEY_AUTO,$KEY_RESTORE value=${snap.wire()} " +
                    "attempted=${w.attempted} committed=${w.committed} confirmed=${w.confirmed} " +
                    "detail=${w.detail} (как j9: один commit на хранилище)"
            )
            return w
        }

        /**
         * Что увидит продолжение: значение по порядку CPS→DPS + конфликты.
         *
         * Если хранилища расходятся, будущий снимок неоднозначен: при падении CPS повторный старт
         * прочитал бы DPS, а при живом CPS — CPS. Такой снимок не даёт права выключать Wi-Fi.
         */
        override fun readRestore(): JitRef.SnapshotRead {
            val all = stores()
            if (all.isEmpty()) return JitRef.SnapshotRead(null, emptyList())
            val values = all.map { (name, s) ->
                name to runCatching { s.getString(KEY_RESTORE, null) }.getOrNull()
            }
            val conflicts = ArrayList<String>()
            for (key in JitRef.SNAPSHOT_KEYS) {
                // Конфликт — это ДВА РАЗНЫХ значения. Отсутствие ключа во втором хранилище
                // конфликтом не является: продолжение прочитает первое, а если первое недоступно —
                // получит `null`, то есть «снимка нет» (операция просто не выполнится).
                val perStore = all.mapNotNull { (name, s) ->
                    runCatching {
                        when (key) {
                            KEY_RESTORE -> s.getString(key, null)?.let { name to it }
                            else -> if (s.contains(key)) name to s.getBoolean(key, false).toString() else null
                        }
                    }.getOrNull()
                }
                if (perStore.size > 1 && perStore.map { it.second }.distinct().size > 1) {
                    conflicts += "$key(${perStore.joinToString("/") { "${it.first}=${it.second}" }})"
                }
            }
            val value = values.firstOrNull { it.second != null }?.second
            val from = values.firstOrNull { it.second != null }?.first ?: "-"
            if (conflicts.isNotEmpty()) {
                log("JIT_STORE read_conflict value=$value from=$from conflicts=${conflicts.joinToString(",")}")
            }
            return JitRef.SnapshotRead(value, conflicts)
        }

        override fun remove(key: String, onlyStores: Set<String>?): JitRef.WriteResult {
            val all = stores().filter { onlyStores == null || it.first in onlyStores }
            if (all.isEmpty()) return JitRef.WriteResult.none("no_store")
            var ok = 0
            val detail = StringBuilder()
            for ((name, s) in all) {
                val good = runCatching { s.edit().remove(key).commit() }.getOrElse { t ->
                    log("JIT_STORE remove store=$name exception=${t.javaClass.simpleName}")
                    false
                }
                if (good) ok++ else detail.append(if (detail.isEmpty()) "" else ",").append("$name:commit_false")
            }
            val r = JitRef.WriteResult(all.size, ok, detail.ifEmpty { "all_ok" }.toString())
            log(
                "JIT_STORE remove key=$key attempted=${r.attempted} ok=${r.ok} detail=${r.detail}" +
                    (if (onlyStores != null) " only=${onlyStores.joinToString(",")}" else "")
            )
            return r
        }
    }

    // ─────────────────────────── состояние клиентского Wi-Fi ───────────────────────────

    /**
     * Только клиентский Wi-Fi: `isWifiEnabled`, `setWifiEnabled` и read-only признак адреса.
     *
     * `wifiOn()` возвращает `null`, если прочитать нельзя: трактовка вынесена в [JitOp].
     */
    internal class AndroidNetState(private val app: Context) : JitRef.JitNetState {

        private fun wm(): WifiManager? =
            runCatching { app.getSystemService(Context.WIFI_SERVICE) as WifiManager }.getOrNull()

        override fun wifiOn(): Boolean? = runCatching { wm()?.isWifiEnabled }.getOrNull()

        override fun setWifi(on: Boolean): Boolean? =
            runCatching { wm()?.setWifiEnabled(on) }.getOrNull()

        /**
         * Read-only: у `wlan0` есть не-loopback адрес. Признак подключения к точке доступа, но НЕ
         * доказательство работающего интернета (маршруты/DNS/TTL не проверяются и не меняются).
         * SSID/пароли не читаются и не сохраняются.
         */
        override fun wifiAddress(): Boolean? = runCatching {
            val ni = java.net.NetworkInterface.getByName("wlan0") ?: return@runCatching false
            ni.isUp && ni.inetAddresses.toList().any { !it.isLoopbackAddress }
        }.getOrNull()
    }

    // ─────────────────────────── публичный вход для AppPerformance ───────────────────────────

    fun read(app: Context, key: String): String? = AndroidStore(app).getString(key)

    fun contains(app: Context, key: String): Boolean = AndroidStore(app).contains(key)

    fun usejitFact(app: Context): JitRef.PropFact = JitOp.readProp(AndroidShell)

    /**
     * `true`/`false`/`null` по `/proc/self/maps` этого процесса — как `w0/j5.d`.
     * Не доказательство JIT системы: только то, что видит наша карта памяти.
     */
    fun codeCacheExecutable(): Boolean? =
        JitRef.cacheExecutableFromMaps(AndroidShell.mapsExcerpt())

    /** Клик тумблера — `w0/j9.onClick` + `w0/la.run`. Только из фонового потока. */
    fun click(app: Context, want: Boolean): JitOp.Click = JitOp.click(
        store = AndroidStore(app),
        netState = AndroidNetState(app),
        shell = AndroidShell,
        want = want,
        log = log
    )

    /**
     * Продолжение операции после рестарта фреймворка — `CentralWatchdogService.onCreate` + `w0/e2`.
     * Возвращается сразу: снимок снят, возврат Wi-Fi запланирован через 15 с ([AndroidScheduler]).
     */
    fun onProcessStart(app: Context, source: String = "process"): JitOp.StartOutcome =
        JitOp.onProcessStart(
            store = AndroidStore(app),
            netState = AndroidNetState(app),
            scheduler = AndroidScheduler,
            log = { log("source=$source $it") }
        )

    /** Сверка `w0/bb` + `w0/r7` (getprop + `/proc/self/maps` + `jit_pedido`). Фоновый поток. */
    fun verifyApplied(app: Context): JitOp.VerifyState = JitOp.verifyApplied(
        store = AndroidStore(app),
        shell = AndroidShell,
        log = log
    )

    /** Есть ли незавершённое намерение — дешёвая локальная проверка, без shell. */
    fun hasPendingPedido(app: Context): Boolean = AndroidStore(app).contains(KEY_PEDIDO)

    // ─────────────────────────── одноразовая read-only диагностика монтирований ───────────────────────────

    /** Процессный флаг: попытка сбора была (не чаще одной за процесс). */
    private val mountDiagAttempted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Одноразовый **read-only** сбор данных о монтированиях в датированный лог приложения.
     *
     * Требования, которые здесь соблюдены: запуск в фоне (не UI-поток), не чаще одной попытки за
     * процесс, без ожидания готовности канала в рабочем потоке, без кнопок и без связи с кликом JIT,
     * без записи в систему. Транспорт — тот же [AndroidShell], что и у JIT-цепочки, поэтому данные
     * относятся к тому же каналу и правам.
     */
    fun requestMountDiagOnce(app: Context, source: String, delayMs: Long = 0L) {
        if (!markMountDiagAttempted()) return
        val task: () -> Unit = {
            BgWorker.assertNotMain("JitNet.requestMountDiagOnce")
            if (!runCatching { AdbClient.isChannelReady() }.getOrDefault(false)) {
                AppLog.i(TAG, "JIT_MOUNT_DIAG source=$source status=root_unavailable action=none " +
                    "(канал не готов на момент единственной попытки; повторов и ожидания в потоке нет)")
            } else {
                runCatching { JitMountDiag.collect(AndroidShell, log, source) }
                    .onFailure { t ->
                        AppLog.i(TAG, "JIT_MOUNT_DIAG source=$source status=unknown " +
                            "reason=${t.javaClass.simpleName}")
                    }
            }
        }
        if (delayMs > 0) BgWorker.postDelayed("JitNet.mountDiag", delayMs, task)
        else BgWorker.post("JitNet.mountDiag", task)
    }

    /** Занять единственную попытку сбора в этом процессе (одноразовый флаг, без повторов). */
    internal fun markMountDiagAttempted(): Boolean = mountDiagAttempted.compareAndSet(false, true)

    /** Только для тестов: сбросить процессный флаг одноразового сбора. */
    internal fun resetMountDiagForTest() {
        mountDiagAttempted.set(false)
    }
}
