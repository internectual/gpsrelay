package net.internectual.gpsrelay.protocol

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Which peers may connect to the relay and NMEA ports. The sockets listen on every interface, and on cellular
 * the device may have a public IPv6 address, so anything that isn't on a local network is turned away.
 */
object LocalPeers {
    fun isLocal(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress) return true
        val b = addr.address
        return when (addr) {
            // CGNAT 100.64.0.0/10: some hotspots hand these out.
            is Inet4Address -> b[0].toInt() and 0xff == 100 && b[1].toInt() and 0xc0 == 64
            // Unique local fc00::/7, or an IPv4-mapped address that is itself local.
            is Inet6Address -> b[0].toInt() and 0xfe == 0xfc ||
                (isV4Mapped(b) && isLocal(InetAddress.getByAddress(b.copyOfRange(12, 16))))
            else -> false
        }
    }

    private fun isV4Mapped(b: ByteArray) =
        (0 until 10).all { b[it].toInt() == 0 } && b[10].toInt() and 0xff == 0xff && b[11].toInt() and 0xff == 0xff
}
