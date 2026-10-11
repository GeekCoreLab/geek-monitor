package com.geekcorelab.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class AvailabilityRollupCalculatorTest {
    private val bucketStart = Instant.parse("2026-10-11T00:00:00Z")

    @Test
    fun `degraded time counts as up while unknown time remains uncovered`() {
        val active = listOf(span(0, 3_600))
        val totals = calculateAvailabilityBucketTotals(
            activePeriods = active,
            monitored = active,
            excluded = emptyList(),
            pauses = emptyList(),
            executions = listOf(
                execution(0, 600, "UP"),
                execution(600, 600, "DEGRADED"),
                execution(1_200, 600, "DOWN"),
                execution(1_800, 600, "UNKNOWN"),
            ),
        )

        assertEquals(3_600, totals.scheduledSeconds)
        assertEquals(1_200, totals.upSeconds)
        assertEquals(600, totals.degradedSeconds)
        assertEquals(600, totals.downSeconds)
        assertEquals(1_800, totals.unknownSeconds)
        assertEquals(0, totals.excludedSeconds)
    }

    @Test
    fun `check result cannot carry through a disabled monitoring gap`() {
        val active = listOf(span(0, 300), span(600, 900))
        val totals = calculateAvailabilityBucketTotals(
            activePeriods = active,
            monitored = active,
            excluded = emptyList(),
            pauses = emptyList(),
            executions = listOf(execution(0, 900, "UP")),
        )

        assertEquals(600, totals.scheduledSeconds)
        assertEquals(300, totals.upSeconds)
        assertEquals(0, totals.downSeconds)
        assertEquals(300, totals.unknownSeconds)
    }

    @Test
    fun `pause cuts a carried result and excludes its duration from coverage`() {
        val active = listOf(span(0, 900))
        val pause = span(300, 600)
        val totals = calculateAvailabilityBucketTotals(
            activePeriods = active,
            monitored = active,
            excluded = listOf(pause),
            pauses = listOf(pause),
            executions = listOf(execution(0, 900, "UP")),
        )

        assertEquals(900, totals.scheduledSeconds)
        assertEquals(300, totals.upSeconds)
        assertEquals(300, totals.unknownSeconds)
        assertEquals(300, totals.excludedSeconds)
    }

    @Test
    fun `overlapping maintenance windows are counted once`() {
        val active = listOf(span(0, 1_000))
        val totals = calculateAvailabilityBucketTotals(
            activePeriods = active,
            monitored = active,
            excluded = listOf(span(200, 500), span(400, 600)),
            pauses = emptyList(),
            executions = emptyList(),
        )

        assertEquals(1_000, totals.scheduledSeconds)
        assertEquals(400, totals.excludedSeconds)
        assertEquals(600, totals.unknownSeconds)
    }

    private fun span(startSeconds: Long, endSeconds: Long) = AvailabilitySpan(
        bucketStart.plusSeconds(startSeconds),
        bucketStart.plusSeconds(endSeconds),
    )

    private fun execution(offsetSeconds: Long, intervalSeconds: Int, status: String) = AvailabilityExecution(
        scheduledAt = bucketStart.plusSeconds(offsetSeconds),
        intervalSeconds = intervalSeconds,
        status = status,
    )
}
