package com.eignex.lab

import com.eignex.lab.util.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.execution.*
import com.eignex.lab.worker.*
import com.eignex.lab.nomad.*
import com.eignex.lab.web.*
import com.eignex.lab.cli.*

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordTest {
    private val host = HostReport("Test/Arm64", 4, "java")

    private fun basic(user: String, password: String) = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    @Test
    fun `a hash verifies its password and no other, and tells nothing from a malformed one`() {
        val password = Password.generate()
        val hash = Password.hash(password)

        assertTrue(password.length >= 43)
        assertTrue(Password.verify(password, hash))
        assertFalse(Password.verify(password + "x", hash))
        assertFalse(Password.verify(password, "plain"))
        assertFalse(Password.verify(password, "pbkdf2-sha256\$1000\$!!\$!!"))
    }

    @Test
    fun `the lab ships a hash, not a password`() {
        assertTrue(Password.bundled().startsWith("sha256\$"))
    }

    @Test
    fun `an older PBKDF2 hash still verifies`() {
        val salt = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val derived = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec("secret".toCharArray(), ByteArray(16) { it.toByte() }, 1_000, 256)).encoded
        val stored = "pbkdf2-sha256\$1000\$$salt\$${Base64.getEncoder().encodeToString(derived)}"

        assertTrue(Password.verify("secret", stored))
        assertFalse(Password.verify("other", stored))
    }

    @Test
    fun `failed passwords are throttled per client only, and a known one still passes`() {
        var now = 0L
        val gate = PasswordGate(Password.hash("secret")) { now }
        assertTrue(gate.allows(basic("", "secret"), "me"))

        repeat(10) { assertEquals(Admission.REFUSE, gate.check(basic("", "guess$it"), "attacker")) }
        // Throttled without being checked, even with the right password, unless the header already passed.
        assertEquals(Admission.THROTTLE, gate.check(basic("attacker", "secret"), "attacker"))
        assertEquals(Admission.ADMIT, gate.check(basic("", "secret"), "attacker"))
        // Failures from many clients do not lock a newcomer out.
        repeat(100) { gate.check(basic("", "guess$it"), "client$it") }
        assertEquals(Admission.ADMIT, gate.check(basic("newcomer", "secret"), "newcomer"))
        assertEquals(Admission.ADMIT, gate.check(basic("", "secret"), "me"))
        now += 60_000
        assertEquals(Admission.ADMIT, gate.check(basic("attacker", "secret"), "attacker"))
    }

    @Test
    fun `a write another site made a browser send is refused, and reads and plain clients pass`() {
        assertTrue(sameSite("GET", "cross-site", "https://evil.example", "lab.eignex.com"))
        assertFalse(sameSite("POST", "cross-site", "https://evil.example", "lab.eignex.com"))
        assertFalse(sameSite("POST", "same-site", null, "lab.eignex.com"))
        assertTrue(sameSite("POST", "same-origin", "https://lab.eignex.com", "lab.eignex.com"))
        assertFalse(sameSite("POST", null, "https://evil.example", "lab.eignex.com"))
        assertTrue(sameSite("POST", null, "http://192.168.50.104:8420", "192.168.50.104:8420"))
        assertTrue(sameSite("POST", null, null, "lab.eignex.com"))
    }

    @Test
    fun `the gate takes the password under any user name, or none`() {
        val gate = PasswordGate(Password.hash("secret"))

        assertTrue(gate.allows(basic("", "secret")))
        assertTrue(gate.allows(basic("anyone", "secret")))
        assertFalse(gate.allows(basic("", "wrong")))
        assertFalse(gate.allows(basic("secret", "")))
        assertFalse(gate.allows("Bearer secret"))
        assertFalse(gate.allows(null))
    }

    @Test
    fun `every page and call, health included, needs the password`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), auth = Auth.Required(Password.hash("secret")))
        val store = Store(config.database)
        testApplication {
            application { api(config, store, host) }

            for (path in listOf("/", "/health", "/jobs", "/trend", "/references", "/jobs/1/files")) {
                val refused = client.get(path) { header(HttpHeaders.Accept, "text/html") }
                assertEquals(HttpStatusCode.Unauthorized, refused.status, path)
                assertTrue(refused.headers[HttpHeaders.WWWAuthenticate]!!.startsWith("Basic"), path)
                assertEquals(HttpStatusCode.Unauthorized, client.get(path) { header(HttpHeaders.Authorization, basic("", "wrong")) }.status, path)
            }
            assertEquals(HttpStatusCode.OK, client.get("/health") { header(HttpHeaders.Authorization, basic("", "secret")) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/update") {
                header(HttpHeaders.Authorization, basic("", "secret"))
                header(HttpHeaders.Origin, "https://evil.example")
            }.status)
            assertEquals(HttpStatusCode.OK, client.get("/") {
                header(HttpHeaders.Authorization, basic("", "secret"))
                header(HttpHeaders.Accept, "text/html")
            }.status)
        }
    }
}
