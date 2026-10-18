package net.internectual.gpsrelay.server

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import net.internectual.gpsrelay.GpsRelayApp
import net.internectual.gpsrelay.state.RelayState

/**
 * Invisible activity registered for geo:, google.navigation:, waze:, Maps/Waze web links and shared text.
 * It forwards the destination to connected clients and finishes immediately.
 */
class NavIntentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                intent.getStringExtra(Intent.EXTRA_SUBJECT),
                intent.getStringExtra(Intent.EXTRA_TEXT),
            ).joinToString("\n").ifBlank { null }
            else -> intent?.dataString
        }
        if (raw == null) {
            finish()
            return
        }
        val app = application as GpsRelayApp
        val source = sourcePackage()

        // While this activity is in the foreground we may start the location service.
        if (!RelayState.serverRunning.value && hasLocationPermission()) {
            ServerService.start(this)
        } else if (!RelayState.serverRunning.value) {
            Toast.makeText(this, "Open GPS Relay and start the server", Toast.LENGTH_LONG).show()
        }
        NavDispatcher.submit(this, raw, source)

        if (app.prefs.current.alsoOpenLocally && intent.action == Intent.ACTION_VIEW) {
            openLocally(intent.data)
        }
        finish()
    }

    private fun openLocally(uri: Uri?) {
        uri ?: return
        val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val chooser = Intent.createChooser(view, "Also open on this device")
        if (Build.VERSION.SDK_INT >= 24) {
            chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, NavIntentActivity::class.java)))
        }
        runCatching { startActivity(chooser) }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun sourcePackage(): String? {
        if (Build.VERSION.SDK_INT >= 22) {
            referrer?.let { r ->
                if (r.scheme == "android-app") return r.host
            }
        }
        return callingPackage
    }
}
