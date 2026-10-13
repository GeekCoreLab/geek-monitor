package com.geekcorelab.notification

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekcorelab.monitor.HttpCheckService
import com.geekcorelab.security.SecretEncryptionService
import io.quarkus.mailer.Mail
import io.quarkus.mailer.Mailer
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.sql.Timestamp
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@ApplicationScoped
class NotificationDispatcher {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var objectMapper: ObjectMapper
    @Inject lateinit var encryption: SecretEncryptionService
    @Inject lateinit var httpChecks: HttpCheckService
    @Inject lateinit var mailer: Mailer

    private val threadNumber = AtomicInteger()
    private val executor = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(50),
        ThreadFactory { task -> Thread(task, "geek-monitor-notification-${threadNumber.incrementAndGet()}").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    @Scheduled(every = "2s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun dispatchDueNotifications() {
        val capacity = executor.queue.remainingCapacity().coerceAtMost(MAX_CLAIM_PER_TICK)
        if (capacity <= 0) return
        claimDueNotifications(capacity).forEach { task ->
            try {
                executor.execute { deliver(task) }
            } catch (_: RejectedExecutionException) {
                releaseClaim(task.ids)
            }
        }
    }

    @PreDestroy
    fun stop() {
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
    }

    private fun claimDueNotifications(limit: Int): List<NotificationTask> = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                "UPDATE notification_outbox SET status = 'PENDING', claimed_at = NULL WHERE status = 'RUNNING' AND claimed_at < now() - INTERVAL '2 minutes'",
            ).use { it.executeUpdate() }
            val candidates = connection.prepareStatement(
                """SELECT channel_id, event_type FROM notification_outbox WHERE status = 'PENDING' AND next_attempt_at <= now()
                    GROUP BY channel_id, event_type ORDER BY min(next_attempt_at), min(created_at) LIMIT ?""",
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(NotificationGroupKey(rows.getObject("channel_id", UUID::class.java), rows.getString("event_type")))
                } }
            }.sortedWith(compareBy({ it.channelId.toString() }, { it.eventType }))
            val tasks = mutableListOf<NotificationTask>()
            candidates.forEach { candidate ->
                val channel = connection.prepareStatement(
                    "SELECT enabled, deleted_at, rate_limit_per_minute, next_delivery_at, next_delivery_at > now() AS delivery_delayed FROM notification_channel WHERE id = ? FOR UPDATE SKIP LOCKED",
                ).use { statement ->
                    statement.setObject(1, candidate.channelId)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) null else ChannelDispatchState(
                            enabled = rows.getBoolean("enabled"),
                            deleted = rows.getTimestamp("deleted_at") != null,
                            rateLimitPerMinute = rows.getInt("rate_limit_per_minute"),
                            nextDeliveryAt = rows.getTimestamp("next_delivery_at")?.toInstant(),
                            deliveryDelayed = rows.getBoolean("delivery_delayed"),
                        )
                    }
                }
                if (channel == null) return@forEach
                if (channel.enabled && !channel.deleted && channel.deliveryDelayed && channel.nextDeliveryAt != null) {
                    connection.prepareStatement(
                        "UPDATE notification_outbox SET next_attempt_at = GREATEST(next_attempt_at, ?) WHERE channel_id = ? AND event_type = ? AND status = 'PENDING' AND next_attempt_at <= now()",
                    ).use { statement ->
                        statement.setTimestamp(1, Timestamp.from(channel.nextDeliveryAt))
                        statement.setObject(2, candidate.channelId)
                        statement.setString(3, candidate.eventType)
                        statement.executeUpdate()
                    }
                    return@forEach
                }
                val batchLimit = if (candidate.eventType == "TEST") 1 else MAX_GROUP_SIZE
                val ids = connection.prepareStatement(
                    """SELECT id FROM notification_outbox WHERE channel_id = ? AND event_type = ? AND status = 'PENDING'
                        AND next_attempt_at <= now() ORDER BY next_attempt_at, created_at LIMIT ? FOR UPDATE SKIP LOCKED""",
                ).use { statement ->
                    statement.setObject(1, candidate.channelId)
                    statement.setString(2, candidate.eventType)
                    statement.setInt(3, batchLimit)
                    statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject("id", UUID::class.java)) }
                }
                }
                if (ids.isEmpty()) return@forEach
                val placeholders = ids.joinToString(",") { "?" }
                connection.prepareStatement(
                    "UPDATE notification_outbox SET status = 'RUNNING', claimed_at = now(), silence_deferred_until = NULL WHERE id IN ($placeholders)",
                ).use { statement ->
                    ids.forEachIndexed { index, id -> statement.setObject(index + 1, id) }
                    statement.executeUpdate()
                }
                if (channel.enabled && !channel.deleted) {
                    connection.prepareStatement(
                        "UPDATE notification_channel SET next_delivery_at = now() + (60.0 / ?) * INTERVAL '1 second' WHERE id = ?",
                    ).use { statement ->
                        statement.setInt(1, channel.rateLimitPerMinute)
                        statement.setObject(2, candidate.channelId)
                        statement.executeUpdate()
                    }
                }
                tasks += NotificationTask(ids)
            }
            connection.commit()
            tasks
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        }
    }

    private fun deliver(task: NotificationTask) {
        val attemptStarted = Instant.now()
        val envelopes = loadEnvelopes(task.ids)
        if (envelopes.isEmpty()) return
        val sendable = mutableListOf<NotificationEnvelope>()
        envelopes.forEach { envelope ->
            val attemptNumber = envelope.attemptCount + 1
            val cancellation = when {
                !envelope.channelEnabled || envelope.channelDeleted -> DeliveryResult(false, null, "CHANNEL_DISABLED", "通知渠道已停用", cancelled = true)
                envelope.maintenanceActive && envelope.eventType != "TEST" -> DeliveryResult(false, null, "MAINTENANCE_ACTIVE", "维护窗口期间已抑制通知", cancelled = true)
                envelope.eventType in setOf("OPENED", "ESCALATED", "REMINDER") && envelope.incidentStatus == "RESOLVED" -> DeliveryResult(false, null, "EVENT_SUPERSEDED", "事件已恢复，已取消过期通知", cancelled = true)
                else -> null
            }
            if (cancellation != null) {
                recordAttempt(envelope.id, envelope, attemptNumber, attemptStarted, cancellation, 1)
            } else if (envelope.eventType != "TEST" && envelope.silencedUntil != null) {
                deferUntilSilenceEnds(envelope.id, envelope.silencedUntil)
            } else {
                sendable += envelope
            }
        }
        if (sendable.isEmpty()) return
        val result = runCatching { send(sendable) }.getOrElse { DeliveryResult(false, null, "DELIVERY_ERROR", "通知发送失败") }
        sendable.forEach { envelope ->
            recordAttempt(envelope.id, envelope, envelope.attemptCount + 1, attemptStarted, result, sendable.size)
        }
    }

    private fun send(envelopes: List<NotificationEnvelope>): DeliveryResult {
        val envelope = envelopes.first()
        val configuration = objectMapper.readTree(encryption.decrypt(envelope.configCiphertext))
        val payloads = envelopes.map { objectMapper.readTree(it.payload) }
        val grouped = envelopes.size > 1
        val notification = if (!grouped) payloads.first() else objectMapper.createObjectNode().apply {
            put("eventType", "notification.grouped")
            put("groupCount", payloads.size)
            val events = objectMapper.createArrayNode()
            payloads.forEach { events.add(it) }
            set<com.fasterxml.jackson.databind.JsonNode>("events", events)
        }
        val title = if (grouped) "${payloads.size} 条告警通知" else payloads.first().path("title").asText("Geek Monitor 通知").take(180)
        val idempotencyKey = if (grouped) groupedIdempotencyKey(envelopes) else envelope.idempotencyKey
        val message = if (grouped) payloads.mapIndexed { index, payload ->
            "${index + 1}. ${payload.path("title").asText("Geek Monitor 通知")}\n${payload.path("message").asText("Geek Monitor 检查状态发生变化。")}"
        }.joinToString("\n\n") else payloads.first().path("message").asText("Geek Monitor 检查状态发生变化。")
        return when (envelope.channelType) {
            "EMAIL" -> {
                val recipients = configuration.path("recipients").map { it.asText() }.distinct()
                if (recipients.isEmpty()) return DeliveryResult(false, null, "CHANNEL_INVALID", "邮件收件人配置为空")
                val subject = if (envelope.eventType == "TEST") "【测试通知】$title" else "【Geek Monitor】$title"
                val body = buildString {
                    if (envelope.eventType == "TEST") appendLine("这是一次测试通知。")
                    appendLine(message)
                    if (grouped) {
                        appendLine()
                        appendLine("本次合并 ${payloads.size} 条同类事件。")
                    } else {
                        val payload = payloads.first()
                        appendLine()
                        appendLine("事件类型：${payload.path("eventType").asText(envelope.eventType)}")
                        payload.path("incidentId").takeIf { !it.isMissingNode && !it.isNull }?.let { appendLine("事件编号：${it.asText()}") }
                        payload.path("severity").takeIf { !it.isMissingNode && !it.isNull }?.let { appendLine("级别：${it.asText()}") }
                        payload.path("occurredAt").takeIf { !it.isMissingNode && !it.isNull }?.let { appendLine("时间：${it.asText()}") }
                    }
                    appendLine("通知编号：$idempotencyKey")
                }
                val mails = recipients.map { Mail.withText(it, subject, body) }.toTypedArray()
                mailer.send(*mails)
                DeliveryResult(true, null, null, null)
            }
            "WEBHOOK" -> {
                val url = configuration.path("url").asText("")
                val token = configuration.path("bearerToken").takeUnless { it.isMissingNode || it.isNull }?.asText()
                val webhookPayload = objectMapper.createObjectNode().apply {
                    set<com.fasterxml.jackson.databind.JsonNode>("notification", notification)
                    put("idempotencyKey", idempotencyKey)
                    put("deliveryId", if (grouped) idempotencyKey else envelope.id.toString())
                }
                val delivery = httpChecks.sendWebhook(url, token, objectMapper.writeValueAsString(webhookPayload), idempotencyKey)
                DeliveryResult(delivery.delivered, delivery.statusCode, delivery.errorCode, delivery.summary)
            }
            else -> DeliveryResult(false, null, "CHANNEL_INVALID", "通知渠道类型无效")
        }
    }

    private fun loadEnvelopes(ids: List<UUID>): List<NotificationEnvelope> = dataSource.connection.use { connection ->
        if (ids.isEmpty()) return@use emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        connection.prepareStatement(
            """SELECT n.id, n.channel_id, n.event_type, n.idempotency_key, n.payload::text AS payload, n.attempt_count,
                c.channel_type, c.config_ciphertext, c.enabled AS channel_enabled, c.deleted_at AS channel_deleted,
                i.status AS incident_status,
                COALESCE((SELECT EXISTS (
                    SELECT 1 FROM monitored_resource r JOIN maintenance_window w ON w.project_id = r.project_id
                    WHERE r.id = i.resource_id AND w.canceled_at IS NULL AND w.starts_at <= now() AND w.ends_at > now()
                      AND (w.resource_id IS NULL OR w.resource_id = r.id)
                )), FALSE) AS maintenance_active,
                (SELECT max(s.ends_at) FROM monitored_resource r JOIN notification_silence s ON s.project_id = r.project_id
                    WHERE r.id = i.resource_id AND s.canceled_at IS NULL AND s.starts_at <= now() AND s.ends_at > now()
                      AND (s.resource_id IS NULL OR s.resource_id = r.id)) AS silenced_until
                FROM notification_outbox n JOIN notification_channel c ON c.id = n.channel_id
                LEFT JOIN incident i ON i.id = n.incident_id WHERE n.id IN ($placeholders) AND n.status = 'RUNNING'""",
        ).use { statement ->
            ids.forEachIndexed { index, id -> statement.setObject(index + 1, id) }
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toEnvelope()) } }
        }
    }

    private fun groupedIdempotencyKey(envelopes: List<NotificationEnvelope>): String {
        val eventIds = envelopes.map { it.id.toString() }.sorted().joinToString(":")
        val digest = MessageDigest.getInstance("SHA-256").digest(eventIds.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "group:${envelopes.first().channelId}:$digest"
    }

    private fun ResultSet.toEnvelope() = NotificationEnvelope(
        id = getObject("id", UUID::class.java),
        channelId = getObject("channel_id", UUID::class.java),
        channelType = getString("channel_type"),
        configCiphertext = getString("config_ciphertext"),
        channelEnabled = getBoolean("channel_enabled"),
        channelDeleted = getTimestamp("channel_deleted") != null,
        eventType = getString("event_type"),
        incidentStatus = getString("incident_status"),
        maintenanceActive = getBoolean("maintenance_active"),
        silencedUntil = getTimestamp("silenced_until")?.toInstant(),
        idempotencyKey = getString("idempotency_key"),
        payload = getString("payload"),
        attemptCount = getInt("attempt_count"),
    )

    private fun deferUntilSilenceEnds(id: UUID, until: Instant) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE notification_outbox SET status = 'PENDING', claimed_at = NULL, next_attempt_at = GREATEST(now(), ?), silence_deferred_until = ? WHERE id = ? AND status = 'RUNNING'",
            ).use { statement ->
                statement.setTimestamp(1, java.sql.Timestamp.from(until))
                statement.setTimestamp(2, java.sql.Timestamp.from(until))
                statement.setObject(3, id)
                statement.executeUpdate()
            }
        }
    }

    private fun recordAttempt(
        id: UUID,
        envelope: NotificationEnvelope,
        attemptNumber: Int,
        startedAt: Instant,
        result: DeliveryResult,
        groupCount: Int,
    ) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val transition = transitionNotificationAttempt(result.delivered, result.cancelled, attemptNumber)
                connection.prepareStatement(
                    """INSERT INTO notification_delivery(id, outbox_id, attempt_number, status, http_status, error_code, error_summary, group_count, started_at, finished_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())""",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, id)
                    statement.setInt(3, attemptNumber)
                    statement.setString(4, transition.deliveryStatus)
                    if (result.httpStatus == null) statement.setNull(5, java.sql.Types.INTEGER) else statement.setInt(5, result.httpStatus)
                    statement.setString(6, result.errorCode)
                    statement.setString(7, result.summary?.take(500))
                    statement.setInt(8, groupCount)
                    statement.setObject(9, java.sql.Timestamp.from(startedAt))
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """UPDATE notification_outbox SET status = ?, attempt_count = ?, claimed_at = NULL,
                        delivered_at = CASE WHEN ? = 'DELIVERED' THEN now() ELSE delivered_at END,
                        next_attempt_at = CASE WHEN ? = 'PENDING' THEN now() + (? * INTERVAL '1 second') ELSE next_attempt_at END,
                        last_http_status = ?, last_error_code = ?, last_error_summary = ? WHERE id = ?""",
                ).use { statement ->
                    statement.setString(1, transition.outboxStatus)
                    statement.setInt(2, attemptNumber)
                    statement.setString(3, transition.outboxStatus)
                    statement.setString(4, transition.outboxStatus)
                    statement.setInt(5, transition.retryDelaySeconds ?: 0)
                    if (result.httpStatus == null) statement.setNull(6, java.sql.Types.INTEGER) else statement.setInt(6, result.httpStatus)
                    statement.setString(7, result.errorCode)
                    statement.setString(8, result.summary?.take(500))
                    statement.setObject(9, id)
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
    }

    private fun releaseClaim(ids: List<UUID>) {
        if (ids.isEmpty()) return
        val placeholders = ids.joinToString(",") { "?" }
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE notification_outbox SET status = 'PENDING', claimed_at = NULL WHERE id IN ($placeholders) AND status = 'RUNNING'").use { statement ->
                ids.forEachIndexed { index, id -> statement.setObject(index + 1, id) }
                statement.executeUpdate()
            }
        }
    }

    private data class NotificationTask(val ids: List<UUID>)
    private data class NotificationGroupKey(val channelId: UUID, val eventType: String)
    private data class ChannelDispatchState(
        val enabled: Boolean,
        val deleted: Boolean,
        val rateLimitPerMinute: Int,
        val nextDeliveryAt: Instant?,
        val deliveryDelayed: Boolean,
    )
    private data class NotificationEnvelope(
        val id: UUID,
        val channelId: UUID,
        val channelType: String,
        val configCiphertext: String,
        val channelEnabled: Boolean,
        val channelDeleted: Boolean,
        val eventType: String,
        val incidentStatus: String?,
        val maintenanceActive: Boolean,
        val silencedUntil: Instant?,
        val idempotencyKey: String,
        val payload: String,
        val attemptCount: Int,
    )
    private data class DeliveryResult(
        val delivered: Boolean,
        val httpStatus: Int?,
        val errorCode: String?,
        val summary: String?,
        val cancelled: Boolean = false,
    )

    companion object {
        private const val MAX_CLAIM_PER_TICK = 20
        private const val MAX_GROUP_SIZE = 50
    }
}
