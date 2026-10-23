package net.internectual.gpsrelay.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.io.Reader
import java.io.StringReader

class LineConnectionTest {
    private fun read(r: Reader, max: Int = 16) = LineConnection.readLine(r, max)

    @Test fun splitsLines() {
        val r = StringReader("one\ntwo\r\n\nlast")
        assertEquals("one", read(r))
        assertEquals("two", read(r))
        assertEquals("", read(r))
        assertEquals("last", read(r))
        assertNull(read(r))
    }

    @Test(expected = IOException::class)
    fun rejectsOverlongLine() {
        read(StringReader("x".repeat(17) + "\n"))
    }

    @Test(expected = IOException::class)
    fun endlessLineFailsWithoutBufferingIt() {
        // Never ends and never sends a newline, like `yes | tr -d '\n' | nc`.
        val endless = object : Reader() {
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                cbuf.fill('y', off, off + len)
                return len
            }
            override fun close() = Unit
        }
        read(endless, LineConnection.MAX_LINE)
    }
}
