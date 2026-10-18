package com.geekcorelab.geekmonitor.api

import com.fasterxml.jackson.databind.JsonNode
import com.geekcorelab.geekmonitor.monitor.RetentionPolicy

data class SystemStatusResponse(
    val generatedAt: String,
    val schemaVersion: String?,
    val databaseSizeBytes: Long,
    val retentionPolicies: List<RetentionPolicy>,
    val backup: BackupStatusResponse,
    val platformHealth: PlatformHealthResponse,
)

data class PlatformHealthResponse(
    val status: String,
    val dueChecks: Long,
    val oldestScheduleDelaySeconds: Long,
    val runningChecks: Long,
    val enabledChecks: Long,
    val staleChecks: Long,
    val recentCheckCount: Long,
    val recentCheckAverageDurationMs: Double?,
    val recentCheckP95DurationMs: Long?,
    val recentResultAverageLatencyMs: Double?,
    val recentResultP95LatencyMs: Long?,
    val rejectedAgentBatchesSinceStart: Long,
    val processStartedAt: String,
    val notificationAttempts24h: Long,
    val notificationDelivered24h: Long,
    val notificationSuccessRate24h: Double?,
    val retention: RetentionJobStatusResponse,
)

data class RetentionJobStatusResponse(
    val status: String,
    val lastStartedAt: String? = null,
    val lastFinishedAt: String? = null,
    val lastSuccessAt: String? = null,
    val lastFailureAt: String? = null,
    val consecutiveFailures: Long = 0,
    val lastFailureType: String? = null,
)

internal fun classifyPlatformHealth(
    staleChecks: Long,
    oldestScheduleDelaySeconds: Long,
    retentionStatus: String,
): String = if (
    staleChecks > 0 || oldestScheduleDelaySeconds > 30 || retentionStatus == "FAILED"
) "DEGRADED" else "HEALTHY"

data class BackupStatusResponse(
    val status: String,
    val filename: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val completedAt: String? = null,
)

data class AuditLogCursor(val createdAt: String, val id: String)

data class AuditLogEntry(
    val id: String,
    val createdAt: String,
    val actorEmail: String?,
    val action: String,
    val subjectType: String,
    val subjectId: String?,
    val summary: JsonNode,
)

data class AuditLogPageResponse(val items: List<AuditLogEntry>, val next: AuditLogCursor?)

internal fun auditLogPage(entries: List<AuditLogEntry>, limit: Int): AuditLogPageResponse {
    require(limit > 0)
    val hasMore = entries.size > limit
    val page = entries.take(limit)
    val next = page.lastOrNull()?.takeIf { hasMore }?.let { AuditLogCursor(it.createdAt, it.id) }
    return AuditLogPageResponse(page, next)
}
