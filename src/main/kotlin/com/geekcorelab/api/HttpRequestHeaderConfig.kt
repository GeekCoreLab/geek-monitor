package com.geekcorelab.api

import com.geekcorelab.security.SecretEncryptionService

internal object HttpRequestHeaderConfig {
    private const val MAX_HEADERS = 32
    private const val MAX_TOTAL_LENGTH = 16_384
    private const val MAX_NAME_LENGTH = 128
    private const val MAX_VALUE_LENGTH = 4096
    private val headerName = Regex("""^[!#$%&'*+.^_`|~0-9A-Za-z-]+$""")
    private val restrictedHeaders = setOf(
        "host", "content-length", "transfer-encoding", "connection", "keep-alive",
        "proxy-authenticate", "proxy-authorization", "proxy-connection", "te", "trailer", "upgrade",
    )

    fun encrypt(
        submitted: Map<String, String>?,
        previousEncrypted: Map<String, String>,
        encryption: SecretEncryptionService,
    ): Map<String, String>? {
        if (submitted == null) return previousEncrypted.toMap()
        if (submitted.size > MAX_HEADERS || submitted.entries.sumOf { it.key.length + it.value.length } > MAX_TOTAL_LENGTH) return null

        val result = linkedMapOf<String, String>()
        for ((rawName, rawValue) in submitted) {
            val name = rawName.trim()
            val normalizedName = name.lowercase(java.util.Locale.ROOT)
            if (name.length !in 1..MAX_NAME_LENGTH || !headerName.matches(name) ||
                normalizedName in restrictedHeaders || normalizedName.startsWith("proxy-") ||
                rawValue.length > MAX_VALUE_LENGTH || rawValue.any(Char::isISOControl) ||
                result.containsKey(normalizedName)) return null

            val oldValue = previousEncrypted[normalizedName]
            val encryptedValue = if (rawValue.isEmpty()) oldValue ?: continue else encryption.encrypt(rawValue)
            result[normalizedName] = encryptedValue
        }
        return result
    }

    fun visibleNames(encrypted: Map<String, String>): Map<String, String> =
        encrypted.keys.associateWith { "" }
}
