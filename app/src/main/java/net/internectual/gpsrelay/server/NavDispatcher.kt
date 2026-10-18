package net.internectual.gpsrelay.server

import android.content.Context
import android.location.Geocoder
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.internectual.gpsrelay.GpsRelayApp
import net.internectual.gpsrelay.log.Direction
import net.internectual.gpsrelay.protocol.Destination
import net.internectual.gpsrelay.protocol.Msg
import net.internectual.gpsrelay.state.RelayState
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

/**
 * Server side: takes an intercepted link, resolves it to a [Destination], logs it, and hands it to the
 * running [RelayServer]. Keeps the latest unacknowledged destination so a client that connects later
 * still receives it.
 */
object NavDispatcher {
    private const val PENDING_TTL_MS = 2 * 60 * 60 * 1000L

    @Volatile var server: RelayServer? = null
    @Volatile private var pending: Pair<Msg.Nav, Long>? = null

    fun pendingNav(): Msg.Nav? {
        val p = pending ?: return null
        if (System.currentTimeMillis() - p.second > PENDING_TTL_MS) {
            pending = null
            return null
        }
        return p.first
    }

    /** Parses and sends [raw] (a URI or shared text) in the background. */
    fun submit(context: Context, raw: String, sourceApp: String?) {
        val app = context.applicationContext as GpsRelayApp
        app.appScope.launch {
            val log = app.navLog
            val row = log.add(Direction.OUT, raw, sourceApp, "intercepted")
            var result = NavParser.parse(raw)
            if (result is ParseResult.NeedsExpansion) {
                log.update(row, status = "expanding link")
                val expanded = withContext(Dispatchers.IO) { expandShortLink(result.url) }
                val label = result.label
                result = expanded?.let { NavParser.parse(it) } ?: ParseResult.None
                if (result is ParseResult.Found && label != null && result.dest.label == null) {
                    result = ParseResult.Found(result.dest.copy(label = label))
                }
            }
            if (result !is ParseResult.Found) {
                log.update(row, status = "failed", detail = "No destination found in this link")
                toast(app, "GPS Relay: couldn't find a destination in that link")
                return@launch
            }
            var dest = result.dest
            if (!dest.hasCoords && dest.query != null) {
                geocode(app, dest.query!!)?.let { (lat, lon) ->
                    dest = dest.copy(lat = lat, lon = lon)
                }
            }
            send(app, row, dest, raw, sourceApp)
        }
    }

    /** Re-sends a destination that is already resolved (from the log). */
    fun resend(context: Context, dest: Destination, raw: String, sourceApp: String?) {
        val app = context.applicationContext as GpsRelayApp
        app.appScope.launch {
            val row = app.navLog.add(Direction.OUT, raw, sourceApp, "resending", dest = dest)
            send(app, row, dest, raw, sourceApp)
        }
    }

    fun onAck(context: Context, navId: String, status: String?, remote: String) {
        if (pending?.first?.id == navId) pending = null
        val app = context.applicationContext as GpsRelayApp
        app.navLog.updateByNavId(navId, Direction.OUT, "acked", status ?: "delivered to $remote")
    }

    private suspend fun send(app: GpsRelayApp, row: Long, dest: Destination, raw: String, sourceApp: String?) {
        val nav = Msg.Nav(UUID.randomUUID().toString(), dest, raw, sourceApp)
        pending = nav to System.currentTimeMillis()
        RelayState.lastDestination.value = dest
        val srv = server
        val n = srv?.sendNav(nav) ?: 0
        val (status, detail, msg) = when {
            srv == null -> Triple("queued", "Server not running", "GPS Relay server is off — destination queued")
            n == 0 -> Triple("queued", "Waiting for a client to connect", "No client connected — destination queued")
            else -> Triple("sent", "to $n device(s)", "Sent to client: ${dest.describe()}")
        }
        app.navLog.update(row, status = status, detail = detail, navId = nav.id, dest = dest)
        toast(app, msg)
    }

    @Suppress("DEPRECATION")
    private suspend fun geocode(context: Context, query: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        try {
            Geocoder(context, Locale.getDefault()).getFromLocationName(query, 1)
                ?.firstOrNull()?.let { it.latitude to it.longitude }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Follows redirects of a short link (maps.app.goo.gl etc.) without loading the final page. */
    private fun expandShortLink(url: String): String? {
        var current = url
        repeat(8) {
            val conn = try {
                URL(current).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                return null
            }
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) GPSRelay")
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code in 300..399 && location != null) {
                    current = URL(URL(current), location).toString()
                    // Stop as soon as the target is something we can parse.
                    if (NavParser.parse(current) is ParseResult.Found) return current
                } else {
                    return current
                }
            } catch (e: IOException) {
                return null
            } finally {
                conn.disconnect()
            }
        }
        return current
    }

    private suspend fun toast(context: Context, msg: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }
}
