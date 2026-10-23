package com.geekcorelab.geekmonitor.monitor

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekcorelab.geekmonitor.notification.NotificationOutbox
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@ApplicationScoped
class IncidentReminderScheduler {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var objectMapper: ObjectMapper
    @Inject lateinit var notificationOutbox: NotificationOutbox

    @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun enqueueDueReminders() {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val due = connection.prepareStatement(
                    """SELECT i.id, i.title, i.category, i.severity, i.opened_at, i.last_notified_at,
                              i.occurrence_count, m.name AS monitor_name, m.reminder_interval_minutes,
                              r.name AS resource_name
                       FROM incident i
                       JOIN monitor m ON m.id = i.monitor_id
                       JOIN monitored_resource r ON r.id = i.resource_id
                       WHERE i.status IN ('OPEN', 'ACKNOWLEDGED')
                         AND i.category <> 'TLS_CERTIFICATE'
                         AND m.reminder_interval_minutes > 0
                         AND m.deleted_at IS NULL AND r.deleted_at IS NULL
                         AND i.last_notified_at <= now() - (m.reminder_interval_minutes * INTERVAL '1 minute')
                       ORDER BY i.last_notified_at, i.id
                       LIMIT ? FOR UPDATE OF i SKIP LOCKED""",
                ).use { statement ->
                    statement.setInt(1, MAX_REMINDERS_PER_TICK)
                    statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toReminder()) } }
                }

                val now = Instant.now()
                due.forEach { reminder -> enqueueReminder(connection, reminder, now) }
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
    }

    private fun enqueueReminder(connection: Connection, reminder: DueReminder, now: Instant) {
        val eventId = UUID.randomUUID()
        val durationMinutes = Duration.between(reminder.openedAt, now).toMinutes().coerceAtLeast(0)
        val note = "故障仍未恢复，已持续 $durationMinutes 分钟；按配置每 ${reminder.intervalMinutes} 分钟提醒一次。"
        val details = mapOf(
            "category" to reminder.category,
            "severity" to reminder.severity,
            "occurrenceCount" to reminder.occurrenceCount,
            "openedAt" to reminder.openedAt.toString(),
            "previousNotifiedAt" to reminder.lastNotifiedAt.toString(),
            "reminderIntervalMinutes" to reminder.intervalMinutes,
            "durationMinutes" to durationMinutes,
        )
        connection.prepareStatement(
            "INSERT INTO incident_event(id, incident_id, event_type, note, details) VALUES (?, ?, 'REMINDER', ?, CAST(? AS jsonb))",
        ).use { statement ->
            statement.setObject(1, eventId)
            statement.setObject(2, reminder.incidentId)
            statement.setString(3, note)
            statement.setString(4, objectMapper.writeValueAsString(details))
            statement.executeUpdate()
        }
        connection.prepareStatement("UPDATE incident SET last_notified_at = ? WHERE id = ?").use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setObject(2, reminder.incidentId)
            statement.executeUpdate()
        }
        notificationOutbox.enqueueIncident(
            connection,
            reminder.incidentId,
            eventId,
            "REMINDER",
            mapOf(
                "eventId" to eventId.toString(),
                "eventType" to "incident.reminder",
                "incidentId" to reminder.incidentId.toString(),
                "category" to reminder.category,
                "title" to "持续故障提醒：${reminder.title}",
                "message" to note,
                "resourceName" to reminder.resourceName,
                "monitorName" to reminder.monitorName,
                "severity" to reminder.severity,
                "status" to if (reminder.category == "TLS_CERTIFICATE") "UP" else "DOWN",
                "occurrenceCount" to reminder.occurrenceCount,
                "openedAt" to reminder.openedAt.toString(),
                "occurredAt" to now.toString(),
            ),
        )
    }

    private fun ResultSet.toReminder() = DueReminder(
        incidentId = getObject("id", UUID::class.java),
        title = getString("title"),
        category = getString("category"),
        severity = getString("severity"),
        openedAt = getTimestamp("opened_at").toInstant(),
        lastNotifiedAt = getTimestamp("last_notified_at").toInstant(),
        occurrenceCount = getInt("occurrence_count"),
        monitorName = getString("monitor_name"),
        intervalMinutes = getInt("reminder_interval_minutes"),
        resourceName = getString("resource_name"),
    )

    private data class DueReminder(
        val incidentId: UUID,
        val title: String,
        val category: String,
        val severity: String,
        val openedAt: Instant,
        val lastNotifiedAt: Instant,
        val occurrenceCount: Int,
        val monitorName: String,
        val intervalMinutes: Int,
        val resourceName: String,
    )

    companion object {
        private const val MAX_REMINDERS_PER_TICK = 50
    }
}
