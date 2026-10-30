package com.eignex.lab.domain

import com.eignex.lab.util.*

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The lab's one password, kept as a salted SHA-256 hash, `sha256$<salt>$<hash>` with salt and hash in base64. The
 * hash is safe to publish: the password is [generate]d, 32 random bytes, so it cannot be guessed from it, and for a
 * secret that cannot be guessed a slow hash adds no safety, only cost. The cost matters: every wrong password a
 * stranger sends is hashed on the lab machine, where its CPU time would disturb the timings of the cases running
 * there. `pbkdf2-sha256$<iterations>$<salt>$<hash>` still verifies. The hash the lab checks against ships with it, in
 * the `lab-password` resource.
 */
object Password {
    private const val SALT_BYTES = 16
    private const val PASSWORD_BYTES = 32
    private const val SHA256 = "sha256"
    private const val PBKDF2 = "pbkdf2-sha256"
    private const val PBKDF2_BITS = 256
    private val random = SecureRandom()

    /** A new password: 32 random bytes, base64url. */
    fun generate(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(PASSWORD_BYTES).also(random::nextBytes))

    fun hash(password: String, salt: ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes)): String {
        val b64 = Base64.getEncoder()
        return "$SHA256\$${b64.encodeToString(salt)}\$${b64.encodeToString(sha256(salt, password))}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.trim().split('$')
        val b64 = Base64.getDecoder()
        fun decode(text: String) = runCatching { b64.decode(text) }.getOrNull()
        val derived = when {
            parts.size == 3 && parts[0] == SHA256 -> decode(parts[1])?.let { sha256(it, password) }
            parts.size == 4 && parts[0] == PBKDF2 -> {
                val iterations = parts[1].toIntOrNull()
                val salt = decode(parts[2])
                if (iterations == null || salt == null) null else pbkdf2(password, salt, iterations)
            }
            else -> null
        } ?: return false
        val expected = decode(parts.last()) ?: return false
        return MessageDigest.isEqual(derived, expected)
    }

    /** The hash the lab ships with. */
    fun bundled(): String = checkNotNull(Password::class.java.getResource("/lab-password")) { "no lab-password resource" }
        .readText().trim()

    private fun sha256(salt: ByteArray, password: String): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { update(salt); update(password.toByteArray()) }.digest()

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, PBKDF2_BITS)).encoded
}

/** What [PasswordGate.check] decides for a request's credentials. */
enum class Admission { ADMIT, REFUSE, THROTTLE }

/**
 * Whether a request's `Authorization` header carries the password, as HTTP Basic with any user name (none is asked
 * for). A header that passed is remembered by its SHA-256. Failures are rationed besides, [PER_CLIENT] a minute from
 * one client and [CONCURRENT] checks at once, past which a request is throttled unchecked: whatever a stranger sends
 * costs the lab machine next to nothing, so it cannot slow the cases running there. There is no limit across clients:
 * a stranger could use it up and keep everyone else from logging in.
 */
/** Whether the API asks for the password: always in a running lab, [Disabled] only in tests. */
sealed interface Auth {
    data class Required(val hash: String) : Auth
    data object Disabled : Auth
}

class PasswordGate(private val stored: String, private val clock: () -> Long = System::currentTimeMillis) {
    private val accepted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val failures = ConcurrentHashMap<String, Window>()
    private val hashing = java.util.concurrent.Semaphore(CONCURRENT)

    private class Window(var start: Long, var count: Int)

    /** Whether [authorization] carries the password; [client] is who sent it, for the failure limits. */
    fun allows(authorization: String?, client: String = "-"): Boolean = check(authorization, client) == Admission.ADMIT

    fun check(authorization: String?, client: String): Admission {
        val header = authorization?.trim()?.takeIf { it.startsWith(BASIC, ignoreCase = true) } ?: return Admission.REFUSE
        val digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(header.toByteArray()))
        if (digest in accepted) return Admission.ADMIT
        if (exhausted(client)) return Admission.THROTTLE
        val decoded = runCatching { String(Base64.getDecoder().decode(header.substring(BASIC.length).trim())) }.getOrNull()
        val password = decoded?.substringAfter(':', missingDelimiterValue = "").orEmpty()
        if (password.isEmpty()) return Admission.REFUSE.also { fail(client) }
        if (!hashing.tryAcquire()) return Admission.THROTTLE
        val ok = try {
            Password.verify(password, stored)
        } finally {
            hashing.release()
        }
        if (!ok) return Admission.REFUSE.also { fail(client) }
        if (accepted.size < MAX_ACCEPTED) accepted += digest
        return Admission.ADMIT
    }

    private fun exhausted(client: String): Boolean = synchronized(this) {
        failures[client]?.let { count(it, clock()) >= PER_CLIENT } == true
    }

    private fun fail(client: String) = synchronized(this) {
        val now = clock()
        if (failures.size > MAX_CLIENTS) failures.clear()
        val window = failures.getOrPut(client) { Window(now, 0) }
        count(window, now)
        window.count++
    }

    /** [window]'s count in the current minute, starting a new minute when the last one is over. */
    private fun count(window: Window, now: Long): Int {
        if (now - window.start >= MINUTE_MS) {
            window.start = now
            window.count = 0
        }
        return window.count
    }

    private companion object {
        const val BASIC = "Basic "
        const val MAX_ACCEPTED = 1_000
        const val MAX_CLIENTS = 10_000
        const val MINUTE_MS = 60_000L
        const val PER_CLIENT = 10
        const val CONCURRENT = 2
    }
}

/**
 * Whether a request may change state, judged by where the browser says it came from: a page of another site can make
 * a browser send the lab's password along with a form it submits, so a write from another site is refused. A
 * reading request, and a client that sends neither header (curl, `lab`), pass.
 */
fun sameSite(method: String, secFetchSite: String?, origin: String?, host: String?): Boolean {
    if (method.uppercase() in setOf("GET", "HEAD", "OPTIONS")) return true
    if (secFetchSite != null) return secFetchSite in setOf("same-origin", "none")
    if (origin == null) return true
    val from = runCatching { java.net.URI(origin) }.getOrNull() ?: return false
    val authority = from.host + (if (from.port == -1) "" else ":${from.port}")
    return host != null && authority.equals(host, ignoreCase = true)
}
