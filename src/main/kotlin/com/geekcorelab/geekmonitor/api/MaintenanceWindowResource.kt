package com.geekcorelab.geekmonitor.api

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
class MaintenanceWindowResource {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var authenticator: SessionAuthenticator
    @Inject lateinit var objectMapper: ObjectMapper

    @GET
    @Path("/maintenance-windows")
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
        val windows = mutableListOf<MaintenanceWindowResponse>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT w.id, w.project_id, p.name AS project_name, w.resource_id, r.name AS resource_name,
                    w.name, w.description, w.starts_at, w.ends_at, w.canceled_at, w.created_at,
                    CASE WHEN w.canceled_at IS NOT NULL THEN 'CANCELLED'
                         WHEN w.starts_at <= now() AND w.ends_at > now() THEN 'ACTIVE'
                         WHEN w.starts_at > now() THEN 'SCHEDULED'
                         ELSE 'ENDED' END AS status
                    FROM maintenance_window w JOIN project p ON p.id = w.project_id
                    JOIN project_membership pm ON pm.project_id = p.id
                    LEFT JOIN monitored_resource r ON r.id = w.resource_id
                    WHERE pm.user_id = ? AND (?::uuid IS NULL OR w.project_id = ?::uuid)
                    ORDER BY CASE WHEN w.canceled_at IS NULL AND w.ends_at > now() THEN 0 ELSE 1 END,
                        w.starts_at DESC LIMIT 500""",
            ).use { statement ->
                statement.setObject(1, user.id)
                statement.setObject(2, projectId)
                statement.setObject(3, projectId)
                statement.executeQuery().use { rows -> while (rows.next()) windows += rows.toMaintenanceWindow() }
            }
        }
        return Response.ok(windows).build()
    }

    @POST
    @Path("/maintenance-windows")
    fun create(request: MaintenanceWindowRequest, @Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers)
            ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        if (user.role == "READ_ONLY") {
            return error(Response.Status.FORBIDDEN, "READ_ONLY", "只读成员不能管理维护窗口")
        }
        val projectId = request.projectId?.let(::parseId)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_PROJECT", "请选择有效项目")
        if (!authenticator.canAccessProject(user.id, projectId)) {
            return error(Response.Status.FORBIDDEN, "PROJECT_FORBIDDEN", "你没有该项目的访问权限")
        }
        val name = request.name?.trim().orEmpty()
        val description = request.description?.trim().orEmpty()
        if (name.isBlank() || name.length > 160 || description.length > 1000) {
            return error(Response.Status.BAD_REQUEST, "INVALID_WINDOW", "请检查维护窗口名称和说明")
        }
        val startsAt = request.startsAt?.let(::parseInstant)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_WINDOW_TIME", "开始时间无效")
        val endsAt = request.endsAt?.let(::parseInstant)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_WINDOW_TIME", "结束时间无效")
        val now = Instant.now()
        if (startsAt.isBefore(now.minus(Duration.ofMinutes(5))) || !endsAt.isAfter(startsAt) || !endsAt.isAfter(now)) {
            return error(Response.Status.BAD_REQUEST, "INVALID_WINDOW_TIME", "开始时间不能早于当前时间，结束时间必须晚于开始时间和当前时间")
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
                    "INSERT INTO maintenance_window(id, project_id, resource_id, name, description, starts_at, ends_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
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
                audit(connection, user.id, "maintenance_window.created", id, mapOf("name" to name, "projectId" to projectId.toString()))
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
        return findWindow(id, user.id)?.let { Response.status(Response.Status.CREATED).entity(it).build() }
            ?: error(Response.Status.INTERNAL_SERVER_ERROR, "WINDOW_LOAD_FAILED", "维护窗口已创建，但无法读取")
    }

    @DELETE
    @Path("/maintenance-windows/{id}")
    fun cancel(@PathParam("id") idText: String, @Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers)
            ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        if (user.role == "READ_ONLY") {
            return error(Response.Status.FORBIDDEN, "READ_ONLY", "只读成员不能管理维护窗口")
        }
        val id = parseId(idText)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_WINDOW", "维护窗口编号无效")
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val projectId = connection.prepareStatement(
                    "UPDATE maintenance_window w SET canceled_at = now() WHERE w.id = ? AND w.canceled_at IS NULL AND w.ends_at > now() AND EXISTS (SELECT 1 FROM project_membership pm WHERE pm.project_id = w.project_id AND pm.user_id = ?) RETURNING w.project_id",
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setObject(2, user.id)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getObject("project_id", UUID::class.java) else null }
                } ?: run {
                    connection.rollback()
                    return error(Response.Status.NOT_FOUND, "WINDOW_NOT_FOUND", "维护窗口不存在、已结束或无权访问")
                }
                audit(connection, user.id, "maintenance_window.cancelled", id, mapOf("projectId" to projectId.toString()))
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

    private fun findWindow(id: UUID, userId: UUID): MaintenanceWindowResponse? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT w.id, w.project_id, p.name AS project_name, w.resource_id, r.name AS resource_name,
                w.name, w.description, w.starts_at, w.ends_at, w.canceled_at, w.created_at,
                CASE WHEN w.canceled_at IS NOT NULL THEN 'CANCELLED'
                     WHEN w.starts_at <= now() AND w.ends_at > now() THEN 'ACTIVE'
                     WHEN w.starts_at > now() THEN 'SCHEDULED'
                     ELSE 'ENDED' END AS status
                FROM maintenance_window w JOIN project p ON p.id = w.project_id
                JOIN project_membership pm ON pm.project_id = p.id LEFT JOIN monitored_resource r ON r.id = w.resource_id
                WHERE w.id = ? AND pm.user_id = ?""",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, userId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toMaintenanceWindow() else null }
        }
    }

    private fun audit(connection: Connection, userId: UUID, action: String, id: UUID, summary: Map<String, String>) {
        connection.prepareStatement(
            "INSERT INTO audit_log(id, actor_user_id, action, subject_type, subject_id, summary) VALUES (?, ?, ?, 'maintenance_window', ?, CAST(? AS jsonb))",
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, userId)
            statement.setString(3, action)
            statement.setString(4, id.toString())
            statement.setString(5, objectMapper.writeValueAsString(summary))
            statement.executeUpdate()
        }
    }

    private fun ResultSet.toMaintenanceWindow() = MaintenanceWindowResponse(
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

    private fun parseId(text: String): UUID? = runCatching { UUID.fromString(text) }.getOrNull()
    private fun parseInstant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()
    private fun error(status: Response.Status, code: String, message: String) = Response.status(status).entity(ErrorResponse(code, message)).build()
}
