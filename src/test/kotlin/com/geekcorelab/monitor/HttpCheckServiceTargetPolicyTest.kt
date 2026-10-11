package com.geekcorelab.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.geekcorelab.security.SecretEncryptionService
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

class HttpCheckServiceTargetPolicyTest {
    @Test
    fun `private targets require explicit policy`() {
        val service = service()

        val resolution = service.resolveAllowedTarget("10.23.4.5")

        assertTrue(resolution.addresses.isEmpty())
        assertEquals("TARGET_BLOCKED", resolution.errorCode)
    }

    @Test
    fun `cidr allowlist permits only addresses inside the configured network`() {
        val service = service(allowedCidrs = "10.23.0.0/16")

        val allowed = service.resolveAllowedTarget("10.23.4.5")
        val outside = service.resolveAllowedTarget("10.24.4.5")

        assertEquals(null, allowed.errorCode)
        assertEquals("10.23.4.5", allowed.addresses.single().hostAddress)
        assertTrue(outside.addresses.isEmpty())
        assertEquals("TARGET_BLOCKED", outside.errorCode)
    }

    @Test
    fun `explicit private target setting does not allow loopback or link local addresses`() {
        val service = service(allowPrivateTargets = true, allowedCidrs = "0.0.0.0/0")

        val loopback = service.resolveAllowedTarget("127.0.0.1")
        val linkLocal = service.resolveAllowedTarget("169.254.169.254")

        assertEquals("TARGET_BLOCKED", loopback.errorCode)
        assertEquals("TARGET_BLOCKED", linkLocal.errorCode)
    }

    @Test
    fun `invalid and blocked literal checks return unknown without making a request`() {
        val service = service()

        val invalid = service.execute("file:///etc/passwd", "GET", 2, setOf(200), null)
        val blocked = service.execute("http://127.0.0.1/", "GET", 2, setOf(200), null)

        assertEquals("UNKNOWN", invalid.status)
        assertEquals("TARGET_INVALID", invalid.errorCode)
        assertEquals("UNKNOWN", blocked.status)
        assertEquals("TARGET_BLOCKED", blocked.errorCode)
    }

    @Test
    fun `invalid JSON assertion configuration is rejected before network access`() {
        val result = service().execute(
            targetUrl = "http://127.0.0.1/",
            method = "GET",
            timeoutSeconds = 2,
            expectedStatusCodes = setOf(200),
            textContains = null,
            jsonPath = "/status",
            jsonExpectedValue = "ok",
        )

        assertEquals("UNKNOWN", result.status)
        assertEquals("CHECK_CONFIG_INVALID", result.errorCode)
    }

    @Test
    fun `HTTP check decrypts configured headers and evaluates JSON response`() {
        val targetAddress = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
        assumeTrue(targetAddress != null, "No private IPv4 interface is available for the local HTTP smoke test")

        val authorization = AtomicReference<String?>()
        val customHeader = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0)
        server.createContext("/") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            customHeader.set(exchange.requestHeaders.getFirst("X-Monitor-Test"))
            val response = """{"ready":true,"count":2}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val encryption = SecretEncryptionService().apply {
                keyBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
            }
            val encryptedHeaders = mapOf(
                "authorization" to encryption.encrypt("Bearer synthetic-test-token"),
                "x-monitor-test" to encryption.encrypt("smoke"),
            )
            val checker = service(allowPrivateTargets = true).apply { secretEncryption = encryption }

            val result = checker.execute(
                targetUrl = "http://${targetAddress!!.hostAddress}:${server.address.port}/",
                method = "GET",
                timeoutSeconds = 3,
                expectedStatusCodes = setOf(200),
                textContains = null,
                jsonPath = "/ready",
                jsonExpectedValue = "true",
                encryptedRequestHeaders = encryptedHeaders,
            )

            assertEquals("UP", result.status)
            assertEquals("Bearer synthetic-test-token", authorization.get())
            assertEquals("smoke", customHeader.get())
        } finally {
            server.stop(0)
        }
    }

    private fun service(
        allowPrivateTargets: Boolean = false,
        allowedCidrs: String = "none",
    ) = HttpCheckService().apply {
        this.allowPrivateTargets = allowPrivateTargets
        allowedCidrsConfig = allowedCidrs
    }
}
