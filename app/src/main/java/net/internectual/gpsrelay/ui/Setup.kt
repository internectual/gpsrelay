package net.internectual.gpsrelay.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import net.internectual.gpsrelay.client.ClientService
import net.internectual.gpsrelay.server.ServerService
import net.internectual.gpsrelay.settings.Mode
import java.net.Inet4Address
import java.net.NetworkInterface

/** Permission / system-setting checks and the intents that fix them. */
object Setup {
    val runtimePermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    fun hasLocation(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasNotifications(ctx: Context) =
        Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)

    @Suppress("DEPRECATION")
    fun isMockApp(ctx: Context): Boolean = if (Build.VERSION.SDK_INT >= 23) {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        runCatching {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    } else {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ALLOW_MOCK_LOCATION) == "1"
    }

    /** Only matters on Android 10+, where background activity starts are restricted. */
    fun overlayNeeded() = Build.VERSION.SDK_INT >= 29
    fun canOverlay(ctx: Context) = !overlayNeeded() || Settings.canDrawOverlays(ctx)

    fun batteryNeeded() = Build.VERSION.SDK_INT >= 23
    fun ignoresBattery(ctx: Context) = !batteryNeeded() ||
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    fun fullScreenNeeded() = Build.VERSION.SDK_INT >= 34
    fun canFullScreen(ctx: Context) = !fullScreenNeeded() ||
        ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun devOptionsIntent() = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
    @SuppressLint("InlinedApi") // only shown when the matching *Needed() check is true
    fun overlayIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
    @SuppressLint("InlinedApi") // only shown when the matching *Needed() check is true
    fun batteryIntent() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    @SuppressLint("InlinedApi") // only shown when the matching *Needed() check is true
    fun fullScreenIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))
    fun appDetailsIntent(pkg: String) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
    fun openByDefaultIntent(pkg: String) = if (Build.VERSION.SDK_INT >= 31) {
        Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:$pkg"))
    } else {
        appDetailsIntent(pkg)
    }

    fun open(ctx: Context, intent: Intent) {
        runCatching { ctx.startActivity(intent) }
            .onFailure { runCatching { ctx.startActivity(appDetailsIntent(ctx.packageName)) } }
    }

    fun startRelay(ctx: Context, mode: Mode) {
        if (mode == Mode.SERVER) {
            ClientService.stop(ctx)
            ServerService.start(ctx)
        } else {
            ServerService.stop(ctx)
            ClientService.start(ctx)
        }
    }

    fun stopRelay(ctx: Context) {
        ServerService.stop(ctx)
        ClientService.stop(ctx)
    }

    /** This device's IPv4 addresses, e.g. to type into a client's manual server field. */
    fun localIpv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress} (${nif.name})" } }
    }.getOrDefault(emptyList())

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
}
