package net.internectual.gpsrelay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.internectual.gpsrelay.BuildConfig
import net.internectual.gpsrelay.GpsRelayApp
import net.internectual.gpsrelay.automation.Automation
import net.internectual.gpsrelay.automation.ControlActivity
import net.internectual.gpsrelay.client.ClientService
import net.internectual.gpsrelay.client.NavLauncher
import net.internectual.gpsrelay.client.ServerAddress
import net.internectual.gpsrelay.nmea.DEFAULT_NMEA_PORT
import net.internectual.gpsrelay.nmea.UdpTarget
import net.internectual.gpsrelay.protocol.DEFAULT_PORT
import net.internectual.gpsrelay.settings.Mode
import net.internectual.gpsrelay.state.RelayState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as GpsRelayApp
    val prefs by app.prefs.values.collectAsStateWithLifecycle()
    val navApps = remember { NavLauncher.installedNavApps(ctx) }
    val clipboard = LocalClipboardManager.current

    var portText by remember { mutableStateOf(prefs.port.toString()) }
    var overrideText by remember { mutableStateOf(prefs.serverOverride) }
    val overrideValid = overrideText.isBlank() || ServerAddress.parseOverride(overrideText, prefs.port) != null
    var nmeaPortText by remember { mutableStateOf(prefs.nmeaTcpPort.toString()) }
    var udpTargetsText by remember { mutableStateOf(prefs.nmeaUdpTargets) }
    val udpTargetsValid = UdpTarget.parseList(udpTargetsText) != null
    // Which side's settings are shown; starts on the side Status is showing but doesn't change it.
    var section by rememberSaveable { mutableStateOf(prefs.mode) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        InfoCard("Connection") {
            OutlinedTextField(
                value = portText,
                onValueChange = { t ->
                    portText = t.filter(Char::isDigit).take(5)
                    portText.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { p -> app.prefs.update { copy(port = p) } }
                },
                label = { Text("Port (default $DEFAULT_PORT)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = prefs.pin,
                onValueChange = { t -> app.prefs.update { copy(pin = t.take(16)) } },
                label = { Text("Pairing PIN (optional, must match on both)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Port and PIN changes apply the next time the relay starts.", style = MaterialTheme.typography.bodySmall)
        }

        InfoCard("General") {
            ToggleRow("Start when the device boots", prefs.startOnBoot) {
                app.prefs.update { copy(startOnBoot = it) }
            }
            OutlinedButton(onClick = { RelayState.clearTrail() }) { Text("Clear map trail") }
        }

        InfoCard("Automation") {
            ToggleRow("Allow automation apps (e.g. Tasker)", prefs.automationEnabled) { on ->
                app.prefs.update { copy(automationEnabled = on, automationKey = automationKey.ifBlank { Automation.newKey() }) }
            }
            if (prefs.automationEnabled) {
                Text(
                    "Send intents to activity ${ControlActivity::class.java.name} in package ${ctx.packageName}, " +
                        "with extra key:${prefs.automationKey}. Any app with this key can start the relay and change " +
                        "where NMEA output goes, so keep it private. Actions are listed in the README.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(prefs.automationKey)) }) { Text("Copy key") }
                    OutlinedButton(onClick = { app.prefs.update { copy(automationKey = Automation.newKey()) } }) { Text("New key") }
                }
            }
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Mode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = section == m,
                    onClick = { section = m },
                    shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size),
                ) { Text(if (m == Mode.SERVER) "Server" else "Client") }
            }
        }

        if (section == Mode.CLIENT) {
            InfoCard("Server address") {
                OutlinedTextField(
                    value = overrideText,
                    onValueChange = { t ->
                        overrideText = t
                        if (t.isBlank() || ServerAddress.parseOverride(t, prefs.port) != null) {
                            app.prefs.update { copy(serverOverride = t) }
                        }
                    },
                    label = { Text("Server address (blank = Wi-Fi gateway)") },
                    placeholder = { Text("192.168.43.1:$DEFAULT_PORT") },
                    isError = !overrideValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { ClientService.reconnect() }) { Text("Reconnect now") }
            }

            InfoCard("Navigation app") {
                NavAppRow("System default (ask)", prefs.navAppPackage.isEmpty()) {
                    app.prefs.update { copy(navAppPackage = "") }
                }
                navApps.forEach { a ->
                    NavAppRow(a.label, prefs.navAppPackage == a.packageName) {
                        app.prefs.update { copy(navAppPackage = a.packageName) }
                    }
                }
            }
        } else {
            InfoCard("Server") {
                ToggleRow("Also open destinations on this device", prefs.alsoOpenLocally) {
                    app.prefs.update { copy(alsoOpenLocally = it) }
                }
            }

            InfoCard("NMEA output") {
                Text(
                    "Streams standard NMEA 0183 sentences to GPS software on a computer, for example over USB tethering. " +
                        "There is no PIN: anything that can reach these ports can read this device's location.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ToggleRow("TCP server", prefs.nmeaTcpEnabled) { app.prefs.update { copy(nmeaTcpEnabled = it) } }
                if (prefs.nmeaTcpEnabled) {
                    OutlinedTextField(
                        value = nmeaPortText,
                        onValueChange = { t ->
                            nmeaPortText = t.filter(Char::isDigit).take(5)
                            nmeaPortText.toIntOrNull()?.takeIf { it in 1024..65535 }
                                ?.let { p -> app.prefs.update { copy(nmeaTcpPort = p) } }
                        },
                        label = { Text("TCP port (default $DEFAULT_NMEA_PORT)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ToggleRow("UDP datagrams", prefs.nmeaUdpEnabled) { app.prefs.update { copy(nmeaUdpEnabled = it) } }
                if (prefs.nmeaUdpEnabled) {
                    OutlinedTextField(
                        value = udpTargetsText,
                        onValueChange = { t ->
                            udpTargetsText = t
                            if (UdpTarget.parseList(t) != null) app.prefs.update { copy(nmeaUdpTargets = t) }
                        },
                        label = { Text("Send to (host[:port], comma-separated)") },
                        placeholder = { Text("192.168.42.100:$DEFAULT_NMEA_PORT") },
                        isError = !udpTargetsValid || udpTargetsText.isBlank(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        InfoCard("About") {
            Text("GPS Relay ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Location and destinations only travel between your own devices over the local network. " +
                    "Map tiles © OpenStreetMap contributors.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun NavAppRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
