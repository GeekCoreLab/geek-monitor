package com.geekcorelab.api

data class MonitorRequest(
    val resourceId: String?,
    val name: String?,
    val type: String? = "HTTP",
    val targetUrl: String? = null,
    val method: String? = "GET",
    val intervalSeconds: Int? = 60,
    val timeoutSeconds: Int? = 10,
    val failureThreshold: Int? = 3,
    val recoveryThreshold: Int? = 2,
    val expectedStatusCodes: List<Int>? = null,
    val textContains: String? = null,
    val critical: Boolean? = true,
    val enabled: Boolean? = true,
    val version: Int? = null,
)

data class MonitorResponse(
    val id: String,
    val resourceId: String,
    val resourceName: String,
    val name: String,
    val type: String,
    val targetUrl: String,
    val method: String,
    val intervalSeconds: Int,
    val timeoutSeconds: Int,
    val failureThreshold: Int,
    val recoveryThreshold: Int,
    val expectedStatusCodes: List<Int>,
    val textContains: String?,
    val critical: Boolean,
    val enabled: Boolean,
    val state: String,
    val version: Int,
    val lastResultAt: String?,
    val nextRunAt: String,
)

data class CheckResponse(
    val status: String,
    val statusCode: Int?,
    val errorCode: String?,
    val summary: String?,
    val durationMs: Long,
    val checkedAt: String,
)

data class CheckHistoryResponse(
    val id: String,
    val scheduledAt: String,
    val startedAt: String?,
    val finishedAt: String?,
    val status: String,
    val statusCode: Int?,
    val errorCode: String?,
    val summary: String?,
    val durationMs: Long?,
)

data class IncidentResponse(
    val id: String,
    val resourceId: String,
    val resourceName: String,
    val monitorId: String,
    val title: String,
    val severity: String,
    val status: String,
    val openedAt: String,
    val acknowledgedAt: String?,
    val acknowledgedBy: String?,
    val acknowledgementNote: String?,
    val resolvedAt: String?,
    val occurrenceCount: Int,
)

data class IncidentAckRequest(val note: String? = null)
data class IncidentEventResponse(
    val id: String,
    val eventType: String,
    val actorEmail: String?,
    val note: String?,
    val details: Map<String, Any?>,
    val createdAt: String,
)

data class NotificationChannelRequest(
    val projectId: String?,
    val name: String?,
    val type: String?,
    val emailRecipients: List<String>? = null,
    val webhookUrl: String? = null,
    val webhookBearerToken: String? = null,
    val enabled: Boolean? = true,
    val version: Int? = null,
)

data class NotificationChannelResponse(
    val id: String,
    val projectId: String,
    val projectName: String,
    val name: String,
    val type: String,
    val destinationSummary: String,
    val enabled: Boolean,
    val version: Int,
    val lastDeliveryStatus: String?,
    val createdAt: String,
)

data class NotificationTestResponse(val outboxId: String, val status: String = "PENDING")

data class NotificationDeliveryResponse(
    val id: String,
    val outboxId: String,
    val channelId: String,
    val channelName: String,
    val incidentId: String?,
    val eventType: String,
    val status: String,
    val attemptNumber: Int,
    val httpStatus: Int?,
    val errorCode: String?,
    val errorSummary: String?,
    val createdAt: String,
    val finishedAt: String,
)
