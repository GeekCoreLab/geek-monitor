package com.geekcorelab.monitor

import com.geekcorelab.api.PlatformTelemetry
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.Timestamp
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

private typealias TimeSpan = AvailabilitySpan

@ApplicationScoped
class MetricRetentionScheduler {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var platformTelemetry: PlatformTelemetry

    @Scheduled(every = "\${geek-monitor.retention.interval:1m}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun retain() {
        val now = Instant.now()
        platformTelemetry.retentionStarted(now)
        try {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    lockJob(connection)
                    compactRawMetrics(connection, now)
                    compactFiveMinuteRollups(connection, now)
                    deleteExpiredRollups(connection, now)
                    rollupAvailability(connection, now)
                    deleteExpiredCheckExecutions(connection, now)
                    deleteExpiredAvailabilityRollups(connection, now)
                    deleteExpiredAgentBatches(connection, now)
                    deleteExpiredNotificationOutbox(connection, now)
                    deleteExpiredResolvedIncidents(connection, now)
                    deleteExpiredAuditEntries(connection, now)
                    connection.commit()
                } catch (exception: Exception) {
                    connection.rollback()
                    throw exception
                }
            }
            platformTelemetry.retentionSucceeded()
        } catch (exception: Exception) {
            platformTelemetry.retentionFailed(failureType = exception::class.simpleName ?: "Exception")
            throw exception
        }
    }

    private fun lockJob(connection: Connection) {
        connection.prepareStatement("SELECT pg_advisory_xact_lock(77324, 18)").use { statement ->
            statement.executeQuery().use { rows -> rows.next() }
        }
    }

    private fun compactRawMetrics(connection: Connection, now: Instant) {
        val cutoff = now.minusSeconds(RAW_METRIC_RETENTION_SECONDS)
        val oldest = connection.prepareStatement(
            """SELECT min(sampled_at) FROM (
                   SELECT min(sampled_at) AS sampled_at FROM metric_sample WHERE sampled_at < ?
                   UNION ALL
                   SELECT min(sampled_at) AS sampled_at FROM database_metric_sample WHERE sampled_at < ?
               ) old_samples""",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(cutoff))
            statement.setTimestamp(2, Timestamp.from(cutoff))
            statement.executeQuery().use { rows -> rows.next(); rows.getTimestamp(1)?.toInstant() }
        } ?: return

        val start = floor(oldest, FIVE_MINUTE_SECONDS)
        val end = minOf(start.plusSeconds(RAW_BATCH_SECONDS), floor(cutoff, FIVE_MINUTE_SECONDS))
        if (!end.isAfter(start)) return

        connection.prepareStatement(
            """INSERT INTO metric_rollup(resource_id, metric_name, labels, resolution_seconds, bucket_at,
                        sample_count, sum_value, min_value, max_value)
               SELECT resource_id, metric_name, labels, 300,
                      date_bin(INTERVAL '5 minutes', sampled_at, TIMESTAMPTZ '1970-01-01 00:00:00+00'),
                      count(*)::bigint, sum(value), min(value), max(value)
               FROM (
                   SELECT resource_id, metric_name, labels, value, sampled_at
                   FROM metric_sample
                   WHERE trusted AND sampled_at >= ? AND sampled_at < ?
                   UNION ALL
                   SELECT resource_id, metric_name, labels, value, sampled_at
                   FROM database_metric_sample
                   WHERE sampled_at >= ? AND sampled_at < ?
               ) raw_samples
               GROUP BY resource_id, metric_name, labels,
                        date_bin(INTERVAL '5 minutes', sampled_at, TIMESTAMPTZ '1970-01-01 00:00:00+00')
               ON CONFLICT (resource_id, metric_name, labels, resolution_seconds, bucket_at) DO UPDATE SET
                   sample_count = metric_rollup.sample_count + EXCLUDED.sample_count,
                   sum_value = metric_rollup.sum_value + EXCLUDED.sum_value,
                   min_value = LEAST(metric_rollup.min_value, EXCLUDED.min_value),
                   max_value = GREATEST(metric_rollup.max_value, EXCLUDED.max_value)""",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(start))
            statement.setTimestamp(2, Timestamp.from(end))
            statement.setTimestamp(3, Timestamp.from(start))
            statement.setTimestamp(4, Timestamp.from(end))
            statement.executeUpdate()
        }
        deleteMetricSamples(connection, "metric_sample", start, end)
        deleteMetricSamples(connection, "database_metric_sample", start, end)
    }

    private fun deleteMetricSamples(connection: Connection, table: String, start: Instant, end: Instant) {
        connection.prepareStatement("DELETE FROM $table WHERE sampled_at >= ? AND sampled_at < ?").use { statement ->
            statement.setTimestamp(1, Timestamp.from(start))
            statement.setTimestamp(2, Timestamp.from(end))
            statement.executeUpdate()
        }
    }

    private fun compactFiveMinuteRollups(connection: Connection, now: Instant) {
        val cutoff = now.minusSeconds(FIVE_MINUTE_ROLLUP_RETENTION_SECONDS)
        val oldest = connection.prepareStatement(
            "SELECT min(bucket_at) FROM metric_rollup WHERE resolution_seconds = 300 AND bucket_at < ?",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(cutoff))
            statement.executeQuery().use { rows -> rows.next(); rows.getTimestamp(1)?.toInstant() }
        } ?: return

        val start = floor(oldest, HOUR_SECONDS)
        val end = minOf(start.plusSeconds(ROLLUP_BATCH_SECONDS), floor(cutoff, HOUR_SECONDS))
        if (!end.isAfter(start)) return

        connection.prepareStatement(
            """INSERT INTO metric_rollup(resource_id, metric_name, labels, resolution_seconds, bucket_at,
                        sample_count, sum_value, min_value, max_value)
               SELECT resource_id, metric_name, labels, 3600,
                      date_bin(INTERVAL '1 hour', bucket_at, TIMESTAMPTZ '1970-01-01 00:00:00+00'),
                      sum(sample_count), sum(sum_value), min(min_value), max(max_value)
               FROM metric_rollup
               WHERE resolution_seconds = 300 AND bucket_at >= ? AND bucket_at < ?
               GROUP BY resource_id, metric_name, labels,
                        date_bin(INTERVAL '1 hour', bucket_at, TIMESTAMPTZ '1970-01-01 00:00:00+00')
               ON CONFLICT (resource_id, metric_name, labels, resolution_seconds, bucket_at) DO UPDATE SET
                   sample_count = metric_rollup.sample_count + EXCLUDED.sample_count,
                   sum_value = metric_rollup.sum_value + EXCLUDED.sum_value,
                   min_value = LEAST(metric_rollup.min_value, EXCLUDED.min_value),
                   max_value = GREATEST(metric_rollup.max_value, EXCLUDED.max_value)""",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(start))
            statement.setTimestamp(2, Timestamp.from(end))
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "DELETE FROM metric_rollup WHERE resolution_seconds = 300 AND bucket_at >= ? AND bucket_at < ?",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(start))
            statement.setTimestamp(2, Timestamp.from(end))
            statement.executeUpdate()
        }
    }

    private fun deleteExpiredRollups(connection: Connection, now: Instant) {
        connection.prepareStatement(
            """WITH expired AS (
                   SELECT id, bucket_at FROM metric_rollup
                   WHERE resolution_seconds = 3600 AND bucket_at < ?
                   ORDER BY bucket_at LIMIT ?
               ) DELETE FROM metric_rollup rollup USING expired
                 WHERE rollup.id = expired.id AND rollup.bucket_at = expired.bucket_at""",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now.minusSeconds(HOURLY_ROLLUP_RETENTION_SECONDS)))
            statement.setInt(2, DELETE_BATCH_SIZE)
            statement.executeUpdate()
        }
    }

    private fun deleteExpiredCheckExecutions(connection: Connection, now: Instant) {
        connection.prepareStatement(
            """WITH expired AS (
                   SELECT id, scheduled_at FROM check_execution WHERE scheduled_at < ?
                   ORDER BY scheduled_at LIMIT ?
               ) DELETE FROM check_execution execution USING expired
                 WHERE execution.id = expired.id AND execution.scheduled_at = expired.scheduled_at""",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now.minusSeconds(CHECK_EXECUTION_RETENTION_SECONDS)))
            statement.setInt(2, DELETE_BATCH_SIZE)
            statement.executeUpdate()
        }
    }

    private fun deleteExpiredNotificationOutbox(connection: Connection, now: Instant) {
        connection.prepareStatement(
            """WITH expired AS (
                   SELECT id FROM notification_outbox
                   WHERE created_at < ? AND status IN ('DELIVERED', 'FAILED', 'CANCELLED')
                   ORDER BY created_at LIMIT ?
               ) DELETE FROM notification_outbox outbox USING expired WHERE outbox.id = expired.id""",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now.minusSeconds(EVENT_RETENTION_SECONDS)))
            statement.setInt(2, DELETE_BATCH_SIZE)
            statement.executeUpdate()
        }
    }

    private fun deleteExpiredResolvedIncidents(connection: Connection, now: Instant) {
        connection.prepareStatement(
            """WITH expired AS (
                   SELECT id FROM incident
                   WHERE status = 'RESOLVED' AND resolved_at < ?
                   ORDER BY resolved_at LIMIT ?
               ) DELETE FROM incident incident USING expired WHERE incident.id = expired.id""",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now.minusSeconds(EVENT_RETENTION_SECONDS)))
            statement.setInt(2, DELETE_BATCH_SIZE)
            statement.executeUpdate()
        }
    }

    private fun deleteExpiredAuditEntries(connection: Connection, now: Instant) {
        connection.prepareStatement(
            """WITH expired AS (
                   SELECT id FROM audit_log
                   WHERE created_at < ?
                   ORDER BY created_at LIMIT ?
               ) DELETE FROM audit_log audit USING expired WHERE audit.id = expired.id""",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now.minusSeconds(EVENT_RETENTION_SECONDS)))
            statement.setInt(2, DELETE_BATCH_SIZE)
            statement.executeUpdate()
        }
    }

    private fun rollupAvailability(connection: Connection, now: Instant) {
        val lastBucket = connection.prepareStatement(
            "SELECT last_bucket_at FROM availability_rollup_state WHERE id = 1 FOR UPDATE",
        ).use { statement ->
            statement.executeQuery().use { rows -> rows.next(); rows.getTimestamp(1).toInstant() }
        }
        val readyThrough = floor(now.minusSeconds(AVAILABILITY_FINALIZATION_DELAY_SECONDS), HOUR_SECONDS)
            .minusSeconds(HOUR_SECONDS)
        var bucket = lastBucket.plusSeconds(HOUR_SECONDS)
        var processed = 0
        while (!bucket.isAfter(readyThrough) && processed < AVAILABILITY_BUCKET_BATCH_SIZE) {
            rollupAvailabilityBucket(connection, bucket)
            connection.prepareStatement("UPDATE availability_rollup_state SET last_bucket_at = ? WHERE id = 1").use { statement ->
                statement.setTimestamp(1, Timestamp.from(bucket))
                statement.executeUpdate()
            }
            bucket = bucket.plusSeconds(HOUR_SECONDS)
            processed += 1
        }
    }

    private fun rollupAvailabilityBucket(connection: Connection, bucket: Instant) {
        val bucketEnd = bucket.plusSeconds(HOUR_SECONDS)
        val monitors = mutableListOf<AvailabilityMonitor>()
        connection.prepareStatement(
            """SELECT m.id, m.resource_id, r.project_id, m.created_at AS monitor_created_at, m.deleted_at AS monitor_deleted_at,
                      r.created_at AS resource_created_at, r.deleted_at AS resource_deleted_at
               FROM monitor m JOIN monitored_resource r ON r.id = m.resource_id
               WHERE m.type <> 'AGENT_OFFLINE' AND m.created_at < ?
                 AND COALESCE(m.deleted_at, 'infinity'::timestamptz) > ?
                 AND r.created_at < ? AND COALESCE(r.deleted_at, 'infinity'::timestamptz) > ?""",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(bucketEnd))
            statement.setTimestamp(2, Timestamp.from(bucket))
            statement.setTimestamp(3, Timestamp.from(bucketEnd))
            statement.setTimestamp(4, Timestamp.from(bucket))
            statement.executeQuery().use { rows ->
                while (rows.next()) monitors += rows.toAvailabilityMonitor()
            }
        }
        if (monitors.isEmpty()) return

        val monitorIds = monitors.associateBy(AvailabilityMonitor::id)
        val activePeriods = mutableMapOf<UUID, MutableList<TimeSpan>>()
        connection.prepareStatement(
            """SELECT monitor_id, starts_at, ends_at FROM monitor_active_period
               WHERE starts_at < ? AND COALESCE(ends_at, 'infinity'::timestamptz) > ?""",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(bucketEnd))
            statement.setTimestamp(2, Timestamp.from(bucket.minusSeconds(MAX_MONITOR_INTERVAL_SECONDS)))
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val id = rows.getObject("monitor_id", UUID::class.java)
                    if (id !in monitorIds) continue
                    activePeriods.getOrPut(id) { mutableListOf() } += TimeSpan(
                        rows.getTimestamp("starts_at").toInstant(),
                        rows.getTimestamp("ends_at")?.toInstant() ?: bucketEnd,
                    )
                }
            }
        }

        val executions = mutableMapOf<UUID, MutableList<AvailabilityExecution>>()
        connection.prepareStatement(
            """SELECT execution.monitor_id, execution.scheduled_at, execution.interval_seconds, execution.result_status
               FROM (
                   SELECT prior_execution.monitor_id, prior_execution.scheduled_at, prior_execution.interval_seconds, prior_execution.result_status
                   FROM monitor m
                   CROSS JOIN LATERAL (
                       SELECT e.monitor_id, e.scheduled_at, e.interval_seconds, e.result_status
                       FROM check_execution e
                       WHERE e.monitor_id = m.id AND e.scheduled_at < ?
                       ORDER BY e.scheduled_at DESC, e.id DESC LIMIT 1
                   ) prior_execution
                   WHERE m.type <> 'AGENT_OFFLINE'
                   UNION ALL
                   SELECT current_execution.monitor_id, current_execution.scheduled_at, current_execution.interval_seconds, current_execution.result_status
                   FROM check_execution current_execution
                   WHERE current_execution.scheduled_at >= ? AND current_execution.scheduled_at < ?
               ) execution
               JOIN monitor m ON m.id = execution.monitor_id
               WHERE m.type <> 'AGENT_OFFLINE'
               ORDER BY execution.monitor_id, execution.scheduled_at""",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(bucket))
            statement.setTimestamp(2, Timestamp.from(bucket))
            statement.setTimestamp(3, Timestamp.from(bucketEnd))
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val id = rows.getObject("monitor_id", UUID::class.java)
                    if (id in monitorIds) executions.getOrPut(id) { mutableListOf() } += rows.toAvailabilityExecution()
                }
            }
        }

        val exclusions = mutableMapOf<UUID, MutableList<TimeSpan>>()
        connection.prepareStatement(
            """SELECT project_id, resource_id, starts_at, LEAST(ends_at, COALESCE(canceled_at, ends_at)) AS effective_end
               FROM maintenance_window
               WHERE starts_at < ? AND LEAST(ends_at, COALESCE(canceled_at, ends_at)) > ?""",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(bucketEnd))
            statement.setTimestamp(2, Timestamp.from(bucket))
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val projectId = rows.getObject("project_id", UUID::class.java)
                    val resourceId = rows.getObject("resource_id", UUID::class.java)
                    val span = TimeSpan(rows.getTimestamp("starts_at").toInstant(), rows.getTimestamp("effective_end").toInstant())
                    monitors.filter { it.projectId == projectId && (resourceId == null || it.resourceId == resourceId) }.forEach { monitor ->
                        clip(span, monitor, bucket, bucketEnd)?.let { exclusions.getOrPut(monitor.id) { mutableListOf() } += it }
                    }
                }
            }
        }
        val pausePeriods = mutableMapOf<UUID, MutableList<TimeSpan>>()
        connection.prepareStatement(
            """SELECT resource_id, starts_at, ends_at FROM resource_pause_period
               WHERE starts_at < ? AND COALESCE(ends_at, 'infinity'::timestamptz) > ?""",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(bucketEnd))
            statement.setTimestamp(2, Timestamp.from(bucket.minusSeconds(MAX_MONITOR_INTERVAL_SECONDS)))
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val resourceId = rows.getObject("resource_id", UUID::class.java)
                    val span = TimeSpan(rows.getTimestamp("starts_at").toInstant(), rows.getTimestamp("ends_at")?.toInstant() ?: bucketEnd)
                    monitors.filter { it.resourceId == resourceId }.forEach { monitor ->
                        clip(span, monitor, bucket, bucketEnd)?.let { exclusions.getOrPut(monitor.id) { mutableListOf() } += it }
                        pausePeriods.getOrPut(monitor.id) { mutableListOf() } += span
                    }
                }
            }
        }

        monitors.forEach { monitor ->
            val monitoringWindows = activePeriods[monitor.id].orEmpty().filter { span ->
                span.start.isBefore(monitor.monitorDeletedAt ?: bucketEnd) &&
                    span.start.isBefore(monitor.resourceDeletedAt ?: bucketEnd)
            }
            val monitored = mergeAvailabilitySpans(monitoringWindows.mapNotNull { clip(it, monitor, bucket, bucketEnd) })
            if (monitored.isEmpty()) return@forEach
            val excluded = intersectAvailabilitySpans(mergeAvailabilitySpans(exclusions[monitor.id].orEmpty()), monitored)
            val pauses = mergeAvailabilitySpans(pausePeriods[monitor.id].orEmpty())
            val runs = executions[monitor.id].orEmpty().sortedBy(AvailabilityExecution::scheduledAt)
            val totals = calculateAvailabilityBucketTotals(
                activePeriods = monitoringWindows,
                monitored = monitored,
                excluded = excluded,
                pauses = pauses,
                executions = runs,
            )

            connection.prepareStatement(
                """INSERT INTO availability_rollup(monitor_id, bucket_at, scheduled_seconds, up_seconds, degraded_seconds,
                            down_seconds, unknown_seconds, excluded_seconds)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT (monitor_id, bucket_at) DO UPDATE SET
                       scheduled_seconds = EXCLUDED.scheduled_seconds,
                       up_seconds = EXCLUDED.up_seconds,
                       degraded_seconds = EXCLUDED.degraded_seconds,
                       down_seconds = EXCLUDED.down_seconds,
                       unknown_seconds = EXCLUDED.unknown_seconds,
                       excluded_seconds = EXCLUDED.excluded_seconds""",
            ).use { statement ->
                statement.setObject(1, monitor.id)
                statement.setTimestamp(2, Timestamp.from(bucket))
                statement.setInt(3, totals.scheduledSeconds)
                statement.setInt(4, totals.upSeconds)
                statement.setInt(5, totals.degradedSeconds)
                statement.setInt(6, totals.downSeconds)
                statement.setInt(7, totals.unknownSeconds)
                statement.setInt(8, totals.excludedSeconds)
                statement.executeUpdate()
            }
        }
    }

    private fun clip(span: TimeSpan, monitor: AvailabilityMonitor, bucket: Instant, bucketEnd: Instant): TimeSpan? {
        val lifeStart = maxOf(monitor.monitorCreatedAt, monitor.resourceCreatedAt, bucket)
        val lifeEnd = minOf(monitor.monitorDeletedAt ?: bucketEnd, monitor.resourceDeletedAt ?: bucketEnd, bucketEnd)
        return TimeSpan(maxOf(span.start, lifeStart), minOf(span.end, lifeEnd)).takeIf { it.end.isAfter(it.start) }
    }

    private fun clip(span: TimeSpan, bucket: Instant, bucketEnd: Instant): TimeSpan? =
        TimeSpan(maxOf(span.start, bucket), minOf(span.end, bucketEnd)).takeIf { it.end.isAfter(it.start) }

    private fun ResultSet.toAvailabilityMonitor() = AvailabilityMonitor(
        id = getObject("id", UUID::class.java),
        resourceId = getObject("resource_id", UUID::class.java),
        projectId = getObject("project_id", UUID::class.java),
        monitorCreatedAt = getTimestamp("monitor_created_at").toInstant(),
        monitorDeletedAt = getTimestamp("monitor_deleted_at")?.toInstant(),
        resourceCreatedAt = getTimestamp("resource_created_at").toInstant(),
        resourceDeletedAt = getTimestamp("resource_deleted_at")?.toInstant(),
    )

    private fun ResultSet.toAvailabilityExecution() = AvailabilityExecution(
        scheduledAt = getTimestamp("scheduled_at").toInstant(),
        intervalSeconds = getInt("interval_seconds"),
        status = getString("result_status"),
    )

    private data class AvailabilityMonitor(
        val id: UUID,
        val resourceId: UUID,
        val projectId: UUID,
        val monitorCreatedAt: Instant,
        val monitorDeletedAt: Instant?,
        val resourceCreatedAt: Instant,
        val resourceDeletedAt: Instant?,
    )

    private fun deleteExpiredAvailabilityRollups(connection: Connection, now: Instant) {
        connection.prepareStatement(
            "DELETE FROM availability_rollup WHERE bucket_at < ?",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(floor(now, HOUR_SECONDS).minusSeconds(AVAILABILITY_RETENTION_SECONDS)))
            statement.executeUpdate()
        }
    }

    private fun deleteExpiredAgentBatches(connection: Connection, now: Instant) {
        connection.prepareStatement(
            """WITH expired AS (
                   SELECT batch.id FROM agent_batch batch
                   WHERE batch.sampled_at < ?
                     AND NOT EXISTS (SELECT 1 FROM metric_sample sample WHERE sample.batch_id = batch.id)
                   ORDER BY batch.sampled_at LIMIT ?
               ) DELETE FROM agent_batch batch USING expired WHERE batch.id = expired.id""",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now.minusSeconds(AGENT_BATCH_RETENTION_SECONDS)))
            statement.setInt(2, DELETE_BATCH_SIZE)
            statement.executeUpdate()
        }
    }

    private fun floor(value: Instant, intervalSeconds: Long): Instant =
        Instant.ofEpochSecond(Math.floorDiv(value.epochSecond, intervalSeconds) * intervalSeconds)

    companion object {
        private const val FIVE_MINUTE_SECONDS = 300L
        private const val HOUR_SECONDS = 3600L
        private const val RAW_BATCH_SECONDS = 1800L
        private const val ROLLUP_BATCH_SECONDS = 43200L
        private const val RAW_METRIC_RETENTION_SECONDS = RetentionPolicyCatalog.RAW_METRIC_DAYS * RetentionPolicyCatalog.DAY_SECONDS
        private const val FIVE_MINUTE_ROLLUP_RETENTION_SECONDS = RetentionPolicyCatalog.FIVE_MINUTE_ROLLUP_DAYS * RetentionPolicyCatalog.DAY_SECONDS
        private const val HOURLY_ROLLUP_RETENTION_SECONDS = RetentionPolicyCatalog.HOURLY_ROLLUP_DAYS * RetentionPolicyCatalog.DAY_SECONDS
        private const val CHECK_EXECUTION_RETENTION_SECONDS = RetentionPolicyCatalog.CHECK_EXECUTION_DAYS * RetentionPolicyCatalog.DAY_SECONDS
        private const val AGENT_BATCH_RETENTION_SECONDS = RetentionPolicyCatalog.AGENT_BATCH_DAYS * RetentionPolicyCatalog.DAY_SECONDS
        private const val EVENT_RETENTION_SECONDS = RetentionPolicyCatalog.EVENT_DAYS * RetentionPolicyCatalog.DAY_SECONDS
        private const val DELETE_BATCH_SIZE = 10_000
        private const val AVAILABILITY_FINALIZATION_DELAY_SECONDS = 300L
        private const val AVAILABILITY_BUCKET_BATCH_SIZE = 24
        private const val AVAILABILITY_RETENTION_SECONDS = RetentionPolicyCatalog.AVAILABILITY_DAYS * RetentionPolicyCatalog.DAY_SECONDS
        private const val MAX_MONITOR_INTERVAL_SECONDS = 24 * 60 * 60L
    }
}
