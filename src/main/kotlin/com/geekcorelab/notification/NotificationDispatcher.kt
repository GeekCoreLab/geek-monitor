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
                releaseClaim(task.id)
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
            val ids = connection.prepareStatement(
                "SELECT id FROM notification_outbox WHERE status = 'PENDING' AND next_attempt_at <= now() ORDER BY next_attempt_at, created_at LIMIT ? FOR UPDATE SKIP LOCKED",
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject("id", UUID::class.java)) }
            }
            }
            ids.forEach { id ->
                connection.prepareStatement("UPDATE notification_outbox SET status = 'RUNNING', claimed_at = now() WHERE id = ?").use { statement ->
                    statement.setObject(1, id)
                    statement.executeUpdate()
                }
            }
            connection.commit()
            ids.map(::NotificationTask)
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        }
    }

    private fun deliver(task: NotificationTask) {
        val attemptStarted = Instant.now()
        val envelope = loadEnvelope(task.id)
        if (envelope == null) return
        val attemptNumber = envelope.attemptCount + 1
        val result = when {
            !envelope.channelEnabled || envelope.channelDeleted -> DeliveryResult(false, null, "CHANNEL_DISABLED", "通知渠道已停用", cancelled = true)
            envelope.maintenanceActive && envelope.eventType != "TEST" -> DeliveryResult(false, null, "MAINTENANCE_ACTIVE", "维护窗口期间已抑制通知", cancelled = true)
            envelope.eventType == "OPENED" && envelope.incidentStatus == "RESOLVED" -> DeliveryResult(false, null, "EVENT_SUPERSEDED", "故障已恢复，已取消过期故障通知", cancelled = true)
            else -> runCatching { send(envelope) }.getOrElse { DeliveryResult(false, null, "DELIVERY_ERROR", "通知发送失败") }
        }
        recordAttempt(task.id, envelope, attemptNumber, attemptStarted, result)
    }

    private fun send(envelope: NotificationEnvelope): DeliveryResult {
        val configuration = objectMapper.readTree(encryption.decrypt(envelope.configCiphertext))
        val payload = objectMapper.readTree(envelope.payload)
        val title = payload.path("title").asText("Geek Monitor 通知").take(180)
        val message = payload.path("message").asText("Geek Monitor 检查状态发生变化。")
        return when (envelope.channelType) {
            "EMAIL" -> {
                val recipients = configuration.path("recipients").map { it.asText() }.distinct()
                if (recipients.isEmpty()) return DeliveryResult(false, null, "CHANNEL_INVALID", "邮件收件人配置为空")
                val subject = if (envelope.eventType == "TEST") "【测试通知】$title" else "【Geek Monitor】$title"
                val body = buildString {
                    if (envelope.eventType == "TEST") appendLine("这是一次测试通知。")
                    appendLine(message)
                    appendLine()
                    appendLine("事件类型：${payload.path("eventType").asText(envelope.eventType)}")
                    payload.path("incidentId").takeIf { !it.isMissingNode && !it.isNull }?.let { appendLine("事件编号：${it.asText()}") }
                    payload.path("severity").takeIf { !it.isMissingNode && !it.isNull }?.let { appendLine("级别：${it.asText()}") }
                    payload.path("occurredAt").takeIf { !it.isMissingNode && !it.isNull }?.let { appendLine("时间：${it.asText()}") }
                    appendLine("通知编号：${envelope.idempotencyKey}")
                }
                val mails = recipients.map { Mail.withText(it, subject, body) }.toTypedArray()
                mailer.send(*mails)
                DeliveryResult(true, null, null, null)
            }
            "WEBHOOK" -> {
                val url = configuration.path("url").asText("")
                val token = configuration.path("bearerToken").takeUnless { it.isMissingNode || it.isNull }?.asText()
                val webhookPayload = objectMapper.createObjectNode().apply {
                    set<com.fasterxml.jackson.databind.JsonNode>("notification", payload)
                    put("idempotencyKey", envelope.idempotencyKey)
                    put("deliveryId", envelope.id.toString())
                }
                val delivery = httpChecks.sendWebhook(url, token, objectMapper.writeValueAsString(webhookPayload), envelope.idempotencyKey)
                DeliveryResult(delivery.delivered, delivery.statusCode, delivery.errorCode, delivery.summary)
            }
            else -> DeliveryResult(false, null, "CHANNEL_INVALID", "通知渠道类型无效")
        }
    }

    private fun loadEnvelope(id: UUID): NotificationEnvelope? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT n.id, n.channel_id, n.event_type, n.idempotency_key, n.payload::text AS payload, n.attempt_count,
                c.channel_type, c.config_ciphertext, c.enabled AS channel_enabled, c.deleted_at AS channel_deleted,
                i.status AS incident_status,
                COALESCE((SELECT EXISTS (
                    SELECT 1 FROM monitored_resource r JOIN maintenance_window w ON w.project_id = r.project_id
                    WHERE r.id = i.resource_id AND w.canceled_at IS NULL AND w.starts_at <= now() AND w.ends_at > now()
                      AND (w.resource_id IS NULL OR w.resource_id = r.id)
                )), FALSE) AS maintenance_active
                FROM notification_outbox n JOIN notification_channel c ON c.id = n.channel_id
                LEFT JOIN incident i ON i.id = n.incident_id WHERE n.id = ? AND n.status = 'RUNNING'""",
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toEnvelope() else null }
        }
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
        idempotencyKey = getString("idempotency_key"),
        payload = getString("payload"),
        attemptCount = getInt("attempt_count"),
    )

    private fun recordAttempt(
        id: UUID,
        envelope: NotificationEnvelope,
        attemptNumber: Int,
        startedAt: Instant,
        result: DeliveryResult,
    ) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val outboxStatus = when {
                    result.cancelled -> "CANCELLED"
                    result.delivered -> "DELIVERED"
                    attemptNumber >= MAX_ATTEMPTS -> "FAILED"
                    else -> "PENDING"
                }
                val deliveryStatus = when (outboxStatus) {
                    "DELIVERED" -> "DELIVERED"
                    "CANCELLED" -> "CANCELLED"
                    "FAILED" -> "FAILED"
                    else -> "RETRYING"
                }
                connection.prepareStatement(
                    """INSERT INTO notification_delivery(id, outbox_id, attempt_number, status, http_status, error_code, error_summary, started_at, finished_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())""",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, id)
                    statement.setInt(3, attemptNumber)
                    statement.setString(4, deliveryStatus)
                    if (result.httpStatus == null) statement.setNull(5, java.sql.Types.INTEGER) else statement.setInt(5, result.httpStatus)
                    statement.setString(6, result.errorCode)
                    statement.setString(7, result.summary?.take(500))
                    statement.setObject(8, java.sql.Timestamp.from(startedAt))
                    statement.executeUpdate()
                }
                val retryDelay = RETRY_DELAYS_SECONDS.getOrNull(attemptNumber - 1) ?: RETRY_DELAYS_SECONDS.last()
                connection.prepareStatement(
                    """UPDATE notification_outbox SET status = ?, attempt_count = ?, claimed_at = NULL,
                        delivered_at = CASE WHEN ? = 'DELIVERED' THEN now() ELSE delivered_at END,
                        next_attempt_at = CASE WHEN ? = 'PENDING' THEN now() + (? * INTERVAL '1 second') ELSE next_attempt_at END,
                        last_http_status = ?, last_error_code = ?, last_error_summary = ? WHERE id = ?""",
                ).use { statement ->
                    statement.setString(1, outboxStatus)
                    statement.setInt(2, attemptNumber)
                    statement.setString(3, outboxStatus)
                    statement.setString(4, outboxStatus)
                    statement.setInt(5, retryDelay)
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

    private fun releaseClaim(id: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE notification_outbox SET status = 'PENDING', claimed_at = NULL WHERE id = ? AND status = 'RUNNING'").use { statement ->
                statement.setObject(1, id)
                statement.executeUpdate()
            }
        }
    }

    private data class NotificationTask(val id: UUID)
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
        private const val MAX_ATTEMPTS = 5
        private val RETRY_DELAYS_SECONDS = listOf(15, 60, 300, 1800)
    }
}
