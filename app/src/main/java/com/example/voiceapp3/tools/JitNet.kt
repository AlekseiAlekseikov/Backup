package com.example.voiceapp3.tools

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager

/**
 * iter33p (1.0.61) — сеть и операция JIT вокруг перезапуска фреймворка: Android-адаптер.
 *
 * Вся логика и решения — в [JitNetCore] (без `android.*`, покрыто `JitNetCoreTest`, а текст скрипта
 * исполняется настоящим `sh` в `JitChainScriptTest`). Здесь только связка с устройством:
 * `WifiManager`, `AdbClient`, `Prefs2`, `AppLog`.
 *
 * Границы (владелец, 09.11 + уточнение iter33p):
 *  • сетевые команды — ТОЛЬКО внутри операции JIT: звено клика и продолжение этой же операции
 *    после подъёма процесса; обычный старт ГУ сеть не трогает;
 *  • `ip rule` — только подсчёт (read-only): удаление правил не доказано, поэтому не выполняется;
 *  • `ctl.restart zygote` — единственная команда рестарта, в единственном скрипте по клику;
 *  • никаких `pkill`/`kill`/`pgrep -f`, никакого `umount` чужих точек, никакого `setenforce`.
 */
object JitNet {

    private const val TAG = "CENTRAL_JitNet"

    internal const val PREF_OP = JitNetModel.PREF_OP
    internal const val PREF_LAST = JitNetModel.PREF_LAST
    internal const val DRAIN_TIMEOUT_S = JitNetModel.DRAIN_TIMEOUT_S
    internal const val WIFI_RESTORE_ATTEMPTS = JitNetModel.WIFI_RESTORE_ATTEMPTS
    internal const val WIFI_RESTORE_PAUSE_MS = JitNetModel.WIFI_RESTORE_PAUSE_MS
    internal const val COMPENSATE_ATTEMPTS = JitNetModel.COMPENSATE_ATTEMPTS
    internal const val OPERATION_TTL_MS = JitNetModel.OPERATION_TTL_MS

    internal const val PROP_USEJIT = JitNetModel.PROP_USEJIT
    internal const val PROP_PERSIST_USEJIT_INIT = JitNetModel.PROP_PERSIST_USEJIT_INIT

    fun parseOp(saved: String?): JitNetModel.Op? = JitNetModel.parseOp(saved)
    fun parseApProbe(out: String?): Boolean? = JitNetModel.parseApProbe(out)
    fun apProbeCommand(): String = JitNetModel.apProbeCommand()
    fun rulesProbeCommand(): String = JitNetModel.rulesProbeCommand()
    fun journalPath(opId: String): String = JitNetModel.journalPath(opId)
    internal fun jitChainScript(opId: String, want: String, journal: String): String =
        JitNetModel.jitChainScript(opId, want, journal)
    internal fun propsRollbackScript(usejitBefore: String): String =
        JitNetModel.propsRollbackScript(usejitBefore)
    fun journalSteps(text: String?, opId: String): Map<String, String> =
        JitNetModel.journalSteps(text, opId)

    // ─────────────────────────────── связка с устройством ───────────────────────────────

    private fun prefs(app: Context) = object : NetPrefs {
        override fun get(key: String): String? = Prefs2.getString(app, key)
        override fun put(key: String, value: String?): Boolean {
            Prefs2.putString(app, key, value)
            val back = Prefs2.getString(app, key)
            return if (value == null) back == null else back == value
        }
    }

    private fun wifiManager(app: Context): WifiManager? = runCatching {
        app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }.getOrNull()

    fun apState(): Boolean? = JitNetModel.parseApProbe(probeAp())

    private fun probeAp(): String? = runCatching {
        AdbClient.execute(JitNetModel.apProbeCommand(), 6_000, expectRoot = false).second
    }.getOrNull()

    internal fun linkState(iface: String): String {
        val (code, out) = runCatching {
            AdbClient.execute("ip -o addr show $iface 2>/dev/null", 6_000, expectRoot = false)
        }.getOrDefault(Pair(1, ""))
        if (out.contains("ERR_") || (code != 0 && out.isBlank())) return "unknown"
        return if (out.lineSequence().any { it.contains("inet ") }) "up" else "down"
    }

    /** pidof zygote — read-only признак смены фреймворка. */
    internal fun zygotePid(): String = runCatching {
        AdbClient.execute("pidof zygote 2>/dev/null", 6_000, expectRoot = false).second
    }.getOrDefault("").lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun readJournal(path: String): String? = runCatching {
        AdbClient.execute("cat $path 2>/dev/null", 6_000, expectRoot = false).second
    }.getOrNull()

    private fun purgeJournals(keepOpId: String?): Int {
        val keep = if (keepOpId.isNullOrBlank()) "" else JitNetModel.journalPath(keepOpId)
        val n = bashCount(JitNetModel.JOURNAL_DIR, keep)
        val (_, out) = runCatching { AdbClient.execute(n, 10_000) }.getOrDefault(Pair(1, ""))
        return Regex("PURGED=([0-9]+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /** Удаление ТОЛЬКО собственных журналов операций (каталог `/data/local/tmp`). */
    private fun bashCount(dir: String, keep: String): String {
        val D = "$"
        return "n=0; for f in $dir/${JitNetModel.JOURNAL_PREFIX}*.log; do " +
            "[ -f \"${D}f\" ] || continue; " +
            (if (keep.isEmpty()) "" else "[ \"${D}f\" = \"$keep\" ] && continue; ") +
            "rm -f \"${D}f\" && n=${D}((n+1)); done; echo PURGED=${D}n"
    }

    private fun core(context: Context): JitNetCore {
        val app = context.applicationContext
        return JitNetCore(
            prefs = prefs(app),
            readWifi = { runCatching { wifiManager(app)?.isWifiEnabled }.getOrNull() },
            setWifi = { on -> runCatching { wifiManager(app)?.setWifiEnabled(on) } },
            apProbe = { probeAp() },
            link = { iface -> linkState(iface) },
            readZygotePid = { zygotePid() },
            readJournal = { path -> readJournal(path) },
            purgeJournals = { keep -> purgeJournals(keep) },
            runScript = { script, timeout ->
                val (code, out) = runCatching { AdbClient.executeScript("jit_net", script, timeout) }
                    .getOrDefault(Pair(1, ""))
                ScriptOut(code, out)
            },
            log = { line -> AppLog.i(TAG, line) },
            now = { System.currentTimeMillis() },
            sleep = { ms -> runCatching { Thread.sleep(ms) } },
            ttlMs = OPERATION_TTL_MS
        ).also { c ->
            c.startSoftAp = {
                val wm = wifiManager(app) ?: throw IllegalStateException("no_wifi_service")
                wm.javaClass.getMethod("startSoftAp", WifiConfiguration::class.java).invoke(wm, null)
            }
        }
    }

    fun currentOp(context: Context): JitNetModel.Op? = core(context).currentOp()

    fun saveOperation(context: Context, bootTag: String, want: String, opId: String): JitNetModel.Op? {
        BgWorker.assertNotMain("JitNet.saveOperation")
        return core(context).saveOperation(bootTag, want, opId)
    }

    fun clearOp(context: Context, opId: String): Boolean = core(context).clearOp(opId)

    fun purgeOldJournals(context: Context, keepOpId: String?) = core(context).purgeOldJournals(keepOpId)

    fun runChain(context: Context, op: JitNetModel.Op): ScriptOut {
        BgWorker.assertNotMain("JitNet.runChain")
        return core(context).runChain(op, JitNetModel.journalPath(op.id))
    }

    fun phaseOf(context: Context, op: JitNetModel.Op): JitFlow.Phase = core(context).phaseOf(op)

    /**
     * Разбор незавершённой операции. Сетевые команды — только по решению
     * [JitFlow.pendingAction]; в `HOLD_UNKNOWN` их нет.
     */
    fun reconcilePending(
        context: Context,
        currentBootTag: String,
        where: String,
        lateRestartPossible: Boolean = true
    ): Recovery {
        BgWorker.assertNotMain("JitNet.reconcilePending")
        return core(context).reconcilePending(currentBootTag, where, lateRestartPossible = lateRestartPossible)
    }

    /** Раздельные факты применения JIT (getprop / maps / рестарт / вердикт). */
    fun applyFacts(context: Context, op: JitNetModel.Op?): ApplyFacts {
        val rt = runCatching {
            AdbClient.execute("getprop ${JitNetModel.PROP_USEJIT}", 5_000, expectRoot = false).second
        }.getOrDefault("").trim()
        val ps = runCatching {
            AdbClient.execute("getprop ${JitNetModel.PROP_PERSIST_USEJIT_INIT}", 5_000, expectRoot = false).second
        }.getOrDefault("").trim()
        val maps: Boolean? = runCatching {
            java.io.File("/proc/self/maps").useLines { l -> l.any { it.contains("dalvik-jit-code-cache") } }
        }.getOrNull()
        val zNow = zygotePid()
        val restart = JitFlow.restartVerdict(op?.zygotePid ?: "", zNow)
        val want = op?.want ?: ""
        return ApplyFacts(
            getprop = rt, persist = ps, maps = maps, restart = restart,
            verdict = JitFlow.verdict(rt, want, maps, restart), want = want, zygoteNow = zNow
        )
    }

    data class ApplyFacts(
        val getprop: String,
        val persist: String,
        val maps: Boolean?,
        val restart: String,
        val verdict: String,
        val want: String,
        val zygoteNow: String
    ) {
        fun log(): String =
            "getprop=${getprop.ifEmpty { "empty" }} want=${want.ifEmpty { "empty" }} " +
                "persist=${persist.ifEmpty { "empty" }} maps=${maps?.let { if (it) "yes" else "no" } ?: "unknown"} " +
                "restart=$restart verdict=$verdict"
    }
}
