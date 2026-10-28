package com.eignex.lab

import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
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
    fun `only a flat tags array is accepted while historical recipes retain their original shape`() {
        val parsed = spec("""{"name":"p","tags":["arch:x86_64","!spot","arch:x86_64"]}""")
        assertEquals(NodeSelector(setOf("arch:x86_64"), setOf("spot")), parsed.nodeTags)
        assertEquals(null, spec("""{"name":"p","tags":[]}""").nodeTags)
        for (tags in listOf("null", "{}", "[1]", "[true]", "[null]", "\\\"spot\\\"", "[\\\"!\\\"]", "[\\\"spot\\\",\\\"!spot\\\"]", "[\\\"arch:arm64\\\",\\\"arch:x86_64\\\"]")) {
            assertFailsWith<IllegalArgumentException>(tags) { spec("""{"name":"p","tags":$tags}""") }
        }
        val legacy = """{"name":"old","host":"lab","auto":true,"machines":3,"nodeTags":{"require":["local"],"exclude":["spot"]}}"""
        assertFailsWith<IllegalArgumentException> { spec(legacy) }
        val restored = storedExperiment(legacy)
        assertEquals(Experiments.LAB_HOST, restored.host)
        assertTrue(restored.auto)
        assertEquals(3, restored.machines)
        assertEquals(NodeSelector(setOf("local"), setOf("spot")), restored.nodeTags)
        assertEquals(restored, storedExperiment(Json.encodeToString(ExperimentSpec.serializer(), restored)))
    }

    @Test
    fun `schedule responses round trip through tags without changing pinned policy or measured identity`() = testApplication {
        val root = Files.createTempDirectory("schedule tags")
        val queue = root.resolve("queue.json").also { it.writeText(java.io.File("deploy/nomad/queue.example.json").readText()) }
        val config = Config(dataDir = root, passwordHash = null, nomadQueueSettings = queue)
        val store = Store(config.database)
        val original = ExperimentSpec("status-sweep", problems = listOf(mapOf("suite" to "s")), description = "regression sweep", priority = 100)
        val id = store.createSchedule(original.name, "main", original, 120)
        store.scheduleRan(id, "abc", 1)
        application { api(config, store, HostReport("test", 4, "java", "vector", true, emptyList())) }
        val response = client.get("/schedules")
        val experiment = Json.parseToJsonElement(response.bodyAsText()).jsonArray.single().jsonObject.getValue("experiment").jsonObject
        assertEquals(listOf("local"), experiment.getValue("tags").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(setOf("nodeTags", "host", "auto", "machines").none { it in experiment })
        val updated = client.post("/schedules/$id") {
            contentType(ContentType.Application.Json); setBody("""{"experiment":$experiment}""")
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        val saved = store.schedules().single()
        assertEquals(original, saved.experiment)
        assertEquals("abc", saved.lastSha)
        val auto = scheduleInput(Json.parseToJsonElement("""{"experiment":{"name":"nightly"}}""").jsonObject)
        assertEquals(Experiments.AUTO_HOST, Json.decodeFromJsonElement(ExperimentSpec.serializer(), auto.getValue("experiment")).host)
    }

    @Test
    fun `measurement modes are canonical stored settings with the requested recording behavior`() {
        val modes = mapOf(
            "none" to Measurement.NONE,
            "resources" to Measurement.RESOURCES,
            "jfr" to Measurement.JFR,
            "compilation" to Measurement.COMPILATION,
            "compilation+log" to Measurement.COMPILATION_LOG,
        )
        val fingerprints = mutableSetOf<String>()
        for ((name, mode) in modes) {
            val experiment = spec("""{"measurement":"$name","name":"e","problems":{"suite":"s"}}""")
            assertEquals(mode, experiment.measurement)
            assertEquals(mode != Measurement.NONE, experiment.measuresCli)
            assertEquals(name in setOf("jfr", "compilation", "compilation+log"), experiment.recordsCliJfr)
            assertEquals(when (name) { "compilation" -> "jfr"; "compilation+log" -> "jfr+log"; else -> null }, experiment.compilationRecording)
            val stored = Json.encodeToString(ExperimentSpec.serializer(), experiment)
            assertEquals(experiment, Json.decodeFromString<ExperimentSpec>(stored))
            assertEquals(name != "none", "measurement" in Json.parseToJsonElement(stored).jsonObject)
            assertTrue(fingerprints.add(Experiments.fingerprint(experiment)))
            Experiments.validate(experiment, 4)
        }
        assertEquals(Json.parseToJsonElement("""{"name":"ordinary"}"""), input("""{"name":"ordinary"}"""))
    }

    @Test
    fun `removed recording keys and unknown modes are rejected instead of interpreted as aliases`() {
        for (fields in listOf(
            "\"profileCli\":true",
            "\"profileCli\":false,\"measurement\":\"jfr\"",
            "\"measureCliResources\":true",
            "\"profileCompilation\":\"jfr\"",
            "\"measurement\":\"unknown\"",
            "\"measurement\":true",
        )) assertFailsWith<IllegalArgumentException>(fields) {
            spec("""{"name":"e","problems":{"suite":"s"},$fields}""")
        }
        val profile = spec("""{"name":"e","measurement":"jfr","profile":{"main":"com.eignex.Main"}}""")
        assertFailsWith<IllegalArgumentException> { Experiments.validate(profile, 4) }
    }

    @Test
    fun `all measurement modes accept parallel cases within the configured limit`() {
        for (mode in Measurement.entries) {
            val experiment = ExperimentSpec("e", problems = listOf(mapOf("suite" to "s")), measurement = mode, parallel = 4)
            Experiments.validate(experiment, 4, 4)
            assertFailsWith<IllegalArgumentException> { Experiments.validate(experiment.copy(parallel = 5), 4, 4) }
        }
    }

    @Test
    fun `measurement is stored directly for immediate submissions and schedule edits`() = testApplication {
        val root = Files.createTempDirectory("measurement api")
        val queue = root.resolve("queue.json").also { it.writeText(java.io.File("deploy/nomad/queue.example.json").readText()) }
        val config = Config(dataDir = root, passwordHash = null, nomadQueueSettings = queue)
        val store = Store(config.database)
        val schedule = store.createSchedule("nightly", "main", ExperimentSpec("e", description = "d", problems = listOf(mapOf("suite" to "s"))), 3600)
        application { api(config, store, HostReport("test", 4, "java", "vector", true, emptyList())) }
        val created = client.post("/experiments") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"e","description":"record resources","problems":{"suite":"s"},"base":{"ref":"0123456789abcdef"},"measurement":"resources"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals(Measurement.RESOURCES, store.active().single().experiment!!.measurement)
        assertEquals(1, store.active().single().parallel)
        val id = store.active().single().id
        val parallel = client.post("/jobs/$id/parallel") {
            contentType(ContentType.Application.Json)
            setBody("""{"parallel":4}""")
        }
        assertEquals(HttpStatusCode.OK, parallel.status)
        assertEquals(4, store.job(id)!!.parallel)
        val updated = client.post("/schedules/$schedule") {
            contentType(ContentType.Application.Json)
            setBody("""{"experiment":{"name":"e","description":"record compilations","problems":{"suite":"s"},"measurement":"compilation+log","parallel":4}}""")
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals("jfr+log", store.schedules().single().experiment.compilationRecording)
        assertEquals(4, store.schedules().single().experiment.parallel)
        val rejected = client.post("/experiments") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"bad","measurement":"jfr","profileCli":false}""")
        }
        assertEquals(HttpStatusCode.BadRequest, rejected.status)
        assertEquals(1, store.active().size)
    }

    @Test
    fun `client accepts only consolidated options and passes harness and main arguments literally`() {
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
            assertEquals(listOf("!spot", "arch:arm64", "dedicated", "simd:sme"), capture["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("900000", capture["base"]!!.jsonObject["timeout"]!!.jsonPrimitive.content)
            assertEquals(listOf("--smoke", "--operation", "dot"), capture["profile"]!!.jsonObject["args"]!!.jsonArray.map { it.jsonPrimitive.content })

            val modern = payload("profile", "--timeout", "600", "com.eignex.Main", "main", "JFR run", "--", "--mode=small", "literal value")
            assertEquals("600000", modern["base"]!!.jsonObject["timeout"]!!.jsonPrimitive.content)
            assertEquals(listOf("--mode=small", "literal value"), modern["profile"]!!.jsonObject["args"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(2, command("profile", "com.eignex.Main", "main", "JFR run", "local", "600", "--mode=small").first)
            for (flag in listOf("--require-tag", "--exclude-tag")) {
                assertEquals(2, command("koblas", flag, "spot", "main", "bad").first)
                assertEquals(2, command("profile", flag, "spot", "com.eignex.Main", "main", "bad").first)
            }
            val defaults = payload("profile", "com.eignex.Main", "main", "JFR run", "--", "--mode=small")
            assertFalse("timeout" in defaults["base"]!!.jsonObject)
            assertEquals("--mode=small", defaults["profile"]!!.jsonObject["args"]!!.jsonArray.single().jsonPrimitive.content)

            val file = root.resolve("experiment.json")
            file.writeText("""{"name":"e","tags":["fast-ssd"]}""")
            val run = payload("run", "--tags", "simd:avx2,!spot", "--measurement", "compilation", file.toString())
            assertEquals("compilation", run["measurement"]!!.jsonPrimitive.content)
            assertEquals(listOf("!spot", "fast-ssd", "simd:avx2"), run["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("!spot", "arch:x86_64"), payload("tags", "7", "arch:x86_64,!spot")["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(0, payload("tags", "7")["tags"]!!.jsonArray.size)
            assertEquals(2, command("host", "7", "auto").first)
            assertEquals(2, command("machines", "7", "2").first)
            assertEquals(2, command("koblas", "--tags", "simd:avx2,,!spot", "main", "bad").first)
        } finally { root.toFile().deleteRecursively() }
    }
}
