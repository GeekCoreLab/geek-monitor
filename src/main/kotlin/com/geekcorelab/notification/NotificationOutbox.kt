package com.geekcorelab.notification

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.util.UUID

@ApplicationScoped
class NotificationOutbox {
    @Inject lateinit var objectMapper: ObjectMapper

    fun enqueueIncident(
        connection: Connection,
        incidentId: UUID,
        incidentEventId: UUID,
        eventType: String,
        payload: Map<String, Any?>,
    ) {
        val maintenanceActive = connection.prepareStatement(
            """SELECT EXISTS (
                SELECT 1 FROM incident i JOIN monitored_resource r ON r.id = i.resource_id
                JOIN maintenance_window w ON w.project_id = r.project_id
                WHERE i.id = ? AND w.canceled_at IS NULL AND w.starts_at <= now() AND w.ends_at > now()
                  AND (w.resource_id IS NULL OR w.resource_id = r.id)
            )""",
        ).use { statement ->
            statement.setObject(1, incidentId)
            statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }
        if (maintenanceActive) return
        val projectId = connection.prepareStatement(
            "SELECT r.project_id FROM incident i JOIN monitored_resource r ON r.id = i.resource_id WHERE i.id = ?",
        ).use { statement ->
            statement.setObject(1, incidentId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getObject("project_id", UUID::class.java) else null }
        } ?: return
        val channels = connection.prepareStatement(
            "SELECT id FROM notification_channel WHERE project_id = ? AND enabled AND deleted_at IS NULL",
        ).use { statement ->
            statement.setObject(1, projectId)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject("id", UUID::class.java)) } }
        }
        channels.forEach { channelId ->
            connection.prepareStatement(
                """INSERT INTO notification_outbox(id, channel_id, incident_id, incident_event_id, event_type, idempotency_key, payload, next_attempt_at)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), now() + (? * INTERVAL '1 second'))
                    ON CONFLICT (idempotency_key) DO NOTHING""",
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, channelId)
                statement.setObject(3, incidentId)
                statement.setObject(4, incidentEventId)
                statement.setString(5, eventType)
                statement.setString(6, "${channelId}:${incidentEventId}")
                statement.setString(7, objectMapper.writeValueAsString(payload))
                statement.setInt(8, GROUPING_WINDOW_SECONDS)
                statement.executeUpdate()
            }
        }
    }

    fun enqueueTest(connection: Connection, channelId: UUID, userId: UUID, email: String): UUID {
        val outboxId = UUID.randomUUID()
        val payload = mapOf(
            "eventId" to outboxId.toString(),
            "eventType" to "notification.test",
            "title" to "Geek Monitor 测试通知",
            "message" to "通知渠道测试成功后，你会在这里收到故障和恢复消息。",
            "requestedBy" to email,
            "isTest" to true,
        )
        connection.prepareStatement(
            """INSERT INTO notification_outbox(id, channel_id, requested_by, event_type, idempotency_key, payload)
                VALUES (?, ?, ?, 'TEST', ?, CAST(? AS jsonb))""",
        ).use { statement ->
            statement.setObject(1, outboxId)
            statement.setObject(2, channelId)
            statement.setObject(3, userId)
            statement.setString(4, "test:${outboxId}")
            statement.setString(5, objectMapper.writeValueAsString(payload))
            statement.executeUpdate()
        }
        return outboxId
    }

    companion object {
        private const val GROUPING_WINDOW_SECONDS = 15
    }
}
