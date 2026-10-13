package com.eignex.lab

import io.ktor.client.request.get
import io.ktor.client.request.header
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
    private val host = HostReport("Test/Arm64", 4, "java", "engine", true, emptyList(), vendor = "Accelerate")

    private fun basic(user: String, password: String) = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    @Test
    fun `a hash verifies its password and no other, and tells nothing from a malformed one`() {
        val password = Password.generate()
        val hash = Password.hash(password, iterations = 1_000)

        assertTrue(password.length >= 43)
        assertTrue(Password.verify(password, hash))
        assertFalse(Password.verify(password + "x", hash))
        assertFalse(Password.verify(password, "plain"))
        assertFalse(Password.verify(password, "pbkdf2-sha256\$1000\$!!\$!!"))
    }

    @Test
    fun `the lab ships a hash, not a password`() {
        assertTrue(Password.bundled().startsWith("pbkdf2-sha256\$210000\$"))
    }

    @Test
    fun `the gate takes the password under any user name, or none`() {
        val gate = PasswordGate(Password.hash("secret", iterations = 1_000))

        assertTrue(gate.allows(basic("", "secret")))
        assertTrue(gate.allows(basic("anyone", "secret")))
        assertFalse(gate.allows(basic("", "wrong")))
        assertFalse(gate.allows(basic("secret", "")))
        assertFalse(gate.allows("Bearer secret"))
        assertFalse(gate.allows(null))
    }

    @Test
    fun `every page and call, health included, needs the password`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = Password.hash("secret", iterations = 1_000))
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
            assertEquals(HttpStatusCode.OK, client.get("/") {
                header(HttpHeaders.Authorization, basic("", "secret"))
                header(HttpHeaders.Accept, "text/html")
            }.status)
        }
    }
}
