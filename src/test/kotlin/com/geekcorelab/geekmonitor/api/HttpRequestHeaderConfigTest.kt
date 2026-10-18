package com.geekcorelab.geekmonitor.api

import com.geekcorelab.geekmonitor.security.SecretEncryptionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class HttpRequestHeaderConfigTest {
    private val encryption = SecretEncryptionService().apply {
        keyBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
    }

    @Test
    fun `encrypts header values and exposes only configured names`() {
        val encrypted = HttpRequestHeaderConfig.encrypt(
            mapOf("Authorization" to "Bearer private-token"),
            emptyMap(),
            encryption,
        )!!

        assertNotEquals("Bearer private-token", encrypted["authorization"])
        assertEquals("Bearer private-token", encryption.decrypt(encrypted.getValue("authorization")))
        assertEquals(mapOf("authorization" to ""), HttpRequestHeaderConfig.visibleNames(encrypted))
    }

    @Test
    fun `blank values retain old secrets while removing the row deletes them`() {
        val old = mapOf("authorization" to encryption.encrypt("Bearer old"))

        val retained = HttpRequestHeaderConfig.encrypt(mapOf("Authorization" to ""), old, encryption)
        val removed = HttpRequestHeaderConfig.encrypt(emptyMap(), old, encryption)

        assertEquals(old, retained)
        assertEquals(emptyMap<String, String>(), removed)
    }

    @Test
    fun `rejects restricted headers duplicate names and control characters`() {
        assertNull(HttpRequestHeaderConfig.encrypt(mapOf("Host" to "example.com"), emptyMap(), encryption))
        assertNull(HttpRequestHeaderConfig.encrypt(mapOf("X-Test" to "safe\r\nInjected: yes"), emptyMap(), encryption))
        assertNull(
            HttpRequestHeaderConfig.encrypt(
                linkedMapOf("X-Test" to "one", "x-test" to "two"),
                emptyMap(),
                encryption,
            ),
        )
        assertNull(
            HttpRequestHeaderConfig.encrypt(
                (1..5).associate { "X-$it" to "v".repeat(4096) },
                emptyMap(),
                encryption,
            ),
        )
        assertTrue(HttpRequestHeaderConfig.encrypt(null, emptyMap(), encryption)!!.isEmpty())
    }
}
