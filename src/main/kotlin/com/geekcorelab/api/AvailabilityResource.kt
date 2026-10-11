package com.geekcorelab.api

import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.math.roundToInt

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
class AvailabilityResource {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var authenticator: SessionAuthenticator

    @GET
    @Path("/monitors/{id}/availability")
    fun availability(
        @PathParam("id") idText: String,
        @QueryParam("from") fromText: String?,
        @QueryParam("to") toText: String?,
        @Context headers: HttpHeaders,
    ): Response {
        val user = authenticator.currentUser(headers)
            ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val monitorId = runCatching { UUID.fromString(idText) }.getOrNull()
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_MONITOR", "监控项编号无效")
        if (!authenticator.canAccessMonitor(user.id, monitorId)) {
            return error(Response.Status.NOT_FOUND, "MONITOR_NOT_FOUND", "监控项不存在或无权访问")
        }

        val now = Instant.now()
        val to = toText?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: floorHour(now)
        val from = fromText?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: to.minus(Duration.ofHours(24))
        val window = Duration.between(from, to)
        if ((fromText != null && runCatching { Instant.parse(fromText) }.isFailure) ||
            (toText != null && runCatching { Instant.parse(toText) }.isFailure) ||
            !isHourBoundary(from) || !isHourBoundary(to) || from.isBefore(Instant.EPOCH) ||
            window.isNegative || window.isZero || window > Duration.ofDays(MAX_RANGE_DAYS.toLong())) {
            return error(
                Response.Status.BAD_REQUEST,
                "INVALID_AVAILABILITY_RANGE",
                "可用率范围必须使用整点 UTC 时间，且不超过 180 天",
            )
        }

        val storedPoints = mutableMapOf<Instant, AvailabilityPoint>()
        var rollupThrough: Instant? = null
        var scope: AvailabilityScope? = null
        val activePeriods = mutableListOf<TimeSpan>()
        val excludedPeriods = mutableListOf<TimeSpan>()
        dataSource.connection.use { connection ->
            rollupThrough = connection.prepareStatement(
                "SELECT last_bucket_at FROM availability_rollup_state WHERE id = 1",
            ).use { statement -> statement.executeQuery().use { rows -> rows.next(); rows.getTimestamp(1).toInstant() } }

            connection.prepareStatement(
                """SELECT m.resource_id, r.project_id, m.created_at AS monitor_created_at, m.deleted_at AS monitor_deleted_at,
                          r.created_at AS resource_created_at, r.deleted_at AS resource_deleted_at
                   FROM monitor m JOIN monitored_resource r ON r.id = m.resource_id WHERE m.id = ?""",
            ).use { statement ->
                statement.setObject(1, monitorId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) {
                        scope = AvailabilityScope(
                            resourceId = rows.getObject("resource_id", UUID::class.java),
                            projectId = rows.getObject("project_id", UUID::class.java),
                            monitorCreatedAt = rows.getTimestamp("monitor_created_at").toInstant(),
                            monitorDeletedAt = rows.getTimestamp("monitor_deleted_at")?.toInstant(),
                            resourceCreatedAt = rows.getTimestamp("resource_created_at").toInstant(),
                            resourceDeletedAt = rows.getTimestamp("resource_deleted_at")?.toInstant(),
                        )
                    }
                }
            }
            connection.prepareStatement(
                """SELECT starts_at, ends_at FROM monitor_active_period
                   WHERE monitor_id = ? AND starts_at < ? AND COALESCE(ends_at, 'infinity'::timestamptz) > ?""",
            ).use { statement ->
                statement.setObject(1, monitorId)
                statement.setTimestamp(2, Timestamp.from(to))
                statement.setTimestamp(3, Timestamp.from(from))
                statement.executeQuery().use { rows ->
                    while (rows.next()) activePeriods += TimeSpan(
                        rows.getTimestamp("starts_at").toInstant(),
                        rows.getTimestamp("ends_at")?.toInstant() ?: to,
                    )
                }
            }
            scope?.let { resourceScope ->
                connection.prepareStatement(
                    """SELECT starts_at, LEAST(ends_at, COALESCE(canceled_at, ends_at)) AS effective_end
                       FROM maintenance_window
                       WHERE project_id = ? AND (resource_id IS NULL OR resource_id = ?)
                         AND starts_at < ? AND LEAST(ends_at, COALESCE(canceled_at, ends_at)) > ?""",
                ).use { statement ->
                    statement.setObject(1, resourceScope.projectId)
                    statement.setObject(2, resourceScope.resourceId)
                    statement.setTimestamp(3, Timestamp.from(to))
                    statement.setTimestamp(4, Timestamp.from(from))
                    statement.executeQuery().use { rows ->
                        while (rows.next()) excludedPeriods += TimeSpan(
                            rows.getTimestamp("starts_at").toInstant(), rows.getTimestamp("effective_end").toInstant(),
                        )
                    }
                }
                connection.prepareStatement(
                    """SELECT starts_at, ends_at FROM resource_pause_period
                       WHERE resource_id = ? AND starts_at < ? AND COALESCE(ends_at, 'infinity'::timestamptz) > ?""",
                ).use { statement ->
                    statement.setObject(1, resourceScope.resourceId)
                    statement.setTimestamp(2, Timestamp.from(to))
                    statement.setTimestamp(3, Timestamp.from(from))
                    statement.executeQuery().use { rows ->
                        while (rows.next()) excludedPeriods += TimeSpan(
                            rows.getTimestamp("starts_at").toInstant(), rows.getTimestamp("ends_at")?.toInstant() ?: to,
                        )
                    }
                }
            }
            connection.prepareStatement(
                """SELECT bucket_at, scheduled_seconds, up_seconds, degraded_seconds, down_seconds, unknown_seconds, excluded_seconds
                   FROM availability_rollup
                   WHERE monitor_id = ? AND bucket_at >= ? AND bucket_at < ?
                   ORDER BY bucket_at""",
            ).use { statement ->
                statement.setObject(1, monitorId)
                statement.setTimestamp(2, Timestamp.from(from))
                statement.setTimestamp(3, Timestamp.from(to))
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val point = AvailabilityPoint(
                            bucketAt = rows.getTimestamp("bucket_at").toInstant(),
                            scheduledSeconds = rows.getInt("scheduled_seconds"),
                            upSeconds = rows.getInt("up_seconds"),
                            degradedSeconds = rows.getInt("degraded_seconds"),
                            downSeconds = rows.getInt("down_seconds"),
                            unknownSeconds = rows.getInt("unknown_seconds"),
                            excludedSeconds = rows.getInt("excluded_seconds"),
                        )
                        storedPoints[point.bucketAt] = point
                    }
                }
            }
        }

        val ready = rollupThrough?.let { !it.isBefore(to.minusSeconds(HOUR_SECONDS)) } == true
        val points = mutableListOf<AvailabilityPoint>()
        var bucket = from
        while (bucket.isBefore(to)) {
            val stored = storedPoints[bucket]
            if (stored != null) {
                points += stored
            } else {
                val monitorScope = scope
                val lifecycleStart = monitorScope?.let { maxOf(it.monitorCreatedAt, it.resourceCreatedAt) }
                val lifecycleEnd = monitorScope?.let {
                    minOf(it.monitorDeletedAt ?: to, it.resourceDeletedAt ?: to, to)
                }
                val monitored = if (monitorScope == null || lifecycleStart == null || lifecycleEnd == null) {
                    emptyList()
                } else {
                    merge(activePeriods.mapNotNull { period ->
                        intersect(
                            period,
                            TimeSpan(maxOf(bucket, lifecycleStart), minOf(bucket.plusSeconds(HOUR_SECONDS), lifecycleEnd)),
                        )
                    })
                }
                val excluded = intersectAll(merge(excludedPeriods), monitored)
                val scheduledSeconds = durationSeconds(monitored).roundToInt().coerceIn(0, HOUR_SECONDS.toInt())
                val excludedSeconds = durationSeconds(excluded).roundToInt().coerceIn(0, scheduledSeconds)
                points += AvailabilityPoint(
                    bucketAt = bucket,
                    scheduledSeconds = scheduledSeconds,
                    upSeconds = 0,
                    degradedSeconds = 0,
                    downSeconds = 0,
                    unknownSeconds = scheduledSeconds - excludedSeconds,
                    excludedSeconds = excludedSeconds,
                )
            }
            bucket = bucket.plusSeconds(HOUR_SECONDS)
        }

        val scheduled = points.sumOf(AvailabilityPoint::scheduledSeconds)
        val up = points.sumOf(AvailabilityPoint::upSeconds)
        val degraded = points.sumOf(AvailabilityPoint::degradedSeconds)
        val down = points.sumOf(AvailabilityPoint::downSeconds)
        val unknown = points.sumOf(AvailabilityPoint::unknownSeconds)
        val excluded = points.sumOf(AvailabilityPoint::excludedSeconds)
        val eligible = (scheduled - excluded).coerceAtLeast(0)
        val measured = up + down
        val availability = if (!ready || measured == 0) null else (up.toDouble() / measured) * 100.0
        val coverage = if (!ready || eligible == 0) null else (measured.toDouble() / eligible) * 100.0

        return Response.ok(
            mapOf(
                "monitorId" to monitorId.toString(),
                "from" to from.toString(),
                "to" to to.toString(),
                "bucketSeconds" to HOUR_SECONDS,
                "ready" to ready,
                "availabilityPercent" to availability,
                "coveragePercent" to coverage,
                "hasData" to (ready && measured > 0),
                "upSeconds" to up,
                "degradedSeconds" to degraded,
                "downSeconds" to down,
                "unknownSeconds" to unknown,
                "excludedSeconds" to excluded,
                "scheduledSeconds" to scheduled,
                "eligibleSeconds" to eligible,
                "points" to points.map(AvailabilityPoint::asMap),
            ),
        ).build()
    }

    private fun floorHour(value: Instant): Instant = Instant.ofEpochSecond(Math.floorDiv(value.epochSecond, HOUR_SECONDS) * HOUR_SECONDS)
    private fun isHourBoundary(value: Instant): Boolean = value.nano == 0 && Math.floorMod(value.epochSecond, HOUR_SECONDS) == 0L
    private fun error(status: Response.Status, code: String, message: String) = Response.status(status).entity(ErrorResponse(code, message)).build()

    private fun intersect(left: TimeSpan, right: TimeSpan): TimeSpan? {
        val start = maxOf(left.start, right.start)
        val end = minOf(left.end, right.end)
        return TimeSpan(start, end).takeIf { end.isAfter(start) }
    }

    private fun intersectAll(exclusions: List<TimeSpan>, periods: List<TimeSpan>): List<TimeSpan> = buildList {
        exclusions.forEach { exclusion -> periods.forEach { period -> intersect(exclusion, period)?.let(::add) } }
    }.let(::merge)

    private fun merge(spans: List<TimeSpan>): List<TimeSpan> {
        val sorted = spans.filter { it.end.isAfter(it.start) }.sortedBy(TimeSpan::start)
        if (sorted.isEmpty()) return emptyList()
        val merged = mutableListOf(sorted.first())
        sorted.drop(1).forEach { span ->
            val previous = merged.last()
            if (!span.start.isAfter(previous.end)) merged[merged.lastIndex] = TimeSpan(previous.start, maxOf(previous.end, span.end))
            else merged += span
        }
        return merged
    }

    private fun durationSeconds(spans: List<TimeSpan>): Double = spans.sumOf { span ->
        val duration = Duration.between(span.start, span.end)
        duration.seconds.toDouble() + duration.nano / 1_000_000_000.0
    }

    private data class AvailabilityPoint(
        val bucketAt: Instant,
        val scheduledSeconds: Int,
        val upSeconds: Int,
        val degradedSeconds: Int,
        val downSeconds: Int,
        val unknownSeconds: Int,
        val excludedSeconds: Int,
    ) {
        fun asMap() = mapOf(
            "bucketAt" to bucketAt.toString(),
            "scheduledSeconds" to scheduledSeconds,
            "upSeconds" to upSeconds,
            "degradedSeconds" to degradedSeconds,
            "downSeconds" to downSeconds,
            "unknownSeconds" to unknownSeconds,
            "excludedSeconds" to excludedSeconds,
        )
    }

    private data class AvailabilityScope(
        val resourceId: UUID,
        val projectId: UUID,
        val monitorCreatedAt: Instant,
        val monitorDeletedAt: Instant?,
        val resourceCreatedAt: Instant,
        val resourceDeletedAt: Instant?,
    )

    private data class TimeSpan(val start: Instant, val end: Instant)

    companion object {
        private const val HOUR_SECONDS = 3600L
        private const val MAX_RANGE_DAYS = 180
    }
}
