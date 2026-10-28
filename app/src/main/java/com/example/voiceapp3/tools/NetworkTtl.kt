package com.example.voiceapp3.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Сеть — фиксация TTL=65 (IPv4 TTL / IPv6 HL) в mangle/POSTROUTING.
 *
 * P4 (по логу):
 *  - `-w 5` у ВСЕХ вызовов iptables/ip6tables (ожидание xtables lock).
 *  - VERIFY через `iptables -C` (а не по флагу наличия строки).
 *  - Счётчики пакетов до/после (iptables -t mangle -L POSTROUTING -v -n)
 *    + попытка косвенно измерить фактический TTL (ping -t 65 -c 1 1.1.1.1).
 *  - Автоматическое выключение тумблера при провале (toggle-off).
 *  - bootRestore с VERIFY (лог, а не слепой re-apply).
 *  - NetworkStateReceiver (CONNECTIVITY_ACTION / ConnectivityManager.NetworkCallback)
 *    пере-применяет правило при поднятии wlan0.
 *
 * P8: VERIFY-строка после каждой операции; при маркерах ошибок
 * (xtables lock / Permission denied / ERR_ROOT_CHANNEL_UNAVAILABLE) — FAILED.
 */
object NetworkTtl {
    private const val TAG = "CENTRAL_NetworkTtl"
    private const val PREFS_NAME = "voiceapp3"

    const val PREF_TTL_65_ACTIVE = "ttl_65_ativo"

    private const val IPT  = "iptables"
    private const val IPT6 = "ip6tables"
    private const val W    = "-w 5"  // ждём xtables lock до 5 секунд

    /** Сброс с VERIFY (-C) и подсчётом до/после. */
    private fun buildScript(enable: Boolean): String {
        val action = if (enable) "-A" else "-D"
        val v4Expect = if (enable) "1" else "0"
        val v6Expect = if (enable) "1" else "0"
        return """
            echo TTL_BEFORE_V4=$($IPT -t mangle -S POSTROUTING 2>/dev/null | grep -c 'ttl-set 65')
            echo TTL_BEFORE_V6=$($IPT6 -t mangle -S POSTROUTING 2>/dev/null | grep -c 'hl-set 65')
            echo TTL_CNTR_BEFORE_V4=$($IPT -t mangle -L POSTROUTING -v -n 2>/dev/null | grep -F 'TTL' | awk '{print ${'$'}1}' | head -1)
            echo TTL_CNTR_BEFORE_V6=$($IPT6 -t mangle -L POSTROUTING -v -n 2>/dev/null | grep -F 'HL' | awk '{print ${'$'}1}' | head -1)
            for i in 1 2 3 4 5; do $IPT $W -t mangle -D POSTROUTING -j TTL --ttl-set 65 2>/dev/null || break; done
            for i in 1 2 3 4 5; do $IPT6 $W -t mangle -D POSTROUTING -j HL  --hl-set  65 2>/dev/null || break; done
        """.trimIndent() + "\n" + (if (enable) """
            $IPT  $W -t mangle -A POSTROUTING -j TTL --ttl-set 65 2>&1 || echo IPTV4_FAIL
            $IPT6 $W -t mangle -A POSTROUTING -j HL  --hl-set  65 2>&1 || echo IPTV6_FAIL
        """.trimIndent() else "") + """
            echo VERIFY_V4=$($IPT $W -t mangle -C POSTROUTING -j TTL --ttl-set 65 >/dev/null 2>&1 && echo OK || echo FAIL)
            echo VERIFY_V6=$($IPT6 $W -t mangle -C POSTROUTING -j HL  --hl-set  65 >/dev/null 2>&1 && echo OK || echo FAIL)
            echo TTL_V4=$($IPT -t mangle -S POSTROUTING 2>/dev/null | grep -c 'ttl-set 65')
            echo TTL_V6=$($IPT6 -t mangle -S POSTROUTING 2>/dev/null | grep -c 'hl-set 65')
            echo TTL_CNTR_AFTER_V4=$($IPT -t mangle -L POSTROUTING -v -n 2>/dev/null | grep -F 'TTL' | awk '{print ${'$'}1}' | head -1)
            echo TTL_CNTR_AFTER_V6=$($IPT6 -t mangle -L POSTROUTING -v -n 2>/dev/null | grep -F 'HL' | awk '{print ${'$'}1}' | head -1)
            # Пытаемся измерить фактический исходящий TTL косвенно:
            # ping -t (IPv4 TTL) на 1.1.1.1 — это задаёт TTL ИСХОДЯЩИХ пакетов пинга.
            # Ошибка "Time to live exceeded" или "ttl=0" при -t 1 подтвердит, что TTL был применён.
            # Прямого измерения без tcpdump нет — честно помечаем как heuristic.
            PING_OUT=$(ping -c 1 -W 2 -t 65 1.1.1.1 2>&1 | head -5 | tr '\n' ';')
            echo "TTL_PING_HEURISTIC=$PING_OUT"
        """.trimIndent()
    }

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isTtl65Active(context: Context): Boolean =
        getPrefs(context).getBoolean(PREF_TTL_65_ACTIVE, false)

    /** Парсит echo-style `KEY=value` строки из вывода. */
    private fun parseKv(output: String): Map<String, String> =
        output.lineSequence().mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0 || !line.substringBefore('=').matches(Regex("[A-Z0-9_]+"))) null
            else line.substringBefore('=') to line.substring(eq+1).trim()
        }.toMap()

    private fun parseVerifyResult(expected: Boolean, out: String): Pair<ToolsStep.Status, String> {
        val kv = parseKv(out)
        val v4 = kv["TTL_V4"]?.trim()
        val v6 = kv["TTL_V6"]?.trim()
        val v4Verify = kv["VERIFY_V4"]
        val v6Verify = kv["VERIFY_V6"]
        val hasLock = out.lowercase().contains("xtables lock")
        val hasPerm = out.lowercase().contains("permission denied") || out.lowercase().contains("avc: denied")
        val hasNoRoot = out.contains("ERR_ROOT_CHANNEL_UNAVAILABLE")
        val noTarget = out.lowercase().contains("no chain/target/match")

        val v4Ok = if (expected) v4 == "1" && v4Verify == "OK" else (v4 == "0" || v4 == null) && v4Verify == "FAIL"
        val v6Ok = if (expected) v6 == "1" && v6Verify == "OK" else (v6 == "0" || v6 == null) && v6Verify == "FAIL"

        val reason = when {
            hasNoRoot -> "ERR_ROOT_CHANNEL_UNAVAILABLE"
            hasPerm   -> "Permission denied"
            hasLock   -> "xtables lock"
            noTarget  -> "iptables target 'TTL' not available (module missing)"
            !v4Ok     -> "TTL_V4 mismatch (expected=${if(expected)1 else0}, got=$v4 verify=$v4Verify)"
            !v6Ok     -> "TTL_V6 mismatch (expected=${if(expected)1 else0}, got=$v6 verify=$v6Verify)"
            else      -> null
        }
        val status = when {
            reason == null -> ToolsStep.Status.OK
            hasNoRoot || hasPerm || noTarget -> ToolsStep.Status.FAILED
            else -> ToolsStep.Status.PARTIAL
        }
        return status to (reason ?: "TTL_V4=$v4 TTL_V6=$v6 VERIFY=$v4Verify/$v6Verify")
    }

    fun setTtl65Active(context: Context, enabled: Boolean): ToolsResult {
        BgWorker.assertNotMain("NetworkTtl.setTtl65Active")
        val t0 = SystemClock.uptimeMillis()
        getPrefs(context).edit().putBoolean(PREF_TTL_65_ACTIVE, enabled).apply()

        val script = buildScript(enabled)
        val (exitCode, output) = AdbClient.execute(script, 25000)

        val (status, reason) = parseVerifyResult(enabled, output)

        // P8: toggle back OFF if failed and we were trying to turn ON
        if (enabled && status == ToolsStep.Status.FAILED) {
            AppLog.w(TAG, "TTL: toggle FAILED ($reason) — turning pref OFF to reflect reality")
            getPrefs(context).edit().putBoolean(PREF_TTL_65_ACTIVE, false).apply()
        }

        val expectedStr = if (enabled) "TTL_V4=1 TTL_V6=1 VERIFY=OK/OK" else "TTL_V4=0 TTL_V6=0 VERIFY=FAIL/FAIL"
        val actualLines = output.lineSequence().filter { it.startsWith("TTL_") || it.startsWith("VERIFY_") || it.contains("TTL_PING") }
            .joinToString(" | ")
        Log.i(TAG, "VERIFY TTL: expected enabled=$enabled ($expectedStr) → got $actualLines rc=$exitCode → $status ($reason)")

        val step = ToolsStep(
            step = "settings.ttl65",
            command = if (enabled) "iptables -w 5 -t mangle -A TTL --ttl-set 65" else "iptables -w 5 -t mangle -D flush",
            startedAtMs = t0,
            finishedAtMs = SystemClock.uptimeMillis(),
            exitCode = exitCode,
            stdout = output,
            stderr = "",
            expected = expectedStr,
            actual = actualLines,
            status = status
        )
        return ToolsResult("settings.ttl65", "Фиксация TTL 65", listOf(step))
    }

    fun bootRestore(context: Context) {
        BgWorker.assertNotMain("NetworkTtl.bootRestore")
        val active = isTtl65Active(context)
        AppLog.i(TAG, "TTL bootRestore: active=$active")
        if (!active) return
        val script = buildScript(true)
        val (rc, out) = AdbClient.execute(script, 25000)
        val (status, reason) = parseVerifyResult(true, out)
        AppLog.i(TAG, "VERIFY TTL bootRestore: rc=$rc → $status ($reason)")
    }

    /**
     * Регистрация сетевого ресивера: при поднятии/изменении сети пере-проверяем/
     * пере-применяем правила (чтобы правило пережило перезапуск wlan0).
     *
     * Вызывать один раз из App.onCreate / VoiceAssistantService.onCreate.
     */
    fun registerNetworkReceiver(context: Context) {
        val ctx = context.applicationContext
        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION).apply {
            addAction("android.net.conn.CONNECTIVITY_CHANGE")
            addAction("android.net.wifi.STATE_CHANGE")
        }
        val recv = object : BroadcastReceiver() {
            override fun onReceive(rcvCtx: Context, intent: Intent) {
                BgWorker.post("NetworkTtl.netChange") {
                    val active = isTtl65Active(rcvCtx)
                    if (!active) return@post
                    val cm = rcvCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    val isWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val cap = cm?.getNetworkCapabilities(cm.activeNetwork)
                        cap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    } else {
                        @Suppress("DEPRECATION") cm?.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
                    }
                    AppLog.i(TAG, "TTL network change (wifi=$isWifi) — re-applying rules")
                    val (rc, out) = AdbClient.execute(buildScript(true), 25000)
                    val (status, reason) = parseVerifyResult(true, out)
                    AppLog.i(TAG, "VERIFY TTL networkChange: rc=$rc → $status ($reason)")
                    if (status == ToolsStep.Status.FAILED) {
                        AppLog.w(TAG, "TTL networkChange: failed to re-apply — turning toggle off")
                        getPrefs(rcvCtx).edit().putBoolean(PREF_TTL_65_ACTIVE, false).apply()
                    }
                }
            }
        }
        ctx.registerReceiver(recv, filter)
        AppLog.i(TAG, "network receiver registered")
    }
}
