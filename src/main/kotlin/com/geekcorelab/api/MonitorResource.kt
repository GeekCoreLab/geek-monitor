package com.geekcorelab.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekcorelab.monitor.ProtocolCheckService
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
                """SELECT m.id, m.resource_id, r.name AS resource_name, m.name, m.type, m.config::text AS config,
                    m.interval_seconds, m.timeout_seconds, m.failure_threshold, m.recovery_threshold,
                    m.critical, m.enabled, m.current_state, m.version, m.last_result_at, m.next_run_at
                    FROM monitor m JOIN monitored_resource r ON r.id = m.resource_id
                    JOIN project_membership pm ON pm.project_id = r.project_id
                    WHERE pm.user_id = ? AND r.deleted_at IS NULL AND m.deleted_at IS NULL
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
        val definition = validate(request) ?: return error(Response.Status.BAD_REQUEST, "INVALID_MONITOR", "请检查监控名称、URL、请求方法、间隔和阈值")
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO monitor(id, resource_id, name, type, config, interval_seconds, timeout_seconds,
                    failure_threshold, recovery_threshold, critical, enabled, next_run_at)
                    VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, now())""",
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
                statement.executeUpdate()
            }
        }
        val created = findMonitor(id, user.id) ?: return error(Response.Status.INTERNAL_SERVER_ERROR, "MONITOR_LOAD_FAILED", "监控项已创建，但无法读取")
        return Response.status(Response.Status.CREATED).entity(created).build()
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
        val requestedResource = request.resourceId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: UUID.fromString(current.resourceId)
        if (!authenticator.canAccessResource(user.id, requestedResource)) return error(Response.Status.FORBIDDEN, "RESOURCE_FORBIDDEN", "你没有该资源的访问权限")
        val definition = validate(request) ?: return error(Response.Status.BAD_REQUEST, "INVALID_MONITOR", "请检查监控名称、URL、请求方法、间隔和阈值")
        val updated = dataSource.connection.use { connection ->
            connection.prepareStatement(
                """UPDATE monitor SET resource_id = ?, name = ?, type = ?, config = CAST(? AS jsonb), interval_seconds = ?,
                    timeout_seconds = ?, failure_threshold = ?, recovery_threshold = ?, critical = ?, enabled = ?,
                    version = version + 1, next_run_at = now(), updated_at = now()
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
                statement.setObject(11, id)
                statement.setInt(12, request.version)
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

    private fun validate(request: MonitorRequest): ValidatedDefinition? {
        val name = request.name?.trim().orEmpty()
        val type = request.type?.trim()?.uppercase() ?: "HTTP"
        val targetUrl = request.targetUrl?.trim().orEmpty()
        val host = request.targetHost?.trim().orEmpty()
        val method = request.method?.trim()?.uppercase() ?: "GET"
        val interval = request.intervalSeconds ?: if (type == "TLS") 21600 else 60
        val timeout = request.timeoutSeconds ?: if (type == "DNS") 5 else 10
        val failures = request.failureThreshold ?: 3
        val recoveries = request.recoveryThreshold ?: 2
        val expectedCodes = (request.expectedStatusCodes ?: (200..299).toList()).distinct().sorted()
        val contains = request.textContains?.takeIf(String::isNotBlank)
        if (name.isBlank() || name.length > 160 || type !in setOf("HTTP", "TCP", "DNS", "TLS") ||
            interval !in 10..86400 || timeout !in 1..30 || interval <= timeout || failures !in 1..20 || recoveries !in 1..20) {
            return null
        }
        val config: Map<String, Any?> = when (type) {
            "HTTP" -> {
                val uri = runCatching { URI(targetUrl) }.getOrNull()
                if (uri == null || uri.scheme.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() ||
                    uri.userInfo != null || uri.fragment != null || (uri.port != -1 && uri.port !in 1..65535) ||
                    method !in setOf("GET", "HEAD") || expectedCodes.isEmpty() || expectedCodes.size > 100 ||
                    expectedCodes.any { it !in 100..599 } || (contains?.length ?: 0) > 500) return null
                mapOf(
                    "url" to uri.normalize().toASCIIString(),
                    "method" to method,
                    "expectedStatusCodes" to expectedCodes,
                    "textContains" to contains,
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
            else -> return null
        }
        return ValidatedDefinition(type, name, config, interval, timeout, failures, recoveries, request.critical != false, request.enabled != false)
    }

    private fun findMonitor(id: UUID, userId: UUID): MonitorResponse? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT m.id, m.resource_id, r.name AS resource_name, m.name, m.type, m.config::text AS config,
                m.interval_seconds, m.timeout_seconds, m.failure_threshold, m.recovery_threshold,
                m.critical, m.enabled, m.current_state, m.version, m.last_result_at, m.next_run_at
                FROM monitor m JOIN monitored_resource r ON r.id = m.resource_id
                JOIN project_membership pm ON pm.project_id = r.project_id
                WHERE m.id = ? AND pm.user_id = ? AND r.deleted_at IS NULL AND m.deleted_at IS NULL""",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, userId)
            statement.executeQuery().use { if (it.next()) it.toMonitor() else null }
        }
    }

    private fun runCheck(monitor: MonitorResponse) = protocolCheckService.execute(
        monitor.type,
        objectMapper.valueToTree(monitor.toCheckConfig()),
        monitor.timeoutSeconds,
    )

    private fun MonitorResponse.toCheckConfig(): Map<String, Any?> = when (type) {
        "HTTP" -> mapOf("url" to targetUrl, "method" to method, "expectedStatusCodes" to expectedStatusCodes, "textContains" to textContains)
        "TCP" -> mapOf("host" to targetHost, "port" to port)
        "DNS" -> mapOf("host" to targetHost, "recordType" to recordType, "resolver" to resolver, "expectedValue" to expectedRecordValue)
        "TLS" -> mapOf("host" to targetHost, "port" to port, "minimumValidityDays" to minimumValidityDays)
        else -> emptyMap()
    }

    private fun parseMonitorId(value: String) = runCatching { UUID.fromString(value) }.getOrNull()
    private fun error(status: Response.Status, code: String, message: String) = Response.status(status).entity(ErrorResponse(code, message)).build()

    private fun ResultSet.toMonitor(): MonitorResponse {
        val config = objectMapper.readTree(getString("config"))
        val type = getString("type")
        val targetHost = config.path("host").takeUnless { it.isMissingNode || it.isNull }?.asText()
        return MonitorResponse(
            id = getObject("id").toString(),
            resourceId = getObject("resource_id").toString(),
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
            intervalSeconds = getInt("interval_seconds"),
            timeoutSeconds = getInt("timeout_seconds"),
            failureThreshold = getInt("failure_threshold"),
            recoveryThreshold = getInt("recovery_threshold"),
            expectedStatusCodes = config.path("expectedStatusCodes").map { it.asInt() },
            textContains = config.path("textContains").takeUnless { it.isMissingNode || it.isNull }?.asText(),
            critical = getBoolean("critical"),
            enabled = getBoolean("enabled"),
            state = getString("current_state"),
            version = getInt("version"),
            lastResultAt = getTimestamp("last_result_at")?.toInstant()?.toString(),
            nextRunAt = getTimestamp("next_run_at").toInstant().toString(),
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
    )

    companion object {
        private const val TEST_COOLDOWN_NANOS = 5_000_000_000L
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
