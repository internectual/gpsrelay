package net.internectual.gpsrelay.server

import net.internectual.gpsrelay.protocol.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavParserTest {

    private fun dest(input: String): Destination {
        val r = NavParser.parse(input)
        assertTrue("expected Found for $input but got $r", r is ParseResult.Found)
        return (r as ParseResult.Found).dest
    }

    private fun assertCoords(d: Destination, lat: Double, lon: Double) {
        assertEquals(lat, d.lat!!, 1e-6)
        assertEquals(lon, d.lon!!, 1e-6)
    }

    @Test fun geoPlainCoords() = assertCoords(dest("geo:37.4220,-122.0841"), 37.4220, -122.0841)

    @Test fun geoCoordsWithZoom() = assertCoords(dest("geo:37.4220,-122.0841?z=17"), 37.4220, -122.0841)

    @Test fun geoQueryCoordsWithLabel() {
        val d = dest("geo:0,0?q=37.4220,-122.0841(Googleplex)")
        assertCoords(d, 37.4220, -122.0841)
        assertEquals("Googleplex", d.label)
    }

    @Test fun geoQueryAddress() {
        val d = dest("geo:0,0?q=1600+Amphitheatre+Parkway%2C+Mountain+View")
        assertEquals("1600 Amphitheatre Parkway, Mountain View", d.query)
        assertEquals(null, d.lat)
    }

    @Test fun geoQueryAddressWithLabel() {
        val d = dest("geo:0,0?q=1600 Amphitheatre Pkwy(Google HQ)")
        assertEquals("1600 Amphitheatre Pkwy", d.query)
        assertEquals("Google HQ", d.label)
    }

    @Test fun geoZeroZeroWithoutQueryIsNothing() = assertEquals(ParseResult.None, NavParser.parse("geo:0,0"))

    @Test fun googleNavigationCoords() = assertCoords(dest("google.navigation:q=51.5007,-0.1246&mode=d"), 51.5007, -0.1246)

    @Test fun googleNavigationAddress() =
        assertEquals("Big Ben, London", dest("google.navigation:q=Big+Ben,+London").query)

    @Test fun googleNavigationLl() = assertCoords(dest("google.navigation:ll=48.8584,2.2945&title=Eiffel"), 48.8584, 2.2945)

    @Test fun wazeScheme() = assertCoords(dest("waze://?ll=40.7484,-73.9857&navigate=yes"), 40.7484, -73.9857)

    @Test fun wazeSchemeQuery() = assertEquals("Empire State Building", dest("waze://?q=Empire%20State%20Building").query)

    @Test fun wazeWebLink() = assertCoords(dest("https://waze.com/ul?ll=40.7484%2C-73.9857&navigate=yes"), 40.7484, -73.9857)

    @Test fun wazeUlHost() = assertCoords(dest("https://ul.waze.com/ul?ll=40.7484,-73.9857&navigate=yes&zoom=17"), 40.7484, -73.9857)

    @Test fun mapsGoogleQ() = assertCoords(dest("https://maps.google.com/?q=35.6586,139.7454"), 35.6586, 139.7454)

    @Test fun mapsGoogleDaddr() =
        assertEquals("Tokyo Tower", dest("https://maps.google.com/maps?saddr=Current+Location&daddr=Tokyo+Tower").query)

    @Test fun mapsGoogleDaddrWaypoints() =
        assertEquals("C St", dest("https://maps.google.com/maps?daddr=A+St+to:B+St+to:C+St").query)

    @Test fun mapsUrlApiDirections() =
        assertEquals("Sydney Opera House", dest("https://www.google.com/maps/dir/?api=1&destination=Sydney+Opera+House&travelmode=driving").query)

    @Test fun mapsUrlApiSearch() =
        assertCoords(dest("https://www.google.com/maps/search/?api=1&query=-33.8568,151.2153"), -33.8568, 151.2153)

    @Test fun mapsPlaceWithDataCoords() {
        val d = dest(
            "https://www.google.com/maps/place/Sydney+Opera+House/@-33.856784,151.213108,17z/data=!3m1!4b1!4m6!3m5!1s0x6b12ae665e892fdd:0x3133f8d75a1ac251!8m2!3d-33.8567844!4d151.2152967!16zL20vMDZfbms"
        )
        assertCoords(d, -33.8567844, 151.2152967)
        assertEquals("Sydney Opera House", d.label)
    }

    @Test fun mapsPlaceWithoutData() = assertEquals("Central Park", dest("https://www.google.com/maps/place/Central+Park/").query)

    @Test fun mapsDirPath() =
        assertEquals("Golden Gate Bridge", dest("https://www.google.com/maps/dir/My+Location/Golden+Gate+Bridge/@37.8,-122.4,12z/data=!4m2").query)

    @Test fun mapsAtOnly() = assertCoords(dest("https://www.google.com/maps/@52.5163,13.3777,15z"), 52.5163, 13.3777)

    @Test fun shortLinkNeedsExpansion() {
        val r = NavParser.parse("https://maps.app.goo.gl/AbCdEf123")
        assertTrue(r is ParseResult.NeedsExpansion)
    }

    @Test fun sharedTextWithShortLink() {
        val r = NavParser.parse("Joe's Diner\n123 Main St, Springfield\nhttps://maps.app.goo.gl/AbCdEf123")
        assertTrue(r is ParseResult.NeedsExpansion)
        assertEquals("Joe's Diner", (r as ParseResult.NeedsExpansion).label)
    }

    @Test fun sharedTextWithFullLink() {
        val d = dest("Check this out: https://maps.google.com/?q=35.6586,139.7454")
        assertCoords(d, 35.6586, 139.7454)
        assertEquals("Check this out:", d.label)
    }

    @Test fun sharedPlainAddress() =
        assertEquals("221B Baker Street, London", dest("221B Baker Street\nLondon").query)

    @Test fun sharedCoordsText() = assertCoords(dest("Meet here 47.6205, -122.3493"), 47.6205, -122.3493)

    @Test fun consentRedirect() = assertCoords(
        dest("https://consent.google.com/m?continue=https%3A%2F%2Fwww.google.com%2Fmaps%2F%4052.5163%2C13.3777%2C15z&gl=DE"),
        52.5163, 13.3777,
    )

    @Test fun outOfRangeCoordsAreQuery() = assertEquals(null, dest("geo:0,0?q=95.0,200.0").lat)

    @Test fun unrelatedUrlIsNothing() = assertEquals(ParseResult.None, NavParser.parse("https://example.com/page"))
}
