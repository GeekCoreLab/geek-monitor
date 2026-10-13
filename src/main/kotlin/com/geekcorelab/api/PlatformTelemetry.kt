package com.geekcorelab.api

import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

@ApplicationScoped
class PlatformTelemetry {
    private val processStartedAt = Instant.now()
    private val rejectedAgentBatches = AtomicLong()

    @Volatile
    private var retentionJob = RetentionJobSnapshot()

    fun recordRejectedAgentBatch() {
        rejectedAgentBatches.incrementAndGet()
    }

    @Synchronized
    fun retentionStarted(at: Instant = Instant.now()) {
        retentionJob = retentionJob.copy(isRunning = true, lastStartedAt = at)
    }

    @Synchronized
    fun retentionSucceeded(at: Instant = Instant.now()) {
        retentionJob = retentionJob.copy(
            isRunning = false,
            lastFinishedAt = at,
            lastSuccessAt = at,
            consecutiveFailures = 0,
            lastFailureType = null,
        )
    }

    @Synchronized
    fun retentionFailed(at: Instant = Instant.now(), failureType: String) {
        retentionJob = retentionJob.copy(
            isRunning = false,
            lastFinishedAt = at,
            lastFailureAt = at,
            consecutiveFailures = retentionJob.consecutiveFailures + 1,
            lastFailureType = failureType.take(80),
        )
    }

    fun snapshot(): ProcessTelemetrySnapshot {
        val job = retentionJob
        val retentionStatus = when {
            job.isRunning -> "RUNNING"
            job.lastFailureAt != null && (job.lastSuccessAt == null || job.lastFailureAt.isAfter(job.lastSuccessAt)) -> "FAILED"
            job.lastSuccessAt != null -> "CURRENT"
            else -> "NOT_RUN"
        }
        return ProcessTelemetrySnapshot(
            processStartedAt = processStartedAt.toString(),
            rejectedAgentBatches = rejectedAgentBatches.get(),
            retention = RetentionJobStatusResponse(
                status = retentionStatus,
                lastStartedAt = job.lastStartedAt?.toString(),
                lastFinishedAt = job.lastFinishedAt?.toString(),
                lastSuccessAt = job.lastSuccessAt?.toString(),
                lastFailureAt = job.lastFailureAt?.toString(),
                consecutiveFailures = job.consecutiveFailures,
                lastFailureType = job.lastFailureType,
            ),
        )
    }

    private data class RetentionJobSnapshot(
        val isRunning: Boolean = false,
        val lastStartedAt: Instant? = null,
        val lastFinishedAt: Instant? = null,
        val lastSuccessAt: Instant? = null,
        val lastFailureAt: Instant? = null,
        val consecutiveFailures: Long = 0,
        val lastFailureType: String? = null,
    )
}

data class ProcessTelemetrySnapshot(
    val processStartedAt: String,
    val rejectedAgentBatches: Long,
    val retention: RetentionJobStatusResponse,
)
