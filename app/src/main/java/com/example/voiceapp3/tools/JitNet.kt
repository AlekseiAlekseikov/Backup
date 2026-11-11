package com.example.voiceapp3.tools

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager

/**
 * Android-адаптеры для ядра [JitOp]. Решений здесь нет — только те вызовы, что есть в референсе:
 * `WifiManager.isWifiEnabled()` / `setWifiEnabled()`, чтение `/proc/self/maps` текущего процесса,
 * `getprop`, префы проекта.
 *
 * Точки доступа ГУ (`ap0`, `startSoftAp`, `WIFI_AP_STATE_CHANGED`, `wifi_state==14`) в JIT не
 * переносятся по решению владельца (iter36 §2.1) — ни здесь, ни в другом месте. `CarplayMonitor`,
 * `connadaptor_modo` и ConnAdaptor тоже не трогаются.
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
         *
         * Ошибка чтения ⇒ `null` (в референсе `catchall_67`), и тогда `maps` = `null`, а не
         * `false`. Разбор (фильтр `jit-cache` + права `\br[-w]x[ps]\b`) — в
         * [JitRef.cacheExecutableFromMaps], чтобы его можно было проверить тестами.
         */
        override fun mapsExcerpt(): String? = runCatching {
            java.io.File("/proc/self/maps").readText()
        }.getOrNull()
    }

    // ─────────────────────────── префы ───────────────────────────

    /**
     * Префы операции поверх хранилищ проекта (`Prefs2.cps`/`Prefs2.dps`).
     *
     * Ключи, значения и порядок — как у одного `Editor` референса. Отличие только в том, что
     * хранилищ два: запись выполняется в КАЖДОЕ независимо, а сводный результат показывает,
     * сколько попыток удалось (`attempted`/`ok`) — падение первого не отменяет второго. Если
     * доступно только одно хранилище, результат честно пишется как `1/1`, а не `2/2`.
     */
    internal class AndroidStore(private val app: Context) : JitRef.JitStore {

        private fun cps(): SharedPreferences? = runCatching { Prefs2.cps(app) }.getOrNull()
        private fun dps(): SharedPreferences? = runCatching { Prefs2.dps(app) }.getOrNull()

        private fun stores(): List<SharedPreferences> = listOfNotNull(cps(), dps()).distinct()

        private fun name(s: SharedPreferences): String =
            runCatching { s.javaClass.simpleName }.getOrDefault("store")

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
            runCatching { stores().any { it.contains(key) } }.getOrDefault(false)

        override fun putSnapshot(want: Boolean, snap: JitRef.WifiSnapshot): JitRef.WriteResult {
            val all = stores()
            if (all.isEmpty()) {
                log("JIT_STORE snapshot saved=false reason=no_store")
                return JitRef.WriteResult.none("no_store")
            }
            var ok = 0
            val detail = StringBuilder()
            for (s in all) {
                val n = name(s)
                val good = runCatching {
                    s.edit()
                        .putBoolean(KEY_PEDIDO, want)
                        .putBoolean(KEY_AUTO, want)
                        .putString(KEY_RESTORE, snap.wire())
                        .commit()
                }.getOrElse { t ->
                    log("JIT_STORE snapshot store=$n exception=${t.javaClass.simpleName}")
                    false
                }
                if (good) ok++ else detail.append(if (detail.isEmpty()) "" else ",").append("$n:commit_false")
            }
            val r = JitRef.WriteResult(all.size, ok, detail.ifEmpty { "all_ok" }.toString())
            log(
                "JIT_STORE snapshot keys=$KEY_PEDIDO,$KEY_AUTO,$KEY_RESTORE value=${snap.wire()} " +
                    "attempted=${r.attempted} ok=${r.ok} detail=${r.detail} " +
                    "(как j9: один commit на хранилище)"
            )
            return r
        }

        /**
         * Подтверждение снимка ЧТЕНИЕМ: сколько хранилищ реально отдают `KEY_RESTORE == expected`.
         *
         * Нужно, потому что `commit()==false` не доказывает, что значения нет в файле, а
         * `commit()==true` не доказывает, что оно доедет до диска при внезапной смерти процесса.
         * Решение «можно ли выключать Wi-Fi скриптом» принимается по этому чтению, а не по
         * возвращённому `true`.
         */
        override fun confirmSnapshot(expected: String): Int = runCatching {
            stores().count { s -> s.getString(KEY_RESTORE, null) == expected }
        }.getOrDefault(0)

        override fun remove(key: String): JitRef.WriteResult {
            val all = stores()
            if (all.isEmpty()) return JitRef.WriteResult.none("no_store")
            var ok = 0
            val detail = StringBuilder()
            for (s in all) {
                val n = name(s)
                val good = runCatching { s.edit().remove(key).commit() }.getOrElse { t ->
                    log("JIT_STORE remove store=$n exception=${t.javaClass.simpleName}")
                    false
                }
                if (good) ok++ else detail.append(if (detail.isEmpty()) "" else ",").append("$n:commit_false")
            }
            val r = JitRef.WriteResult(all.size, ok, detail.ifEmpty { "all_ok" }.toString())
            log("JIT_STORE remove key=$key attempted=${r.attempted} ok=${r.ok} detail=${r.detail}")
            return r
        }

        /** Есть ли снимок/намерение — локальная проверка без shell. */
        fun has(key: String): Boolean = contains(key)
    }

    // ─────────────────────────── состояние клиентского Wi-Fi ───────────────────────────

    /**
     * Только клиентский Wi-Fi: `isWifiEnabled`, `setWifiEnabled` и read-only признак адреса.
     *
     * `wifiOn()` возвращает `null`, если прочитать нельзя: трактовка вынесена в [JitOp], чтобы
     * правило было видно в одном месте (`j9` считал `false`; здесь неизвестное — повод НЕ выключать
     * Wi-Fi скриптом).
     */
    internal class AndroidNetState(private val app: Context) : JitRef.JitNetState {

        private fun wm(): WifiManager? =
            runCatching { app.getSystemService(Context.WIFI_SERVICE) as WifiManager }.getOrNull()

        override fun wifiOn(): Boolean? = runCatching { wm()?.isWifiEnabled }.getOrNull()

        override fun setWifi(on: Boolean): Boolean? =
            runCatching { wm()?.setWifiEnabled(on) }.getOrNull()

        /**
         * Read-only: у `wlan0` есть не-loopback адрес. Это признак подключения к точке доступа, но
         * НЕ доказательство работающего интернета (маршруты/DNS/TTL не проверяются и не меняются).
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
}
