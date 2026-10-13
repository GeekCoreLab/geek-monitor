package com.geekcorelab.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class NetworkCheckServiceTlsTest {
    @Test
    fun `TLS check classifies an untrusted server certificate`() {
        val address = privateIpv4Address()
        assumeTrue(address != null, "No private IPv4 interface is available for the local TLS smoke test")
        val listener = testTlsListener()
        listener.bind(InetSocketAddress("0.0.0.0", 0))
        val server = Thread {
            runCatching { (listener.accept() as SSLSocket).use { it.startHandshake() } }
        }.apply { isDaemon = true; start() }

        try {
            val result = checker().tls(address!!.hostAddress, listener.localPort, minimumValidityDays = 0, timeoutSeconds = 3)

            assertEquals("DOWN", result.status)
            assertEquals("TLS_FAILURE", result.errorCode)
        } finally {
            listener.close()
            server.join(3_500)
        }
    }

    @Test
    fun `TLS check classifies a stalled handshake as timeout`() {
        val address = privateIpv4Address()
        assumeTrue(address != null, "No private IPv4 interface is available for the local TLS smoke test")
        val listener = ServerSocket()
        listener.bind(InetSocketAddress("0.0.0.0", 0))
        val server = Thread {
            runCatching { listener.accept().use { Thread.sleep(2_000) } }
        }.apply { isDaemon = true; start() }

        try {
            val result = checker().tls(address!!.hostAddress, listener.localPort, minimumValidityDays = 0, timeoutSeconds = 1)

            assertEquals("DOWN", result.status)
            assertEquals("TIMEOUT", result.errorCode)
        } finally {
            listener.close()
            server.join(2_500)
        }
    }

    private fun checker() = NetworkCheckService().apply {
        addressPolicy = HttpCheckService().apply {
            allowPrivateTargets = true
            allowedCidrsConfig = "none"
        }
    }

    private fun testTlsListener(): SSLServerSocket {
        val keyStore = KeyStore.getInstance("PKCS12")
        val password = "test-password".toCharArray()
        javaClass.getResourceAsStream("/monitor-test-cert.p12")!!.use { keyStore.load(it, password) }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }
        val context = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, SecureRandom()) }
        return context.serverSocketFactory.createServerSocket() as SSLServerSocket
    }

    private fun privateIpv4Address() = NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
}
