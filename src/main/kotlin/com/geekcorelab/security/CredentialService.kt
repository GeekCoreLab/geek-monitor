package com.geekcorelab.security

import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@ApplicationScoped
class CredentialService {
    private val random = SecureRandom()
    private val iterations = 310_000
    private val keyBits = 256

    fun hashPassword(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val derived = derive(password, salt, iterations)
        return "pbkdf2-sha256\$$iterations\$${Base64.getEncoder().encodeToString(salt)}\$${Base64.getEncoder().encodeToString(derived)}"
    }

    fun verifyPassword(password: String, encoded: String): Boolean = runCatching {
        val parts = encoded.split('$')
        require(parts.size == 4 && parts[0] == "pbkdf2-sha256")
        val expected = Base64.getDecoder().decode(parts[3])
        val actual = derive(password, Base64.getDecoder().decode(parts[2]), parts[1].toInt())
        MessageDigest.isEqual(expected, actual)
    }.getOrDefault(false)

    fun newToken(): String = ByteArray(32).also(random::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

    fun tokenHash(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun derive(password: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, rounds, keyBits)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
