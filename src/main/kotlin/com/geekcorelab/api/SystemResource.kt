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
            Response.ok(SystemStatusResponse(now.toString(), schemaVersion, databaseSizeBytes, RetentionPolicyCatalog.policies, backup)).build()
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
                    statement.setInt(3, limit)
                } else {
                    statement.setInt(1, limit)
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
        val next = entries.lastOrNull()?.takeIf { entries.size == limit }?.let { AuditLogCursor(it.createdAt, it.id) }
        return Response.ok(AuditLogPageResponse(entries, next)).build()
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
}
