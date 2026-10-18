package net.internectual.gpsrelay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import net.internectual.gpsrelay.state.RelayState
import net.internectual.gpsrelay.ui.AppRoot
import net.internectual.gpsrelay.ui.GpsRelayTheme

class MainActivity : ComponentActivity() {
    /** Set when launched from the boot notification; the UI starts the relay once permissions allow. */
    private val autostart = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            GpsRelayTheme {
                AppRoot(autostart = autostart)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) autostart.value = true
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
    }
}
