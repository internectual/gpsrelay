package net.internectual.gpsrelay.automation

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.internectual.gpsrelay.settings.Mode
import net.internectual.gpsrelay.settings.Prefs
import net.internectual.gpsrelay.state.RelayState
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Control by automation apps such as Tasker: actions sent to [ControlActivity], and a [EVENT_STATE] broadcast
 * whenever the relay's state changes. Both are off until the user turns automation on in Settings, and every
 * action must carry the key shown there, so other apps can't start the relay or redirect NMEA output.
 */
object Automation {
    private const val PREFIX = "net.internectual.gpsrelay.action."
    const val START_SERVER = PREFIX + "START_SERVER"
    const val START_CLIENT = PREFIX + "START_CLIENT"
    const val STOP = PREFIX + "STOP"
    const val STOP_SERVER = PREFIX + "STOP_SERVER"
    const val STOP_CLIENT = PREFIX + "STOP_CLIENT"
    const val RECONNECT = PREFIX + "RECONNECT"
    const val OPEN = PREFIX + "OPEN"
    const val CLOSE = PREFIX + "CLOSE"
    const val QUIT = PREFIX + "QUIT"
    const val SET = PREFIX + "SET"
    const val REQUEST_STATE = PREFIX + "REQUEST_STATE"

    const val EVENT_STATE = "net.internectual.gpsrelay.event.STATE"

    const val EXTRA_KEY = "key"
    const val EXTRA_TAB = "tab"

    private const val KEY_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    fun newKey(): String {
        val rnd = SecureRandom()
        return String(CharArray(24) { KEY_CHARS[rnd.nextInt(KEY_CHARS.length)] })
    }

    /** Null when [given] may run actions, otherwise why not. */
    fun reject(prefs: Prefs.Values, given: String?): String? = when {
        !prefs.automationEnabled -> "automation is off (Settings → General)"
        given.isNullOrEmpty() -> "missing key extra"
        !MessageDigest.isEqual(given.toByteArray(), prefs.automationKey.toByteArray()) -> "wrong key"
        else -> null
    }

    private data class State(
        val mode: Mode,
        val serverRunning: Boolean,
        val clientRunning: Boolean,
        val clientState: String,
        val clients: Int,
        val nmeaClients: Int,
    )

    /** Broadcasts [EVENT_STATE] on every change for as long as [scope] lives. */
    fun broadcastStateChanges(context: Context, prefs: Prefs, scope: CoroutineScope) {
        scope.launch {
            combine(
                prefs.values, RelayState.serverRunning, RelayState.clientRunning, RelayState.conn,
                RelayState.clients, RelayState.nmeaClients,
            ) { a ->
                @Suppress("UNCHECKED_CAST")
                State(
                    mode = (a[0] as Prefs.Values).mode,
                    serverRunning = a[1] as Boolean,
                    clientRunning = a[2] as Boolean,
                    clientState = a[3].toString().lowercase(),
                    clients = (a[4] as List<String>).size,
                    nmeaClients = (a[5] as List<String>).size,
                )
            }.distinctUntilChanged().collect { s -> if (prefs.current.automationEnabled) send(context, s) }
        }
    }

    fun sendState(context: Context, prefs: Prefs) = send(
        context,
        State(
            mode = prefs.current.mode,
            serverRunning = RelayState.serverRunning.value,
            clientRunning = RelayState.clientRunning.value,
            clientState = RelayState.conn.value.toString().lowercase(),
            clients = RelayState.clients.value.size,
            nmeaClients = RelayState.nmeaClients.value.size,
        ),
    )

    // Running state and counts only: never location, which other apps would otherwise get without permission.
    private fun send(context: Context, s: State) {
        context.sendBroadcast(
            Intent(EVENT_STATE)
                .putExtra("mode", s.mode.name.lowercase())
                .putExtra("server_running", s.serverRunning)
                .putExtra("client_running", s.clientRunning)
                .putExtra("client_state", s.clientState)
                .putExtra("clients", s.clients)
                .putExtra("nmea_clients", s.nmeaClients),
        )
    }
}
