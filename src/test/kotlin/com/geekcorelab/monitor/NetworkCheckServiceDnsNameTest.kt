package com.geekcorelab.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NetworkCheckServiceDnsNameTest {
    private val service = NetworkCheckService()

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
