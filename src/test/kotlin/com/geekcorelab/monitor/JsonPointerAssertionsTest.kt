package com.geekcorelab.monitor

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonPointerAssertionsTest {
    @Test
    fun `matches typed values at object and array paths`() {
        val body = """{"status":{"ready":true},"checks":[{"code":200}],"label":"ok"}""".toByteArray()

        assertTrue(JsonPointerAssertions.matches(body, "/status/ready", "true"))
        assertTrue(JsonPointerAssertions.matches(body, "/checks/0/code", "200"))
        assertTrue(JsonPointerAssertions.matches(body, "/label", "\"ok\""))
    }

    @Test
    fun `supports escaped pointer segments and rejects missing or mismatched values`() {
        val body = """{"a/b":{"~key":"value"}}""".toByteArray()

        assertTrue(JsonPointerAssertions.matches(body, "/a~1b/~0key", "\"value\""))
        assertFalse(JsonPointerAssertions.matches(body, "/missing", "null"))
        assertFalse(JsonPointerAssertions.matches(body, "/a~1b/~0key", "\"other\""))
        assertFalse(JsonPointerAssertions.matches("not json".toByteArray(), "/value", "1"))
    }

    @Test
    fun `validates pointer syntax and expected JSON literals`() {
        assertTrue(JsonPointerAssertions.isValidPath("/status"))
        assertFalse(JsonPointerAssertions.isValidPath("status"))
        assertFalse(JsonPointerAssertions.isValidPath("/bad~escape"))
        assertTrue(JsonPointerAssertions.isValidExpectedValue("null"))
        assertTrue(JsonPointerAssertions.isValidExpectedValue("true"))
        assertFalse(JsonPointerAssertions.isValidExpectedValue("ok"))
        assertFalse(JsonPointerAssertions.isValidExpectedValue(""))
        assertFalse(JsonPointerAssertions.isValidExpectedValue("true false"))
    }

    @Test
    fun `requires both path and expected value when an assertion is configured`() {
        assertTrue(JsonPointerAssertions.isValidConfiguration(null, null))
        assertTrue(JsonPointerAssertions.isValidConfiguration("/status", "\"ok\""))
        assertFalse(JsonPointerAssertions.isValidConfiguration("/status", null))
        assertFalse(JsonPointerAssertions.isValidConfiguration(null, "true"))
        assertFalse(JsonPointerAssertions.isValidConfiguration("status", "true"))
        assertFalse(JsonPointerAssertions.isValidConfiguration("/status", "ok"))
    }
}
