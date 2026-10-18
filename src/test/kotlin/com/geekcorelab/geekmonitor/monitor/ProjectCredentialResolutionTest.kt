package com.geekcorelab.geekmonitor.monitor

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekcorelab.geekmonitor.api.ProjectCredentialOrigins
import com.geekcorelab.geekmonitor.security.SecretEncryptionService
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

class ProjectCredentialResolutionTest {
    @Test
    fun `project credential reference is resolved and decrypted only for its resource`() {
        val targetAddress = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
        assumeTrue(targetAddress != null, "No private IPv4 interface is available for the local HTTP smoke test")
        val targetHost = targetAddress?.hostAddress ?: return

        val encryption = SecretEncryptionService().apply {
            keyBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
        }
        val resourceId = UUID.randomUUID()
        val credentialId = UUID.randomUUID()
        val authorization = AtomicReference<String?>()
        val requestCount = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0)
        server.createContext("/") { exchange ->
            requestCount.incrementAndGet()
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            val response = "ready".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val url = "http://$targetHost:${server.address.port}/"
            val allowedOriginsJson = ObjectMapper().writeValueAsString(
                listOf(ProjectCredentialOrigins.fromUrl(url)),
            )
            val encryptedJson = ObjectMapper().writeValueAsString(
                mapOf("authorization" to encryption.encrypt("Bearer shared-test-token")),
            )
            val checker = ProtocolCheckService().apply {
                httpChecker = HttpCheckService().apply {
                    allowPrivateTargets = true
                    allowedCidrsConfig = "none"
                    secretEncryption = encryption
                }
                dataSource = credentialDataSource(encryptedJson, allowedOriginsJson, credentialId, resourceId)
                objectMapper = ObjectMapper()
            }
            val validConfig = ObjectMapper().readTree(
                """{"url":"$url","resourceId":"$resourceId","credentialId":"$credentialId","method":"GET","expectedStatusCodes":[200],"textContains":"ready"}""",
            )

            val accepted = checker.execute("HTTP", validConfig, 3)

            assertEquals("UP", accepted.status)
            assertEquals("Bearer shared-test-token", authorization.get())
            assertEquals(1, requestCount.get())

            val unapprovedUrl = "http://$targetHost:${server.address.port + 1}/"
            val otherOriginConfig = ObjectMapper().readTree(
                """{"url":"$unapprovedUrl","resourceId":"$resourceId","credentialId":"$credentialId","method":"GET","expectedStatusCodes":[200]}""",
            )
            val forbiddenOrigin = checker.execute("HTTP", otherOriginConfig, 3)

            assertEquals("UNKNOWN", forbiddenOrigin.status)
            assertEquals("CREDENTIAL_ORIGIN_FORBIDDEN", forbiddenOrigin.errorCode)
            assertEquals(1, requestCount.get())

            val otherResourceConfig = ObjectMapper().readTree(
                """{"url":"$url","resourceId":"${UUID.randomUUID()}","credentialId":"$credentialId","method":"GET","expectedStatusCodes":[200]}""",
            )
            val rejected = checker.execute("HTTP", otherResourceConfig, 3)

            assertEquals("UNKNOWN", rejected.status)
            assertEquals("CREDENTIAL_REFERENCE_INVALID", rejected.errorCode)
            assertEquals(1, requestCount.get())
        } finally {
            server.stop(0)
        }
    }

    private fun credentialDataSource(
        encryptedJson: String,
        allowedOriginsJson: String,
        expectedCredentialId: UUID,
        expectedResourceId: UUID,
    ): DataSource = proxy(DataSource::class.java) { method, _ ->
        when (method.name) {
            "getConnection" -> credentialConnection(
                encryptedJson,
                allowedOriginsJson,
                expectedCredentialId,
                expectedResourceId,
            )
            else -> defaultValue(method.returnType)
        }
    }

    private fun credentialConnection(
        encryptedJson: String,
        allowedOriginsJson: String,
        expectedCredentialId: UUID,
        expectedResourceId: UUID,
    ): Connection {
        val parameters = mutableMapOf<Int, Any?>()
        return proxy(Connection::class.java) { method, args ->
            when (method.name) {
                "prepareStatement" -> proxy(PreparedStatement::class.java) { statementMethod, statementArgs ->
                    when (statementMethod.name) {
                        "setObject" -> {
                            parameters[statementArgs!![0] as Int] = statementArgs[1]
                            null
                        }
                        "executeQuery" -> {
                            val present = parameters[1] == expectedCredentialId && parameters[2] == expectedResourceId
                            var available = present
                            proxy(ResultSet::class.java) { resultMethod, resultArgs ->
                                when (resultMethod.name) {
                                    "next" -> available.also { available = false }
                                    "getString" -> when (resultArgs?.firstOrNull()) {
                                        1 -> encryptedJson
                                        2 -> allowedOriginsJson
                                        else -> null
                                    }
                                    else -> defaultValue(resultMethod.returnType)
                                }
                            }
                        }
                        else -> defaultValue(statementMethod.returnType)
                    }
                }
                else -> defaultValue(method.returnType)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, handler: (Method, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> handler(method, args) } as T

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
