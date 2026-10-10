package com.geekcorelab.monitor

import com.geekcorelab.api.CheckResponse
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import kotlin.math.max

data class WebhookResult(val delivered: Boolean, val statusCode: Int?, val errorCode: String?, val summary: String?)

@ApplicationScoped
class HttpCheckService {
    @Inject @field:ConfigProperty(name = "geek-monitor.network.allow-private-targets", defaultValue = "false")
    var allowPrivateTargets: Boolean = false

    @Inject @field:ConfigProperty(name = "geek-monitor.network.allowed-cidrs", defaultValue = "none")
    lateinit var allowedCidrsConfig: String

    fun sendWebhook(targetUrl: String, bearerToken: String?, payload: String, idempotencyKey: String): WebhookResult {
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_WEBHOOK_BYTES) {
            return WebhookResult(false, null, "PAYLOAD_TOO_LARGE", "通知内容超过限制")
        }
        val allowedNetworks = parseAllowedNetworks()
        val uri = try {
            validateUri(URI(targetUrl.trim())).also {
                require(it.scheme.equals("https", true) || (allowPrivateTargets && it.scheme.equals("http", true)))
            }
        } catch (_: Exception) {
            return WebhookResult(false, null, "TARGET_INVALID", "Webhook 必须使用有效的 HTTPS 地址")
        }
        if (hasBlockedIpLiteral(uri.host, allowedNetworks)) {
            return WebhookResult(false, null, "TARGET_BLOCKED", "Webhook 目标地址不在允许范围内")
        }
        val dns = Dns { hostname -> resolveAndValidate(hostname, uri.host, allowedNetworks) }
        val client = OkHttpClient.Builder()
            .dns(dns)
            .connectTimeout(WEBHOOK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(WEBHOOK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WEBHOOK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(WEBHOOK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(uri.toASCIIString())
            .post(body)
            .header("User-Agent", "GeekMonitor/1.0")
            .header("Accept", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .apply { bearerToken?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") } }
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) WebhookResult(true, response.code, null, null)
                else WebhookResult(false, response.code, "HTTP_STATUS", "Webhook 返回 HTTP ${response.code}")
            }
        } catch (exception: Exception) {
            if (exception is UnknownHostException && exception.message.orEmpty().contains("network policy")) {
                WebhookResult(false, null, "TARGET_BLOCKED", "Webhook 目标地址不在允许范围内")
            } else {
                val code = when (exception) {
                    is UnknownHostException -> "DNS_FAILURE"
                    is java.net.SocketTimeoutException -> "TIMEOUT"
                    is SSLHandshakeException -> "TLS_FAILURE"
                    else -> "NETWORK_FAILURE"
                }
                WebhookResult(false, null, code, "Webhook 投递失败")
            }
        }
    }

    fun execute(
        targetUrl: String,
        method: String,
        timeoutSeconds: Int,
        expectedStatusCodes: Set<Int>,
        textContains: String?,
    ): CheckResponse {
        val startedNanos = System.nanoTime()
        val checkedAt = Instant.now()
        val deadlineNanos = startedNanos + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        val allowedNetworks = parseAllowedNetworks()
        var uri = try {
            validateUri(URI(targetUrl.trim()))
        } catch (exception: Exception) {
            return unknown(startedNanos, checkedAt, "TARGET_INVALID", "目标地址格式无效")
        }
        if (hasBlockedIpLiteral(uri.host, allowedNetworks)) {
            return unknown(startedNanos, checkedAt, "TARGET_BLOCKED", "目标地址解析到未授权的地址")
        }
        val visited = mutableSetOf<String>()

        for (redirectCount in 0..MAX_REDIRECTS) {
            if (!visited.add(uri.toASCIIString())) {
                return failure(startedNanos, checkedAt, "REDIRECT_LOOP", "检测到重定向循环")
            }
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) return failure(startedNanos, checkedAt, "TIMEOUT", "检查超过配置的总超时时间")
            val remainingMillis = max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))
            val dns = Dns { hostname -> resolveAndValidate(hostname, uri.host, allowedNetworks) }
            val client = OkHttpClient.Builder()
                .dns(dns)
                .connectTimeout(remainingMillis, TimeUnit.MILLISECONDS)
                .readTimeout(remainingMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(remainingMillis, TimeUnit.MILLISECONDS)
                .callTimeout(remainingMillis, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
            val request = try {
                Request.Builder()
                    .url(uri.toASCIIString())
                    .method(method, null)
                    .header("User-Agent", "GeekMonitor/1.0")
                    .header("Accept", "*/*")
                    .build()
            } catch (exception: Exception) {
                return unknown(startedNanos, checkedAt, "TARGET_INVALID", "目标地址格式无效")
            }

            try {
                client.newCall(request).execute().use { response ->
                    if (response.code in 300..399 && response.header("Location") != null) {
                        if (redirectCount == MAX_REDIRECTS) {
                            return failure(startedNanos, checkedAt, "TOO_MANY_REDIRECTS", "重定向次数超过 5 次", response.code)
                        }
                        val redirected = try {
                            validateUri(uri.resolve(response.header("Location")!!))
                        } catch (exception: Exception) {
                            return unknown(startedNanos, checkedAt, "REDIRECT_TARGET_REJECTED", "重定向目标地址无效", response.code)
                        }
                        if (hasBlockedIpLiteral(redirected.host, allowedNetworks)) {
                            return unknown(startedNanos, checkedAt, "REDIRECT_TARGET_REJECTED", "重定向目标地址解析到未授权的地址", response.code)
                        }
                        if (uri.scheme == "https" && redirected.scheme != "https") {
                            return unknown(startedNanos, checkedAt, "REDIRECT_DOWNGRADE", "HTTPS 检查不允许重定向到 HTTP", response.code)
                        }
                        uri = redirected
                        return@use
                    }

                    val bodyContainsText = if (textContains == null) true else {
                        val bytes = response.body.byteStream().use { it.readNBytes(MAX_RESPONSE_BYTES + 1) }
                        String(bytes.copyOf(minOf(bytes.size, MAX_RESPONSE_BYTES)), Charsets.UTF_8).contains(textContains)
                    }
                    val statusMatches = response.code in expectedStatusCodes
                    if (!statusMatches) {
                        return CheckResponse("DOWN", response.code, "HTTP_STATUS", "HTTP 状态码 ${response.code} 不符合期望", elapsedMillis(startedNanos), checkedAt.toString())
                    }
                    if (!bodyContainsText) {
                        return CheckResponse("DOWN", response.code, "ASSERTION_FAILED", "响应内容未包含配置的文本", elapsedMillis(startedNanos), checkedAt.toString())
                    }
                    return CheckResponse("UP", response.code, null, null, elapsedMillis(startedNanos), checkedAt.toString())
                }
            } catch (exception: Exception) {
                if (exception is UnknownHostException && exception.message.orEmpty().contains("network policy")) {
                    return unknown(startedNanos, checkedAt, "TARGET_BLOCKED", "目标解析到未授权的地址")
                }
                val (code, summary) = when (exception) {
                    is UnknownHostException -> "DNS_FAILURE" to "目标域名无法解析"
                    is java.net.SocketTimeoutException -> "TIMEOUT" to "连接或读取超时"
                    is SSLHandshakeException -> "TLS_FAILURE" to "TLS 证书或握手校验失败"
                    else -> "NETWORK_FAILURE" to "无法连接目标服务"
                }
                return failure(startedNanos, checkedAt, code, summary)
            }
        }
        return failure(startedNanos, checkedAt, "TOO_MANY_REDIRECTS", "重定向次数超过 5 次")
    }

    private fun validateUri(uri: URI): URI {
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true))
        require(!uri.host.isNullOrBlank())
        require(uri.userInfo == null && uri.fragment == null)
        require(uri.port == -1 || uri.port in 1..65535)
        return uri.normalize()
    }

    private fun hasBlockedIpLiteral(host: String, allowedNetworks: List<IpNetwork>): Boolean {
        val unwrapped = host.removeSurrounding("[", "]")
        val isIpLiteral = ':' in unwrapped || unwrapped.all { it.isDigit() || it == '.' }
        if (!isIpLiteral) return false
        return runCatching { InetAddress.getAllByName(unwrapped).any { !addressAllowed(it, allowedNetworks) } }
            .getOrDefault(true)
    }

    private fun resolveAndValidate(hostname: String, targetHost: String, allowedNetworks: List<IpNetwork>): List<InetAddress> {
        if (!hostname.equals(targetHost, ignoreCase = true)) throw UnknownHostException("request attempted DNS lookup for an unexpected host")
        val addresses = InetAddress.getAllByName(hostname).toList()
        if (addresses.isEmpty()) throw UnknownHostException("host returned no addresses")
        if (addresses.any { !addressAllowed(it, allowedNetworks) }) {
            throw UnknownHostException("address rejected by network policy")
        }
        return addresses
    }

    private fun addressAllowed(address: InetAddress, allowedNetworks: List<IpNetwork>): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isMulticastAddress) return false
        if (isAlwaysRestricted(address)) return false
        if (!isPrivateAddress(address)) return true
        return allowPrivateTargets || allowedNetworks.any { it.contains(address) }
    }

    private fun isAlwaysRestricted(address: InetAddress): Boolean {
        val bytes = address.address
        if (address is Inet4Address && bytes.size == 4) {
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            val c = bytes[2].toInt() and 0xff
            return a == 0 || a == 127 || (a == 169 && b == 254) ||
                (a == 100 && b in 64..127) ||
                (a == 192 && b == 0 && c == 0) || (a == 192 && b == 0 && c == 2) ||
                (a == 192 && b == 88 && c == 99) ||
                (a == 198 && b in 18..19) ||
                (a == 198 && b == 51 && c == 100) ||
                (a == 203 && b == 0 && c == 113) || a >= 224
        }
        if (address is Inet6Address && bytes.size == 16) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            return (first == 0xfe && second and 0xc0 == 0x80) ||
                (first == 0xff) ||
                (first == 0xfc || first == 0xfd) ||
                (first == 0xfe && second and 0xc0 == 0xc0)
        }
        return false
    }

    private fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isSiteLocalAddress) return true
        val bytes = address.address
        if (address is Inet4Address && bytes.size == 4) {
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
        }
        return address is Inet6Address && bytes.isNotEmpty() && (bytes[0].toInt() and 0xfe) == 0xfc
    }

    private fun parseAllowedNetworks(): List<IpNetwork> {
        if (allowedCidrsConfig.isBlank() || allowedCidrsConfig.equals("none", ignoreCase = true)) return emptyList()
        return allowedCidrsConfig.split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { runCatching { IpNetwork.parse(it) }.getOrNull() }
    }

    private fun elapsedMillis(startedNanos: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

    private fun failure(startedNanos: Long, checkedAt: Instant, code: String, summary: String, statusCode: Int? = null) =
        CheckResponse("DOWN", statusCode, code, summary, elapsedMillis(startedNanos), checkedAt.toString())

    private fun unknown(startedNanos: Long, checkedAt: Instant, code: String, summary: String, statusCode: Int? = null) =
        CheckResponse("UNKNOWN", statusCode, code, summary, elapsedMillis(startedNanos), checkedAt.toString())

    private data class IpNetwork(val network: ByteArray, val prefixLength: Int) {
        fun contains(address: InetAddress): Boolean {
            val candidate = address.address
            if (candidate.size != network.size) return false
            val fullBytes = prefixLength / 8
            val remainingBits = prefixLength % 8
            for (index in 0 until fullBytes) if (candidate[index] != network[index]) return false
            if (remainingBits == 0) return true
            val mask = (0xff shl (8 - remainingBits)) and 0xff
            return (candidate[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
        }

        companion object {
            fun parse(value: String): IpNetwork {
                val parts = value.split('/', limit = 2)
                val address = InetAddress.getByName(parts[0])
                val prefix = parts.getOrNull(1)?.toInt() ?: address.address.size * 8
                require(prefix in 0..address.address.size * 8)
                return IpNetwork(address.address, prefix)
            }
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
        private const val MAX_WEBHOOK_BYTES = 64 * 1024
        private const val WEBHOOK_TIMEOUT_SECONDS = 10L
    }
}
