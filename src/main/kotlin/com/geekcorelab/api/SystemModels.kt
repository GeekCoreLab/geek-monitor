package com.geekcorelab.api

import com.fasterxml.jackson.databind.JsonNode
import com.geekcorelab.monitor.RetentionPolicy

data class SystemStatusResponse(
    val generatedAt: String,
    val schemaVersion: String?,
    val databaseSizeBytes: Long,
    val retentionPolicies: List<RetentionPolicy>,
    val backup: BackupStatusResponse,
)

data class BackupStatusResponse(
    val status: String,
    val filename: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val completedAt: String? = null,
)

data class AuditLogCursor(val createdAt: String, val id: String)

data class AuditLogEntry(
    val id: String,
    val createdAt: String,
    val actorEmail: String?,
    val action: String,
    val subjectType: String,
    val subjectId: String?,
    val summary: JsonNode,
)

data class AuditLogPageResponse(val items: List<AuditLogEntry>, val next: AuditLogCursor?)
