package com.geekcorelab.api

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.infrastructure.Infrastructure
import jakarta.inject.Inject
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.NotAuthorizedException
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.sse.OutboundSseEvent
import jakarta.ws.rs.sse.Sse
import org.jboss.resteasy.reactive.NoCache
import org.jboss.resteasy.reactive.ResponseHeader
import org.jboss.resteasy.reactive.RestMulti
import org.jboss.resteasy.reactive.RestStreamElementType
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Path("/api/v1/events")
class StatusEventResource {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var authenticator: SessionAuthenticator
    @Inject lateinit var sse: Sse

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    @NoCache
    @ResponseHeader(name = "Cache-Control", value = ["no-cache, no-transform"])
    @ResponseHeader(name = "X-Accel-Buffering", value = ["no"])
    fun stream(
        @Context headers: HttpHeaders,
        @QueryParam("projectId") projectIdText: String?,
        @HeaderParam("Last-Event-ID") lastEventId: String?,
    ): RestMulti<OutboundSseEvent> {
        val user = authenticator.currentUser(headers) ?: throw NotAuthorizedException("Geek Monitor")
        val projectId = projectIdText?.let {
            runCatching { UUID.fromString(it) }.getOrNull() ?: throw BadRequestException("项目编号无效")
        }
        if (projectId != null && !authenticator.canAccessProject(user.id, projectId)) {
            throw ForbiddenException("你没有该项目的访问权限")
        }

        var cursor = lastEventId?.let(StreamEventCursor::parse)
            ?: StreamEventCursor(Instant.now(), EMPTY_UUID)
        var idleTicks = 0
        val pollStream: Multi<OutboundSseEvent> = Multi.createFrom().ticks().every(POLL_INTERVAL)
            .onOverflow().drop()
            .onItem().transformToUniAndConcatenate {
                Uni.createFrom().item { fetchEvents(user.id, projectId, cursor) }
                    .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            }
            .onItem().transformToMultiAndConcatenate { events ->
                if (events.isEmpty()) {
                    idleTicks += 1
                    if (idleTicks >= HEARTBEAT_TICKS) {
                        idleTicks = 0
                        Multi.createFrom().item(heartbeatEvent())
                    } else {
                        Multi.createFrom().empty<OutboundSseEvent>()
                    }
                } else {
                    idleTicks = 0
                    cursor = events.last().cursor
                    Multi.createFrom().iterable(events.map(::toSseEvent))
                }
            }
        val stream = Multi.createBy().concatenating().streams(
            Multi.createFrom().item(heartbeatEvent()),
            pollStream,
        )

        return RestMulti.fromMultiData(stream)
            .header("Cache-Control", "no-cache, no-transform")
            .header("X-Accel-Buffering", "no")
            .build()
    }

    private fun fetchEvents(userId: UUID, projectId: UUID?, cursor: StreamEventCursor): List<StatusStreamEvent> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT occurred_at, id, event_name, payload::text AS payload
                    FROM (
                        SELECT ce.finished_at AS occurred_at, ce.id,
                            'monitor.status'::text AS event_name,
                            jsonb_build_object(
                                'eventId', ce.id,
                                'projectId', r.project_id,
                                'resourceId', r.id,
                                'resourceName', r.name,
                                'resourceStatus', r.health_status,
                                'monitorId', m.id,
                                'monitorName', m.name,
                                'monitorStatus', m.current_state,
                                'resultStatus', ce.result_status,
                                'errorCode', ce.error_code,
                                'occurredAt', ce.finished_at
                            ) AS payload
                        FROM check_execution ce
                        JOIN monitor m ON m.id = ce.monitor_id
                        JOIN monitored_resource r ON r.id = m.resource_id
                        JOIN project_membership pm ON pm.project_id = r.project_id
                        WHERE pm.user_id = ? AND r.deleted_at IS NULL AND m.deleted_at IS NULL
                          AND ce.finished_at IS NOT NULL
                          AND (?::uuid IS NULL OR r.project_id = ?::uuid)
                          AND (ce.finished_at, ce.id) > (?, ?)
                        UNION ALL
                        SELECT ie.created_at AS occurred_at, ie.id,
                            'incident.updated'::text AS event_name,
                            jsonb_build_object(
                                'eventId', ie.id,
                                'projectId', r.project_id,
                                'resourceId', r.id,
                                'resourceName', r.name,
                                'incidentId', i.id,
                                'incidentStatus', i.status,
                                'category', i.category,
                                'severity', i.severity,
                                'eventType', ie.event_type,
                                'occurredAt', ie.created_at
                            ) AS payload
                        FROM incident_event ie
                        JOIN incident i ON i.id = ie.incident_id
                        JOIN monitored_resource r ON r.id = i.resource_id
                        JOIN project_membership pm ON pm.project_id = r.project_id
                        WHERE pm.user_id = ? AND r.deleted_at IS NULL
                          AND (?::uuid IS NULL OR r.project_id = ?::uuid)
                          AND (ie.created_at, ie.id) > (?, ?)
                    ) AS status_changes
                    ORDER BY occurred_at, id
                    LIMIT ?""",
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setObject(2, projectId)
                statement.setObject(3, projectId)
                statement.setTimestamp(4, Timestamp.from(cursor.occurredAt))
                statement.setObject(5, cursor.id)
                statement.setObject(6, userId)
                statement.setObject(7, projectId)
                statement.setObject(8, projectId)
                statement.setTimestamp(9, Timestamp.from(cursor.occurredAt))
                statement.setObject(10, cursor.id)
                statement.setInt(11, PAGE_SIZE)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) {
                        add(StatusStreamEvent(
                            cursor = StreamEventCursor(rows.getTimestamp("occurred_at").toInstant(), rows.getObject("id", UUID::class.java)),
                            name = rows.getString("event_name"),
                            payload = rows.getString("payload"),
                        ))
                    }
                } }
            }
        }

    private fun toSseEvent(event: StatusStreamEvent): OutboundSseEvent = sse.newEventBuilder()
        .id(event.cursor.toHeaderValue())
        .name(event.name)
        .mediaType(MediaType.TEXT_PLAIN_TYPE)
        .data(String::class.java, event.payload)
        .build()

    private fun heartbeatEvent(): OutboundSseEvent = sse.newEventBuilder()
        .comment("keepalive")
        .build()

    private data class StatusStreamEvent(
        val cursor: StreamEventCursor,
        val name: String,
        val payload: String,
    )

    private data class StreamEventCursor(val occurredAt: Instant, val id: UUID) {
        fun toHeaderValue(): String = "${occurredAt}|$id"

        companion object {
            fun parse(value: String): StreamEventCursor? {
                val parts = value.split('|', limit = 2)
                if (parts.size != 2) return null
                val instant = runCatching { Instant.parse(parts[0]) }.getOrNull() ?: return null
                val id = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: return null
                return StreamEventCursor(instant, id)
            }
        }
    }

    companion object {
        private val POLL_INTERVAL = Duration.ofSeconds(2)
        private const val HEARTBEAT_TICKS = 8
        private const val PAGE_SIZE = 100
        private val EMPTY_UUID = UUID(0, 0)
    }
}
