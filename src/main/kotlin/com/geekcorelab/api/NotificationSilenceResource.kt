package com.geekcorelab.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Path("/api/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class NotificationSilenceResource {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var authenticator: SessionAuthenticator
    @Inject lateinit var objectMapper: ObjectMapper

    @GET
    @Path("/notification-silences")
    fun list(
        @QueryParam("projectId") projectIdText: String?,
        @Context headers: HttpHeaders,
    ): Response {
        val user = authenticator.currentUser(headers)
            ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val projectId = projectIdText?.let(::parseId)
        if (projectIdText != null && projectId == null) {
            return error(Response.Status.BAD_REQUEST, "INVALID_PROJECT", "项目编号无效")
        }
        if (projectId != null && !authenticator.canAccessProject(user.id, projectId)) {
            return error(Response.Status.FORBIDDEN, "PROJECT_FORBIDDEN", "你没有该项目的访问权限")
        }
        val silences = mutableListOf<NotificationSilenceResponse>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT s.id, s.project_id, p.name AS project_name, s.resource_id, r.name AS resource_name,
                    s.name, s.description, s.starts_at, s.ends_at, s.canceled_at, s.created_at,
                    CASE WHEN s.canceled_at IS NOT NULL THEN 'CANCELLED'
                         WHEN s.starts_at <= now() AND s.ends_at > now() THEN 'ACTIVE'
                         WHEN s.starts_at > now() THEN 'SCHEDULED'
                         ELSE 'ENDED' END AS status
                    FROM notification_silence s JOIN project p ON p.id = s.project_id
                    JOIN project_membership pm ON pm.project_id = p.id
                    LEFT JOIN monitored_resource r ON r.id = s.resource_id
                    WHERE pm.user_id = ? AND (?::uuid IS NULL OR s.project_id = ?::uuid)
                    ORDER BY CASE WHEN s.canceled_at IS NULL AND s.ends_at > now() THEN 0 ELSE 1 END,
                        s.starts_at DESC LIMIT 500""",
            ).use { statement ->
                statement.setObject(1, user.id)
                statement.setObject(2, projectId)
                statement.setObject(3, projectId)
                statement.executeQuery().use { rows -> while (rows.next()) silences += rows.toSilence() }
            }
        }
        return Response.ok(silences).build()
    }

    @POST
    @Path("/notification-silences")
    fun create(request: NotificationSilenceRequest, @Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers)
            ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        if (user.role == "READ_ONLY") {
            return error(Response.Status.FORBIDDEN, "READ_ONLY", "只读成员不能管理通知静默")
        }
        val projectId = request.projectId?.let(::parseId)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_PROJECT", "请选择有效项目")
        if (!authenticator.canAccessProject(user.id, projectId)) {
            return error(Response.Status.FORBIDDEN, "PROJECT_FORBIDDEN", "你没有该项目的访问权限")
        }
        val name = request.name?.trim().orEmpty()
        val description = request.description?.trim().orEmpty()
        if (name.isBlank() || name.length > 160 || description.length > 1000) {
            return error(Response.Status.BAD_REQUEST, "INVALID_SILENCE", "请检查静默名称和说明")
        }
        val startsAt = request.startsAt?.let(::parseInstant)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_SILENCE_TIME", "开始时间无效")
        val endsAt = request.endsAt?.let(::parseInstant)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_SILENCE_TIME", "结束时间无效")
        val now = Instant.now()
        if (startsAt.isBefore(now.minus(Duration.ofMinutes(5))) || !endsAt.isAfter(startsAt) || !endsAt.isAfter(now)) {
            return error(Response.Status.BAD_REQUEST, "INVALID_SILENCE_TIME", "开始时间不能早于当前时间，结束时间必须晚于开始时间和当前时间")
        }

        val resourceId = request.resourceId?.let(::parseId)
        if (request.resourceId != null && resourceId == null) {
            return error(Response.Status.BAD_REQUEST, "INVALID_RESOURCE", "资源编号无效")
        }
        if (resourceId != null && !resourceBelongsToProject(resourceId, projectId)) {
            return error(Response.Status.BAD_REQUEST, "RESOURCE_PROJECT_MISMATCH", "所选资源不属于该项目或已删除")
        }

        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "INSERT INTO notification_silence(id, project_id, resource_id, name, description, starts_at, ends_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setObject(2, projectId)
                    statement.setObject(3, resourceId)
                    statement.setString(4, name)
                    statement.setString(5, description)
                    statement.setTimestamp(6, Timestamp.from(startsAt))
                    statement.setTimestamp(7, Timestamp.from(endsAt))
                    statement.setObject(8, user.id)
                    statement.executeUpdate()
                }
                audit(connection, user.id, "notification_silence.created", id, mapOf("name" to name, "projectId" to projectId.toString()))
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
        return findSilence(id, user.id)?.let { Response.status(Response.Status.CREATED).entity(it).build() }
            ?: error(Response.Status.INTERNAL_SERVER_ERROR, "SILENCE_LOAD_FAILED", "静默已创建，但无法读取")
    }

    @DELETE
    @Path("/notification-silences/{id}")
    fun cancel(@PathParam("id") idText: String, @Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers)
            ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        if (user.role == "READ_ONLY") {
            return error(Response.Status.FORBIDDEN, "READ_ONLY", "只读成员不能管理通知静默")
        }
        val id = parseId(idText)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_SILENCE", "静默编号无效")
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val scope = connection.prepareStatement(
                    "UPDATE notification_silence s SET canceled_at = now() WHERE s.id = ? AND s.canceled_at IS NULL AND s.ends_at > now() AND EXISTS (SELECT 1 FROM project_membership pm WHERE pm.project_id = s.project_id AND pm.user_id = ?) RETURNING s.project_id, s.resource_id",
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setObject(2, user.id)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) CancellationScope(
                            rows.getObject("project_id", UUID::class.java),
                            rows.getObject("resource_id", UUID::class.java),
                        ) else null
                    }
                } ?: run {
                    connection.rollback()
                    return error(Response.Status.NOT_FOUND, "SILENCE_NOT_FOUND", "静默不存在、已结束或无权访问")
                }
                connection.prepareStatement(
                    """UPDATE notification_outbox n SET next_attempt_at = now(), silence_deferred_until = NULL
                        FROM incident i JOIN monitored_resource r ON r.id = i.resource_id
                        WHERE n.incident_id = i.id AND n.status = 'PENDING' AND n.silence_deferred_until IS NOT NULL
                          AND r.project_id = ? AND (?::uuid IS NULL OR r.id = ?::uuid)""",
                ).use { statement ->
                    statement.setObject(1, scope.projectId)
                    statement.setObject(2, scope.resourceId)
                    statement.setObject(3, scope.resourceId)
                    statement.executeUpdate()
                }
                audit(connection, user.id, "notification_silence.cancelled", id, mapOf("projectId" to scope.projectId.toString()))
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
        return Response.noContent().build()
    }

    private fun resourceBelongsToProject(resourceId: UUID, projectId: UUID): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT 1 FROM monitored_resource WHERE id = ? AND project_id = ? AND deleted_at IS NULL").use { statement ->
            statement.setObject(1, resourceId)
            statement.setObject(2, projectId)
            statement.executeQuery().use { it.next() }
        }
    }

    private fun findSilence(id: UUID, userId: UUID): NotificationSilenceResponse? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT s.id, s.project_id, p.name AS project_name, s.resource_id, r.name AS resource_name,
                s.name, s.description, s.starts_at, s.ends_at, s.canceled_at, s.created_at,
                CASE WHEN s.canceled_at IS NOT NULL THEN 'CANCELLED'
                     WHEN s.starts_at <= now() AND s.ends_at > now() THEN 'ACTIVE'
                     WHEN s.starts_at > now() THEN 'SCHEDULED'
                     ELSE 'ENDED' END AS status
                FROM notification_silence s JOIN project p ON p.id = s.project_id
                JOIN project_membership pm ON pm.project_id = p.id LEFT JOIN monitored_resource r ON r.id = s.resource_id
                WHERE s.id = ? AND pm.user_id = ?""",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, userId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toSilence() else null }
        }
    }

    private fun audit(connection: Connection, userId: UUID, action: String, id: UUID, summary: Map<String, String>) {
        connection.prepareStatement(
            "INSERT INTO audit_log(id, actor_user_id, action, subject_type, subject_id, summary) VALUES (?, ?, ?, 'notification_silence', ?, CAST(? AS jsonb))",
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, userId)
            statement.setString(3, action)
            statement.setString(4, id.toString())
            statement.setString(5, objectMapper.writeValueAsString(summary))
            statement.executeUpdate()
        }
    }

    private fun ResultSet.toSilence() = NotificationSilenceResponse(
        id = getObject("id", UUID::class.java).toString(),
        projectId = getObject("project_id", UUID::class.java).toString(),
        projectName = getString("project_name"),
        resourceId = getObject("resource_id", UUID::class.java)?.toString(),
        resourceName = getString("resource_name"),
        name = getString("name"),
        description = getString("description"),
        startsAt = getTimestamp("starts_at").toInstant().toString(),
        endsAt = getTimestamp("ends_at").toInstant().toString(),
        status = getString("status"),
        createdAt = getTimestamp("created_at").toInstant().toString(),
    )

    private data class CancellationScope(val projectId: UUID, val resourceId: UUID?)

    private fun parseId(text: String): UUID? = runCatching { UUID.fromString(text) }.getOrNull()
    private fun parseInstant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()
    private fun error(status: Response.Status, code: String, message: String) = Response.status(status).entity(ErrorResponse(code, message)).build()
}
