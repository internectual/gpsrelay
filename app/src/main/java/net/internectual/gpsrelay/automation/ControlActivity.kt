package net.internectual.gpsrelay.automation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import net.internectual.gpsrelay.GpsRelayApp
import net.internectual.gpsrelay.MainActivity
import net.internectual.gpsrelay.client.ClientService
import net.internectual.gpsrelay.client.ServerAddress
import net.internectual.gpsrelay.nmea.UdpTarget
import net.internectual.gpsrelay.server.ServerService
import net.internectual.gpsrelay.settings.Mode
import net.internectual.gpsrelay.settings.Prefs
import net.internectual.gpsrelay.state.RelayState
import net.internectual.gpsrelay.ui.Setup

/**
 * Invisible activity that runs [Automation] actions and finishes. It's an activity rather than a receiver because
 * a location foreground service may only be started while the app has a visible activity.
 */
class ControlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as GpsRelayApp
        val action = intent?.action
        val rejected = Automation.reject(app.prefs.current, intent?.extras?.get(Automation.EXTRA_KEY)?.toString())
        if (rejected != null) {
            toast("GPS Relay: $rejected")
        } else {
            when (action) {
                Automation.START_SERVER -> start(app, Mode.SERVER)
                Automation.START_CLIENT -> start(app, Mode.CLIENT)
                Automation.STOP -> Setup.stopRelay(this)
                Automation.STOP_SERVER -> ServerService.stop(this)
                Automation.STOP_CLIENT -> ClientService.stop(this)
                Automation.RECONNECT -> ClientService.reconnect()
                Automation.OPEN -> open(intent.getStringExtra(Automation.EXTRA_TAB))
                Automation.CLOSE -> RelayState.closeUi.tryEmit(Unit)
                Automation.QUIT -> {
                    Setup.stopRelay(this)
                    RelayState.closeUi.tryEmit(Unit)
                }
                Automation.SET -> set(app)
                Automation.REQUEST_STATE -> Automation.sendState(this, app.prefs)
                else -> toast("GPS Relay: unknown action $action")
            }
        }
        finish()
    }

    private fun start(app: GpsRelayApp, mode: Mode) {
        app.prefs.update { copy(mode = mode) }
        if (app.prefs.current.disclosureAccepted && Setup.hasLocation(this) && Setup.hasNotifications(this)) {
            Setup.startRelay(this, mode)
        } else {
            // Needs the location disclosure or a permission prompt first; the app starts the relay once granted.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_AUTOSTART, true),
            )
        }
    }

    private fun open(tab: String?) {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tab != null) {
            if (tab.lowercase() !in MainActivity.TABS) {
                toast("GPS Relay: unknown tab $tab (use ${MainActivity.TABS.joinToString()})")
            } else {
                i.putExtra(MainActivity.EXTRA_TAB, tab.lowercase())
            }
        }
        startActivity(i)
    }

    /** Applies setting extras all-or-nothing, so a typo doesn't leave a half-changed setup. */
    private fun set(app: GpsRelayApp) {
        val extras = intent.extras ?: return
        val errors = mutableListOf<String>()
        val edits = mutableListOf<Prefs.Values.() -> Prefs.Values>()

        fun str(name: String) = extras.get(name)?.toString()
        fun bool(name: String, apply: Prefs.Values.(Boolean) -> Prefs.Values) {
            val v = str(name) ?: return
            when (v.lowercase()) {
                "true", "1", "on" -> edits += { apply(true) }
                "false", "0", "off" -> edits += { apply(false) }
                else -> errors += "$name=$v"
            }
        }
        fun port(name: String, apply: Prefs.Values.(Int) -> Prefs.Values) {
            val v = str(name) ?: return
            val p = v.toIntOrNull()?.takeIf { it in 1024..65535 }
            if (p == null) errors += "$name=$v" else edits += { apply(p) }
        }

        port("port") { copy(port = it) }
        str("pin")?.let { v -> if (v.length > 16) errors += "pin too long" else edits += { copy(pin = v) } }
        str("server_address")?.let { v ->
            if (v.isBlank() || ServerAddress.parseOverride(v, app.prefs.current.port) != null) edits += { copy(serverOverride = v) }
            else errors += "server_address=$v"
        }
        str("nav_app")?.let { v -> edits += { copy(navAppPackage = v) } }
        bool("also_open_locally") { copy(alsoOpenLocally = it) }
        bool("start_on_boot") { copy(startOnBoot = it) }
        bool("nmea_tcp") { copy(nmeaTcpEnabled = it) }
        port("nmea_tcp_port") { copy(nmeaTcpPort = it) }
        bool("nmea_udp") { copy(nmeaUdpEnabled = it) }
        str("nmea_udp_targets")?.let { v ->
            if (UdpTarget.parseList(v) != null) edits += { copy(nmeaUdpTargets = v) } else errors += "nmea_udp_targets=$v"
        }

        when {
            errors.isNotEmpty() -> toast("GPS Relay: bad setting ${errors.joinToString()}; nothing changed")
            edits.isEmpty() -> toast("GPS Relay: SET had no setting extras")
            else -> app.prefs.update { edits.fold(this) { v, e -> v.e() } }
        }
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
}
