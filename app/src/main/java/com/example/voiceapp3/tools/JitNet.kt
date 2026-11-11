package com.example.voiceapp3.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.SystemClock

/**
 * Android-адаптеры для ядра [JitOp]. Решений здесь нет — только те вызовы, что есть в референсе:
 * `WifiManager.isWifiEnabled()` / `getWifiState()` / `setWifiEnabled()`,
 * `NetworkInterface.getByName("ap0")`, рефлексия `startSoftAp(WifiConfiguration)`, `getprop`,
 * `/proc/self/maps`, префы, события `WIFI_AP_STATE_CHANGED` (как `w0/u2`).
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
            BgWorker.postDelayed("JitNet.restoreNetwork", delayMs, task)
        }
    }

    // ─────────────────────────── shell ───────────────────────────

    internal object AndroidShell : JitRef.JitShell {
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> = runCatching {
            AdbClient.execute(command, timeoutMs)
        }.getOrElse { t -> Pair(1, "ERR_EXCEPTION:${t.javaClass.simpleName}") }

        /**
         * `w0/bb`: читает `/proc/self/maps` и ищет строку кэша JIT; ошибка чтения ⇒ `null`
         * (в референсе это `Boolean?`, и при `null` условие `maps != FALSE` истинно).
         *
         * Это наблюдение карты ТЕКУЩЕГО процесса, а не доказательство работающего JIT системы.
         */
        override fun cacheVisible(): Boolean? = runCatching {
            java.io.File("/proc/self/maps").useLines { lines ->
                lines.any { it.contains("jit-cache") || it.contains("dalvik-jit-code-cache") }
            }
        }.getOrNull()
    }

    internal object AndroidWaiter : JitRef.JitWaiter {
        override fun sleep(ms: Long) {
            runCatching { Thread.sleep(ms) }
        }

        override fun nowMs(): Long = SystemClock.elapsedRealtime()
    }

    // ─────────────────────────── префы ───────────────────────────

    /**
     * Префы операции поверх хранилищ проекта (`Prefs2.cps`/`Prefs2.dps`).
     *
     * Ключи, значения и порядок — как у одного `Editor` референса. Отличие только в том, что
     * хранилищ два: запись выполняется в КАЖДОЕ независимо, а сводный результат показывает,
     * сколько попыток удалось (`attempted`/`ok`) — падение первого не отменяет второго.
     */
    internal class AndroidStore(private val app: Context) : JitRef.JitStore {

        private fun cps(): SharedPreferences? = runCatching { Prefs2.cps(app) }.getOrNull()
        private fun dps(): SharedPreferences? = runCatching { Prefs2.dps(app) }.getOrNull()

        private fun stores(): List<SharedPreferences> = listOfNotNull(cps(), dps()).distinct()

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

        override fun putSnapshot(want: Boolean, net: JitRef.Net): JitRef.WriteResult {
            val all = stores()
            if (all.isEmpty()) {
                log("JIT_STORE snapshot saved=false reason=no_store")
                return JitRef.WriteResult.none("no_store")
            }
            var ok = 0
            val detail = StringBuilder()
            for (s in all) {
                val name = s.javaClass.simpleName
                val res = runCatching {
                    s.edit()
                        .putBoolean(KEY_PEDIDO, want)
                        .putBoolean(KEY_AUTO, want)
                        .putString(KEY_RESTORE, net.wire())
                        .commit()
                }
                val good = res.getOrElse { t ->
                    log("JIT_STORE snapshot store=$name exception=${t.javaClass.simpleName}")
                    false
                }
                if (good) ok++ else detail.append(if (detail.isEmpty()) "" else ",").append("$name:fail")
                // Каждое хранилище — своя попытка: результат одного не отменяет другого.
            }
            val r = JitRef.WriteResult(all.size, ok, detail.ifEmpty { "all_ok" }.toString())
            log(
                "JIT_STORE snapshot keys=$KEY_PEDIDO,$KEY_AUTO,$KEY_RESTORE value=${net.wire()} " +
                    "attempted=${r.attempted} ok=${r.ok} detail=${r.detail} " +
                    "(как j9: один commit на хранилище; результат не гейтит отправку скрипта)"
            )
            return r
        }

        override fun remove(key: String): JitRef.WriteResult {
            val all = stores()
            if (all.isEmpty()) return JitRef.WriteResult.none("no_store")
            var ok = 0
            val detail = StringBuilder()
            for (s in all) {
                val name = s.javaClass.simpleName
                val good = runCatching { s.edit().remove(key).commit() }.getOrElse { t ->
                    log("JIT_STORE remove store=$name exception=${t.javaClass.simpleName}")
                    false
                }
                if (good) ok++ else detail.append(if (detail.isEmpty()) "" else ",").append("$name:fail")
            }
            val r = JitRef.WriteResult(all.size, ok, detail.ifEmpty { "all_ok" }.toString())
            log("JIT_STORE remove key=$key attempted=${r.attempted} ok=${r.ok} detail=${r.detail}")
            return r
        }
    }

    // ─────────────────────────── состояние радио ───────────────────────────

    /**
     * `WifiManager`, `ap0` и события `WIFI_AP_STATE_CHANGED` — как в референсе.
     *
     * `wifiOn()` возвращает `null`, если прочитать нельзя: референс в разных местах трактует это
     * по-разному (`j9`: `false`; `c1`/`U`: `true`; `e2`: исключение обрывает возврат) — правила
     * применяются в [JitOp], чтобы их было видно в одном месте.
     */
    internal class AndroidNetState(private val app: Context) : JitRef.JitNetState {

        @Volatile private var apFailedAt: Long = 0L
        private var receiver: BroadcastReceiver? = null

        private fun wm(): WifiManager? =
            runCatching { app.getSystemService(Context.WIFI_SERVICE) as WifiManager }.getOrNull()

        override fun wifiOn(): Boolean? = runCatching { wm()?.isWifiEnabled }.getOrNull()

        override fun wifiEnabling(): Boolean? = runCatching {
            wm()?.wifiState == WifiManager.WIFI_STATE_ENABLING
        }.getOrNull()

        override fun apUp(): Boolean? = runCatching {
            val ni = java.net.NetworkInterface.getByName("ap0") ?: return@runCatching false
            ni.isUp && ni.inetAddresses.hasMoreElements()
        }.getOrNull()

        override fun setWifi(on: Boolean): Boolean? =
            runCatching { wm()?.setWifiEnabled(on) }.getOrNull()

        /**
         * `U.d(String)`: `WifiManager.class.getDeclaredMethod("startSoftAp",
         * Class.forName("android.net.wifi.WifiConfiguration"))`, `setAccessible(true)`,
         * `invoke(wm, new Object[]{null})`. Результат `invoke` референс не использует; здесь он
         * только для честной строки лога.
         */
        override fun startAp(): Boolean? = runCatching {
            val w = wm() ?: return@runCatching null
            val m = w.javaClass.getDeclaredMethod(
                "startSoftAp",
                Class.forName("android.net.wifi.WifiConfiguration")
            )
            m.isAccessible = true
            when (val r = m.invoke(w, null)) {
                is Boolean -> r
                else -> true
            }
        }.getOrElse { t ->
            log("JIT_AP startSoftAp FALHOU — ${t.javaClass.simpleName}: ${t.message}")
            null
        }

        override fun apFailedAtMs(): Long = apFailedAt

        /**
         * Приёмник `w0/u2` референса: `WIFI_AP_STATE_CHANGED` + `wifi_state == 14`
         * (`WIFI_AP_STATE_FAILED`) ⇒ запомнить `elapsedRealtime`. Регистрируется только на время
         * попытки и снимается в [endApWatch], чтобы не держать постоянную подписку.
         */
        override fun beginApWatch() {
            if (receiver != null) return
            val r = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val i = intent ?: return
                    // Строки — как в APK референса (`w0/u2`), а не @SystemApi-константы.
                    if (i.action != JitRef.AP_STATE_CHANGED_ACTION) return
                    val state = i.getIntExtra(JitRef.EXTRA_WIFI_AP_STATE, -1)
                    if (state == JitRef.AP_STATE_FAILED) apFailedAt = SystemClock.elapsedRealtime()
                }
            }
            runCatching {
                app.registerReceiver(r, IntentFilter(JitRef.AP_STATE_CHANGED_ACTION))
            }.onSuccess {
                receiver = r
                log(
                    "JIT_AP watch=on filter=${JitRef.AP_STATE_CHANGED_ACTION} " +
                        "failed_state=${JitRef.AP_STATE_FAILED}"
                )
            }.onFailure {
                log("JIT_AP watch=failed reason=${it.javaClass.simpleName} (сигнал FAILED недоступен)")
            }
        }

        override fun endApWatch() {
            val r = receiver ?: return
            receiver = null
            runCatching { app.unregisterReceiver(r) }
            log("JIT_AP watch=off")
        }

        /**
         * `U.O()` — «CarplayMonitor не готов». В нашем приложении источника нет: в референсе это
         * поля `n1/o1/p1` сервиса, установленный `com.njda.adapter` (`M()`) и их преф
         * `connadaptor_modo` (`w0.u.b`). Возвращаем `null` = «неизвестно», и [JitOp.openAp]
         * честно пишет `carplay_wait=skipped reason=signal_unavailable`, а не изобретает состояние.
         */
        override fun carplayBusy(): Boolean? = null
    }

    // ─────────────────────────── публичный вход для AppPerformance ───────────────────────────

    fun read(app: Context, key: String): String? = AndroidStore(app).getString(key)

    fun contains(app: Context, key: String): Boolean = AndroidStore(app).contains(key)

    fun usejitFact(app: Context): JitRef.PropFact = JitOp.readProp(AndroidShell)

    fun codeCacheVisible(): Boolean? = AndroidShell.cacheVisible()

    /** Клик тумблера — `w0/j9.onClick` + `w0/la.run` (референс). Только из фонового потока. */
    fun click(app: Context, want: Boolean): JitOp.Click = JitOp.click(
        store = AndroidStore(app),
        netState = AndroidNetState(app),
        shell = AndroidShell,
        want = want,
        log = log
    )

    /**
     * Продолжение операции после рестарта фреймворка — `CentralWatchdogService.onCreate` + `w0/e2`.
     *
     * Возвращается сразу: снимок снят, `e2` запланирован через 15 с ([AndroidScheduler]).
     * Ожидание больше не занимает поток общего пула.
     */
    fun onProcessStart(app: Context): JitOp.StartOutcome = JitOp.onProcessStart(
        store = AndroidStore(app),
        netState = AndroidNetState(app),
        waiter = AndroidWaiter,
        scheduler = AndroidScheduler,
        log = log
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
