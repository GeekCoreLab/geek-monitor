package com.geekcorelab.geekmonitor.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekcorelab.geekmonitor.notification.NotificationOutbox
import com.geekcorelab.geekmonitor.security.SecretEncryptionService
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URI
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

@Path("/api/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class NotificationResource {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var objectMapper: ObjectMapper
    @Inject lateinit var authenticator: SessionAuthenticator
    @Inject lateinit var encryption: SecretEncryptionService
    @Inject lateinit var outbox: NotificationOutbox
    @Inject @field:ConfigProperty(name = "geek-monitor.network.allow-private-targets", defaultValue = "false")
    var allowPrivateTargets: Boolean = false

    @GET
    @Path("/notification-channels")
    fun listChannels(@QueryParam("projectId") projectIdText: String?, @Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val projectId = projectIdText?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (projectIdText != null && projectId == null) return error(Response.Status.BAD_REQUEST, "INVALID_PROJECT", "项目编号无效")
        if (projectId != null && !authenticator.canAccessProject(user.id, projectId)) return error(Response.Status.FORBIDDEN, "PROJECT_FORBIDDEN", "你没有该项目的访问权限")
        if (!encryption.isConfigured()) return encryptionUnavailable()
        val channels = mutableListOf<NotificationChannelResponse>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT c.id, c.project_id, p.name AS project_name, c.name, c.channel_type, c.config_ciphertext,
                    c.enabled, c.version, c.rate_limit_per_minute, c.created_at,
                    (SELECT n.status FROM notification_outbox n WHERE n.channel_id = c.id ORDER BY n.created_at DESC LIMIT 1) AS last_delivery_status
                    FROM notification_channel c JOIN project p ON p.id = c.project_id
                    JOIN project_membership pm ON pm.project_id = p.id
                    WHERE pm.user_id = ? AND c.deleted_at IS NULL AND (?::uuid IS NULL OR c.project_id = ?::uuid)
                    ORDER BY c.created_at DESC LIMIT 500""",
            ).use { statement ->
                statement.setObject(1, user.id)
                statement.setObject(2, projectId)
                statement.setObject(3, projectId)
                statement.executeQuery().use { rows -> while (rows.next()) channels += rows.toChannel() }
            }
        }
        return Response.ok(channels).build()
    }

    @POST
    @Path("/notification-channels")
    fun createChannel(request: NotificationChannelRequest, @Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        if (user.role == "READ_ONLY") return error(Response.Status.FORBIDDEN, "READ_ONLY", "只读成员不能管理通知渠道")
        if (!encryption.isConfigured()) return encryptionUnavailable()
        val projectId = request.projectId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_PROJECT", "请选择有效项目")
        if (!authenticator.canAccessProject(user.id, projectId)) return error(Response.Status.FORBIDDEN, "PROJECT_FORBIDDEN", "你没有该项目的访问权限")
        val definition = validate(request) ?: return error(Response.Status.BAD_REQUEST, "INVALID_CHANNEL", "请检查渠道名称、接收地址和每分钟 1–600 批的上限")
        val id = UUID.randomUUID()
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO notification_channel(id, project_id, name, channel_type, config_ciphertext, enabled, rate_limit_per_minute) VALUES (?, ?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setObject(2, projectId)
                    statement.setString(3, definition.name)
                    statement.setString(4, definition.type)
                    statement.setString(5, encryption.encrypt(objectMapper.writeValueAsString(definition.config)))
                    statement.setBoolean(6, request.enabled != false)
                    statement.setInt(7, definition.rateLimitPerMinute)
                    statement.executeUpdate()
                }
            }
        } catch (exception: Exception) {
            if (exception.message.orEmpty().contains("duplicate key", true)) return error(Response.Status.CONFLICT, "CHANNEL_NAME_EXISTS", "该项目中已有同名通知渠道")
            throw exception
        }
        audit(user.id, "notification_channel.created", id, mapOf("name" to definition.name, "type" to definition.type, "rateLimitPerMinute" to definition.rateLimitPerMinute))
        return findChannel(id, user.id)?.let { Response.status(Response.Status.CREATED).entity(it).build() }
            ?: error(Response.Status.INTERNAL_SERVER_ERROR, "CHANNEL_LOAD_FAILED", "通知渠道已创建，但无法读取")
    }

    @PATCH
    @Path("/notification-channels/{id}")
    fun updateChannel(@PathParam("id") idText: String, request: NotificationChannelRequest, @Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        if (user.role == "READ_ONLY") return error(Response.Status.FORBIDDEN, "READ_ONLY", "只读成员不能管理通知渠道")
        if (!encryption.isConfigured()) return encryptionUnavailable()
        val id = parseId(idText) ?: return error(Response.Status.BAD_REQUEST, "INVALID_CHANNEL", "通知渠道编号无效")
        val current = findChannel(id, user.id) ?: return error(Response.Status.NOT_FOUND, "CHANNEL_NOT_FOUND", "通知渠道不存在或无权访问")
        if (request.version == null) return error(Response.Status.BAD_REQUEST, "VERSION_REQUIRED", "更新渠道时必须提供版本号")
        if (request.version != current.version) return error(Response.Status.CONFLICT, "VERSION_CONFLICT", "通知渠道已被其他人更新，请刷新后重试")
        val projectId = request.projectId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: UUID.fromString(current.projectId)
        if (!authenticator.canAccessProject(user.id, projectId)) return error(Response.Status.FORBIDDEN, "PROJECT_FORBIDDEN", "你没有该项目的访问权限")
        val definition = validate(request.copy(projectId = projectId.toString(), rateLimitPerMinute = request.rateLimitPerMinute ?: current.rateLimitPerMinute))
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_CHANNEL", "请检查渠道名称、接收地址和每分钟 1–600 批的上限")
        val updated = dataSource.connection.use { connection ->
            connection.prepareStatement(
                """UPDATE notification_channel SET project_id = ?, name = ?, channel_type = ?, config_ciphertext = ?, enabled = ?, rate_limit_per_minute = ?,
                    version = version + 1, updated_at = now() WHERE id = ? AND version = ? AND deleted_at IS NULL""",
            ).use { statement ->
                statement.setObject(1, projectId)
                statement.setString(2, definition.name)
                statement.setString(3, definition.type)
                statement.setString(4, encryption.encrypt(objectMapper.writeValueAsString(definition.config)))
                statement.setBoolean(5, request.enabled != false)
                statement.setInt(6, definition.rateLimitPerMinute)
                statement.setObject(7, id)
                statement.setInt(8, request.version)
                statement.executeUpdate()
            }
        }
        if (updated != 1) return error(Response.Status.CONFLICT, "VERSION_CONFLICT", "通知渠道已被其他人更新，请刷新后重试")
        audit(user.id, "notification_channel.updated", id, mapOf("name" to definition.name, "type" to definition.type, "rateLimitPerMinute" to definition.rateLimitPerMinute))
        return findChannel(id, user.id)?.let { Response.ok(it).build() }
            ?: error(Response.Status.INTERNAL_SERVER_ERROR, "CHANNEL_LOAD_FAILED", "无法读取已更新的通知渠道")
    }

    @DELETE
    @Path("/notification-channels/{id}")
    fun deleteChannel(@PathParam("id") idText: String, @Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        if (user.role == "READ_ONLY") return error(Response.Status.FORBIDDEN, "READ_ONLY", "只读成员不能管理通知渠道")
        val id = parseId(idText) ?: return error(Response.Status.BAD_REQUEST, "INVALID_CHANNEL", "通知渠道编号无效")
        if (!canAccessChannel(id, user.id)) return error(Response.Status.NOT_FOUND, "CHANNEL_NOT_FOUND", "通知渠道不存在或无权访问")
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE notification_channel SET deleted_at = now(), enabled = FALSE, version = version + 1, updated_at = now() WHERE id = ? AND deleted_at IS NULL").use { statement ->
                statement.setObject(1, id)
                statement.executeUpdate()
            }
        }
        audit(user.id, "notification_channel.deleted", id, emptyMap<String, Any?>())
        return Response.noContent().build()
    }

    @POST
    @Path("/notification-channels/{id}/test")
    fun testChannel(@PathParam("id") idText: String, @Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        if (user.role == "READ_ONLY") return error(Response.Status.FORBIDDEN, "READ_ONLY", "只读成员不能发送测试通知")
        if (!encryption.isConfigured()) return encryptionUnavailable()
        val id = parseId(idText) ?: return error(Response.Status.BAD_REQUEST, "INVALID_CHANNEL", "通知渠道编号无效")
        val projectId = projectForChannel(id, user.id) ?: return error(Response.Status.NOT_FOUND, "CHANNEL_NOT_FOUND", "通知渠道不存在或无权访问")
        val enabled = dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT enabled FROM notification_channel WHERE id = ? AND deleted_at IS NULL").use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
            }
        }
        if (!enabled) return error(Response.Status.CONFLICT, "CHANNEL_DISABLED", "请先启用通知渠道")
        val outboxId = dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val outboxId = outbox.enqueueTest(connection, id, user.id, user.email)
                connection.prepareStatement(
                    "INSERT INTO audit_log(id, actor_user_id, action, subject_type, subject_id, summary) VALUES (?, ?, 'notification_channel.test_requested', 'notification_channel', ?, '{}'::jsonb)",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, user.id)
                    statement.setString(3, id.toString())
                    statement.executeUpdate()
                }
                connection.commit()
                outboxId
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
        return Response.status(Response.Status.ACCEPTED).entity(NotificationTestResponse(outboxId.toString())).build()
    }

    @GET
    @Path("/notification-deliveries")
    fun deliveries(
        @QueryParam("channelId") channelIdText: String?,
        @QueryParam("limit") limitText: Int?,
        @Context headers: HttpHeaders,
    ): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val channelId = channelIdText?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (channelIdText != null && channelId == null) return error(Response.Status.BAD_REQUEST, "INVALID_CHANNEL", "通知渠道编号无效")
        if (channelId != null && !canReadChannelHistory(channelId, user.id)) return error(Response.Status.NOT_FOUND, "CHANNEL_NOT_FOUND", "通知渠道不存在或无权访问")
        val limit = (limitText ?: 100).coerceIn(1, 500)
        val results = mutableListOf<NotificationDeliveryResponse>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT d.id, d.outbox_id, n.channel_id, c.name AS channel_name, n.incident_id, n.event_type,
                    d.group_count, d.status, d.attempt_number, d.http_status, d.error_code, d.error_summary,
                    d.started_at AS created_at, d.finished_at
                    FROM notification_delivery d JOIN notification_outbox n ON n.id = d.outbox_id
                    JOIN notification_channel c ON c.id = n.channel_id
                    JOIN project_membership pm ON pm.project_id = c.project_id
                    WHERE pm.user_id = ? AND (?::uuid IS NULL OR c.id = ?::uuid)
                    ORDER BY d.started_at DESC LIMIT ?""",
            ).use { statement ->
                statement.setObject(1, user.id)
                statement.setObject(2, channelId)
                statement.setObject(3, channelId)
                statement.setInt(4, limit)
                statement.executeQuery().use { rows -> while (rows.next()) results += rows.toDelivery() }
            }
        }
        return Response.ok(results).build()
    }

    private fun validate(request: NotificationChannelRequest): ChannelDefinition? {
        val name = request.name?.trim().orEmpty()
        val type = request.type?.trim()?.uppercase().orEmpty()
        val rateLimitPerMinute = request.rateLimitPerMinute ?: DEFAULT_RATE_LIMIT_PER_MINUTE
        if (name.isBlank() || name.length > 120 || rateLimitPerMinute !in 1..600) return null
        return when (type) {
            "EMAIL" -> {
                val recipients = request.emailRecipients.orEmpty().map { it.trim().lowercase() }.filter(String::isNotEmpty).distinct()
                if (recipients.isEmpty() || recipients.size > 20 || recipients.any { !EMAIL.matches(it) }) null
                else ChannelDefinition(name, type, mapOf("recipients" to recipients), rateLimitPerMinute)
            }
            "WEBHOOK" -> {
                val url = request.webhookUrl?.trim().orEmpty()
                val uri = runCatching { URI(url) }.getOrNull()
                val validScheme = uri?.scheme.equals("https", true) || (allowPrivateTargets && uri?.scheme.equals("http", true))
                if (uri == null || !validScheme || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null ||
                    (uri.port != -1 && uri.port !in 1..65535) || url.length > 2048 ||
                    (request.webhookBearerToken?.length ?: 0) > 4096 || request.webhookBearerToken.orEmpty().any { it.code < 32 || it.code == 127 }) null
                else ChannelDefinition(name, type, mapOf("url" to uri.normalize().toASCIIString(), "bearerToken" to request.webhookBearerToken?.takeIf(String::isNotBlank)), rateLimitPerMinute)
            }
            else -> null
        }
    }

    private fun findChannel(id: UUID, userId: UUID): NotificationChannelResponse? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT c.id, c.project_id, p.name AS project_name, c.name, c.channel_type, c.config_ciphertext,
                c.enabled, c.version, c.rate_limit_per_minute, c.created_at,
                (SELECT n.status FROM notification_outbox n WHERE n.channel_id = c.id ORDER BY n.created_at DESC LIMIT 1) AS last_delivery_status
                FROM notification_channel c JOIN project p ON p.id = c.project_id
                JOIN project_membership pm ON pm.project_id = p.id WHERE c.id = ? AND pm.user_id = ? AND c.deleted_at IS NULL""",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, userId)
            statement.executeQuery().use { if (it.next()) it.toChannel() else null }
        }
    }

    private fun ResultSet.toChannel(): NotificationChannelResponse {
        val config = objectMapper.readTree(encryption.decrypt(getString("config_ciphertext")))
        val type = getString("channel_type")
        val summary = if (type == "EMAIL") {
            config.path("recipients").map { it.asText() }.joinToString(", ")
        } else {
            runCatching {
                val uri = URI(config.path("url").asText())
                "${uri.scheme}://${uri.host}${if (uri.port == -1) "" else ":${uri.port}"}/…"
            }.getOrDefault("Webhook 地址已保存")
        }
        return NotificationChannelResponse(
            id = getObject("id").toString(),
            projectId = getObject("project_id").toString(),
            projectName = getString("project_name"),
            name = getString("name"),
            type = type,
            destinationSummary = summary,
            enabled = getBoolean("enabled"),
            version = getInt("version"),
            rateLimitPerMinute = getInt("rate_limit_per_minute"),
            lastDeliveryStatus = getString("last_delivery_status"),
            createdAt = getTimestamp("created_at").toInstant().toString(),
        )
    }

    private fun ResultSet.toDelivery() = NotificationDeliveryResponse(
        id = getObject("id").toString(),
        outboxId = getObject("outbox_id").toString(),
        channelId = getObject("channel_id").toString(),
        channelName = getString("channel_name"),
        incidentId = getObject("incident_id")?.toString(),
        eventType = getString("event_type"),
        status = getString("status"),
        attemptNumber = getInt("attempt_number"),
        httpStatus = getInt("http_status").let { if (wasNull()) null else it },
        errorCode = getString("error_code"),
        errorSummary = getString("error_summary"),
        groupCount = getInt("group_count"),
        createdAt = getTimestamp("created_at").toInstant().toString(),
        finishedAt = getTimestamp("finished_at").toInstant().toString(),
    )

    private fun canAccessChannel(id: UUID, userId: UUID) = projectForChannel(id, userId) != null
    private fun canReadChannelHistory(id: UUID, userId: UUID): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT 1 FROM notification_channel c JOIN project_membership pm ON pm.project_id = c.project_id WHERE c.id = ? AND pm.user_id = ?",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, userId)
            statement.executeQuery().use { it.next() }
        }
    }
    private fun projectForChannel(id: UUID, userId: UUID): UUID? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT c.project_id FROM notification_channel c JOIN project_membership pm ON pm.project_id = c.project_id WHERE c.id = ? AND pm.user_id = ? AND c.deleted_at IS NULL",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, userId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getObject("project_id", UUID::class.java) else null }
        }
    }

    private fun audit(userId: UUID, action: String, subjectId: UUID, summary: Map<String, Any?>) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO audit_log(id, actor_user_id, action, subject_type, subject_id, summary) VALUES (?, ?, ?, 'notification_channel', ?, CAST(? AS jsonb))",
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.setString(3, action)
                statement.setString(4, subjectId.toString())
                statement.setString(5, objectMapper.writeValueAsString(summary))
                statement.executeUpdate()
            }
        }
    }

    private fun parseId(value: String) = runCatching { UUID.fromString(value) }.getOrNull()
    private fun encryptionUnavailable() = error(Response.Status.SERVICE_UNAVAILABLE, "ENCRYPTION_NOT_CONFIGURED", "请在服务端配置 GEEK_MONITOR_ENCRYPTION_KEY 后再管理通知渠道")
    private fun error(status: Response.Status, code: String, message: String) = Response.status(status).entity(ErrorResponse(code, message)).build()

    private data class ChannelDefinition(val name: String, val type: String, val config: Map<String, Any?>, val rateLimitPerMinute: Int)

    companion object {
        private val EMAIL = Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)
        private const val DEFAULT_RATE_LIMIT_PER_MINUTE = 60
    }
}
