package com.geekcorelab.geekmonitor.monitor

import com.fasterxml.jackson.databind.JsonNode
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

internal class TargetConcurrencyLimiter(private val limit: Int) {
    private val activeByTarget = ConcurrentHashMap<String, Int>()

    init {
        require(limit > 0)
    }

    fun tryAcquire(target: String): Boolean {
        var acquired = false
        activeByTarget.compute(target) { _, active ->
            val current = active ?: 0
            if (current >= limit) current else {
                acquired = true
                current + 1
            }
        }
        return acquired
    }

    fun release(target: String) {
        activeByTarget.computeIfPresent(target) { _, active ->
            if (active <= 1) null else active - 1
        }
    }
}

internal object TargetConcurrencyKey {
    fun of(type: String, config: JsonNode, monitorId: String): String {
        val protocol = type.uppercase()
        val target = when (protocol) {
            "HTTP" -> httpOrigin(config.path("url").asText())
            "TCP", "TLS", "POSTGRESQL", "MYSQL", "REDIS" -> endpoint(
                config.path("host").asText(),
                config.path("port").asInt(defaultPort(protocol)),
            )
            "DNS" -> {
                val host = normalizeHost(config.path("host").asText())
                val resolver = config.path("resolver").takeUnless { it.isMissingNode || it.isNull }
                    ?.asText()?.let(::normalizeHost) ?: "system"
                host.takeIf(String::isNotBlank)?.let { "$it@$resolver" }
            }
            else -> null
        }
        return "$protocol:${target ?: "monitor:$monitorId"}"
    }

    private fun httpOrigin(value: String): String? = runCatching { URI(value) }.getOrNull()?.let { uri ->
        val host = uri.host?.let(::normalizeHost)?.takeIf(String::isNotBlank) ?: return@let null
        val scheme = uri.scheme?.lowercase()?.takeIf(String::isNotBlank) ?: return@let null
        val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        "$scheme://${formatHost(host)}:$port"
    }

    private fun endpoint(hostValue: String, port: Int): String? {
        val host = normalizeHost(hostValue)
        return host.takeIf(String::isNotBlank)?.let { "${formatHost(it)}:$port" }
    }

    private fun normalizeHost(value: String): String = value.trim().removePrefix("[").removeSuffix("]").trimEnd('.').lowercase()

    private fun formatHost(value: String): String = if (':' in value) "[$value]" else value

    private fun defaultPort(protocol: String): Int = when (protocol) {
        "TLS" -> 443
        "POSTGRESQL" -> 5432
        "MYSQL" -> 3306
        "REDIS" -> 6379
        else -> 0
    }
}
