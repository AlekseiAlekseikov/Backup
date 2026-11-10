package com.example.voiceapp3.tools

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager

/**
 * Android-адаптеры для ядра операции [JitOp]. Здесь нет ни одного решения: только чтение
 * состояния, запуск скрипта через [AdbClient] и запрос сети. Вся последовательность — в [JitRef]
 * и [JitOp] (без `android.*`, покрыта JVM-тестами `JitRefTest`, `JitChainScriptTest`,
 * `JitChainEndToEndTest`).
 *
 * Границы (мандат §1.2): сетевые команды — только в клике JIT и в продолжении этой же операции;
 * `ip rule` только читается; `ctl.restart zygote` — единственная команда рестарта; никаких
 * persist-свойств JIT, `kill`/`pkill`, `ip rule del`, `settings`, `umount` по умолчанию.
 */
object JitNet {

    private const val TAG = "CENTRAL_JitNet"

    internal const val KEY_PEDIDO = JitRef.KEY_PEDIDO
    internal const val KEY_AUTO = JitRef.KEY_AUTO
    internal const val KEY_RESTORE = JitRef.KEY_RESTORE
    internal const val KEY_RESTORE_BOOT = JitRef.KEY_RESTORE_BOOT
    internal const val KEY_RESTORE_TS = JitRef.KEY_RESTORE_TS
    internal const val PROP_USEJIT = JitRef.PROP_USEJIT
    internal const val DRAIN_MAX_S = JitRef.DRAIN_MAX_S
    internal const val RESTORE_DELAY_MS = JitRef.RESTORE_DELAY_MS
    internal const val CHAIN_TIMEOUT_MS = JitRef.CHAIN_TIMEOUT_MS

    fun chainScript(want: Boolean, mountStep: Boolean = JitRef.MOUNT_STEP_ENABLED): String =
        JitRef.chainScript(want, mountStep)

    fun parseRestore(raw: String?): JitRef.Net? = JitRef.parseRestore(raw)

    fun verify(pedido: Boolean, getprop: String, cache: Boolean?): JitRef.Verify =
        JitRef.verify(pedido, getprop, cache)

    /** Лог операции — одной функцией, чтобы строки шли и в файл, и в logcat. */
    private val log: (String) -> Unit = { AppLog.i(TAG, it) }

    // ─────────────────────────── Android-реализации швов ───────────────────────────

    private object AndroidShell : JitShell {
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> = runCatching {
            AdbClient.execute(command, timeoutMs)
        }.getOrDefault(Pair(1, "ERR_EXCEPTION"))

        override fun cacheVisible(): Boolean? = runCatching {
            java.io.File("/proc/self/maps").useLines { l -> l.any { it.contains("dalvik-jit-code-cache") } }
        }.getOrNull()
    }

    internal class AndroidStore(private val app: Context) : JitStore {
        override fun get(key: String): String? = Prefs2.getString(app, key)
        override fun put(key: String, value: String?): Boolean {
            Prefs2.putString(app, key, value)
            return Prefs2.getString(app, key) == value
        }
        override fun getBool(key: String, def: Boolean): Boolean = Prefs2.getBoolean(app, key, def)
        override fun putBool(key: String, value: Boolean): Boolean {
            Prefs2.putBoolean(app, key, value)
            return Prefs2.getBoolean(app, key, !value) == value
        }
    }

    internal class AndroidNetState(private val app: Context) : JitNetState {
        private fun wm(): WifiManager? = runCatching {
            app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        }.getOrNull()

        override fun wifiOn(): Boolean? = runCatching { wm()?.isWifiEnabled }.getOrNull()

        override fun apUp(): Boolean? = runCatching {
            val ni = java.net.NetworkInterface.getByName("ap0") ?: return@runCatching false
            ni.isUp && ni.inetAddresses.hasMoreElements()
        }.getOrNull()

        override fun setWifi(on: Boolean): Boolean {
            val w = wm() ?: return false
            return runCatching { w.isWifiEnabled = on }.isSuccess
        }

        override fun startAp(): Boolean {
            val w = wm() ?: return false
            return runCatching {
                w.javaClass.getMethod("startSoftAp", WifiConfiguration::class.java).invoke(w, null)
            }.isSuccess
        }
    }

    // ─────────────────────────── чтение состояния (read-only) ───────────────────────────

    fun read(app: Context, key: String): String? = AndroidStore(app).get(key)
    fun readBool(app: Context, key: String, def: Boolean = false): Boolean =
        AndroidStore(app).getBool(key, def)

    fun usejitNow(@Suppress("UNUSED_PARAMETER") app: Context): String =
        AndroidShell.exec("getprop $PROP_USEJIT", 6_000).second.trim()

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

    // ─────────────────────────── операция (делегируется в [JitOp]) ───────────────────────────

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
            "net=${res.net?.log() ?: "unknown"} rc=${res.run?.exitCode ?: -1} " +
            "outcome=${res.run?.let { JitRef.chainOutcomeLabel(it.outcome) } ?: "none"} " +
            "mount_step=${if (res.mountStep) "on" else "off"} (рестарт фреймворка не заявляем)")
        return res
    }

    fun runChain(app: Context, want: Boolean, mountStep: Boolean = JitRef.MOUNT_STEP_ENABLED): JitOp.ChainRun {
        BgWorker.assertNotMain("JitNet.runChain")
        val script = JitRef.chainScript(want, mountStep)
        log("JIT_CHAIN_SENT want=$want bytes=${script.toByteArray(Charsets.UTF_8).size} " +
            "timeout_ms=$CHAIN_TIMEOUT_MS mount_step=${if (mountStep) "on" else "off"}")
        val run = AndroidShell.exec(script, CHAIN_TIMEOUT_MS).let { JitOp.ChainRun(it.first, it.second) }
        log("JIT_CHAIN_RETURN rc=${run.exitCode} outcome=${JitRef.chainOutcomeLabel(run.outcome)} " +
            "out=\"${run.output.replace('\n', ' ').trim().take(200)}\" restart=unknown " +
            "(ответ транспорта сам по себе не доказывает ни рестарт, ни его отсутствие)")
        return run
    }

    fun restoreNetwork(app: Context, net: JitRef.Net, reason: String): Boolean {
        BgWorker.assertNotMain("JitNet.restoreNetwork")
        val ok = JitOp.restoreNetwork(AndroidNetState(app), net, reason, log)
        log("JIT_NET_RESTORE link=${linkUp(app)} oif_wlan0_rules=${oifWlan0Rules(app)} " +
            "confirmed=$ok (правила только читаем; подтверждено состояние, а не наличие интернета)")
        return ok
    }

    fun continueAfterBoot(app: Context, bootTag: String): JitOp.RestoreResult {
        BgWorker.assertNotMain("JitNet.continueAfterBoot")
        return JitOp.continueAfterBoot(
            store = AndroidStore(app),
            netState = AndroidNetState(app),
            bootTag = bootTag,
            sleep = { ms -> runCatching { Thread.sleep(ms) } },
            log = log
        )
    }

    fun verifyApplied(app: Context, wantFallback: Boolean): JitOp.VerifyState {
        BgWorker.assertNotMain("JitNet.verifyApplied")
        return JitOp.verifyApplied(AndroidStore(app), AndroidShell, wantFallback, log)
    }

    fun clearPedido(app: Context) {
        AndroidStore(app).put(KEY_PEDIDO, null)
    }

    fun clearRestore(app: Context) {
        val st = AndroidStore(app)
        st.put(KEY_RESTORE, null)
        st.put(KEY_RESTORE_BOOT, null)
        st.put(KEY_RESTORE_TS, null)
    }
}
