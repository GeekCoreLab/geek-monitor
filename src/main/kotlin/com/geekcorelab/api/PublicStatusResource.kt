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
import java.time.Instant
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
        val resources = linkedMapOf<String, PublicResource>()
        dataSource.connection.use { connection ->
            connection.prepareStatement("""SELECT r.id, r.name, r.tags::text AS tags, r.health_status, r.paused,
                m.type, m.enabled, m.current_state
                FROM monitored_resource r LEFT JOIN monitor m ON m.resource_id = r.id AND m.deleted_at IS NULL
                WHERE r.project_id = ? AND r.public_enabled AND r.deleted_at IS NULL
                ORDER BY r.name, r.id, m.type, m.id""").use { statement ->
                statement.setObject(1, projectId)
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val resource = resources.getOrPut(result.getString("id")) {
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
        }
        if (resources.isEmpty()) return unavailable()
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
}

@TemplateData
data class PublicGroup(val name: String, val resources: List<PublicResource>)
@TemplateData
data class PublicResource(val name: String, val tags: List<String>, val state: String, val status: String,
    val methods: MutableList<PublicMethod> = mutableListOf())
@TemplateData
data class PublicMethod(val name: String, val state: String, val status: String)
