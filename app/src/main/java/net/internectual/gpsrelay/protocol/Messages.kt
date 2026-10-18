package net.internectual.gpsrelay.protocol

import org.json.JSONException
import org.json.JSONObject

const val PROTOCOL_VERSION = 1
const val DEFAULT_PORT = 47474

/** A GPS fix as relayed from server to clients. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val alt: Double? = null,
    val acc: Float? = null,
    val speed: Float? = null,
    val bearing: Float? = null,
    val time: Long,
    val sats: Int? = null,
)

/** A navigation destination: coordinates, a free-text query, or both. */
data class Destination(
    val lat: Double? = null,
    val lon: Double? = null,
    val query: String? = null,
    val label: String? = null,
) {
    val hasCoords: Boolean get() = lat != null && lon != null

    fun describe(): String = buildString {
        label?.let { append(it) }
        if (query != null && query != label) {
            if (isNotEmpty()) append(" · ")
            append(query)
        }
        if (hasCoords) {
            if (isNotEmpty()) append(" · ")
            append("%.5f, %.5f".format(java.util.Locale.US, lat, lon))
        }
    }
}

sealed interface Msg {
    data class Hello(val version: Int, val role: String, val token: String?) : Msg
    data class FixMsg(val fix: Fix) : Msg
    data class Nav(
        val id: String,
        val dest: Destination,
        val raw: String,
        val sourceApp: String? = null,
        val mode: String = "drive",
    ) : Msg
    data class Ack(val id: String, val status: String?) : Msg
    data object Ping : Msg
    data object Pong : Msg
    data class Error(val reason: String) : Msg
}

/** Newline-delimited JSON codec. One message per line, `t` holds the type. */
object Codec {
    fun encode(m: Msg): String {
        val o = JSONObject()
        when (m) {
            is Msg.Hello -> o.put("t", "hello").put("version", m.version).put("role", m.role).putOpt("token", m.token)
            is Msg.FixMsg -> {
                val f = m.fix
                o.put("t", "fix").put("lat", f.lat).put("lon", f.lon).put("time", f.time)
                f.alt?.let { o.put("alt", it) }
                f.acc?.let { o.put("acc", it.toDouble()) }
                f.speed?.let { o.put("speed", it.toDouble()) }
                f.bearing?.let { o.put("bearing", it.toDouble()) }
                f.sats?.let { o.put("sats", it) }
            }
            is Msg.Nav -> {
                o.put("t", "nav").put("id", m.id).put("raw", m.raw).put("mode", m.mode)
                o.putOpt("sourceApp", m.sourceApp)
                m.dest.lat?.let { o.put("lat", it) }
                m.dest.lon?.let { o.put("lon", it) }
                o.putOpt("query", m.dest.query)
                o.putOpt("label", m.dest.label)
            }
            is Msg.Ack -> o.put("t", "ack").put("id", m.id).putOpt("status", m.status)
            Msg.Ping -> o.put("t", "ping")
            Msg.Pong -> o.put("t", "pong")
            is Msg.Error -> o.put("t", "error").put("reason", m.reason)
        }
        return o.toString()
    }

    fun decode(line: String): Msg? = try {
        val o = JSONObject(line)
        when (o.optString("t")) {
            "hello" -> Msg.Hello(o.optInt("version", 0), o.optString("role"), o.str("token"))
            "fix" -> Msg.FixMsg(
                Fix(
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    alt = o.dbl("alt"),
                    acc = o.dbl("acc")?.toFloat(),
                    speed = o.dbl("speed")?.toFloat(),
                    bearing = o.dbl("bearing")?.toFloat(),
                    time = o.getLong("time"),
                    sats = if (o.has("sats")) o.getInt("sats") else null,
                )
            )
            "nav" -> Msg.Nav(
                id = o.getString("id"),
                dest = Destination(o.dbl("lat"), o.dbl("lon"), o.str("query"), o.str("label")),
                raw = o.optString("raw"),
                sourceApp = o.str("sourceApp"),
                mode = o.optString("mode", "drive"),
            )
            "ack" -> Msg.Ack(o.getString("id"), o.str("status"))
            "ping" -> Msg.Ping
            "pong" -> Msg.Pong
            "error" -> Msg.Error(o.optString("reason"))
            else -> null
        }
    } catch (e: JSONException) {
        null
    }

    private fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
}
