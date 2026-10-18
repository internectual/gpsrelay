package net.internectual.gpsrelay.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Socket

/**
 * One side of a relay TCP connection: newline-delimited JSON in both directions.
 * Writes are queued and sent from a coroutine so callers never block on the network.
 */
class LineConnection(private val socket: Socket, scope: CoroutineScope) {
    val remote: String = socket.inetAddress?.hostAddress ?: "?"

    private val out = Channel<String>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
    private val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

    init {
        scope.launch(Dispatchers.IO) {
            try {
                for (line in out) {
                    writer.write(line)
                    writer.write('\n'.code)
                    writer.flush()
                }
            } catch (e: IOException) {
                close()
            }
        }
    }

    fun send(m: Msg) {
        out.trySend(Codec.encode(m))
    }

    /** Blocks until the next decodable message; null on EOF. Throws IOException on socket errors/timeouts. */
    fun read(): Msg? {
        while (true) {
            val line = reader.readLine() ?: return null
            if (line.length > MAX_LINE) continue
            Codec.decode(line)?.let { return it }
        }
    }

    fun close() {
        out.close()
        runCatching { socket.close() }
    }

    private companion object {
        const val MAX_LINE = 64 * 1024
    }
}
