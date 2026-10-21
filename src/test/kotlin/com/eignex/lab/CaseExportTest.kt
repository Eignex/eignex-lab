package com.eignex.lab

import com.eignex.lab.checker.LabCheck
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaseExportTest {
    private val host = HostReport("Test/Arm64", 4, "java", "engine", true, emptyList(), vendor = "Accelerate")

    private val check = LabCheck(
        policy = "lab-smtlib-dolmen-0.10-v1", state = LabCheck.VALID, reason = "accepted", checker = "dolmen",
        checkerVersion = "0.10", checkerSha256 = "c".repeat(64), witnessSha256 = "w".repeat(64),
        sourceSha256 = mapOf("model" to "s".repeat(64)), checkedAt = 1234, elapsedMs = 81,
    )

    /** An experiment of [problems] problems on one arm, each case's record carrying a final witness; case 0 checked. */
    private fun experiment(config: Config, store: Store, problems: Int): Long {
        val id = store.create("export", "main", emptyList(), experiment = ExperimentSpec("export", listOf(mapOf("suite" to "s"))))
        store.plan(id, listOf(PlannedArm(Arm("base", emptyMap()), "abc")), (0 until problems).map { Problem("s", "p$it") },
            Experiments.cases(problems, 1, emptyList()), List(problems) { "true" to 1L })
        repeat(problems) { index ->
            store.caseRecord(id, index, """{"kind":"satisfy","feasible":true,"validationPolicy":"reported-result-v1",""" +
                """"finalWitness":"(model (define-fun x () Int $index))","stats":{"solveTime":5}}""")
        }
        store.caseCheck(id, 0, check.json())
        config.jobDir(id).toFile().mkdirs()
        return id
    }

    private fun lines(text: String): List<JsonElement> = text.lines().filter { it.isNotEmpty() }.map(Json::parseToJsonElement)

    @Test
    fun `the export has each case whole, its final witness and lab check included`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val store = Store(config.database)
        val id = experiment(config, store, 2)
        testApplication {
            application { api(config, store, host) }

            val response = client.get("/experiments/$id/cases.jsonl")
            val cases = lines(response.bodyAsText())

            assertEquals(ContentType("application", "x-ndjson"), response.contentType()?.withoutParameters())
            assertEquals(listOf("0", "1"), cases.map { it.jsonObject.getValue("index").jsonPrimitive.content })
            val first = cases[0].jsonObject
            for (key in listOf("index", "status", "problem", "arm", "seed", "repeat", "record")) assertTrue(key in first, key)
            val record = first.getValue("record").jsonObject
            assertEquals("(model (define-fun x () Int 0))", record.getValue("finalWitness").jsonPrimitive.content)
            assertEquals(Json.parseToJsonElement(check.json()), record.getValue("labCheck"))
            assertEquals("reported-result-v1", record.getValue("benchValidationPolicy").jsonPrimitive.content)
            assertNull(cases[1].jsonObject.getValue("record").jsonObject["labCheck"])
        }
    }

    @Test
    fun `the default cases response stays one JSON array of the same cases`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val store = Store(config.database)
        val id = experiment(config, store, 3)
        testApplication {
            application { api(config, store, host) }

            val array = Json.parseToJsonElement(client.get("/experiments/$id/cases").bodyAsText()).jsonArray
            val lines = lines(client.get("/experiments/$id/cases.jsonl").bodyAsText())

            assertEquals(lines, array.toList())
            assertEquals(check.policy, array[0].jsonObject.getValue("record").jsonObject.getValue("validationPolicy").jsonPrimitive.content)
        }
    }

    @Test
    fun `many cases stream out across pages, each once and in order`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val store = Store(config.database)
        val id = experiment(config, store, 250)
        testApplication {
            application { api(config, store, host) }

            val cases = lines(client.get("/experiments/$id/cases.jsonl").bodyAsText())

            assertEquals((0 until 250).map { it.toString() }, cases.map { it.jsonObject.getValue("index").jsonPrimitive.content })
            assertEquals("(model (define-fun x () Int 249))",
                cases.last().jsonObject.getValue("record").jsonObject.getValue("finalWitness").jsonPrimitive.content)
        }
    }

    @Test
    fun `a job with no cases exports nothing`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val store = Store(config.database)
        val id = store.create("plain", "main", listOf("true" to 1L))
        testApplication {
            application { api(config, store, host) }

            assertEquals("", client.get("/experiments/$id/cases.jsonl").bodyAsText())
        }
    }

    @Test
    fun `the client prints the export and fetch saves it beside the job's files`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val store = Store(config.database)
        val id = experiment(config, store, 40)
        config.jobDir(id).resolve("job.log").toFile().writeText("log\n")
        val server = embeddedServer(Netty, port = 0) { api(config, store, host) }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val printed = lab(port, "cases", "$id", "--json")
            val dest = Files.createTempDirectory("fetched")
            lab(port, "fetch", "$id", dest.toString())

            val cases = lines(printed)
            assertEquals(40, cases.size)
            assertEquals(Json.parseToJsonElement(check.json()), cases[0].jsonObject.getValue("record").jsonObject.getValue("labCheck"))
            assertEquals(printed, dest.resolve("lab-cases.jsonl").readText())
            assertEquals("log\n", dest.resolve("job.log").readText())
            assertEquals(lines(printed), lines(lab(port, "cases", "--json", "$id")))
        } finally {
            server.stop(0, 0)
        }
    }

    /** What `deploy/lab <args>` printed against the lab on [port]; it must succeed. */
    private fun lab(port: Int, vararg args: String): String {
        val out = Files.createTempFile("lab", ".out")
        val process = ProcessBuilder(listOf("bash", Path.of("deploy/lab").toAbsolutePath().toString()) + args)
            .redirectOutput(out.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT)
            .apply { environment()["LAB_URL"] = "http://127.0.0.1:$port"; environment()["LAB_PASSWORD"] = "test" }
            .start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "lab ${args.joinToString(" ")} timed out")
        assertEquals(0, process.exitValue(), "lab ${args.joinToString(" ")}")
        return out.readText()
    }
}
