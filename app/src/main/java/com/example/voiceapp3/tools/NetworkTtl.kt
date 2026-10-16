package com.example.voiceapp3.tools

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log

/**
 * Stage CXT · срез №6: Сеть — Фиксация TTL 65 (IPv4/IPv6 iptables mangle).
 *
 * Точный перенос механизма CentralEXAuto (w0.z8, w0.s8):
 * Выполнение правил iptables/ip6tables через локальный ADB сокет 5555.
 */
object NetworkTtl {
    private const val TAG = "NetworkTtl"
    private const val PREFS_NAME = "voiceapp3"

    const val PREF_TTL_65_ACTIVE = "ttl_65_ativo"

    private const val CMD_ENABLE =
        "for i in 1 2 3 4 5; do iptables  -t mangle -D POSTROUTING -j TTL --ttl-set 65 2>/dev/null || break; done\n" +
        "for i in 1 2 3 4 5; do ip6tables -t mangle -D POSTROUTING -j HL  --hl-set  65 2>/dev/null || break; done\n" +
        "iptables  -t mangle -A POSTROUTING -j TTL --ttl-set 65 2>&1\n" +
        "ip6tables -t mangle -A POSTROUTING -j HL  --hl-set  65 2>&1\n" +
        "echo TTL_V4=$(iptables  -t mangle -S POSTROUTING 2>/dev/null | grep -c 'ttl-set 65')\n" +
        "echo TTL_V6=$(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'hl-set 65')"

    private const val CMD_DISABLE =
        "for i in 1 2 3 4 5; do iptables  -t mangle -D POSTROUTING -j TTL --ttl-set 65 2>/dev/null || break; done\n" +
        "for i in 1 2 3 4 5; do ip6tables -t mangle -D POSTROUTING -j HL  --hl-set  65 2>/dev/null || break; done\n" +
        "echo TTL_V4=$(iptables  -t mangle -S POSTROUTING 2>/dev/null | grep -c 'ttl-set 65')\n" +
        "echo TTL_V6=$(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'hl-set 65')"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isTtl65Active(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_TTL_65_ACTIVE, false)
    }

    /**
     * Применение или отключение TTL 65 с формированием ToolsResult.
     */
    fun setTtl65Active(context: Context, enabled: Boolean): ToolsResult {
        val t0 = SystemClock.uptimeMillis()
        getPrefs(context).edit().putBoolean(PREF_TTL_65_ACTIVE, enabled).commit()

        val script = if (enabled) CMD_ENABLE else CMD_DISABLE
        val (exitCode, output) = AdbClient.execute(script, 15000)

        val v4Active = output.contains("TTL_V4=") && !output.contains("TTL_V4=0")
        val status = if (enabled) {
            if (v4Active || exitCode == 0) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL
        } else {
            if (!v4Active || exitCode == 0) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL
        }

        val step = ToolsStep(
            step = "settings.ttl65",
            command = if (enabled) "iptables mangle --ttl-set 65" else "iptables mangle -D (clear)",
            startedAtMs = t0,
            finishedAtMs = SystemClock.uptimeMillis(),
            exitCode = exitCode,
            stdout = output,
            stderr = "",
            expected = if (enabled) "TTL_V4=1" else "TTL_V4=0",
            actual = output.lines().firstOrNull { it.startsWith("TTL_V4=") } ?: output,
            status = status
        )

        return ToolsResult(
            action = "settings.ttl65",
            title = "Фиксация TTL 65",
            steps = listOf(step)
        )
    }

    /**
     * Восстановление при загрузке системы (BootReceiver / Service).
     */
    fun bootRestore(context: Context) {
        if (isTtl65Active(context)) {
            Log.i(TAG, "TTL 65 boot restore: applying rules...")
            AdbClient.execute(CMD_ENABLE, 15000)
        }
    }
}
