package net.internectual.gpsrelay.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.internectual.gpsrelay.protocol.Codec
import net.internectual.gpsrelay.protocol.Destination
import net.internectual.gpsrelay.protocol.Fix
import net.internectual.gpsrelay.protocol.Msg
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Drives the real server over TCP with a raw socket client, like `nc` would. */
class RelayServerTest {
    private val port = 47999
    private lateinit var scope: CoroutineScope
    private lateinit var server: RelayServer
    private val acks = LinkedBlockingQueue<Pair<String, String?>>()
    private val clientCounts = LinkedBlockingQueue<Int>()
    @Volatile private var pending: Msg.Nav? = null

    private fun startServer(pin: String = "") {
        server = RelayServer(port, pin, scope, object : RelayServer.Listener {
            override fun onClientsChanged(remotes: List<String>) { clientCounts.put(remotes.size) }
            override fun onAck(navId: String, status: String?, remote: String) { acks.put(navId to status) }
            override fun onError(message: String) = throw AssertionError(message)
            override fun pendingNav(): Msg.Nav? = pending
        })
        server.start()
        Thread.sleep(300)
    }

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After fun tearDown() {
        server.stop()
        scope.cancel()
    }

    private class RawClient(port: Int) {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 3000 }
        private val r = socket.getInputStream().bufferedReader()
        private val w = socket.getOutputStream().bufferedWriter()
        fun send(m: Msg) { w.write(Codec.encode(m) + "\n"); w.flush() }
        fun read(): Msg? = r.readLine()?.let { Codec.decode(it) }
    }

    @Test fun handshakeFixNavAck() {
        pending = Msg.Nav("queued-1", Destination(query = "Queued Place"), "Queued Place")
        startServer()
        server.broadcastFix(Msg.FixMsg(Fix(10.0, 20.0, time = 1)))

        val c = RawClient(port)
        c.send(Msg.Hello(1, "client", null))
        assertTrue(c.read() is Msg.Hello)
        // Latest fix and pending destination are replayed on connect.
        assertEquals(10.0, (c.read() as Msg.FixMsg).fix.lat, 0.0)
        assertEquals("queued-1", (c.read() as Msg.Nav).id)
        assertEquals(1, clientCounts.poll(2, TimeUnit.SECONDS))

        server.broadcastFix(Msg.FixMsg(Fix(11.0, 21.0, acc = 3f, time = 2)))
        assertEquals(11.0, (c.read() as Msg.FixMsg).fix.lat, 0.0)

        val nav = Msg.Nav("n2", Destination(1.0, 2.0, label = "X"), "geo:1,2")
        assertEquals(1, server.sendNav(nav))
        assertEquals(nav, c.read())
        c.send(Msg.Ack("n2", "launched in Waze"))
        assertEquals("n2" to "launched in Waze", acks.poll(2, TimeUnit.SECONDS))

        c.send(Msg.Ping)
        assertEquals(Msg.Pong, c.read())

        c.socket.close()
        assertEquals(0, clientCounts.poll(2, TimeUnit.SECONDS))
    }

    @Test fun wrongPinIsRejected() {
        startServer(pin = "1234")
        val c = RawClient(port)
        c.send(Msg.Hello(1, "client", "9999"))
        assertEquals(Msg.Error("bad_pin"), c.read())
    }

    @Test fun rightPinIsAccepted() {
        startServer(pin = "1234")
        val c = RawClient(port)
        c.send(Msg.Hello(1, "client", "1234"))
        assertTrue(c.read() is Msg.Hello)
    }
}
