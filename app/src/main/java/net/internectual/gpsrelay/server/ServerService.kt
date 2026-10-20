package net.internectual.gpsrelay.server

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.internectual.gpsrelay.GpsRelayApp
import net.internectual.gpsrelay.MainActivity
import net.internectual.gpsrelay.R
import net.internectual.gpsrelay.nmea.NmeaOutput
import net.internectual.gpsrelay.nmea.NmeaSentences
import net.internectual.gpsrelay.nmea.UdpTarget
import net.internectual.gpsrelay.protocol.Fix
import net.internectual.gpsrelay.protocol.Msg
import net.internectual.gpsrelay.settings.Prefs
import net.internectual.gpsrelay.state.RelayState

/** Server mode: reads the GPS and serves fixes + intercepted destinations to clients on the hotspot. */
class ServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var lm: LocationManager
    private var relay: RelayServer? = null
    private var sats: Int? = null
    private var lastNotifUpdate = 0L
    private var gnssCallback: Any? = null
    private var nmea: NmeaOutput? = null
    private var nmeaJob: Job? = null
    private var nmeaCallback: Any? = null
    /** The last error message the NMEA output posted, so a reconfigure can clear it without clobbering others. */
    private var nmeaError: String? = null
    /** When the platform last handed us a raw NMEA sentence; while it keeps doing so we don't synthesize our own. */
    @Volatile private var lastRawNmeaAt = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) {
            RelayState.serverError.value = null
        }
        override fun onProviderDisabled(provider: String) {
            RelayState.serverError.value = "GPS is turned off"
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, GpsRelayApp.NOTIF_SERVICE, buildNotification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            // Location FGS can't start from the background on newer Android (e.g. a sticky restart).
            RelayState.serverError.value = "Couldn't start in background: open GPS Relay to start the server"
            stopSelf()
            return START_NOT_STICKY
        }
        if (relay == null) startRelay()
        RelayState.serverRunning.value = true
        return START_STICKY
    }

    private fun startRelay() {
        val app = application as GpsRelayApp
        val p = app.prefs.current
        RelayState.serverError.value = null
        val server = RelayServer(p.port, p.pin, scope, object : RelayServer.Listener {
            override fun onClientsChanged(remotes: List<String>) {
                RelayState.clients.value = remotes
                updateNotification(force = true)
            }
            override fun onAck(navId: String, status: String?, remote: String) =
                NavDispatcher.onAck(this@ServerService, navId, status, remote)
            override fun onError(message: String) {
                RelayState.serverError.value = message
            }
            override fun pendingNav(): Msg.Nav? = NavDispatcher.pendingNav()
        })
        server.start()
        relay = server
        NavDispatcher.server = server
        startGps()
        nmeaJob = scope.launch(Dispatchers.Main) {
            var first = true
            app.prefs.values.map(::NmeaConfig).distinctUntilChanged().collectLatest { cfg ->
                // Settings save on every keystroke; wait for typing to settle before rebinding sockets.
                if (!first) delay(NMEA_SETTLE_MS)
                first = false
                applyNmea(cfg)
            }
        }
    }

    /** The settings [NmeaOutput] is built from; a change to any of them rebuilds it. */
    private data class NmeaConfig(val tcpPort: Int?, val udpEnabled: Boolean, val udpTargets: String) {
        constructor(p: Prefs.Values) : this(
            tcpPort = if (p.nmeaTcpEnabled) p.nmeaTcpPort else null,
            udpEnabled = p.nmeaUdpEnabled,
            udpTargets = if (p.nmeaUdpEnabled) p.nmeaUdpTargets else "",
        )
    }

    private fun applyNmea(cfg: NmeaConfig) {
        nmea?.stop()
        nmea = null
        nmeaError?.let { if (RelayState.serverError.value == it) RelayState.serverError.value = null }
        nmeaError = null
        val udpTargets = if (cfg.udpEnabled) UdpTarget.parseList(cfg.udpTargets) else emptyList()
        if (udpTargets == null || (cfg.udpEnabled && udpTargets.isEmpty())) {
            setNmeaError("NMEA UDP is on but has no valid host:port")
        }
        val targets = udpTargets.orEmpty()
        if (cfg.tcpPort == null && targets.isEmpty()) {
            setNmeaListener(false)
            return
        }
        val out = NmeaOutput(scope, cfg.tcpPort, targets, object : NmeaOutput.Listener {
            override fun onClientsChanged(remotes: List<String>) {
                RelayState.nmeaClients.value = remotes
            }
            override fun onError(message: String) = setNmeaError(message)
        })
        out.start()
        nmea = out
        setNmeaListener(true)
    }

    private fun setNmeaError(message: String) {
        nmeaError = message
        RelayState.serverError.value = message
    }

    /** The receiver's own sentences (GSV, GSA, multi-constellation talkers, ...) beat anything we could synthesize. */
    @SuppressLint("MissingPermission")
    private fun setNmeaListener(on: Boolean) {
        if (Build.VERSION.SDK_INT < 24 || on == (nmeaCallback != null)) return
        if (!on) {
            (nmeaCallback as? OnNmeaMessageListener)?.let { lm.removeNmeaListener(it) }
            nmeaCallback = null
            return
        }
        val listener = OnNmeaMessageListener { message, _ ->
            val sentences = NmeaSentences.splitRaw(message)
            if (sentences.isNotEmpty()) {
                lastRawNmeaAt = SystemClock.elapsedRealtime()
                nmea?.publish(sentences)
            }
        }
        try {
            @Suppress("DEPRECATION")
            lm.addNmeaListener(listener, Handler(Looper.getMainLooper()))
            nmeaCallback = listener
        } catch (e: SecurityException) {
            RelayState.serverError.value = "Location permission not granted"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (!lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            RelayState.serverError.value = "This device has no GPS"
            return
        }
        try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocation(it) }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) RelayState.serverError.value = "GPS is turned off"
            if (Build.VERSION.SDK_INT >= 24) {
                val cb = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        sats = (0 until status.satelliteCount).count { status.usedInFix(it) }
                    }
                }
                @Suppress("DEPRECATION")
                lm.registerGnssStatusCallback(cb, Handler(Looper.getMainLooper()))
                gnssCallback = cb
            }
        } catch (e: SecurityException) {
            RelayState.serverError.value = "Location permission not granted"
        }
    }

    private fun onLocation(l: Location) {
        val fix = Fix(
            lat = l.latitude,
            lon = l.longitude,
            alt = if (l.hasAltitude()) l.altitude else null,
            acc = if (l.hasAccuracy()) l.accuracy else null,
            speed = if (l.hasSpeed()) l.speed else null,
            bearing = if (l.hasBearing()) l.bearing else null,
            time = l.time,
            sats = sats,
        )
        RelayState.onFix(fix)
        relay?.broadcastFix(Msg.FixMsg(fix))
        nmea?.let { out ->
            // Fall back to sentences built from the fix when the platform isn't providing raw NMEA (before API 24, or
            // a device that doesn't deliver it).
            if (SystemClock.elapsedRealtime() - lastRawNmeaAt > RAW_NMEA_STALE_MS) out.publish(NmeaSentences.fromFix(fix))
        }
        updateNotification(force = false)
    }

    private fun buildNotification(): Notification {
        val clients = RelayState.clients.value.size
        val fix = RelayState.fix.value
        val text = buildString {
            append(if (clients == 1) "1 client connected" else "$clients clients connected")
            fix?.acc?.let { append(" · GPS ±%.0f m".format(it)) } ?: if (fix == null) append(" · waiting for GPS") else Unit
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PI_FLAGS,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PI_FLAGS,
        )
        return NotificationCompat.Builder(this, GpsRelayApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(getString(R.string.notif_server_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifUpdate < 10_000) return
        lastNotifUpdate = now
        try {
            NotificationManagerCompat.from(this).notify(GpsRelayApp.NOTIF_SERVICE, buildNotification())
        } catch (e: SecurityException) {
            // Notifications permission denied; the FGS still runs.
        }
    }

    override fun onDestroy() {
        lm.removeUpdates(locationListener)
        if (Build.VERSION.SDK_INT >= 24) {
            (gnssCallback as? GnssStatus.Callback)?.let { lm.unregisterGnssStatusCallback(it) }
            (nmeaCallback as? OnNmeaMessageListener)?.let { lm.removeNmeaListener(it) }
        }
        NavDispatcher.server = null
        relay?.stop()
        relay = null
        nmeaJob?.cancel()
        nmea?.stop()
        nmea = null
        scope.cancel()
        RelayState.serverRunning.value = false
        RelayState.clients.value = emptyList()
        RelayState.nmeaClients.value = emptyList()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "net.internectual.gpsrelay.STOP_SERVER"
        private const val RAW_NMEA_STALE_MS = 3_000L
        private const val NMEA_SETTLE_MS = 500L
        val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ServerService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, ServerService::class.java))
        }
    }
}
