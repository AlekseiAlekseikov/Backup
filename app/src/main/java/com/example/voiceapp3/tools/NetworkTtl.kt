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

    // P-фикс: один BroadcastReceiver на процесс — иначе registerReceiver
    // вызывается на каждом onStartCommand и в логе дубли "network receiver registered" x6.
    @Volatile private var networkReceiver: BroadcastReceiver? = null

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
            echo TTL_BEFORE_V4=${'$'}($IPT -t mangle -S POSTROUTING 2>/dev/null | grep -c 'ttl-set 65')
            echo TTL_BEFORE_V6=${'$'}($IPT6 -t mangle -S POSTROUTING 2>/dev/null | grep -c 'hl-set 65')
            echo TTL_CNTR_BEFORE_V4=${'$'}($IPT -t mangle -L POSTROUTING -v -n 2>/dev/null | grep -F 'TTL' | awk '{print ${'$'}1}' | head -1)
            echo TTL_CNTR_BEFORE_V6=${'$'}($IPT6 -t mangle -L POSTROUTING -v -n 2>/dev/null | grep -F 'HL' | awk '{print ${'$'}1}' | head -1)
            for i in 1 2 3 4 5; do $IPT $W -t mangle -D POSTROUTING -j TTL --ttl-set 65 2>/dev/null || break; done
            for i in 1 2 3 4 5; do $IPT6 $W -t mangle -D POSTROUTING -j HL  --hl-set  65 2>/dev/null || break; done
        """.trimIndent() + "\n" + (if (enable) """
            $IPT  $W -t mangle -A POSTROUTING -j TTL --ttl-set 65 2>&1 || echo IPTV4_FAIL
            $IPT6 $W -t mangle -A POSTROUTING -j HL  --hl-set  65 2>&1 || echo IPTV6_FAIL
        """.trimIndent() else "") + """
            echo VERIFY_V4=${'$'}($IPT $W -t mangle -C POSTROUTING -j TTL --ttl-set 65 >/dev/null 2>&1 && echo OK || echo FAIL)
            echo VERIFY_V6=${'$'}($IPT6 $W -t mangle -C POSTROUTING -j HL  --hl-set  65 >/dev/null 2>&1 && echo OK || echo FAIL)
            echo TTL_V4=${'$'}($IPT -t mangle -S POSTROUTING 2>/dev/null | grep -c 'ttl-set 65')
            echo TTL_V6=${'$'}($IPT6 -t mangle -S POSTROUTING 2>/dev/null | grep -c 'hl-set 65')
            echo TTL_CNTR_AFTER_V4=${'$'}($IPT -t mangle -L POSTROUTING -v -n 2>/dev/null | grep -F 'TTL' | awk '{print ${'$'}1}' | head -1)
            echo TTL_CNTR_AFTER_V6=${'$'}($IPT6 -t mangle -L POSTROUTING -v -n 2>/dev/null | grep -F 'HL' | awk '{print ${'$'}1}' | head -1)
            # === Попытка РЕАЛЬНО измерить исходящий TTL ===
            # Метод 1 (предпочтительный): tcpdump. Пробуем все известные пути (system, toybox, busybox).
            # tcpdump -ni any -c 5 -v 'ip' — снимает 5 IP-пакетов и печатает ttl N в каждом.
            TTL_MEASURED=""
            TTL_METHOD=""
            for TCPDUMP in tcpdump toybox-tcpdump busybox-tcpdump /system/bin/tcpdump /system/xbin/tcpdump; do
              if command -v ${'$'}TCPDUMP >/dev/null 2>&1; then
                # Запускаем tcpdump в фоне и инициируем один ICMP пакет ping-ом (без -t!
                # он использует системный TTL, который и должно переписать mangle-правило).
                ${'$'}TCPDUMP -ni any -c 3 -v -s 0 'ip and dst host 1.1.1.1' -w /dev/null 2>/tmp/ttl_tcpdump.out &
                TD_PID=$!
                sleep 1
                ping -c 1 -W 2 1.1.1.1 >/dev/null 2>&1 || true
                wait ${'$'}TD_PID 2>/dev/null || true
                TTL_MEASURED=${'$'}(grep -oE 'ttl [0-9]+' /tmp/ttl_tcpdump.out | head -1 | awk '{print $2}')
                if [ -n "${'$'}TTL_MEASURED" ]; then TTL_METHOD="tcpdump(${'$'}TCPDUMP)"; break; fi
              fi
            done
            # Метод 2 (fallback): сравниваем счётчики правил TTL/HL ДО и ПОСЛЕ порции трафика.
            # Если счётчик увеличился — значит правило реально сработало на пакетах и TTL
            # переписан. Это не даёт числа TTL, но доказывает, что правило применяется.
            if [ -z "${'$'}TTL_MEASURED" ]; then
              CNTR_BEFORE=${'$'}($IPT -t mangle -L POSTROUTING -v -n 2>/dev/null | grep -F 'TTL' | awk '{print ${'$'}1}' | head -1)
              ping -c 3 -W 2 1.1.1.1 >/dev/null 2>&1 || true
              sleep 1
              CNTR_AFTER=${'$'}($IPT -t mangle -L POSTROUTING -v -n 2>/dev/null | grep -F 'TTL' | awk '{print ${'$'}1}' | head -1)
              echo TTL_CNTR_PING_BEFORE=${'$'}CNTR_BEFORE TTL_CNTR_PING_AFTER=${'$'}CNTR_AFTER
              if [ -n "${'$'}CNTR_BEFORE" ] && [ -n "${'$'}CNTR_AFTER" ] && [ "${'$'}CNTR_AFTER" -gt "${'$'}CNTR_BEFORE" ] 2>/dev/null; then
                TTL_MEASURED="counters_incremented(${'$'}CNTR_BEFORE->${'$'}CNTR_AFTER)"
                TTL_METHOD="iptables-counters-after-ping"
              else
                TTL_MEASURED=unavailable
                TTL_METHOD="no-tcpdump-and-counters-not-incremented"
              fi
            fi
            echo "TTL_CHECK: метод=${'$'}TTL_METHOD ttl=${'$'}TTL_MEASURED"
            rm -f /tmp/ttl_tcpdump.out
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
            hasNoRoot -> "требуется root-канал (порт 65010 не поднят). Проверьте в dev.checkRoot."
            hasPerm   -> "Permission denied (нужен root-шелл uid=0)"
            hasLock   -> "xtables lock (iptables занят, попробуйте снова)"
            noTarget  -> "iptables target 'TTL' не поддерживается ядром (модуль отсутствует)"
            !v4Ok     -> "TTL_V4 mismatch (expected=${if (expected) 1 else 0}, got=$v4 verify=$v4Verify)"
            !v6Ok     -> "TTL_V6 mismatch (expected=${if (expected) 1 else 0}, got=$v6 verify=$v6Verify)"
            else      -> null
        }
        val stepStatus = when {
            reason == null -> ToolsStep.Status.OK
            hasNoRoot -> ToolsStep.Status.BLOCKED
            hasPerm || noTarget -> ToolsStep.Status.FAILED
            else -> ToolsStep.Status.PARTIAL
        }
        return stepStatus to (reason ?: "TTL_V4=$v4 TTL_V6=$v6 VERIFY=$v4Verify/$v6Verify")
    }

    fun setTtl65Active(context: Context, enabled: Boolean): ToolsResult {
        BgWorker.assertNotMain("NetworkTtl.setTtl65Active")
        val t0 = SystemClock.uptimeMillis()
        getPrefs(context).edit().putBoolean(PREF_TTL_65_ACTIVE, enabled).apply()

        val script = buildScript(enabled)
        val (exitCode, output) = AdbClient.execute(script, 25000)

        val (status, reason) = parseVerifyResult(enabled, output)

        // P8: toggle back OFF if failed and we were trying to turn ON (PARTIAL/BLOCKED оставляем как есть — пользователь должен видеть не-идеал)
        if (enabled && status == ToolsStep.Status.FAILED) {
            AppLog.w(TAG, "TTL: toggle FAILED ($reason) — turning pref OFF to reflect reality")
            getPrefs(context).edit().putBoolean(PREF_TTL_65_ACTIVE, false).apply()
        }
        if (status == ToolsStep.Status.BLOCKED) {
            AppLog.w(TAG, "TTL: toggle BLOCKED ($reason). rootChannel state=${AdbClient.currentState()} port=${AdbClient.currentPort()}")
        }

        val expectedStr = if (enabled) "TTL_V4=1 TTL_V6=1 VERIFY=OK/OK" else "TTL_V4=0 TTL_V6=0 VERIFY=FAIL/FAIL"
        // Парсим строку TTL_CHECK: метод=... ttl=...
        val ttlCheckLine = output.lineSequence().firstOrNull { it.startsWith("TTL_CHECK:") } ?: ""
        val ttlMethod = Regex("метод=([^ ]+)").find(ttlCheckLine)?.groupValues?.get(1) ?: "none"
        val ttlValue  = Regex("ttl=([^ ]+)").find(ttlCheckLine)?.groupValues?.get(1) ?: "unavailable"
        val actualLines = output.lineSequence().filter {
            it.startsWith("TTL_") || it.startsWith("VERIFY_") || it.startsWith("TTL_CHECK:")
        }.joinToString(" | ")
        val ttlExpected = if (enabled) "65" else "default"
        val kv = parseKv(output)
        Log.i(TAG, "TTL_BEFORE_V4=${kv["TTL_BEFORE_V4"]} TTL_BEFORE_V6=${kv["TTL_BEFORE_V6"]}")
        Log.i(TAG, "TTL_AFTER_V4=${kv["TTL_V4"]} TTL_AFTER_V6=${kv["TTL_V6"]} VERIFY=${kv["VERIFY_V4"]}/${kv["VERIFY_V6"]}")
        Log.i(TAG, "TTL_CHECK: фактический TTL = $ttlValue (метод=$ttlMethod)")
        Log.i(TAG, "TTL_CHECK: ИТОГ = $ttlValue (ожидали $ttlExpected) → $status ($reason)")
        Log.i(TAG, "VERIFY TTL: expected enabled=$enabled ($expectedStr) → got $actualLines rc=$exitCode → $status ($reason)")

        val step = ToolsStep(
            step = "settings.ttl65",
            command = if (enabled) "iptables -w 5 -t mangle -A TTL --ttl-set 65" else "iptables -w 5 -t mangle -D flush",
            startedAtMs = t0,
            finishedAtMs = SystemClock.uptimeMillis(),
            exitCode = exitCode,
            stdout = output,
            stderr = if (status == ToolsStep.Status.OK) "" else (reason ?: "неизвестная ошибка") + " | rootState=${AdbClient.currentState()} port=${AdbClient.currentPort()}",
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
        // P-фикс: idempotent — если ресивер уже зарегистрирован, не дублировать
        // (иначе на каждом onStartCommand добавлялся ещё один = в логе x6).
        if (networkReceiver != null) {
            AppLog.i(TAG, "network receiver already registered — skip (single instance)")
            return
        }
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
        try {
            ctx.registerReceiver(recv, filter)
            networkReceiver = recv
            AppLog.i(TAG, "network receiver registered (single instance)")
        } catch (t: Throwable) {
            AppLog.e(TAG, "registerNetworkReceiver failed: ${t.message}", t)
        }
    }

    @JvmStatic fun unregisterNetworkReceiver(context: Context) {
        val ctx = context.applicationContext
        val recv = networkReceiver ?: return
        runCatching { ctx.unregisterReceiver(recv) }
        networkReceiver = null
        AppLog.i(TAG, "network receiver unregistered")
    }
}
