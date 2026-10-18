package com.geekcorelab.geekmonitor.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekcorelab.geekmonitor.security.CredentialService
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
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

@Path("/api/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class ProjectMemberResource {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var authenticator: SessionAuthenticator
    @Inject lateinit var credentials: CredentialService
    @Inject lateinit var objectMapper: ObjectMapper

    @GET
    @Path("/projects/{projectId}/members")
    fun list(@PathParam("projectId") projectIdText: String, @Context headers: jakarta.ws.rs.core.HttpHeaders): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val projectId = parseId(projectIdText) ?: return error(Response.Status.BAD_REQUEST, "INVALID_PROJECT", "项目编号无效")
        if (!isProjectAdmin(user, projectId)) return error(Response.Status.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "只有项目管理员可以管理成员")
        val members = mutableListOf<ProjectMemberResponse>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT u.id, u.email, pm.role, u.created_at FROM project_membership pm JOIN app_user u ON u.id = pm.user_id WHERE pm.project_id = ? ORDER BY CASE pm.role WHEN 'ADMIN' THEN 0 WHEN 'OPERATOR' THEN 1 ELSE 2 END, u.email",
            ).use { statement ->
                statement.setObject(1, projectId)
                statement.executeQuery().use { rows -> while (rows.next()) members += rows.toMember() }
            }
        }
        return Response.ok(members).build()
    }

    @POST
    @Path("/projects/{projectId}/members")
    fun add(
        @PathParam("projectId") projectIdText: String,
        request: ProjectMemberRequest,
        @Context headers: jakarta.ws.rs.core.HttpHeaders,
    ): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val projectId = parseId(projectIdText) ?: return error(Response.Status.BAD_REQUEST, "INVALID_PROJECT", "项目编号无效")
        if (!isProjectAdmin(user, projectId)) return error(Response.Status.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "只有项目管理员可以管理成员")
        val email = request.email?.trim()?.lowercase().orEmpty()
        if (!EMAIL.matches(email)) return error(Response.Status.BAD_REQUEST, "INVALID_EMAIL", "请输入有效的成员邮箱")
        val password = request.password.orEmpty()
        val requestedRole = request.role?.trim()?.uppercase()?.takeIf(String::isNotBlank)
        if (requestedRole != null && requestedRole !in ROLES) return error(Response.Status.BAD_REQUEST, "INVALID_ROLE", "成员角色无效")
        if (password.isNotEmpty() && password.length < 12) return error(Response.Status.BAD_REQUEST, "WEAK_PASSWORD", "新成员密码至少需要 12 个字符")

        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                lockProject(connection, projectId)
                if (!isProjectAdmin(connection, user.id, projectId)) {
                    connection.rollback()
                    return@use error(Response.Status.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "只有项目管理员可以管理成员")
                }
                val account = connection.prepareStatement(
                    "SELECT id, role, disabled_at FROM app_user WHERE email = ? FOR UPDATE",
                ).use { statement ->
                    statement.setString(1, email)
                    statement.executeQuery().use { rows -> if (rows.next()) Account(rows.getObject("id", UUID::class.java), rows.getString("role"), rows.getTimestamp("disabled_at") != null) else null }
                }
                val memberId: UUID
                val role: String
                if (account != null) {
                    if (account.disabled) {
                        connection.rollback()
                        return@use error(Response.Status.CONFLICT, "ACCOUNT_DISABLED", "该成员账号已停用")
                    }
                    if (password.isNotEmpty()) {
                        connection.rollback()
                        return@use error(Response.Status.CONFLICT, "ACCOUNT_EXISTS", "该邮箱已有账号；添加已有账号时请留空密码")
                    }
                    if (requestedRole != null && requestedRole != account.role) {
                        connection.rollback()
                        return@use error(Response.Status.CONFLICT, "ROLE_CONFLICT", "已有账号当前角色为 ${account.role}，请先更新账号角色或沿用当前角色")
                    }
                    memberId = account.id
                    role = account.role
                } else {
                    if (password.length < 12) {
                        connection.rollback()
                        return@use error(Response.Status.BAD_REQUEST, "WEAK_PASSWORD", "新成员需要设置至少 12 个字符的初始密码")
                    }
                    memberId = UUID.randomUUID()
                    role = requestedRole ?: "OPERATOR"
                    connection.prepareStatement(
                        "INSERT INTO app_user(id, email, password_hash, role) VALUES (?, ?, ?, ?)",
                    ).use { statement ->
                        statement.setObject(1, memberId)
                        statement.setString(2, email)
                        statement.setString(3, credentials.hashPassword(password))
                        statement.setString(4, role)
                        statement.executeUpdate()
                    }
                }
                val alreadyMember = connection.prepareStatement(
                    "SELECT 1 FROM project_membership WHERE project_id = ? AND user_id = ?",
                ).use { statement ->
                    statement.setObject(1, projectId)
                    statement.setObject(2, memberId)
                    statement.executeQuery().use { it.next() }
                }
                if (alreadyMember) {
                    connection.rollback()
                    return@use error(Response.Status.CONFLICT, "MEMBER_EXISTS", "该账号已经是项目成员")
                }
                connection.prepareStatement(
                    "INSERT INTO project_membership(project_id, user_id, role) VALUES (?, ?, ?)",
                ).use { statement ->
                    statement.setObject(1, projectId)
                    statement.setObject(2, memberId)
                    statement.setString(3, role)
                    statement.executeUpdate()
                }
                audit(connection, user.id, "project.member_added", memberId, mapOf("projectId" to projectId.toString(), "email" to email, "role" to role))
                val createdMember = loadMember(connection, memberId, projectId)
                    ?: throw IllegalStateException("created project member could not be loaded")
                connection.commit()
                Response.status(Response.Status.CREATED).entity(createdMember).build()
            } catch (exception: Exception) {
                connection.rollback()
                if (exception.message.orEmpty().contains("duplicate key", true)) {
                    error(Response.Status.CONFLICT, "MEMBER_EXISTS", "该账号已经是项目成员")
                } else {
                    throw exception
                }
            }
        }
    }

    @PATCH
    @Path("/projects/{projectId}/members/{memberId}")
    fun updateRole(
        @PathParam("projectId") projectIdText: String,
        @PathParam("memberId") memberIdText: String,
        request: ProjectMemberRoleRequest,
        @Context headers: jakarta.ws.rs.core.HttpHeaders,
    ): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val projectId = parseId(projectIdText) ?: return error(Response.Status.BAD_REQUEST, "INVALID_PROJECT", "项目编号无效")
        val memberId = parseId(memberIdText) ?: return error(Response.Status.BAD_REQUEST, "INVALID_MEMBER", "成员编号无效")
        if (!isProjectAdmin(user, projectId)) return error(Response.Status.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "只有项目管理员可以管理成员")
        if (memberId == user.id) return error(Response.Status.CONFLICT, "SELF_ROLE_CHANGE", "不能通过当前会话修改自己的角色")
        val role = request.role?.trim()?.uppercase().orEmpty()
        if (role !in ROLES) return error(Response.Status.BAD_REQUEST, "INVALID_ROLE", "成员角色无效")

        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "SELECT p.id FROM project p WHERE p.id = ? OR EXISTS (SELECT 1 FROM project_membership pm WHERE pm.project_id = p.id AND pm.user_id = ?) ORDER BY p.id FOR UPDATE",
                ).use { statement ->
                    statement.setObject(1, projectId)
                    statement.setObject(2, memberId)
                    statement.executeQuery().use { rows -> while (rows.next()) {} }
                }
                if (!isProjectAdmin(connection, user.id, projectId)) {
                    connection.rollback()
                    return@use error(Response.Status.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "只有项目管理员可以管理成员")
                }
                val current = connection.prepareStatement(
                    "SELECT u.role FROM project_membership pm JOIN app_user u ON u.id = pm.user_id WHERE pm.project_id = ? AND pm.user_id = ? FOR UPDATE OF pm, u",
                ).use { statement ->
                    statement.setObject(1, projectId)
                    statement.setObject(2, memberId)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getString("role") else null }
                } ?: run {
                    connection.rollback()
                    return@use error(Response.Status.NOT_FOUND, "MEMBER_NOT_FOUND", "项目成员不存在")
                }
                if (current == "ADMIN" && role != "ADMIN" && isLastProjectAdmin(connection, memberId)) {
                    connection.rollback()
                    return@use error(Response.Status.CONFLICT, "LAST_PROJECT_ADMIN", "该账号是其某个项目的最后一位管理员，不能降级")
                }
                connection.prepareStatement("UPDATE app_user SET role = ? WHERE id = ?").use { statement ->
                    statement.setString(1, role)
                    statement.setObject(2, memberId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("UPDATE project_membership SET role = ? WHERE user_id = ?").use { statement ->
                    statement.setString(1, role)
                    statement.setObject(2, memberId)
                    statement.executeUpdate()
                }
                audit(connection, user.id, "project.member_role_updated", memberId, mapOf("projectId" to projectId.toString(), "role" to role))
                val updatedMember = loadMember(connection, memberId, projectId)
                    ?: throw IllegalStateException("updated project member could not be loaded")
                connection.commit()
                Response.ok(updatedMember).build()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
    }

    @DELETE
    @Path("/projects/{projectId}/members/{memberId}")
    fun remove(
        @PathParam("projectId") projectIdText: String,
        @PathParam("memberId") memberIdText: String,
        @Context headers: jakarta.ws.rs.core.HttpHeaders,
    ): Response {
        val user = authenticator.currentUser(headers) ?: return error(Response.Status.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        val projectId = parseId(projectIdText) ?: return error(Response.Status.BAD_REQUEST, "INVALID_PROJECT", "项目编号无效")
        val memberId = parseId(memberIdText) ?: return error(Response.Status.BAD_REQUEST, "INVALID_MEMBER", "成员编号无效")
        if (!isProjectAdmin(user, projectId)) return error(Response.Status.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "只有项目管理员可以管理成员")
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                lockProject(connection, projectId)
                if (!isProjectAdmin(connection, user.id, projectId)) {
                    connection.rollback()
                    return@use error(Response.Status.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "只有项目管理员可以管理成员")
                }
                val role = connection.prepareStatement(
                    "SELECT role FROM project_membership WHERE project_id = ? AND user_id = ? FOR UPDATE",
                ).use { statement ->
                    statement.setObject(1, projectId)
                    statement.setObject(2, memberId)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getString("role") else null }
                } ?: run {
                    connection.rollback()
                    return@use error(Response.Status.NOT_FOUND, "MEMBER_NOT_FOUND", "项目成员不存在")
                }
                if (role == "ADMIN" && isLastProjectAdmin(connection, memberId, projectId)) {
                    connection.rollback()
                    return@use error(Response.Status.CONFLICT, "LAST_PROJECT_ADMIN", "不能移除项目的最后一位管理员")
                }
                connection.prepareStatement("DELETE FROM project_membership WHERE project_id = ? AND user_id = ?").use { statement ->
                    statement.setObject(1, projectId)
                    statement.setObject(2, memberId)
                    statement.executeUpdate()
                }
                audit(connection, user.id, "project.member_removed", memberId, mapOf("projectId" to projectId.toString()))
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

    private fun isProjectAdmin(connection: Connection, userId: UUID, projectId: UUID): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM project_membership WHERE project_id = ? AND user_id = ? AND role = 'ADMIN'",
        ).use { statement ->
            statement.setObject(1, projectId)
            statement.setObject(2, userId)
            statement.executeQuery().use { it.next() }
        }

    private fun lockProject(connection: Connection, projectId: UUID) {
        connection.prepareStatement("SELECT id FROM project WHERE id = ? FOR UPDATE").use { statement ->
            statement.setObject(1, projectId)
            statement.executeQuery().use { rows -> while (rows.next()) {} }
        }
    }

    private fun isLastProjectAdmin(connection: Connection, userId: UUID): Boolean =
        connection.prepareStatement(
            """SELECT EXISTS (
                   SELECT 1 FROM project_membership mine
                   WHERE mine.user_id = ? AND mine.role = 'ADMIN'
                     AND NOT EXISTS (
                         SELECT 1 FROM project_membership other
                         WHERE other.project_id = mine.project_id AND other.user_id <> ? AND other.role = 'ADMIN'
                     )
               )""",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, userId)
            statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }

    private fun isLastProjectAdmin(connection: Connection, userId: UUID, projectId: UUID): Boolean =
        connection.prepareStatement(
            "SELECT NOT EXISTS (SELECT 1 FROM project_membership WHERE project_id = ? AND role = 'ADMIN' AND user_id <> ?)",
        ).use { statement ->
            statement.setObject(1, projectId)
            statement.setObject(2, userId)
            statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }

    private fun loadMember(connection: Connection, memberId: UUID, projectId: UUID): ProjectMemberResponse? =
        connection.prepareStatement(
            "SELECT u.id, u.email, pm.role, u.created_at FROM project_membership pm JOIN app_user u ON u.id = pm.user_id WHERE pm.project_id = ? AND pm.user_id = ?",
        ).use { statement ->
            statement.setObject(1, projectId)
            statement.setObject(2, memberId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toMember() else null }
        }

    private fun audit(connection: Connection, actorId: UUID, action: String, memberId: UUID, summary: Map<String, Any>) {
        connection.prepareStatement(
            "INSERT INTO audit_log(id, actor_user_id, action, subject_type, subject_id, summary) VALUES (?, ?, ?, 'user', ?, CAST(? AS jsonb))",
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, actorId)
            statement.setString(3, action)
            statement.setString(4, memberId.toString())
            statement.setString(5, objectMapper.writeValueAsString(summary))
            statement.executeUpdate()
        }
    }

    private fun ResultSet.toMember() = ProjectMemberResponse(
        getObject("id").toString(), getString("email"), getString("role"), getTimestamp("created_at").toInstant().toString(),
    )

    private fun parseId(value: String) = runCatching { UUID.fromString(value) }.getOrNull()
    private fun error(status: Response.Status, code: String, message: String) = Response.status(status).entity(ErrorResponse(code, message)).build()

    private data class Account(val id: UUID, val role: String, val disabled: Boolean)

    companion object {
        private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        private val ROLES = setOf("ADMIN", "OPERATOR", "READ_ONLY")
    }
}
