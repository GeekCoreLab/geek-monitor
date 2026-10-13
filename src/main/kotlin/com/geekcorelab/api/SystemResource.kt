package com.geekcorelab.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.geekcorelab.monitor.RetentionPolicyCatalog
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
class SystemResource {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var authenticator: SessionAuthenticator
    @Inject lateinit var objectMapper: ObjectMapper
    @Inject lateinit var platformTelemetry: PlatformTelemetry

    @GET
    @Path("/system/status")
    fun status(@Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers)
            ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        if (user.role != "ADMIN") return error(Response.Status.FORBIDDEN, "ADMIN_REQUIRED", "只有系统管理员可以查看系统状态")
        val now = Instant.now()
        return dataSource.connection.use { connection ->
            val schemaVersion = connection.prepareStatement(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
            ).use { statement ->
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
            val databaseSizeBytes = connection.prepareStatement("SELECT pg_database_size(current_database())").use { statement ->
                statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
            }
            val backup = connection.prepareStatement(
                "SELECT filename, size_bytes, sha256, completed_at FROM backup_status WHERE id = 1",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    if (!rows.next()) {
                        BackupStatusResponse("NOT_RECORDED")
                    } else {
                        val completedAt = rows.getTimestamp("completed_at").toInstant()
                        BackupStatusResponse(
                            status = if (completedAt.isBefore(now.minus(Duration.ofHours(BACKUP_FRESH_HOURS)))) "OVERDUE" else "CURRENT",
                            filename = rows.getString("filename"),
                            sizeBytes = rows.getLong("size_bytes"),
                            sha256 = rows.getString("sha256").trim(),
                            completedAt = completedAt.toString(),
                        )
                    }
                }
            }
            val process = platformTelemetry.snapshot()
            val schedule = connection.prepareStatement(
                """SELECT
                       count(*) FILTER (WHERE enabled AND deleted_at IS NULL AND next_run_at <= now()) AS due_checks,
                       COALESCE(floor(EXTRACT(EPOCH FROM (now() - min(next_run_at) FILTER (
                           WHERE enabled AND deleted_at IS NULL AND next_run_at <= now()
                       ))))::bigint, 0) AS oldest_schedule_delay_seconds,
                       count(*) FILTER (WHERE enabled AND deleted_at IS NULL) AS enabled_checks,
                       count(*) FILTER (WHERE enabled AND deleted_at IS NULL AND (
                           last_result_at IS NULL OR last_result_at < now() - (interval_seconds * 2) * INTERVAL '1 second'
                       )) AS stale_checks
                   FROM monitor""",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    rows.next()
                    ScheduleHealth(
                        dueChecks = rows.getLong("due_checks"),
                        oldestScheduleDelaySeconds = rows.getLong("oldest_schedule_delay_seconds"),
                        enabledChecks = rows.getLong("enabled_checks"),
                        staleChecks = rows.getLong("stale_checks"),
                    )
                }
            }
            val runningChecks = connection.prepareStatement(
                "SELECT count(*) FROM check_execution WHERE result_status = 'RUNNING'",
            ).use { statement -> statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) } }
            val recentChecks = connection.prepareStatement(
                """SELECT count(*) AS sample_count, avg(duration_ms) AS average_duration_ms,
                          percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95_duration_ms,
                          avg(GREATEST(0, EXTRACT(EPOCH FROM (finished_at - scheduled_at)) * 1000)) AS average_result_latency_ms,
                          percentile_cont(0.95) WITHIN GROUP (
                              ORDER BY GREATEST(0, EXTRACT(EPOCH FROM (finished_at - scheduled_at)) * 1000)
                          ) AS p95_result_latency_ms
                   FROM check_execution
                   WHERE result_status <> 'RUNNING' AND finished_at >= now() - INTERVAL '1 hour'""",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    rows.next()
                    RecentCheckHealth(
                        sampleCount = rows.getLong("sample_count"),
                        averageDurationMs = rows.getDouble("average_duration_ms").let { if (rows.wasNull()) null else it },
                        p95DurationMs = rows.getDouble("p95_duration_ms").let { if (rows.wasNull()) null else it.toLong() },
                        averageResultLatencyMs = rows.getDouble("average_result_latency_ms").let { if (rows.wasNull()) null else it },
                        p95ResultLatencyMs = rows.getDouble("p95_result_latency_ms").let { if (rows.wasNull()) null else it.toLong() },
                    )
                }
            }
            val deliveries = connection.prepareStatement(
                """SELECT count(*) AS attempts,
                          count(*) FILTER (WHERE status = 'DELIVERED') AS delivered
                   FROM notification_delivery WHERE finished_at >= now() - INTERVAL '24 hours'""",
            ).use { statement ->
                statement.executeQuery().use { rows -> rows.next(); rows.getLong("attempts") to rows.getLong("delivered") }
            }
            val status = classifyPlatformHealth(
                schedule.staleChecks,
                schedule.oldestScheduleDelaySeconds,
                process.retention.status,
            )
            val platformHealth = PlatformHealthResponse(
                status = status,
                dueChecks = schedule.dueChecks,
                oldestScheduleDelaySeconds = schedule.oldestScheduleDelaySeconds,
                runningChecks = runningChecks,
                enabledChecks = schedule.enabledChecks,
                staleChecks = schedule.staleChecks,
                recentCheckCount = recentChecks.sampleCount,
                recentCheckAverageDurationMs = recentChecks.averageDurationMs,
                recentCheckP95DurationMs = recentChecks.p95DurationMs,
                recentResultAverageLatencyMs = recentChecks.averageResultLatencyMs,
                recentResultP95LatencyMs = recentChecks.p95ResultLatencyMs,
                rejectedAgentBatchesSinceStart = process.rejectedAgentBatches,
                processStartedAt = process.processStartedAt,
                notificationAttempts24h = deliveries.first,
                notificationDelivered24h = deliveries.second,
                notificationSuccessRate24h = if (deliveries.first == 0L) null else deliveries.second * 100.0 / deliveries.first,
                retention = process.retention,
            )
            Response.ok(SystemStatusResponse(
                now.toString(), schemaVersion, databaseSizeBytes, RetentionPolicyCatalog.policies, backup, platformHealth,
            )).build()
        }
    }

    @GET
    @Path("/audit-log")
    fun auditLog(
        @QueryParam("limit") limitText: Int?,
        @QueryParam("beforeCreatedAt") beforeCreatedAtText: String?,
        @QueryParam("beforeId") beforeIdText: String?,
        @Context headers: HttpHeaders,
    ): Response {
        val user = authenticator.currentUser(headers)
            ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        if (user.role != "ADMIN") return error(Response.Status.FORBIDDEN, "ADMIN_REQUIRED", "只有系统管理员可以查看审计日志")
        val beforeCreatedAt = beforeCreatedAtText?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val beforeId = beforeIdText?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if ((beforeCreatedAtText != null && beforeCreatedAt == null) || (beforeIdText != null && beforeId == null) ||
            (beforeCreatedAt == null) != (beforeId == null)
        ) {
            return error(Response.Status.BAD_REQUEST, "INVALID_AUDIT_CURSOR", "审计日志游标无效")
        }
        val limit = (limitText ?: DEFAULT_AUDIT_PAGE_SIZE).coerceIn(1, MAX_AUDIT_PAGE_SIZE)
        val cursorClause = if (beforeCreatedAt == null) "" else "WHERE (a.created_at, a.id) < (?, ?)"
        val entries = mutableListOf<AuditLogEntry>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT a.id, a.created_at, u.email AS actor_email, a.action, a.subject_type, a.subject_id, a.summary::text AS summary
                   FROM audit_log a LEFT JOIN app_user u ON u.id = a.actor_user_id
                   $cursorClause ORDER BY a.created_at DESC, a.id DESC LIMIT ?""",
            ).use { statement ->
                if (beforeCreatedAt != null && beforeId != null) {
                    statement.setTimestamp(1, Timestamp.from(beforeCreatedAt))
                    statement.setObject(2, beforeId)
                    statement.setInt(3, limit + 1)
                } else {
                    statement.setInt(1, limit + 1)
                }
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        entries += AuditLogEntry(
                            id = rows.getObject("id").toString(),
                            createdAt = rows.getTimestamp("created_at").toInstant().toString(),
                            actorEmail = rows.getString("actor_email"),
                            action = rows.getString("action"),
                            subjectType = rows.getString("subject_type"),
                            subjectId = rows.getString("subject_id"),
                            summary = sanitizeSummary(objectMapper.readTree(rows.getString("summary"))),
                        )
                    }
                }
            }
        }
        return Response.ok(auditLogPage(entries, limit)).build()
    }

    private fun sanitizeSummary(node: JsonNode): JsonNode = when {
        node.isObject -> objectMapper.createObjectNode().also { sanitized ->
            node.properties().forEach { (name, value) ->
                if (isSensitiveField(name)) sanitized.put(name, "[已隐藏]")
                else sanitized.set<JsonNode>(name, sanitizeSummary(value))
            }
        }
        node.isArray -> objectMapper.createArrayNode().also { sanitized -> node.forEach { sanitized.add(sanitizeSummary(it)) } }
        else -> node
    }

    private fun isSensitiveField(name: String): Boolean {
        val normalized = name.lowercase().filter(Char::isLetterOrDigit)
        return normalized == "note" || SENSITIVE_FIELD_MARKERS.any(normalized::contains)
    }

    private fun error(status: Response.Status, code: String, message: String) =
        Response.status(status).entity(ErrorResponse(code, message)).build()

    companion object {
        private const val DEFAULT_AUDIT_PAGE_SIZE = 50
        private const val MAX_AUDIT_PAGE_SIZE = 200
        private const val BACKUP_FRESH_HOURS = 24L
        private val SENSITIVE_FIELD_MARKERS = setOf("password", "secret", "token", "apikey", "authorization", "cookie", "credential", "ciphertext")
    }

    private data class ScheduleHealth(
        val dueChecks: Long,
        val oldestScheduleDelaySeconds: Long,
        val enabledChecks: Long,
        val staleChecks: Long,
    )

    private data class RecentCheckHealth(
        val sampleCount: Long,
        val averageDurationMs: Double?,
        val p95DurationMs: Long?,
        val averageResultLatencyMs: Double?,
        val p95ResultLatencyMs: Long?,
    )
}
