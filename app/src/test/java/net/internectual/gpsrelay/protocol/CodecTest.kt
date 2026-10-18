package net.internectual.gpsrelay.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecTest {
    private fun roundTrip(m: Msg) = assertEquals(m, Codec.decode(Codec.encode(m)))

    @Test fun hello() = roundTrip(Msg.Hello(1, "client", "1234"))
    @Test fun helloNoToken() = roundTrip(Msg.Hello(1, "server", null))
    @Test fun fullFix() = roundTrip(Msg.FixMsg(Fix(1.5, -2.25, 100.0, 4.5f, 12.25f, 270.5f, 1_700_000_000_000, 9)))
    @Test fun minimalFix() = roundTrip(Msg.FixMsg(Fix(lat = 1.0, lon = 2.0, time = 5)))
    @Test fun nav() = roundTrip(Msg.Nav("abc", Destination(1.0, 2.0, "Main St", "Home"), "geo:1,2", "com.example"))
    @Test fun navQueryOnly() = roundTrip(Msg.Nav("abc", Destination(query = "Main St"), "Main St"))
    @Test fun ack() = roundTrip(Msg.Ack("abc", "launched in Waze"))
    @Test fun ping() = roundTrip(Msg.Ping)
    @Test fun pong() = roundTrip(Msg.Pong)
    @Test fun error() = roundTrip(Msg.Error("bad_pin"))

    @Test fun encodingIsOneLine() = assertEquals(false, Codec.encode(Msg.Nav("x", Destination(query = "a\nb"), "a\nb")).contains('\n'))
    @Test fun garbageIsNull() = assertNull(Codec.decode("not json"))
    @Test fun unknownTypeIsNull() = assertNull(Codec.decode("""{"t":"wat"}"""))
}
