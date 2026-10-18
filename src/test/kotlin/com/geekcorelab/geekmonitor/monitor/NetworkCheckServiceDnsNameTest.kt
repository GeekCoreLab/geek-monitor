package com.geekcorelab.geekmonitor.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path

class NetworkCheckServiceDnsNameTest {
    private val service = NetworkCheckService()

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `system DNS servers are read from resolv conf and invalid entries are ignored`() {
        val resolvConf = tempDir.resolve("resolv.conf")
        Files.writeString(
            resolvConf,
            """
                # container DNS settings
                nameserver 127.0.0.11
                nameserver invalid.example
                nameserver 1.1.1.1 ; backup
            """.trimIndent(),
        )

        assertEquals(
            listOf("127.0.0.11", "1.1.1.1"),
            service.systemDnsServers(resolvConf).map(InetAddress::getHostAddress),
        )
    }

    @Test
    fun `system DNS servers fall back to loopback when resolv conf is missing`() {
        assertEquals(
            listOf(InetAddress.getLoopbackAddress().hostAddress),
            service.systemDnsServers(tempDir.resolve("missing-resolv.conf")).map(InetAddress::getHostAddress),
        )
    }

    @Test
    fun `IPv6 PTR name reverses all address nibbles`() {
        assertEquals(
            "1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.b.d.0.1.0.0.2.ip6.arpa",
            service.normalizeDnsName("2001:db8::1", "PTR"),
        )
    }

    @Test
    fun `IPv4 PTR name reverses address octets`() {
        assertEquals("1.2.0.192.in-addr.arpa", service.normalizeDnsName("192.0.2.1", "PTR"))
    }
}
