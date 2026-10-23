package com.geekcorelab.geekmonitor.api

import com.fasterxml.jackson.annotation.JsonIgnore
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
        val snapshot = loadStatus(projectId) ?: return unavailable()
        val body = template.data("groups", snapshot.groups)
            .data("projectId", projectId.toString())
            .data("updatedAt", snapshot.updatedAt)
            .data("historyStart", snapshot.historyStart)
            .data("historyEnd", snapshot.historyEnd)
            .data("count", snapshot.count).render()
        return publicResponse(body, MediaType.TEXT_HTML)
    }

    @GET
    @Path("/{projectId}/data")
    @Produces(MediaType.APPLICATION_JSON)
    fun data(@PathParam("projectId") projectIdText: String): Response {
        val projectId = runCatching { UUID.fromString(projectIdText) }.getOrNull()
            ?: return unavailableJson()
        val snapshot = loadStatus(projectId) ?: return unavailableJson()
        return publicResponse(objectMapper.writeValueAsString(snapshot), MediaType.APPLICATION_JSON)
    }

    private fun publicResponse(body: String, mediaType: String): Response = Response.ok(body)
        .type(mediaType)
        .header("Cache-Control", "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'")
        .header("Referrer-Policy", "no-referrer")
        .header("X-Robots-Tag", "noindex, nofollow")
        .build()

    private fun loadStatus(projectId: UUID): PublicStatusData? {
        val resources = linkedMapOf<UUID, PublicResource>()
        val now = Instant.now()
        val currentSlotStart = Instant.ofEpochSecond(now.epochSecond / SLOT_SECONDS * SLOT_SECONDS)
        val historyStartAt = currentSlotStart.minusSeconds((HISTORY_SLOTS - 1L) * SLOT_SECONDS)
        val historyEndAt = currentSlotStart.plusSeconds(SLOT_SECONDS)
        val historyTotals = mutableMapOf<UUID, MutableMap<Instant, PublicSlotTotals>>()
        val maintenanceWindows = mutableListOf<PublicMaintenanceWindow>()
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
                    statement.setTimestamp(3, Timestamp.from(now))
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
                connection.prepareStatement(
                    """SELECT w.resource_id, w.name, w.starts_at, w.ends_at, w.canceled_at,
                              CASE WHEN w.canceled_at IS NOT NULL THEN 'CANCELLED'
                                   WHEN w.starts_at <= now() AND w.ends_at > now() THEN 'ACTIVE'
                                   WHEN w.starts_at > now() THEN 'SCHEDULED'
                                   ELSE 'ENDED' END AS status
                       FROM maintenance_window w
                       WHERE w.project_id = ?
                         AND LEAST(w.ends_at, COALESCE(w.canceled_at, w.ends_at)) > ?
                         AND w.starts_at < ?
                       ORDER BY w.starts_at""",
                ).use { statement ->
                    statement.setObject(1, projectId)
                    statement.setTimestamp(2, Timestamp.from(historyStartAt))
                    statement.setTimestamp(3, Timestamp.from(historyEndAt))
                    statement.executeQuery().use { result ->
                        while (result.next()) {
                            val startsAt = result.getTimestamp("starts_at").toInstant()
                            val endsAt = result.getTimestamp("ends_at").toInstant()
                            val canceledAt = result.getTimestamp("canceled_at")?.toInstant()
                            val effectiveEndsAt = minOf(endsAt, canceledAt ?: endsAt)
                            maintenanceWindows += PublicMaintenanceWindow(
                                resourceId = result.getObject("resource_id", UUID::class.java),
                                name = result.getString("name"),
                                startsAt = startsAt,
                                effectiveEndsAt = effectiveEndsAt,
                                status = result.getString("status"),
                                period = "${DISPLAY_TIME.format(startsAt)} 至 ${DISPLAY_TIME.format(endsAt)}（北京时间）",
                            )
                        }
                    }
                }
            }
        }
        if (resources.isEmpty()) return null
        resources.forEach { (resourceId, resource) ->
            val totals = historyTotals[resourceId].orEmpty()
            resource.maintenanceWindows = maintenanceWindows.filter { window ->
                window.status in PUBLIC_WINDOW_STATUSES &&
                    (window.resourceId == null || window.resourceId == resourceId)
            }
            if (resource.maintenanceWindows.any { it.startsAt <= now && it.effectiveEndsAt > now }) {
                resource.state = "MAINTENANCE"
                resource.status = "维护中"
            }
            resource.history = (0 until HISTORY_SLOTS).map { offset ->
                val slot = historyStartAt.plusSeconds(offset * SLOT_SECONDS)
                val slotEnd = minOf(slot.plusSeconds(SLOT_SECONDS), now)
                val maintenance = maintenanceWindows.any { window ->
                    (window.resourceId == null || window.resourceId == resourceId) &&
                        window.startsAt < slotEnd && window.effectiveEndsAt > slot
                }
                val state = if (maintenance) "MAINTENANCE" else historyState(totals[slot])
                PublicHistorySlot(
                    "${DISPLAY_TIME.format(slot)} 至 ${DISPLAY_TIME.format(slotEnd)}（北京时间）",
                    state,
                    historyLabel(state),
                )
            }
        }
        val groups = sortedMapOf<String, MutableList<PublicResource>>()
        resources.values.forEach { resource ->
            resource.tags.ifEmpty { listOf("未分组") }.forEach { tag -> groups.getOrPut(tag) { mutableListOf() } += resource }
        }
        return PublicStatusData(
            groups = groups.map { PublicGroup(it.key, it.value) },
            updatedAt = DISPLAY_TIME.format(now),
            historyStart = SCALE_TIME.format(historyStartAt),
            historyEnd = SCALE_TIME.format(now),
            count = resources.size,
        )
    }

    private fun unavailable() = Response.status(Response.Status.NOT_FOUND).type(MediaType.TEXT_HTML)
        .entity("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>页面未公开</title><h1>页面不存在或尚未公开</h1></html>")
        .header("Cache-Control", "no-store").build()

    private fun unavailableJson() = Response.status(Response.Status.NOT_FOUND).type(MediaType.APPLICATION_JSON)
        .entity("{\"message\":\"页面不存在或尚未公开\"}")
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
        totals == null || totals.upSeconds + totals.degradedSeconds + totals.downSeconds == 0L -> "UNKNOWN"
        totals.downSeconds > 0 -> "DOWN"
        totals.degradedSeconds > 0 -> "DEGRADED"
        else -> "UP"
    }

    private fun historyLabel(state: String) = when (state) {
        "UP" -> "运行正常"
        "DEGRADED" -> "高延迟或性能降级"
        "DOWN" -> "服务异常"
        "MAINTENANCE" -> "维护中"
        else -> "暂无可用数据"
    }

    private data class PublicSlotTotals(
        var upSeconds: Long = 0,
        var degradedSeconds: Long = 0,
        var downSeconds: Long = 0,
    )

    companion object {
        private const val HISTORY_DAYS = 30
        private const val HISTORY_SLOTS = HISTORY_DAYS * 12
        private const val SLOT_SECONDS = 2L * 60 * 60
        private val PUBLIC_WINDOW_STATUSES = setOf("ACTIVE", "SCHEDULED")
        private val DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA)
            .withZone(ZoneId.of("Asia/Shanghai"))
        private val SCALE_TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
            .withZone(ZoneId.of("Asia/Shanghai"))
    }
}

@TemplateData
data class PublicGroup(val name: String, val resources: List<PublicResource>)
@TemplateData
data class PublicStatusData(
    val groups: List<PublicGroup>,
    val updatedAt: String,
    val historyStart: String,
    val historyEnd: String,
    val count: Int,
)
@TemplateData
data class PublicResource(val name: String, val tags: List<String>, var state: String, var status: String,
    val methods: MutableList<PublicMethod> = mutableListOf(), var history: List<PublicHistorySlot> = emptyList(),
    var maintenanceWindows: List<PublicMaintenanceWindow> = emptyList())
@TemplateData
data class PublicMethod(val name: String, val state: String, val status: String)
@TemplateData
data class PublicHistorySlot(val period: String, val state: String, val status: String)
@TemplateData
data class PublicMaintenanceWindow(
    @get:JsonIgnore val resourceId: UUID?,
    val name: String,
    @get:JsonIgnore val startsAt: Instant,
    @get:JsonIgnore val effectiveEndsAt: Instant,
    val status: String,
    val period: String,
)
