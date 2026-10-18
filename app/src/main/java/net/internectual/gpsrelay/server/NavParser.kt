package net.internectual.gpsrelay.server

import net.internectual.gpsrelay.protocol.Destination
import java.net.URLDecoder

sealed interface ParseResult {
    data class Found(val dest: Destination) : ParseResult
    /** A short link (e.g. maps.app.goo.gl) that must be expanded over the network first. */
    data class NeedsExpansion(val url: String, val label: String?) : ParseResult
    data object None : ParseResult
}

/**
 * Turns whatever a "navigate" intent carried — geo: / google.navigation: / waze: URIs,
 * Google Maps or Waze web links, or shared free text — into a [Destination].
 * Pure Kotlin so it can be unit-tested on the JVM.
 */
object NavParser {

    private val anchoredCoords =
        Regex("""^\s*(?:loc:)?\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*(?:\((.*)\))?\s*$""")
    private val looseCoords = Regex("""(-?\d{1,2}\.\d{3,})\s*,\s*(-?\d{1,3}\.\d{3,})""")
    private val placeDataCoords = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")
    private val atCoords = Regex("""/@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")
    private val urlInText = Regex("""https?://\S+""")
    private val trailingLabel = Regex("""^(.*?)\s*\(([^()]*)\)\s*$""")

    fun parse(input: String): ParseResult {
        val s = input.trim()
        if (s.isEmpty()) return ParseResult.None
        val lower = s.lowercase()
        return when {
            lower.startsWith("geo:") -> parseGeo(s.substring(4))
            lower.startsWith("google.navigation:") -> parseGoogleNav(s.substringAfter(':'))
            lower.startsWith("waze:") -> parseWaze(params(s.substringAfter('?', "")))
            lower.startsWith("http://") || lower.startsWith("https://") -> parseUrl(s)
            else -> parseText(s)
        }
    }

    private fun parseText(text: String): ParseResult {
        val url = urlInText.find(text)?.value?.trimEnd('.', ',', ')', ']')
        if (url != null) {
            val label = text.replace(url, "").lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            return when (val r = parseUrl(url)) {
                is ParseResult.Found -> ParseResult.Found(r.dest.copy(label = r.dest.label ?: label))
                is ParseResult.NeedsExpansion -> r.copy(label = r.label ?: label)
                ParseResult.None -> if (label != null) ParseResult.Found(Destination(query = label)) else ParseResult.None
            }
        }
        looseCoords.find(text)?.let { m ->
            val rest = text.replace(m.value, "").trim().trim(',', ' ').ifEmpty { null }
            coords(m.groupValues[1], m.groupValues[2])?.let { (lat, lon) ->
                return ParseResult.Found(Destination(lat, lon, label = rest))
            }
        }
        val query = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        return ParseResult.Found(Destination(query = query))
    }

    private fun parseGeo(body: String): ParseResult {
        val b = body.removePrefix("//")
        val path = b.substringBefore('?').substringBefore(';')
        val p = params(b.substringAfter('?', ""))
        p["q"]?.takeIf { it.isNotBlank() }?.let { return found(it) }
        val c = anchoredCoords.find(path)
        if (c != null) {
            coords(c.groupValues[1], c.groupValues[2])?.let { (lat, lon) ->
                if (lat != 0.0 || lon != 0.0) return ParseResult.Found(Destination(lat, lon))
            }
        }
        return ParseResult.None
    }

    private fun parseGoogleNav(body: String): ParseResult {
        val p = params(body.removePrefix("//").removePrefix("?"))
        val title = p["title"]
        val r = (p["q"] ?: p["ll"])?.takeIf { it.isNotBlank() }?.let { found(it) } ?: return ParseResult.None
        return withLabel(r, title)
    }

    private fun parseWaze(p: Map<String, String>): ParseResult {
        p["ll"]?.let { ll -> anchoredCoords.find(ll)?.let { return withLabel(found(ll), p["q"]) } }
        p["q"]?.takeIf { it.isNotBlank() }?.let { return found(it) }
        return ParseResult.None
    }

    private fun parseUrl(url: String): ParseResult {
        val afterScheme = url.substringAfter("://")
        val host = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
        val pathAndQuery = afterScheme.removePrefix(afterScheme.substringBefore('/').substringBefore('?'))
        val path = pathAndQuery.substringBefore('?').substringBefore('#')
        val p = params(pathAndQuery.substringAfter('?', "").substringBefore('#'))

        if (host == "maps.app.goo.gl" || (host == "goo.gl" && path.startsWith("/maps")) || host == "g.co") {
            return ParseResult.NeedsExpansion(url, null)
        }
        if (host == "consent.google.com") {
            return p["continue"]?.let { parseUrl(it) } ?: ParseResult.None
        }
        if (host.endsWith("waze.com")) return parseWaze(p)

        val isGoogleMaps = host.startsWith("maps.google.") ||
            ((host.startsWith("google.") || host.startsWith("www.google.")) && path.startsWith("/maps"))
        if (!isGoogleMaps) return ParseResult.None

        (p["destination"] ?: p["daddr"] ?: p["q"] ?: p["query"])?.takeIf { it.isNotBlank() }?.let { v ->
            // "daddr" may chain waypoints with "+to:"; the last one is the destination.
            return found(v.split("+to:", " to:").last())
        }

        val segments = path.split('/').filter { it.isNotEmpty() }.map { decode(it) }
        val placeCoords = placeDataCoords.find(url)?.let { coords(it.groupValues[1], it.groupValues[2]) }
        val i = segments.indexOf("place")
        if (i >= 0 && i + 1 < segments.size) {
            val name = segments[i + 1]
            return if (placeCoords != null) {
                ParseResult.Found(Destination(placeCoords.first, placeCoords.second, query = name, label = name))
            } else {
                withLabel(found(name), null)
            }
        }
        val d = segments.indexOf("dir")
        if (d >= 0) {
            segments.drop(d + 1).lastOrNull { !it.startsWith("@") && !it.startsWith("data=") && !it.startsWith("am=") }
                ?.let { return found(it) }
        }
        val s = segments.indexOf("search")
        if (s >= 0 && s + 1 < segments.size && !segments[s + 1].startsWith("@")) return found(segments[s + 1])

        placeCoords?.let { return ParseResult.Found(Destination(it.first, it.second)) }
        p["ll"]?.let { return found(it) }
        atCoords.find(path)?.let { m ->
            coords(m.groupValues[1], m.groupValues[2])?.let { return ParseResult.Found(Destination(it.first, it.second)) }
        }
        return ParseResult.None
    }

    /** A value that is either "lat,lon[(label)]" or a free-text place/address. */
    private fun found(value: String): ParseResult {
        val v = value.trim()
        anchoredCoords.find(v)?.let { m ->
            coords(m.groupValues[1], m.groupValues[2])?.let { (lat, lon) ->
                return ParseResult.Found(Destination(lat, lon, label = m.groupValues[3].trim().ifEmpty { null }))
            }
        }
        trailingLabel.find(v)?.let { m ->
            val q = m.groupValues[1].trim()
            if (q.isNotEmpty()) return ParseResult.Found(Destination(query = q, label = m.groupValues[2].trim().ifEmpty { null }))
        }
        return ParseResult.Found(Destination(query = v))
    }

    private fun withLabel(r: ParseResult, label: String?): ParseResult =
        if (r is ParseResult.Found && label != null && r.dest.label == null) ParseResult.Found(r.dest.copy(label = label)) else r

    private fun coords(a: String, b: String): Pair<Double, Double>? {
        val lat = a.toDoubleOrNull() ?: return null
        val lon = b.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    private fun params(q: String): Map<String, String> =
        q.split('&').filter { it.isNotEmpty() }.associate { kv ->
            decode(kv.substringBefore('=')).lowercase() to decode(kv.substringAfter('=', ""))
        }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        s
    }
}
