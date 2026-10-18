package com.geekcorelab.geekmonitor.api

import com.fasterxml.jackson.databind.ObjectMapper
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
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
class MetricResource {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var objectMapper: ObjectMapper
    @Inject lateinit var authenticator: SessionAuthenticator

    @GET
    @Path("/resources/{id}/metrics")
    fun metrics(
        @PathParam("id") idText: String,
        @QueryParam("from") fromText: String?,
        @QueryParam("to") toText: String?,
        @QueryParam("stepSeconds") stepSeconds: Int?,
        @Context headers: HttpHeaders,
    ): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val resourceId = runCatching { UUID.fromString(idText) }.getOrNull()
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_RESOURCE", "资源编号无效")
        if (!authenticator.canAccessResource(user.id, resourceId)) return error(Response.Status.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在或无权访问")
        val now = Instant.now()
        val to = toText?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: now
        val from = fromText?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: to.minus(Duration.ofHours(24))
        val step = stepSeconds ?: 60
        val window = Duration.between(from, to)
        val effectiveStep = when {
            from.isBefore(now.minus(Duration.ofDays(30))) -> maxOf(step, HOURLY_ROLLUP_SECONDS)
            from.isBefore(now.minus(Duration.ofDays(3))) -> maxOf(step, FIVE_MINUTE_ROLLUP_SECONDS)
            else -> step
        }
        if (fromText != null && runCatching { Instant.parse(fromText) }.isFailure ||
            toText != null && runCatching { Instant.parse(toText) }.isFailure ||
            step !in 30..86400 || window.isNegative || window.isZero || window > Duration.ofDays(180) ||
            window.seconds / effectiveStep > MAX_BUCKETS) {
            return error(Response.Status.BAD_REQUEST, "INVALID_METRIC_RANGE", "查询范围、聚合间隔或时间格式无效")
        }

        val grouped = linkedMapOf<Pair<String, String>, MutableList<Map<String, Any>>>()
        var rowCount = 0
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT metric_name, labels::text AS labels, date_bin(? * INTERVAL '1 second', sampled_at, TIMESTAMPTZ '1970-01-01 00:00:00+00') AS bucket_at,
                          sum(total_value) / sum(sample_count) AS average,
                          min(minimum) AS minimum, max(maximum) AS maximum, sum(sample_count) AS sample_count
                   FROM (
                       SELECT metric_name, labels, sampled_at, value AS total_value, 1::bigint AS sample_count,
                              value AS minimum, value AS maximum
                       FROM metric_sample
                       WHERE resource_id = ? AND trusted AND sampled_at >= ? AND sampled_at < ?
                       UNION ALL
                       SELECT metric_name, labels, sampled_at, value AS total_value, 1::bigint AS sample_count,
                              value AS minimum, value AS maximum
                       FROM database_metric_sample
                       WHERE resource_id = ? AND sampled_at >= ? AND sampled_at < ?
                       UNION ALL
                       SELECT metric_name, labels, bucket_at AS sampled_at, sum_value AS total_value, sample_count,
                              min_value AS minimum, max_value AS maximum
                       FROM metric_rollup
                       WHERE resource_id = ? AND resolution_seconds = 300 AND bucket_at >= ? AND bucket_at < ?
                       UNION ALL
                       SELECT metric_name, labels, bucket_at AS sampled_at, sum_value AS total_value, sample_count,
                              min_value AS minimum, max_value AS maximum
                       FROM metric_rollup
                       WHERE resource_id = ? AND resolution_seconds = 3600 AND bucket_at >= ? AND bucket_at < ?
                   ) samples
                   GROUP BY metric_name, labels, bucket_at
                   ORDER BY metric_name, labels::text, bucket_at
                   LIMIT ?""",
            ).use { statement ->
                statement.setInt(1, effectiveStep)
                statement.setObject(2, resourceId)
                statement.setTimestamp(3, Timestamp.from(from))
                statement.setTimestamp(4, Timestamp.from(to))
                statement.setObject(5, resourceId)
                statement.setTimestamp(6, Timestamp.from(from))
                statement.setTimestamp(7, Timestamp.from(to))
                statement.setObject(8, resourceId)
                statement.setTimestamp(9, Timestamp.from(from))
                statement.setTimestamp(10, Timestamp.from(to))
                statement.setObject(11, resourceId)
                statement.setTimestamp(12, Timestamp.from(from))
                statement.setTimestamp(13, Timestamp.from(to))
                statement.setInt(14, MAX_RESPONSE_BUCKETS)
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val name = rows.getString("metric_name")
                        val labelsText = rows.getString("labels")
                        val labels = objectMapper.readValue(labelsText, objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, String::class.java)) as Map<String, String>
                        val key = name to labelsText
                        val points = grouped.getOrPut(key) { mutableListOf() }
                        points += rows.toPoint()
                        rowCount += 1
                    }
                }
            }
        }
        val series = grouped.map { (key, points) -> mapOf("metricName" to key.first, "labels" to objectMapper.readTree(key.second), "points" to points) }
        return Response.ok(mapOf("from" to from.toString(), "to" to to.toString(), "stepSeconds" to effectiveStep, "series" to series, "pointCount" to rowCount)).build()
    }

    private fun ResultSet.toPoint(): Map<String, Any> = mapOf(
        "bucketAt" to getTimestamp("bucket_at").toInstant().toString(),
        "average" to getDouble("average"),
        "minimum" to getDouble("minimum"),
        "maximum" to getDouble("maximum"),
        "sampleCount" to getLong("sample_count"),
    )

    private fun error(status: Response.Status, code: String, message: String) = Response.status(status).entity(ErrorResponse(code, message)).build()

    companion object {
        private const val FIVE_MINUTE_ROLLUP_SECONDS = 300
        private const val HOURLY_ROLLUP_SECONDS = 3600
        private const val MAX_BUCKETS = 5000
        private const val MAX_RESPONSE_BUCKETS = 250_000
    }
}
