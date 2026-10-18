package com.geekcorelab.geekmonitor.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.Statement

class DatabaseCheckServiceFailureClassificationTest {
    private val service = DatabaseCheckService()

    @Test
    fun `socket read timeout is classified as timeout`() {
        assertEquals("TIMEOUT", service.classifyDatabaseFailure(SocketTimeoutException()).code)
    }

    @Test
    fun `jdbc query timeout is classified as timeout`() {
        assertEquals("TIMEOUT", service.classifyDatabaseFailure(SQLTimeoutException()).code)
    }

    @Test
    fun `postgres query cancellation is classified as timeout`() {
        assertEquals("TIMEOUT", service.classifyDatabaseFailure(SQLException("canceling statement", "57014")).code)
    }

    @Test
    fun `authentication sql state is classified without exposing driver message`() {
        val failure = service.classifyDatabaseFailure(SQLException("password secret rejected", "28P01"))

        assertEquals("AUTHENTICATION_FAILED", failure.code)
        assertEquals("数据库认证失败", failure.summary)
    }

    @Test
    fun `missing database sql state is classified separately`() {
        assertEquals("DATABASE_NOT_FOUND", service.classifyDatabaseFailure(SQLException("missing", "3D000")).code)
    }

    @Test
    fun `connection sql state is classified separately`() {
        assertEquals("CONNECTION_FAILED", service.classifyDatabaseFailure(SQLException("connection failed", "08006")).code)
    }

    @Test
    fun `permission failure remains a generic read only query failure`() {
        val failure = service.classifyDatabaseFailure(SQLException("permission denied for pg_stat_activity", "42501"))

        assertEquals("DATABASE_QUERY_FAILED", failure.code)
        assertEquals("数据库只读探测失败", failure.summary)
    }

    @Test
    fun `postgres metrics permission failures are omitted`() {
        assertEquals(emptyMap<String, Double>(), service.readJdbcConnectionMetrics("POSTGRESQL", deniedMetricsConnection()))
    }

    @Test
    fun `mysql metrics permission failures are omitted`() {
        assertEquals(emptyMap<String, Double>(), service.readJdbcConnectionMetrics("MYSQL", deniedMetricsConnection()))
    }

    private fun deniedMetricsConnection(): Connection {
        val deniedStatement = Proxy.newProxyInstance(
            Statement::class.java.classLoader,
            arrayOf(Statement::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "executeQuery" -> throw SQLException("permission denied for connection statistics", "42501")
                "getQueryTimeout" -> 0
                "isClosed" -> false
                else -> null
            }
        } as Statement
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "createStatement" -> deniedStatement
                "isClosed" -> false
                else -> null
            }
        } as Connection
    }
}
