package com.example.voiceapp3.tools

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager

/**
 * Android-адаптеры для ядра [JitOp]. Решений здесь нет — только те вызовы, что есть в референсе:
 * `WifiManager.isWifiEnabled()` / `setWifiEnabled()`, `NetworkInterface.getByName("ap0")`,
 * рефлексия `startSoftAp(WifiConfiguration)`, `getprop`, `/proc/self/maps`, префы.
 *
 * Сетевые команды выполняются только внутри цепочки клика и её продолжения после рестарта
 * фреймворка; `ip rule` только читается; `ctl.restart zygote` — единственная команда рестарта.
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

    // ─────────────────────────── shell ───────────────────────────

    internal object AndroidShell : JitRef.JitShell {
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> = runCatching {
            AdbClient.execute(command, timeoutMs)
        }.getOrElse { t -> Pair(1, "ERR_EXCEPTION:${t.javaClass.simpleName}") }

        /**
         * `w0/bb` читает `/proc/self/maps` и ищет строку кэша JIT; ошибка чтения ⇒ `null`
         * (в референсе это `Boolean?` и при `null` условие `maps != FALSE` истинно).
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

        override fun nowMs(): Long = System.currentTimeMillis()
    }

    // ─────────────────────────── префы: три ключа одним commit() ───────────────────────────

    /**
     * Префы операции поверх тех же хранилищ, что и остальное приложение (`Prefs2.cps`/`Prefs2.dps`).
     *
     * Порядок записи/чтения — как в референсе: один `Editor` на хранилище (`putBoolean(jit_pedido)`,
     * `putBoolean(perf_auto_otimizar)`, `putString(jit_restaurar)`) и один `commit()`. Результат
     * `commit()` здесь возвращается наружу и пишется в лог (`saved=…`), но цепочка от него не
     * зависит — ровно как в референсе, который результат не смотрит вовсе.
     */
    internal class AndroidStore(private val app: Context) : JitRef.JitStore {

        private fun cps(): SharedPreferences? = runCatching { Prefs2.cps(app) }.getOrNull()
        private fun dps(): SharedPreferences? = runCatching { Prefs2.dps(app) }.getOrNull()

        private fun stores(): List<SharedPreferences> =
            listOfNotNull(cps(), dps()).distinct()

        override fun getString(key: String): String? =
            runCatching { cps()?.getString(key, null) ?: dps()?.getString(key, null) }.getOrNull()

        override fun getBool(key: String): Boolean? =
            runCatching {
                cps()?.let { if (it.contains(key)) it.getBoolean(key, false) else null }
                    ?: dps()?.let { if (it.contains(key)) it.getBoolean(key, false) else null }
            }.getOrNull()

        override fun contains(key: String): Boolean =
            runCatching { stores().any { it.contains(key) } }.getOrDefault(false)

        override fun putSnapshot(want: Boolean, net: JitRef.Net): Boolean {
            val all = stores()
            if (all.isEmpty()) {
                log("JIT_STORE snapshot saved=false reason=no_store")
                return false
            }
            var saved = true
            for (s in all) {
                val ok = runCatching {
                    s.edit()
                        .putBoolean(KEY_PEDIDO, want)
                        .putBoolean(KEY_AUTO, want)
                        .putString(KEY_RESTORE, net.wire())
                        .commit()
                }.getOrElse { t ->
                    log("JIT_STORE snapshot store=${s.javaClass.simpleName} " +
                        "exception=${t.javaClass.simpleName}")
                    false
                }
                saved = saved && ok
            }
            log(
                "JIT_STORE snapshot keys=$KEY_PEDIDO,$KEY_AUTO,$KEY_RESTORE " +
                    "value=${net.wire()} saved=$saved commit_result_is_informational=true " +
                    "(как j9: один commit, результат не влияет на отправку скрипта)"
            )
            return saved
        }

        override fun putBool(key: String, value: Boolean): Boolean {
            val all = stores()
            if (all.isEmpty()) return false
            var saved = true
            for (s in all) {
                saved = saved && runCatching { s.edit().putBoolean(key, value).commit() }
                    .getOrDefault(false)
            }
            return saved
        }

        override fun putString(key: String, value: String?): Boolean {
            val all = stores()
            if (all.isEmpty()) return false
            var saved = true
            for (s in all) {
                saved = saved && runCatching {
                    val e = s.edit()
                    if (value == null) e.remove(key) else e.putString(key, value)
                    e.commit()
                }.getOrDefault(false)
            }
            return saved
        }
    }

    // ─────────────────────────── состояние радио ───────────────────────────

    /**
     * `WifiManager` и `ap0` — как в референсе.
     *
     * `wifiOn()` возвращает `null`, если прочитать нельзя: референс в РАЗНЫХ местах трактует это
     * по-разному (`j9`: `false`; guard `U`: `true`; `e2`: исключение обрывает возврат) — эти
     * правила применяются в [JitOp], а не здесь, чтобы их было видно в одном месте.
     */
    internal class AndroidNetState(private val app: Context) : JitRef.JitNetState {

        private fun wm(): WifiManager? =
            runCatching { app.getSystemService(Context.WIFI_SERVICE) as WifiManager }.getOrNull()

        override fun wifiOn(): Boolean? = runCatching { wm()?.isWifiEnabled() }.getOrNull()

        override fun apUp(): Boolean? = runCatching {
            val ni = java.net.NetworkInterface.getByName("ap0") ?: return@runCatching false
            ni.isUp && ni.inetAddresses.hasMoreElements()
        }.getOrNull()

        override fun setWifi(on: Boolean): Boolean? =
            runCatching { wm()?.setWifiEnabled(on) }.getOrNull()

        /**
         * `d(String)` референса: `WifiManager.class.getDeclaredMethod("startSoftAp",
         * Class.forName("android.net.wifi.WifiConfiguration"))`, `setAccessible(true)`,
         * `invoke(wm, null)`. Результат `invoke` в референсе не используется — здесь он
         * возвращается только для честной строки лога (`accepted=`), поведение то же.
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
                null -> true // void/null: вызов прошёл
                else -> true
            }
        }.getOrElse { t ->
            log("JIT_AP startSoftAp FALHOU — ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    // ─────────────────────────── публичный вход для AppPerformance ───────────────────────────

    fun read(app: Context, key: String): String? = AndroidStore(app).getString(key)

    fun contains(app: Context, key: String): Boolean = AndroidStore(app).contains(key)

    fun usejitFact(app: Context): JitRef.PropFact = JitOp.readProp(AndroidShell)

    fun codeCacheVisible(): Boolean? = AndroidShell.cacheVisible()

    /** Клик тумблера — `w0/j9.onClick` + `w0/la.run` (референс). Вызывать только из фонового потока. */
    fun click(app: Context, want: Boolean): JitOp.Click = JitOp.click(
        store = AndroidStore(app),
        netState = AndroidNetState(app),
        shell = AndroidShell,
        want = want,
        waiter = AndroidWaiter,
        log = log
    )

    /**
     * Продолжение операции после рестарта фреймворка — `CentralWatchdogService.onCreate` + `w0/e2`.
     * Функция блокирующая: 15 с паузы + ожидание AP до 20 с ⇒ только из фонового потока.
     */
    fun onProcessStart(app: Context): JitOp.StartOutcome = JitOp.onProcessStart(
        store = AndroidStore(app),
        netState = AndroidNetState(app),
        waiter = AndroidWaiter,
        log = log
    )

    /** Сверка `w0/bb` + `w0/r7` (getprop + `/proc/self/maps` + `jit_pedido`). Фоновый поток. */
    fun verifyApplied(app: Context): JitOp.VerifyState = JitOp.verifyApplied(
        store = AndroidStore(app),
        shell = AndroidShell,
        log = log
    )
}
