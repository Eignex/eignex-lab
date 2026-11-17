package com.eignex.lab

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.cli.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.web.*
import com.eignex.lab.worker.*
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.html.*
import kotlinx.serialization.json.Json

/**
 * Text anyone can put in the lab (names, descriptions, refs, problems, arm labels, file names)
 * reaches a page only escaped.
 */
class PageEscapingTest {
    @Test
    fun `DSL escapes text and attributes exactly once while composing rendered fragments`() {
        val hostile = "\"><img src=x onerror=alert(1)> & entity &amp;"
        val fragment = buildHtml { b { +hostile } }
        val rendered = buildHtml {
            a {
                href = hostile
                emit(fragment)
                +hostile
            }
        }
            .markup
        assertTrue("href=\"&quot;&gt;&lt;img" in rendered)
        assertTrue("<b>&quot;&gt;&lt;img" in rendered)
        assertTrue("&amp; entity &amp;amp;" in rendered)
        assertFalse("<img" in rendered)
        assertFalse("&amp;quot;" in rendered)
    }

    @Test
    fun `trend JSON stays inside its script and round trips hostile values in both chart layouts`() {
        val hostile = "</script><script>alert(1)</script> & \"quoted\""
        val run =
            TrendRun(
                job = 1,
                sha = hostile,
                spec = hostile,
                at = 0,
                finished = true,
                cases = 1,
                problems = 1,
                solved = Estimate(1.0, 1.0, 1.0),
                proven = 0.0,
                unsupported = 0,
                errors = 0,
                par2 = Estimate(1.0, 1.0, 1.0),
                suites = emptyMap(),
            )
        for (sample in listOf(run, run.copy(benchmark = BenchmarkTrend(1, 0, 0, 0, emptyList())))) {
            val page = trendPage("sweep", listOf(sample), "https://example.com/repo").markup
            assertFalse("<script>alert(1)</script>" in page)
            val embedded = page.substringAfter("id=\"trend-data\">").substringBefore("</script>")
            assertEquals(listOf(sample), Json.decodeFromString<List<TrendRun>>(embedded))
        }
    }

    private val host = HostReport("Test/Arm64", 4, "java")
    private val hostile = "\"><script>alert(1)</script>"

    @Test
    fun `hostile text in a job, its plan, its cases and its files never reaches a page as markup`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), auth = Auth.Disabled)
        val store = Store(config.database)
        val spec = ExperimentSpec("e$hostile", description = "desc$hostile")
        val id = store.create("e$hostile", "ref$hostile", emptyList(), experiment = spec)
        store.plans.planRows(
            id,
            listOf(PlannedArm(Arm("arm$hostile", emptyMap()), "abcdef0")),
            listOf(Problem("collection$hostile", "problem$hostile")),
            listOf(Case(0, 0, null)),
            listOf(CaseCommand("true", 1L)),
        )
        config.jobDir(id).resolve("cases/0/out").toFile().mkdirs()
        // A file name cannot hold the script's closing tag; it carries an attribute that would run
        // instead.
        config
            .jobDir(id)
            .resolve("cases/0/out/out\"><b onmouseover=alert(1)>.txt")
            .toFile()
            .writeText("x")
        store.schedules.create("s", "main", spec.copy(name = "s"), 3600)
        val other =
            store.create(
                "f",
                "main",
                listOf("true" to 1L),
                experiment = ExperimentSpec("f", description = "d"),
            )
        testApplication {
            application { api(config, store, host) }
            for (path in
                listOf(
                    "/",
                    "/jobs/$id",
                    "/jobs/$id?commands",
                    "/compare?jobs=$id,$other",
                    "/jobs/$id/files",
                    "/trend",
                    "/references",
                    "/problem?collection=collection$hostile&problem=problem$hostile",
                    "/disk",
                    "/nodes",
                    "/jobs/$id/execution",
                )) {
                suspend fun readPage() =
                    client.get(
                        path
                            .replace("\"", "%22")
                            .replace("<", "%3C")
                            .replace(">", "%3E")
                            .replace(" ", "%20"),
                    ) {
                        header(HttpHeaders.Accept, "text/html")
                    }
                var response = readPage()
                // The root intentionally returns immediately while its first display snapshot
                // loads.
                // Check escaping on the populated page rather than racing the background refresh.
                if (path == "/")
                    withTimeout(5_000) {
                        while ("Queue snapshot is loading;" in response.bodyAsText()) {
                            delay(10)
                            response = readPage()
                        }
                    }
                assertEquals(HttpStatusCode.OK, response.status, path)
                val body = if (path == "/") client.queuePage() else response.bodyAsText()
                assertFalse(
                    "<script>alert(1)" in body || "<b onmouseover" in body,
                    "$path renders hostile text as markup",
                )
                // The pages that show the job do show its text, escaped: the check above is not
                // passing on an empty page.
                if (path == "/" || path == "/jobs/$id")
                    assertTrue("&lt;script&gt;alert(1)" in body, path)
            }
        }
    }

    @Test
    fun `errors answer browsers with a page and API clients with JSON, escaped either way`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), auth = Auth.Disabled)
        val store = Store(config.database)
        testApplication {
            application { api(config, store, host) }
            val page = client.get("/jobs/12345") { header(HttpHeaders.Accept, "text/html") }
            assertEquals(HttpStatusCode.NotFound, page.status)
            assertTrue("<h1>404" in page.bodyAsText())
            val json = client.get("/jobs/12345")
            assertEquals(HttpStatusCode.NotFound, json.status)
            assertEquals(
                ErrorBody("no such job 12345"),
                Json.decodeFromString(ErrorBody.serializer(), json.bodyAsText()),
            )
            val bad =
                client.get("/jobs/x%22%3E%3Cscript%3Ealert(1)%3C%2Fscript%3E") {
                    header(HttpHeaders.Accept, "text/html")
                }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertFalse("<script>" in bad.bodyAsText())
        }
    }
}
