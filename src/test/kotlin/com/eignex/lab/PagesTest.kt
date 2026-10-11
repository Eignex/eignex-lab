package com.eignex.lab

import kotlinx.serialization.json.Json
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PagesTest {
    private val host = HostReport("Test/Arm64", 4, "java", "engine", true, emptyList(), vendor = "Accelerate")

    @Test
    fun `arm pages show mixed provenance and missing legacy identities`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val cases = listOf(
            """{"buildFingerprint":"build-a","validationPolicy":"source-v1"}""",
            """{"buildFingerprint":"build-b","validationPolicy":"reported-v1"}""",
            """{}""",
        ).mapIndexed { index, record ->
            CaseResult(index, Status.DONE, Problem("s", "p$index"), "a", null, Json.parseToJsonElement(record))
        }
        val arms = listOf(PlannedArm(Arm("a", emptyMap()), "abc"))

        val page = comparePage(config, emptyList(), arms, cases)

        assertContains(page, "mixed provenance")
        assertContains(page, "build-a")
        assertContains(page, "source-v1")
        assertContains(page, "records missing provenance: 1")
    }

    @Test
    fun `frozen polynomial real benchmarks are selectable in reference coverage`() {
        for (collection in listOf(
            "klause-bench/smoke-corpus/qfnra-v1/core/algebraic",
            "klause-bench/smoke-corpus/qfnra-source-canonical-v1/degeneracy",
        )) {
            val coverage = listOf(
                ReferenceCoverage(collection, "z3", 5, 5, 5, 1, 0),
                ReferenceCoverage("klause-bench/smoke-corpus/smtlib", "z3", 1, 1, 1, 0, 0),
            )

            val page = referencesPage(coverage, ReferenceFilter(), emptyList(), 0)

            assertContains(page, "5 results from")
            assertContains(page, "value=\"$collection\"")
            assertFalse(page.contains("value=\"klause-bench/smoke-corpus/smtlib\""))
        }
    }

    @Test
    fun `a commit links to github only for a github repository`() {
        assertEquals(
            "https://github.com/Eignex/klause/commit/abc",
            commitUrl("https://github.com/Eignex/klause.git", "abc"),
        )
        assertEquals("https://github.com/Eignex/klause/commit/abc", commitUrl("git@github.com:Eignex/klause.git", "abc"))
        assertEquals(null, commitUrl("/srv/git/klause.git", "abc"))
    }

    @Test
    fun `a ref the mirror lacks reads as an unknown ref`() {
        val error = "failed: git rev-parse --verify 'fix/x^{commit}': fatal: Needed a single revision"

        assertEquals("unknown ref: fix/x", shortError(error))
    }

    @Test
    fun `a browser gets pages and an api client gets json`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.database)
        val done = store.create("sweep", "main", listOf("true" to 10L, "false" to 10L))
        store.next()
        store.setup(done, "0123456789abcdef")
        store.commandStarted(done, 0)
        store.commandFinished(done, 0, 0)
        store.commandStarted(done, 1)
        store.commandFinished(done, 1, 1)
        store.finish(done, Status.DONE)
        val queued = store.create("waiting", "main", listOf("true" to 10L))
        testApplication {
            application { api(config, store, host) }

            val index = client.get("/") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
            val job = client.get("/jobs/$done") { header(HttpHeaders.Accept, "text/html") }
            val failed = client.get("/jobs/$done?failed") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
            val json = client.get("/jobs/$done")

            assertContains(index, "#1 in the Mac queue")
            assertContains(index, "/jobs/$done?failed\">1 failed</a>")
            assertContains(index, "https://github.com/Eignex/klause/commit/0123456789abcdef")
            assertContains(job.bodyAsText(), "<code>false</code>")
            assertFalse(failed.contains("<code>true</code>"))
            assertTrue(json.contentType()?.match(ContentType.Application.Json) == true)
            assertContains(index, "/jobs/$queued")
        }
    }

    @Test
    fun `an experiment's page compares its arms problem by problem`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.database)
        val spec = ExperimentSpec("ab", listOf(mapOf("suite" to "s")))
        val id = store.create("ab", "main", emptyList(), experiment = spec)
        val arms = listOf(PlannedArm(Arm("cp", emptyMap()), "abc"), PlannedArm(Arm("ls", emptyMap()), "abc"))
        store.plan(id, arms, listOf(Problem("s", "p")), Experiments.cases(1, 2, emptyList()), listOf("true" to 1L, "true" to 1L))
        store.caseRecord(id, 0, """{"kind":"optimize","feasible":true,"objective":3,"proven":true,"timeToBestMs":5,"budgetMs":1000}""")
        store.caseRecord(id, 1, """{"kind":"optimize","feasible":true,"objective":9,"timeToBestMs":5,"budgetMs":1000}""")
        testApplication {
            application { api(config, store, host) }

            val page = client.get("/jobs/$id") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()

            assertContains(page, "<td class=\"best\">3*")
            assertContains(page, "1 worse")
        }
    }

    @Test
    fun `two experiments compare side by side with each arm named by its job`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.database)
        val ids = listOf("a", "b").map { sha ->
            val id = store.create("sweep@$sha", sha, emptyList(), experiment = ExperimentSpec("sweep@$sha", listOf(mapOf("suite" to "s"))))
            store.plan(id, listOf(PlannedArm(Arm("base", emptyMap()), sha)), listOf(Problem("s", "p")), Experiments.cases(1, 1, emptyList()), listOf("true" to 1L))
            id
        }
        testApplication {
            application { api(config, store, host) }

            val page = client.get("/compare?jobs=${ids.joinToString(",")}").bodyAsText()

            assertContains(page, "<th>${ids[0]} base</th><th>${ids[1]} base</th>")
        }
    }

    @Test
    fun `deleting an ended job removes it and its files but a running one stays`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.database)
        val ended = store.create("old", "main", listOf("true" to 1L))
        store.next()
        store.finish(ended, Status.CANCELLED)
        val running = store.create("now", "main", listOf("true" to 1L))
        store.next()
        config.jobDir(ended).toFile().apply { mkdirs() }.resolve("0.out").writeText("x")
        testApplication {
            application { api(config, store, host) }

            val statuses = listOf(ended, running).map { client.post("/jobs/$it/delete").status }

            assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.Conflict), statuses)
            assertEquals(listOf(running), store.jobs().map { it.id })
            assertFalse(config.jobDir(ended).toFile().exists())
        }
    }

    @Test
    fun `a trend run without disagreements still carries its per-suite counts to the charts`() {
        val run = TrendRun(job = 1, sha = "abc", spec = "s", at = 0, finished = true, cases = 1, problems = 1,
            solved = Estimate(1.0, 1.0, 1.0), proven = 0.0, unsupported = 0, errors = 0,
            par2 = Estimate(1.0, 1.0, 1.0), suites = mapOf("hakank" to SuiteShare(1, 1.0)))

        val page = trendPage("sweep", listOf(run), "https://example.com/repo")

        assertContains(page, "\"disagreementsBySuite\":{}")
    }
}
