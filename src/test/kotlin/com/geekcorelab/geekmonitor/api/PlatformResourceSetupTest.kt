package com.geekcorelab.geekmonitor.api

import com.geekcorelab.geekmonitor.security.CredentialService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource
import java.sql.Connection
import java.sql.PreparedStatement

class PlatformResourceSetupTest {
    @Test
    fun `first installation creates the requested administrator and is one time`() {
        val database = SetupDatabase()
        val credentials = CredentialService()
        val resource = PlatformResource().apply {
            dataSource = database.dataSource()
            this.credentials = credentials
            sessionDays = 30
            cookieName = "geek_monitor_session"
            secureCookie = false
        }

        val response = resource.setup(
            SetupRequest(
                email = " Admin@Biliwind.com ",
                password = "admin@biliwind.com",
                projectName = " ",
            ),
        )

        assertEquals(201, response.status)
        val session = response.entity as SessionResponse
        assertEquals("admin@biliwind.com", session.user.email)
        assertEquals("ADMIN", session.user.role)
        val sessionCookie = response.getHeaderString("Set-Cookie")
        assertTrue(sessionCookie.contains("HttpOnly"))
        assertTrue(sessionCookie.contains("SameSite=Strict"))
        assertFalse(sessionCookie.contains("Secure"))
        assertEquals(1, database.commitCount)

        val adminInsert = database.statements.single { it.sql.startsWith("INSERT INTO app_user") }
        assertEquals("admin@biliwind.com", adminInsert.parameters[2])
        val passwordHash = adminInsert.parameters[3] as String
        assertTrue(passwordHash.startsWith("pbkdf2-sha256$310000$"))
        assertTrue(credentials.verifyPassword("admin@biliwind.com", passwordHash))
        assertFalse(passwordHash == "admin@biliwind.com")

        val projectInsert = database.statements.single { it.sql.startsWith("INSERT INTO project(") }
        assertEquals("默认项目", projectInsert.parameters[2])
        assertTrue(database.statements.any { it.sql.contains("INSERT INTO project_membership") })
        assertTrue(database.statements.any { it.sql.contains("installation.completed") })

        val repeated = resource.setup(
            SetupRequest("admin@biliwind.com", "admin@biliwind.com", "默认项目"),
        )

        assertEquals(409, repeated.status)
        assertEquals("ALREADY_INITIALIZED", (repeated.entity as ErrorResponse).code)
        assertEquals(1, database.statements.count { it.sql.startsWith("INSERT INTO app_user") })
        assertEquals(1, database.rollbackCount)
    }

    private data class ExecutedStatement(val sql: String, val parameters: Map<Int, Any?>)

    private inner class SetupDatabase {
        val statements = CopyOnWriteArrayList<ExecutedStatement>()
        var commitCount = 0
        var rollbackCount = 0
        private var initialized = false

        fun dataSource(): DataSource = proxy(DataSource::class.java) { method, _ ->
            when (method.name) {
                "getConnection" -> connection()
                else -> defaultValue(method.returnType)
            }
        }

        private fun connection(): Connection = proxy(Connection::class.java) { method, arguments ->
            when (method.name) {
                "prepareStatement" -> preparedStatement(arguments!![0] as String)
                "commit" -> {
                    commitCount++
                    null
                }
                "rollback" -> {
                    rollbackCount++
                    null
                }
                else -> defaultValue(method.returnType)
            }
        }

        private fun preparedStatement(sql: String): PreparedStatement {
            val parameters = mutableMapOf<Int, Any?>()
            return proxy(PreparedStatement::class.java) { method, arguments ->
                when (method.name) {
                    "setString", "setObject", "setBoolean", "setInt", "setTimestamp" -> {
                        parameters[arguments!![0] as Int] = arguments[1]
                        null
                    }
                    "executeUpdate" -> {
                        if (sql.startsWith("UPDATE installation_state")) {
                            if (initialized) 0 else {
                                initialized = true
                                1
                            }
                        } else {
                            statements += ExecutedStatement(sql, parameters.toMap())
                            1
                        }
                    }
                    else -> defaultValue(method.returnType)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(
        type: Class<T>,
        handler: (Method, Array<out Any?>?) -> Any?,
    ): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
        handler(method, args)
    } as T

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
