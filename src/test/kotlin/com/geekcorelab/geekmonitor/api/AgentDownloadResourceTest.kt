package com.geekcorelab.geekmonitor.api

import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class AgentDownloadResourceTest {

    @Test
    fun `rejects unauthorized and non-whitelisted file names`() {
        val resource = AgentDownloadResource().apply { binaryDir = "dist" }
        val response = resource.download("../secret.txt")
        assertEquals(Response.Status.NOT_FOUND.statusCode, response.status)

        val response2 = resource.download("malicious.sh")
        assertEquals(Response.Status.NOT_FOUND.statusCode, response.status)
    }

    @Test
    fun `serves install-agent script if present`() {
        val resource = AgentDownloadResource().apply { binaryDir = "dist" }
        val response = resource.download("install-agent.sh")
        assertEquals(Response.Status.OK.statusCode, response.status)
        assertNotNull(response.entity)
        assertTrue(response.mediaType.toString().contains("shellscript"))
    }

    @Test
    fun `serves binary when file exists in directory`(@TempDir tempDir: Path) {
        val resource = AgentDownloadResource().apply { binaryDir = tempDir.toString() }
        val fakeBinary = tempDir.resolve("geek-monitor-agent-linux-amd64").toFile()
        fakeBinary.writeBytes("ELF-dummy-binary".toByteArray())

        val response = resource.download("geek-monitor-agent-linux-amd64")
        assertEquals(Response.Status.OK.statusCode, response.status)
        assertEquals(fakeBinary, response.entity)

        val sumsResponse = resource.download("SHA256SUMS")
        assertEquals(Response.Status.OK.statusCode, sumsResponse.status)
        val sumsContent = sumsResponse.entity as String
        assertTrue(sumsContent.contains("geek-monitor-agent-linux-amd64"))
    }

    @Test
    fun `returns 404 with helpful message when binary is not present`(@TempDir tempDir: Path) {
        val resource = AgentDownloadResource().apply { binaryDir = tempDir.toString() }
        val response = resource.download("geek-monitor-agent-linux-arm64")
        assertEquals(Response.Status.NOT_FOUND.statusCode, response.status)
        val entity = response.entity as? Map<*, *>
        assertNotNull(entity)
        assertEquals("AGENT_BINARY_NOT_FOUND", entity?.get("error"))
    }
}
