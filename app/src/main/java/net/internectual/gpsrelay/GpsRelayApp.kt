package net.internectual.gpsrelay

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.internectual.gpsrelay.automation.Automation
import net.internectual.gpsrelay.log.NavLogStore
import net.internectual.gpsrelay.settings.Prefs
import org.osmdroid.config.Configuration
import java.io.File

class GpsRelayApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var navLog: NavLogStore
        private set

    /** Outlives activities; used for nav dispatch work started from short-lived activities. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        navLog = NavLogStore(this)
        createChannels()
        Automation.broadcastStateChanges(this, prefs, appScope)
        Configuration.getInstance().apply {
            userAgentValue = "$packageName/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, getString(R.string.ch_service), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_NAV, getString(R.string.ch_nav), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_BOOT, getString(R.string.ch_boot), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CH_SERVICE = "relay_service"
        const val CH_NAV = "nav_prompt"
        const val CH_BOOT = "boot_start"

        const val NOTIF_SERVER = 1
        const val NOTIF_NAV = 2
        const val NOTIF_BOOT = 3
        const val NOTIF_CLIENT = 4

        lateinit var instance: GpsRelayApp
            private set
    }
}
