package net.internectual.gpsrelay.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.internectual.gpsrelay.GpsRelayApp
import net.internectual.gpsrelay.client.NavLauncher
import net.internectual.gpsrelay.client.ServerAddress
import net.internectual.gpsrelay.protocol.Fix
import net.internectual.gpsrelay.server.NavDispatcher
import net.internectual.gpsrelay.settings.Mode
import net.internectual.gpsrelay.state.ConnState
import net.internectual.gpsrelay.state.RelayState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(onStart: () -> Unit, onStop: () -> Unit, onRequestPermissions: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as GpsRelayApp
    val prefs by app.prefs.values.collectAsStateWithLifecycle()
    val serverRunning by RelayState.serverRunning.collectAsStateWithLifecycle()
    val clientRunning by RelayState.clientRunning.collectAsStateWithLifecycle()
    // The switch only picks which side to look at; whichever side is running keeps running until Start on the other.
    val thisRunning = if (prefs.mode == Mode.SERVER) serverRunning else clientRunning
    val otherRunning = if (prefs.mode == Mode.SERVER) clientRunning else serverRunning
    var confirmHandover by remember { mutableStateOf(false) }

    // Re-check permissions and system settings whenever the user comes back from Settings.
    var resumeTick by remember { mutableLongStateOf(0L) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Mode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = prefs.mode == m,
                    onClick = { if (prefs.mode != m) app.prefs.update { copy(mode = m) } },
                    shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size),
                ) { Text(if (m == Mode.SERVER) "Server" else "Client") }
            }
        }

        val thisName = if (prefs.mode == Mode.SERVER) "server" else "client"
        val otherName = if (prefs.mode == Mode.SERVER) "client" else "server"
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (thisRunning) {
                Button(onClick = onStop) { Text("Stop $thisName") }
            } else {
                Button(onClick = { if (otherRunning) confirmHandover = true else onStart() }) { Text("Start $thisName") }
            }
        }
        if (otherRunning) {
            Text(
                "The $otherName is running. Starting the $thisName will stop it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (prefs.mode == Mode.SERVER) ServerStatus(ctx, prefs, resumeTick) else ClientStatus(ctx, prefs, resumeTick, onRequestPermissions)
    }

    if (confirmHandover) {
        val thisName = if (prefs.mode == Mode.SERVER) "server" else "client"
        val otherName = if (prefs.mode == Mode.SERVER) "client" else "server"
        AlertDialog(
            onDismissRequest = { confirmHandover = false },
            title = { Text("Stop the $otherName?") },
            text = { Text("This device is running the $otherName. Starting the $thisName will stop it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmHandover = false
                    onStart()
                }) { Text("Start $thisName") }
            },
            dismissButton = { TextButton(onClick = { confirmHandover = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ServerStatus(ctx: Context, prefs: net.internectual.gpsrelay.settings.Prefs.Values, resumeTick: Long) {
    val port = prefs.port
    val running by RelayState.serverRunning.collectAsStateWithLifecycle()
    val clients by RelayState.clients.collectAsStateWithLifecycle()
    val nmeaClients by RelayState.nmeaClients.collectAsStateWithLifecycle()
    val error by RelayState.serverError.collectAsStateWithLifecycle()
    val fix by RelayState.fix.collectAsStateWithLifecycle()
    val ips = remember(resumeTick, running) { Setup.localIpv4() }

    InfoCard("Server") {
        Text(if (running) "Listening on port $port" else "Stopped")
        if (ips.isNotEmpty()) Text("This device: " + ips.joinToString(", "), style = MaterialTheme.typography.bodySmall)
        Text(
            when (clients.size) {
                0 -> "No clients connected"
                else -> "Connected: " + clients.joinToString(", ")
            }
        )
        error?.let { ErrorText(it) }
    }
    if (prefs.nmeaTcpEnabled || prefs.nmeaUdpEnabled) {
        InfoCard("NMEA output") {
            if (prefs.nmeaTcpEnabled) {
                Text(
                    (if (running) "TCP on port ${prefs.nmeaTcpPort}" else "TCP port ${prefs.nmeaTcpPort} (server stopped)") +
                        if (nmeaClients.isEmpty()) ", no readers" else ", readers: " + nmeaClients.joinToString(", ")
                )
            }
            if (prefs.nmeaUdpEnabled) {
                Text("UDP to " + prefs.nmeaUdpTargets.ifBlank { "(no target set)" })
            }
        }
    }
    FixCard(fix, "This device's GPS")

    var test by rememberSaveable { mutableStateOf("") }
    InfoCard("Send a destination") {
        Text(
            "Anything you'd share with a maps app: an address, \"lat, lon\", or a Google Maps / Waze link.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = test,
            onValueChange = { test = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Destination") },
            singleLine = true,
        )
        Button(enabled = test.isNotBlank(), onClick = { NavDispatcher.submit(ctx, test, "manual") }) { Text("Send to client") }
    }

    InfoCard("Catching navigation links") {
        Text(
            "When an app opens a geo: or navigation link, choose \"Send to client (GPS Relay)\" and tap Always. " +
                "You can also use Share → GPS Relay from Google Maps or any app.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Google Maps owns maps.google.com links. To route those here, turn off \"Open supported links\" for " +
                "Google Maps.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { Setup.open(ctx, Setup.openByDefaultIntent(NavLauncher.GOOGLE_MAPS)) }) {
            Text("Google Maps link settings")
        }
    }
}

@Composable
private fun ClientStatus(
    ctx: Context,
    prefs: net.internectual.gpsrelay.settings.Prefs.Values,
    resumeTick: Long,
    onRequestPermissions: () -> Unit,
) {
    val conn by RelayState.conn.collectAsStateWithLifecycle()
    val target by RelayState.connTarget.collectAsStateWithLifecycle()
    val error by RelayState.connError.collectAsStateWithLifecycle()
    val mockError by RelayState.mockError.collectAsStateWithLifecycle()
    val fix by RelayState.fix.collectAsStateWithLifecycle()
    val expected = remember(resumeTick, prefs.serverOverride, prefs.port) {
        ServerAddress.resolve(ctx, prefs.serverOverride, prefs.port)
    }

    InfoCard("Connection") {
        Text(
            when (conn) {
                ConnState.STOPPED -> "Stopped"
                ConnState.SEARCHING -> "Waiting for Wi-Fi…"
                ConnState.CONNECTING -> "Connecting to $target…"
                ConnState.CONNECTED -> "Connected to $target"
                ConnState.ERROR -> "Reconnecting to $target…"
            }
        )
        Text(
            "Server: " + when {
                expected == null -> "no Wi-Fi gateway found. Join the server's hotspot or set an address in Settings"
                expected.fromGateway -> "$expected (Wi-Fi gateway)"
                else -> "$expected (manual)"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        error?.let { ErrorText(it) }
        mockError?.let { ErrorText(it) }
        Text(
            "Navigation app: " + if (prefs.navAppPackage.isEmpty()) "system default" else NavLauncher.appLabel(ctx, prefs.navAppPackage),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    FixCard(fix, "Relayed location")

    InfoCard("Setup checklist") {
        key(resumeTick) {
            CheckItem("Location permission", Setup.hasLocation(ctx), "Allow", onRequestPermissions)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                CheckItem("Notifications", Setup.hasNotifications(ctx), "Open") { Setup.open(ctx, Setup.appDetailsIntent(ctx.packageName)) }
            }
            CheckItem(
                "Mock location app = GPS Relay (Developer options)",
                Setup.isMockApp(ctx), "Open",
            ) { Setup.open(ctx, Setup.devOptionsIntent()) }
            if (Setup.overlayNeeded()) {
                CheckItem("Display over other apps (opens nav app automatically)", Setup.canOverlay(ctx), "Allow") {
                    Setup.open(ctx, Setup.overlayIntent(ctx))
                }
            }
            if (Setup.fullScreenNeeded()) {
                CheckItem("Full-screen navigation prompts", Setup.canFullScreen(ctx), "Allow") {
                    Setup.open(ctx, Setup.fullScreenIntent(ctx))
                }
            }
            if (Setup.batteryNeeded()) {
                CheckItem("Not battery-optimized", Setup.ignoresBattery(ctx), "Open") {
                    Setup.open(ctx, Setup.batteryIntent())
                }
            }
        }
        Text(
            "Mock location: enable Developer options (tap Build number 7 times in Settings → About), then choose " +
                "\"Select mock location app\" → GPS Relay.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CheckItem(label: String, ok: Boolean, action: String?, onAction: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = if (ok) "Done" else "Needed",
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!ok && action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun FixCard(fix: Fix?, title: String) {
    val receivedAt by RelayState.fixReceivedAt.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    InfoCard(title) {
        if (fix == null) {
            Text("No fix yet")
        } else {
            Text("%.6f, %.6f".format(Locale.US, fix.lat, fix.lon))
            Text(
                listOfNotNull(
                    fix.acc?.let { "±%.0f m".format(it) },
                    fix.speed?.let { "%.0f km/h".format(it * 3.6f) },
                    fix.bearing?.let { "%.0f°".format(it) },
                    fix.sats?.let { "$it sats" },
                    "updated ${((now - receivedAt) / 1000).coerceAtLeast(0)} s ago",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun ErrorText(text: String) = Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
