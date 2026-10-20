package net.internectual.gpsrelay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.internectual.gpsrelay.state.RelayState
import net.internectual.gpsrelay.ui.AppRoot
import net.internectual.gpsrelay.ui.GpsRelayTheme

class MainActivity : ComponentActivity() {
    /** Set when launched from the boot notification; the UI starts the relay once permissions allow. */
    private val autostart = mutableStateOf(false)
    /** Index into [TABS] that an automation OPEN asked for; the UI switches to it and clears it. */
    private val openTab = mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        lifecycleScope.launch { RelayState.closeUi.collect { finishAndRemoveTask() } }
        setContent {
            GpsRelayTheme {
                AppRoot(autostart = autostart, openTab = openTab)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) autostart.value = true
        intent?.getStringExtra(EXTRA_TAB)?.let { t -> TABS.indexOf(t).takeIf { it >= 0 }?.let { openTab.value = it } }
    }

    override fun onResume() {
        super.onResume()
        RelayState.uiVisible = true
    }

    override fun onPause() {
        RelayState.uiVisible = false
        super.onPause()
    }

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
        const val EXTRA_TAB = "tab"
        /** Tab names in bottom-bar order, as accepted by [EXTRA_TAB]. */
        val TABS = listOf("status", "map", "log", "settings")
    }
}
