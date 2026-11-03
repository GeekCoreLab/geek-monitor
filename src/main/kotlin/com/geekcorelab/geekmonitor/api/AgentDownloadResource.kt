package com.geekcorelab.geekmonitor.api

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.io.File
import java.security.MessageDigest

@Path("/api/v1/agent-downloads")
@ApplicationScoped
class AgentDownloadResource {

    @ConfigProperty(name = "geek-monitor.agent.binary-dir", defaultValue = "dist")
    lateinit var binaryDir: String

    companion object {
        private val ALLOWED_FILES = setOf(
            "install-agent.sh",
            "geek-monitor-agent-linux-amd64",
            "geek-monitor-agent-linux-arm64",
            "SHA256SUMS",
        )
    }

    @GET
    @Path("/{filename}")
    fun download(@PathParam("filename") filename: String): Response {
        if (filename !in ALLOWED_FILES) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to "NOT_FOUND", "message" to "文件未找到"))
                .type(MediaType.APPLICATION_JSON)
                .build()
        }

        if (filename == "install-agent.sh") {
            val scriptFile = findFile("install-agent.sh", listOf(binaryDir, "dist", "scripts"))
            if (scriptFile != null && scriptFile.exists() && scriptFile.isFile) {
                return Response.ok(scriptFile)
                    .type("text/x-shellscript; charset=utf-8")
                    .header("Content-Disposition", "attachment; filename=\"install-agent.sh\"")
                    .build()
            }
            val resourceStream = Thread.currentThread().contextClassLoader.getResourceAsStream("scripts/install-agent.sh")
            if (resourceStream != null) {
                return Response.ok(resourceStream)
                    .type("text/x-shellscript; charset=utf-8")
                    .header("Content-Disposition", "attachment; filename=\"install-agent.sh\"")
                    .build()
            }
            return Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to "FILE_NOT_FOUND", "message" to "未找到 install-agent.sh"))
                .type(MediaType.APPLICATION_JSON)
                .build()
        }

        if (filename == "SHA256SUMS") {
            val sumsFile = findFile("SHA256SUMS", listOf(binaryDir, "dist"))
            if (sumsFile != null && sumsFile.exists() && sumsFile.isFile) {
                return Response.ok(sumsFile)
                    .type("text/plain; charset=utf-8")
                    .header("Content-Disposition", "attachment; filename=\"SHA256SUMS\"")
                    .build()
            }
            val content = generateSha256Sums()
            return Response.ok(content)
                .type("text/plain; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"SHA256SUMS\"")
                .build()
        }

        val binaryFile = findFile(filename, listOf(binaryDir, "dist", "agent"))
        if (binaryFile != null && binaryFile.exists() && binaryFile.isFile) {
            return Response.ok(binaryFile)
                .type(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"$filename\"")
                .build()
        }

        return Response.status(Response.Status.NOT_FOUND)
            .entity(
                mapOf(
                    "error" to "AGENT_BINARY_NOT_FOUND",
                    "message" to "未在服务器上找到 $filename 二进制文件。请先构建 Agent 二进制或放置在 dist/ 目录下，或使用 GitHub Releases 下载源。",
                )
            )
            .type(MediaType.APPLICATION_JSON)
            .build()
    }

    private fun findFile(filename: String, dirs: List<String>): File? {
        for (dir in dirs) {
            val file = File(dir, filename)
            if (file.exists() && file.isFile) {
                return file
            }
        }
        return null
    }

    private fun generateSha256Sums(): String {
        val lines = mutableListOf<String>()
        val targets = listOf("geek-monitor-agent-linux-amd64", "geek-monitor-agent-linux-arm64", "install-agent.sh")
        val dirs = listOf(binaryDir, "dist", "scripts", "agent")
        for (target in targets) {
            val file = findFile(target, dirs)
            if (file != null && file.exists() && file.isFile) {
                val hash = sha256Hex(file)
                lines.add("$hash  $target")
            }
        }
        return lines.joinToString("\n") + if (lines.isNotEmpty()) "\n" else ""
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
