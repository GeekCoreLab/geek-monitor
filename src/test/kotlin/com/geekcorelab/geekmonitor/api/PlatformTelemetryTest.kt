package com.geekcorelab.geekmonitor.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Instant

class PlatformTelemetryTest {
    @Test
    fun `retention status distinguishes startup active success and failure`() {
        val telemetry = PlatformTelemetry()
        val first = Instant.parse("2026-10-12T12:00:00Z")

        assertEquals("NOT_RUN", telemetry.snapshot().retention.status)
        telemetry.retentionStarted(first)
        assertEquals("RUNNING", telemetry.snapshot().retention.status)
        telemetry.retentionFailed(first.plusSeconds(3), "SQLException")
        val failed = telemetry.snapshot().retention
        assertEquals("FAILED", failed.status)
        assertEquals(1, failed.consecutiveFailures)
        assertEquals("SQLException", failed.lastFailureType)

        telemetry.retentionStarted(first.plusSeconds(4))
        telemetry.retentionSucceeded(first.plusSeconds(5))
        val recovered = telemetry.snapshot().retention
        assertEquals("CURRENT", recovered.status)
        assertEquals(0, recovered.consecutiveFailures)
        assertNotNull(recovered.lastSuccessAt)
    }

    @Test
    fun `agent batch rejection counter is process scoped and monotonic`() {
        val telemetry = PlatformTelemetry()

        telemetry.recordRejectedAgentBatch()
        telemetry.recordRejectedAgentBatch()

        val snapshot = telemetry.snapshot()
        assertEquals(2, snapshot.rejectedAgentBatches)
        assertNotNull(Instant.parse(snapshot.processStartedAt))
    }

    @Test
    fun `platform health becomes degraded on stale checks long delay or failed retention`() {
        assertEquals("HEALTHY", classifyPlatformHealth(0, 30, "CURRENT"))
        assertEquals("DEGRADED", classifyPlatformHealth(1, 0, "CURRENT"))
        assertEquals("DEGRADED", classifyPlatformHealth(0, 31, "CURRENT"))
        assertEquals("DEGRADED", classifyPlatformHealth(0, 0, "FAILED"))
    }
}
