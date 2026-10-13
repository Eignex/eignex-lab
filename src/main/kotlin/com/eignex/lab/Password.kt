package com.eignex.lab

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The lab's one password, kept as a salted PBKDF2-SHA256 hash, `pbkdf2-sha256$<iterations>$<salt>$<hash>` with salt
 * and hash in base64. The hash is safe to publish: the password is [generate]d, long and random, so it cannot be
 * guessed from it. The hash the lab checks against ships with it, in the `lab-password` resource.
 */
object Password {
    private const val ITERATIONS = 210_000
    private const val BITS = 256
    private const val SALT_BYTES = 16
    private const val PASSWORD_BYTES = 32
    private const val PREFIX = "pbkdf2-sha256"
    private val random = SecureRandom()

    /** A new password: 32 random bytes, base64url. */
    fun generate(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(PASSWORD_BYTES).also(random::nextBytes))

    fun hash(password: String, iterations: Int = ITERATIONS, salt: ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes)): String {
        val b64 = Base64.getEncoder()
        return "$PREFIX\$$iterations\$${b64.encodeToString(salt)}\$${b64.encodeToString(derive(password, salt, iterations))}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.trim().split('$')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val b64 = Base64.getDecoder()
        val expected = runCatching { b64.decode(parts[3]) }.getOrNull() ?: return false
        val salt = runCatching { b64.decode(parts[2]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(password, salt, iterations), expected)
    }

    /** The hash the lab ships with. */
    fun bundled(): String = checkNotNull(Password::class.java.getResource("/lab-password")) { "no lab-password resource" }
        .readText().trim()

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, BITS)).encoded
}

/**
 * Whether a request's `Authorization` header carries the password, as HTTP Basic with any user name (none is asked
 * for). A header that passed is remembered by its SHA-256, so the slow hash runs once per client, not per request.
 */
class PasswordGate(private val stored: String) {
    private val accepted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun allows(authorization: String?): Boolean {
        val header = authorization?.trim() ?: return false
        if (!header.startsWith("Basic ", ignoreCase = true)) return false
        val digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(header.toByteArray()))
        if (digest in accepted) return true
        val decoded = runCatching { String(Base64.getDecoder().decode(header.substring(BASIC.length).trim())) }.getOrNull() ?: return false
        val password = decoded.substringAfter(':', missingDelimiterValue = "")
        if (password.isEmpty() || !Password.verify(password, stored)) return false
        if (accepted.size < MAX_ACCEPTED) accepted += digest
        return true
    }

    private companion object {
        const val BASIC = "Basic "
        const val MAX_ACCEPTED = 1_000
    }
}
