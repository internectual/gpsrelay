package net.internectual.gpsrelay.client

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import net.internectual.gpsrelay.GpsRelayApp
import net.internectual.gpsrelay.R
import net.internectual.gpsrelay.protocol.Destination
import net.internectual.gpsrelay.state.RelayState
import java.util.Locale

/** Client side: hands a destination to the user's chosen navigation app. */
object NavLauncher {
    const val GOOGLE_MAPS = "com.google.android.apps.maps"
    const val WAZE = "com.waze"

    data class NavApp(val packageName: String, val label: String)

    /** Installed apps that handle geo: links (excluding GPS Relay itself). */
    fun installedNavApps(context: Context): List<NavApp> {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=test"))
        @Suppress("DEPRECATION")
        val found = pm.queryIntentActivities(probe, 0)
            .map { it.activityInfo.packageName }
            .toMutableSet()
        if (isInstalled(pm, WAZE)) found += WAZE
        found -= context.packageName
        return found.map { pkg ->
            val label = runCatching {
                @Suppress("DEPRECATION")
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            NavApp(pkg, label)
        }.sortedBy { it.label.lowercase() }
    }

    fun appLabel(context: Context, pkg: String): String =
        installedNavApps(context).firstOrNull { it.packageName == pkg }?.label ?: pkg

    fun buildIntent(context: Context, dest: Destination, pkg: String): Intent {
        val coords = if (dest.hasCoords) "%.6f,%.6f".format(Locale.US, dest.lat, dest.lon) else null
        val q = Uri.encode(coords ?: dest.query ?: "")
        val uri = when (pkg) {
            GOOGLE_MAPS -> Uri.parse("google.navigation:q=$q&mode=d")
            WAZE -> Uri.parse(
                if (coords != null) "https://waze.com/ul?ll=$q&navigate=yes" else "https://waze.com/ul?q=$q&navigate=yes"
            )
            else -> if (coords != null) {
                val label = dest.label ?: dest.query
                Uri.parse("geo:$coords?q=$coords" + (label?.let { "(" + Uri.encode(it) + ")" } ?: ""))
            } else {
                Uri.parse("geo:0,0?q=$q")
            }
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg.isNotEmpty() && isInstalled(context.packageManager, pkg)) intent.setPackage(pkg)
        return intent
    }

    /**
     * Opens the navigation app. If Android won't allow a background activity start, posts a
     * high-priority notification instead. Returns a short status for the log.
     */
    @SuppressLint("MissingPermission")
    fun launch(context: Context, dest: Destination, pkg: String, fromForeground: Boolean = false): String {
        val intent = buildIntent(context, dest, pkg)
        val target = if (pkg.isEmpty()) "default maps app" else appLabel(context, pkg)
        if (fromForeground || canStartFromBackground(context)) {
            try {
                context.startActivity(intent)
                return "launched in $target"
            } catch (e: Exception) {
                // Fall through to the notification.
            }
        }
        val pi = PendingIntent.getActivity(
            context, dest.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0),
        )
        val builder = NotificationCompat.Builder(context, GpsRelayApp.CH_NAV)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle("Navigate to destination")
            .setContentText(dest.describe())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .setContentIntent(pi)
        val fullScreen = Build.VERSION.SDK_INT < 34 ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        if (fullScreen) builder.setFullScreenIntent(pi, true)
        return try {
            NotificationManagerCompat.from(context).notify(GpsRelayApp.NOTIF_NAV, builder.build())
            "notification posted (tap to open $target)"
        } catch (e: SecurityException) {
            "failed: notifications are blocked"
        }
    }

    fun canStartFromBackground(context: Context): Boolean =
        RelayState.uiVisible || Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(context)

    private fun isInstalled(pm: PackageManager, pkg: String) = try {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
