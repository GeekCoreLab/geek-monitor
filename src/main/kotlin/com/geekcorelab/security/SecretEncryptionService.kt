package com.geekcorelab.security

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@ApplicationScoped
class SecretEncryptionService {
    @Inject @field:ConfigProperty(name = "geek-monitor.security.encryption-key", defaultValue = "")
    lateinit var keyBase64: String

    private val random = SecureRandom()

    fun isConfigured(): Boolean = runCatching { encryptionKey(); true }.getOrDefault(false)

    fun encrypt(value: String): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), GCMParameterSpec(TAG_BITS, nonce))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return listOf("v1", Base64.getEncoder().encodeToString(nonce), Base64.getEncoder().encodeToString(encrypted)).joinToString("$")
    }

    fun decrypt(value: String): String {
        val parts = value.split('$')
        require(parts.size == 3 && parts[0] == "v1")
        val nonce = Base64.getDecoder().decode(parts[1])
        require(nonce.size == NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(TAG_BITS, nonce))
        return String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), Charsets.UTF_8)
    }

    private fun encryptionKey(): SecretKeySpec {
        val decoded = runCatching { Base64.getDecoder().decode(keyBase64) }
            .getOrElse { throw IllegalStateException("GEEK_MONITOR_ENCRYPTION_KEY must be base64") }
        require(decoded.size == KEY_BYTES) { "GEEK_MONITOR_ENCRYPTION_KEY must decode to 32 bytes" }
        return SecretKeySpec(decoded, "AES")
    }

    companion object {
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
    }
}
