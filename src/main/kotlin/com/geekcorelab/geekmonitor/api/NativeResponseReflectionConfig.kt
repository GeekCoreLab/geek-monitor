package com.geekcorelab.geekmonitor.api

import com.geekcorelab.geekmonitor.monitor.RetentionPolicy
import io.quarkus.runtime.annotations.RegisterForReflection

/**
 * JAX-RS methods returning Response hide their entity type from Quarkus' build-time
 * REST type analysis. Keep those DTOs available to Jackson in native executables.
 */
@RegisterForReflection(
    targets = [
        SessionResponse::class,
        UserResponse::class,
        ErrorResponse::class,
        ProjectResponse::class,
        ProjectMemberResponse::class,
        ResourceResponse::class,
        ProjectCredentialResponse::class,
        MonitorResponse::class,
        CheckResponse::class,
        CheckHistoryResponse::class,
        IncidentResponse::class,
        IncidentEventResponse::class,
        NotificationChannelResponse::class,
        NotificationTestResponse::class,
        NotificationDeliveryResponse::class,
        MaintenanceWindowResponse::class,
        NotificationSilenceResponse::class,
        SystemStatusResponse::class,
        PlatformHealthResponse::class,
        RetentionJobStatusResponse::class,
        BackupStatusResponse::class,
        AuditLogCursor::class,
        AuditLogEntry::class,
        AuditLogPageResponse::class,
        RetentionPolicy::class,
    ],
)
class NativeResponseReflectionConfig
