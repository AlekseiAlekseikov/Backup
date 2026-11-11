package com.example.voiceapp3.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/**
 * Android-часть диагностики сети ([InternetDiag.NetInfo]): только чтение.
 *
 * Вынесена отдельно, чтобы сама диагностика и её тесты не зависели от Android API: сборщик
 * принимает интерфейс, а этот класс подставляется в бою
 * (`VoiceAssistantService` → `JitNet.requestDiagOnce(..., NetInfoAndroid(app))`).
 *
 * Чего здесь НЕТ: SSID, BSSID, MAC, идентификаторов телефона, чтения сохранённых сетей. `null`
 * из любого метода означает «недоступно», а не «нет».
 */
class NetInfoAndroid(private val app: Context) : InternetDiag.NetInfo {

    private fun cm(): ConnectivityManager? = runCatching {
        app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }.getOrNull()

    private fun caps(): NetworkCapabilities? = runCatching {
        val c = cm() ?: return@runCatching null
        val n = c.activeNetwork ?: return@runCatching null
        c.getNetworkCapabilities(n)
    }.getOrNull()

    override fun wifiEnabled(): Boolean? = runCatching {
        (app.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    }.getOrNull()

    override fun activeTransports(): Set<String>? {
        val c = caps() ?: return null
        val out = LinkedHashSet<String>()
        runCatching {
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) out += "wifi"
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) out += "cellular"
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) out += "ethernet"
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) out += "vpn"
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) out += "bluetooth"
        }
        return out
    }

    override fun validated(): Boolean? = runCatching {
        caps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrNull()

    override fun captivePortal(): Boolean? = runCatching {
        caps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
    }.getOrNull()

    override fun dnsServers(): List<String>? = runCatching {
        val c = cm() ?: return@runCatching null
        val n = c.activeNetwork ?: return@runCatching null
        val lp = c.getLinkProperties(n) ?: return@runCatching null
        lp.dnsServers.mapNotNull { it?.hostAddress }.filter { it.isNotBlank() }
    }.getOrNull()
}
