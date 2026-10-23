package net.internectual.gpsrelay.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

class LocalPeersTest {
    private fun local(ip: String) = LocalPeers.isLocal(InetAddress.getByName(ip))

    @Test fun localAddresses() {
        listOf(
            "127.0.0.1", "::1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.43.1",
            "169.254.1.1", "100.64.0.1", "100.127.255.254", "fe80::1", "fd12:3456::1", "fc00::1",
        ).forEach { assertTrue(it, local(it)) }
    }

    @Test fun publicAddresses() {
        listOf(
            "8.8.8.8", "172.32.0.1", "100.63.255.255", "100.128.0.1", "2001:4860:4860::8888", "2a00:1450::1",
        ).forEach { assertFalse(it, local(it)) }
    }

    @Test fun ipv4MappedFollowsTheIpv4Address() {
        fun mapped(a: Int, b: Int, c: Int, d: Int) = Inet6Address.getByAddress(
            null, ByteArray(10) + byteArrayOf(-1, -1, a.toByte(), b.toByte(), c.toByte(), d.toByte()), null,
        )
        assertTrue(LocalPeers.isLocal(mapped(192, 168, 43, 5)))
        assertFalse(LocalPeers.isLocal(mapped(8, 8, 8, 8)))
    }
}
