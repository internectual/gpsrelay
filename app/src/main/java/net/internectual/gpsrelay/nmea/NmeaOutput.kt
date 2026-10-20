package net.internectual.gpsrelay.nmea

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** The conventional NMEA-over-IP port. */
const val DEFAULT_NMEA_PORT = 10110

/** A UDP destination for NMEA datagrams. */
data class UdpTarget(val host: String, val port: Int) {
    override fun toString() = "$host:$port"

    companion object {
        /**
         * Parses `host`, `host:port` or a list of them separated by commas, semicolons or spaces.
         * Returns null if any entry is invalid; blank input is an empty list.
         */
        fun parseList(text: String, defaultPort: Int = DEFAULT_NMEA_PORT): List<UdpTarget>? {
            val out = ArrayList<UdpTarget>()
            for (token in text.split(',', ';', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }) {
                // A single colon separates host and port; more than one means an IPv6 literal with no port.
                val host: String
                val port: Int?
                if (token.count { it == ':' } == 1) {
                    val colon = token.indexOf(':')
                    host = token.substring(0, colon)
                    port = token.substring(colon + 1).toIntOrNull()
                } else {
                    host = token
                    port = defaultPort
                }
                if (host.isEmpty() || port == null || port !in 1..65535) return null
                out += UdpTarget(host, port)
            }
            return out
        }
    }
}

/**
 * Sends NMEA 0183 sentences to anything that wants them, with no handshake so gpsd, OpenCPN,
 * `nc` and `socat` can read it as-is:
 *  - TCP: listens on [tcpPort] and streams to every connected client.
 *  - UDP: sends each batch of sentences as one datagram to every entry in [udpTargets].
 *
 * Sends are queued and made from coroutines, so [publish] is safe to call from any thread.
 */
class NmeaOutput(
    private val scope: CoroutineScope,
    private val tcpPort: Int?,
    private val udpTargets: List<UdpTarget>,
    private val listener: Listener,
) {
    interface Listener {
        fun onClientsChanged(remotes: List<String>)
        fun onError(message: String)
    }

    private inner class Client(private val socket: Socket) {
        val remote: String = socket.inetAddress?.hostAddress ?: "?"
        private val out = Channel<String>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        init {
            scope.launch(Dispatchers.IO) {
                try {
                    val stream = socket.getOutputStream()
                    for (text in out) {
                        stream.write(text.toByteArray(Charsets.US_ASCII))
                        stream.flush()
                    }
                } catch (e: IOException) {
                    close()
                }
            }
        }

        fun send(text: String) {
            out.trySend(text)
        }

        /** Blocks until the peer disconnects. Clients aren't expected to send anything; input is discarded. */
        fun awaitClose() {
            val buf = ByteArray(256)
            val input = socket.getInputStream()
            while (input.read(buf) >= 0) Unit
        }

        fun close() {
            out.close()
            runCatching { socket.close() }
        }
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var udpJob: Job? = null
    private val clients = CopyOnWriteArrayList<Client>()
    private val udpQueue = Channel<String>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun start() {
        if (tcpPort != null) {
            acceptJob = scope.launch(Dispatchers.IO) {
                val ss = try {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(tcpPort))
                    }
                } catch (e: IOException) {
                    listener.onError("Can't listen for NMEA on port $tcpPort: ${e.message}")
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
                        launch(Dispatchers.IO) { handle(s) }
                    }
                } finally {
                    // stop() may have run before the bind finished, so it couldn't close the socket itself.
                    runCatching { ss.close() }
                }
            }
        }
        if (udpTargets.isNotEmpty()) {
            udpJob = scope.launch(Dispatchers.IO) { runUdp() }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        acceptJob?.cancel()
        udpJob?.cancel()
        udpQueue.close()
        clients.forEach { it.close() }
        clients.clear()
        listener.onClientsChanged(emptyList())
    }

    /** Sends [sentences] (each a complete `$...*HH` sentence without a terminator) to all outputs. */
    fun publish(sentences: List<String>) {
        if (sentences.isEmpty()) return
        val text = sentences.joinToString(separator = "") { it + "\r\n" }
        clients.forEach { it.send(text) }
        if (udpTargets.isNotEmpty()) udpQueue.trySend(text)
    }

    private fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        val client = Client(socket)
        clients += client
        notifyClients()
        try {
            client.awaitClose()
        } catch (e: IOException) {
            // Disconnected or reset.
        } finally {
            client.close()
            if (clients.remove(client)) notifyClients()
        }
    }

    private fun notifyClients() = listener.onClientsChanged(clients.map { it.remote })

    private suspend fun runUdp() {
        val resolved = HashMap<UdpTarget, InetAddress>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (text in udpQueue) {
                val bytes = text.toByteArray(Charsets.US_ASCII)
                for (target in udpTargets) {
                    try {
                        val addr = resolved.getOrPut(target) { InetAddress.getByName(target.host) }
                        socket.send(DatagramPacket(bytes, bytes.size, addr, target.port))
                    } catch (e: IOException) {
                        // Unreachable (cable unplugged, tethering off) or unresolvable: drop this datagram
                        // and try again on the next one.
                        resolved.remove(target)
                    }
                }
            }
        }
    }
}
