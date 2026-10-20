package net.internectual.gpsrelay.nmea

import net.internectual.gpsrelay.protocol.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NmeaSentencesTest {
    // 2026-10-20 01:36:07.45 UTC
    private val time = Instant.parse("2026-10-20T01:36:07.450Z").toEpochMilli()

    @Test fun checksumMatchesPublishedExamples() {
        assertEquals(
            "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47",
            NmeaSentences.frame("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"),
        )
        assertEquals(
            "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A",
            NmeaSentences.frame("GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W"),
        )
    }

    @Test fun validatesFraming() {
        assertTrue(NmeaSentences.isValid("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"))
        assertFalse(NmeaSentences.isValid("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*48"))
        assertFalse(NmeaSentences.isValid("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"))
        assertFalse(NmeaSentences.isValid("hello world"))
        assertFalse(NmeaSentences.isValid(""))
    }

    @Test fun splitsRawChunks() {
        val a = NmeaSentences.frame("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,")
        val b = NmeaSentences.frame("GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W")
        assertEquals(listOf(a), NmeaSentences.splitRaw("$a\r\n"))
        assertEquals(listOf(a), NmeaSentences.splitRaw(a))
        assertEquals(listOf(a, b), NmeaSentences.splitRaw("$a\r\n$b\r\n"))
        // Corrupt and non-NMEA lines are dropped.
        assertEquals(listOf(b), NmeaSentences.splitRaw("\$GPGGA,garbage*00\r\nnoise\r\n$b\n"))
        assertEquals(emptyList<String>(), NmeaSentences.splitRaw("\r\n"))
    }

    @Test fun formatsLatLon() {
        assertEquals("4807.03800,N", NmeaSentences.lat(48.1173))
        assertEquals("3352.12800,S", NmeaSentences.lat(-33.8688))
        assertEquals("01131.00000,E", NmeaSentences.lon(11.516666667))
        assertEquals("07400.60000,W", NmeaSentences.lon(-74.01))
        assertEquals("0000.00000,N", NmeaSentences.lat(0.0))
        assertEquals("00000.00000,E", NmeaSentences.lon(0.0))
        // Minutes must roll over into degrees rather than print 60.00000.
        assertEquals("1100.00000,N", NmeaSentences.lat(10.99999999999))
        assertEquals("18000.00000,E", NmeaSentences.lon(179.99999998))
        assertEquals("17959.99999,E", NmeaSentences.lon(179.9999999))
    }

    @Test fun formatsTimeAndDate() {
        assertEquals("013607.45", NmeaSentences.utcTime(time))
        assertEquals("201026", NmeaSentences.utcDate(time))
    }

    @Test fun buildsSentencesFromAFullFix() {
        val fix = Fix(
            lat = 48.1173, lon = 11.516666667, alt = 545.4, acc = 4.5f,
            speed = 11.5f, bearing = 84.4f, time = time, sats = 8,
        )
        val (gga, rmc, vtg) = NmeaSentences.fromFix(fix)
        assertEquals(NmeaSentences.frame("GPGGA,013607.45,4807.03800,N,01131.00000,E,1,08,0.9,545.4,M,,M,,"), gga)
        assertEquals(NmeaSentences.frame("GPRMC,013607.45,A,4807.03800,N,01131.00000,E,22.35,84.4,201026,,,A"), rmc)
        assertEquals(NmeaSentences.frame("GPVTG,84.4,T,,M,22.35,N,41.40,K,A"), vtg)
        listOf(gga, rmc, vtg).forEach { assertTrue(it, NmeaSentences.isValid(it)) }
    }

    @Test fun leavesUnknownFieldsEmpty() {
        val (gga, rmc, vtg) = NmeaSentences.fromFix(Fix(lat = -33.8688, lon = 151.2093, time = time))
        assertEquals(NmeaSentences.frame("GPGGA,013607.45,3352.12800,S,15112.55800,E,1,00,,,M,,M,,"), gga)
        assertEquals(NmeaSentences.frame("GPRMC,013607.45,A,3352.12800,S,15112.55800,E,,,201026,,,A"), rmc)
        assertEquals(NmeaSentences.frame("GPVTG,,T,,M,,N,,K,A"), vtg)
        listOf(gga, rmc, vtg).forEach { assertTrue(it, NmeaSentences.isValid(it)) }
    }
}
