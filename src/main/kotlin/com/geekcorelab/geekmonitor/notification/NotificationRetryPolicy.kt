package com.geekcorelab.geekmonitor.notification

internal data class NotificationAttemptTransition(
    val outboxStatus: String,
    val deliveryStatus: String,
    val retryDelaySeconds: Int?,
)

internal fun transitionNotificationAttempt(
    delivered: Boolean,
    cancelled: Boolean,
    attemptNumber: Int,
): NotificationAttemptTransition {
    require(attemptNumber >= 1) { "Notification attempt number must be positive" }
    return when {
        cancelled -> NotificationAttemptTransition("CANCELLED", "CANCELLED", null)
        delivered -> NotificationAttemptTransition("DELIVERED", "DELIVERED", null)
        attemptNumber >= MAX_ATTEMPTS -> NotificationAttemptTransition("FAILED", "FAILED", null)
        else -> NotificationAttemptTransition(
            "PENDING",
            "RETRYING",
            RETRY_DELAYS_SECONDS[attemptNumber - 1],
        )
    }
}

private const val MAX_ATTEMPTS = 5
private val RETRY_DELAYS_SECONDS = listOf(15, 60, 300, 1800)
