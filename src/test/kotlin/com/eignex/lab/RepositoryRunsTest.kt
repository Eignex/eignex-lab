package com.eignex.lab

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
    fun `the client preserves capture switches and generic profile settings`() {
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

            val capture = payload("koblas", "--tags", "arch:arm64,!spot", "main", "capture dot", "--smoke", "--operation", "dot")
            val ordinary = payload("koblas", "main", "capture dot", "--smoke")
            val generic = payload("profile", "--repo", "Eignex/skema", "--build", ":tools:installDist", "--classpath",
                "tools/build/install/tools/lib/*", "--timeout", "600", "com.eignex.skema.profile.MainKt", "main", "profile tool", "--", "--mode=small")

            assertEquals("Eignex/koblas", capture["repository"]?.jsonPrimitive?.content)
            assertEquals("arch:arm64", capture["nodeTags"]?.jsonObject?.get("require")?.jsonArray?.single()?.jsonPrimitive?.content)
            assertEquals("spot", capture["nodeTags"]?.jsonObject?.get("exclude")?.jsonArray?.single()?.jsonPrimitive?.content)
            assertFalse("nodeTags" in ordinary)
            assertEquals(listOf("--smoke", "--operation", "dot"), capture["profile"]?.jsonObject?.get("args")?.jsonArray?.map { it.jsonPrimitive.content })
            assertEquals("Eignex/skema", generic["repository"]?.jsonPrimitive?.content)
            assertEquals(":tools:installDist", generic["profile"]?.jsonObject?.get("buildTasks")?.jsonArray?.single()?.jsonPrimitive?.content)
            assertEquals("--mode=small", generic["profile"]?.jsonObject?.get("args")?.jsonArray?.single()?.jsonPrimitive?.content)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `submission rejects a non Eignex repository before any source is fetched`() = testApplication {
        val config = Config(dataDir = Files.createTempDirectory("repository api"), passwordHash = null)
        val store = Store(config.database)
        application { api(config, store, HostReport("test", 4, "java", "vector", true, emptyList())) }

        val response = client.post("/experiments") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"bad","description":"outside owner","repository":"other/koblas","profile":{"script":"capture.sh"}}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(store.active().isEmpty())
    }
}
