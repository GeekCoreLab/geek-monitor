package com.geekcorelab.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

    private fun service(
        allowPrivateTargets: Boolean = false,
        allowedCidrs: String = "none",
    ) = HttpCheckService().apply {
        this.allowPrivateTargets = allowPrivateTargets
        allowedCidrsConfig = allowedCidrs
    }
}
