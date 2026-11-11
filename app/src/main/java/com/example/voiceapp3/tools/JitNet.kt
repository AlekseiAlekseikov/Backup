package com.example.voiceapp3.tools

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager

/**
 * Android-адаптеры для ядра [JitOp]. Решений здесь нет: только чтение состояния, запись префов с
 * проверкой результата, запуск скрипта и запрос сети.
 *
 * Границы (мандат §1.2): сетевые команды — только в клике JIT и в продолжении этой же операции;
 * `ip rule` только читается; `ctl.restart zygote` — единственная команда рестарта; никаких
 * persist-свойств JIT, `kill`/`pkill`, `ip rule del`, `flush`, `route`, `settings`,
 * `umount` по умолчанию (`JitRef.MOUNT_STEP_ENABLED=false`).
 */
object JitNet {

    private const val TAG = "CENTRAL_JitNet"

    internal const val KEY_PEDIDO = JitRef.KEY_PEDIDO
    internal const val KEY_AUTO = JitRef.KEY_AUTO
    internal const val KEY_RESTORE = JitRef.KEY_RESTORE
    internal const val KEY_OP = JitRef.KEY_OP
    internal const val PROP_USEJIT = JitRef.PROP_USEJIT
    internal const val DRAIN_MAX_S = JitRef.DRAIN_MAX_S
    internal const val RESTORE_DELAY_MS = JitRef.RESTORE_DELAY_MS
    internal const val CHAIN_TIMEOUT_MS = JitRef.CHAIN_TIMEOUT_MS
    internal const val MOUNT_STEP_ENABLED = JitRef.MOUNT_STEP_ENABLED

    private val log: (String) -> Unit = { AppLog.i(TAG, it) }

    // ─────────────────────────── shell ───────────────────────────

    private object AndroidShell : JitShell {
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> = runCatching {
            AdbClient.execute(command, timeoutMs)
        }.getOrElse { t -> Pair(1, "ERR_EXCEPTION:${t.javaClass.simpleName}") }

        override fun cacheVisible(): Boolean? = runCatching {
            java.io.File("/proc/self/maps").useLines { l -> l.any { it.contains("dalvik-jit-code-cache") } }
        }.getOrNull()
    }

    internal object AndroidWaiter : JitWaiter {
        override fun sleep(ms: Long) { runCatching { Thread.sleep(ms) } }
        override fun nowMs(): Long = System.currentTimeMillis()
    }

    /**
     * Хранилище операции поверх CPS+DPS.
     *
     * `Prefs2` писать результат `commit()` наружу не умеет и глотает ошибки, поэтому здесь свои
     * два `commit()` с проверкой и обратным чтением. Общая семантика `Prefs2` для остальных
     * функций НЕ меняется (REVIEW §0.1(5)).
     */
    internal class AndroidStore(private val app: Context) : JitStore {

        private fun cps(): SharedPreferences? = runCatching { Prefs2.cps(app) }.getOrNull()
        private fun dps(): SharedPreferences? = runCatching { Prefs2.dps(app) }.getOrNull()

        override fun getString(key: String): String? {
            val c = runCatching { cps()?.getString(key, null) }.getOrNull()
            val d = runCatching { dps()?.getString(key, null) }.getOrNull()
            return c ?: d
        }

        /**
         * Запись ОДНОГО ключа в оба хранилища + подтверждение чтением.
         *
         * Подтверждено = хотя бы одна запись вернула `commit()==true`, И чтение обратно (та же
         * логика приоритета, что в `Prefs2.getString`) дало ровно записанное значение.
         * Для `null` подтверждаем «ключа больше нет ни в одном хранилище».
         */
        override fun putStringConfirmed(key: String, value: String?): Boolean {
            var cpsOk = false
            var dpsOk = false
            runCatching {
                cps()?.let { p ->
                    val ed = p.edit()
                    if (value == null) ed.remove(key) else ed.putString(key, value)
                    cpsOk = ed.commit()
                }
            }
            runCatching {
                dps()?.let { p ->
                    val ed = p.edit()
                    if (value == null) ed.remove(key) else ed.putString(key, value)
                    dpsOk = ed.commit()
                }
            }
            val back = getString(key)
            val ok = (cpsOk || dpsOk) && back == value
            AppLog.i(TAG, "JIT_STORE put key=$key confirmed=$ok cps=$cpsOk dps=$dpsOk " +
                "back=${if (back == null) "null" else "len${back.length}"}")
            return ok
        }

        override fun getBool(key: String): Boolean? {
            val c = runCatching { cps()?.getBoolean(key, false) }.getOrNull()
            val d = runCatching { dps()?.getBoolean(key, false) }.getOrNull()
            val hasC = runCatching { cps()?.contains(key) }.getOrNull() == true
            val hasD = runCatching { dps()?.contains(key) }.getOrNull() == true
            if (!hasC && !hasD) return null
            return (c == true) || (d == true)
        }

        override fun putBoolConfirmed(key: String, value: Boolean): Boolean {
            var cpsOk = false
            var dpsOk = false
            runCatching { cps()?.let { cpsOk = it.edit().putBoolean(key, value).commit() } }
            runCatching { dps()?.let { dpsOk = it.edit().putBoolean(key, value).commit() } }
            val back = getBool(key)
            val ok = (cpsOk || dpsOk) && back == value
            AppLog.i(TAG, "JIT_STORE put key=$key(bool=$value) confirmed=$ok cps=$cpsOk dps=$dpsOk")
            return ok
        }
    }

    /** Реальное состояние сети. */
    internal class AndroidNetState(private val app: Context) : JitNetState {

        private fun wm(): WifiManager? = runCatching {
            app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        }.getOrNull()

        override fun wifiOn(): Boolean? = runCatching { wm()?.isWifiEnabled }.getOrNull()

        override fun apUp(): Boolean? = runCatching {
            val ni = java.net.NetworkInterface.getByName("ap0") ?: return@runCatching false
            ni.isUp && ni.inetAddresses.hasMoreElements()
        }.getOrNull()

        /**
         * Запрос включения/выключения Wi-Fi. Как в референсе (`w0/e2`), результат запроса не
         * считается состоянием: фактическое состояние потом читается отдельно.
         */
        @Suppress("DEPRECATION")
        override fun setWifi(on: Boolean): Pair<Boolean, String> {
            val w = wm() ?: return Pair(false, "wifi_manager_absent")
            return runCatching { Pair(w.setWifiEnabled(on), "request_sent") }
                .getOrElse { t -> Pair(false, "throw:${t.javaClass.simpleName}") }
        }

        /** Как `U`/`d`: рефлексия `startSoftAp(WifiConfiguration)`, аргумент `null`. */
        override fun startAp(): Pair<Boolean, String> {
            val w = wm() ?: return Pair(false, "wifi_manager_absent")
            return runCatching {
                val m = w.javaClass.getDeclaredMethod("startSoftAp", WifiConfiguration::class.java)
                m.isAccessible = true
                m.invoke(w, null)
                Pair(true, "reflection_invoked")
            }.getOrElse { t -> Pair(false, "throw:${t.javaClass.simpleName}") }
        }
    }

    // ─────────────────────────── чтение состояния (read-only) ───────────────────────────

    fun read(app: Context, key: String): String? = AndroidStore(app).getString(key)
    fun readBool(app: Context, key: String): Boolean? = AndroidStore(app).getBool(key)

    /** Факт `getprop dalvik.vm.usejit` с rc и причиной (для UI и логов). */
    fun usejitFact(app: Context): JitRef.PropFact = JitOp.readProp(AndroidShell)

    fun codeCacheVisible(): Boolean? = AndroidShell.cacheVisible()

    fun linkUp(app: Context, iface: String = "wlan0"): String {
        val (code, out) = runCatching {
            AdbClient.execute("ip -o addr show $iface 2>/dev/null", 6_000, expectRoot = false)
        }.getOrDefault(Pair(1, ""))
        if (out.contains("ERR_") || (code != 0 && out.isBlank())) return "unknown"
        return if (out.lineSequence().any { it.contains("inet ") }) "up" else "down"
    }

    fun oifWlan0Rules(app: Context): Int {
        val (_, out) = runCatching {
            AdbClient.execute("ip rule show 2>/dev/null | grep -c 'oif wlan0'", 6_000, expectRoot = false)
        }.getOrDefault(Pair(1, ""))
        return out.trim().toIntOrNull() ?: -1
    }

    // ─────────────────────────── операция ───────────────────────────

    fun click(app: Context, want: Boolean, bootTag: String, nowMs: Long): JitOp.Click {
        BgWorker.assertNotMain("JitNet.click")
        val res = JitOp.click(
            store = AndroidStore(app),
            netState = AndroidNetState(app),
            shell = AndroidShell,
            want = want,
            bootTag = bootTag,
            nowMs = nowMs,
            mountStep = JitRef.MOUNT_STEP_ENABLED,
            log = log
        )
        log("JIT_CLICK result=${res.result.name.lowercase()} want=$want boot=$bootTag " +
            "op=${res.op?.log() ?: "none"} rc=${res.run?.exitCode ?: -1} " +
            "outcome=${res.run?.let { JitRef.chainOutcomeLabel(it.outcome) } ?: "none"} " +
            "mount_step=${if (res.mountStep) "on" else "off"} (рестарт фреймворка не заявляем)")
        return res
    }

    fun onProcessStart(app: Context, bootTag: String): JitOp.StartOutcome {
        BgWorker.assertNotMain("JitNet.onProcessStart")
        val out = JitOp.onProcessStart(
            store = AndroidStore(app),
            netState = AndroidNetState(app),
            shell = AndroidShell,
            bootTag = bootTag,
            waiter = AndroidWaiter,
            log = log
        )
        log("JIT_START_RESULT decision=${out.decision.name.lowercase()} " +
            "restore=${out.restore?.summary ?: "none"} verify=${out.verify?.verdict?.let { JitRef.verifyLabel(it) } ?: "none"} " +
            "link=${linkUp(app)} oif_wlan0_rules=${oifWlan0Rules(app)} (правила только читаем)")
        return out
    }

    fun verifyApplied(app: Context, wantFallback: Boolean?): JitOp.VerifyState {
        BgWorker.assertNotMain("JitNet.verifyApplied")
        return JitOp.verifyApplied(AndroidStore(app), AndroidShell, wantFallback, true, log)
    }
}
