package com.geekcorelab.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

class NetworkCheckServiceTcpTest {
    @Test
    fun `TCP check succeeds against an open local service socket`() {
        val address = privateIpv4Address()
        assumeTrue(address != null, "No private IPv4 interface is available for the local TCP smoke test")
        ServerSocket().use { listener ->
            listener.bind(InetSocketAddress("0.0.0.0", 0))

            val result = checker(allowPrivateTargets = true).tcp(address!!.hostAddress, listener.localPort, 2)

            assertEquals("UP", result.status)
            assertEquals(null, result.errorCode)
        }
    }

    @Test
    fun `TCP check classifies a closed local port as refused`() {
        val address = privateIpv4Address()
        assumeTrue(address != null, "No private IPv4 interface is available for the local TCP smoke test")
        val closedPort = ServerSocket(0).use(ServerSocket::getLocalPort)

        val result = checker(allowPrivateTargets = true).tcp(address!!.hostAddress, closedPort, 2)

        assertEquals("DOWN", result.status)
        assertEquals("CONNECTION_REFUSED", result.errorCode)
    }

    @Test
    fun `TCP check rejects a private destination when private targets are disabled`() {
        val address = privateIpv4Address()
        assumeTrue(address != null, "No private IPv4 interface is available for the local TCP smoke test")

        val result = checker().tcp(address!!.hostAddress, 5432, 2)

        assertEquals("UNKNOWN", result.status)
        assertEquals("TARGET_BLOCKED", result.errorCode)
    }

    private fun checker(allowPrivateTargets: Boolean = false) = NetworkCheckService().apply {
        addressPolicy = HttpCheckService().apply {
            this.allowPrivateTargets = allowPrivateTargets
            allowedCidrsConfig = "none"
        }
    }

    private fun privateIpv4Address() = NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
}
