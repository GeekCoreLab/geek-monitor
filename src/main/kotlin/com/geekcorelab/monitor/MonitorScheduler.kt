package com.geekcorelab.monitor

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekcorelab.api.CheckResponse
import com.geekcorelab.notification.NotificationOutbox
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@ApplicationScoped
class MonitorScheduler {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var objectMapper: ObjectMapper
    @Inject lateinit var checker: ProtocolCheckService
    @Inject lateinit var notificationOutbox: NotificationOutbox

    private val threadNumber = AtomicInteger()
    private val executor = ThreadPoolExecutor(
        4,
        4,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(100),
        ThreadFactory { task -> Thread(task, "geek-monitor-check-${threadNumber.incrementAndGet()}").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    @Scheduled(every = "2s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun dispatchDueChecks() {
        val capacity = executor.queue.remainingCapacity().coerceAtMost(MAX_CLAIM_PER_TICK)
        if (capacity <= 0) return
        claimDueChecks(capacity).forEach { task ->
            try {
                executor.execute { execute(task) }
            } catch (_: RejectedExecutionException) {
                persistResult(task, CheckResponse("UNKNOWN", null, "EXECUTOR_BUSY", "检查执行队列已满", 0, Instant.now().toString()))
            }
        }
    }

    @PreDestroy
    fun stop() {
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
    }

    private fun claimDueChecks(limit: Int): List<CheckTask> = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val tasks = mutableListOf<CheckTask>()
            connection.prepareStatement(
                """SELECT m.id, m.resource_id, m.name, m.type, r.name AS resource_name, m.config::text AS config,
                    m.interval_seconds, m.timeout_seconds, m.next_run_at
                    FROM monitor m JOIN monitored_resource r ON r.id = m.resource_id
                    WHERE m.enabled AND m.deleted_at IS NULL AND r.deleted_at IS NULL AND NOT r.paused
                      AND m.next_run_at <= now()
                    ORDER BY m.next_run_at LIMIT ? FOR UPDATE OF m SKIP LOCKED""",
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rows -> while (rows.next()) tasks += rows.toTask() }
            }
            tasks.forEach { task ->
                connection.prepareStatement(
                    "INSERT INTO check_execution(id, monitor_id, scheduled_at, started_at, result_status) VALUES (?, ?, ?, now(), 'RUNNING')",
                ).use { statement ->
                    statement.setObject(1, task.executionId)
                    statement.setObject(2, task.id)
                    statement.setObject(3, java.sql.Timestamp.from(task.scheduledAt))
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "UPDATE monitor SET next_run_at = now() + (? * INTERVAL '1 second'), updated_at = now() WHERE id = ?",
                ).use { statement ->
                    statement.setInt(1, task.intervalSeconds)
                    statement.setObject(2, task.id)
                    statement.executeUpdate()
                }
            }
            connection.commit()
            tasks
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        }
    }

    private fun ResultSet.toTask(): CheckTask {
        val intervalSeconds = getInt("interval_seconds")
        return CheckTask(
            executionId = UUID.randomUUID(),
            id = getObject("id", UUID::class.java),
            resourceId = getObject("resource_id", UUID::class.java),
            monitorName = getString("name"),
            resourceName = getString("resource_name"),
            type = getString("type"),
            config = getString("config"),
            intervalSeconds = intervalSeconds,
            timeoutSeconds = getInt("timeout_seconds"),
            scheduledAt = getTimestamp("next_run_at").toInstant(),
        )
    }

    private fun execute(task: CheckTask) {
        val result = try {
            checker.execute(task.type, objectMapper.readTree(task.config), task.timeoutSeconds)
        } catch (_: Exception) {
            CheckResponse("UNKNOWN", null, "CHECKER_ERROR", "检查器发生内部错误", 0, Instant.now().toString())
        }
        persistResult(task, result)
    }

    private fun persistResult(task: CheckTask, result: CheckResponse) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "UPDATE check_execution SET finished_at = ?, result_status = ?, error_code = ?, error_summary = ?, duration_ms = ?, http_status = ? WHERE id = ?",
                ).use { statement ->
                    statement.setObject(1, java.sql.Timestamp.from(Instant.parse(result.checkedAt)))
                    statement.setString(2, result.status)
                    statement.setString(3, result.errorCode)
                    statement.setString(4, result.summary)
                    statement.setLong(5, result.durationMs)
                    if (result.statusCode == null) statement.setNull(6, java.sql.Types.INTEGER) else statement.setInt(6, result.statusCode)
                    statement.setObject(7, task.executionId)
                    statement.executeUpdate()
                }

                val monitor = connection.prepareStatement(
                    "SELECT current_state, consecutive_failures, consecutive_successes, failure_threshold, recovery_threshold, critical, deleted_at FROM monitor WHERE id = ? FOR UPDATE",
                ).use { statement ->
                    statement.setObject(1, task.id)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) MonitorState(
                            rows.getString("current_state"),
                            rows.getInt("consecutive_failures"),
                            rows.getInt("consecutive_successes"),
                            rows.getInt("failure_threshold"),
                            rows.getInt("recovery_threshold"),
                            rows.getBoolean("critical"),
                            rows.getTimestamp("deleted_at") != null,
                        ) else null
                    }
                } ?: run {
                    connection.commit()
                    return
                }
                val success = result.status == "UP"
                val failure = result.status == "DOWN"
                val failures = when {
                    success || !failure -> 0
                    else -> monitor.consecutiveFailures + 1
                }
                val successes = if (success) monitor.consecutiveSuccesses + 1 else 0
                val nextState = when {
                    result.status == "UNKNOWN" && monitor.currentState == "DOWN" -> "DOWN"
                    result.status == "UNKNOWN" -> "UNKNOWN"
                    success && monitor.currentState == "DOWN" && successes >= monitor.recoveryThreshold -> "UP"
                    success && monitor.currentState in setOf("PENDING", "UNKNOWN") -> "UP"
                    failure && monitor.currentState != "DOWN" && failures >= monitor.failureThreshold -> "DOWN"
                    else -> monitor.currentState
                }
                connection.prepareStatement(
                    "UPDATE monitor SET current_state = ?, consecutive_failures = ?, consecutive_successes = ?, last_result_at = now(), updated_at = now() WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, nextState)
                    statement.setInt(2, failures)
                    statement.setInt(3, successes)
                    statement.setObject(4, task.id)
                    statement.executeUpdate()
                }

                if (!monitor.deleted && monitor.currentState != nextState) {
                    when (nextState) {
                        "DOWN" -> openIncident(connection, task, result)
                        "UP" -> resolveIncident(connection, task)
                    }
                } else if (!monitor.deleted && nextState == "DOWN" && failure) {
                    connection.prepareStatement(
                        "UPDATE incident SET occurrence_count = occurrence_count + 1 WHERE monitor_id = ? AND status IN ('OPEN', 'ACKNOWLEDGED')",
                    ).use { statement ->
                        statement.setObject(1, task.id)
                        statement.executeUpdate()
                    }
                }

                if (monitor.critical && !monitor.deleted) updateResourceHealth(connection, task.resourceId)
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
    }

    private fun openIncident(connection: Connection, task: CheckTask, result: CheckResponse) {
        val incidentId = UUID.randomUUID()
        connection.prepareStatement(
            "INSERT INTO incident(id, deduplication_key, monitor_id, resource_id, severity, status, title) VALUES (?, ?, ?, ?, 'WARNING', 'OPEN', ?)",
        ).use { statement ->
            statement.setObject(1, incidentId)
            statement.setString(2, "${task.id}:$incidentId")
            statement.setObject(3, task.id)
            statement.setObject(4, task.resourceId)
            statement.setString(5, "${task.resourceName} · ${task.monitorName} 检查失败")
            statement.executeUpdate()
        }
        val eventId = UUID.randomUUID()
        connection.prepareStatement(
            "INSERT INTO incident_event(id, incident_id, event_type, details) VALUES (?, ?, 'OPENED', CAST(? AS jsonb))",
        ).use { statement ->
            statement.setObject(1, eventId)
            statement.setObject(2, incidentId)
            statement.setString(3, objectMapper.writeValueAsString(mapOf("errorCode" to result.errorCode, "statusCode" to result.statusCode)))
            statement.executeUpdate()
        }
        notificationOutbox.enqueueIncident(
            connection,
            incidentId,
            eventId,
            "OPENED",
            mapOf(
                "eventId" to eventId.toString(),
                "eventType" to "incident.opened",
                "incidentId" to incidentId.toString(),
                "title" to "${task.resourceName} · ${task.monitorName} 检查失败",
                "message" to "${task.resourceName} 的 ${task.monitorName} 检查达到失败阈值。",
                "resourceName" to task.resourceName,
                "monitorName" to task.monitorName,
                "severity" to "WARNING",
                "status" to "DOWN",
                "occurredAt" to result.checkedAt,
                "errorCode" to result.errorCode,
                "statusCode" to result.statusCode,
            ),
        )
    }

    private fun resolveIncident(connection: Connection, task: CheckTask) {
        val active = connection.prepareStatement(
            "SELECT id FROM incident WHERE monitor_id = ? AND status IN ('OPEN', 'ACKNOWLEDGED') FOR UPDATE",
        ).use { statement ->
            statement.setObject(1, task.id)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getObject("id", UUID::class.java) else null }
        } ?: return
        connection.prepareStatement("UPDATE incident SET status = 'RESOLVED', resolved_at = now() WHERE id = ?").use { statement ->
            statement.setObject(1, active)
            statement.executeUpdate()
        }
        val eventId = UUID.randomUUID()
        connection.prepareStatement("INSERT INTO incident_event(id, incident_id, event_type) VALUES (?, ?, 'RESOLVED')").use { statement ->
            statement.setObject(1, eventId)
            statement.setObject(2, active)
            statement.executeUpdate()
        }
        notificationOutbox.enqueueIncident(
            connection,
            active,
            eventId,
            "RESOLVED",
            mapOf(
                "eventId" to eventId.toString(),
                "eventType" to "incident.resolved",
                "incidentId" to active.toString(),
                "title" to "${task.resourceName} · ${task.monitorName} 已恢复",
                "message" to "${task.resourceName} 的 ${task.monitorName} 已连续成功并恢复。",
                "resourceName" to task.resourceName,
                "monitorName" to task.monitorName,
                "severity" to "INFO",
                "status" to "UP",
                "occurredAt" to Instant.now().toString(),
            ),
        )
    }

    private fun updateResourceHealth(connection: Connection, resourceId: UUID) {
        val aggregate = connection.prepareStatement(
            "SELECT count(*) AS total, bool_or(current_state = 'DOWN') AS has_down, bool_or(current_state = 'UNKNOWN') AS has_unknown, bool_or(current_state = 'PENDING') AS has_pending FROM monitor WHERE resource_id = ? AND critical AND enabled AND deleted_at IS NULL",
        ).use { statement ->
            statement.setObject(1, resourceId)
            statement.executeQuery().use { rows ->
                rows.next()
                ResourceAggregate(rows.getInt("total"), rows.getBoolean("has_down"), rows.getBoolean("has_unknown"), rows.getBoolean("has_pending"))
            }
        }
        val status = when {
            aggregate.total == 0 -> "PENDING"
            aggregate.hasDown -> "DOWN"
            aggregate.hasUnknown -> "UNKNOWN"
            aggregate.hasPending -> "PENDING"
            else -> "UP"
        }
        connection.prepareStatement("UPDATE monitored_resource SET health_status = ?, updated_at = now() WHERE id = ?").use { statement ->
            statement.setString(1, status)
            statement.setObject(2, resourceId)
            statement.executeUpdate()
        }
    }

    private data class CheckTask(
        val executionId: UUID,
        val id: UUID,
        val resourceId: UUID,
        val monitorName: String,
        val resourceName: String,
        val type: String,
        val config: String,
        val intervalSeconds: Int,
        val timeoutSeconds: Int,
        val scheduledAt: Instant,
    )

    private data class MonitorState(
        val currentState: String,
        val consecutiveFailures: Int,
        val consecutiveSuccesses: Int,
        val failureThreshold: Int,
        val recoveryThreshold: Int,
        val critical: Boolean,
        val deleted: Boolean,
    )

    private data class ResourceAggregate(val total: Int, val hasDown: Boolean, val hasUnknown: Boolean, val hasPending: Boolean)

    companion object {
        private const val MAX_CLAIM_PER_TICK = 20
    }
}
