package com.geekcorelab.geekmonitor.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
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
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

@Path("/api/v1/incidents")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class IncidentResource {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var objectMapper: ObjectMapper
    @Inject lateinit var authenticator: SessionAuthenticator

    @GET
    fun list(
        @QueryParam("status") statusText: String?,
        @QueryParam("limit") limitText: Int?,
        @Context headers: HttpHeaders,
    ): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val status = statusText?.uppercase()
        if (status != null && status !in INCIDENT_STATUSES) return error(Response.Status.BAD_REQUEST, "INVALID_STATUS", "事件状态无效")
        val limit = (limitText ?: 100).coerceIn(1, 500)
        val incidents = mutableListOf<IncidentResponse>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT i.id, i.resource_id, r.name AS resource_name, i.monitor_id, i.title, i.category, i.severity,
                    i.status, i.opened_at, i.acknowledged_at, u.email AS acknowledged_by,
                    i.acknowledgement_note, i.resolved_at, i.occurrence_count
                    FROM incident i JOIN monitored_resource r ON r.id = i.resource_id
                    JOIN project_membership pm ON pm.project_id = r.project_id
                    LEFT JOIN app_user u ON u.id = i.acknowledged_by
                    WHERE pm.user_id = ? AND (?::varchar IS NULL OR i.status = ?::varchar)
                    ORDER BY i.opened_at DESC LIMIT ?""",
            ).use { statement ->
                statement.setObject(1, user.id)
                statement.setString(2, status)
                statement.setString(3, status)
                statement.setInt(4, limit)
                statement.executeQuery().use { rows -> while (rows.next()) incidents += rows.toIncident() }
            }
        }
        return Response.ok(incidents).build()
    }

    @POST
    @Path("/{id}/ack")
    fun acknowledge(
        @PathParam("id") idText: String,
        request: IncidentAckRequest,
        @Context headers: HttpHeaders,
    ): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        if (user.role == "READ_ONLY") return error(Response.Status.FORBIDDEN, "READ_ONLY", "只读成员不能确认故障")
        val id = parseId(idText) ?: return error(Response.Status.BAD_REQUEST, "INVALID_INCIDENT", "事件编号无效")
        val note = request.note?.trim()?.takeIf(String::isNotEmpty)
        if ((note?.length ?: 0) > 2000) return error(Response.Status.BAD_REQUEST, "NOTE_TOO_LONG", "处理备注不能超过 2000 个字符")
        val result = dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val status = connection.prepareStatement(
                    """SELECT i.status FROM incident i JOIN monitored_resource r ON r.id = i.resource_id
                        JOIN project_membership pm ON pm.project_id = r.project_id
                        WHERE i.id = ? AND pm.user_id = ? FOR UPDATE OF i""",
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setObject(2, user.id)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getString("status") else null }
                }
                if (status == null) {
                    connection.rollback()
                    return error(Response.Status.NOT_FOUND, "INCIDENT_NOT_FOUND", "事件不存在或无权访问")
                }
                if (status != "OPEN") {
                    connection.rollback()
                    return error(Response.Status.CONFLICT, "INCIDENT_NOT_OPEN", "只有未确认的活跃事件可以确认")
                }
                connection.prepareStatement(
                    "UPDATE incident SET status = 'ACKNOWLEDGED', acknowledged_by = ?, acknowledged_at = now(), acknowledgement_note = ? WHERE id = ?",
                ).use { statement ->
                    statement.setObject(1, user.id)
                    statement.setString(2, note)
                    statement.setObject(3, id)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO incident_event(id, incident_id, actor_user_id, event_type, note) VALUES (?, ?, ?, 'ACKNOWLEDGED', ?)",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, id)
                    statement.setObject(3, user.id)
                    statement.setString(4, note)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO audit_log(id, actor_user_id, action, subject_type, subject_id, summary) VALUES (?, ?, 'incident.acknowledged', 'incident', ?, CAST(? AS jsonb))",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, user.id)
                    statement.setString(3, id.toString())
                    statement.setString(4, objectMapper.writeValueAsString(mapOf("note" to note)))
                    statement.executeUpdate()
                }
                connection.commit()
                findIncident(id, user.id)
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
        return result?.let { Response.ok(it).build() }
            ?: error(Response.Status.INTERNAL_SERVER_ERROR, "INCIDENT_LOAD_FAILED", "事件已更新，但无法读取")
    }

    @GET
    @Path("/{id}/events")
    fun events(@PathParam("id") idText: String, @Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val id = parseId(idText) ?: return error(Response.Status.BAD_REQUEST, "INVALID_INCIDENT", "事件编号无效")
        if (!canAccessIncident(id, user.id)) return error(Response.Status.NOT_FOUND, "INCIDENT_NOT_FOUND", "事件不存在或无权访问")
        val events = mutableListOf<IncidentEventResponse>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT e.id, e.event_type, u.email AS actor_email, e.note, e.details::text AS details, e.created_at FROM incident_event e LEFT JOIN app_user u ON u.id = e.actor_user_id WHERE e.incident_id = ? ORDER BY e.created_at",
            ).use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows -> while (rows.next()) events += rows.toEvent() }
            }
        }
        return Response.ok(events).build()
    }

    private fun canAccessIncident(id: UUID, userId: UUID) = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT 1 FROM incident i JOIN monitored_resource r ON r.id = i.resource_id JOIN project_membership pm ON pm.project_id = r.project_id WHERE i.id = ? AND pm.user_id = ?",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, userId)
            statement.executeQuery().use { it.next() }
        }
    }

    private fun findIncident(id: UUID, userId: UUID): IncidentResponse? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT i.id, i.resource_id, r.name AS resource_name, i.monitor_id, i.title, i.category, i.severity,
                i.status, i.opened_at, i.acknowledged_at, u.email AS acknowledged_by,
                i.acknowledgement_note, i.resolved_at, i.occurrence_count
                FROM incident i JOIN monitored_resource r ON r.id = i.resource_id
                JOIN project_membership pm ON pm.project_id = r.project_id
                LEFT JOIN app_user u ON u.id = i.acknowledged_by
                WHERE i.id = ? AND pm.user_id = ?""",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, userId)
            statement.executeQuery().use { if (it.next()) it.toIncident() else null }
        }
    }

    private fun ResultSet.toIncident() = IncidentResponse(
        id = getObject("id").toString(),
        resourceId = getObject("resource_id").toString(),
        resourceName = getString("resource_name"),
        monitorId = getObject("monitor_id").toString(),
        title = getString("title"),
        category = getString("category"),
        severity = getString("severity"),
        status = getString("status"),
        openedAt = getTimestamp("opened_at").toInstant().toString(),
        acknowledgedAt = getTimestamp("acknowledged_at")?.toInstant()?.toString(),
        acknowledgedBy = getString("acknowledged_by"),
        acknowledgementNote = getString("acknowledgement_note"),
        resolvedAt = getTimestamp("resolved_at")?.toInstant()?.toString(),
        occurrenceCount = getInt("occurrence_count"),
    )

    private fun ResultSet.toEvent() = IncidentEventResponse(
        id = getObject("id").toString(),
        eventType = getString("event_type"),
        actorEmail = getString("actor_email"),
        note = getString("note"),
        details = objectMapper.readValue(getString("details"), objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, Any::class.java)) as Map<String, Any?>,
        createdAt = getTimestamp("created_at").toInstant().toString(),
    )

    private fun parseId(value: String) = runCatching { UUID.fromString(value) }.getOrNull()
    private fun error(status: Response.Status, code: String, message: String) = Response.status(status).entity(ErrorResponse(code, message)).build()

    companion object {
        private val INCIDENT_STATUSES = setOf("OPEN", "ACKNOWLEDGED", "RESOLVED")
    }
}
