package com.geekcorelab.monitor

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.sql.DataSource
import org.jboss.logging.Logger

@ApplicationScoped
class TimePartitionMaintenance {
    @Inject lateinit var dataSource: DataSource

    fun ensurePartitions() {
        val now = Instant.now()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("SELECT pg_advisory_xact_lock(77324, 27)").use { statement ->
                    statement.executeQuery().use { rows -> rows.next() }
                }
                val currentMonth = YearMonth.from(now.atZone(ZoneOffset.UTC))
                val endMonthExclusive = currentMonth.plusMonths(PARTITION_FUTURE_MONTHS + 1)
                val tablesCreated = ensureMonthlyPartitions(connection, currentMonth, endMonthExclusive)
                val partitionsDropped = dropExpiredEmptyPartitions(connection, now)
                connection.commit()
                if (tablesCreated > 0 || partitionsDropped > 0) {
                    LOG.infof("Time partition maintenance created %d partitions and dropped %d empty expired partitions", tablesCreated, partitionsDropped)
                }
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
    }

    @Scheduled(every = "\${geek-monitor.partition.interval:12h}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun maintain() = ensurePartitions()

    private fun ensureMonthlyPartitions(connection: Connection, firstMonth: YearMonth, endMonthExclusive: YearMonth): Int {
        var created = 0
        PARTITIONED_TABLES.forEach { table ->
            var month = firstMonth
            while (month.isBefore(endMonthExclusive)) {
                val partitionName = "${table.name}_${month.format(MONTH_SUFFIX)}"
                if (!isAttachedPartition(connection, table.name, partitionName)) {
                    val nextMonth = month.plusMonths(1)
                    val lower = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()
                    val upper = nextMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "CREATE TABLE ${identifier(partitionName)} PARTITION OF ${identifier(table.name)} " +
                                "FOR VALUES FROM (TIMESTAMPTZ '${lower}') TO (TIMESTAMPTZ '${upper}')",
                        )
                    }
                    created += 1
                }
                month = month.plusMonths(1)
            }
        }
        return created
    }

    private fun isAttachedPartition(connection: Connection, parent: String, child: String): Boolean =
        connection.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM pg_inherits WHERE inhparent = to_regclass(?) AND inhrelid = to_regclass(?))",
        ).use { statement ->
            statement.setString(1, "public.$parent")
            statement.setString(2, "public.$child")
            statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }

    private fun dropExpiredEmptyPartitions(connection: Connection, now: Instant): Int {
        var dropped = 0
        PARTITIONED_TABLES.forEach { table ->
            val partitionNames = connection.prepareStatement(
                """SELECT child.relname
                   FROM pg_inherits inheritance
                   JOIN pg_class parent ON parent.oid = inheritance.inhparent
                   JOIN pg_namespace parent_schema ON parent_schema.oid = parent.relnamespace
                   JOIN pg_class child ON child.oid = inheritance.inhrelid
                   WHERE parent_schema.nspname = 'public' AND parent.relname = ?
                     AND child.relname ~ '_[0-9]{6}$'
                   ORDER BY child.relname""",
            ).use { statement ->
                statement.setString(1, table.name)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
            partitionNames.forEach partition@{ partitionName ->
                val suffix = partitionName.substringAfterLast('_')
                val month = runCatching { YearMonth.parse(suffix, MONTH_SUFFIX) }.getOrNull() ?: return@partition
                val upperBound = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()
                if (upperBound.isAfter(now.minusSeconds(table.retentionSeconds))) return@partition
                val empty = connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT NOT EXISTS (SELECT 1 FROM public.${identifier(partitionName)} LIMIT 1)")
                        .use { rows -> rows.next() && rows.getBoolean(1) }
                }
                if (empty) {
                    connection.createStatement().use { statement -> statement.execute("DROP TABLE public.${identifier(partitionName)}") }
                    dropped += 1
                }
            }
        }
        return dropped
    }

    private fun identifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private data class PartitionedTable(val name: String, val retentionSeconds: Long)

    companion object {
        private val LOG = Logger.getLogger(TimePartitionMaintenance::class.java)
        private val MONTH_SUFFIX: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMM")
        private const val PARTITION_FUTURE_MONTHS = 3L
        private const val DAY_SECONDS = 86_400L
        private val PARTITIONED_TABLES = listOf(
            PartitionedTable("check_execution", 7 * DAY_SECONDS),
            PartitionedTable("metric_sample", 3 * DAY_SECONDS),
            PartitionedTable("database_metric_sample", 3 * DAY_SECONDS),
            PartitionedTable("metric_rollup", 180 * DAY_SECONDS),
            PartitionedTable("availability_rollup", 180 * DAY_SECONDS),
        )
    }
}
