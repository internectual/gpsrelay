package net.internectual.gpsrelay.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.internectual.gpsrelay.protocol.LineConnection
import net.internectual.gpsrelay.protocol.LocalPeers
import net.internectual.gpsrelay.protocol.Msg
import net.internectual.gpsrelay.protocol.PROTOCOL_VERSION
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TCP server that broadcasts fixes and destinations to every connected client.
 * The newest fix and any unacknowledged destination are replayed to clients as they connect.
 */
class RelayServer(
    private val port: Int,
    private val pin: String,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        fun onClientsChanged(remotes: List<String>)
        fun onAck(navId: String, status: String?, remote: String)
        fun onError(message: String)
        /** The destination still waiting for an ack, if any, to replay to a new client. */
        fun pendingNav(): Msg.Nav?
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val clients = CopyOnWriteArrayList<LineConnection>()
    @Volatile private var lastFix: Msg.FixMsg? = null

    fun start() {
        acceptJob = scope.launch(Dispatchers.IO) {
            val ss = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            } catch (e: IOException) {
                listener.onError("Can't listen on port $port: ${e.message}")
                return@launch
            }
            serverSocket = ss
            try {
                while (isActive) {
                    val s = try {
                        ss.accept()
                    } catch (e: IOException) {
                        break
                    }
                    if (!LocalPeers.isLocal(s.inetAddress)) {
                        runCatching { s.close() }
                        continue
                    }
                    launch { handle(s) }
                }
            } finally {
                // stop() may have run before the bind finished, so it couldn't close the socket itself.
                runCatching { ss.close() }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        acceptJob?.cancel()
        clients.forEach { it.close() }
        clients.clear()
        listener.onClientsChanged(emptyList())
    }

    fun broadcastFix(fix: Msg.FixMsg) {
        lastFix = fix
        clients.forEach { it.send(fix) }
    }

    /** Sends to all connected clients; returns how many received it. */
    fun sendNav(nav: Msg.Nav): Int {
        clients.forEach { it.send(nav) }
        return clients.size
    }

    private suspend fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = HELLO_TIMEOUT_MS
        val conn = LineConnection(socket, scope)
        try {
            val hello = conn.read() as? Msg.Hello
            if (hello == null) {
                conn.close()
                return
            }
            if (pin.isNotEmpty() && hello.token != pin) {
                conn.send(Msg.Error("bad_pin"))
                kotlinx.coroutines.delay(200)
                conn.close()
                return
            }
            socket.soTimeout = READ_TIMEOUT_MS
            conn.send(Msg.Hello(PROTOCOL_VERSION, "server", null))
            lastFix?.let { conn.send(it) }
            listener.pendingNav()?.let { conn.send(it) }
            clients += conn
            notifyClients()

            while (true) {
                when (val m = conn.read() ?: break) {
                    Msg.Ping -> conn.send(Msg.Pong)
                    is Msg.Ack -> listener.onAck(m.id, m.status, conn.remote)
                    else -> Unit
                }
            }
        } catch (e: IOException) {
            // Disconnected or timed out.
        } finally {
            conn.close()
            if (clients.remove(conn)) notifyClients()
        }
    }

    private fun notifyClients() = listener.onClientsChanged(clients.map { it.remote })

    private companion object {
        const val READ_TIMEOUT_MS = 20_000
        const val HELLO_TIMEOUT_MS = 10_000
    }
}
