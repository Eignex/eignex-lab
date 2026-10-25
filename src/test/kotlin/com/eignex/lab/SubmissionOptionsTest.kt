package com.eignex.lab

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubmissionOptionsTest {
    private fun input(text: String) = experimentInput(Json.parseToJsonElement(text).jsonObject)
    private fun spec(text: String) = Json.decodeFromJsonElement(ExperimentSpec.serializer(), input(text))

    @Test
    fun `measurement modes retain the corresponding legacy execution and identity`() {
        val modes = mapOf(
            "none" to "",
            "resources" to "\"measureCliResources\":true,\"parallel\":1,",
            "jfr" to "\"profileCli\":true,\"parallel\":1,",
            "compilation" to "\"profileCli\":true,\"profileCompilation\":\"jfr\",\"parallel\":1,",
            "compilation+log" to "\"profileCli\":true,\"profileCompilation\":\"jfr+log\",\"parallel\":1,",
        )
        for ((mode, legacyFields) in modes) {
            val modern = spec("""{"measurement":"$mode","name":"e","problems":{"suite":"s"}}""")
            val legacy = Json.decodeFromString<ExperimentSpec>("""{$legacyFields"name":"e","problems":{"suite":"s"}}""")
            assertEquals(legacy, modern, mode)
            assertEquals(Experiments.fingerprint(legacy), Experiments.fingerprint(modern), mode)
            assertFalse("measurement" in Json.encodeToString(ExperimentSpec.serializer(), modern))
            Experiments.validate(modern, 4)
        }
        assertEquals(Json.parseToJsonElement("""{"name":"ordinary"}"""), input("""{"name":"ordinary"}"""))
    }

    @Test
    fun `measurement rejects ambiguous flags while preserving explicit parallel validation`() {
        for (body in listOf(
            """{"measurement":"jfr","profileCli":false}""",
            """{"measurement":"resources","profileCompilation":"jfr"}""",
            """{"measurement":"none","measureCliResources":true}""",
            """{"measurement":"unknown"}""",
            """{"measurement":true}""",
            """{"measurement":"jfr","profile":{"main":"com.eignex.Main"}}""",
        )) assertFailsWith<IllegalArgumentException>(body) { input(body) }
        val redundant = spec("""{"name":"e","problems":{"suite":"s"},"measurement":"jfr","profileCli":true}""")
        assertTrue(redundant.profileCli)
        val parallel = spec("""{"name":"e","problems":{"suite":"s"},"measurement":"resources","parallel":2}""")
        assertFailsWith<IllegalArgumentException> { Experiments.validate(parallel, 4) }
    }

    @Test
    fun `measurement is normalized for immediate submissions and schedule edits`() = testApplication {
        val config = Config(dataDir = Files.createTempDirectory("measurement api"), passwordHash = null)
        val store = Store(config.database)
        val schedule = store.createSchedule("nightly", "main", ExperimentSpec("e", description = "d", problems = listOf(mapOf("suite" to "s"))), 3600)
        application { api(config, store, HostReport("test", 4, "java", "vector", true, emptyList())) }
        val created = client.post("/experiments") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"e","description":"record resources","problems":{"suite":"s"},"base":{"ref":"0123456789abcdef"},"measurement":"resources"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        assertTrue(store.active().single().experiment!!.measureCliResources)
        assertEquals(1, store.active().single().parallel)
        val updated = client.post("/schedules/$schedule") {
            contentType(ContentType.Application.Json)
            setBody("""{"experiment":{"name":"e","description":"record compilations","problems":{"suite":"s"},"measurement":"compilation+log"}}""")
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals("jfr+log", store.schedules().single().experiment.profileCompilation)
        assertEquals(1, store.schedules().single().experiment.parallel)
        val rejected = client.post("/experiments") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"bad","measurement":"jfr","profileCli":false}""")
        }
        assertEquals(HttpStatusCode.BadRequest, rejected.status)
        assertEquals(1, store.active().size)
    }

    @Test
    fun `condensed client selectors and timeout preserve harness and main arguments and legacy commands`() {
        val root = Files.createTempDirectory("submission client")
        try {
            root.resolve("curl").apply { writeText("#!/bin/sh\ncat\n"); toFile().setExecutable(true) }
            fun command(vararg args: String): Pair<Int, String> = ProcessBuilder(listOf("bash", "deploy/lab") + args)
                .redirectErrorStream(true).apply {
                    environment()["PATH"] = "$root:${System.getenv("PATH")}"
                    environment()["LAB_PASSWORD"] = "fixture-password"
                    environment()["LAB_URL"] = "http://example.invalid"
                }.start().let { process ->
                    val output = process.inputStream.bufferedReader().readText()
                    process.waitFor() to output
                }
            fun payload(vararg args: String): JsonObject {
                val (exit, output) = command(*args)
                assertEquals(0, exit, output)
                return Json.parseToJsonElement(output).jsonObject
            }
            val capture = payload("koblas", "--tags", "arch:arm64, simd:sme,!spot", "--tags", "dedicated", "--timeout", "900",
                "main", "SME run", "--smoke", "--operation", "dot")
            assertEquals(listOf("arch:arm64", "dedicated", "simd:sme"), capture["nodeTags"]!!.jsonObject["require"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("spot", capture["nodeTags"]!!.jsonObject["exclude"]!!.jsonArray.single().jsonPrimitive.content)
            assertEquals("900000", capture["base"]!!.jsonObject["timeout"]!!.jsonPrimitive.content)
            assertEquals(listOf("--smoke", "--operation", "dot"), capture["profile"]!!.jsonObject["args"]!!.jsonArray.map { it.jsonPrimitive.content })

            val modern = payload("profile", "--timeout", "600", "com.eignex.Main", "main", "JFR run", "--", "--mode=small", "literal value")
            val legacy = payload("profile", "com.eignex.Main", "main", "JFR run", "local", "600", "--mode=small", "literal value")
            assertEquals(legacy, modern)
            val defaults = payload("profile", "com.eignex.Main", "main", "JFR run", "--", "--mode=small")
            assertFalse("timeout" in defaults["base"]!!.jsonObject)
            assertEquals("--mode=small", defaults["profile"]!!.jsonObject["args"]!!.jsonArray.single().jsonPrimitive.content)

            val file = root.resolve("experiment.json")
            file.writeText("""{"name":"e","nodeTags":{"require":["fast-ssd"]}}""")
            val run = payload("run", "--tags", "simd:avx2,!spot", "--measurement", "compilation", file.toString())
            assertEquals("compilation", run["measurement"]!!.jsonPrimitive.content)
            assertEquals(listOf("fast-ssd", "simd:avx2"), run["nodeTags"]!!.jsonObject["require"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(2, command("koblas", "--tags", "simd:avx2,,!spot", "main", "bad").first)
        } finally { root.toFile().deleteRecursively() }
    }
}
