package net.internectual.gpsrelay.nmea

import net.internectual.gpsrelay.protocol.Fix
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Builds NMEA 0183 sentences from a [Fix], and validates raw sentences from the platform. */
object NmeaSentences {
    private const val MS_TO_KNOTS = 1.943844
    private const val MS_TO_KMH = 3.6

    /** Typical GNSS range error in metres, used to turn Android's horizontal accuracy into an HDOP estimate. */
    private const val UERE_M = 5.0

    /** XOR of every character between `$` and `*`. */
    fun checksum(body: String): Int {
        var c = 0
        for (ch in body) c = c xor ch.code
        return c
    }

    /** Wraps [body] (no `$`, no checksum) as `$body*HH`, without a line terminator. */
    fun frame(body: String): String = "$" + body + "*" + "%02X".format(Locale.US, checksum(body))

    /** True if [sentence] looks like `$...*HH` or `!...*HH` with a matching checksum. */
    fun isValid(sentence: String): Boolean {
        if (sentence.length < 9 || (sentence[0] != '$' && sentence[0] != '!')) return false
        val star = sentence.length - 3
        if (sentence[star] != '*') return false
        val given = sentence.substring(star + 1).toIntOrNull(16) ?: return false
        return checksum(sentence.substring(1, star)) == given
    }

    /**
     * Splits a chunk from the platform's NMEA callback into individual valid sentences.
     * A callback may carry one sentence or several, with or without a trailing CR/LF.
     */
    fun splitRaw(chunk: String): List<String> =
        chunk.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() && isValid(it) }

    /** GGA, RMC and VTG for [fix], in that order. */
    fun fromFix(fix: Fix): List<String> = listOf(gga(fix), rmc(fix), vtg(fix))

    fun gga(f: Fix): String {
        val hdop = f.acc?.let { "%.1f".format(Locale.US, (it / UERE_M).coerceAtLeast(0.5)) } ?: ""
        val alt = f.alt?.let { "%.1f".format(Locale.US, it) } ?: ""
        // Android reports height above the WGS84 ellipsoid and no geoid model is bundled, so the
        // geoid separation is left empty.
        return frame("GPGGA,${utcTime(f.time)},${lat(f.lat)},${lon(f.lon)},1,%02d,$hdop,$alt,M,,M,,".format(Locale.US, f.sats ?: 0))
    }

    fun rmc(f: Fix): String {
        val knots = f.speed?.let { "%.2f".format(Locale.US, it * MS_TO_KNOTS) } ?: ""
        val course = f.bearing?.let { "%.1f".format(Locale.US, it) } ?: ""
        return frame("GPRMC,${utcTime(f.time)},A,${lat(f.lat)},${lon(f.lon)},$knots,$course,${utcDate(f.time)},,,A")
    }

    fun vtg(f: Fix): String {
        val course = f.bearing?.let { "%.1f".format(Locale.US, it) } ?: ""
        val knots = f.speed?.let { "%.2f".format(Locale.US, it * MS_TO_KNOTS) } ?: ""
        val kmh = f.speed?.let { "%.2f".format(Locale.US, it * MS_TO_KMH) } ?: ""
        return frame("GPVTG,$course,T,,M,$knots,N,$kmh,K,A")
    }

    /** `ddmm.mmmmm,N|S`. Works in integer units so minutes never round up to 60. */
    internal fun lat(deg: Double): String {
        val units = Math.round(abs(deg) * 60.0 * 100_000.0)
        val d = units / 6_000_000
        val m = units % 6_000_000
        return "%02d%02d.%05d,%s".format(Locale.US, d, m / 100_000, m % 100_000, if (deg >= 0) "N" else "S")
    }

    /** `dddmm.mmmmm,E|W`. */
    internal fun lon(deg: Double): String {
        val units = Math.round(abs(deg) * 60.0 * 100_000.0)
        val d = units / 6_000_000
        val m = units % 6_000_000
        return "%03d%02d.%05d,%s".format(Locale.US, d, m / 100_000, m % 100_000, if (deg >= 0) "E" else "W")
    }

    private fun utc(time: Long) = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US).apply { timeInMillis = time }

    /** `hhmmss.ss` */
    internal fun utcTime(time: Long): String {
        val c = utc(time)
        return "%02d%02d%02d.%02d".format(
            Locale.US,
            c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE),
            c.get(java.util.Calendar.SECOND),
            c.get(java.util.Calendar.MILLISECOND) / 10,
        )
    }

    /** `ddmmyy` */
    internal fun utcDate(time: Long): String {
        val c = utc(time)
        return "%02d%02d%02d".format(
            Locale.US,
            c.get(java.util.Calendar.DAY_OF_MONTH),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.YEAR) % 100,
        )
    }
}
