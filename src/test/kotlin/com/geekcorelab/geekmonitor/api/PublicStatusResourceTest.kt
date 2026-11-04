package com.geekcorelab.geekmonitor.api

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.qute.Template
import io.quarkus.qute.TemplateInstance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class PublicStatusResourceTest {

    @Test
    fun `public range resolution defaults to 24h and supports 30d`() {
        assertEquals(PublicRange.RANGE_24H, PublicRange.from(null))
        assertEquals(PublicRange.RANGE_24H, PublicRange.from(""))
        assertEquals(PublicRange.RANGE_24H, PublicRange.from("24h"))
        assertEquals(PublicRange.RANGE_24H, PublicRange.from("invalid"))

        assertEquals(PublicRange.RANGE_30D, PublicRange.from("30d"))
        assertEquals(PublicRange.RANGE_30D, PublicRange.from("30D"))
        assertEquals(PublicRange.RANGE_30D, PublicRange.from(" 30d "))

        assertEquals("24h", PublicRange.RANGE_24H.code)
        assertEquals(24, PublicRange.RANGE_24H.slots)
        assertEquals(3600L, PublicRange.RANGE_24H.slotSeconds)
        assertEquals("过去 24 小时 · 每格 1 小时", PublicRange.RANGE_24H.heading)

        assertEquals("30d", PublicRange.RANGE_30D.code)
        assertEquals(360, PublicRange.RANGE_30D.slots)
        assertEquals(7200L, PublicRange.RANGE_30D.slotSeconds)
        assertEquals("过去 30 天 · 每格 2 小时", PublicRange.RANGE_30D.heading)
    }

    @Test
    fun `public responses include connect-src self in Content-Security-Policy header`() {
        val resourceId = UUID.randomUUID()
        val projectId = UUID.randomUUID()
        val mockData = listOf(
            MockRow(
                id = resourceId,
                name = "核心 API 服务",
                tags = """["生产环境"]""",
                healthStatus = "UP",
                paused = false,
                type = "HTTP",
                enabled = true,
                currentState = "UP",
            ),
        )
        val resource = PublicStatusResource().apply {
            dataSource = mockDataSource(mockData)
            objectMapper = ObjectMapper()
        }
        val response = resource.data(projectId.toString(), null)
        assertEquals(200, response.status)
        val csp = response.headers.getFirst("Content-Security-Policy")?.toString()
        assertNotNull(csp)
        assertTrue(csp!!.contains("connect-src 'self'"), "CSP should permit connect-src 'self', got: $csp")
    }

    @Test
    fun `returns 404 for invalid project id`() {
        val resource = PublicStatusResource().apply {
            dataSource = mockDataSource(emptyList())
            objectMapper = ObjectMapper()
        }
        val response = resource.data("not-a-uuid", null)
        assertEquals(404, response.status)
        val htmlResponse = resource.page("not-a-uuid", null)
        assertEquals(404, htmlResponse.status)
    }

    @Test
    fun `loadStatus respects 24h default range and 30d option`() {
        val resourceId = UUID.randomUUID()
        val projectId = UUID.randomUUID()
        val mockData = listOf(
            MockRow(
                id = resourceId,
                name = "核心 API 服务",
                tags = """["生产环境"]""",
                healthStatus = "UP",
                paused = false,
                type = "HTTP",
                enabled = true,
                currentState = "UP",
            ),
        )

        val resource = PublicStatusResource().apply {
            dataSource = mockDataSource(mockData)
            objectMapper = ObjectMapper()
        }

        // Test default 24h
        val defaultSnapshot = resource.loadStatus(projectId)
        assertNotNull(defaultSnapshot)
        assertEquals(1, defaultSnapshot!!.count)
        assertEquals("24h", defaultSnapshot.range)
        assertEquals("过去 24 小时 · 每格 1 小时", defaultSnapshot.historyHeading)
        val defaultRes = defaultSnapshot.groups.single().resources.single()
        assertEquals("核心 API 服务", defaultRes.name)
        assertEquals(24, defaultRes.history.size)

        // Test explicit 30d
        val snapshot30d = resource.loadStatus(projectId, PublicRange.RANGE_30D)
        assertNotNull(snapshot30d)
        assertEquals("30d", snapshot30d!!.range)
        assertEquals("过去 30 天 · 每格 2 小时", snapshot30d.historyHeading)
        val res30d = snapshot30d.groups.single().resources.single()
        assertEquals(360, res30d.history.size)
    }

    private data class MockRow(
        val id: UUID,
        val name: String,
        val tags: String,
        val healthStatus: String,
        val paused: Boolean,
        val type: String?,
        val enabled: Boolean,
        val currentState: String?,
    )

    private fun mockDataSource(rows: List<MockRow>): DataSource {
        return Proxy.newProxyInstance(
            DataSource::class.java.classLoader,
            arrayOf(DataSource::class.java),
        ) { _, method, args ->
            if (method.name == "getConnection") {
                mockConnection(rows)
            } else null
        } as DataSource
    }

    private fun mockConnection(rows: List<MockRow>): Connection {
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "prepareStatement" -> mockPreparedStatement(args[0] as String, rows)
                "close" -> null
                else -> null
            }
        } as Connection
    }

    private fun mockPreparedStatement(sql: String, rows: List<MockRow>): PreparedStatement {
        return Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "executeQuery" -> mockResultSet(sql, rows)
                "setObject", "setTimestamp", "setString", "setInt", "setLong" -> null
                "close" -> null
                else -> null
            }
        } as PreparedStatement
    }

    private fun mockResultSet(sql: String, rows: List<MockRow>): ResultSet {
        var cursor = -1
        return Proxy.newProxyInstance(
            ResultSet::class.java.classLoader,
            arrayOf(ResultSet::class.java),
        ) { _, method, args ->
            when (method.name) {
                "next" -> {
                    if (sql.contains("FROM monitored_resource r LEFT JOIN monitor m")) {
                        cursor++
                        cursor < rows.size
                    } else {
                        // Empty rollups and maintenance windows
                        false
                    }
                }
                "getObject" -> {
                    val col = args[0] as String
                    when (col) {
                        "id" -> rows[cursor].id
                        else -> null
                    }
                }
                "getString" -> {
                    val col = args[0] as String
                    when (col) {
                        "name" -> rows[cursor].name
                        "tags" -> rows[cursor].tags
                        "health_status" -> rows[cursor].healthStatus
                        "type" -> rows[cursor].type
                        "current_state" -> rows[cursor].currentState
                        else -> null
                    }
                }
                "getBoolean" -> {
                    val col = args[0] as String
                    when (col) {
                        "paused" -> rows[cursor].paused
                        "enabled" -> rows[cursor].enabled
                        else -> false
                    }
                }
                "close" -> null
                else -> null
            }
        } as ResultSet
    }
}
