package com.geekcorelab.api

import com.geekcorelab.security.CredentialService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.HttpHeaders
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.UUID
import javax.sql.DataSource

data class AuthenticatedUser(val id: UUID, val email: String, val role: String)

@ApplicationScoped
class SessionAuthenticator {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var credentials: CredentialService
    @Inject @field:ConfigProperty(name = "geek-monitor.security.cookie-name", defaultValue = "geek_monitor_session")
    lateinit var cookieName: String

    fun currentUser(headers: HttpHeaders): AuthenticatedUser? {
        val token = headers.cookies[cookieName]?.value?.takeIf(String::isNotBlank)
            ?: headers.getHeaderString(HttpHeaders.AUTHORIZATION)
                ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substringAfter(' ')
                ?.takeIf(String::isNotBlank)
            ?: return null
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT u.id, u.email, u.role FROM auth_session s JOIN app_user u ON u.id = s.user_id WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > now() AND u.disabled_at IS NULL",
            ).use { statement ->
                statement.setString(1, credentials.tokenHash(token))
                statement.executeQuery().use { result ->
                    if (result.next()) AuthenticatedUser(result.getObject("id", UUID::class.java), result.getString("email"), result.getString("role")) else null
                }
            }
        }
    }

    fun canAccessProject(userId: UUID, projectId: UUID): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT 1 FROM project_membership WHERE project_id = ? AND user_id = ?").use { statement ->
            statement.setObject(1, projectId)
            statement.setObject(2, userId)
            statement.executeQuery().use { it.next() }
        }
    }

    fun canAccessResource(userId: UUID, resourceId: UUID): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT 1 FROM monitored_resource r JOIN project_membership pm ON pm.project_id = r.project_id WHERE r.id = ? AND r.deleted_at IS NULL AND pm.user_id = ?",
        ).use { statement ->
            statement.setObject(1, resourceId)
            statement.setObject(2, userId)
            statement.executeQuery().use { it.next() }
        }
    }

    fun canAccessMonitor(userId: UUID, monitorId: UUID): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT 1 FROM monitor m JOIN monitored_resource r ON r.id = m.resource_id JOIN project_membership pm ON pm.project_id = r.project_id WHERE m.id = ? AND m.deleted_at IS NULL AND m.type <> 'AGENT_OFFLINE' AND r.deleted_at IS NULL AND pm.user_id = ?",
        ).use { statement ->
            statement.setObject(1, monitorId)
            statement.setObject(2, userId)
            statement.executeQuery().use { it.next() }
        }
    }
}
