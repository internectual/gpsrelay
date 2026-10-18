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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.internectual.gpsrelay.BuildConfig
import net.internectual.gpsrelay.GpsRelayApp
import net.internectual.gpsrelay.client.ClientService
import net.internectual.gpsrelay.client.NavLauncher
import net.internectual.gpsrelay.client.ServerAddress
import net.internectual.gpsrelay.protocol.DEFAULT_PORT
import net.internectual.gpsrelay.settings.Mode
import net.internectual.gpsrelay.state.RelayState

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as GpsRelayApp
    val prefs by app.prefs.values.collectAsStateWithLifecycle()
    val navApps = remember { NavLauncher.installedNavApps(ctx) }

    var portText by remember { mutableStateOf(prefs.port.toString()) }
    var overrideText by remember { mutableStateOf(prefs.serverOverride) }
    val overrideValid = overrideText.isBlank() || ServerAddress.parseOverride(overrideText, prefs.port) != null

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
            if (prefs.mode == Mode.CLIENT) {
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
            Text("Port and PIN changes apply the next time the relay starts.", style = MaterialTheme.typography.bodySmall)
        }

        if (prefs.mode == Mode.CLIENT) {
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
        }

        InfoCard("General") {
            ToggleRow("Start when the device boots", prefs.startOnBoot) {
                app.prefs.update { copy(startOnBoot = it) }
            }
            OutlinedButton(onClick = { RelayState.clearTrail() }) { Text("Clear map trail") }
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
