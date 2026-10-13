package com.geekcorelab.monitor

internal data class MonitorStateTransition(
    val state: String,
    val consecutiveFailures: Int,
    val consecutiveSuccesses: Int,
)

internal fun transitionMonitorState(
    currentState: String,
    consecutiveFailures: Int,
    consecutiveSuccesses: Int,
    resultStatus: String,
    failureThreshold: Int,
    recoveryThreshold: Int,
): MonitorStateTransition {
    val success = resultStatus == "UP"
    val failure = resultStatus == "DOWN"
    val degraded = resultStatus == "DEGRADED"
    val failureEvidence = failure || degraded
    val failures = when {
        success || !failureEvidence -> 0
        else -> consecutiveFailures + 1
    }
    val successes = if (success) consecutiveSuccesses + 1 else 0
    val nextState = when {
        resultStatus == "UNKNOWN" && currentState in setOf("DOWN", "DEGRADED") -> currentState
        resultStatus == "UNKNOWN" -> "UNKNOWN"
        success && currentState in setOf("DOWN", "DEGRADED") && successes >= recoveryThreshold -> "UP"
        success && currentState in setOf("PENDING", "UNKNOWN") -> "UP"
        failure && currentState != "DOWN" && failures >= failureThreshold -> "DOWN"
        degraded && currentState !in setOf("DOWN", "DEGRADED") && failures >= failureThreshold -> "DEGRADED"
        else -> currentState
    }
    return MonitorStateTransition(nextState, failures, successes)
}
