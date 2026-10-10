package com.geekcorelab.monitor

import com.fasterxml.jackson.databind.JsonNode
import com.geekcorelab.api.CheckResponse
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.security.cert.X509Certificate
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.Type

@ApplicationScoped
class ProtocolCheckService {
    @Inject lateinit var httpChecker: HttpCheckService
    @Inject lateinit var networkChecker: NetworkCheckService
    @Inject lateinit var dataSource: DataSource

    fun execute(type: String, config: JsonNode, timeoutSeconds: Int): CheckResponse = when (type.uppercase()) {
        "HTTP" -> httpChecker.execute(
            config.path("url").asText(),
            config.path("method").asText("GET"),
            timeoutSeconds,
            config.path("expectedStatusCodes").map { it.asInt() }.toSet().ifEmpty { (200..299).toSet() },
            config.path("textContains").takeUnless { it.isMissingNode || it.isNull }?.asText(),
        )
        "TCP" -> networkChecker.tcp(config.path("host").asText(), config.path("port").asInt(), timeoutSeconds)
        "DNS" -> networkChecker.dns(
            config.path("host").asText(),
            config.path("recordType").asText("A"),
            config.path("resolver").takeUnless { it.isMissingNode || it.isNull }?.asText(),
            config.path("expectedValue").takeUnless { it.isMissingNode || it.isNull }?.asText(),
            timeoutSeconds,
        )
        "TLS" -> networkChecker.tls(
            config.path("host").asText(),
            config.path("port").asInt(443),
            config.path("minimumValidityDays").asInt(14),
            timeoutSeconds,
        )
        "HEARTBEAT" -> heartbeat(
            config.path("monitorId").asText(),
            config.path("expectedIntervalSeconds").asInt(),
            config.path("graceSeconds").asInt(60),
        )
        else -> CheckResponse("UNKNOWN", null, "UNSUPPORTED_CHECK", "不支持此监控类型", 0, Instant.now().toString())
    }

    private fun heartbeat(monitorIdText: String, intervalSeconds: Int, graceSeconds: Int): CheckResponse {
        val started = System.nanoTime()
        val checkedAt = Instant.now()
        val monitorId = runCatching { UUID.fromString(monitorIdText) }.getOrNull()
            ?: return CheckResponse("UNKNOWN", null, "CHECK_CONFIG_INVALID", "心跳任务配置无效", 0, checkedAt.toString())
        if (intervalSeconds !in 10..86400 || graceSeconds !in 0..86400) {
            return CheckResponse("UNKNOWN", null, "CHECK_CONFIG_INVALID", "心跳任务间隔配置无效", 0, checkedAt.toString())
        }
        return try {
            val state = dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT created_at, last_run_at, last_status, last_summary FROM heartbeat_task WHERE monitor_id = ?",
                ).use { statement ->
                    statement.setObject(1, monitorId)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) null else HeartbeatState(
                            createdAt = rows.getTimestamp("created_at").toInstant(),
                            lastRunAt = rows.getTimestamp("last_run_at")?.toInstant(),
                            lastStatus = rows.getString("last_status"),
                            lastSummary = rows.getString("last_summary"),
                        )
                    }
                }
            } ?: return CheckResponse("UNKNOWN", null, "HEARTBEAT_TASK_MISSING", "心跳任务配置不存在", elapsed(started), checkedAt.toString())

            val latestRunAt = state.lastRunAt
            if (latestRunAt == null) {
                val elapsedSinceCreated = Duration.between(state.createdAt, checkedAt).seconds.coerceAtLeast(0)
                if (elapsedSinceCreated <= intervalSeconds + graceSeconds) {
                    return CheckResponse("UNKNOWN", null, "HEARTBEAT_WAITING", "等待任务首次上报", elapsed(started), checkedAt.toString())
                }
                return CheckResponse("DOWN", null, "HEARTBEAT_MISSED", "任务超过预期间隔与宽限期仍未上报", elapsed(started), checkedAt.toString())
            }
            if (state.lastStatus == "FAILURE") {
                val summary = state.lastSummary?.takeIf(String::isNotBlank)?.let { "任务上报执行失败：$it" } ?: "任务上报执行失败"
                return CheckResponse("DOWN", null, "HEARTBEAT_REPORTED_FAILURE", summary, elapsed(started), checkedAt.toString())
            }
            val ageSeconds = Duration.between(latestRunAt, checkedAt).seconds.coerceAtLeast(0)
            if (ageSeconds > intervalSeconds + graceSeconds) {
                CheckResponse("DOWN", null, "HEARTBEAT_MISSED", "任务最后运行于 ${ageSeconds} 秒前，已超过预期间隔与宽限期", elapsed(started), checkedAt.toString())
            } else {
                CheckResponse("UP", null, null, "任务最近一次成功运行于 ${ageSeconds} 秒前", elapsed(started), checkedAt.toString())
            }
        } catch (_: Exception) {
            CheckResponse("UNKNOWN", null, "HEARTBEAT_STATE_UNAVAILABLE", "无法读取心跳任务状态", elapsed(started), checkedAt.toString())
        }
    }

    private fun elapsed(started: Long) = Duration.ofNanos(System.nanoTime() - started).toMillis()

    private data class HeartbeatState(
        val createdAt: Instant,
        val lastRunAt: Instant?,
        val lastStatus: String?,
        val lastSummary: String?,
    )
}

@ApplicationScoped
class NetworkCheckService {
    @Inject lateinit var addressPolicy: HttpCheckService

    fun tcp(host: String, port: Int, timeoutSeconds: Int): CheckResponse {
        val started = System.nanoTime()
        val checkedAt = Instant.now()
        if (!validHost(host) || port !in 1..65535) return result("UNKNOWN", "TARGET_INVALID", "TCP 目标地址或端口无效", started, checkedAt)
        val deadline = started + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        val resolution = addressPolicy.resolveAllowedTarget(host)
        if (resolution.errorCode != "TARGET_BLOCKED" && remainingMillis(deadline) == null) {
            return result("DOWN", "TIMEOUT", "TCP 检查超过总超时时间", started, checkedAt)
        }
        if (resolution.errorCode != null) return resolutionFailure(resolution.errorCode, started, checkedAt)
        var lastCode = "CONNECTION_REFUSED"
        for (address in resolution.addresses) {
            val remaining = remainingMillis(deadline) ?: return result("DOWN", "TIMEOUT", "TCP 检查超过总超时时间", started, checkedAt)
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), remaining)
                }
                return result("UP", null, null, started, checkedAt)
            } catch (_: SocketTimeoutException) {
                lastCode = "TIMEOUT"
            } catch (_: ConnectException) {
                lastCode = "CONNECTION_REFUSED"
            } catch (_: Exception) {
                lastCode = "NETWORK_FAILURE"
            }
        }
        val summary = when (lastCode) {
            "TIMEOUT" -> "TCP 建连超过配置的超时时间"
            "CONNECTION_REFUSED" -> "TCP 目标拒绝连接"
            else -> "无法连接 TCP 目标"
        }
        return result("DOWN", lastCode, summary, started, checkedAt)
    }

    fun dns(host: String, recordType: String, resolver: String?, expectedValue: String?, timeoutSeconds: Int): CheckResponse {
        val started = System.nanoTime()
        val checkedAt = Instant.now()
        val type = recordType.uppercase()
        if (type !in DNS_RECORD_TYPES) return result("UNKNOWN", "CHECK_CONFIG_INVALID", "DNS 记录类型无效", started, checkedAt)
        val normalizedHost = normalizeDnsName(host, type) ?: return result("UNKNOWN", "TARGET_INVALID", "DNS 查询名称无效", started, checkedAt)
        val customResolver = if (resolver.isNullOrBlank()) null else {
            if (!isIpLiteral(resolver)) return result("UNKNOWN", "RESOLVER_INVALID", "自定义 DNS 解析器必须填写 IP 地址", started, checkedAt)
            val resolution = addressPolicy.resolveAllowedTarget(resolver)
            if (resolution.errorCode != null) return resolutionFailure(resolution.errorCode, started, checkedAt)
            resolution.addresses.firstOrNull()
                ?: return result("DOWN", "DNS_FAILURE", "DNS 解析器无法解析", started, checkedAt)
        }
        return try {
            val dnsType = Type.value(type)
            val queryName = Name.fromString("$normalizedHost.")
            val queryRecord = Record.newRecord(queryName, dnsType, DClass.IN)
            val query = Message.newQuery(queryRecord)
            val dnsResolver = if (customResolver == null) SimpleResolver() else SimpleResolver(customResolver)
            dnsResolver.setTimeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            val response = dnsResolver.send(query)
            val records = response.getSection(Section.ANSWER).filter { it.type == dnsType }
            if (response.header.rcode == Rcode.NXDOMAIN || (response.header.rcode == Rcode.NOERROR && records.isEmpty())) {
                return result("DOWN", "DNS_NO_RECORD", "没有找到 $type 记录", started, checkedAt)
            }
            if (response.header.rcode != Rcode.NOERROR) {
                return result("DOWN", "DNS_FAILURE", "DNS 解析器返回 ${Rcode.string(response.header.rcode)}", started, checkedAt)
            }
            val values = records.map { it.rdataToString().trim().trim('"') }
            when {
                expectedValue != null && values.none { canonicalRecord(it, type) == canonicalRecord(expectedValue.trim(), type) } ->
                    CheckResponse("DOWN", null, "DNS_ASSERTION_FAILED", "$type 记录与期望值不符", elapsedMillis(started), checkedAt.toString())
                else -> CheckResponse("UP", null, null, "$type: ${values.take(8).joinToString(", ")}", elapsedMillis(started), checkedAt.toString())
            }
        } catch (exception: Exception) {
            if (rootCause(exception) is SocketTimeoutException || rootCause(exception) is TimeoutException) {
                result("DOWN", "TIMEOUT", "DNS 查询超时", started, checkedAt)
            } else {
                result("DOWN", "DNS_FAILURE", "DNS 解析器查询失败", started, checkedAt)
            }
        }
    }

    fun tls(host: String, port: Int, minimumValidityDays: Int, timeoutSeconds: Int): CheckResponse {
        val started = System.nanoTime()
        val checkedAt = Instant.now()
        if (!validHost(host) || port !in 1..65535 || minimumValidityDays !in 0..3650) {
            return result("UNKNOWN", "TARGET_INVALID", "TLS 目标地址、端口或证书有效期阈值无效", started, checkedAt)
        }
        val deadline = started + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        val resolution = addressPolicy.resolveAllowedTarget(host)
        if (resolution.errorCode != "TARGET_BLOCKED" && remainingMillis(deadline) == null) {
            return result("DOWN", "TIMEOUT", "TLS 检查超过总超时时间", started, checkedAt)
        }
        if (resolution.errorCode != null) return resolutionFailure(resolution.errorCode, started, checkedAt)
        var lastCode = "TLS_FAILURE"
        for (address in resolution.addresses) {
            if (remainingMillis(deadline) == null) return result("DOWN", "TIMEOUT", "TLS 检查超过总超时时间", started, checkedAt)
            try {
                val certificate = tlsHandshake(address, host, port, deadline)
                val certificateExpiresAt = certificate.notAfter.toInstant()
                val daysLeft = Duration.between(Instant.now(), certificateExpiresAt).toDays()
                if (daysLeft < minimumValidityDays) {
                    return CheckResponse(
                        "DOWN", null, "TLS_EXPIRING", "TLS 证书将在 $daysLeft 天后到期",
                        elapsedMillis(started), checkedAt.toString(), certificateExpiresAt.toString(), daysLeft,
                    )
                }
                return CheckResponse(
                    "UP", null, null, "TLS 证书有效，剩余 $daysLeft 天",
                    elapsedMillis(started), checkedAt.toString(), certificateExpiresAt.toString(), daysLeft,
                )
            } catch (_: SocketTimeoutException) {
                lastCode = "TIMEOUT"
            } catch (_: SSLHandshakeException) {
                lastCode = "TLS_FAILURE"
            } catch (_: Exception) {
                lastCode = "NETWORK_FAILURE"
            }
        }
        val summary = when (lastCode) {
            "TIMEOUT" -> "TLS 握手超过配置的超时时间"
            "TLS_FAILURE" -> "TLS 证书信任或主机名校验失败"
            else -> "无法连接 TLS 目标"
        }
        return result("DOWN", lastCode, summary, started, checkedAt)
    }

    private fun tlsHandshake(address: InetAddress, host: String, port: Int, deadlineNanos: Long): X509Certificate {
        val underlying = Socket()
        try {
            val connectTimeout = remainingMillis(deadlineNanos) ?: throw SocketTimeoutException("TLS connect timed out")
            underlying.connect(InetSocketAddress(address, port), connectTimeout)
            val peerHost = host.trim().removeSurrounding("[", "]").removeSuffix(".").let {
                if (isIpLiteral(it)) it else java.net.IDN.toASCII(it)
            }
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(underlying, peerHost, port, true) as SSLSocket
            ssl.use {
                ssl.soTimeout = remainingMillis(deadlineNanos) ?: throw SocketTimeoutException("TLS handshake timed out")
                val parameters: SSLParameters = ssl.sslParameters
                parameters.endpointIdentificationAlgorithm = "HTTPS"
                if (!isIpLiteral(peerHost)) parameters.serverNames = listOf(SNIHostName(peerHost))
                ssl.sslParameters = parameters
                ssl.startHandshake()
                return ssl.session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: throw SSLHandshakeException("peer did not provide an X.509 certificate")
            }
        } catch (exception: Exception) {
            runCatching { underlying.close() }
            throw exception
        }
    }

    private fun normalizeDnsName(host: String, recordType: String): String? = runCatching {
        val value = host.trim().removeSuffix(".")
        require(value.isNotBlank() && value.length <= 253 && '/' !in value && '@' !in value)
        if (recordType == "PTR" && isIpLiteral(value)) {
            val address = InetAddress.getByName(value.trim('[', ']'))
            val bytes = address.address
            return if (address is java.net.Inet4Address) {
                bytes.reversed().joinToString(".") { (it.toInt() and 0xff).toString() } + ".in-addr.arpa"
            } else {
                bytes.joinToString("") { byte ->
                    val octet = byte.toInt() and 0xff
                    "${"%x".format(octet and 0x0f)}.${"%x".format(octet ushr 4)}"
                } + ".ip6.arpa"
            }
        }
        val asciiLabels = value.split('.').map { label ->
            val ascii = if (label.startsWith("_")) {
                require(label.length in 2..63 && label.drop(1).matches(Regex("[A-Za-z0-9_-]+")))
                label
            } else {
                java.net.IDN.toASCII(label, java.net.IDN.USE_STD3_ASCII_RULES)
            }
            require(ascii.isNotBlank() && ascii.length <= 63)
            ascii
        }
        val ascii = asciiLabels.joinToString(".")
        require(ascii.length <= 253)
        ascii
    }.getOrNull()

    private fun canonicalRecord(value: String, type: String) = when (type) {
        "A" -> {
            val octets = value.split('.').map(String::toIntOrNull)
            if (octets.size == 4 && octets.all { it != null && it in 0..255 }) {
                octets.joinToString(".") { it.toString() }
            } else value.trim().lowercase()
        }
        "AAAA" -> if (':' in value && value.matches(Regex("^[0-9a-fA-F:.]+$"))) {
            runCatching { InetAddress.getByName(value).hostAddress.substringBefore('%').lowercase() }
                .getOrDefault(value.trim().lowercase())
        } else value.trim().lowercase()
        "CNAME", "NS", "PTR" -> value.trim().trimEnd('.').lowercase()
        "TXT" -> value.trim().trim('"')
        else -> value.trim().lowercase()
    }

    private fun validHost(host: String): Boolean = host.isNotBlank() && host.length <= 253 &&
        !host.contains('/') && !host.contains('@') && !host.any(Char::isWhitespace)

    private fun isIpLiteral(host: String): Boolean {
        val unwrapped = host.trim('[', ']')
        return if (':' in unwrapped) {
            unwrapped.matches(Regex("^[0-9a-fA-F:.]+$")) && runCatching { InetAddress.getByName(unwrapped) }.isSuccess
        } else {
            val octets = unwrapped.split('.')
            octets.size == 4 && octets.all {
                it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull()?.let { octet -> octet in 0..255 } == true
            }
        }
    }

    private fun remainingMillis(deadline: Long): Int? {
        val nanos = deadline - System.nanoTime()
        if (nanos <= 0) return null
        return TimeUnit.NANOSECONDS.toMillis(nanos).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun resolutionFailure(code: String, started: Long, checkedAt: Instant) = if (code == "TARGET_BLOCKED") {
        result("UNKNOWN", code, "目标解析到未授权的网络地址", started, checkedAt)
    } else {
        result("DOWN", code, "目标域名无法解析", started, checkedAt)
    }

    private fun rootCause(exception: Throwable): Throwable {
        var current = exception
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }

    private fun elapsedMillis(started: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    private fun result(status: String, code: String?, summary: String?, started: Long, checkedAt: Instant) =
        CheckResponse(status, null, code, summary, elapsedMillis(started), checkedAt.toString())

    companion object {
        private val DNS_RECORD_TYPES = setOf("A", "AAAA", "CNAME", "MX", "TXT", "NS", "SRV", "PTR")
    }
}
