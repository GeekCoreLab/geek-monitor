package com.geekcorelab.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MonitorStateMachineTest {
    @Test
    fun `failure threshold changes UP to DOWN only on the threshold result`() {
        val first = transitionMonitorState("UP", 0, 0, "DOWN", 3, 2)
        val second = transitionMonitorState(first.state, first.consecutiveFailures, first.consecutiveSuccesses, "DOWN", 3, 2)
        val third = transitionMonitorState(second.state, second.consecutiveFailures, second.consecutiveSuccesses, "DOWN", 3, 2)

        assertEquals(MonitorStateTransition("UP", 1, 0), first)
        assertEquals(MonitorStateTransition("UP", 2, 0), second)
        assertEquals(MonitorStateTransition("DOWN", 3, 0), third)
    }

    @Test
    fun `recovery threshold keeps DOWN until enough consecutive successes`() {
        val first = transitionMonitorState("DOWN", 3, 0, "UP", 3, 2)
        val second = transitionMonitorState(first.state, first.consecutiveFailures, first.consecutiveSuccesses, "UP", 3, 2)

        assertEquals(MonitorStateTransition("DOWN", 0, 1), first)
        assertEquals(MonitorStateTransition("UP", 0, 2), second)
    }

    @Test
    fun `UNKNOWN resets counters without resolving an active DOWN incident state`() {
        assertEquals(
            MonitorStateTransition("DOWN", 0, 0),
            transitionMonitorState("DOWN", 2, 1, "UNKNOWN", 3, 2),
        )
    }

    @Test
    fun `UNKNOWN changes a non-failing resource to UNKNOWN`() {
        assertEquals(
            MonitorStateTransition("UNKNOWN", 0, 0),
            transitionMonitorState("UP", 2, 1, "UNKNOWN", 3, 2),
        )
    }

    @Test
    fun `DEGRADED evidence contributes to failure threshold`() {
        val first = transitionMonitorState("UP", 0, 0, "DEGRADED", 2, 2)
        val second = transitionMonitorState(first.state, first.consecutiveFailures, first.consecutiveSuccesses, "DEGRADED", 2, 2)

        assertEquals(MonitorStateTransition("UP", 1, 0), first)
        assertEquals(MonitorStateTransition("DEGRADED", 2, 0), second)
    }

    @Test
    fun `first success moves PENDING and UNKNOWN resources to UP`() {
        assertEquals(MonitorStateTransition("UP", 0, 1), transitionMonitorState("PENDING", 0, 0, "UP", 3, 2))
        assertEquals(MonitorStateTransition("UP", 0, 1), transitionMonitorState("UNKNOWN", 0, 0, "UP", 3, 2))
    }

    @Test
    fun `UNKNOWN breaks the recovery streak`() {
        assertEquals(
            MonitorStateTransition("DOWN", 0, 0),
            transitionMonitorState("DOWN", 0, 1, "UNKNOWN", 3, 3),
        )
    }
}
