package com.geekcorelab.geekmonitor.api

import java.net.URI
import java.util.Locale

data class ProjectCredentialRequest(
    val name: String?,
    val headers: Map<String, String>? = null,
    val allowedOrigins: List<String>? = null,
    val version: Int? = null,
)

data class ProjectCredentialResponse(
    val id: String,
    val projectId: String,
    val name: String,
    val headerNames: List<String>,
    val allowedOrigins: List<String>,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
)

internal object ProjectCredentialOrigins {
    fun fromUrl(value: String?): String? {
        val uri = runCatching { URI(value?.trim().orEmpty()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank) ?: return null
        if (scheme !in setOf("http", "https") || uri.userInfo != null ||
            uri.port != -1 && uri.port !in 1..65535) return null
        val port = uri.port.takeUnless { it == -1 || (scheme == "http" && it == 80) || (scheme == "https" && it == 443) }
        val authorityHost = if (':' in host && !host.startsWith("[")) "[$host]" else host
        return "$scheme://$authorityHost${port?.let { ":$it" } ?: ""}"
    }

    fun normalizeAllowed(values: List<String>?): List<String>? {
        if (values == null || values.isEmpty() || values.size > 32) return null
        val normalized = linkedSetOf<String>()
        for (value in values) {
            val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
            if (uri.path !in setOf("", "/") || uri.query != null || uri.fragment != null) return null
            val origin = fromUrl(value) ?: return null
            normalized += origin
        }
        return normalized.toList().takeIf { it.isNotEmpty() }
    }
}
