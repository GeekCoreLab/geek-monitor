package com.geekcorelab.api

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.qute.Location
import io.quarkus.qute.Template
import io.quarkus.qute.TemplateData
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** Public projection deliberately excludes monitor config, results, targets and credentials. */
@Path("/public")
class PublicStatusResource {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var objectMapper: ObjectMapper
    @Inject @field:Location("public-status.html") lateinit var template: Template

    @GET
    @Path("/{projectId}")
    @Produces(MediaType.TEXT_HTML)
    fun page(@PathParam("projectId") projectIdText: String): Response {
        val projectId = runCatching { UUID.fromString(projectIdText) }.getOrNull()
            ?: return unavailable()
        val resources = linkedMapOf<UUID, PublicResource>()
        val historyStart = LocalDate.now(ZoneOffset.UTC).minusDays((HISTORY_DAYS - 1).toLong())
        val historyStartAt = historyStart.atStartOfDay(ZoneOffset.UTC).toInstant()
        val historyEndAt = historyStartAt.plusSeconds(HISTORY_DAYS * SLOTS_PER_DAY * SLOT_SECONDS)
        val historyTotals = mutableMapOf<UUID, MutableMap<Instant, PublicSlotTotals>>()
        dataSource.connection.use { connection ->
            connection.prepareStatement("""SELECT r.id, r.name, r.tags::text AS tags, r.health_status, r.paused,
                m.type, m.enabled, m.current_state
                FROM monitored_resource r LEFT JOIN monitor m ON m.resource_id = r.id AND m.deleted_at IS NULL
                WHERE r.project_id = ? AND r.public_enabled AND r.deleted_at IS NULL
                ORDER BY r.name, r.id, m.type, m.id""").use { statement ->
                statement.setObject(1, projectId)
                statement.executeQuery().use { result ->
                    while (result.next()) {
                            val resourceId = result.getObject("id", UUID::class.java)
                            val resource = resources.getOrPut(resourceId) {
                            val tags = objectMapper.readTree(result.getString("tags")).map { it.asText() }
                                .filter { it.isNotBlank() }.distinct().sorted()
                            val state = if (result.getBoolean("paused")) "PAUSED" else result.getString("health_status")
                            PublicResource(result.getString("name"), tags, state, label(state))
                        }
                        result.getString("type")?.let { type ->
                            val state = if (resource.state == "PAUSED" || !result.getBoolean("enabled")) "PAUSED"
                                else result.getString("current_state")
                            resource.methods += PublicMethod(methodName(type), state, label(state))
                        }
                    }
                }
            }
            if (resources.isNotEmpty()) {
                connection.prepareStatement(
                    """SELECT m.resource_id,
                              date_bin(INTERVAL '2 hours', a.bucket_at, TIMESTAMPTZ '1970-01-01 00:00:00+00') AS slot_at,
                              SUM(a.up_seconds) AS up_seconds, SUM(a.degraded_seconds) AS degraded_seconds,
                              SUM(a.down_seconds) AS down_seconds
                       FROM availability_rollup a
                       JOIN monitor m ON m.id = a.monitor_id
                       JOIN monitored_resource r ON r.id = m.resource_id
                       WHERE r.project_id = ? AND r.public_enabled AND r.deleted_at IS NULL
                         AND a.bucket_at >= ? AND a.bucket_at < ?
                       GROUP BY m.resource_id, slot_at
                       ORDER BY m.resource_id, slot_at""",
                ).use { statement ->
                    statement.setObject(1, projectId)
                    statement.setTimestamp(2, Timestamp.from(historyStartAt))
                    statement.setTimestamp(3, Timestamp.from(historyEndAt))
                    statement.executeQuery().use { result ->
                        while (result.next()) {
                            val resourceId = result.getObject("resource_id", UUID::class.java)
                            val slot = result.getTimestamp("slot_at").toInstant()
                            val totals = historyTotals.getOrPut(resourceId) { mutableMapOf() }
                            val current = totals.getOrPut(slot) { PublicSlotTotals() }
                            current.upSeconds += result.getLong("up_seconds")
                            current.degradedSeconds += result.getLong("degraded_seconds")
                            current.downSeconds += result.getLong("down_seconds")
                        }
                    }
                }
            }
        }
        if (resources.isEmpty()) return unavailable()
        resources.forEach { (resourceId, resource) ->
            val totals = historyTotals[resourceId].orEmpty()
            resource.history = (0 until HISTORY_SLOTS).map { offset ->
                val slot = historyStartAt.plusSeconds(offset * SLOT_SECONDS)
                val state = historyState(totals[slot])
                val slotEnd = slot.plusSeconds(SLOT_SECONDS)
                PublicHistorySlot("${slot} – ${slotEnd}", state, historyLabel(state))
            }
        }
        val groups = sortedMapOf<String, MutableList<PublicResource>>()
        resources.values.forEach { resource ->
            resource.tags.ifEmpty { listOf("未分组") }.forEach { tag -> groups.getOrPut(tag) { mutableListOf() } += resource }
        }
        val body = template.data("groups", groups.map { PublicGroup(it.key, it.value) })
            .data("updatedAt", Instant.now().toString()).data("count", resources.size).render()
        return Response.ok(body).header("Cache-Control", "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'")
            .header("Referrer-Policy", "no-referrer").header("X-Robots-Tag", "noindex, nofollow").build()
    }

    private fun unavailable() = Response.status(Response.Status.NOT_FOUND).type(MediaType.TEXT_HTML)
        .entity("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>页面未公开</title><h1>页面不存在或尚未公开</h1></html>")
        .header("Cache-Control", "no-store").build()

    private fun methodName(type: String) = when (type) {
        "HTTP" -> "HTTP"; "TCP" -> "TCP"; "DNS" -> "DNS"; "TLS" -> "TLS 证书"
        "POSTGRESQL" -> "PostgreSQL"; "MYSQL" -> "MySQL"; "REDIS" -> "Redis"
        "HEARTBEAT" -> "心跳检测"; "METRIC_THRESHOLD" -> "指标监控"; "AGENT_OFFLINE" -> "Agent 在线检测"
        else -> "其他监控"
    }
    private fun label(state: String) = when (state) {
        "UP" -> "运行正常"; "DOWN" -> "故障"; "DEGRADED" -> "服务降级"
        "PAUSED" -> "已暂停"; "PENDING" -> "等待检测"; else -> "状态未知"
    }

    private fun historyState(totals: PublicSlotTotals?): String = when {
        totals == null || totals.upSeconds + totals.downSeconds == 0L -> "UNKNOWN"
        totals.downSeconds > 0 -> "DOWN"
        totals.degradedSeconds > 0 -> "DEGRADED"
        else -> "UP"
    }

    private fun historyLabel(state: String) = when (state) {
        "UP" -> "运行正常"
        "DEGRADED" -> "高延迟或性能降级"
        "DOWN" -> "服务异常"
        else -> "暂无可用数据"
    }

    private data class PublicSlotTotals(
        var upSeconds: Long = 0,
        var degradedSeconds: Long = 0,
        var downSeconds: Long = 0,
    )

    companion object {
        private const val HISTORY_DAYS = 30
        private const val SLOTS_PER_DAY = 12
        private const val HISTORY_SLOTS = HISTORY_DAYS * SLOTS_PER_DAY
        private const val SLOT_SECONDS = 2L * 60 * 60
    }
}

@TemplateData
data class PublicGroup(val name: String, val resources: List<PublicResource>)
@TemplateData
data class PublicResource(val name: String, val tags: List<String>, val state: String, val status: String,
    val methods: MutableList<PublicMethod> = mutableListOf(), var history: List<PublicHistorySlot> = emptyList())
@TemplateData
data class PublicMethod(val name: String, val state: String, val status: String)
@TemplateData
data class PublicHistorySlot(val period: String, val state: String, val status: String)
