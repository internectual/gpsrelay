package net.internectual.gpsrelay.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.internectual.gpsrelay.nmea.DEFAULT_NMEA_PORT
import net.internectual.gpsrelay.protocol.DEFAULT_PORT

enum class Mode { SERVER, CLIENT }

/** App settings, mirrored into a StateFlow so Compose recomposes on change. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    data class Values(
        val mode: Mode,
        val port: Int,
        val serverOverride: String,
        val pin: String,
        val navAppPackage: String,
        val alsoOpenLocally: Boolean,
        val startOnBoot: Boolean,
        val disclosureAccepted: Boolean,
        /** Server: stream NMEA sentences to TCP clients on [nmeaTcpPort]. */
        val nmeaTcpEnabled: Boolean,
        val nmeaTcpPort: Int,
        /** Server: send NMEA sentences as UDP datagrams to [nmeaUdpTargets] (`host[:port]`, comma-separated). */
        val nmeaUdpEnabled: Boolean,
        val nmeaUdpTargets: String,
    )

    private val _values = MutableStateFlow(read())
    val values: StateFlow<Values> = _values
    val current: Values get() = _values.value

    private fun read() = Values(
        mode = runCatching { Mode.valueOf(sp.getString("mode", null) ?: "") }.getOrDefault(Mode.CLIENT),
        port = sp.getInt("port", DEFAULT_PORT),
        serverOverride = sp.getString("serverOverride", "") ?: "",
        pin = sp.getString("pin", "") ?: "",
        navAppPackage = sp.getString("navApp", "") ?: "",
        alsoOpenLocally = sp.getBoolean("alsoOpenLocally", false),
        startOnBoot = sp.getBoolean("startOnBoot", false),
        disclosureAccepted = sp.getBoolean("disclosureAccepted", false),
        nmeaTcpEnabled = sp.getBoolean("nmeaTcpEnabled", false),
        nmeaTcpPort = sp.getInt("nmeaTcpPort", DEFAULT_NMEA_PORT),
        nmeaUdpEnabled = sp.getBoolean("nmeaUdpEnabled", false),
        nmeaUdpTargets = sp.getString("nmeaUdpTargets", "") ?: "",
    )

    fun update(block: Values.() -> Values) {
        val v = _values.value.block()
        sp.edit()
            .putString("mode", v.mode.name)
            .putInt("port", v.port)
            .putString("serverOverride", v.serverOverride.trim())
            .putString("pin", v.pin)
            .putString("navApp", v.navAppPackage)
            .putBoolean("alsoOpenLocally", v.alsoOpenLocally)
            .putBoolean("startOnBoot", v.startOnBoot)
            .putBoolean("disclosureAccepted", v.disclosureAccepted)
            .putBoolean("nmeaTcpEnabled", v.nmeaTcpEnabled)
            .putInt("nmeaTcpPort", v.nmeaTcpPort)
            .putBoolean("nmeaUdpEnabled", v.nmeaUdpEnabled)
            .putString("nmeaUdpTargets", v.nmeaUdpTargets.trim())
            .apply()
        _values.value = v
    }
}
