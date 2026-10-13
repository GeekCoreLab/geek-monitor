package com.geekcorelab.monitor

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TargetConcurrencyLimiterTest {
    @Test
    fun `target capacity is isolated and released`() {
        val limiter = TargetConcurrencyLimiter(2)

        assertTrue(limiter.tryAcquire("HTTP:https://example.test:443"))
        assertTrue(limiter.tryAcquire("HTTP:https://example.test:443"))
        assertFalse(limiter.tryAcquire("HTTP:https://example.test:443"))
        assertTrue(limiter.tryAcquire("HTTP:https://other.test:443"))

        limiter.release("HTTP:https://example.test:443")

        assertTrue(limiter.tryAcquire("HTTP:https://example.test:443"))
        assertFalse(limiter.tryAcquire("HTTP:https://example.test:443"))
    }

    @Test
    fun `concurrent acquisition never exceeds the per target limit`() {
        val limiter = TargetConcurrencyLimiter(4)
        val pool = Executors.newFixedThreadPool(24)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(24)
        val acquired = AtomicInteger()

        repeat(24) {
            pool.execute {
                start.await()
                if (limiter.tryAcquire("TCP:db.internal:5432")) acquired.incrementAndGet()
                finished.countDown()
            }
        }
        start.countDown()

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(4, acquired.get())
        pool.shutdownNow()
    }

    @Test
    fun `HTTP target key groups URLs by normalized origin`() {
        val mapper = ObjectMapper()
        val first = TargetConcurrencyKey.of("HTTP", mapper.readTree("""{"url":"https://Example.test/status"}"""), "one")
        val second = TargetConcurrencyKey.of("HTTP", mapper.readTree("""{"url":"https://example.test:443/health"}"""), "two")
        val otherPort = TargetConcurrencyKey.of("HTTP", mapper.readTree("""{"url":"https://example.test:8443/health"}"""), "three")
        val ipv6 = TargetConcurrencyKey.of("TCP", mapper.readTree("""{"host":"2001:db8::1","port":5432}"""), "four")

        assertEquals(first, second)
        assertNotEquals(first, otherPort)
        assertEquals("TCP:[2001:db8::1]:5432", ipv6)
    }
}
