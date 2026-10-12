package com.geekcorelab.monitor

import com.fasterxml.jackson.databind.JsonNode
import com.geekcorelab.api.CheckResponse
import com.geekcorelab.security.SecretEncryptionService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.time.Instant
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal data class DatabaseCheckFailure(val status: String, val code: String, val summary: String)

@ApplicationScoped
class DatabaseCheckService {
    @Inject lateinit var addressPolicy: HttpCheckService
    @Inject lateinit var secretEncryption: SecretEncryptionService

    fun execute(type: String, config: JsonNode, timeoutSeconds: Int): CheckResponse {
        val started = System.nanoTime()
        val checkedAt = Instant.now()
        fun result(status: String, code: String?, summary: String?) =
            CheckResponse(status, null, code, summary, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), checkedAt.toString())

        val kind = type.uppercase()
        val host = config.path("host").asText("")
        val port = config.path("port").asInt()
        if (kind !in DATABASE_TYPES || !validHost(host) || port !in 1..65535 || timeoutSeconds !in 1..30) {
            return result("UNKNOWN", "CHECK_CONFIG_INVALID", "数据库检查配置无效")
        }
        val resolution = addressPolicy.resolveAllowedTarget(host)
        if (resolution.errorCode != null) {
            return when (resolution.errorCode) {
                "TARGET_BLOCKED" -> result("UNKNOWN", "TARGET_BLOCKED", "数据库目标地址不在允许范围内")
                else -> result("DOWN", "DNS_FAILURE", "数据库目标域名无法解析")
            }
        }
        val passwordEncrypted = config.path("passwordEncrypted")
            .takeUnless { it.isMissingNode || it.isNull || it.asText().isBlank() }
            ?.asText()
        val password = if (passwordEncrypted == null) "" else try {
            secretEncryption.decrypt(passwordEncrypted)
        } catch (_: Exception) {
            return result("UNKNOWN", "CREDENTIAL_DECRYPTION_FAILED", "数据库凭据无法解密，请重新保存凭据")
        }
        val tls = config.path("tls").asBoolean(false)
        return when (kind) {
            "POSTGRESQL", "MYSQL" -> checkJdbc(kind, config, resolution.addresses, port, password, tls, timeoutSeconds, started, checkedAt, ::result)
            "REDIS" -> checkRedis(config, resolution.addresses, host, port, password, tls, timeoutSeconds, started, checkedAt, ::result)
            else -> result("UNKNOWN", "UNSUPPORTED_CHECK", "不支持此数据库检查类型")
        }
    }

    private fun checkJdbc(
        kind: String,
        config: JsonNode,
        addresses: List<InetAddress>,
        port: Int,
        password: String,
        tls: Boolean,
        timeoutSeconds: Int,
        started: Long,
        checkedAt: Instant,
        result: (String, String?, String?) -> CheckResponse,
    ): CheckResponse {
        val database = config.path("databaseName").asText("")
        val username = config.path("username").asText("")
        if (!DATABASE_NAME.matches(database) || username.isBlank()) {
            return result("UNKNOWN", "CHECK_CONFIG_INVALID", "数据库名称或用户名配置无效")
        }
        val deadline = started + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        var lastException: Exception? = null
        for (address in addresses.take(MAX_ADDRESSES)) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) break
            val remainingSeconds = TimeUnit.NANOSECONDS.toSeconds(remainingNanos + 999_999_999).coerceAtLeast(1).toInt()
            val ip = address.hostAddress.substringBefore('%')
            val jdbcHost = if (':' in ip) "[$ip]" else ip
            val url = when (kind) {
                "POSTGRESQL" -> "jdbc:postgresql://$jdbcHost:$port/$database"
                else -> "jdbc:mysql://$jdbcHost:$port/$database"
            }
            val properties = Properties().apply {
                setProperty("user", username)
                setProperty("password", password)
                if (kind == "POSTGRESQL") {
                    setProperty("connectTimeout", minOf(remainingSeconds, JDBC_CONNECT_TIMEOUT_SECONDS).toString())
                    setProperty("socketTimeout", minOf(remainingSeconds, timeoutSeconds).toString())
                    setProperty("sslmode", if (tls) "verify-ca" else "disable")
                    setProperty("ApplicationName", "GeekMonitor")
                } else {
                    setProperty("connectTimeout", (minOf(remainingSeconds, JDBC_CONNECT_TIMEOUT_SECONDS) * 1000).toString())
                    setProperty("socketTimeout", (minOf(remainingSeconds, timeoutSeconds) * 1000).toString())
                    setProperty("sslMode", if (tls) "VERIFY_CA" else "DISABLED")
                    setProperty("allowPublicKeyRetrieval", "false")
                    setProperty("applicationName", "GeekMonitor")
                }
            }
            try {
                DriverManager.getConnection(url, properties).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.queryTimeout = minOf(remainingSeconds, timeoutSeconds)
                        statement.executeQuery("SELECT 1").use { rows ->
                            if (!rows.next() || rows.getInt(1) != 1) throw SQLException("fixed health query returned no result", "HY000")
                        }
                    }
                    val metrics = readJdbcConnectionMetrics(kind, connection)
                    val utilization = metrics["database_connections_percent"]
                        ?.let { "; 数据库连接使用率 %.1f%%".format(java.util.Locale.ROOT, it) }
                        .orEmpty()
                    return CheckResponse(
                        "UP", null, null, "数据库只读探测成功$utilization",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), checkedAt.toString(), metrics = metrics,
                    )
                }
            } catch (exception: Exception) {
                lastException = exception
                if (deadline - System.nanoTime() <= 0) break
            }
        }
        return classify(lastException, result)
    }

    private fun checkRedis(
        config: JsonNode,
        addresses: List<InetAddress>,
        host: String,
        port: Int,
        password: String,
        tls: Boolean,
        timeoutSeconds: Int,
        started: Long,
        checkedAt: Instant,
        result: (String, String?, String?) -> CheckResponse,
    ): CheckResponse {
        val username = config.path("username").asText("")
        val database = config.path("redisDatabase").asInt(0)
        if (database !in 0..15) return result("UNKNOWN", "CHECK_CONFIG_INVALID", "Redis 数据库编号无效")
        val deadline = started + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        var lastException: Exception? = null
        for (address in addresses.take(MAX_ADDRESSES)) {
            try {
                Socket().use { rawSocket ->
                    rawSocket.connect(InetSocketAddress(address, port), remainingMillis(deadline))
                    updateReadTimeout(rawSocket, deadline)
                    rawSocket.tcpNoDelay = true
                    val socket: Socket = if (tls) {
                        val tlsSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                            .createSocket(rawSocket, host.trim().removeSurrounding("[", "]"), port, true) as SSLSocket
                        updateReadTimeout(tlsSocket, deadline)
                        tlsSocket.sslParameters = SSLParameters().apply { endpointIdentificationAlgorithm = "HTTPS" }
                        tlsSocket.startHandshake()
                        tlsSocket
                    } else rawSocket
                    socket.use { connected ->
                    val input = DataInputStream(BufferedInputStream(connected.getInputStream()))
                    val output = BufferedOutputStream(connected.getOutputStream())
                    if (password.isNotEmpty()) {
                        val auth = if (username.isBlank()) listOf("AUTH", password) else listOf("AUTH", username, password)
                        writeRedisCommand(output, auth)
                        updateReadTimeout(connected, deadline)
                        requireRedisOk(readRedisReply(input))
                    } else if (username.isNotBlank()) {
                        return result("UNKNOWN", "CHECK_CONFIG_INVALID", "Redis ACL 用户必须配置密码")
                    }
                    if (database != 0) {
                        writeRedisCommand(output, listOf("SELECT", database.toString()))
                        updateReadTimeout(connected, deadline)
                        requireRedisOk(readRedisReply(input))
                    }
                    writeRedisCommand(output, listOf("PING"))
                    updateReadTimeout(connected, deadline)
                    if (readRedisReply(input) != "PONG") throw RedisCheckException("Redis PING returned an unexpected response")
                    val metrics = readRedisMetrics(input, output, connected, deadline)
                    val utilization = metrics["database_connections_percent"]
                        ?.let { "; Redis 连接使用率 %.1f%%".format(java.util.Locale.ROOT, it) }
                        .orEmpty()
                    return CheckResponse(
                        "UP", null, null, "Redis 只读 PING 探测成功$utilization",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), checkedAt.toString(), metrics = metrics,
                    )
                    }
                }
            } catch (exception: Exception) {
                lastException = exception
                if (deadline - System.nanoTime() <= 0) break
            }
        }
        val exception = rootCause(lastException)
        return when {
            lastException == null -> result("DOWN", "TIMEOUT", "Redis 检查超过配置的总超时时间")
            exception is RedisCheckException && (exception.message.orEmpty().startsWith("WRONGPASS") ||
                exception.message.orEmpty().startsWith("NOAUTH") || exception.message.orEmpty().startsWith("ERR AUTH")) ->
                result("DOWN", "AUTHENTICATION_FAILED", "Redis 认证失败")
            exception is RedisCheckException -> result("DOWN", "DATABASE_QUERY_FAILED", "Redis 只读探测命令失败")
            exception is java.net.SocketTimeoutException -> result("DOWN", "TIMEOUT", "Redis 连接或读取超时")
            exception is SSLException -> result("DOWN", "TLS_FAILURE", "Redis TLS 证书或握手校验失败")
            exception is java.net.ConnectException -> result("DOWN", "CONNECTION_REFUSED", "Redis 目标拒绝连接")
            else -> result("DOWN", "NETWORK_FAILURE", "无法连接 Redis 服务")
        }
    }

    private fun classify(
        exception: Exception?,
        result: (String, String?, String?) -> CheckResponse,
    ): CheckResponse {
        val failure = classifyDatabaseFailure(exception)
        return result(failure.status, failure.code, failure.summary)
    }

    internal fun classifyDatabaseFailure(exception: Exception?): DatabaseCheckFailure {
        val root = rootCause(exception)
        val sql = generateSequence(exception) { (it as? SQLException)?.nextException }.firstOrNull { it is SQLException } as? SQLException
        return when {
            root is InterruptedIOException || root is SQLTimeoutException || sql?.sqlState == "57014" ->
                DatabaseCheckFailure("DOWN", "TIMEOUT", "数据库连接或查询超时")
            root is SSLException -> DatabaseCheckFailure("DOWN", "TLS_FAILURE", "数据库 TLS 证书或握手校验失败")
            sql?.sqlState?.startsWith("28") == true -> DatabaseCheckFailure("DOWN", "AUTHENTICATION_FAILED", "数据库认证失败")
            sql?.sqlState?.startsWith("3D") == true -> DatabaseCheckFailure("DOWN", "DATABASE_NOT_FOUND", "数据库名称不存在")
            sql?.sqlState?.startsWith("08") == true || root is java.net.ConnectException ->
                DatabaseCheckFailure("DOWN", "CONNECTION_FAILED", "无法建立数据库连接")
            exception != null -> DatabaseCheckFailure("DOWN", "DATABASE_QUERY_FAILED", "数据库只读探测失败")
            else -> DatabaseCheckFailure("DOWN", "CONNECTION_FAILED", "无法建立数据库连接")
        }
    }

    internal fun readJdbcConnectionMetrics(kind: String, connection: java.sql.Connection): Map<String, Double> {
        val connected = runCatching {
            connection.createStatement().use { statement ->
                statement.queryTimeout = METRIC_QUERY_TIMEOUT_SECONDS
                val query = if (kind == "POSTGRESQL") {
                    "SELECT count(*) FROM pg_stat_activity"
                } else {
                    "SHOW GLOBAL STATUS LIKE 'Threads_connected'"
                }
                statement.executeQuery(query).use { rows ->
                    if (!rows.next()) null else if (kind == "POSTGRESQL") rows.getDouble(1) else rows.getString(2)?.toDoubleOrNull()
                }
            }
        }.getOrNull()
        val maximum = runCatching {
            connection.createStatement().use { statement ->
                statement.queryTimeout = METRIC_QUERY_TIMEOUT_SECONDS
                val query = if (kind == "POSTGRESQL") {
                    "SELECT setting FROM pg_settings WHERE name = 'max_connections'"
                } else {
                    "SHOW GLOBAL VARIABLES LIKE 'max_connections'"
                }
                statement.executeQuery(query).use { rows ->
                    if (!rows.next()) null else if (kind == "POSTGRESQL") rows.getString(1)?.toDoubleOrNull() else rows.getString(2)?.toDoubleOrNull()
                }
            }
        }.getOrNull()
        return connectionMetrics(connected, maximum)
    }

    private fun readRedisMetrics(
        input: DataInputStream,
        output: BufferedOutputStream,
        socket: Socket,
        deadline: Long,
    ): Map<String, Double> {
        fun readInfo(section: String): String? = runCatching {
            writeRedisCommand(output, listOf("INFO", section))
            updateReadTimeout(socket, deadline)
            readRedisReply(input)
        }.getOrNull()

        val clientsInfo = readInfo("clients")
        val memoryInfo = readInfo("memory")
        val statsInfo = readInfo("stats")
        val maximum = runCatching {
            writeRedisCommand(output, listOf("CONFIG", "GET", "maxclients"))
            updateReadTimeout(socket, deadline)
            readRedisReply(input).split(',', limit = 2).getOrNull(1)?.toDoubleOrNull()
        }.getOrNull()
        return parseRedisMetrics(clientsInfo, memoryInfo, statsInfo, maximum)
    }

    internal fun parseRedisMetrics(
        clientsInfo: String?,
        memoryInfo: String?,
        statsInfo: String?,
        maximumClients: Double?,
    ): Map<String, Double> {
        fun value(info: String?, name: String): Double? = info
            ?.lineSequence()
            ?.map(String::trim)
            ?.firstOrNull { it.substringBefore(':') == name }
            ?.substringAfter(':')
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0 }

        val connected = value(clientsInfo, "connected_clients")
        val usedMemory = value(memoryInfo, "used_memory")
        val memoryLimit = value(memoryInfo, "maxmemory")?.takeIf { it > 0 }
        val evictedKeys = value(statsInfo, "evicted_keys")

        return buildMap {
            putAll(connectionMetrics(connected, maximumClients))
            usedMemory?.let { put("redis_memory_used_bytes", it) }
            memoryLimit?.let { put("redis_memory_limit_bytes", it) }
            if (usedMemory != null && memoryLimit != null) {
                put("redis_memory_used_percent", usedMemory / memoryLimit * 100.0)
            }
            evictedKeys?.let { put("redis_evicted_keys_total", it) }
        }
    }

    private fun connectionMetrics(connected: Double?, maximum: Double?): Map<String, Double> = buildMap {
        connected?.takeIf { it.isFinite() && it >= 0 }?.let { put("database_connections", it) }
        maximum?.takeIf { it.isFinite() && it > 0 }?.let { put("database_connection_limit", it) }
        if (connected != null && maximum != null && connected.isFinite() && maximum.isFinite() && connected >= 0 && maximum > 0) {
            put("database_connections_percent", connected / maximum * 100.0)
        }
    }

    private fun writeRedisCommand(output: BufferedOutputStream, arguments: List<String>) {
        output.write("*${arguments.size}\r\n".toByteArray(Charsets.US_ASCII))
        arguments.forEach { argument ->
            val bytes = argument.toByteArray(Charsets.UTF_8)
            output.write("\$${bytes.size}\r\n".toByteArray(Charsets.US_ASCII))
            output.write(bytes)
            output.write(CRLF)
        }
        output.flush()
    }

    private fun readRedisReply(input: DataInputStream, depth: Int = 0): String {
        require(depth <= 4) { "Redis reply nesting exceeds limit" }
        return when (val marker = input.readUnsignedByte().toChar()) {
            '+' -> readRedisLine(input)
            '-' -> throw RedisCheckException(readRedisLine(input))
            ':' -> readRedisLine(input)
            '$' -> {
                val size = readRedisLine(input).toIntOrNull() ?: throw RedisCheckException("invalid Redis bulk length")
                if (size < 0) "" else {
                    require(size <= MAX_REDIS_REPLY_BYTES) { "Redis reply exceeds limit" }
                    val bytes = ByteArray(size)
                    input.readFully(bytes)
                    require(input.readUnsignedByte() == 13 && input.readUnsignedByte() == 10) { "invalid Redis bulk ending" }
                    String(bytes, Charsets.UTF_8)
                }
            }
            '*' -> {
                val count = readRedisLine(input).toIntOrNull() ?: throw RedisCheckException("invalid Redis array length")
                require(count in 0..32) { "Redis array exceeds limit" }
                List(count) { readRedisReply(input, depth + 1) }.joinToString(",")
            }
            else -> throw RedisCheckException("invalid Redis response marker ${marker.code}")
        }
    }

    private fun readRedisLine(input: DataInputStream): String {
        val bytes = ArrayList<Byte>(64)
        while (bytes.size < MAX_REDIS_LINE_BYTES) {
            val value = input.readUnsignedByte()
            if (value == 13) {
                require(input.readUnsignedByte() == 10) { "invalid Redis line ending" }
                return String(bytes.toByteArray(), Charsets.UTF_8)
            }
            bytes += value.toByte()
        }
        throw RedisCheckException("Redis line exceeds limit")
    }

    private fun requireRedisOk(reply: String) {
        if (reply != "OK") throw RedisCheckException(reply)
    }

    private fun remainingMillis(deadline: Long): Int {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw java.net.SocketTimeoutException("database check deadline exceeded")
        return TimeUnit.NANOSECONDS.toMillis(remaining + 999_999).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
    }

    private fun updateReadTimeout(socket: Socket, deadline: Long) {
        socket.soTimeout = remainingMillis(deadline)
    }

    private fun rootCause(exception: Throwable?): Throwable? {
        var current = exception ?: return null
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }

    private fun validHost(host: String): Boolean = runCatching {
        val normalized = host.trim().removeSurrounding("[", "]")
        require(normalized.isNotBlank() && normalized.length <= 253 && '/' !in normalized && '@' !in normalized && !normalized.any(Char::isWhitespace))
        true
    }.getOrDefault(false)

    private class RedisCheckException(message: String) : RuntimeException(message)

    companion object {
        private const val MAX_ADDRESSES = 4
        private const val MAX_REDIS_REPLY_BYTES = 4096
        private const val MAX_REDIS_LINE_BYTES = 4096
        private const val JDBC_CONNECT_TIMEOUT_SECONDS = 5
        private const val METRIC_QUERY_TIMEOUT_SECONDS = 1
        private val DATABASE_TYPES = setOf("POSTGRESQL", "MYSQL", "REDIS")
        private val DATABASE_NAME = Regex("[A-Za-z0-9_.-]{1,63}")
        private val CRLF = byteArrayOf(13, 10)
    }
}
