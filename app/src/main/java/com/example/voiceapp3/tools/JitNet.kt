package com.example.voiceapp3.tools

import android.content.Context
import android.net.wifi.WifiManager

/**
 * iter33p §2 — сеть вокруг перезапуска фреймворка, ровно как в референсе (CentralEXAuto 3.3.9,
 * байткод `w0/la.run` → `w0/e2.run`).
 *
 * Почему это появилось: 1.0.60 после рестарта zygote оставляла «Wi-Fi подключён, страницы не
 * грузятся» — в policy-routing висели правила `ip rule … oif wlan0` от прошлого состояния Wi-Fi.
 * Референс гасит Wi-Fi ДО рестарта, ждёт, пока правила `oif wlan0` уйдут, и поднимает Wi-Fi после.
 *
 * Цепочка (порядок строгий, держится `JitToggleChainTest`):
 *  1. сохранить состояние: `wifi = isWifiEnabled()`, `ap = ap0 up && has IP` → лог `JIT_NET_PRE wifi=… ap=…`;
 *  2. `svc wifi disable` → ждать ≤20 с, пока `ip rule show | grep -c 'oif wlan0'` не станет 0
 *     → `setprop dalvik.vm.usejit <X>` (+ `usejitprofiles`) → (рестарт фреймворка делает watcher);
 *  3. после подъёма — санация зависших `oif wlan0`, возврат Wi-Fi (до 3 попыток) и AP.
 *
 * Границы (правило владельца 09.11): сетевые команды живут ТОЛЬКО внутри цепочки тумблера JIT;
 * наружу цепочки не вызываются; трогаем только правила `oif wlan0`.
 */
object JitNet {

    private const val TAG = "CENTRAL_JitNet"

    /** Сохранённое сетевое состояние (формат референса `jit_restaurar`: `wifi=1,ap=0`). */
    internal const val PREF_NET_SAVED = "jit_net_saved"

    /** Сколько ждём, пока правила `oif wlan0` уйдут после `svc wifi disable` (как в `la.run`). */
    internal const val DRAIN_TIMEOUT_S = 20

    /** Возврат Wi-Fi после подъёма: до 3 попыток с паузой 3 с. */
    internal const val WIFI_RESTORE_ATTEMPTS = 3
    internal const val WIFI_RESTORE_PAUSE_MS = 3_000L

    data class State(val wifi: Boolean, val ap: Boolean) {
        /** `wifi=1 ap=0` — для логов. */
        fun log(): String = "wifi=${if (wifi) 1 else 0} ap=${if (ap) 1 else 0}"

        /** `wifi=1,ap=0` — формат хранения (как `jit_restaurar` референса). */
        fun wire(): String = "wifi=${if (wifi) 1 else 0},ap=${if (ap) 1 else 0}"
    }

    /** Разбор сохранённого состояния; null — если строки нет/она битая. */
    internal fun parse(saved: String?): State? {
        if (saved.isNullOrBlank()) return null
        val w = Regex("wifi=([01])").find(saved)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val a = Regex("ap=([01])").find(saved)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return State(wifi = w == 1, ap = a == 1)
    }

    fun savedState(context: Context): State? =
        parse(Prefs2.getString(context.applicationContext, PREF_NET_SAVED))

    fun clearSaved(context: Context) =
        Prefs2.putString(context.applicationContext, PREF_NET_SAVED, null)

    /** Текущее состояние: Wi-Fi — через WifiManager, AP — по `ap0` (up + есть IPv4). */
    fun readState(context: Context): State? = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        State(wifi = wm.isWifiEnabled, ap = apIsUp())
    }.getOrNull()

    internal fun apIsUp(): Boolean = runCatching {
        val (_, out) = AdbClient.execute("ip -o addr show ap0 2>/dev/null", 6_000, expectRoot = false)
        out.lineSequence().any { it.contains("inet ") }
    }.getOrDefault(false)

    /**
     * §2.1 — сохранение перед рестартом (только чтение + одна строка в префах) и лог
     * `JIT_NET_PRE wifi=… ap=…`.
     */
    fun saveBeforeRestart(context: Context): State? {
        val app = context.applicationContext
        val st = readState(app)
        if (st == null) {
            AppLog.i(TAG, "JIT_NET_PRE skipped reason=no_wifi_service")
            return null
        }
        Prefs2.putString(app, PREF_NET_SAVED, st.wire())
        AppLog.i(TAG, "JIT_NET_PRE ${st.log()} source=wifi_manager saved=${st.wire()}")
        return st
    }

    /**
     * §2.2 — скрипт подготовки (механизм `la.run`): погасить Wi-Fi, дождаться, пока из policy-routing
     * уйдут правила `oif wlan0`, и только потом поднять props. Рестарт фреймворка — следующим шагом
     * в watcher-е (сам рестарт живёт только в блоке JIT_ZYGOTE файла AppPerformance.kt).
     */
    internal fun preRestartScript(usejitValue: String): String {
        val D = "$"
        return """
            #!/system/bin/sh
            # iter33p §2.2 — как `w0/la.run`: svc wifi disable → drain oif wlan0 → setprop
            svc wifi disable >/dev/null 2>&1
            i=0
            n=1
            while [ ${D}i -lt $DRAIN_TIMEOUT_S ]; do
              n=${D}(ip rule show 2>/dev/null | grep -c 'oif wlan0')
              [ "${D}n" = "0" ] && break
              sleep 1
              i=${D}((i+1))
            done
            setprop $PROP_USEJIT $usejitValue 2>/dev/null
            setprop $PROP_USEJIT_PROFILES $usejitValue 2>/dev/null
            echo "JIT_NET_PRE ip_rule_wait=${D}i drained=${D}([ "${D}n" = "0" ] && echo yes || echo no) oif_wlan0=${D}n"
            echo "RT=${D}(getprop $PROP_USEJIT)"
            echo "PS=${D}(getprop $PROP_PERSIST_USEJIT_INIT)"
        """.trimIndent()
    }

    internal const val PROP_USEJIT = "dalvik.vm.usejit"
    internal const val PROP_USEJIT_PROFILES = "dalvik.vm.usejitprofiles"
    internal const val PROP_PERSIST_USEJIT_INIT = "persist.dalvik.vm.usejit"

    /** Результат подготовки: что реально вышло (для лога и для решения «рестартить/не рестартить»). */
    data class PreResult(
        val exitCode: Int,
        val output: String,
        val ipRuleWait: Int,
        val drained: Boolean,
        val runtime: String,
        val persist: String
    ) {
        val ok: Boolean get() = output.isNotEmpty()
    }

    /** Выполнить §2.2 (одна root-команда, ≤20 с ожидания). */
    fun runPreRestart(context: Context, usejitValue: String): PreResult {
        BgWorker.assertNotMain("JitNet.runPreRestart")
        val (code, out) = runCatching {
            AdbClient.executeScript("jit_net_pre", preRestartScript(usejitValue), 45_000)
        }.getOrDefault(Pair(1, ""))
        fun tag(t: String): String =
            out.lineSequence().firstOrNull { it.trimStart().startsWith("$t=") }?.substringAfter('=')?.trim().orEmpty()
        val waitLine = out.lineSequence().firstOrNull { it.contains("ip_rule_wait=") }?.trim().orEmpty()
        val wait = Regex("ip_rule_wait=([0-9]+)").find(waitLine)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val drained = waitLine.contains("drained=yes")
        val res = PreResult(code, out, wait, drained, tag("RT"), tag("PS"))
        AppLog.i(TAG, "JIT_NET_PRE ip_rule_wait=$wait drained=${if (drained) "yes" else "no"} " +
            "rc=$code runtime=${res.runtime.ifEmpty { "empty" }} persist=${res.persist.ifEmpty { "empty" }}")
        return res
    }

    /**
     * §2.3(а) — санация: удалить зависшие правила ТОЛЬКО `oif wlan0` (чужие правила не трогаем).
     */
    internal fun sanitizeScript(): String {
        val D = "$"
        return """
            #!/system/bin/sh
            # iter33p §2.3а — снимаем только `oif wlan0`, остальные правила не трогаем
            removed=0
            for p in ${D}(ip rule show 2>/dev/null | grep 'oif wlan0' | cut -d: -f1); do
              ip rule del priority ${D}p 2>/dev/null && removed=${D}((removed+1))
            done
            left=${D}(ip rule show 2>/dev/null | grep -c 'oif wlan0')
            echo "JIT_NET_SANITIZE removed=${D}removed left=${D}left scope=oif_wlan0"
        """.trimIndent()
    }

    /**
     * §2.3 — возврат сети после подъёма: санация → Wi-Fi → AP. Только из СОХРАНЁННОГО состояния.
     * Возвращает true, если всё, что было включено, вернулось.
     */
    fun restoreAfterRestart(context: Context, saved: State): Boolean {
        val app = context.applicationContext
        // (а) санация правил policy-routing
        runCatching {
            val (_, out) = AdbClient.executeScript("jit_net_sanitize", sanitizeScript(), 20_000)
            out.lineSequence().firstOrNull { it.contains("JIT_NET_SANITIZE") }?.let { AppLog.i(TAG, it.trim()) }
        }.onFailure { AppLog.w(TAG, "JIT_NET_SANITIZE failed: ${it.javaClass.simpleName}") }
        // (б) Wi-Fi — только если он был включён до рестарта
        var wifiOk = true
        if (saved.wifi) {
            wifiOk = restoreWifi(app)
        } else {
            AppLog.i(TAG, "JIT_NET_RESTORE wifi=0 result=skipped reason=was_off")
        }
        // (в) AP — только если был поднят
        var apOk = true
        if (saved.ap) {
            apOk = restoreAp(app)
        } else {
            AppLog.i(TAG, "JIT_NET_RESTORE ap=0 result=skipped reason=was_off")
        }
        clearSaved(app)
        return wifiOk && apOk
    }

    /** §2.3(б): `setWifiEnabled(true)` до 3 попыток с паузой ~3 с (образец «WiFi auto-enable: tentativa N»). */
    private fun restoreWifi(context: Context): Boolean {
        val wm = runCatching {
            context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        }.getOrNull() ?: run {
            AppLog.i(TAG, "JIT_NET_RESTORE wifi=1 attempt=0 result=failed reason=no_wifi_service")
            return false
        }
        for (attempt in 1..WIFI_RESTORE_ATTEMPTS) {
            val ok = runCatching {
                if (wm.isWifiEnabled) true else wm.setWifiEnabled(true)
            }.getOrDefault(false)
            AppLog.i(TAG, "JIT_NET_RESTORE wifi=1 attempt=$attempt/$WIFI_RESTORE_ATTEMPTS " +
                "result=${if (ok) "ok" else "failed"}")
            if (ok && runCatching { wm.isWifiEnabled }.getOrDefault(false)) return true
            if (attempt < WIFI_RESTORE_ATTEMPTS) runCatching { Thread.sleep(WIFI_RESTORE_PAUSE_MS) }
        }
        AppLog.i(TAG, "JIT_NET_RESTORE wifi=1 attempts=$WIFI_RESTORE_ATTEMPTS result=failed")
        return false
    }

    /**
     * §2.3(в): вернуть точку доступа механизмом референса (`startSoftAp` через рефлексию,
     * как `U("pós-JIT")`). Недоступно — честный лог, без «лечения» системы.
     */
    private fun restoreAp(context: Context): Boolean {
        if (apIsUp()) {
            AppLog.i(TAG, "JIT_NET_RESTORE ap=1 result=already_up")
            return true
        }
        val ok = runCatching {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val m = wm.javaClass.getMethod("startSoftAp", android.net.wifi.WifiConfiguration::class.java)
            m.invoke(wm, null) as? Boolean ?: false
        }.getOrDefault(false)
        AppLog.i(TAG, "JIT_NET_RESTORE ap=1 attempt=1 result=${if (ok) "ok" else "failed"} " +
            "reason=${if (ok) "softap_started" else "no_startSoftAp"}")
        return ok
    }
}
