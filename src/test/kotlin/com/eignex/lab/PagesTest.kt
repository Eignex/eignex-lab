package com.eignex.lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `a profile stopped at its time limit says so and still lists what it left in OUT`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val store = Store(config.dataDir.resolve("lab.db"))
        val spec = ExperimentSpec("p", base = mapOf("timeout" to "36000000"),
            profile = ProfileMain("com.eignex.klause.bench.profile.Tool"))
        val id = store.create("p", "main", listOf("true" to 60L), experiment = spec)
        val dir = config.jobDir(id)
        dir.resolve("cases/0/out").toFile().mkdirs()
        dir.resolve("cases/0/out/profile.jfr").toFile().writeText("jfr")
        dir.resolve("0.exit").toFile().writeText("timeout\n")
        Termination.record(Termination.file(dir, 0), Termination.timeoutGraceful(7_320, 30_000))

        val page = jobPage(config, store.job(id)!!, failedOnly = false)

        assertContains(page, "Time limit: 2 h")
        assertContains(page, "Capped: a timeout of 10 h was asked, capped at 2 h.")
        assertContains(page, "stopped at its time limit")
        assertContains(page, "/jobs/$id/files/cases/0/out/profile.jfr")
    }

    @Test
    fun `arm pages show mixed provenance and missing legacy identities`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
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
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
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

            assertContains(index, "#1 in the queue")
            assertContains(index, "/jobs/$done?failed\">1 failed</a>")
            assertContains(index, "https://github.com/Eignex/klause/commit/0123456789abcdef")
            assertContains(job.bodyAsText(), "<code>false</code>")
            assertFalse(failed.contains("<code>true</code>"))
            assertTrue(json.contentType()?.match(ContentType.Application.Json) == true)
            assertContains(index, "/jobs/$queued")
        }
    }

    @Test
    fun `a case's termination reasons show under its exit and in the job's JSON`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val store = Store(config.database)
        val id = store.create("kills", "main", listOf("sleep 1" to 60L))
        store.commandFinished(id, 0, 137)
        val dir = Files.createDirectories(config.jobDir(id))
        Termination.record(Termination.file(dir, 0), Termination.SHUTDOWN, at = 1)
        Termination.record(Termination.file(dir, 0), Termination.external(137)!!, at = 2)
        testApplication {
            application { api(config, store, host) }

            val page = client.get("/jobs/$id") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
            val json = client.get("/jobs/$id").bodyAsText()

            assertContains(page, "exit 137</small><br><small class=\"muted\" title=\"1970-01-01T00:00:00.001Z\">shutdown:")
            assertContains(page, "external: exit 137 is a SIGKILL the lab did not send")
            val terminations = Json.parseToJsonElement(json).jsonObject.getValue("commands").jsonArray.single()
                .jsonObject.getValue("terminations").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("1 ${Termination.SHUTDOWN}", "2 ${Termination.external(137)}"), terminations)
        }
    }

    @Test
    fun `an experiment's page compares its arms problem by problem`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
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
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
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
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
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

    @Test
    fun `a running AWS case's phase reads as what the lab is doing`() {
        val backoff = CasePhase(CasePhase.Phase.RETRY_BACKOFF, 0, attempt = 2, attempts = 8, retryAt = 90_000)
        val transfer = CasePhase(CasePhase.Phase.TRANSFERRING_OUTPUT, 0, elapsedMs = 10_175)

        assertEquals("SSH dropped, waiting to retry for 1m, attempt 2 of 8 failed, next in 30s", phaseText(backoff, 60_000))
        assertEquals("transferring output for 5s, solve took 10s", phaseText(transfer, 5_000))
    }

    @Test
    fun `a submitted host and machine count are not followed, and the lab says it placed the job`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null, cores = 8)
        config.dataDir.resolve("aws").toFile().mkdirs()
        config.dataDir.resolve("aws/aws.properties").toFile()
            .writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k.pem\nsecurityGroup=sg-1\nvcpuQuota=32\n")
        val store = Store(config.database)
        testApplication {
            application { api(config, store, host) }

            val created = client.post("/experiments") {
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"name": "e", "description": "d", "problems": {"suite": "s"}, "base": {"ref": "0123456789abcdef"},
                    "host": "lab", "machines": 3}""")
            }
            val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            val spec = store.job(body["id"]!!.jsonPrimitive.content.toLong())!!.experiment!!

            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals(listOf(Experiments.AUTO_HOST, null), listOf(spec.host, spec.machines))
            assertContains(body["placement"]!!.jsonPrimitive.content, "host and machines ignored: the lab places jobs")
            assertContains(client.post("/jobs/1/machines") {
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"machines": 2}""")
            }.bodyAsText(), "ignored")
        }
    }

    @Test
    fun `a job asking for more than the Mac has is cut down to it, and the response and the job page say so`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null, cores = 8, maxParallel = 6)
        config.dataDir.resolve("aws").toFile().mkdirs()
        config.dataDir.resolve("aws/aws.properties").toFile()
            .writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k.pem\nsecurityGroup=sg-1\nvcpuQuota=32\n")
        val store = Store(config.database)
        testApplication {
            application { api(config, store, host) }

            val created = client.post("/experiments") {
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"name": "e", "description": "d", "problems": {"suite": "s"},
                    "base": {"ref": "0123456789abcdef", "processors": "12"}, "parallel": 8}""")
            }
            val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            val job = store.job(body["id"]!!.jsonPrimitive.content.toLong())!!
            val notes = body["notes"]!!.jsonArray.map { it.jsonPrimitive.content }

            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals(listOf(Experiments.AUTO_HOST, "8", "6"), listOf(job.experiment!!.host, job.experiment!!.base["processors"], "${job.parallel}"))
            assertEquals(listOf("capped: parallel=8 was asked, capped at the Mac's 6", "capped: processors=12 was asked, capped at the Mac's 8 cores"), notes)
            assertContains(jobPage(config, job, failedOnly = false), "processors=12 was asked, capped at the Mac's 8 cores")
        }
    }

    @Test
    fun `the queue's summary shows the AWS nodes, their sizes, markets and what each does`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        config.dataDir.resolve("aws").toFile().mkdirs()
        config.dataDir.resolve("aws/aws.properties").toFile().writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k.pem\nsecurityGroup=sg-1\n")
        assertEquals(null, nodesLine(config))
        val report = NodePoolReport(1, 3.25, 1, listOf(
            NodeReport("i-1", "c7i.2xlarge", "spot", "running job 7", 7),
            NodeReport("i-2", "c7i.4xlarge", "on-demand", "idle"),
        ))
        config.dataDir.resolve("aws/nodes.json").toFile().writeText(Json.encodeToString(NodePoolReport.serializer(), report))

        val line = nodesLine(config)!!

        assertContains(line, "AWS nodes: 2, 1 launching")
        assertContains(line, "2xlarge spot</span> running <a href=\"/jobs/7\">job 7</a>")
        assertContains(line, "4xlarge on-demand</span> idle")
        assertContains(line, "pressure 3.3 core-h")
    }

    @Test
    fun `a queued AWS job waits for a node unless its cancel was requested`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val spec = ExperimentSpec("e", listOf(mapOf("suite" to "s")), host = Experiments.AWS_HOST)
        fun job(id: Long, cancelRequested: Boolean) =
            Job(id, "e$id", "main", status = Status.QUEUED, cancelRequested = cancelRequested, setupDone = true, createdAt = 0, experiment = spec)

        val waiting = indexPage(config, host, listOf(job(1, false)), emptyList(), emptyList(), 50, null, null)
        val cancelling = indexPage(config, host, listOf(job(2, true)), emptyList(), emptyList(), 50, null, null)

        assertContains(waiting, "waiting for a node")
        assertContains(cancelling, "cancelling")
        assertFalse("waiting for a node" in cancelling)
    }
}
