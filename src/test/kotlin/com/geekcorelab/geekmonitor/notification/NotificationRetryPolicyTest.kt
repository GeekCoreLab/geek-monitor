package com.geekcorelab.geekmonitor.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NotificationRetryPolicyTest {
    @Test
    fun `failed deliveries use increasing bounded retry delays`() {
        val transitions = (1..4).map { attempt ->
            transitionNotificationAttempt(delivered = false, cancelled = false, attemptNumber = attempt)
        }

        assertEquals(listOf(15, 60, 300, 1800), transitions.map { it.retryDelaySeconds })
        assertEquals(listOf("PENDING", "PENDING", "PENDING", "PENDING"), transitions.map { it.outboxStatus })
        assertEquals(listOf("RETRYING", "RETRYING", "RETRYING", "RETRYING"), transitions.map { it.deliveryStatus })
    }

    @Test
    fun `fifth failed delivery is terminal`() {
        assertEquals(
            NotificationAttemptTransition("FAILED", "FAILED", null),
            transitionNotificationAttempt(delivered = false, cancelled = false, attemptNumber = 5),
        )
    }

    @Test
    fun `successful delivery is terminal before retry limit`() {
        assertEquals(
            NotificationAttemptTransition("DELIVERED", "DELIVERED", null),
            transitionNotificationAttempt(delivered = true, cancelled = false, attemptNumber = 2),
        )
    }

    @Test
    fun `cancelled delivery is terminal without retry`() {
        assertEquals(
            NotificationAttemptTransition("CANCELLED", "CANCELLED", null),
            transitionNotificationAttempt(delivered = false, cancelled = true, attemptNumber = 1),
        )
    }

    @Test
    fun `attempt number must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            transitionNotificationAttempt(delivered = false, cancelled = false, attemptNumber = 0)
        }
    }
}
