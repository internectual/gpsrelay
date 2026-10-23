package net.internectual.gpsrelay.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.Reader
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

    /**
     * Blocks until the next decodable message; null on EOF. Throws IOException on socket errors/timeouts, and on a
     * line longer than [MAX_LINE] so a peer can't make us buffer without limit.
     */
    fun read(): Msg? {
        while (true) {
            val line = readLine(reader, MAX_LINE) ?: return null
            Codec.decode(line)?.let { return it }
        }
    }

    fun close() {
        out.close()
        runCatching { socket.close() }
    }

    internal companion object {
        const val MAX_LINE = 64 * 1024

        /** Like [java.io.BufferedReader.readLine] for `\n` or `\r\n` endings, but gives up past [max] characters. */
        fun readLine(r: Reader, max: Int): String? {
            val sb = StringBuilder()
            while (true) {
                val c = r.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.removeSuffix("\r").toString()
                if (sb.length >= max) throw IOException("Line longer than $max characters")
                sb.append(c.toChar())
            }
        }
    }
}
