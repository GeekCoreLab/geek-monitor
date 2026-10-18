package com.geekcorelab.geekmonitor.api

data class InstallStatus(val initialized: Boolean, val productName: String = "Geek Monitor")
data class SetupRequest(val email: String?, val password: String?, val projectName: String? = "默认项目")
data class LoginRequest(val email: String?, val password: String?)
data class SessionResponse(val expiresAt: String, val user: UserResponse)
data class UserResponse(val id: String, val email: String, val role: String)
data class ProjectRequest(val name: String?, val description: String? = "")
data class ResourceUpdateRequest(val name: String?, val environment: String?, val tags: List<String>? = emptyList(), val ownerEmail: String? = null)
data class ProjectResponse(
    val id: String,
    val name: String,
    val description: String,
    val createdAt: String,
    val membershipRole: String = "ADMIN",
)
data class ProjectMemberRequest(val email: String?, val password: String? = null, val role: String? = null)
data class ProjectMemberRoleRequest(val role: String?)
data class ProjectMemberResponse(val id: String, val email: String, val role: String, val createdAt: String)
data class ResourceRequest(
    val projectId: String?,
    val name: String?,
    val type: String?,
    val environment: String? = "production",
    val tags: List<String>? = emptyList(),
    val ownerEmail: String? = null,
)
data class ResourcePublicRequest(val enabled: Boolean?)
data class ResourcePauseRequest(val paused: Boolean?)
data class ResourceResponse(
    val id: String,
    val projectId: String,
    val projectName: String,
    val name: String,
    val type: String,
    val environment: String,
    val tags: List<String>,
    val ownerEmail: String?,
    val healthStatus: String,
    val paused: Boolean,
    val createdAt: String,
    val publicEnabled: Boolean = false,
)
data class ErrorResponse(val code: String, val message: String)
