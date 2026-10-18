package net.internectual.gpsrelay.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.internectual.gpsrelay.GpsRelayApp
import net.internectual.gpsrelay.settings.Mode
import net.internectual.gpsrelay.state.RelayState

private data class Tab(val title: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Status", Icons.Default.Home),
    Tab("Map", Icons.Default.Place),
    @Suppress("DEPRECATION") Tab("Log", Icons.Default.List),
    Tab("Settings", Icons.Default.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(autostart: MutableState<Boolean>) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as GpsRelayApp
    val prefs by app.prefs.values.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showDisclosure by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingStart && Setup.hasLocation(ctx)) Setup.startRelay(ctx, app.prefs.current.mode)
        pendingStart = false
    }

    fun requestStart() {
        pendingStart = true
        when {
            !app.prefs.current.disclosureAccepted -> showDisclosure = true
            Setup.hasLocation(ctx) && Setup.hasNotifications(ctx) -> {
                pendingStart = false
                Setup.startRelay(ctx, app.prefs.current.mode)
            }
            else -> permLauncher.launch(Setup.runtimePermissions)
        }
    }

    fun requestPermissions() {
        pendingStart = false
        if (!app.prefs.current.disclosureAccepted) showDisclosure = true
        else permLauncher.launch(Setup.runtimePermissions)
    }

    LaunchedEffect(autostart.value) {
        if (autostart.value) {
            autostart.value = false
            requestStart()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text("GPS Relay · " + if (prefs.mode == Mode.SERVER) "Server" else "Client")
            })
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.title) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> StatusScreen(
                    onStart = ::requestStart,
                    onStop = { Setup.stopRelay(ctx) },
                    onRequestPermissions = ::requestPermissions,
                )
                1 -> MapScreen()
                2 -> LogScreen(onShowOnMap = { dest ->
                    RelayState.mapFocus.value = dest
                    tab = 1
                })
                3 -> SettingsScreen()
            }
        }
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = {
                showDisclosure = false
                pendingStart = false
            },
            title = { Text("Location use") },
            text = {
                Text(
                    "GPS Relay uses your location while the relay is running, including when the screen is off " +
                        "(a notification is shown the whole time).\n\n" +
                        "• Server mode reads this device's GPS and sends it to devices connected to your hotspot.\n" +
                        "• Client mode receives that location from the server and provides it to apps on this device.\n\n" +
                        "Location and destinations only travel over your local network. Nothing is sent to us or to " +
                        "third parties."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDisclosure = false
                    app.prefs.update { copy(disclosureAccepted = true) }
                    permLauncher.launch(Setup.runtimePermissions)
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDisclosure = false
                    pendingStart = false
                }) { Text("Not now") }
            },
        )
    }
}
