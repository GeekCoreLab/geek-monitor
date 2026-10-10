package com.geekcorelab.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekcorelab.security.CredentialService
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Path("/api/v1/heartbeats")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class HeartbeatResource {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var authenticator: SessionAuthenticator
    @Inject lateinit var credentials: CredentialService
    @Inject lateinit var objectMapper: ObjectMapper

    @POST
    @Path("/{monitorId}")
    fun report(
        @PathParam("monitorId") monitorIdText: String,
        request: HeartbeatReportRequest,
        @Context headers: HttpHeaders,
    ): Response {
        val monitorId = parseId(monitorIdText)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_MONITOR", "心跳任务编号无效")
        val runId = request.runId?.let(::parseId)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_RUN_ID", "每次任务运行必须提供 UUID 格式的 runId")
        val status = request.status?.trim()?.uppercase()
            ?.takeIf { it in HEARTBEAT_STATUSES }
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_HEARTBEAT_STATUS", "状态必须是 SUCCESS 或 FAILURE")
        val summary = request.summary?.trim()?.takeIf(String::isNotEmpty)
        if ((summary?.length ?: 0) > MAX_SUMMARY_LENGTH || summary?.any(Char::isISOControl) == true) {
            return error(Response.Status.BAD_REQUEST, "INVALID_HEARTBEAT_SUMMARY", "运行摘要不能超过 500 字且不能包含控制字符")
        }
        val safeSummary = summary?.let(::sanitizeSummary)
        val runAt = request.runAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_RUN_TIME", "runAt 必须是 ISO-8601 时间")
        val now = Instant.now()
        if (runAt.isBefore(now.minus(MAX_RUN_AGE)) || runAt.isAfter(now.plus(MAX_FUTURE_SKEW))) {
            return error(Response.Status.BAD_REQUEST, "RUN_TIME_OUT_OF_RANGE", "任务运行时间超出允许范围")
        }
        val token = bearer(headers)
            ?: return error(Response.Status.UNAUTHORIZED, "HEARTBEAT_UNAUTHENTICATED", "心跳任务凭据无效")

        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val state = connection.prepareStatement(
                    """SELECT h.last_run_at
                       FROM heartbeat_task h
                       JOIN monitor m ON m.id = h.monitor_id
                       JOIN monitored_resource r ON r.id = m.resource_id
                       WHERE h.monitor_id = ? AND h.token_hash = ? AND m.type = 'HEARTBEAT'
                         AND m.enabled AND m.deleted_at IS NULL AND r.deleted_at IS NULL AND NOT r.paused
                       FOR UPDATE OF h""",
                ).use { statement ->
                    statement.setObject(1, monitorId)
                    statement.setString(2, credentials.tokenHash(token))
                    statement.executeQuery().use { rows ->
                        if (rows.next()) HeartbeatCursor(
                            lastRunAt = rows.getTimestamp("last_run_at")?.toInstant(),
                        ) else null
                    }
                }
                val cursor = state ?: run {
                    connection.rollback()
                    return@use error(Response.Status.UNAUTHORIZED, "HEARTBEAT_UNAUTHENTICATED", "心跳任务凭据无效或任务已停用")
                }
                val inserted = connection.prepareStatement(
                    """INSERT INTO heartbeat_report(monitor_id, run_id, run_at, status, summary)
                       VALUES (?, ?, ?, ?, ?) ON CONFLICT (monitor_id, run_id) DO NOTHING RETURNING received_at""",
                ).use { statement ->
                    statement.setObject(1, monitorId)
                    statement.setObject(2, runId)
                    statement.setTimestamp(3, Timestamp.from(runAt))
                    statement.setString(4, status)
                    statement.setString(5, safeSummary)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getTimestamp("received_at").toInstant() else null }
                }
                if (inserted == null) {
                    connection.commit()
                    return@use Response.ok(mapOf("accepted" to true, "duplicate" to true, "stale" to false)).build()
                }

                val stale = cursor.lastRunAt != null && !runAt.isAfter(cursor.lastRunAt)
                connection.prepareStatement(
                    """UPDATE heartbeat_task SET last_received_at = now(),
                              last_run_at = CASE WHEN last_run_at IS NULL OR last_run_at < ? THEN ? ELSE last_run_at END,
                              last_run_id = CASE WHEN last_run_at IS NULL OR last_run_at < ? THEN ? ELSE last_run_id END,
                              last_status = CASE WHEN last_run_at IS NULL OR last_run_at < ? THEN ? ELSE last_status END,
                              last_summary = CASE WHEN last_run_at IS NULL OR last_run_at < ? THEN ? ELSE last_summary END,
                              updated_at = now()
                       WHERE monitor_id = ?""",
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(runAt))
                    statement.setTimestamp(2, Timestamp.from(runAt))
                    statement.setTimestamp(3, Timestamp.from(runAt))
                    statement.setObject(4, runId)
                    statement.setTimestamp(5, Timestamp.from(runAt))
                    statement.setString(6, status)
                    statement.setTimestamp(7, Timestamp.from(runAt))
                    statement.setString(8, safeSummary)
                    statement.setObject(9, monitorId)
                    statement.executeUpdate()
                }
                connection.commit()
                Response.accepted(
                    mapOf("accepted" to true, "duplicate" to false, "stale" to stale, "receivedAt" to inserted.toString()),
                ).build()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
    }

    @POST
    @Path("/{monitorId}/rotate-token")
    fun rotateToken(@PathParam("monitorId") monitorIdText: String, @Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers)
            ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        if (user.role != "ADMIN") return error(Response.Status.FORBIDDEN, "ADMIN_REQUIRED", "只有管理员可以轮换心跳任务凭据")
        val monitorId = parseId(monitorIdText)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_MONITOR", "心跳任务编号无效")
        if (!authenticator.canAccessMonitor(user.id, monitorId)) return error(Response.Status.NOT_FOUND, "MONITOR_NOT_FOUND", "心跳任务不存在或无权访问")

        val token = credentials.newToken()
        val rotatedAt = Instant.now()
        val result = dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val updated = connection.prepareStatement(
                    """UPDATE heartbeat_task h SET token_hash = ?, updated_at = now()
                       FROM monitor m WHERE h.monitor_id = ? AND m.id = h.monitor_id AND m.type = 'HEARTBEAT'
                         AND m.deleted_at IS NULL AND m.enabled""",
                ).use { statement ->
                    statement.setString(1, credentials.tokenHash(token))
                    statement.setObject(2, monitorId)
                    statement.executeUpdate()
                }
                if (updated != 1) {
                    connection.rollback()
                    return@use false
                }
                connection.prepareStatement(
                    "INSERT INTO audit_log(id, actor_user_id, action, subject_type, subject_id, summary) VALUES (?, ?, 'heartbeat.token.rotated', 'monitor', ?, CAST(? AS jsonb))",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, user.id)
                    statement.setString(3, monitorId.toString())
                    statement.setString(4, objectMapper.writeValueAsString(mapOf("monitorId" to monitorId.toString())))
                    statement.executeUpdate()
                }
                connection.commit()
                true
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
        if (!result) return error(Response.Status.NOT_FOUND, "HEARTBEAT_TASK_NOT_FOUND", "心跳任务不存在或已停用")
        return Response.ok(mapOf("monitorId" to monitorId.toString(), "token" to token, "rotatedAt" to rotatedAt.toString())).build()
    }

    private fun bearer(headers: HttpHeaders): String? = headers.getHeaderString(HttpHeaders.AUTHORIZATION)
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter(' ')
        ?.takeIf(String::isNotBlank)

    private fun sanitizeSummary(value: String): String {
        val credentialPattern = Regex(
            """(?i)(["']?(?:password|token|secret|api[_-]?key)["']?\s*[:=]\s*)(?:"(?:\\.|[^"])*"|'(?:\\.|[^'])*'|[^,;}\r\n]+)""",
        )
        return credentialPattern.replace(value) { match -> "${match.groupValues[1]}\"[redacted]\"" }
            .replace(Regex("(?i)Bearer\\s+[^\\s,;]+"), "Bearer [redacted]")
    }

    private fun parseId(value: String) = runCatching { UUID.fromString(value) }.getOrNull()
    private fun error(status: Response.Status, code: String, message: String) = Response.status(status).entity(ErrorResponse(code, message)).build()

    private data class HeartbeatCursor(val lastRunAt: Instant?)

    companion object {
        private const val MAX_SUMMARY_LENGTH = 500
        private val MAX_RUN_AGE = Duration.ofDays(30)
        private val MAX_FUTURE_SKEW = Duration.ofMinutes(5)
        private val HEARTBEAT_STATUSES = setOf("SUCCESS", "FAILURE")
    }
}

data class HeartbeatReportRequest(
    val runId: String?,
    val runAt: String?,
    val status: String?,
    val summary: String? = null,
)
