package net.internectual.gpsrelay.client

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.internectual.gpsrelay.GpsRelayApp
import net.internectual.gpsrelay.MainActivity
import net.internectual.gpsrelay.R
import net.internectual.gpsrelay.log.Direction
import net.internectual.gpsrelay.protocol.Fix
import net.internectual.gpsrelay.protocol.LineConnection
import net.internectual.gpsrelay.protocol.Msg
import net.internectual.gpsrelay.protocol.PROTOCOL_VERSION
import net.internectual.gpsrelay.state.ConnState
import net.internectual.gpsrelay.state.RelayState
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Client mode: connects to the server (hotspot gateway by default), replays its GPS fixes as mock
 * locations and opens received destinations in the chosen navigation app.
 */
class ClientService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var mock: MockLocationProvider
    private var loopJob: Job? = null
    @Volatile private var conn: LineConnection? = null
    private val handledNavIds = ArrayDeque<String>()
    private var lastMockAttempt = 0L
    private var lastNotifUpdate = 0L
    /** Set once onDestroy starts, so callbacks fired while tearing down can't re-post the notification. */
    private var destroyed = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val app get() = application as GpsRelayApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        mock = MockLocationProvider(this)
        current = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, GpsRelayApp.NOTIF_CLIENT, buildNotification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            RelayState.connError.value = "Couldn't start in background: open GPS Relay to connect"
            stopSelf()
            return START_NOT_STICKY
        }
        if (loopJob == null) {
            loopJob = scope.launch { runLoop() }
            watchWifi()
        }
        RelayState.clientRunning.value = true
        return START_STICKY
    }

    private suspend fun runLoop() {
        var backoff = 1000L
        while (scope.isActive) {
            val p = app.prefs.current
            val target = ServerAddress.resolve(this, p.serverOverride, p.port)
            if (target == null) {
                setConn(ConnState.SEARCHING, null, "Not connected to a Wi-Fi hotspot")
                delay(3000)
                continue
            }
            setConn(ConnState.CONNECTING, target.toString(), null)
            var error: String? = null
            var pingJob: Job? = null
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(target.host, target.port), 5000)
                socket.tcpNoDelay = true
                socket.soTimeout = 15_000
                val c = LineConnection(socket, scope)
                conn = c
                c.send(Msg.Hello(PROTOCOL_VERSION, "client", p.pin.ifEmpty { null }))
                when (val first = c.read()) {
                    is Msg.Hello -> Unit
                    is Msg.Error -> throw IOException(if (first.reason == "bad_pin") "Server rejected the PIN" else first.reason)
                    else -> throw IOException("Not a GPS Relay server")
                }
                setConn(ConnState.CONNECTED, target.toString(), null)
                backoff = 1000L
                pingJob = scope.launch {
                    while (isActive) {
                        delay(5000)
                        c.send(Msg.Ping)
                    }
                }
                while (true) {
                    when (val m = c.read() ?: break) {
                        is Msg.FixMsg -> onFix(m.fix)
                        is Msg.Nav -> onNav(c, m)
                        is Msg.Error -> error = m.reason
                        else -> Unit
                    }
                }
                if (error == null) error = "Server closed the connection"
            } catch (e: IOException) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                pingJob?.cancel()
                conn?.close()
                conn = null
            }
            if (!scope.isActive) break
            setConn(ConnState.ERROR, target.toString(), error)
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(8000L)
        }
    }

    private fun onFix(fix: Fix) {
        RelayState.onFix(fix)
        if (!mock.isActive) {
            val now = System.currentTimeMillis()
            if (now - lastMockAttempt < 5000) return
            lastMockAttempt = now
            RelayState.mockError.value = mock.start()
        }
        if (mock.isActive) mock.push(fix)
        updateNotification(force = false)
    }

    private fun onNav(c: LineConnection, nav: Msg.Nav) {
        if (nav.id in handledNavIds) {
            c.send(Msg.Ack(nav.id, "already handled"))
            return
        }
        handledNavIds.addLast(nav.id)
        while (handledNavIds.size > 50) handledNavIds.removeFirst()

        RelayState.lastDestination.value = nav.dest
        val log = app.navLog
        val row = log.add(Direction.IN, nav.raw, nav.sourceApp, "received", navId = nav.id, dest = nav.dest)
        val result = NavLauncher.launch(this, nav.dest, app.prefs.current.navAppPackage)
        val status = when {
            result.startsWith("launched") -> "launched"
            result.startsWith("notification") -> "notified"
            else -> "failed"
        }
        log.update(row, status = status, detail = result)
        c.send(Msg.Ack(nav.id, result))
    }

    /** Drop the connection when Wi-Fi changes so we reconnect to the new gateway promptly. */
    private fun watchWifi() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                conn?.close()
            }
        }
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { cm.registerNetworkCallback(req, cb) }.onSuccess { networkCallback = cb }
    }

    private fun setConn(state: ConnState, target: String?, error: String?) {
        RelayState.conn.value = state
        RelayState.connTarget.value = target
        RelayState.connError.value = error
        updateNotification(force = true)
    }

    private fun buildNotification(): Notification {
        val target = RelayState.connTarget.value
        val text = when (RelayState.conn.value) {
            ConnState.CONNECTED -> buildString {
                append("Connected to $target")
                RelayState.fix.value?.acc?.let { append(" · GPS ±%.0f m".format(it)) }
            }
            ConnState.CONNECTING -> "Connecting to $target…"
            ConnState.ERROR -> "Reconnecting… (${RelayState.connError.value ?: "error"})"
            else -> "Looking for the GPS Relay server…"
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PI_FLAGS)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ClientService::class.java).setAction(ACTION_STOP), PI_FLAGS,
        )
        return NotificationCompat.Builder(this, GpsRelayApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(getString(R.string.notif_client_title))
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
        if (destroyed) return
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifUpdate < 10_000) return
        lastNotifUpdate = now
        try {
            NotificationManagerCompat.from(this).notify(GpsRelayApp.NOTIF_CLIENT, buildNotification())
        } catch (e: SecurityException) {
            // Notifications permission denied; the FGS still runs.
        }
    }

    override fun onDestroy() {
        destroyed = true
        networkCallback?.let {
            runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
        }
        scope.cancel()
        conn?.close()
        mock.stop()
        RelayState.clientRunning.value = false
        RelayState.conn.value = ConnState.STOPPED
        RelayState.connError.value = null
        current = null
        // Also clears a notification left behind by an older build or a stray Stop tap.
        NotificationManagerCompat.from(this).cancel(GpsRelayApp.NOTIF_CLIENT)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "net.internectual.gpsrelay.STOP_CLIENT"
        private val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ClientService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, ClientService::class.java))
        }

        /** Drops the current connection so the next attempt picks up changed settings. */
        fun reconnect() {
            instanceConn?.close()
        }

        private val instanceConn: LineConnection? get() = current?.conn
        @Volatile private var current: ClientService? = null
    }
}
