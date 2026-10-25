package com.eignex.lab

import java.nio.file.Files
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BenchmarkCapturesTest {
    private val manifest = Json.parseToJsonElement("""{
        "schemaVersion":1,"revision":"abc","workingTreeStatus":"","instrumented":false,"jmhVersion":"1.37",
        "options":{"preset":"standard","mode":"performance","allocation":false},
        "timing":{"warmups":3,"iterations":5,"iterationMillis":1000,"forks":2},
        "caseTimings":{"sum":{"warmups":3,"iterations":5,"iterationMillis":1000,"forks":2}},
        "runtime":{"javaVersion":"25","javaVendor":"OpenJDK","javaArguments":"","os":"Linux 1","architecture":"amd64",
            "availableProcessors":"4","cpu":"test cpu","memory":"8GB","affinity":"0-3"}
    }""").jsonObject
    private val row = BenchmarkRow(
        Json.parseToJsonElement("""{"id":"sum","seed":0,"fixtureVersion":1,"batchSize":4096}""").jsonObject,
        "jvm-scalar", "passed", BenchmarkValidation("scalar", "a".repeat(64), listOf(BenchmarkMetric("error", 0.0, "relative", 1e-9))),
        listOf(BenchmarkMetric("time", 12.0, "ns/op"), BenchmarkMetric("timeError", 0.3, "ns/op")), "raw/sum.json",
    )
    private val capture = BenchmarkCapture("Eignex/kumulant", "passed", manifest, listOf(row))

    @Test
    fun `compatible captures show differences ratios and original uncertainty`() {
        val candidate = capture.copy(rows = listOf(row.copy(metrics = listOf(BenchmarkMetric("time", 9.0, "ns/op"), BenchmarkMetric("timeError", 0.2, "ns/op")))))

        val delta = BenchmarkCaptures.compare(capture, candidate).first()

        assertEquals(-3.0, delta.difference)
        assertEquals(0.75, delta.ratio)
        assertEquals(0.3, delta.baselineError)
        assertEquals(0.2, delta.candidateError)
        assertNull(delta.reason)
    }

    @Test
    fun `different case selection preserves compatible per case measurements`() {
        val times = JsonObject(manifest.getValue("caseTimings").jsonObject + ("other" to JsonObject(emptyMap())))
        val baseline = capture.copy(manifest = JsonObject(manifest + ("caseTimings" to times)), rows = listOf(row, row.copy(case = JsonObject(row.case + ("id" to JsonPrimitive("other"))))))

        val delta = BenchmarkCaptures.compare(baseline, capture).first()

        assertEquals(1.0, delta.ratio)
        assertNull(delta.reason)
    }

    @Test
    fun `incompatible evidence gives a reason without a numeric comparison`() {
        val runtime = manifest.getValue("runtime").jsonObject
        val options = manifest.getValue("options").jsonObject
        val changedManifests = listOf(
            manifest + ("workingTreeStatus" to JsonPrimitive(" M file")),
            manifest + ("options" to JsonObject(options + ("preset" to JsonPrimitive("smoke")))),
            manifest + ("options" to JsonObject(options + ("allocation" to JsonPrimitive(true)))),
            manifest + ("runtime" to JsonObject(runtime + ("cpu" to JsonPrimitive("another cpu")))),
            manifest - "instrumented",
            manifest + ("caseTimings" to JsonObject(mapOf("sum" to JsonPrimitive("other timing")))),
        )
        val variants = changedManifests.map { capture.copy(manifest = JsonObject(it)) } + listOf(
            capture.copy(repository = "Eignex/koblas"),
            capture.copy(status = "passedWithSkips"),
            capture.copy(measurement = Measurement.JFR),
            capture.copy(rows = listOf(row.copy(status = "failed"))),
            capture.copy(rows = listOf(row.copy(validation = null))),
            capture.copy(rows = listOf(row.copy(validation = row.validation!!.copy(inputSha256 = "different")))),
            capture.copy(rows = listOf(row.copy(validation = row.validation!!.copy(backend = "simd")))),
            capture.copy(rows = listOf(row.copy(case = JsonObject(row.case + ("fixtureVersion" to JsonPrimitive(2)))))),
        )
        for (candidate in variants) {
            val delta = BenchmarkCaptures.compare(capture, candidate).first()
            assertNotNull(delta.reason, candidate.toString())
            assertNull(delta.ratio)
            assertNull(delta.difference)
        }
    }

    @Test
    fun `Native build settings affect only Native rows`() {
        val settings = JsonPrimitive("compiler flags")
        val mixed = capture.copy(manifest = JsonObject(manifest + ("nativeBuildSettings" to settings)))
        assertNull(BenchmarkCaptures.compare(capture, mixed).first().reason)
        val native = capture.copy(rows = listOf(row.copy(target = "native")))
        assertContains(BenchmarkCaptures.compare(native, native).first().reason!!, "Native build settings")
        val configured = native.copy(manifest = JsonObject(manifest + ("nativeBuildSettings" to settings)))
        assertNull(BenchmarkCaptures.compare(configured, configured).first().reason)
    }

    @Test
    fun `zero and extreme baselines produce serializable comparisons`() {
        for (baseline in listOf(0.0, 1e-300, -Double.MAX_VALUE)) {
            val before = capture.copy(rows = listOf(row.copy(metrics = listOf(BenchmarkMetric("time", baseline, "ns/op")))))
            val after = capture.copy(rows = listOf(row.copy(metrics = listOf(BenchmarkMetric("time", Double.MAX_VALUE, "ns/op")))))
            val delta = BenchmarkCaptures.compare(before, after).first()
            assertNotNull(delta.reason)
            assertTrue(delta.ratio == null || delta.ratio.isFinite())
            assertTrue(delta.difference == null || delta.difference.isFinite())
            Json.encodeToString(delta)
        }
    }

    @Test
    fun `capture reports reject malformed duplicate and nonfinite evidence`() {
        val root = Files.createTempDirectory("capture")
        try {
            val report = Files.createDirectories(root.resolve("cases/0/out/report"))
            Files.writeString(report.resolve("manifest.json"), manifest.toString())
            val encoded = Json.encodeToString(row)
            for (results in listOf(
                """{"schemaVersion":2,"status":"passed","results":[$encoded]}""",
                """{"schemaVersion":1,"status":"passed","results":[$encoded,$encoded]}""",
                """{"schemaVersion":1,"status":"passed","results":[${encoded.replace("12.0", "1e309")}]}""",
            )) {
                Files.writeString(report.resolve("results.json"), results)
                assertFailsWith<IllegalArgumentException> { BenchmarkCaptures.read(root, "Eignex/kumulant") }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `reports outside the retained job are unavailable`() {
        val root = Files.createTempDirectory("capture")
        val external = Files.createTempDirectory("external")
        try {
            Files.createDirectories(root.resolve("cases/0/out"))
            Files.writeString(external.resolve("manifest.json"), manifest.toString())
            Files.writeString(external.resolve("results.json"), "{}")
            Files.createSymbolicLink(root.resolve("cases/0/out/report"), external)
            assertFalse(BenchmarkCaptures.available(root))
            assertNull(BenchmarkCaptures.read(root, "Eignex/kumulant"))
        } finally {
            root.toFile().deleteRecursively()
            external.toFile().deleteRecursively()
        }
    }

    @Test
    fun `capture pages escape report content and exclude unsafe raw links`() {
        val unsafe = row.copy(case = JsonObject(row.case + ("id" to JsonPrimitive("<script>"))), reason = "<script>", raw = "../private")

        val page = benchmarkCapturePage(7, capture.copy(rows = listOf(unsafe)), null, null)

        assertFalse(page.contains("<script>"))
        assertContains(page, "&lt;script&gt;")
        assertFalse(page.contains("report/../private"))
        assertContains(benchmarkCapturePage(7, capture, null, null), "/jobs/7/files/cases/0/out/report/raw/sum.json")
    }

    @Test
    fun `job capture endpoint reads compares and links retained evidence`() = testApplication {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val store = Store(config.database)
        val spec = ExperimentSpec("capture", repository = "Eignex/kumulant", profile = ProfileMain(script = "kumulant-bench/capture-report.sh"))
        val id = store.create("capture", "main", listOf("true" to 60L), experiment = spec)
        val dir = Files.createDirectories(config.jobDir(id).resolve("cases/0/out/report"))
        Files.writeString(dir.resolve("manifest.json"), manifest.toString())
        Files.writeString(dir.resolve("results.json"), """{"schemaVersion":1,"status":"passed","results":[${Json.encodeToString(row)}]}""")
        application { api(config, store, HostReport("Test/Arm64", 4, "java", "engine", true, emptyList(), vendor = "Accelerate")) }

        val report = client.get("/jobs/$id/capture")
        assertEquals(HttpStatusCode.OK, report.status)
        assertEquals("Eignex/kumulant", Json.parseToJsonElement(report.bodyAsText()).jsonObject.getValue("repository").jsonPrimitive.content)
        val comparison = client.get("/jobs/$id/capture?baseline=$id")
        assertEquals(HttpStatusCode.OK, comparison.status)
        assertEquals("1.0", Json.parseToJsonElement(comparison.bodyAsText()).jsonArray.first().jsonObject.getValue("ratio").jsonPrimitive.content)
        val html = client.get("/jobs/$id/capture?baseline=$id") { header(HttpHeaders.Accept, "text/html") }
        assertContains(html.bodyAsText(), "Compared with")
        assertContains(jobPage(config, store.job(id)!!, false), "/jobs/$id/capture")
        assertEquals(HttpStatusCode.BadRequest, client.get("/jobs/$id/capture?baseline=invalid").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/jobs/999/capture").status)
    }
}
