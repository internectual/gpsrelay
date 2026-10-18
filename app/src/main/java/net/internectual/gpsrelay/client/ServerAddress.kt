package net.internectual.gpsrelay.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address

/**
 * Where the client connects. By default the Wi-Fi gateway — on a hotspot that is the
 * server device itself. A manual "host:port" override in settings takes precedence.
 */
object ServerAddress {

    data class Target(val host: String, val port: Int, val fromGateway: Boolean) {
        override fun toString() = "$host:$port"
    }

    /** Parses "host", "host:port" or "[v6]:port". Returns null if blank or invalid. */
    fun parseOverride(text: String, defaultPort: Int): Target? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("[")) {
            val host = t.substringAfter('[').substringBefore(']')
            val port = t.substringAfter("]:", "").toIntOrNull() ?: defaultPort
            return if (host.isNotEmpty() && port in 1..65535) Target(host, port, false) else null
        }
        val parts = t.split(':')
        return when (parts.size) {
            1 -> Target(parts[0], defaultPort, false)
            2 -> parts[1].toIntOrNull()?.takeIf { it in 1..65535 }?.let { Target(parts[0], it, false) }
            else -> null
        }?.takeIf { it.host.isNotEmpty() }
    }

    fun resolve(context: Context, override: String, port: Int): Target? =
        parseOverride(override, port) ?: wifiGateway(context)?.let { Target(it, port, true) }

    fun wifiGateway(context: Context): String? {
        if (Build.VERSION.SDK_INT >= 23) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val lp = cm.getLinkProperties(network) ?: continue
                lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                    ?.gateway?.hostAddress?.takeIf { it != "0.0.0.0" }
                    ?.let { return it }
            }
        }
        @Suppress("DEPRECATION")
        val gw = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).dhcpInfo?.gateway ?: 0
        if (gw == 0) return null
        // DhcpInfo stores the address little-endian.
        return "${gw and 0xff}.${gw shr 8 and 0xff}.${gw shr 16 and 0xff}.${gw shr 24 and 0xff}"
    }
}
