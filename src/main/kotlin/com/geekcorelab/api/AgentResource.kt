package com.geekcorelab.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekcorelab.security.CredentialService
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Path("/api/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class AgentResource {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var authenticator: SessionAuthenticator
    @Inject lateinit var credentials: CredentialService
    @Inject lateinit var objectMapper: ObjectMapper

    @POST
    @Path("/agent-enrollments")
    fun createEnrollment(request: AgentEnrollmentRequest, @Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        if (user.role != "ADMIN") return error(Response.Status.FORBIDDEN, "ADMIN_REQUIRED", "只有管理员可以签发 Agent 注册令牌")
        val resourceId = request.resourceId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_RESOURCE", "请选择有效的主机资源")
        if (!authenticator.canAccessResource(user.id, resourceId)) return error(Response.Status.NOT_FOUND, "RESOURCE_NOT_FOUND", "主机资源不存在或无权访问")

        val projectId = dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT project_id FROM monitored_resource WHERE id = ? AND type = 'HOST' AND deleted_at IS NULL",
            ).use { statement ->
                statement.setObject(1, resourceId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getObject("project_id", UUID::class.java) else null }
            }
        } ?: return error(Response.Status.BAD_REQUEST, "HOST_RESOURCE_REQUIRED", "Agent 只能绑定到主机资源")

        val enrollmentId = UUID.randomUUID()
        val token = credentials.newToken()
        val expiresAt = Instant.now().plus(ENROLLMENT_TTL)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "INSERT INTO agent_enrollment(id, resource_id, project_id, token_hash, issued_by, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setObject(1, enrollmentId)
                    statement.setObject(2, resourceId)
                    statement.setObject(3, projectId)
                    statement.setString(4, credentials.tokenHash(token))
                    statement.setObject(5, user.id)
                    statement.setTimestamp(6, Timestamp.from(expiresAt))
                    statement.executeUpdate()
                }
                writeAudit(connection, user.id, "agent.enrollment.created", "agent_enrollment", enrollmentId, mapOf("resourceId" to resourceId.toString(), "expiresAt" to expiresAt.toString()))
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
        return Response.status(Response.Status.CREATED).entity(
            mapOf("id" to enrollmentId.toString(), "resourceId" to resourceId.toString(), "token" to token, "expiresAt" to expiresAt.toString()),
        ).build()
    }

    @POST
    @Path("/agents/register")
    fun register(request: AgentRegisterRequest): Response {
        val enrollmentToken = request.enrollmentToken?.trim().orEmpty()
        val hostname = request.hostname?.trim().orEmpty()
        val operatingSystem = request.operatingSystem?.trim().orEmpty()
        val agentVersion = request.agentVersion?.trim().orEmpty()
        if (enrollmentToken.length !in 32..128 || hostname.isBlank() || hostname.length > 255 ||
            operatingSystem.isBlank() || operatingSystem.length > 120 || agentVersion.isBlank() || agentVersion.length > 64) {
            return error(Response.Status.BAD_REQUEST, "INVALID_AGENT", "注册信息无效")
        }

        val agentId = UUID.randomUUID()
        val credential = credentials.newToken()
        val enrollmentHash = credentials.tokenHash(enrollmentToken)
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val enrollment = connection.prepareStatement(
                    """SELECT e.id, e.resource_id, e.issued_by
                       FROM agent_enrollment e JOIN monitored_resource r ON r.id = e.resource_id
                       WHERE e.token_hash = ? AND e.consumed_at IS NULL AND e.expires_at > now()
                         AND r.type = 'HOST' AND r.deleted_at IS NULL
                       FOR UPDATE OF e""",
                ).use { statement ->
                    statement.setString(1, enrollmentHash)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.toEnrollment() else null }
                }
                if (enrollment == null) {
                    connection.rollback()
                    return@use error(Response.Status.UNAUTHORIZED, "ENROLLMENT_INVALID", "注册令牌无效、已使用或已过期")
                }
                val activeAgentExists = connection.prepareStatement(
                    "SELECT EXISTS (SELECT 1 FROM agent WHERE resource_id = ? AND revoked_at IS NULL)",
                ).use { statement ->
                    statement.setObject(1, enrollment.resourceId)
                    statement.executeQuery().use { rows -> rows.next(); rows.getBoolean(1) }
                }
                if (activeAgentExists) {
                    connection.rollback()
                    return@use error(Response.Status.CONFLICT, "AGENT_ALREADY_REGISTERED", "该主机资源已有有效 Agent，请先吊销旧凭据")
                }
                connection.prepareStatement("UPDATE agent_enrollment SET consumed_at = now() WHERE id = ?").use { statement ->
                    statement.setObject(1, enrollment.id)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO agent(id, resource_id, credential_hash, hostname, operating_system, agent_version, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, now())",
                ).use { statement ->
                    statement.setObject(1, agentId)
                    statement.setObject(2, enrollment.resourceId)
                    statement.setString(3, credentials.tokenHash(credential))
                    statement.setString(4, hostname)
                    statement.setString(5, operatingSystem)
                    statement.setString(6, agentVersion)
                    statement.executeUpdate()
                }
                writeAudit(connection, enrollment.issuedBy, "agent.registered", "agent", agentId, mapOf("resourceId" to enrollment.resourceId.toString(), "hostname" to hostname, "agentVersion" to agentVersion))
                connection.commit()
                Response.status(Response.Status.CREATED).entity(
                    mapOf("agentId" to agentId.toString(), "resourceId" to enrollment.resourceId.toString(), "credential" to credential, "configVersion" to 1),
                ).build()
            } catch (exception: Exception) {
                connection.rollback()
                if (exception.message.orEmpty().contains("duplicate key", true)) {
                    error(Response.Status.CONFLICT, "AGENT_ALREADY_REGISTERED", "该主机资源已有有效 Agent")
                } else {
                    throw exception
                }
            }
        }
    }

    @GET
    @Path("/agents")
    fun listAgents(@QueryParam("projectId") projectIdText: String?, @Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val projectId = projectIdText?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (projectIdText != null && projectId == null) return error(Response.Status.BAD_REQUEST, "INVALID_PROJECT", "项目编号无效")
        if (projectId != null && !authenticator.canAccessProject(user.id, projectId)) return error(Response.Status.FORBIDDEN, "PROJECT_FORBIDDEN", "你没有该项目的访问权限")
        val agents = mutableListOf<Map<String, Any?>>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT a.id, a.resource_id, r.name AS resource_name, r.project_id, p.name AS project_name,
                          a.hostname, a.operating_system, a.agent_version, a.config_version, a.last_seen_at,
                          a.revoked_at, a.created_at,
                          CASE WHEN a.revoked_at IS NOT NULL THEN 'REVOKED'
                               WHEN a.last_seen_at >= now() - INTERVAL '90 seconds' THEN 'ONLINE'
                               ELSE 'OFFLINE' END AS node_status
                   FROM agent a JOIN monitored_resource r ON r.id = a.resource_id
                   JOIN project p ON p.id = r.project_id
                   JOIN project_membership pm ON pm.project_id = r.project_id
                   WHERE pm.user_id = ? AND r.deleted_at IS NULL AND (?::uuid IS NULL OR r.project_id = ?::uuid)
                   ORDER BY a.created_at DESC LIMIT 500""",
            ).use { statement ->
                statement.setObject(1, user.id)
                statement.setObject(2, projectId)
                statement.setObject(3, projectId)
                statement.executeQuery().use { rows -> while (rows.next()) agents += rows.toAgentResponse() }
            }
        }
        return Response.ok(agents).build()
    }

    @GET
    @Path("/agents/{id}/config")
    fun getAgentConfig(@PathParam("id") idText: String, @Context headers: HttpHeaders): Response {
        val agentId = parseId(idText) ?: return error(Response.Status.BAD_REQUEST, "INVALID_AGENT", "Agent 编号无效")
        return dataSource.connection.use { connection ->
            val agent = authenticateAgent(connection, agentId, headers)
                ?: return@use error(Response.Status.UNAUTHORIZED, "AGENT_UNAUTHENTICATED", "Agent 凭据无效或已吊销")
            val now = Instant.now()
            Response.ok(
                mapOf(
                    "agentId" to agent.id.toString(),
                    "resourceId" to agent.resourceId.toString(),
                    "configVersion" to agent.configVersion,
                    "collectionIntervalSeconds" to COLLECTION_INTERVAL_SECONDS,
                    "serverTime" to now.toString(),
                    "metrics" to HOST_METRICS,
                ),
            ).build()
        }
    }

    @POST
    @Path("/agents/{id}/batches")
    fun ingestBatch(@PathParam("id") idText: String, request: AgentBatchRequest, @Context headers: HttpHeaders): Response {
        val agentId = parseId(idText) ?: return error(Response.Status.BAD_REQUEST, "INVALID_AGENT", "Agent 编号无效")
        val bootId = request.bootId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_BATCH", "启动编号无效")
        val sequence = request.sequence ?: 0
        val configVersion = request.configVersion ?: 0
        val sampledAt = request.sampledAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_BATCH", "采样时间无效")
        val metrics = request.metrics ?: emptyList()
        if (sequence <= 0 || configVersion <= 0 || metrics.isEmpty() || metrics.size > MAX_METRICS_PER_BATCH) {
            return error(Response.Status.BAD_REQUEST, "INVALID_BATCH", "批次序号、配置版本或指标数量无效")
        }
        val normalized = normalizeMetrics(metrics) ?: return error(Response.Status.BAD_REQUEST, "INVALID_METRIC", "指标名称、标签或数值不在允许范围内")
        val now = Instant.now()
        if (sampledAt.isBefore(now.minus(MAX_SAMPLE_AGE)) || sampledAt.isAfter(now.plus(MAX_FUTURE_SKEW))) {
            return error(Response.Status.BAD_REQUEST, "SAMPLE_TIME_OUT_OF_RANGE", "采样时间超出允许范围")
        }
        val sampleAgeMillis = Duration.between(sampledAt, now).toMillis()
        val clockSynced = request.clockSynced == true
        val clientClockOffsetMillis = request.clientClockOffsetMs
        if (clockSynced && clientClockOffsetMillis == null) {
            return error(Response.Status.BAD_REQUEST, "INVALID_BATCH", "时钟校准状态与偏差值不匹配")
        }
        val trusted = clockSynced && clientClockOffsetMillis?.let { offset ->
            offset >= -TRUSTED_CLOCK_SKEW.toMillis() && offset <= TRUSTED_CLOCK_SKEW.toMillis()
        } == true

        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val agent = authenticateAgent(connection, agentId, headers)
                if (agent == null) {
                    connection.rollback()
                    return@use error(Response.Status.UNAUTHORIZED, "AGENT_UNAUTHENTICATED", "Agent 凭据无效或已吊销")
                }
                if (configVersion > agent.configVersion) {
                    connection.rollback()
                    return@use error(Response.Status.CONFLICT, "AGENT_CONFIG_VERSION_FUTURE", "批次配置版本尚未由服务端签发")
                }
                val batchId = UUID.randomUUID()
                val insertedBatchId = connection.prepareStatement(
                    """INSERT INTO agent_batch(id, agent_id, boot_id, sequence, sampled_at, config_version,
                              receive_sample_delta_ms, client_clock_offset_ms, clock_synced, trusted, accepted_count)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (agent_id, boot_id, sequence) DO NOTHING RETURNING id""",
                ).use { statement ->
                    statement.setObject(1, batchId)
                    statement.setObject(2, agent.id)
                    statement.setObject(3, bootId)
                    statement.setLong(4, sequence)
                    statement.setTimestamp(5, Timestamp.from(sampledAt))
                    statement.setInt(6, configVersion)
                    statement.setLong(7, sampleAgeMillis)
                    statement.setObject(8, clientClockOffsetMillis)
                    statement.setBoolean(9, clockSynced)
                    statement.setBoolean(10, trusted)
                    statement.setInt(11, normalized.size)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getObject("id", UUID::class.java) else null }
                }
                if (insertedBatchId == null) {
                    connection.prepareStatement("UPDATE agent SET last_seen_at = now(), updated_at = now() WHERE id = ?").use { statement ->
                        statement.setObject(1, agent.id)
                        statement.executeUpdate()
                    }
                    connection.commit()
                    return@use Response.ok(mapOf("accepted" to 0, "duplicate" to true, "trusted" to trusted, "configVersion" to agent.configVersion)).build()
                }
                connection.prepareStatement(
                    """INSERT INTO metric_sample(batch_id, agent_id, resource_id, metric_name, labels, value, sampled_at, config_version, trusted)
                       VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)""",
                ).use { statement ->
                    normalized.forEach { metric ->
                        statement.setObject(1, insertedBatchId)
                        statement.setObject(2, agent.id)
                        statement.setObject(3, agent.resourceId)
                        statement.setString(4, metric.name)
                        statement.setString(5, objectMapper.writeValueAsString(metric.labels))
                        statement.setDouble(6, metric.value)
                        statement.setTimestamp(7, Timestamp.from(sampledAt))
                        statement.setInt(8, configVersion)
                        statement.setBoolean(9, trusted)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.prepareStatement("UPDATE agent SET last_seen_at = now(), updated_at = now() WHERE id = ?").use { statement ->
                    statement.setObject(1, agent.id)
                    statement.executeUpdate()
                }
                connection.commit()
                Response.accepted(
                    mapOf(
                        "accepted" to normalized.size,
                        "duplicate" to false,
                        "trusted" to trusted,
                        "sampleAgeMs" to sampleAgeMillis,
                        "clientClockOffsetMs" to clientClockOffsetMillis,
                        "clockSynced" to clockSynced,
                        "configVersion" to agent.configVersion,
                    ),
                ).build()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
    }

    @POST
    @Path("/agents/{id}/revoke")
    fun revoke(@PathParam("id") idText: String, @Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        if (user.role != "ADMIN") return error(Response.Status.FORBIDDEN, "ADMIN_REQUIRED", "只有管理员可以吊销 Agent 凭据")
        val id = parseId(idText) ?: return error(Response.Status.BAD_REQUEST, "INVALID_AGENT", "Agent 编号无效")
        val updated = dataSource.connection.use { connection ->
            connection.prepareStatement(
                """UPDATE agent a SET revoked_at = now(), updated_at = now()
                   WHERE a.id = ? AND a.revoked_at IS NULL AND EXISTS (
                     SELECT 1 FROM monitored_resource r JOIN project_membership pm ON pm.project_id = r.project_id
                     WHERE r.id = a.resource_id AND pm.user_id = ?
                   )""",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, user.id)
                statement.executeUpdate()
            }
        }
        if (updated != 1) return error(Response.Status.NOT_FOUND, "AGENT_NOT_FOUND", "Agent 不存在或已吊销")
        audit(user.id, "agent.revoked", id, emptyMap<String, Any?>())
        return Response.noContent().build()
    }

    private fun normalizeMetrics(metrics: List<AgentMetricInput>): List<NormalizedMetric>? {
        if (metrics.size > MAX_METRICS_PER_BATCH) return null
        val normalized = mutableListOf<NormalizedMetric>()
        val seen = mutableSetOf<String>()
        metrics.forEach { metric ->
            val name = metric.name?.trim().orEmpty()
            val value = metric.value ?: return null
            val labels = metric.labels ?: emptyMap()
            if (name !in MetricCatalog.hostMetricNames || !value.isFinite() || value < 0 || value > MAX_METRIC_VALUE) return null
            if (name.endsWith("_percent") && value > 100) return null
            val expectedLabel = MetricCatalog.labelKeysByMetric[name]
            if ((expectedLabel == null && labels.isNotEmpty()) ||
                (expectedLabel != null && labels.keys != setOf(expectedLabel))) return null
            if (labels.values.any { it.isBlank() || it.length > 255 || it.any(Char::isISOControl) }) return null
            val sortedLabels = labels.toSortedMap()
            val uniquenessKey = "$name:${objectMapper.writeValueAsString(sortedLabels)}"
            if (!seen.add(uniquenessKey)) return null
            normalized += NormalizedMetric(name, sortedLabels, value)
        }
        return normalized
    }

    private fun authenticateAgent(connection: Connection, agentId: UUID, headers: HttpHeaders): AgentIdentity? {
        val credential = bearer(headers) ?: return null
        val credentialHash = credentials.tokenHash(credential)
        return connection.prepareStatement(
            "SELECT id, resource_id, config_version FROM agent WHERE id = ? AND credential_hash = ? AND revoked_at IS NULL",
        ).use { statement ->
            statement.setObject(1, agentId)
            statement.setString(2, credentialHash)
            statement.executeQuery().use { rows -> if (rows.next()) AgentIdentity(rows.getObject("id", UUID::class.java), rows.getObject("resource_id", UUID::class.java), rows.getInt("config_version")) else null }
        }
    }

    private fun ResultSet.toEnrollment() = EnrollmentIdentity(
        id = getObject("id", UUID::class.java),
        resourceId = getObject("resource_id", UUID::class.java),
        issuedBy = getObject("issued_by", UUID::class.java),
    )

    private fun ResultSet.toAgentResponse(): Map<String, Any?> = mapOf(
        "id" to getObject("id").toString(),
        "resourceId" to getObject("resource_id").toString(),
        "resourceName" to getString("resource_name"),
        "projectId" to getObject("project_id").toString(),
        "projectName" to getString("project_name"),
        "hostname" to getString("hostname"),
        "operatingSystem" to getString("operating_system"),
        "agentVersion" to getString("agent_version"),
        "configVersion" to getInt("config_version"),
        "status" to getString("node_status"),
        "lastSeenAt" to getTimestamp("last_seen_at")?.toInstant()?.toString(),
        "revokedAt" to getTimestamp("revoked_at")?.toInstant()?.toString(),
        "createdAt" to getTimestamp("created_at").toInstant().toString(),
    )

    private fun writeAudit(connection: Connection, userId: UUID, action: String, subjectType: String, subjectId: UUID, summary: Map<String, Any?>) {
        connection.prepareStatement(
            "INSERT INTO audit_log(id, actor_user_id, action, subject_type, subject_id, summary) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))",
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, userId)
            statement.setString(3, action)
            statement.setString(4, subjectType)
            statement.setString(5, subjectId.toString())
            statement.setString(6, objectMapper.writeValueAsString(summary))
            statement.executeUpdate()
        }
    }

    private fun audit(userId: UUID, action: String, id: UUID, summary: Map<String, Any?>) = dataSource.connection.use { connection ->
        writeAudit(connection, userId, action, "agent", id, summary)
    }

    private fun bearer(headers: HttpHeaders): String? = headers.getHeaderString(HttpHeaders.AUTHORIZATION)
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter(' ')
        ?.takeIf(String::isNotBlank)

    private fun parseId(value: String) = runCatching { UUID.fromString(value) }.getOrNull()
    private fun error(status: Response.Status, code: String, message: String) = Response.status(status).entity(ErrorResponse(code, message)).build()

    private data class EnrollmentIdentity(val id: UUID, val resourceId: UUID, val issuedBy: UUID)
    private data class AgentIdentity(val id: UUID, val resourceId: UUID, val configVersion: Int)
    private data class NormalizedMetric(val name: String, val labels: Map<String, String>, val value: Double)

    companion object {
        private val ENROLLMENT_TTL = Duration.ofMinutes(15)
        private val TRUSTED_CLOCK_SKEW = Duration.ofMinutes(5)
        private val MAX_SAMPLE_AGE = Duration.ofDays(30)
        private val MAX_FUTURE_SKEW = Duration.ofMinutes(10)
        private const val MAX_METRICS_PER_BATCH = 500
        private const val MAX_METRIC_VALUE = 1_000_000_000_000_000.0
        private const val COLLECTION_INTERVAL_SECONDS = 30
        private val HOST_METRICS = MetricCatalog.hostMetricNames.toList()
    }
}

data class AgentEnrollmentRequest(val resourceId: String?)
data class AgentRegisterRequest(val enrollmentToken: String?, val hostname: String?, val operatingSystem: String?, val agentVersion: String?)
data class AgentBatchRequest(
    val bootId: String?,
    val sequence: Long?,
    val sampledAt: String?,
    val configVersion: Int?,
    val clientClockOffsetMs: Long? = null,
    val clockSynced: Boolean? = false,
    val metrics: List<AgentMetricInput>?,
)
data class AgentMetricInput(val name: String?, val value: Double?, val labels: Map<String, String>? = emptyMap())
