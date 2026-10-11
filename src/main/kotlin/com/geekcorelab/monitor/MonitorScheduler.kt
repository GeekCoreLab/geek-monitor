package com.geekcorelab.monitor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
        16,
        16,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(500),
        ThreadFactory { task -> Thread(task, "geek-monitor-check-${threadNumber.incrementAndGet()}").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
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
                    "INSERT INTO check_execution(id, monitor_id, scheduled_at, result_status, interval_seconds) VALUES (?, ?, ?, 'RUNNING', ?)",
                ).use { statement ->
                    statement.setObject(1, task.executionId)
                    statement.setObject(2, task.id)
                    statement.setObject(3, java.sql.Timestamp.from(task.scheduledAt))
                    statement.setInt(4, task.intervalSeconds)
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
            markExecutionStarted(task.executionId)
            val config = objectMapper.readTree(task.config)
            if (task.type == "HEARTBEAT" && config is ObjectNode) {
                config.put("monitorId", task.id.toString())
                config.put("expectedIntervalSeconds", task.intervalSeconds)
            }
            if (task.type == "METRIC_THRESHOLD" && config is ObjectNode) {
                config.put("monitorId", task.id.toString())
                config.put("resourceId", task.resourceId.toString())
            }
            checker.execute(task.type, config, task.timeoutSeconds)
        } catch (_: Exception) {
            CheckResponse("UNKNOWN", null, "CHECKER_ERROR", "检查器发生内部错误", 0, Instant.now().toString())
        }
        persistResult(task, result)
    }

    private fun markExecutionStarted(executionId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE check_execution SET started_at = now() WHERE id = ? AND result_status = 'RUNNING'").use { statement ->
                statement.setObject(1, executionId)
                statement.executeUpdate()
            }
        }
    }

    private fun persistResult(task: CheckTask, result: CheckResponse) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val processedAt = connection.prepareStatement("SELECT now()").use { statement ->
                    statement.executeQuery().use { rows -> rows.next(); rows.getTimestamp(1).toInstant() }
                }
                connection.prepareStatement(
                    "UPDATE check_execution SET finished_at = ?, result_status = ?, error_code = ?, error_summary = ?, duration_ms = ?, http_status = ? WHERE id = ?",
                ).use { statement ->
                    statement.setObject(1, java.sql.Timestamp.from(processedAt))
                    statement.setString(2, result.status)
                    statement.setString(3, result.errorCode)
                    statement.setString(4, result.summary)
                    statement.setLong(5, result.durationMs)
                    if (result.statusCode == null) statement.setNull(6, java.sql.Types.INTEGER) else statement.setInt(6, result.statusCode)
                    statement.setObject(7, task.executionId)
                    statement.executeUpdate()
                }
                if (task.type in DATABASE_MONITOR_TYPES && result.status == "UP" && result.metrics.isNotEmpty()) {
                    persistDatabaseMetricSamples(connection, task, result)
                }

                val monitor = connection.prepareStatement(
                    "SELECT current_state, consecutive_failures, consecutive_successes, failure_threshold, recovery_threshold, critical, deleted_at, last_result_at FROM monitor WHERE id = ? FOR UPDATE",
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
                            rows.getTimestamp("last_result_at")?.toInstant(),
                        ) else null
                    }
                } ?: run {
                    connection.commit()
                    return
                }
                val maintenanceBoundaryCrossed = monitor.lastResultAt?.let { lastResultAt ->
                    connection.prepareStatement(
                        """SELECT EXISTS (
                            WITH windows AS (
                                SELECT w.id, w.starts_at, LEAST(COALESCE(w.canceled_at, w.ends_at), w.ends_at) AS effective_end
                                FROM maintenance_window w JOIN monitored_resource r ON r.project_id = w.project_id
                                WHERE r.id = ? AND (w.resource_id IS NULL OR w.resource_id = r.id)
                            ), boundaries AS (
                                SELECT id, starts_at AS boundary_at, 'START' AS boundary_type FROM windows
                                    WHERE starts_at > ? AND starts_at <= ? AND starts_at < effective_end
                                UNION ALL
                                SELECT id, effective_end AS boundary_at, 'END' AS boundary_type FROM windows
                                    WHERE effective_end > ? AND effective_end <= ? AND starts_at < effective_end
                            )
                            SELECT 1 FROM boundaries b WHERE
                                (b.boundary_type = 'START' AND NOT EXISTS (
                                    SELECT 1 FROM windows other WHERE other.id <> b.id
                                        AND other.starts_at < b.boundary_at AND other.effective_end >= b.boundary_at
                                )) OR
                                (b.boundary_type = 'END' AND NOT EXISTS (
                                    SELECT 1 FROM windows other WHERE other.id <> b.id
                                        AND other.starts_at <= b.boundary_at AND other.effective_end > b.boundary_at
                                ))
                        )""",
                    ).use { statement ->
                        statement.setObject(1, task.resourceId)
                        statement.setTimestamp(2, java.sql.Timestamp.from(lastResultAt))
                        statement.setTimestamp(3, java.sql.Timestamp.from(processedAt))
                        statement.setTimestamp(4, java.sql.Timestamp.from(lastResultAt))
                        statement.setTimestamp(5, java.sql.Timestamp.from(processedAt))
                        statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
                    }
                } ?: false
                val maintenanceActive = isMaintenanceActive(connection, task.resourceId, processedAt)
                processTlsCertificateAlert(connection, task, result)
                val previousFailures = if (maintenanceBoundaryCrossed) 0 else monitor.consecutiveFailures
                val previousSuccesses = if (maintenanceBoundaryCrossed) 0 else monitor.consecutiveSuccesses
                val success = result.status == "UP"
                val failure = result.status == "DOWN"
                val degraded = result.status == "DEGRADED"
                val failureEvidence = failure || degraded
                val failures = when {
                    success || !failureEvidence -> 0
                    else -> previousFailures + 1
                }
                val successes = if (success) previousSuccesses + 1 else 0
                val nextState = when {
                    result.status == "UNKNOWN" && monitor.currentState in setOf("DOWN", "DEGRADED") -> monitor.currentState
                    result.status == "UNKNOWN" -> "UNKNOWN"
                    success && monitor.currentState in setOf("DOWN", "DEGRADED") && successes >= monitor.recoveryThreshold -> "UP"
                    success && monitor.currentState in setOf("PENDING", "UNKNOWN") -> "UP"
                    failure && monitor.currentState != "DOWN" && failures >= monitor.failureThreshold -> "DOWN"
                    degraded && monitor.currentState !in setOf("DOWN", "DEGRADED") && failures >= monitor.failureThreshold -> "DEGRADED"
                    else -> monitor.currentState
                }
                connection.prepareStatement(
                    "UPDATE monitor SET current_state = ?, consecutive_failures = ?, consecutive_successes = ?, last_result_at = ?, updated_at = now() WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, nextState)
                    statement.setInt(2, failures)
                    statement.setInt(3, successes)
                    statement.setTimestamp(4, java.sql.Timestamp.from(processedAt))
                    statement.setObject(5, task.id)
                    statement.executeUpdate()
                }

                if (!monitor.deleted && nextState == "UP" && monitor.currentState != "UP") {
                    resolveIncident(connection, task)
                }
                if (!monitor.deleted && ((nextState == "DOWN" && failure) || (nextState == "DEGRADED" && degraded))) {
                    val incidentCategory = incidentCategory(task.type)
                    val activeIncident = connection.prepareStatement(
                        "SELECT id FROM incident WHERE monitor_id = ? AND category = ? AND status IN ('OPEN', 'ACKNOWLEDGED') FOR UPDATE",
                    ).use { statement ->
                        statement.setObject(1, task.id)
                        statement.setString(2, incidentCategory)
                        statement.executeQuery().use { rows -> if (rows.next()) rows.getObject("id", UUID::class.java) else null }
                    }
                    if (activeIncident != null && !(task.type == "TLS" && result.errorCode == "TLS_EXPIRING")) {
                        connection.prepareStatement("UPDATE incident SET occurrence_count = occurrence_count + 1 WHERE id = ?").use { statement ->
                            statement.setObject(1, activeIncident)
                            statement.executeUpdate()
                        }
                    } else if (activeIncident == null && !(task.type == "TLS" && result.errorCode == "TLS_EXPIRING") && !maintenanceActive && failures >= monitor.failureThreshold) {
                        val severity = if (task.type == "METRIC_THRESHOLD") {
                            objectMapper.readTree(task.config).path("severity").asText("WARNING")
                        } else "WARNING"
                        openIncident(connection, task, result, incidentCategory, severity)
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

    private fun isMaintenanceActive(connection: Connection, resourceId: UUID, at: Instant): Boolean =
        connection.prepareStatement(
            """SELECT EXISTS (
                SELECT 1 FROM maintenance_window w JOIN monitored_resource r ON r.project_id = w.project_id
                WHERE r.id = ? AND w.canceled_at IS NULL AND w.starts_at <= ? AND w.ends_at > ?
                  AND (w.resource_id IS NULL OR w.resource_id = r.id)
            )""",
        ).use { statement ->
            statement.setObject(1, resourceId)
            statement.setTimestamp(2, java.sql.Timestamp.from(at))
            statement.setTimestamp(3, java.sql.Timestamp.from(at))
            statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }

    private fun processTlsCertificateAlert(connection: Connection, task: CheckTask, result: CheckResponse) {
        if (task.type != "TLS") return
        val certificateExpiresAt = result.certificateExpiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return
        val daysRemaining = result.certificateDaysRemaining ?: return
        val thresholdDays = when {
            daysRemaining <= 7 -> 7
            daysRemaining <= 14 -> 14
            daysRemaining <= 30 -> 30
            else -> null
        }
        val state = connection.prepareStatement(
            """SELECT s.incident_id, s.certificate_expires_at, s.threshold_days, i.status AS incident_status
                FROM tls_certificate_alert_state s JOIN incident i ON i.id = s.incident_id
                WHERE s.monitor_id = ? FOR UPDATE OF s""",
        ).use { statement ->
            statement.setObject(1, task.id)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else TlsCertificateAlertState(
                    incidentId = rows.getObject("incident_id", UUID::class.java),
                    certificateExpiresAt = rows.getTimestamp("certificate_expires_at").toInstant(),
                    thresholdDays = rows.getInt("threshold_days"),
                    incidentStatus = rows.getString("incident_status"),
                )
            }
        }
        if (state?.incidentStatus == "RESOLVED") {
            connection.prepareStatement("DELETE FROM tls_certificate_alert_state WHERE monitor_id = ?").use { statement ->
                statement.setObject(1, task.id)
                statement.executeUpdate()
            }
        }
        val activeState = state?.takeUnless { it.incidentStatus == "RESOLVED" }
        if (thresholdDays == null) {
            if (activeState != null) resolveTlsCertificateIncident(connection, task, activeState.incidentId, daysRemaining, certificateExpiresAt)
            return
        }
        if (activeState == null) {
            val incidentId = openTlsCertificateIncident(connection, task, thresholdDays, daysRemaining, certificateExpiresAt)
            connection.prepareStatement(
                "INSERT INTO tls_certificate_alert_state(monitor_id, incident_id, certificate_expires_at, threshold_days) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, task.id)
                statement.setObject(2, incidentId)
                statement.setTimestamp(3, java.sql.Timestamp.from(certificateExpiresAt))
                statement.setInt(4, thresholdDays)
                statement.executeUpdate()
            }
            return
        }
        if (thresholdDays != activeState.thresholdDays) {
            val severity = tlsSeverity(thresholdDays)
            val title = "${task.resourceName} · TLS 证书将在 $thresholdDays 天内到期"
            connection.prepareStatement("UPDATE incident SET severity = ?, title = ?, last_notified_at = CASE WHEN ? THEN now() ELSE last_notified_at END WHERE id = ? AND status IN ('OPEN', 'ACKNOWLEDGED')").use { statement ->
                statement.setString(1, severity)
                statement.setString(2, title)
                statement.setBoolean(3, thresholdDays < activeState.thresholdDays)
                statement.setObject(4, activeState.incidentId)
                statement.executeUpdate()
            }
            if (thresholdDays < activeState.thresholdDays) {
                val eventId = UUID.randomUUID()
                val note = "TLS 证书剩余 $daysRemaining 天，提醒级别升级为 $thresholdDays 天。"
                val details = mapOf(
                    "fromThresholdDays" to activeState.thresholdDays,
                    "thresholdDays" to thresholdDays,
                    "daysRemaining" to daysRemaining,
                    "certificateExpiresAt" to certificateExpiresAt.toString(),
                )
                connection.prepareStatement(
                    "INSERT INTO incident_event(id, incident_id, event_type, note, details) VALUES (?, ?, 'ESCALATED', ?, CAST(? AS jsonb))",
                ).use { statement ->
                    statement.setObject(1, eventId)
                    statement.setObject(2, activeState.incidentId)
                    statement.setString(3, note)
                    statement.setString(4, objectMapper.writeValueAsString(details))
                    statement.executeUpdate()
                }
                notificationOutbox.enqueueIncident(
                    connection,
                    activeState.incidentId,
                    eventId,
                    "ESCALATED",
                    tlsNotificationPayload(eventId, activeState.incidentId, "incident.escalated", title, note, severity, daysRemaining, thresholdDays, certificateExpiresAt, task),
                )
            } else {
                val note = "TLS 证书当前剩余 $daysRemaining 天，到期时间为 ${certificateExpiresAt}."
                connection.prepareStatement(
                    "INSERT INTO incident_event(id, incident_id, event_type, note, details) VALUES (?, ?, 'NOTE', ?, CAST(? AS jsonb))",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, activeState.incidentId)
                    statement.setString(3, note)
                    statement.setString(4, objectMapper.writeValueAsString(mapOf("thresholdDays" to thresholdDays, "daysRemaining" to daysRemaining, "certificateExpiresAt" to certificateExpiresAt.toString())))
                    statement.executeUpdate()
                }
            }
        }
        connection.prepareStatement(
            "UPDATE tls_certificate_alert_state SET certificate_expires_at = ?, threshold_days = ?, updated_at = now() WHERE monitor_id = ?",
        ).use { statement ->
            statement.setTimestamp(1, java.sql.Timestamp.from(certificateExpiresAt))
            statement.setInt(2, thresholdDays)
            statement.setObject(3, task.id)
            statement.executeUpdate()
        }
    }

    private fun openTlsCertificateIncident(
        connection: Connection,
        task: CheckTask,
        thresholdDays: Int,
        daysRemaining: Long,
        certificateExpiresAt: Instant,
    ): UUID {
        val incidentId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val severity = tlsSeverity(thresholdDays)
        val title = "${task.resourceName} · TLS 证书将在 $thresholdDays 天内到期"
        val message = "${task.monitorName} 证书剩余 $daysRemaining 天，到期时间为 $certificateExpiresAt。"
        connection.prepareStatement(
            "INSERT INTO incident(id, deduplication_key, monitor_id, resource_id, category, severity, status, title) VALUES (?, ?, ?, ?, 'TLS_CERTIFICATE', ?, 'OPEN', ?)",
        ).use { statement ->
            statement.setObject(1, incidentId)
            statement.setString(2, "${task.id}:TLS_CERTIFICATE:$incidentId")
            statement.setObject(3, task.id)
            statement.setObject(4, task.resourceId)
            statement.setString(5, severity)
            statement.setString(6, title)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO incident_event(id, incident_id, event_type, note, details) VALUES (?, ?, 'OPENED', ?, CAST(? AS jsonb))",
        ).use { statement ->
            statement.setObject(1, eventId)
            statement.setObject(2, incidentId)
            statement.setString(3, message)
            statement.setString(4, objectMapper.writeValueAsString(mapOf("category" to "TLS_CERTIFICATE", "severity" to severity, "daysRemaining" to daysRemaining, "thresholdDays" to thresholdDays, "certificateExpiresAt" to certificateExpiresAt.toString())))
            statement.executeUpdate()
        }
        notificationOutbox.enqueueIncident(
            connection,
            incidentId,
            eventId,
            "OPENED",
            tlsNotificationPayload(eventId, incidentId, "incident.opened", title, message, severity, daysRemaining, thresholdDays, certificateExpiresAt, task),
        )
        return incidentId
    }

    private fun resolveTlsCertificateIncident(
        connection: Connection,
        task: CheckTask,
        incidentId: UUID,
        daysRemaining: Long,
        certificateExpiresAt: Instant,
    ) {
        val updated = connection.prepareStatement(
            "UPDATE incident SET status = 'RESOLVED', resolved_at = now() WHERE id = ? AND status IN ('OPEN', 'ACKNOWLEDGED')",
        ).use { statement ->
            statement.setObject(1, incidentId)
            statement.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM tls_certificate_alert_state WHERE monitor_id = ?").use { statement ->
            statement.setObject(1, task.id)
            statement.executeUpdate()
        }
        if (updated != 1) return
        val eventId = UUID.randomUUID()
        val note = "TLS 证书当前剩余 $daysRemaining 天，到期时间为 $certificateExpiresAt；30 天提醒已关闭。"
        connection.prepareStatement(
            "INSERT INTO incident_event(id, incident_id, event_type, note, details) VALUES (?, ?, 'RESOLVED', ?, CAST(? AS jsonb))",
        ).use { statement ->
            statement.setObject(1, eventId)
            statement.setObject(2, incidentId)
            statement.setString(3, note)
            statement.setString(4, objectMapper.writeValueAsString(mapOf("daysRemaining" to daysRemaining, "certificateExpiresAt" to certificateExpiresAt.toString())))
            statement.executeUpdate()
        }
        notificationOutbox.enqueueIncident(
            connection,
            incidentId,
            eventId,
            "RESOLVED",
            mapOf(
                "eventId" to eventId.toString(), "eventType" to "incident.resolved", "incidentId" to incidentId.toString(),
                "category" to "TLS_CERTIFICATE", "title" to "${task.resourceName} · TLS 证书到期提醒已解除",
                "message" to note, "resourceName" to task.resourceName, "monitorName" to task.monitorName,
                "severity" to "INFO", "status" to "UP", "daysRemaining" to daysRemaining,
                "certificateExpiresAt" to certificateExpiresAt.toString(), "occurredAt" to Instant.now().toString(),
            ),
        )
    }

    private fun tlsNotificationPayload(
        eventId: UUID,
        incidentId: UUID,
        eventType: String,
        title: String,
        message: String,
        severity: String,
        daysRemaining: Long,
        thresholdDays: Int,
        certificateExpiresAt: Instant,
        task: CheckTask,
    ) = mapOf(
        "eventId" to eventId.toString(), "eventType" to eventType,
        "incidentId" to incidentId.toString(), "category" to "TLS_CERTIFICATE", "title" to title,
        "message" to message, "resourceName" to task.resourceName, "monitorName" to task.monitorName,
        "severity" to severity, "status" to "UP", "daysRemaining" to daysRemaining,
        "thresholdDays" to thresholdDays, "certificateExpiresAt" to certificateExpiresAt.toString(),
        "occurredAt" to Instant.now().toString(),
    )

    private fun tlsSeverity(thresholdDays: Int) = when (thresholdDays) {
        7 -> "CRITICAL"
        14 -> "HIGH"
        else -> "WARNING"
    }

    private fun openIncident(
        connection: Connection,
        task: CheckTask,
        result: CheckResponse,
        category: String,
        severity: String,
    ) {
        val incidentId = UUID.randomUUID()
        val performanceIncident = category == "PERFORMANCE"
        val collectionIncident = category == "COLLECTION"
        val title = when {
            performanceIncident -> "${task.resourceName} · ${task.monitorName} 指标超过阈值"
            collectionIncident -> "${task.resourceName} · Agent 采集已失联"
            else -> "${task.resourceName} · ${task.monitorName} 检查失败"
        }
        val message = when {
            performanceIncident -> result.summary ?: "${task.resourceName} 的 ${task.monitorName} 指标达到阈值。"
            collectionIncident -> result.summary ?: "主机 Agent 已超过预期间隔未上报。"
            else -> "${task.resourceName} 的 ${task.monitorName} 检查达到失败阈值。"
        }
        connection.prepareStatement(
            "INSERT INTO incident(id, deduplication_key, monitor_id, resource_id, category, severity, status, title) VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?)",
        ).use { statement ->
            statement.setObject(1, incidentId)
            statement.setString(2, "${task.id}:$incidentId")
            statement.setObject(3, task.id)
            statement.setObject(4, task.resourceId)
            statement.setString(5, category)
            statement.setString(6, severity)
            statement.setString(7, title)
            statement.executeUpdate()
        }
        val eventId = UUID.randomUUID()
        connection.prepareStatement(
            "INSERT INTO incident_event(id, incident_id, event_type, note, details) VALUES (?, ?, 'OPENED', ?, CAST(? AS jsonb))",
        ).use { statement ->
            statement.setObject(1, eventId)
            statement.setObject(2, incidentId)
            statement.setString(3, result.summary.takeIf { performanceIncident || collectionIncident })
            statement.setString(4, objectMapper.writeValueAsString(mapOf("category" to category, "errorCode" to result.errorCode, "statusCode" to result.statusCode)))
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
                "category" to category,
                "title" to title,
                "message" to message,
                "resourceName" to task.resourceName,
                "monitorName" to task.monitorName,
                "severity" to severity,
                "status" to if (performanceIncident) "DEGRADED" else "DOWN",
                "occurredAt" to result.checkedAt,
                "errorCode" to result.errorCode,
                "statusCode" to result.statusCode,
            ),
        )
    }

    private fun resolveIncident(connection: Connection, task: CheckTask) {
        val category = incidentCategory(task.type)
        val active = connection.prepareStatement(
            "SELECT id FROM incident WHERE monitor_id = ? AND category = ? AND status IN ('OPEN', 'ACKNOWLEDGED') FOR UPDATE",
        ).use { statement ->
            statement.setObject(1, task.id)
            statement.setString(2, category)
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

    private fun incidentCategory(type: String) = when (type) {
        "METRIC_THRESHOLD" -> "PERFORMANCE"
        "AGENT_OFFLINE" -> "COLLECTION"
        else -> "AVAILABILITY"
    }

    private fun persistDatabaseMetricSamples(connection: Connection, task: CheckTask, result: CheckResponse) {
        val sampledAt = runCatching { Instant.parse(result.checkedAt) }.getOrElse { Instant.now() }
        connection.prepareStatement(
            """INSERT INTO database_metric_sample(monitor_id, resource_id, metric_name, labels, value, sampled_at)
               VALUES (?, ?, ?, '{}'::jsonb, ?, ?) ON CONFLICT DO NOTHING""",
        ).use { statement ->
            result.metrics.forEach { (name, value) ->
                if (name !in com.geekcorelab.api.MetricCatalog.databaseMetricNames || !value.isFinite() || value < 0) return@forEach
                statement.setObject(1, task.id)
                statement.setObject(2, task.resourceId)
                statement.setString(3, name)
                statement.setDouble(4, value)
                statement.setTimestamp(5, java.sql.Timestamp.from(sampledAt))
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun updateResourceHealth(connection: Connection, resourceId: UUID) {
        val aggregate = connection.prepareStatement(
            "SELECT count(*) AS total, bool_or(current_state = 'DOWN') AS has_down, bool_or(current_state = 'UNKNOWN') AS has_unknown, bool_or(current_state = 'PENDING') AS has_pending, bool_or(current_state = 'DEGRADED') AS has_degraded FROM monitor WHERE resource_id = ? AND critical AND enabled AND deleted_at IS NULL",
        ).use { statement ->
            statement.setObject(1, resourceId)
            statement.executeQuery().use { rows ->
                rows.next()
                ResourceAggregate(rows.getInt("total"), rows.getBoolean("has_down"), rows.getBoolean("has_unknown"), rows.getBoolean("has_pending"), rows.getBoolean("has_degraded"))
            }
        }
        val status = when {
            aggregate.total == 0 -> "PENDING"
            aggregate.hasDown -> "DOWN"
            aggregate.hasUnknown -> "UNKNOWN"
            aggregate.hasPending -> "PENDING"
            aggregate.hasDegraded -> "DEGRADED"
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
        val lastResultAt: Instant?,
    )

    private data class TlsCertificateAlertState(
        val incidentId: UUID,
        val certificateExpiresAt: Instant,
        val thresholdDays: Int,
        val incidentStatus: String,
    )

    private data class ResourceAggregate(
        val total: Int,
        val hasDown: Boolean,
        val hasUnknown: Boolean,
        val hasPending: Boolean,
        val hasDegraded: Boolean,
    )

    companion object {
        private const val MAX_CLAIM_PER_TICK = 500
        private val DATABASE_MONITOR_TYPES = setOf("POSTGRESQL", "MYSQL", "REDIS")
    }
}
