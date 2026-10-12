package com.geekcorelab.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DatabaseCheckServiceRedisMetricsTest {
    private val service = DatabaseCheckService()

    @Test
    fun `redis info sections produce connection memory and eviction metrics`() {
        val metrics = service.parseRedisMetrics(
            clientsInfo = "# Clients\r\nconnected_clients:12\r\n",
            memoryInfo = "# Memory\r\nused_memory:4000000\r\nmaxmemory:10000000\r\n",
            statsInfo = "# Stats\r\nevicted_keys:37\r\n",
            maximumClients = 200.0,
        )

        assertEquals(12.0, metrics["database_connections"])
        assertEquals(200.0, metrics["database_connection_limit"])
        assertEquals(6.0, metrics["database_connections_percent"])
        assertEquals(4_000_000.0, metrics["redis_memory_used_bytes"])
        assertEquals(10_000_000.0, metrics["redis_memory_limit_bytes"])
        assertEquals(40.0, metrics["redis_memory_used_percent"])
        assertEquals(37.0, metrics["redis_evicted_keys_total"])
    }

    @Test
    fun `redis memory percentage is omitted when maxmemory is unlimited`() {
        val metrics = service.parseRedisMetrics(
            clientsInfo = "connected_clients:1",
            memoryInfo = "used_memory:512000\r\nmaxmemory:0",
            statsInfo = "evicted_keys:0",
            maximumClients = null,
        )

        assertEquals(512_000.0, metrics["redis_memory_used_bytes"])
        assertEquals(0.0, metrics["redis_evicted_keys_total"])
        assertTrue("redis_memory_limit_bytes" !in metrics)
        assertTrue("redis_memory_used_percent" !in metrics)
    }

    @Test
    fun `redis metric permission failures omit only unavailable metrics`() {
        assertEquals(emptyMap<String, Double>(), service.parseRedisMetrics(null, null, null, null))
        val partial = service.parseRedisMetrics(
            clientsInfo = null,
            memoryInfo = "used_memory:bad\r\nmaxmemory:-1",
            statsInfo = "evicted_keys:9",
            maximumClients = null,
        )
        assertEquals(mapOf("redis_evicted_keys_total" to 9.0), partial)
    }
}
