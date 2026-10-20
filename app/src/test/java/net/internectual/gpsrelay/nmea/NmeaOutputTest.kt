package net.internectual.gpsrelay.nmea

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Drives the real outputs over loopback with plain sockets, like `nc` and `socat` would. */
class NmeaOutputTest {
    private val tcpPort = 47998
    private lateinit var scope: CoroutineScope
    private var output: NmeaOutput? = null
    private val clientCounts = LinkedBlockingQueue<Int>()

    private val gga = NmeaSentences.frame("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,")
    private val rmc = NmeaSentences.frame("GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W")

    private fun start(tcp: Int? = null, udp: List<UdpTarget> = emptyList()) {
        output = NmeaOutput(scope, tcp, udp, object : NmeaOutput.Listener {
            override fun onClientsChanged(remotes: List<String>) { clientCounts.put(remotes.size) }
            override fun onError(message: String) = throw AssertionError(message)
        }).also { it.start() }
        Thread.sleep(300)
    }

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After fun tearDown() {
        output?.stop()
        scope.cancel()
    }

    @Test fun tcpClientsReceiveSentencesAsPlainLines() {
        start(tcp = tcpPort)
        val a = Socket("127.0.0.1", tcpPort).apply { soTimeout = 3000 }
        val b = Socket("127.0.0.1", tcpPort).apply { soTimeout = 3000 }
        assertEquals(1, clientCounts.poll(2, TimeUnit.SECONDS))
        assertEquals(2, clientCounts.poll(2, TimeUnit.SECONDS))

        output!!.publish(listOf(gga, rmc))
        listOf(a, b).forEach { s ->
            val r = s.getInputStream().bufferedReader(Charsets.US_ASCII)
            assertEquals(gga, r.readLine())
            assertEquals(rmc, r.readLine())
        }

        a.close()
        assertEquals(1, clientCounts.poll(2, TimeUnit.SECONDS))
        // The remaining client keeps receiving.
        output!!.publish(listOf(gga))
        assertEquals(gga, b.getInputStream().bufferedReader(Charsets.US_ASCII).readLine())
    }

    @Test fun sentencesAreCrLfTerminated() {
        start(tcp = tcpPort)
        val s = Socket("127.0.0.1", tcpPort).apply { soTimeout = 3000 }
        assertEquals(1, clientCounts.poll(2, TimeUnit.SECONDS))
        output!!.publish(listOf(gga))
        val expected = (gga + "\r\n").toByteArray(Charsets.US_ASCII)
        val got = ByteArray(expected.size)
        var n = 0
        while (n < got.size) n += s.getInputStream().read(got, n, got.size - n)
        assertEquals(expected.toList(), got.toList())
    }

    @Test fun udpTargetsReceiveOneDatagramPerBatch() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { rx ->
            rx.soTimeout = 3000
            start(udp = listOf(UdpTarget("127.0.0.1", rx.localPort)))
            output!!.publish(listOf(gga, rmc))
            val buf = ByteArray(1024)
            val p = DatagramPacket(buf, buf.size)
            rx.receive(p)
            assertEquals(gga + "\r\n" + rmc + "\r\n", String(p.data, 0, p.length, Charsets.US_ASCII))
        }
    }

    @Test fun udpAndTcpRunTogether() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { rx ->
            rx.soTimeout = 3000
            start(tcp = tcpPort, udp = listOf(UdpTarget("127.0.0.1", rx.localPort)))
            val s = Socket("127.0.0.1", tcpPort).apply { soTimeout = 3000 }
            assertEquals(1, clientCounts.poll(2, TimeUnit.SECONDS))
            output!!.publish(listOf(gga))
            assertEquals(gga, s.getInputStream().bufferedReader(Charsets.US_ASCII).readLine())
            val p = DatagramPacket(ByteArray(256), 256)
            rx.receive(p)
            assertEquals(gga + "\r\n", String(p.data, 0, p.length, Charsets.US_ASCII))
        }
    }

    @Test fun publishWithNobodyListeningIsHarmless() {
        start(tcp = tcpPort)
        output!!.publish(listOf(gga))
        output!!.publish(emptyList())
        assertNull(clientCounts.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun parsesUdpTargetLists() {
        assertEquals(emptyList<UdpTarget>(), UdpTarget.parseList(""))
        assertEquals(listOf(UdpTarget("192.168.42.100", 10110)), UdpTarget.parseList("192.168.42.100"))
        assertEquals(
            listOf(UdpTarget("192.168.42.100", 4000), UdpTarget("laptop.local", 10110), UdpTarget("255.255.255.255", 10110)),
            UdpTarget.parseList("192.168.42.100:4000, laptop.local;255.255.255.255:10110"),
        )
        assertEquals(listOf(UdpTarget("fe80::1", 10110)), UdpTarget.parseList("fe80::1"))
        assertNull(UdpTarget.parseList("host:notaport"))
        assertNull(UdpTarget.parseList("host:0"))
        assertNull(UdpTarget.parseList("host:70000"))
        assertNull(UdpTarget.parseList(":4000"))
        assertNotNull(UdpTarget.parseList("a:1,b:2"))
    }
}
