package com.eignex.lab

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadHoldTest {
    private val host = HostReport("Test/Arm64", 4, "java")
    private val hour = 3_600_000L

    private fun hold(text: String): Hold = Json.decodeFromString(Hold.serializer(), text)

    /** An ended job whose directory has what a trim keeps and what it removes: case output, stdout and stderr. */
    private fun endedJob(config: Config, store: Store): Long {
        val id = store.create("fetched", "main", listOf("true" to 1L))
        val dir = config.jobDir(id)
        Files.createDirectories(dir.resolve("cases/0/out"))
        Files.writeString(dir.resolve("job.log"), "log\n")
        Files.writeString(dir.resolve("0.exit"), "0")
        Files.writeString(dir.resolve("0.out"), "stdout of case 0\n")
        Files.writeString(dir.resolve("0.err"), "stderr of case 0\n")
        Files.writeString(dir.resolve("cases/0/record.json"), "{}")
        Files.writeString(dir.resolve("cases/0/out/solution.sol"), "x = 1\n")
        return id
    }

    @Test
    fun `a hold is taken for 2 h, renewed, released once, and lapses on its own`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val store = Store(config.database)
        val id = endedJob(config, store)
        testApplication {
            application { api(config, store, host) }

            val created = client.post("/jobs/$id/hold")
            assertEquals(HttpStatusCode.Created, created.status)
            val first = hold(created.bodyAsText())
            assertEquals(id, first.job)
            assertEquals(JobTrim.HOLD_MS, first.expiresAt - first.createdAt)
            assertEquals(listOf(first), store.holds())

            val short = hold(client.post("/jobs/$id/hold?ttlSec=60").bodyAsText())
            assertNotEquals(first.id, short.id)
            assertEquals(60_000L, short.expiresAt - short.createdAt)
            assertEquals(HttpStatusCode.BadRequest, client.post("/jobs/$id/hold?ttlSec=7201").status)
            assertEquals(HttpStatusCode.BadRequest, client.post("/jobs/$id/hold?ttlSec=0").status)
            assertEquals(HttpStatusCode.NotFound, client.post("/jobs/999/hold").status)

            val renewed = client.post("/jobs/$id/hold/${short.id}")
            assertEquals(HttpStatusCode.OK, renewed.status)
            assertTrue(hold(renewed.bodyAsText()).expiresAt >= short.createdAt + JobTrim.HOLD_MS)
            assertEquals(HttpStatusCode.NotFound, client.post("/jobs/$id/hold/nonesuch").status)

            assertEquals(HttpStatusCode.OK, client.delete("/jobs/$id/hold/${short.id}").status)
            assertEquals(HttpStatusCode.NotFound, client.delete("/jobs/$id/hold/${short.id}").status)
            assertEquals(HttpStatusCode.NotFound, client.post("/jobs/$id/hold/${short.id}").status)
            assertEquals(listOf(first.id), store.holds().map { it.id })

            // The disk page and its data list the holds there are.
            assertContains(client.get("/disk") { header(HttpHeaders.Accept, "text/html") }.bodyAsText(), "Held for download")
            val disk = Json.parseToJsonElement(client.get("/disk").bodyAsText()).jsonObject
            assertEquals(first.id, disk.getValue("holds").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        }
        // Lapsed, a hold neither counts nor renews.
        assertEquals(emptyList(), store.holds(at = now() + JobTrim.HOLD_MS + 1))
        val lapsed = store.hold(id, hour, at = 0)
        assertNull(store.renewHold(id, lapsed.id, hour, at = 2 * hour))
    }

    @Test
    fun `holds outlive a restart`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val id = endedJob(config, Store(config.database))
        val taken = Store(config.database).hold(id, JobTrim.HOLD_MS)

        assertEquals(listOf(taken), Store(config.database).holds())
    }

    @Test
    fun `the hold calls need the password and refuse a cross-site browser`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = Password.hash("secret"))
        val store = Store(config.database)
        val id = endedJob(config, store)
        val auth = "Basic " + Base64.getEncoder().encodeToString(":secret".toByteArray())
        testApplication {
            application { api(config, store, host) }

            assertEquals(HttpStatusCode.Unauthorized, client.post("/jobs/$id/hold").status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/jobs/$id/hold") {
                header(HttpHeaders.Authorization, auth)
                header("Sec-Fetch-Site", "cross-site")
            }.status)
            assertEquals(emptyList(), store.holds())
            // curl sends neither Origin nor Sec-Fetch-Site, as the client's other writes do not.
            val taken = hold(client.post("/jobs/$id/hold") { header(HttpHeaders.Authorization, auth) }.bodyAsText())
            assertEquals(HttpStatusCode.Unauthorized, client.delete("/jobs/$id/hold/${taken.id}").status)
            assertEquals(HttpStatusCode.Forbidden, client.delete("/jobs/$id/hold/${taken.id}") {
                header(HttpHeaders.Authorization, auth)
                header(HttpHeaders.Origin, "https://evil.example")
            }.status)
            assertEquals(HttpStatusCode.OK, client.delete("/jobs/$id/hold/${taken.id}") { header(HttpHeaders.Authorization, auth) }.status)
            assertEquals(emptyList(), store.holds())
        }
    }

    @Test
    fun `the job page says how long a job is held for download`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val store = Store(config.database)
        val id = endedJob(config, store)
        val job = store.job(id)!!

        assertFalse("held for download" in jobPage(config, job, failedOnly = false))
        assertContains(jobPage(config, job, failedOnly = false, holds = listOf(store.hold(id, JobTrim.HOLD_MS))), "held for download until")
    }

    @Test
    fun `fetch holds the job while it lists and downloads, then releases it`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val store = Store(config.database)
        val id = endedJob(config, store)
        val heldWhileListing = mutableListOf<List<Long>>()
        withServer(config, store, onListing = { heldWhileListing += store.holds().map { it.job } }) { port ->
            val dest = Files.createTempDirectory("fetched")

            assertEquals(0, lab(port, "fetch", "$id", dest.toString()))

            assertEquals(listOf(listOf(id)), heldWhileListing)
            assertEquals(emptyList(), store.holds())
            for (path in listOf("job.log", "0.exit", "0.out", "0.err", "cases/0/record.json", "cases/0/out/solution.sol")) {
                assertEquals(config.jobDir(id).resolve(path).readText(), dest.resolve(path).readText(), path)
            }
            assertFalse(dest.resolve("lab-fetch-missing.json").exists())
        }
    }

    @Test
    fun `a file gone after the listing fails the fetch with a receipt, the rest fetched and the hold released`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val store = Store(config.database)
        val id = endedJob(config, store)
        // As a trim would, between the listing and the downloads: the case's stdout and its output go.
        val gone = listOf("0.out", "cases/0/out/solution.sol")
        withServer(config, store, onListing = { gone.forEach { Files.delete(config.jobDir(id).resolve(it)) } }) { port ->
            val dest = Files.createTempDirectory("fetched")

            assertEquals(1, lab(port, "fetch", "$id", dest.toString()))

            assertEquals(emptyList(), store.holds())
            val receipt = Json.parseToJsonElement(dest.resolve("lab-fetch-missing.json").readText()).jsonObject
            assertEquals(id, receipt.getValue("job").jsonPrimitive.long)
            assertEquals(6, receipt.getValue("listed").jsonPrimitive.int)
            val missing = receipt.getValue("missing").jsonArray.map { it.jsonObject }
            assertEquals(gone, missing.map { it.getValue("path").jsonPrimitive.content })
            assertEquals(listOf("404", "404"), missing.map { it.getValue("httpStatus").jsonPrimitive.content })
            assertEquals("stdout of case 0\n".length.toLong(), missing[0].getValue("bytes").jsonPrimitive.long)
            for (path in gone) assertFalse(dest.resolve(path).exists(), path)
            for (path in listOf("job.log", "0.err", "cases/0/record.json")) assertTrue(dest.resolve(path).exists(), path)
        }
    }

    /** A real lab server on a free port for [block], calling [onListing] each time `lab fetch` has been given a listing. */
    private fun withServer(config: Config, store: Store, onListing: () -> Unit, block: (Int) -> Unit) {
        val server = embeddedServer(Netty, port = 0) {
            install(createApplicationPlugin("listing") {
                onCallRespond { call, _ ->
                    if (call.request.httpMethod.value == "GET" && call.request.path().matches(Regex("/jobs/\\d+/files"))) onListing()
                }
            })
            api(config, store, host)
        }.start(wait = false)
        try {
            block(runBlocking { server.engine.resolvedConnectors().first().port })
        } finally {
            server.stop(0, 0)
        }
    }

    /** The exit status of `deploy/lab <args>` against the lab on [port]. */
    private fun lab(port: Int, vararg args: String): Int {
        val process = ProcessBuilder(listOf("bash", Path.of("deploy/lab").toAbsolutePath().toString()) + args)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.INHERIT)
            .apply { environment()["LAB_URL"] = "http://127.0.0.1:$port"; environment()["LAB_PASSWORD"] = "test" }
            .start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "lab ${args.joinToString(" ")} timed out")
        return process.exitValue()
    }
}
