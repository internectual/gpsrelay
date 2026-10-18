package net.internectual.gpsrelay

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import net.internectual.gpsrelay.client.ClientService
import net.internectual.gpsrelay.server.ServerService
import net.internectual.gpsrelay.settings.Mode

/**
 * Android 11+ doesn't give location access to services started at boot, so there we post a
 * one-tap "Start" notification instead. Older devices start directly.
 */
class BootReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = (context.applicationContext as GpsRelayApp).prefs.current
        if (!prefs.startOnBoot) return

        if (Build.VERSION.SDK_INT < 30) {
            runCatching {
                if (prefs.mode == Mode.SERVER) ServerService.start(context) else ClientService.start(context)
            }
            return
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_AUTOSTART, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val what = if (prefs.mode == Mode.SERVER) "server" else "client"
        val n = NotificationCompat.Builder(context, GpsRelayApp.CH_BOOT)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle("Start GPS Relay $what")
            .setContentText("Tap to start relaying")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(GpsRelayApp.NOTIF_BOOT, n) }
    }
}
