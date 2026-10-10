package com.geekcorelab.monitor

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

@ApplicationScoped
class MetricRetentionScheduler {
    @Inject lateinit var dataSource: DataSource

    @Scheduled(every = "\${geek-monitor.retention.interval:1m}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun retain() {
        val now = Instant.now()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                lockJob(connection)
                compactRawMetrics(connection, now)
                compactFiveMinuteRollups(connection, now)
                deleteExpiredRollups(connection, now)
                deleteExpiredCheckExecutions(connection, now)
                deleteExpiredAgentBatches(connection, now)
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
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
                   SELECT id FROM metric_rollup
                   WHERE resolution_seconds = 3600 AND bucket_at < ?
                   ORDER BY bucket_at LIMIT ?
               ) DELETE FROM metric_rollup rollup USING expired WHERE rollup.id = expired.id""",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now.minusSeconds(HOURLY_ROLLUP_RETENTION_SECONDS)))
            statement.setInt(2, DELETE_BATCH_SIZE)
            statement.executeUpdate()
        }
    }

    private fun deleteExpiredCheckExecutions(connection: Connection, now: Instant) {
        connection.prepareStatement(
            """WITH expired AS (
                   SELECT id FROM check_execution WHERE scheduled_at < ?
                   ORDER BY scheduled_at LIMIT ?
               ) DELETE FROM check_execution execution USING expired WHERE execution.id = expired.id""",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now.minusSeconds(CHECK_EXECUTION_RETENTION_SECONDS)))
            statement.setInt(2, DELETE_BATCH_SIZE)
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
        private const val RAW_METRIC_RETENTION_SECONDS = 3 * 24 * 60 * 60L
        private const val FIVE_MINUTE_ROLLUP_RETENTION_SECONDS = 30 * 24 * 60 * 60L
        private const val HOURLY_ROLLUP_RETENTION_SECONDS = 180 * 24 * 60 * 60L
        private const val CHECK_EXECUTION_RETENTION_SECONDS = 7 * 24 * 60 * 60L
        private const val AGENT_BATCH_RETENTION_SECONDS = 30 * 24 * 60 * 60L
        private const val DELETE_BATCH_SIZE = 10_000
    }
}
