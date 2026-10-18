package com.geekcorelab.geekmonitor.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekcorelab.geekmonitor.security.SecretEncryptionService
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

@Path("/api/v1/projects/{projectId}/credentials")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class ProjectCredentialResource {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var objectMapper: ObjectMapper
    @Inject lateinit var authenticator: SessionAuthenticator
    @Inject lateinit var secretEncryption: SecretEncryptionService

    @GET
    fun list(@PathParam("projectId") projectIdText: String, @Context headers: HttpHeaders): Response {
        val user = authenticator.currentUser(headers)
            ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val projectId = parseId(projectIdText)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_PROJECT", "项目编号无效")
        if (!authenticator.canAccessProject(user.id, projectId)) {
            return error(Response.Status.FORBIDDEN, "PROJECT_FORBIDDEN", "你没有该项目的访问权限")
        }
        val credentials = mutableListOf<ProjectCredentialResponse>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT id, project_id, name, encrypted_headers::text AS encrypted_headers, allowed_origins::text AS allowed_origins, version, created_at, updated_at FROM project_credential WHERE project_id = ? ORDER BY name",
            ).use { statement ->
                statement.setObject(1, projectId)
                statement.executeQuery().use { rows -> while (rows.next()) credentials += rows.toCredential() }
            }
        }
        return Response.ok(credentials).build()
    }

    @POST
    fun create(
        @PathParam("projectId") projectIdText: String,
        request: ProjectCredentialRequest,
        @Context headers: HttpHeaders,
    ): Response {
        val user = authenticator.currentUser(headers)
            ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val projectId = parseId(projectIdText)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_PROJECT", "项目编号无效")
        if (!isProjectAdmin(user, projectId)) {
            return error(Response.Status.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "只有项目管理员可以管理凭据")
        }
        val name = request.name?.trim().orEmpty()
        if (!validName(name)) return error(Response.Status.BAD_REQUEST, "INVALID_CREDENTIAL", "凭据名称无效")
        val allowedOrigins = ProjectCredentialOrigins.normalizeAllowed(request.allowedOrigins)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_CREDENTIAL_SCOPE", "至少填写一个有效的 HTTP/HTTPS 源站")
        val submittedHeaders = request.headers
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_CREDENTIAL", "至少填写一个请求头")
        if (hasSecretValues(submittedHeaders) && !secretEncryption.isConfigured()) {
            return encryptionUnavailable()
        }
        val encryptedHeaders = HttpRequestHeaderConfig.encrypt(submittedHeaders, emptyMap(), secretEncryption)
            ?.takeIf { it.isNotEmpty() }
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_CREDENTIAL", "请求头无效、为空或超过限制")
        val id = UUID.randomUUID()
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "INSERT INTO project_credential(id, project_id, name, encrypted_headers, allowed_origins, created_by) VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)",
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setObject(2, projectId)
                    statement.setString(3, name)
                    statement.setString(4, objectMapper.writeValueAsString(encryptedHeaders))
                    statement.setString(5, objectMapper.writeValueAsString(allowedOrigins))
                    statement.setObject(6, user.id)
                    statement.executeUpdate()
                }
                writeAudit(connection, user.id, "project_credential.created", id, projectId, name, encryptedHeaders.keys, allowedOrigins)
                connection.commit()
                val created = findCredential(connection, id, projectId)
                    ?: return@use error(Response.Status.INTERNAL_SERVER_ERROR, "CREDENTIAL_LOAD_FAILED", "凭据已创建，但无法读取")
                Response.status(Response.Status.CREATED).entity(created).build()
            } catch (exception: SQLException) {
                connection.rollback()
                if (exception.sqlState == "23505") {
                    error(Response.Status.CONFLICT, "CREDENTIAL_NAME_EXISTS", "该项目已有同名凭据")
                } else {
                    throw exception
                }
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
    }

    @PATCH
    @Path("/{credentialId}")
    fun update(
        @PathParam("projectId") projectIdText: String,
        @PathParam("credentialId") credentialIdText: String,
        request: ProjectCredentialRequest,
        @Context headers: HttpHeaders,
    ): Response {
        val user = authenticator.currentUser(headers)
            ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val projectId = parseId(projectIdText)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_PROJECT", "项目编号无效")
        val credentialId = parseId(credentialIdText)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_CREDENTIAL", "凭据编号无效")
        if (!isProjectAdmin(user, projectId)) {
            return error(Response.Status.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "只有项目管理员可以管理凭据")
        }
        if (request.version == null) {
            return error(Response.Status.BAD_REQUEST, "VERSION_REQUIRED", "更新凭据时必须提供版本号")
        }
        val name = request.name?.trim()
        if (name != null && !validName(name)) {
            return error(Response.Status.BAD_REQUEST, "INVALID_CREDENTIAL", "凭据名称无效")
        }
        val allowedOrigins = if (request.allowedOrigins == null) null else
            ProjectCredentialOrigins.normalizeAllowed(request.allowedOrigins)
                ?: return error(Response.Status.BAD_REQUEST, "INVALID_CREDENTIAL_SCOPE", "至少填写一个有效的 HTTP/HTTPS 源站")

        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val current = findCredential(connection, credentialId, projectId, lock = true)
                    ?: run {
                        connection.rollback()
                        return@use error(Response.Status.NOT_FOUND, "CREDENTIAL_NOT_FOUND", "凭据不存在或无权访问")
                    }
                if (request.version != current.version) {
                    connection.rollback()
                    return@use error(Response.Status.CONFLICT, "VERSION_CONFLICT", "凭据已被其他人更新，请刷新后重试")
                }
                val previousHeaders = loadEncryptedHeaders(connection, credentialId)
                val encryptedHeaders = if (request.headers == null) previousHeaders else {
                    if (hasSecretValues(request.headers) && !secretEncryption.isConfigured()) {
                        connection.rollback()
                        return@use encryptionUnavailable()
                    }
                    HttpRequestHeaderConfig.encrypt(request.headers, previousHeaders, secretEncryption)
                        ?.takeIf { it.isNotEmpty() }
                        ?: run {
                            connection.rollback()
                            return@use error(Response.Status.BAD_REQUEST, "INVALID_CREDENTIAL", "请求头无效、为空或超过限制")
                        }
                }
                val updatedName = name ?: current.name
                val updatedOrigins = allowedOrigins ?: current.allowedOrigins
                connection.prepareStatement(
                    "UPDATE project_credential SET name = ?, encrypted_headers = CAST(? AS jsonb), allowed_origins = CAST(? AS jsonb), version = version + 1, updated_at = now() WHERE id = ? AND project_id = ? AND version = ?",
                ).use { statement ->
                    statement.setString(1, updatedName)
                    statement.setString(2, objectMapper.writeValueAsString(encryptedHeaders))
                    statement.setString(3, objectMapper.writeValueAsString(updatedOrigins))
                    statement.setObject(4, credentialId)
                    statement.setObject(5, projectId)
                    statement.setInt(6, request.version)
                    if (statement.executeUpdate() != 1) {
                        connection.rollback()
                        return@use error(Response.Status.CONFLICT, "VERSION_CONFLICT", "凭据已被其他人更新，请刷新后重试")
                    }
                }
                writeAudit(connection, user.id, "project_credential.updated", credentialId, projectId, updatedName, encryptedHeaders.keys, updatedOrigins)
                connection.commit()
                Response.ok(findCredential(connection, credentialId, projectId)).build()
            } catch (exception: SQLException) {
                connection.rollback()
                if (exception.sqlState == "23505") {
                    error(Response.Status.CONFLICT, "CREDENTIAL_NAME_EXISTS", "该项目已有同名凭据")
                } else {
                    throw exception
                }
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
    }

    @DELETE
    @Path("/{credentialId}")
    fun delete(
        @PathParam("projectId") projectIdText: String,
        @PathParam("credentialId") credentialIdText: String,
        @Context headers: HttpHeaders,
    ): Response {
        val user = authenticator.currentUser(headers)
            ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val projectId = parseId(projectIdText)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_PROJECT", "项目编号无效")
        val credentialId = parseId(credentialIdText)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_CREDENTIAL", "凭据编号无效")
        if (!isProjectAdmin(user, projectId)) {
            return error(Response.Status.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "只有项目管理员可以管理凭据")
        }
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val current = findCredential(connection, credentialId, projectId, lock = true)
                    ?: run {
                        connection.rollback()
                        return@use error(Response.Status.NOT_FOUND, "CREDENTIAL_NOT_FOUND", "凭据不存在或无权访问")
                    }
                val referenced = connection.prepareStatement("SELECT 1 FROM monitor WHERE credential_id = ? LIMIT 1").use { statement ->
                    statement.setObject(1, credentialId)
                    statement.executeQuery().use { it.next() }
                }
                if (referenced) {
                    connection.rollback()
                    return@use error(Response.Status.CONFLICT, "CREDENTIAL_IN_USE", "仍有监控项引用该凭据，请先更换引用")
                }
                connection.prepareStatement("DELETE FROM project_credential WHERE id = ? AND project_id = ?").use { statement ->
                    statement.setObject(1, credentialId)
                    statement.setObject(2, projectId)
                    statement.executeUpdate()
                }
                writeAudit(connection, user.id, "project_credential.deleted", credentialId, projectId, current.name, current.headerNames, current.allowedOrigins)
                connection.commit()
                Response.noContent().build()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
    }

    private fun isProjectAdmin(user: AuthenticatedUser, projectId: UUID) =
        user.role == "ADMIN" && authenticator.canManageProject(user.id, projectId)

    private fun findCredential(
        connection: Connection,
        id: UUID,
        projectId: UUID,
        lock: Boolean = false,
    ): ProjectCredentialResponse? = connection.prepareStatement(
        "SELECT id, project_id, name, encrypted_headers::text AS encrypted_headers, allowed_origins::text AS allowed_origins, version, created_at, updated_at FROM project_credential WHERE id = ? AND project_id = ?${if (lock) " FOR UPDATE" else ""}",
    ).use { statement ->
        statement.setObject(1, id)
        statement.setObject(2, projectId)
        statement.executeQuery().use { rows -> if (rows.next()) rows.toCredential() else null }
    }

    private fun loadEncryptedHeaders(connection: Connection, id: UUID): Map<String, String> =
        connection.prepareStatement("SELECT encrypted_headers::text FROM project_credential WHERE id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows ->
                if (!rows.next()) emptyMap() else objectMapper.readValue(rows.getString(1), objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, String::class.java)) as Map<String, String>
            }
        }

    private fun ResultSet.toCredential(): ProjectCredentialResponse {
        val headers = objectMapper.readValue(
            getString("encrypted_headers"),
            objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, String::class.java),
        ) as Map<String, String>
        return ProjectCredentialResponse(
            id = getObject("id").toString(),
            projectId = getObject("project_id").toString(),
            name = getString("name"),
            headerNames = headers.keys.sorted(),
            allowedOrigins = objectMapper.readValue(
                getString("allowed_origins"),
                objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java),
            ) as List<String>,
            version = getInt("version"),
            createdAt = getTimestamp("created_at").toInstant().toString(),
            updatedAt = getTimestamp("updated_at").toInstant().toString(),
        )
    }

    private fun writeAudit(
        connection: Connection,
        actorId: UUID,
        action: String,
        credentialId: UUID,
        projectId: UUID,
        name: String,
        headerNames: Collection<String>,
        allowedOrigins: Collection<String>,
    ) {
        val summary = mapOf(
            "projectId" to projectId.toString(),
            "name" to name,
            "headerNames" to headerNames.sorted(),
            "allowedOrigins" to allowedOrigins.sorted(),
        )
        connection.prepareStatement(
            "INSERT INTO audit_log(id, actor_user_id, action, subject_type, subject_id, summary) VALUES (?, ?, ?, 'project_credential', ?, CAST(? AS jsonb))",
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, actorId)
            statement.setString(3, action)
            statement.setString(4, credentialId.toString())
            statement.setString(5, objectMapper.writeValueAsString(summary))
            statement.executeUpdate()
        }
    }

    private fun validName(name: String) = name.isNotBlank() && name.length <= 100 && !name.any(Char::isISOControl)

    private fun hasSecretValues(headers: Map<String, String>) = headers.values.any(String::isNotEmpty)

    private fun parseId(value: String) = runCatching { UUID.fromString(value) }.getOrNull()

    private fun encryptionUnavailable() = error(
        Response.Status.SERVICE_UNAVAILABLE,
        "SECRET_ENCRYPTION_UNAVAILABLE",
        "尚未配置凭据加密密钥",
    )

    private fun error(status: Response.Status, code: String, message: String) =
        Response.status(status).entity(ErrorResponse(code, message)).build()
}
