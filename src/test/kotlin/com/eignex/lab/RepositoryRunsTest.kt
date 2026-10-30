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

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RepositoryRunsTest {
    @Test
    fun `the runner rejects an outside repository even if submission validation was bypassed`() {
        val root = Files.createTempDirectory("rejected source")
        try {
            val config = Config(dataDir = root, retry = Backoff(1, 1, 1), updateCheckSec = 0)
            val store = Store(config.database)
            val spec = ExperimentSpec("outside", repository = "other/koblas", profile = ProfileMain(script = "capture.sh"))
            val id = store.create(spec.name, "main", emptyList(), experiment = spec)
            var transportUsed = false
            val runner = Runner(config, store, sourceUrl = { transportUsed = true; error("must not fetch") })

            kotlin.test.assertFailsWith<IllegalArgumentException> { runner.runJob(checkNotNull(store.startForTest())) }

            assertEquals(Status.RUNNING, store.job(id)?.status)
            assertFalse(transportUsed)
            assertFalse(config.mirror.exists())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the client preserves repository bench requests and generic profile settings`() {
        val root = Files.createTempDirectory("lab client")
        try {
            root.resolve("curl").apply { writeText("#!/bin/sh\ncat\n"); toFile().setExecutable(true) }
            fun payload(vararg args: String) = ProcessBuilder(listOf("bash", "deploy/lab") + args)
                .redirectErrorStream(true).apply {
                    environment()["PATH"] = "$root:${System.getenv("PATH")}"
                    environment()["LAB_PASSWORD"] = "fixture-password"
                    environment()["LAB_URL"] = "http://example.invalid"
                }.start().let { process ->
                    val output = process.inputStream.bufferedReader().readText()
                    assertEquals(0, process.waitFor(), output)
                    Json.parseToJsonElement(output).jsonObject
                }

            val experiment = root.resolve("bench.json")
            experiment.writeText("""{"name":"bench","repository":"Eignex/example","base":{"ref":"main"},"bench":{"manifest":"bench/lab.json","request":{"parameters":{"mode":"small"}}}}""")
            val capture = payload("run", "--tags", "arch:arm64,!spot", experiment.toString())
            val ordinary = payload("run", experiment.toString())
            val generic = payload("profile", "--repo", "Eignex/skema", "--build", ":tools:installDist", "--classpath",
                "tools/build/install/tools/lib/*", "--timeout", "600", "com.eignex.skema.profile.MainKt", "main", "profile tool", "--", "--mode=small")

            assertEquals("Eignex/example", capture["repository"]?.jsonPrimitive?.content)
            assertEquals(listOf("!spot", "arch:arm64"), capture["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertFalse("tags" in ordinary)
            assertEquals(ordinary["bench"], capture["bench"])
            assertEquals("bench/lab.json", capture["bench"]!!.jsonObject["manifest"]!!.jsonPrimitive.content)
            assertEquals("small", capture["bench"]!!.jsonObject["request"]!!.jsonObject["parameters"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
            assertEquals("Eignex/skema", generic["repository"]?.jsonPrimitive?.content)
            assertEquals(":tools:installDist", generic["profile"]?.jsonObject?.get("buildTasks")?.jsonArray?.single()?.jsonPrimitive?.content)
            assertEquals("--mode=small", generic["profile"]?.jsonObject?.get("args")?.jsonArray?.single()?.jsonPrimitive?.content)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `submission rejects a non Eignex repository before any source is fetched`() = testApplication {
        val config = Config(dataDir = Files.createTempDirectory("repository api"), auth = Auth.Disabled)
        val store = Store(config.database)
        application { api(config, store, HostReport("test", 4, "java")) }

        val response = client.post("/experiments") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"bad","description":"outside owner","repository":"other/koblas","profile":{"script":"capture.sh"}}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(store.active().isEmpty())
    }
}
