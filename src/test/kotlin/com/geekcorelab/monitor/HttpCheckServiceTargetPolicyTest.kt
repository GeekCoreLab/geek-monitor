package com.geekcorelab.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.geekcorelab.security.SecretEncryptionService
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
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
    fun `HTTP checks classify status assertions timeouts and redirect limits`() {
        val targetAddress = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
        assumeTrue(targetAddress != null, "No private IPv4 interface is available for the local HTTP smoke test")

        val server = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0)
        server.createContext("/") { exchange ->
            try {
                val path = exchange.requestURI.path
                when {
                    path == "/error" -> {
                        val body = "private-response-marker".toByteArray()
                        exchange.sendResponseHeaders(500, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                    path == "/slow" -> {
                        Thread.sleep(1_500)
                        val body = "ready".toByteArray()
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                    path.startsWith("/redirect/") -> {
                        val parts = path.split('/')
                        val limit = parts[2].toInt()
                        val index = parts[3].toInt()
                        if (index < limit) {
                            exchange.responseHeaders.add("Location", "/redirect/$limit/${index + 1}")
                            exchange.sendResponseHeaders(302, -1)
                            exchange.close()
                        } else {
                            val body = "ready".toByteArray()
                            exchange.sendResponseHeaders(200, body.size.toLong())
                            exchange.responseBody.use { it.write(body) }
                        }
                    }
                    else -> {
                        val body = "ready".toByteArray()
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                }
            } catch (_: IOException) {
                exchange.close()
            }
        }
        server.start()
        try {
            val checker = service(allowPrivateTargets = true)
            val baseUrl = "http://${targetAddress!!.hostAddress}:${server.address.port}"

            val statusFailure = checker.execute("$baseUrl/error", "GET", 3, setOf(200), null)
            assertEquals("DOWN", statusFailure.status)
            assertEquals("HTTP_STATUS", statusFailure.errorCode)
            assertEquals(500, statusFailure.statusCode)
            assertFalse(statusFailure.summary.orEmpty().contains("private-response-marker"))

            val bodyFailure = checker.execute("$baseUrl/ready", "GET", 3, setOf(200), "missing-marker")
            assertEquals("DOWN", bodyFailure.status)
            assertEquals("ASSERTION_FAILED", bodyFailure.errorCode)

            val fiveRedirects = checker.execute("$baseUrl/redirect/5/0", "GET", 3, setOf(200), null)
            assertEquals("UP", fiveRedirects.status)

            val sixRedirects = checker.execute("$baseUrl/redirect/6/0", "GET", 3, setOf(200), null)
            assertEquals("DOWN", sixRedirects.status)
            assertEquals("TOO_MANY_REDIRECTS", sixRedirects.errorCode)

            val timeout = checker.execute("$baseUrl/slow", "GET", 1, setOf(200), null)
            assertEquals("DOWN", timeout.status)
            assertEquals("TIMEOUT", timeout.errorCode)
            assertTrue(timeout.durationMs < 3_000)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `HTTP client pins validated DNS answer and rechecks it on the next request`() {
        val targetAddress = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
        assumeTrue(targetAddress != null, "No private IPv4 interface is available for the local HTTP smoke test")

        val blockedAddress = InetAddress.getByAddress(
            byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte()),
        )
        val currentAnswer = AtomicReference<List<java.net.InetAddress>>(listOf(targetAddress!!))
        val lookupCount = AtomicInteger()
        val requestCount = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0)
        server.createContext("/") { exchange ->
            requestCount.incrementAndGet()
            val response = "ready".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val checker = service(allowPrivateTargets = true).apply {
                addressResolver = { hostname ->
                    assertEquals("dns-rebind.test", hostname)
                    val answer = currentAnswer.get()
                    if (lookupCount.incrementAndGet() == 1) currentAnswer.set(listOf(blockedAddress))
                    answer
                }
            }
            val url = "http://dns-rebind.test:${server.address.port}/"

            val pinnedAnswer = checker.execute(url, "GET", 3, setOf(200), "ready")

            assertEquals("UP", pinnedAnswer.status)
            assertEquals(1, lookupCount.get())
            assertEquals(1, requestCount.get())

            val blockedAnswer = checker.execute(url, "GET", 3, setOf(200), "ready")

            assertEquals("UNKNOWN", blockedAnswer.status)
            assertEquals("TARGET_BLOCKED", blockedAnswer.errorCode)
            assertEquals(2, lookupCount.get())
            assertEquals(1, requestCount.get())

            currentAnswer.set(listOf(targetAddress))
            val recovered = checker.execute(url, "GET", 3, setOf(200), "ready")

            assertEquals("UP", recovered.status)
            assertEquals(3, lookupCount.get())
            assertEquals(2, requestCount.get())
        } finally {
            server.stop(0)
        }
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

    @Test
    fun `POST requires explicit no-side-effect confirmation and sends an empty body`() {
        val targetAddress = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
        assumeTrue(targetAddress != null, "No private IPv4 interface is available for the local HTTP smoke test")

        val requestCount = AtomicInteger()
        val method = AtomicReference<String?>()
        val bodySize = AtomicInteger(-1)
        val server = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0)
        server.createContext("/") { exchange ->
            requestCount.incrementAndGet()
            method.set(exchange.requestMethod)
            bodySize.set(exchange.requestBody.use { it.readAllBytes().size })
            if (exchange.requestURI.path == "/redirect") {
                exchange.responseHeaders.add("Location", "/")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
                return@createContext
            }
            val response = "ready".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val checker = service(allowPrivateTargets = true)
            val url = "http://${targetAddress!!.hostAddress}:${server.address.port}/"
            val refused = checker.execute(url, "POST", 3, setOf(200), null)

            assertEquals("UNKNOWN", refused.status)
            assertEquals("CHECK_CONFIG_INVALID", refused.errorCode)
            assertEquals(0, requestCount.get())

            val accepted = checker.execute(
                targetUrl = url,
                method = "POST",
                timeoutSeconds = 3,
                expectedStatusCodes = setOf(200),
                textContains = "ready",
                allowPostNoSideEffects = true,
            )

            assertEquals("UP", accepted.status)
            assertEquals(1, requestCount.get())
            assertEquals("POST", method.get())
            assertEquals(0, bodySize.get())

            val redirect = checker.execute(
                targetUrl = "$url/redirect",
                method = "POST",
                timeoutSeconds = 3,
                expectedStatusCodes = setOf(302),
                textContains = null,
                allowPostNoSideEffects = true,
            )
            assertEquals("UP", redirect.status)
            assertEquals(2, requestCount.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `encrypted request headers are dropped across origins`() {
        val targetAddress = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
        assumeTrue(targetAddress != null, "No private IPv4 interface is available for the local HTTP smoke test")

        val forwardedAuthorization = AtomicReference<String?>()
        val destination = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0)
        destination.createContext("/") { exchange ->
            forwardedAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
            val response = "ready".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        destination.start()

        val redirect = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0)
        redirect.createContext("/") { exchange ->
            exchange.responseHeaders.add(
                "Location",
                "http://${targetAddress!!.hostAddress}:${destination.address.port}/",
            )
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        redirect.start()
        try {
            val encryption = SecretEncryptionService().apply {
                keyBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
            }
            val checker = service(allowPrivateTargets = true).apply { secretEncryption = encryption }
            val result = checker.execute(
                targetUrl = "http://${targetAddress!!.hostAddress}:${redirect.address.port}/",
                method = "GET",
                timeoutSeconds = 3,
                expectedStatusCodes = setOf(200),
                textContains = "ready",
                encryptedRequestHeaders = mapOf(
                    "authorization" to encryption.encrypt("Bearer synthetic-test-token"),
                ),
            )

            assertEquals("UP", result.status)
            assertEquals(null, forwardedAuthorization.get())
        } finally {
            redirect.stop(0)
            destination.stop(0)
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
