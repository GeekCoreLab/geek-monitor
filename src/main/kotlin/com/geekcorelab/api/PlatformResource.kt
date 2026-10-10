package com.geekcorelab.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekcorelab.security.CredentialService
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
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
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

@Path("/api/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class PlatformResource {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var credentials: CredentialService
    @Inject lateinit var objectMapper: ObjectMapper
    @Inject @field:ConfigProperty(name = "geek-monitor.session.days", defaultValue = "30")
    var sessionDays: Int = 30
    @Inject @field:ConfigProperty(name = "geek-monitor.security.cookie-name", defaultValue = "geek_monitor_session")
    lateinit var cookieName: String
    @Inject @field:ConfigProperty(name = "geek-monitor.security.secure-cookie", defaultValue = "true")
    var secureCookie: Boolean = true

    @GET
    @Path("/install/status")
    fun installStatus(): InstallStatus = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT initialized FROM installation_state WHERE id = 1").use { statement ->
            statement.executeQuery().use { result ->
                result.next()
                InstallStatus(result.getBoolean(1))
            }
        }
    }

    @POST
    @Path("/install/setup")
    fun setup(request: SetupRequest): Response {
        val email = request.email?.trim()?.lowercase().orEmpty()
        val password = request.password.orEmpty()
        val projectName = request.projectName?.trim().orEmpty().ifBlank { "默认项目" }
        if (!EMAIL.matches(email)) return error(Response.Status.BAD_REQUEST, "INVALID_EMAIL", "请输入有效的管理员邮箱")
        if (password.length < 12) return error(Response.Status.BAD_REQUEST, "WEAK_PASSWORD", "密码至少需要 12 个字符")
        if (projectName.length > 120) return error(Response.Status.BAD_REQUEST, "INVALID_PROJECT", "项目名称不能超过 120 个字符")

        val passwordHash = credentials.hashPassword(password)
        val userId = UUID.randomUUID()
        val projectId = UUID.randomUUID()
        val token = credentials.newToken()
        val expiresAt = Instant.now().plus(sessionDays.toLong(), ChronoUnit.DAYS)

        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val claim = connection.prepareStatement(
                    "UPDATE installation_state SET initialized = TRUE, initialized_at = now() WHERE id = 1 AND initialized = FALSE",
                ).use { it.executeUpdate() }
                if (claim != 1) {
                    connection.rollback()
                    return error(Response.Status.CONFLICT, "ALREADY_INITIALIZED", "系统已经完成初始化")
                }
                connection.prepareStatement(
                    "INSERT INTO app_user(id, email, password_hash, role) VALUES (?, ?, ?, 'ADMIN')",
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, email)
                    statement.setString(3, passwordHash)
                    statement.executeUpdate()
                }
                connection.prepareStatement("INSERT INTO project(id, name) VALUES (?, ?)").use { statement ->
                    statement.setObject(1, projectId)
                    statement.setString(2, projectName)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO project_membership(project_id, user_id, role) VALUES (?, ?, 'ADMIN')",
                ).use { statement ->
                    statement.setObject(1, projectId)
                    statement.setObject(2, userId)
                    statement.executeUpdate()
                }
                insertSession(connection, token, userId, expiresAt)
                connection.prepareStatement(
                    "INSERT INTO audit_log(id, actor_user_id, action, subject_type, subject_id, summary) VALUES (?, ?, 'installation.completed', 'user', ?, '{}'::jsonb)",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, userId)
                    statement.setString(3, userId.toString())
                    statement.executeUpdate()
                }
                connection.commit()
                Response.status(Response.Status.CREATED)
                    .header(HttpHeaders.SET_COOKIE, sessionCookie(token, expiresAt))
                    .entity(SessionResponse(expiresAt.toString(), UserResponse(userId.toString(), email, "ADMIN")))
                    .build()
            } catch (exception: Exception) {
                connection.rollback()
                if (exception.message?.contains("duplicate key", true) == true) {
                    error(Response.Status.CONFLICT, "EMAIL_EXISTS", "该邮箱已注册")
                } else {
                    throw exception
                }
            }
        }
    }

    @POST
    @Path("/auth/login")
    fun login(request: LoginRequest): Response {
        val email = request.email?.trim()?.lowercase().orEmpty()
        val password = request.password.orEmpty()
        val login = dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT id, email, password_hash, role FROM app_user WHERE email = ? AND disabled_at IS NULL").use { statement ->
                statement.setString(1, email)
                statement.executeQuery().use { result -> if (result.next()) LoginUser(result.getObject("id", UUID::class.java), result.getString("email"), result.getString("password_hash"), result.getString("role")) else null }
            }
        }
        if (login == null || !credentials.verifyPassword(password, login.passwordHash)) {
            return error(Response.Status.UNAUTHORIZED, "INVALID_CREDENTIALS", "邮箱或密码错误")
        }
        val token = credentials.newToken()
        val expiresAt = Instant.now().plus(sessionDays.toLong(), ChronoUnit.DAYS)
        dataSource.connection.use { connection -> insertSession(connection, token, login.id, expiresAt) }
        return Response.ok(SessionResponse(expiresAt.toString(), UserResponse(login.id.toString(), login.email, login.role)))
            .header(HttpHeaders.SET_COOKIE, sessionCookie(token, expiresAt))
            .build()
    }

    @GET
    @Path("/auth/me")
    fun me(@Context headers: HttpHeaders): Response {
        val user = authenticatedUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        return Response.ok(UserResponse(user.id.toString(), user.email, user.role)).build()
    }

    @POST
    @Path("/auth/logout")
    fun logout(@Context headers: HttpHeaders): Response {
        val token = requestToken(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE auth_session SET revoked_at = now() WHERE token_hash = ? AND revoked_at IS NULL").use { statement ->
                statement.setString(1, credentials.tokenHash(token))
                statement.executeUpdate()
            }
        }
        return Response.noContent().header(HttpHeaders.SET_COOKIE, expiredSessionCookie()).build()
    }

    @GET
    @Path("/projects")
    fun listProjects(@Context headers: HttpHeaders): Response {
        val user = requireAdmin(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val projects = mutableListOf<ProjectResponse>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT p.id, p.name, p.description, p.created_at FROM project p JOIN project_membership pm ON pm.project_id = p.id WHERE pm.user_id = ? ORDER BY p.created_at",
            ).use { statement ->
                statement.setObject(1, user.id)
                statement.executeQuery().use { result -> while (result.next()) projects += result.toProject() }
            }
        }
        return Response.ok(projects).build()
    }

    @POST
    @Path("/projects")
    fun createProject(request: ProjectRequest, @Context headers: HttpHeaders): Response {
        val user = requireAdmin(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val name = request.name?.trim().orEmpty()
        val description = request.description?.trim().orEmpty()
        if (name.isBlank() || name.length > 120 || description.length > 1000) return error(Response.Status.BAD_REQUEST, "INVALID_PROJECT", "请检查项目名称和说明")
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("INSERT INTO project(id, name, description) VALUES (?, ?, ?)").use { statement ->
                    statement.setObject(1, id)
                    statement.setString(2, name)
                    statement.setString(3, description)
                    statement.executeUpdate()
                }
                connection.prepareStatement("INSERT INTO project_membership(project_id, user_id, role) VALUES (?, ?, 'ADMIN')").use { statement ->
                    statement.setObject(1, id)
                    statement.setObject(2, user.id)
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
        return Response.status(Response.Status.CREATED).entity(ProjectResponse(id.toString(), name, description, Instant.now().toString())).build()
    }

    @GET
    @Path("/resources")
    fun listResources(@QueryParam("projectId") projectId: String?, @Context headers: HttpHeaders): Response {
        val user = authenticatedUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val parsedProjectId = projectId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (projectId != null && parsedProjectId == null) return error(Response.Status.BAD_REQUEST, "INVALID_PROJECT", "项目编号无效")
        val resources = mutableListOf<ResourceResponse>()
        val query = """SELECT r.id, r.project_id, p.name AS project_name, r.name, r.type, r.environment, r.tags::text AS tags,
            r.owner_email, r.health_status, r.paused, r.created_at
            FROM monitored_resource r JOIN project p ON p.id = r.project_id
            JOIN project_membership pm ON pm.project_id = p.id
            WHERE pm.user_id = ? AND r.deleted_at IS NULL AND (?::uuid IS NULL OR r.project_id = ?::uuid)
            ORDER BY r.created_at DESC LIMIT 500"""
        dataSource.connection.use { connection ->
            connection.prepareStatement(query).use { statement ->
                statement.setObject(1, user.id)
                statement.setObject(2, parsedProjectId)
                statement.setObject(3, parsedProjectId)
                statement.executeQuery().use { result -> while (result.next()) resources += result.toResource() }
            }
        }
        return Response.ok(resources).build()
    }

    @POST
    @Path("/resources")
    fun createResource(request: ResourceRequest, @Context headers: HttpHeaders): Response {
        val user = authenticatedUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val projectId = request.projectId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val name = request.name?.trim().orEmpty()
        val type = request.type?.trim()?.uppercase().orEmpty()
        val environment = request.environment?.trim().orEmpty().ifBlank { "production" }
        val tags = (request.tags ?: emptyList()).map(String::trim).filter(String::isNotEmpty).distinct().take(20)
        val ownerEmail = request.ownerEmail?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        if (projectId == null) return error(Response.Status.BAD_REQUEST, "INVALID_PROJECT", "请选择有效项目")
        if (name.isBlank() || name.length > 160) return error(Response.Status.BAD_REQUEST, "INVALID_RESOURCE", "资源名称不能为空且不能超过 160 个字符")
        if (type !in RESOURCE_TYPES) return error(Response.Status.BAD_REQUEST, "INVALID_RESOURCE_TYPE", "资源类型无效")
        if (environment.length > 80 || (ownerEmail != null && !EMAIL.matches(ownerEmail))) return error(Response.Status.BAD_REQUEST, "INVALID_RESOURCE", "环境或负责人邮箱无效")
        val authorized = dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT 1 FROM project_membership WHERE project_id = ? AND user_id = ?").use { statement ->
                statement.setObject(1, projectId)
                statement.setObject(2, user.id)
                statement.executeQuery().use(ResultSet::next)
            }
        }
        if (!authorized) return error(Response.Status.FORBIDDEN, "PROJECT_FORBIDDEN", "你没有该项目的访问权限")
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO monitored_resource(id, project_id, name, type, environment, tags, owner_email) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, projectId)
                statement.setString(3, name)
                statement.setString(4, type)
                statement.setString(5, environment)
                statement.setString(6, objectMapper.writeValueAsString(tags))
                statement.setString(7, ownerEmail)
                statement.executeUpdate()
            }
        }
        val created = getResource(id, user.id) ?: return error(Response.Status.INTERNAL_SERVER_ERROR, "RESOURCE_LOAD_FAILED", "资源已创建，但无法读取")
        return Response.status(Response.Status.CREATED).entity(created).build()
    }

    @DELETE
    @Path("/resources/{id}")
    fun deleteResource(@PathParam("id") idText: String, @Context headers: HttpHeaders): Response {
        val user = authenticatedUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val id = runCatching { UUID.fromString(idText) }.getOrNull() ?: return error(Response.Status.BAD_REQUEST, "INVALID_RESOURCE", "资源编号无效")
        val updated = dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE monitored_resource r SET deleted_at = now(), updated_at = now() WHERE r.id = ? AND r.deleted_at IS NULL AND EXISTS (SELECT 1 FROM project_membership pm WHERE pm.project_id = r.project_id AND pm.user_id = ?)",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, user.id)
                statement.executeUpdate()
            }
        }
        return if (updated == 1) Response.noContent().build() else error(Response.Status.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在或无权访问")
    }

    private fun getResource(id: UUID, userId: UUID): ResourceResponse? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT r.id, r.project_id, p.name AS project_name, r.name, r.type, r.environment, r.tags::text AS tags, r.owner_email, r.health_status, r.paused, r.created_at FROM monitored_resource r JOIN project p ON p.id = r.project_id JOIN project_membership pm ON pm.project_id = p.id WHERE r.id = ? AND pm.user_id = ? AND r.deleted_at IS NULL",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, userId)
            statement.executeQuery().use { result -> if (result.next()) result.toResource() else null }
        }
    }

    private fun authenticatedUser(headers: HttpHeaders): SessionUser? {
        val token = requestToken(headers) ?: return null
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT u.id, u.email, u.role FROM auth_session s JOIN app_user u ON u.id = s.user_id WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > now() AND u.disabled_at IS NULL",
            ).use { statement ->
                statement.setString(1, credentials.tokenHash(token))
                statement.executeQuery().use { result -> if (result.next()) SessionUser(result.getObject("id", UUID::class.java), result.getString("email"), result.getString("role")) else null }
            }
        }
    }

    private fun requireAdmin(headers: HttpHeaders): SessionUser? = authenticatedUser(headers)?.takeIf { it.role == "ADMIN" }

    private fun bearer(headers: HttpHeaders): String? = headers.getHeaderString(HttpHeaders.AUTHORIZATION)
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter(' ')
        ?.takeIf(String::isNotBlank)

    private fun requestToken(headers: HttpHeaders): String? =
        headers.cookies[cookieName]?.value?.takeIf(String::isNotBlank) ?: bearer(headers)

    private fun sessionCookie(token: String, expiresAt: Instant): String = buildString {
        append(cookieName).append('=').append(token)
        append("; Path=/; HttpOnly; SameSite=Strict; Max-Age=")
        append(ChronoUnit.SECONDS.between(Instant.now(), expiresAt).coerceAtLeast(0))
        if (secureCookie) append("; Secure")
    }

    private fun expiredSessionCookie(): String = buildString {
        append(cookieName).append("=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0")
        if (secureCookie) append("; Secure")
    }

    private fun insertSession(connection: Connection, token: String, userId: UUID, expiresAt: Instant) {
        connection.prepareStatement("INSERT INTO auth_session(token_hash, user_id, expires_at) VALUES (?, ?, ?)").use { statement ->
            statement.setString(1, credentials.tokenHash(token))
            statement.setObject(2, userId)
            statement.setObject(3, java.sql.Timestamp.from(expiresAt))
            statement.executeUpdate()
        }
    }

    private fun error(status: Response.Status, code: String, message: String) = Response.status(status).entity(ErrorResponse(code, message)).build()
    private fun ResultSet.toProject() = ProjectResponse(getObject("id").toString(), getString("name"), getString("description"), getTimestamp("created_at").toInstant().toString())
    private fun ResultSet.toResource(): ResourceResponse = ResourceResponse(
        getObject("id").toString(), getObject("project_id").toString(), getString("project_name"), getString("name"), getString("type"),
        getString("environment"), objectMapper.readValue(getString("tags"), objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java)) as List<String>,
        getString("owner_email"), getString("health_status"), getBoolean("paused"), getTimestamp("created_at").toInstant().toString(),
    )

    private data class LoginUser(val id: UUID, val email: String, val passwordHash: String, val role: String)
    private data class SessionUser(val id: UUID, val email: String, val role: String)

    companion object {
        private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        private val RESOURCE_TYPES = setOf("HOST", "WEBSITE", "DATABASE", "SERVICE", "HEARTBEAT")
    }
}
