package com.geekcorelab.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekcorelab.monitor.ProtocolCheckService
import com.geekcorelab.monitor.JsonPointerAssertions
import com.geekcorelab.security.CredentialService
import com.geekcorelab.security.SecretEncryptionService
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.net.URI
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

@Path("/api/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class MonitorResource {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var objectMapper: ObjectMapper
    @Inject lateinit var authenticator: SessionAuthenticator
    @Inject lateinit var protocolCheckService: ProtocolCheckService
    @Inject lateinit var credentials: CredentialService
    @Inject lateinit var secretEncryption: SecretEncryptionService

    @GET
    @Path("/monitors")
    fun listMonitors(
        @QueryParam("resourceId") resourceIdText: String?,
        @Context headers: HttpHeaders,
    ): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val resourceId = resourceIdText?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (resourceIdText != null && resourceId == null) return error(Response.Status.BAD_REQUEST, "INVALID_RESOURCE", "资源编号无效")
        if (resourceId != null && !authenticator.canAccessResource(user.id, resourceId)) {
            return error(Response.Status.FORBIDDEN, "RESOURCE_FORBIDDEN", "你没有该资源的访问权限")
        }
        val monitors = mutableListOf<MonitorResponse>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT m.id, m.resource_id, m.credential_id, r.name AS resource_name, m.name, m.type, m.config::text AS config,
                    m.interval_seconds, m.timeout_seconds, m.failure_threshold, m.recovery_threshold,
                    m.critical, m.enabled, m.reminder_interval_minutes, m.current_state, m.version, m.last_result_at, m.next_run_at
                    FROM monitor m JOIN monitored_resource r ON r.id = m.resource_id
                    JOIN project_membership pm ON pm.project_id = r.project_id
                    WHERE pm.user_id = ? AND r.deleted_at IS NULL AND m.deleted_at IS NULL
                      AND m.type <> 'AGENT_OFFLINE'
                      AND (?::uuid IS NULL OR r.id = ?::uuid)
                    ORDER BY m.created_at DESC LIMIT 500""",
            ).use { statement ->
                statement.setObject(1, user.id)
                statement.setObject(2, resourceId)
                statement.setObject(3, resourceId)
                statement.executeQuery().use { result -> while (result.next()) monitors += result.toMonitor() }
            }
        }
        return Response.ok(monitors).build()
    }

    @POST
    @Path("/monitors")
    fun createMonitor(request: MonitorRequest, @Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        if (user.role == "READ_ONLY") return error(Response.Status.FORBIDDEN, "READ_ONLY", "只读成员不能修改监控配置")
        val resourceId = request.resourceId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_RESOURCE", "请选择有效的监控资源")
        if (!authenticator.canAccessResource(user.id, resourceId)) return error(Response.Status.FORBIDDEN, "RESOURCE_FORBIDDEN", "你没有该资源的访问权限")
        val requestedType = request.type?.trim()?.uppercase() ?: "HTTP"
        if (requestedType in DATABASE_TYPES && !secretEncryption.isConfigured()) {
            return error(Response.Status.SERVICE_UNAVAILABLE, "SECRET_ENCRYPTION_UNAVAILABLE", "尚未配置数据库凭据加密密钥")
        }
        if (requestedType == "HTTP" && request.requestHeaders?.values?.any(String::isNotEmpty) == true &&
            !secretEncryption.isConfigured()) {
            return error(Response.Status.SERVICE_UNAVAILABLE, "SECRET_ENCRYPTION_UNAVAILABLE", "尚未配置请求头凭据加密密钥")
        }
        if (request.credentialId?.isNotBlank() == true && !secretEncryption.isConfigured()) {
            return error(Response.Status.SERVICE_UNAVAILABLE, "SECRET_ENCRYPTION_UNAVAILABLE", "尚未配置请求头凭据解密密钥")
        }
        if (!credentialBelongsToResource(request.credentialId, resourceId)) {
            return error(Response.Status.BAD_REQUEST, "CREDENTIAL_PROJECT_MISMATCH", "只能引用同一项目中的有效 HTTP 凭据")
        }
        if (requestedType == "HTTP" && !credentialAllowsTarget(request.credentialId, request.targetUrl)) {
            return error(Response.Status.BAD_REQUEST, "CREDENTIAL_ORIGIN_FORBIDDEN", "请求目标不在共享凭据的授权源站范围内")
        }
        val definition = validate(request) ?: return error(Response.Status.BAD_REQUEST, "INVALID_MONITOR", "请检查监控名称、URL、请求方法、间隔、阈值和重复提醒")
        val resourceType = resourceType(resourceId)
        if ((definition.type == "HEARTBEAT") != (resourceType == "HEARTBEAT")) {
            return error(Response.Status.BAD_REQUEST, "HEARTBEAT_RESOURCE_REQUIRED", "心跳任务必须使用心跳类型资源，其他检查不能绑定到该资源")
        }
        if (definition.type == "METRIC_THRESHOLD" && resourceType != metricResourceType(definition.config["metricName"] as? String)) {
            return error(Response.Status.BAD_REQUEST, "METRIC_RESOURCE_REQUIRED", "指标阈值规则必须绑定与指标类型匹配的 HOST 或 DATABASE 资源")
        }
        if (definition.type in DATABASE_TYPES && resourceType != "DATABASE") {
            return error(Response.Status.BAD_REQUEST, "DATABASE_RESOURCE_REQUIRED", "数据库检查必须绑定到 DATABASE 资源")
        }
        val id = UUID.randomUUID()
        val heartbeatToken = if (definition.type == "HEARTBEAT") credentials.newToken() else null
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """INSERT INTO monitor(id, resource_id, name, type, config, interval_seconds, timeout_seconds,
                        failure_threshold, recovery_threshold, critical, enabled, reminder_interval_minutes, next_run_at, credential_id)
                        VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, now(), ?)""",
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setObject(2, resourceId)
                    statement.setString(3, definition.name)
                    statement.setString(4, definition.type)
                    statement.setString(5, objectMapper.writeValueAsString(definition.config))
                    statement.setInt(6, definition.intervalSeconds)
                    statement.setInt(7, definition.timeoutSeconds)
                    statement.setInt(8, definition.failureThreshold)
                    statement.setInt(9, definition.recoveryThreshold)
                    statement.setBoolean(10, definition.critical)
                    statement.setBoolean(11, definition.enabled)
                    statement.setInt(12, definition.reminderIntervalMinutes)
                    statement.setObject(13, definition.credentialId)
                    statement.executeUpdate()
                }
                if (heartbeatToken != null) {
                    connection.prepareStatement("INSERT INTO heartbeat_task(monitor_id, token_hash) VALUES (?, ?)").use { statement ->
                        statement.setObject(1, id)
                        statement.setString(2, credentials.tokenHash(heartbeatToken))
                        statement.executeUpdate()
                    }
                }
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
        val created = findMonitor(id, user.id) ?: return error(Response.Status.INTERNAL_SERVER_ERROR, "MONITOR_LOAD_FAILED", "监控项已创建，但无法读取")
        return Response.status(Response.Status.CREATED).entity(created.copy(heartbeatToken = heartbeatToken)).build()
    }

    @GET
    @Path("/monitors/{id}")
    fun getMonitor(@PathParam("id") idText: String, @Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val id = parseMonitorId(idText) ?: return error(Response.Status.BAD_REQUEST, "INVALID_MONITOR", "监控项编号无效")
        return findMonitor(id, user.id)?.let { Response.ok(it).build() }
            ?: error(Response.Status.NOT_FOUND, "MONITOR_NOT_FOUND", "监控项不存在或无权访问")
    }

    @PATCH
    @Path("/monitors/{id}")
    fun updateMonitor(
        @PathParam("id") idText: String,
        request: MonitorRequest,
        @Context headers: HttpHeaders,
    ): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        if (user.role == "READ_ONLY") return error(Response.Status.FORBIDDEN, "READ_ONLY", "只读成员不能修改监控配置")
        val id = parseMonitorId(idText) ?: return error(Response.Status.BAD_REQUEST, "INVALID_MONITOR", "监控项编号无效")
        val current = findMonitor(id, user.id) ?: return error(Response.Status.NOT_FOUND, "MONITOR_NOT_FOUND", "监控项不存在或无权访问")
        if (request.version == null) return error(Response.Status.BAD_REQUEST, "VERSION_REQUIRED", "更新配置时必须提供版本号")
        if (request.version != current.version) return error(Response.Status.CONFLICT, "VERSION_CONFLICT", "监控配置已被其他人更新，请刷新后重试")
        val requestedType = request.type?.trim()?.uppercase() ?: current.type
        if ((current.type == "HEARTBEAT" || requestedType == "HEARTBEAT") && requestedType != current.type) {
            return error(Response.Status.CONFLICT, "HEARTBEAT_TYPE_IMMUTABLE", "心跳任务类型不可转换；请新建任务以重新签发凭据")
        }
        if ((current.type in DATABASE_TYPES || requestedType in DATABASE_TYPES) && requestedType != current.type) {
            return error(Response.Status.CONFLICT, "DATABASE_TYPE_IMMUTABLE", "数据库检查类型不可转换；请新建监控项以更换数据库类型")
        }
        val requestedResource = request.resourceId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: UUID.fromString(current.resourceId)
        if (!authenticator.canAccessResource(user.id, requestedResource)) return error(Response.Status.FORBIDDEN, "RESOURCE_FORBIDDEN", "你没有该资源的访问权限")
        val requestedResourceType = resourceType(requestedResource)
        if ((requestedType == "HEARTBEAT") != (requestedResourceType == "HEARTBEAT")) {
            return error(Response.Status.BAD_REQUEST, "HEARTBEAT_RESOURCE_REQUIRED", "心跳任务必须保留在心跳类型资源上")
        }
        val requestedMetricName = request.metricName ?: current.metricName
        if (requestedType == "METRIC_THRESHOLD" && requestedResourceType != metricResourceType(requestedMetricName)) {
            return error(Response.Status.BAD_REQUEST, "METRIC_RESOURCE_REQUIRED", "指标阈值规则必须绑定与指标类型匹配的 HOST 或 DATABASE 资源")
        }
        if (requestedType in DATABASE_TYPES && requestedResourceType != "DATABASE") {
            return error(Response.Status.BAD_REQUEST, "DATABASE_RESOURCE_REQUIRED", "数据库检查必须绑定到 DATABASE 资源")
        }
        val currentConfig = loadMonitorConfig(id)
        if (requestedType in DATABASE_TYPES && !secretEncryption.isConfigured()) {
            return error(Response.Status.SERVICE_UNAVAILABLE, "SECRET_ENCRYPTION_UNAVAILABLE", "尚未配置数据库凭据加密密钥")
        }
        if (requestedType == "HTTP" && request.requestHeaders?.values?.any(String::isNotEmpty) == true &&
            !secretEncryption.isConfigured()) {
            return error(Response.Status.SERVICE_UNAVAILABLE, "SECRET_ENCRYPTION_UNAVAILABLE", "尚未配置请求头凭据加密密钥")
        }
        val requestedCredentialId = if (requestedType == "HTTP") requestedCredentialId(request, currentConfig) else null
        if (requestedCredentialId != null && !secretEncryption.isConfigured()) {
            return error(Response.Status.SERVICE_UNAVAILABLE, "SECRET_ENCRYPTION_UNAVAILABLE", "尚未配置请求头凭据解密密钥")
        }
        if (!credentialBelongsToResource(requestedCredentialId, requestedResource)) {
            return error(Response.Status.BAD_REQUEST, "CREDENTIAL_PROJECT_MISMATCH", "只能引用同一项目中的有效 HTTP 凭据")
        }
        if (requestedType == "HTTP" && !credentialAllowsTarget(requestedCredentialId, request.targetUrl ?: current.targetUrl)) {
            return error(Response.Status.BAD_REQUEST, "CREDENTIAL_ORIGIN_FORBIDDEN", "请求目标不在共享凭据的授权源站范围内")
        }
        val definition = validate(
            request.copy(
                reminderIntervalMinutes = request.reminderIntervalMinutes ?: current.reminderIntervalMinutes,
                heartbeatGraceSeconds = request.heartbeatGraceSeconds ?: current.heartbeatGraceSeconds,
            ),
            currentConfig,
        )
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_MONITOR", "请检查监控名称、URL、请求方法、间隔、阈值和重复提醒")
        val updated = dataSource.connection.use { connection ->
            connection.prepareStatement(
                """UPDATE monitor SET resource_id = ?, name = ?, type = ?, config = CAST(? AS jsonb), interval_seconds = ?,
                    timeout_seconds = ?, failure_threshold = ?, recovery_threshold = ?, critical = ?, enabled = ?,
                    reminder_interval_minutes = ?, credential_id = ?, version = version + 1, next_run_at = now(), updated_at = now()
                    WHERE id = ? AND version = ? AND deleted_at IS NULL""",
            ).use { statement ->
                statement.setObject(1, requestedResource)
                statement.setString(2, definition.name)
                statement.setString(3, definition.type)
                statement.setString(4, objectMapper.writeValueAsString(definition.config))
                statement.setInt(5, definition.intervalSeconds)
                statement.setInt(6, definition.timeoutSeconds)
                statement.setInt(7, definition.failureThreshold)
                statement.setInt(8, definition.recoveryThreshold)
                statement.setBoolean(9, definition.critical)
                statement.setBoolean(10, definition.enabled)
                statement.setInt(11, definition.reminderIntervalMinutes)
                statement.setObject(12, definition.credentialId)
                statement.setObject(13, id)
                statement.setInt(14, request.version)
                statement.executeUpdate()
            }
        }
        if (updated != 1) return error(Response.Status.CONFLICT, "VERSION_CONFLICT", "监控配置已被其他人更新，请刷新后重试")
        return findMonitor(id, user.id)?.let { Response.ok(it).build() }
            ?: error(Response.Status.INTERNAL_SERVER_ERROR, "MONITOR_LOAD_FAILED", "无法读取已更新的监控项")
    }

    @DELETE
    @Path("/monitors/{id}")
    fun deleteMonitor(@PathParam("id") idText: String, @Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        if (user.role == "READ_ONLY") return error(Response.Status.FORBIDDEN, "READ_ONLY", "只读成员不能删除监控项")
        val id = parseMonitorId(idText) ?: return error(Response.Status.BAD_REQUEST, "INVALID_MONITOR", "监控项编号无效")
        if (!authenticator.canAccessMonitor(user.id, id)) return error(Response.Status.NOT_FOUND, "MONITOR_NOT_FOUND", "监控项不存在或无权访问")
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE monitor SET deleted_at = now(), enabled = FALSE, version = version + 1, updated_at = now() WHERE id = ? AND deleted_at IS NULL").use { statement ->
                statement.setObject(1, id)
                statement.executeUpdate()
            }
        }
        return Response.noContent().build()
    }

    @POST
    @Path("/monitors/{id}/test")
    fun testMonitor(@PathParam("id") idText: String, @Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val id = parseMonitorId(idText) ?: return error(Response.Status.BAD_REQUEST, "INVALID_MONITOR", "监控项编号无效")
        val monitor = findMonitor(id, user.id) ?: return error(Response.Status.NOT_FOUND, "MONITOR_NOT_FOUND", "监控项不存在或无权访问")
        val now = System.nanoTime()
        val lastRun = lastManualTestNanos.computeIfAbsent(id) { java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE) }
        while (true) {
            val previous = lastRun.get()
            if (previous != Long.MIN_VALUE && now - previous < TEST_COOLDOWN_NANOS) {
                return error(Response.Status.TOO_MANY_REQUESTS, "TEST_RATE_LIMITED", "请稍后再试运行")
            }
            if (lastRun.compareAndSet(previous, now)) break
        }
        return Response.ok(runCheck(monitor)).build()
    }

    @GET
    @Path("/monitors/{id}/results")
    fun results(
        @PathParam("id") idText: String,
        @QueryParam("limit") requestedLimit: Int?,
        @Context headers: HttpHeaders,
    ): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val id = parseMonitorId(idText) ?: return error(Response.Status.BAD_REQUEST, "INVALID_MONITOR", "监控项编号无效")
        if (!authenticator.canAccessMonitor(user.id, id)) return error(Response.Status.NOT_FOUND, "MONITOR_NOT_FOUND", "监控项不存在或无权访问")
        val limit = (requestedLimit ?: 100).coerceIn(1, 500)
        val results = mutableListOf<CheckHistoryResponse>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT id, scheduled_at, started_at, finished_at, result_status, http_status, error_code, error_summary, duration_ms FROM check_execution WHERE monitor_id = ? ORDER BY scheduled_at DESC LIMIT ?",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setInt(2, limit)
                statement.executeQuery().use { rows -> while (rows.next()) results += rows.toHistory() }
            }
        }
        return Response.ok(results).build()
    }

    private fun validate(request: MonitorRequest, existingConfig: com.fasterxml.jackson.databind.JsonNode? = null): ValidatedDefinition? {
        val name = request.name?.trim().orEmpty()
        val type = request.type?.trim()?.uppercase() ?: "HTTP"
        val targetUrl = request.targetUrl?.trim().orEmpty()
        val host = request.targetHost?.trim().orEmpty()
        val method = request.method?.trim()?.uppercase() ?: "GET"
        val allowPostNoSideEffects = request.allowPostNoSideEffects == true
        val credentialIdText = if (type == "HTTP") {
            if (request.credentialId != null) request.credentialId.trim().takeIf(String::isNotEmpty)
            else existingConfig?.path("credentialId")?.takeUnless { it.isMissingNode || it.isNull }?.asText()?.takeIf(String::isNotEmpty)
        } else request.credentialId?.trim()?.takeIf(String::isNotEmpty)
        val credentialId = credentialIdText?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if ((credentialIdText != null && credentialId == null) || (type != "HTTP" && credentialId != null)) return null
        val interval = request.intervalSeconds ?: if (type == "TLS") 21600 else 60
        val timeout = request.timeoutSeconds ?: when (type) { "DNS" -> 5; "HEARTBEAT", "METRIC_THRESHOLD" -> 1; else -> 10 }
        val heartbeatGraceSeconds = request.heartbeatGraceSeconds ?: DEFAULT_HEARTBEAT_GRACE_SECONDS
        val metricName = request.metricName?.trim().orEmpty()
        val metricOperator = request.metricOperator?.trim()?.uppercase() ?: "GT"
        val metricThreshold = request.metricThreshold
        val metricRecoveryValue = request.metricRecoveryValue
        val metricWindowSeconds = request.metricWindowSeconds ?: DEFAULT_METRIC_WINDOW_SECONDS
        val metricSeverity = request.metricSeverity?.trim()?.uppercase() ?: "WARNING"
        val metricSampleIntervalSeconds = if (metricName in MetricCatalog.databaseMetricNames) {
            DATABASE_METRIC_SAMPLE_INTERVAL_SECONDS
        } else METRIC_SAMPLE_INTERVAL_SECONDS
        val reminderInterval = request.reminderIntervalMinutes ?: 30
        val failures = request.failureThreshold ?: 3
        val recoveries = request.recoveryThreshold ?: 2
        val expectedCodes = (request.expectedStatusCodes ?: (200..299).toList()).distinct().sorted()
        val contains = request.textContains?.takeIf(String::isNotBlank)
        val jsonPath = request.jsonPath?.trim()?.takeIf(String::isNotBlank)
        val jsonExpectedValue = request.jsonExpectedValue?.trim()?.takeIf(String::isNotBlank)
        val jsonAssertionValid = JsonPointerAssertions.isValidConfiguration(jsonPath, jsonExpectedValue)
        if (name.isBlank() || name.length > 160 || type !in setOf("HTTP", "TCP", "DNS", "TLS", "POSTGRESQL", "MYSQL", "REDIS", "HEARTBEAT", "METRIC_THRESHOLD") ||
            interval !in 10..86400 || timeout !in 1..30 || interval <= timeout || failures !in 1..20 || recoveries !in 1..20 ||
            reminderInterval !in 0..1440 || heartbeatGraceSeconds !in 0..86400) {
            return null
        }
        if (type == "METRIC_THRESHOLD") {
            val trigger = metricThreshold ?: return null
            val recovery = metricRecoveryValue ?: return null
            val recoveryDirectionValid = when (metricOperator) {
                "GT", "GTE" -> recovery < trigger
                "LT", "LTE" -> recovery > trigger
                else -> false
            }
            if (metricName !in MetricCatalog.thresholdMetricNames || metricOperator !in METRIC_OPERATORS ||
                !trigger.isFinite() || trigger < 0 || trigger > MAX_METRIC_VALUE ||
                !recovery.isFinite() || recovery < 0 || recovery > MAX_METRIC_VALUE || !recoveryDirectionValid ||
                metricWindowSeconds !in MIN_METRIC_WINDOW_SECONDS..MAX_METRIC_WINDOW_SECONDS ||
                metricWindowSeconds < metricSampleIntervalSeconds * 4 || metricSeverity !in METRIC_SEVERITIES) {
                return null
            }
        }
        val config: Map<String, Any?> = when (type) {
            "HTTP" -> {
                val uri = runCatching { URI(targetUrl) }.getOrNull()
                val encryptedHeaders = HttpRequestHeaderConfig.encrypt(
                    request.requestHeaders,
                    encryptedRequestHeaders(existingConfig),
                    secretEncryption,
                ) ?: return null
                if (uri == null || uri.scheme.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() ||
                    uri.userInfo != null || uri.fragment != null || (uri.port != -1 && uri.port !in 1..65535) ||
                    method !in setOf("GET", "HEAD", "POST") ||
                    (method == "POST") != allowPostNoSideEffects || expectedCodes.isEmpty() || expectedCodes.size > 100 ||
                    expectedCodes.any { it !in 100..599 } || (contains?.length ?: 0) > 500 || !jsonAssertionValid) return null
                mapOf(
                    "url" to uri.normalize().toASCIIString(),
                    "resourceId" to request.resourceId,
                    "method" to method,
                    "allowPostNoSideEffects" to allowPostNoSideEffects,
                    "credentialId" to credentialId?.toString(),
                    "expectedStatusCodes" to expectedCodes,
                    "textContains" to contains,
                    "jsonPath" to jsonPath,
                    "jsonExpectedValue" to jsonExpectedValue,
                    "requestHeadersEncrypted" to encryptedHeaders,
                )
            }
            "TCP" -> {
                val port = request.port ?: 0
                if (!validHost(host) || port !in 1..65535) return null
                mapOf("host" to host, "port" to port)
            }
            "DNS" -> {
                val recordType = request.recordType?.trim()?.uppercase() ?: "A"
                val resolver = request.resolver?.trim()?.takeIf(String::isNotEmpty)
                val expected = request.expectedRecordValue?.trim()?.takeIf(String::isNotEmpty)
                if ((!validDnsName(host) && !(recordType == "PTR" && isIpLiteral(host))) || recordType !in DNS_RECORD_TYPES ||
                    (resolver != null && !isIpLiteral(resolver)) ||
                    (expected?.length ?: 0) > 255) return null
                mapOf("host" to host, "recordType" to recordType, "resolver" to resolver, "expectedValue" to expected)
            }
            "TLS" -> {
                val port = request.port ?: 443
                val minimumValidityDays = request.minimumValidityDays ?: 14
                if (!validHost(host) || port !in 1..65535 || minimumValidityDays !in 0..3650) return null
                mapOf("host" to host, "port" to port, "minimumValidityDays" to minimumValidityDays)
            }
            "POSTGRESQL", "MYSQL", "REDIS" -> {
                val port = request.port ?: when (type) { "POSTGRESQL" -> 5432; "MYSQL" -> 3306; else -> 6379 }
                val databaseName = request.databaseName?.trim().orEmpty()
                val username = request.databaseUsername?.trim().orEmpty()
                val redisDatabase = request.redisDatabase ?: 0
                val suppliedPassword = request.databasePassword?.takeIf { it.isNotEmpty() }
                val previousPassword = existingConfig?.path("passwordEncrypted")
                    ?.takeUnless { it.isMissingNode || it.isNull || it.asText().isBlank() }
                    ?.asText()
                val encryptedPassword = suppliedPassword?.let { secretEncryption.encrypt(it) } ?: previousPassword
                val databaseNameValid = type == "REDIS" ||
                    (databaseName.matches(Regex("[A-Za-z0-9_.-]{1,63}")))
                val usernameValid = type == "REDIS" ||
                    (username.isNotBlank() && username.length <= 128 && !username.any(Char::isISOControl))
                if (!validHost(host) || port !in 1..65535 || !databaseNameValid || !usernameValid ||
                    (type == "REDIS" && (username.length > 128 || username.any(Char::isISOControl) ||
                        (username.isNotBlank() && encryptedPassword == null))) ||
                    (request.databasePassword?.length ?: 0) > 1024 || request.databasePassword?.any(Char::isISOControl) == true ||
                    (type == "REDIS" && redisDatabase !in 0..15)) return null
                if (type != "REDIS" && encryptedPassword == null) return null
                mapOf(
                    "host" to host,
                    "port" to port,
                    "databaseName" to databaseName.takeIf { type != "REDIS" },
                    "username" to username.takeIf { it.isNotBlank() },
                    "passwordEncrypted" to encryptedPassword,
                    "tls" to (request.databaseTls ?: (type == "MYSQL")),
                    "redisDatabase" to if (type == "REDIS") redisDatabase else null,
                )
            }
            "HEARTBEAT" -> mapOf("graceSeconds" to heartbeatGraceSeconds)
        "METRIC_THRESHOLD" -> mapOf(
                "metricName" to metricName,
                "operator" to metricOperator,
                "threshold" to metricThreshold,
                "recoveryValue" to metricRecoveryValue,
                "windowSeconds" to metricWindowSeconds,
                "sampleIntervalSeconds" to metricSampleIntervalSeconds,
                "severity" to metricSeverity,
            )
            else -> return null
        }
        return ValidatedDefinition(
            type, name, config, interval, timeout, failures, recoveries,
            request.critical != false, request.enabled != false, reminderInterval, heartbeatGraceSeconds,
            credentialId,
        )
    }

    private fun findMonitor(id: UUID, userId: UUID): MonitorResponse? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT m.id, m.resource_id, m.credential_id, r.name AS resource_name, m.name, m.type, m.config::text AS config,
                m.interval_seconds, m.timeout_seconds, m.failure_threshold, m.recovery_threshold,
                m.critical, m.enabled, m.reminder_interval_minutes, m.current_state, m.version, m.last_result_at, m.next_run_at
                FROM monitor m JOIN monitored_resource r ON r.id = m.resource_id
                JOIN project_membership pm ON pm.project_id = r.project_id
                WHERE m.id = ? AND pm.user_id = ? AND r.deleted_at IS NULL AND m.deleted_at IS NULL AND m.type <> 'AGENT_OFFLINE'""",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, userId)
            statement.executeQuery().use { if (it.next()) it.toMonitor() else null }
        }
    }

    private fun runCheck(monitor: MonitorResponse): CheckResponse {
        val config = if (monitor.type in DATABASE_TYPES || monitor.type == "HTTP") {
            loadMonitorConfig(UUID.fromString(monitor.id)) ?: objectMapper.createObjectNode()
        } else {
            objectMapper.valueToTree(monitor.toCheckConfig())
        }
        return protocolCheckService.execute(monitor.type, config, monitor.timeoutSeconds)
    }

    private fun loadMonitorConfig(id: UUID): com.fasterxml.jackson.databind.JsonNode? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT config::text FROM monitor WHERE id = ? AND deleted_at IS NULL").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows -> if (rows.next()) objectMapper.readTree(rows.getString(1)) else null }
        }
    }

    private fun MonitorResponse.toCheckConfig(): Map<String, Any?> = when (type) {
        "HTTP" -> mapOf(
            "url" to targetUrl,
            "method" to method,
            "expectedStatusCodes" to expectedStatusCodes,
            "textContains" to textContains,
            "jsonPath" to jsonPath,
            "jsonExpectedValue" to jsonExpectedValue,
        )
        "TCP" -> mapOf("host" to targetHost, "port" to port)
        "DNS" -> mapOf("host" to targetHost, "recordType" to recordType, "resolver" to resolver, "expectedValue" to expectedRecordValue)
        "TLS" -> mapOf("host" to targetHost, "port" to port, "minimumValidityDays" to minimumValidityDays)
        "HEARTBEAT" -> mapOf("monitorId" to id, "expectedIntervalSeconds" to intervalSeconds, "graceSeconds" to heartbeatGraceSeconds)
        "METRIC_THRESHOLD" -> mapOf(
            "monitorId" to id,
            "resourceId" to resourceId,
            "metricName" to metricName,
            "operator" to metricOperator,
            "threshold" to metricThreshold,
            "recoveryValue" to metricRecoveryValue,
            "windowSeconds" to metricWindowSeconds,
            "sampleIntervalSeconds" to metricSampleIntervalSeconds,
            "severity" to metricSeverity,
        )
        else -> emptyMap()
    }

    private fun parseMonitorId(value: String) = runCatching { UUID.fromString(value) }.getOrNull()
    private fun error(status: Response.Status, code: String, message: String) = Response.status(status).entity(ErrorResponse(code, message)).build()

    private fun resourceType(resourceId: UUID): String? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT type FROM monitored_resource WHERE id = ? AND deleted_at IS NULL").use { statement ->
            statement.setObject(1, resourceId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString("type") else null }
        }
    }

    private fun metricResourceType(metricName: String?): String =
        if (metricName in MetricCatalog.databaseMetricNames) "DATABASE" else "HOST"

    private fun requestedCredentialId(
        request: MonitorRequest,
        existingConfig: com.fasterxml.jackson.databind.JsonNode?,
    ): String? = if (request.credentialId != null) {
        request.credentialId.trim().takeIf(String::isNotEmpty)
    } else {
        existingConfig?.path("credentialId")?.takeUnless { it.isMissingNode || it.isNull }?.asText()?.takeIf(String::isNotEmpty)
    }

    private fun credentialBelongsToResource(credentialIdText: String?, resourceId: UUID): Boolean {
        if (credentialIdText.isNullOrBlank()) return true
        val credentialId = runCatching { UUID.fromString(credentialIdText) }.getOrNull() ?: return false
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM project_credential c JOIN monitored_resource r ON r.project_id = c.project_id WHERE c.id = ? AND r.id = ? AND r.deleted_at IS NULL",
            ).use { statement ->
                statement.setObject(1, credentialId)
                statement.setObject(2, resourceId)
                statement.executeQuery().use { it.next() }
            }
        }
    }

    private fun credentialAllowsTarget(credentialIdText: String?, targetUrl: String?): Boolean {
        if (credentialIdText.isNullOrBlank()) return true
        val credentialId = runCatching { UUID.fromString(credentialIdText) }.getOrNull() ?: return false
        val targetOrigin = ProjectCredentialOrigins.fromUrl(targetUrl) ?: return false
        val allowedOriginsJson = dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT allowed_origins::text FROM project_credential WHERE id = ?").use { statement ->
                statement.setObject(1, credentialId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        } ?: return false
        val allowedOrigins = runCatching { objectMapper.readTree(allowedOriginsJson) }.getOrNull()
            ?.takeIf { it.isArray }?.map { it.asText() } ?: return false
        return targetOrigin in allowedOrigins
    }

    private fun encryptedRequestHeaders(config: com.fasterxml.jackson.databind.JsonNode?): Map<String, String> {
        val headers = config?.path("requestHeadersEncrypted") ?: return emptyMap()
        if (!headers.isObject) return emptyMap()
        return headers.fieldNames().asSequence().associateWith { headers.path(it).asText() }
    }

    private fun ResultSet.toMonitor(): MonitorResponse {
        val config = objectMapper.readTree(getString("config"))
        val type = getString("type")
        val targetHost = config.path("host").takeUnless { it.isMissingNode || it.isNull }?.asText()
        return MonitorResponse(
            id = getObject("id").toString(),
            resourceId = getObject("resource_id").toString(),
            credentialId = getObject("credential_id")?.toString(),
            resourceName = getString("resource_name"),
            name = getString("name"),
            type = type,
            targetUrl = config.path("url").asText(targetHost ?: ""),
            targetHost = targetHost,
            port = config.path("port").takeUnless { it.isMissingNode || it.isNull }?.asInt(),
            recordType = config.path("recordType").takeUnless { it.isMissingNode || it.isNull }?.asText(),
            resolver = config.path("resolver").takeUnless { it.isMissingNode || it.isNull }?.asText(),
            expectedRecordValue = config.path("expectedValue").takeUnless { it.isMissingNode || it.isNull }?.asText(),
            minimumValidityDays = config.path("minimumValidityDays").takeUnless { it.isMissingNode || it.isNull }?.asInt(),
            method = config.path("method").asText("GET"),
            allowPostNoSideEffects = config.path("allowPostNoSideEffects").asBoolean(false),
            intervalSeconds = getInt("interval_seconds"),
            timeoutSeconds = getInt("timeout_seconds"),
            failureThreshold = getInt("failure_threshold"),
            recoveryThreshold = getInt("recovery_threshold"),
            expectedStatusCodes = config.path("expectedStatusCodes").map { it.asInt() },
            textContains = config.path("textContains").takeUnless { it.isMissingNode || it.isNull }?.asText(),
            jsonPath = config.path("jsonPath").takeUnless { it.isMissingNode || it.isNull }?.asText(),
            jsonExpectedValue = config.path("jsonExpectedValue").takeUnless { it.isMissingNode || it.isNull }?.asText(),
            requestHeaders = HttpRequestHeaderConfig.visibleNames(encryptedRequestHeaders(config)),
            critical = getBoolean("critical"),
            enabled = getBoolean("enabled"),
            reminderIntervalMinutes = getInt("reminder_interval_minutes"),
            state = getString("current_state"),
            version = getInt("version"),
            lastResultAt = getTimestamp("last_result_at")?.toInstant()?.toString(),
            nextRunAt = getTimestamp("next_run_at").toInstant().toString(),
            heartbeatGraceSeconds = config.path("graceSeconds").takeUnless { it.isMissingNode || it.isNull }?.asInt(),
            metricName = config.path("metricName").takeUnless { it.isMissingNode || it.isNull }?.asText(),
            metricOperator = config.path("operator").takeUnless { it.isMissingNode || it.isNull }?.asText(),
            metricThreshold = config.path("threshold").takeUnless { it.isMissingNode || it.isNull }?.asDouble(),
            metricRecoveryValue = config.path("recoveryValue").takeUnless { it.isMissingNode || it.isNull }?.asDouble(),
            metricWindowSeconds = config.path("windowSeconds").takeUnless { it.isMissingNode || it.isNull }?.asInt(),
            metricSeverity = config.path("severity").takeUnless { it.isMissingNode || it.isNull }?.asText(),
            metricSampleIntervalSeconds = config.path("sampleIntervalSeconds").takeUnless { it.isMissingNode || it.isNull }?.asInt(),
            databaseName = config.path("databaseName").takeUnless { it.isMissingNode || it.isNull }?.asText(),
            databaseUsername = config.path("username").takeUnless { it.isMissingNode || it.isNull }?.asText(),
            databasePasswordConfigured = config.path("passwordEncrypted").takeUnless { it.isMissingNode || it.isNull }?.asText()?.isNotBlank() == true,
            databaseTls = config.path("tls").takeUnless { it.isMissingNode || it.isNull }?.asBoolean(),
            redisDatabase = config.path("redisDatabase").takeUnless { it.isMissingNode || it.isNull }?.asInt(),
        )
    }

    private fun ResultSet.toHistory() = CheckHistoryResponse(
        id = getObject("id").toString(),
        scheduledAt = getTimestamp("scheduled_at").toInstant().toString(),
        startedAt = getTimestamp("started_at")?.toInstant()?.toString(),
        finishedAt = getTimestamp("finished_at")?.toInstant()?.toString(),
        status = getString("result_status"),
        statusCode = getInt("http_status").let { if (wasNull()) null else it },
        errorCode = getString("error_code"),
        summary = getString("error_summary"),
        durationMs = getLong("duration_ms").let { if (wasNull()) null else it },
    )

    private data class ValidatedDefinition(
        val type: String,
        val name: String,
        val config: Map<String, Any?>,
        val intervalSeconds: Int,
        val timeoutSeconds: Int,
        val failureThreshold: Int,
        val recoveryThreshold: Int,
        val critical: Boolean,
        val enabled: Boolean,
        val reminderIntervalMinutes: Int,
        val heartbeatGraceSeconds: Int,
        val credentialId: UUID?,
    )

    companion object {
        private const val TEST_COOLDOWN_NANOS = 5_000_000_000L
        private const val DEFAULT_HEARTBEAT_GRACE_SECONDS = 60
        private const val DEFAULT_METRIC_WINDOW_SECONDS = 300
        private const val MIN_METRIC_WINDOW_SECONDS = 120
        private const val MAX_METRIC_WINDOW_SECONDS = 86400
        private const val METRIC_SAMPLE_INTERVAL_SECONDS = 30
        private const val DATABASE_METRIC_SAMPLE_INTERVAL_SECONDS = 60
        private const val MAX_METRIC_VALUE = 1_000_000_000_000_000.0
        private val METRIC_OPERATORS = setOf("GT", "GTE", "LT", "LTE")
        private val METRIC_SEVERITIES = setOf("WARNING", "HIGH", "CRITICAL")
        private val DATABASE_TYPES = setOf("POSTGRESQL", "MYSQL", "REDIS")
        private val lastManualTestNanos = ConcurrentHashMap<UUID, java.util.concurrent.atomic.AtomicLong>()
        private val DNS_RECORD_TYPES = setOf("A", "AAAA", "CNAME", "MX", "TXT", "NS", "SRV", "PTR")

        private fun validHost(value: String): Boolean = runCatching {
            val raw = value.trim()
            val host = raw.removeSurrounding("[", "]").removeSuffix(".")
            require(host.isNotBlank() && host.length <= 253 && '/' !in host && '@' !in host && !host.any(Char::isWhitespace))
            if (raw.startsWith("[") || raw.endsWith("]")) {
                require(raw.startsWith("[") && raw.endsWith("]") && ':' in host)
            }
            if (':' in host) {
                java.net.InetAddress.getByName(host) is java.net.Inet6Address
            } else {
                val ascii = java.net.IDN.toASCII(host, java.net.IDN.USE_STD3_ASCII_RULES)
                ascii.split('.').all { it.isNotBlank() && it.length <= 63 }
            }
        }.getOrDefault(false)

        private fun isIpLiteral(value: String): Boolean {
            val host = value.trim().removeSurrounding("[", "]")
            return if (':' in host) {
                host.matches(Regex("^[0-9a-fA-F:.]+$")) &&
                    runCatching { java.net.InetAddress.getByName(host) }.isSuccess
            } else {
                val octets = host.split('.')
                octets.size == 4 && octets.all {
                    it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull()?.let { octet -> octet in 0..255 } == true
                }
            }
        }

        private fun validDnsName(value: String): Boolean = runCatching {
            val host = value.trim().removeSuffix(".")
            require(host.isNotBlank() && host.length <= 253 && '/' !in host && '@' !in host && !host.any(Char::isWhitespace))
            val asciiLabels = host.split('.').map { label ->
                val ascii = if (label.startsWith("_")) {
                    require(label.length in 2..63 && label.drop(1).matches(Regex("[A-Za-z0-9_-]+")))
                    label
                } else {
                    java.net.IDN.toASCII(label, java.net.IDN.USE_STD3_ASCII_RULES)
                }
                require(ascii.isNotBlank() && ascii.length <= 63)
                ascii
            }
            asciiLabels.joinToString(".").length <= 253
        }.getOrDefault(false)
    }
}
