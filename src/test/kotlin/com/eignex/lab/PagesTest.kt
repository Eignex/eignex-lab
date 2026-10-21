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

    /** A finished profileCompilation A/B whose two cases have compilation reports from the committed HotSpot log sample. */
    private fun instrumentedJob(config: Config, store: Store): Long {
        val spec = ExperimentSpec("compile-ab", listOf(mapOf("suite" to "s")), parallel = 1, profileCli = true, profileCompilation = "jfr+log")
        val id = store.create("compile-ab", "main", emptyList(), experiment = spec)
        val arms = listOf(PlannedArm(Arm("baseline", emptyMap()), "abc"), PlannedArm(Arm("candidate", emptyMap()), "def"))
        store.plan(id, arms, listOf(Problem("s", "p")), Experiments.cases(1, 2, emptyList()), listOf("true" to 1L, "true" to 1L))
        val sample = checkNotNull(javaClass.getResource("/compilation/hotspot-compilation.log")).readText()
        for (index in 0..1) {
            store.caseRecord(id, index, """{"kind":"optimize","feasible":true,"objective":3,"proven":true,"timeToBestMs":5,"budgetMs":1000}""")
            store.commandFinished(id, index, 0)
            val dir = Files.createDirectories(config.jobDir(id).resolve("cases/$index/profile-$index"))
            Files.writeString(dir.resolve(CliMeasurements.COMPILATION_LOG), if (index == 0) sample else sample.replace("stamp='0.956'", "stamp='0.953'"))
            CliMeasurements.writeManifest(dir, index, 1, "m$index", "lab", "solve", 0, "", null, macOs = false, compilation = "jfr+log")
            CompilationReports.analyzeAndWrite(dir, SourceProvenance(id, index), WorkerProvenance())
        }
        store.finish(id, Status.DONE)
        return id
    }

    @Test
    fun `an instrumented experiment's page says so and marks its arms`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val store = Store(config.database)
        val id = instrumentedJob(config, store)
        testApplication {
            application { api(config, store, host) }

            val page = client.get("/jobs/$id") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()

            assertContains(page, "<dt>instrumented</dt>")
            assertContains(page, "profileCompilation jfr+log")
            assertContains(page, "title=\"profileCompilation: times are no runtime measurement\">instrumented</span>")
            assertContains(client.get("/jobs/$id/files").bodyAsText(), "cases/1/profile-1/${CompilationReports.REPORT}")
        }
    }

    @Test
    fun `comparing an instrumented run with an uninstrumented one warns that their times measure the instrumentation`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val store = Store(config.database)
        val instrumented = instrumentedJob(config, store)
        val plain = store.create("plain", "main", emptyList(), experiment = ExperimentSpec("plain", listOf(mapOf("suite" to "s"))))
        store.plan(plain, listOf(PlannedArm(Arm("baseline", emptyMap()), "abc")), listOf(Problem("s", "p")), Experiments.cases(1, 1, emptyList()), listOf("true" to 1L))
        store.caseRecord(plain, 0, """{"kind":"optimize","feasible":true,"objective":3,"proven":true,"timeToBestMs":5,"budgetMs":1000}""")
        testApplication {
            application { api(config, store, host) }

            val page = client.get("/compare?jobs=$plain,$instrumented").bodyAsText()

            assertContains(page, "This mixes instrumented runs")
        }
    }

    @Test
    fun `an analysis is queued on the local machine for a profiled job's captures and refused once they were trimmed`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val store = Store(config.database)
        val target = instrumentedJob(config, store)
        testApplication {
            application { api(config, store, host) }
            suspend fun analyze() = client.post("/experiments") {
                contentType(ContentType.Application.Json)
                setBody("""{"analyze": {"job": $target, "kind": "compilation"}}""")
            }

            val queued = analyze()
            Files.writeString(config.jobDir(target).resolve(JobTrim.MARKER), "1 2\n")
            val refused = analyze()

            assertEquals(HttpStatusCode.Created, queued.status, queued.bodyAsText())
            val id = Json.parseToJsonElement(queued.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content.toLong()
            val job = store.job(id)!!
            assertEquals("analyze-$target", job.name)
            assertEquals(Experiments.LAB_HOST, job.experiment?.host)
            assertEquals(AnalyzeSpec(target), job.experiment?.analyze)
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertContains(refused.bodyAsText(), "was trimmed")
            assertEquals(HttpStatusCode.OK, client.get("/") { header(HttpHeaders.Accept, "text/html") }.status)
            val page = client.get("/jobs/$id") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
            assertContains(page, "retained captures of job <a href=\"/jobs/$target\">$target</a>")
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
    fun `a trend still computing its points says so and reloads itself as they arrive`() {
        val computing = trendPage("sweep", emptyList(), "https://example.com/repo", earlier = 0, all = true, computing = 4)
        assertContains(computing, "Computing its runs; they appear here as they are ready.")
        assertContains(computing, "<body data-refresh")
        assertContains(computing, "location.reload()")
        assertFalse("current experiment" in computing)

        assertContains(trendPage("sweep", emptyList(), "https://example.com/repo", all = true), "No run of this schedule has results yet.")
        val current = trendPage("sweep", emptyList(), "https://example.com/repo", earlier = 3)
        assertContains(current, "No run of this schedule's current experiment has results yet.")
        assertFalse("<body data-refresh" in current)
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
            assertEquals("auto", body["host"]!!.jsonPrimitive.content)
            assertContains(client.post("/jobs/1/machines") {
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"machines": 2}""")
            }.bodyAsText(), "ignored")
        }
    }

    @Test
    fun `an operator's host override takes local and cloud, stored as lab and aws, and the older names still`() {
        val config = lab(cores = 8, aws = "vcpuQuota=32\n")
        val store = Store(config.database)
        val id = store.create("e", "0123456789abcdef", emptyList(), 1, 0,
            ExperimentSpec("e", listOf(mapOf("suite" to "s")), base = mapOf("ref" to "0123456789abcdef"), description = "d"))
        testApplication {
            application { api(config, store, host) }
            suspend fun place(to: String) = client.post("/jobs/$id/host") {
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"host": "$to"}""")
            }

            val cloud = place("cloud")
            assertEquals(HttpStatusCode.OK, cloud.status)
            assertContains(cloud.bodyAsText(), "placed on the cloud: a cloud node runs it")
            assertEquals(Experiments.AWS_HOST, store.job(id)!!.experiment!!.host)
            assertContains(place("local").bodyAsText(), "placed locally")
            assertEquals(Experiments.LAB_HOST, store.job(id)!!.experiment!!.host)
            assertEquals(HttpStatusCode.OK, place("aws").status)
            assertEquals(Experiments.AWS_HOST, store.job(id)!!.experiment!!.host)
            assertContains(place("mac").bodyAsText(), "host must be local, cloud or auto")
        }
        assertEquals(listOf("lab", "aws", "auto", "lab", "aws"),
            listOf("local", "cloud", "auto", "lab", "aws").map(Experiments::storedHost))
        assertEquals(listOf("local", "cloud", "auto"), listOf("lab", "aws", "auto").map(Experiments::hostWord))
    }

    @Test
    fun `a batch of an active experiment is refused, and a cancelled one no longer counts`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val store = Store(config.database)
        fun spec(name: String, suite: String) = """{"name": "$name", "description": "d", "problems": {"suite": "$suite"},
            "grid": {"ref": ["0123456789abcdef", "fedcba9876543210"]}, "seeds": [1, 2], "repeats": 3}"""
        testApplication {
            application { api(config, store, host) }
            suspend fun submit(body: String) = client.post("/experiments") {
                header(HttpHeaders.ContentType, "application/json")
                setBody(body)
            }

            assertEquals(HttpStatusCode.Created, submit(spec("ab-batch1", "a")).status)
            val batch = submit(spec("ab-batch2", "b"))
            val other = submit(spec("ab-other", "b").replace("\"repeats\": 3", "\"repeats\": 5"))
            assertEquals(HttpStatusCode.Created, other.status)
            store.requestCancel(1)
            val after = submit(spec("ab-all", "a,b"))

            assertEquals(HttpStatusCode.Conflict, batch.status)
            val message = batch.bodyAsText()
            assertContains(message, "job 1 (ab-batch1)")
            assertContains(message, "submit one experiment that covers all the problems")
            assertContains(message, "lab cases 1")
            assertEquals(HttpStatusCode.Created, after.status)
            assertEquals(listOf("ab-other", "ab-all"), store.active(withCommands = false).map { it.name })
        }
    }

    @Test
    fun `a job asking for more than the local machine has is cut down to it, and the response and the job page say so`() {
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
            assertEquals(listOf("capped: parallel=8 was asked, capped at the local machine's 6", "capped: processors=12 was asked, capped at the local machine's 8 cores"), notes)
            assertContains(jobPage(config, job, failedOnly = false), "processors=12 was asked, capped at the local machine's 8 cores")
        }
    }

    @Test
    fun `the queue page puts local-only jobs first at equal priority only while the report shows a node`() {
        val config = lab()
        val store = Store(config.database)
        store.create("older-auto", "main", emptyList(), experiment = ExperimentSpec("older-auto", listOf(mapOf("suite" to "s")), host = Experiments.AUTO_HOST))
        store.create("local-only", "main", emptyList(), experiment = ExperimentSpec("local-only", listOf(mapOf("suite" to "s")), host = Experiments.LAB_HOST))
        val node = NodeReport("i-1", "c7i.2xlarge", "spot", "idle")
        testApplication {
            application { api(config, store, host) }
            suspend fun first(report: NodePoolReport): String {
                writeReport(config, report)
                val index = client.get("/") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
                return if (index.indexOf("local-only") < index.indexOf("older-auto")) "local-only" else "older-auto"
            }

            assertEquals("local-only", first(NodePoolReport(now(), 0.0, 0, listOf(node))))
            assertEquals("local-only", first(NodePoolReport(now(), 0.0, 1, emptyList())))
            assertEquals("older-auto", first(NodePoolReport(now(), 0.0, 0, emptyList())))
            // A report the worker has not rewritten for minutes says nothing about the nodes now.
            assertEquals("older-auto", first(NodePoolReport(now() - 10 * 60_000L, 0.0, 0, listOf(node))))
        }
    }

    /** A lab of [cores] cores, with AWS settings when [aws] is not null (extra `aws.properties` lines). */
    private fun lab(cores: Int = 4, aws: String? = ""): Config {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null, cores = cores)
        if (aws != null) {
            config.dataDir.resolve("aws").toFile().mkdirs()
            config.dataDir.resolve("aws/aws.properties").toFile()
                .writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k.pem\nsecurityGroup=sg-1\n$aws")
        }
        return config
    }

    private fun writeReport(config: Config, report: NodePoolReport) =
        config.dataDir.resolve("aws/nodes.json").toFile().writeText(Json.encodeToString(NodePoolReport.serializer(), report))

    /** A job of 0.1 core-hours a case (6-minute timeout on one core), [cases] of them planned, [done] of them done,
     *  four at once. */
    private fun work(id: Long, host: String, cases: Int, done: Int = 0, status: Status = Status.QUEUED, priority: Int = 0) = Job(
        id, "e$id", "main", status = status, cancelRequested = false, setupDone = true, createdAt = 0, parallel = 4, priority = priority,
        experiment = ExperimentSpec("e", listOf(mapOf("suite" to "s")), base = mapOf("timeout" to "360000"), host = host),
        counts = CommandCounts(cases, done, 0, 0),
    )

    private fun summaryOf(config: Config, jobs: List<Job>) =
        workSummary(config, jobs, jobs.associate { it.id to Estimates.estimate(it, { null }, config.cores)!! })

    @Test
    fun `without cloud settings the work summary shows only the work and the local machine's time to drain it`() {
        val config = lab(aws = null)

        val summary = summaryOf(config, listOf(work(1, Experiments.LAB_HOST, 10), work(2, Experiments.LAB_HOST, 30, done = 10)))

        assertContains(summary, "≈3.0 core-h")
        assertContains(summary, "~45 min for the local machine alone")
        assertFalse("Cloud" in summary)
    }

    @Test
    fun `the work summary splits local-only, toward-cloud and below-the-floor work`() {
        val config = lab(aws = "nodeMinPriority=1\n")
        writeReport(config, NodePoolReport(now(), 0.0, 0, emptyList()))

        val summary = summaryOf(config, listOf(work(1, Experiments.LAB_HOST, 10), work(2, Experiments.AUTO_HOST, 20, priority = 1),
            work(3, Experiments.AUTO_HOST, 40), work(4, Experiments.AWS_HOST, 50, done = 20, status = Status.RUNNING)))

        assertContains(summary, "≈10 core-h")
        // Everything but the AWS job's 3 core-hours, on four cores.
        assertContains(summary, "~1 h 45 min for the local machine alone")
        assertContains(summary, "local only 1.0</span>")
        assertContains(summary, "local or cloud, toward cloud 2.0</span>")
        assertContains(summary, "local or cloud below the priority floor 4.0</span>")
        assertContains(summary, "cloud 3.0</span>")
    }

    @Test
    fun `below the threshold the gauge says how far the pressure is from the first node`() {
        val config = lab(cores = 10)
        writeReport(config, NodePoolReport(now(), 0.8, 0, emptyList(), threshold = 10.0, nextThreshold = 10.0,
            sustainMs = 600_000, cooldownMs = 300_000, blocker = LaunchGate.BELOW))

        val summary = summaryOf(config, listOf(work(1, Experiments.AUTO_HOST, 8)))

        assertContains(summary, "style=\"width:8.0%\"")
        assertContains(summary, "0.8 of 10 core-h for the first node")
        assertContains(summary, "no node: below threshold")
        assertContains(summary, "cloud nodes: none")
        assertFalse("over for" in summary)
    }

    @Test
    fun `over the threshold the gauge says how long it has held against the sustain time`() {
        val config = lab(cores = 10)
        val at = now()
        writeReport(config, NodePoolReport(at, 45.0, 0, listOf(
            NodeReport("i-1", "c7i.2xlarge", "spot", "running job 7", 7),
            NodeReport("i-2", "c7i.4xlarge", "on-demand", "idle"),
        ), threshold = 10.0, nextThreshold = 40.0, overSince = at - 4 * 60_000, lastLaunch = at - 30 * 60_000,
            sustainMs = 600_000, cooldownMs = 300_000, blocker = "waiting 6 min more"))

        val summary = summaryOf(config, listOf(work(1, Experiments.AUTO_HOST, 450)))

        assertContains(summary, "<i class=\"over\" style=\"width:100.0%\">")
        assertContains(summary, "45 of 40 core-h for node 3")
        assertContains(summary, "over for 4 of 10 min")
        assertContains(summary, "no node: waiting 6 min more")
        assertFalse("cooldown" in summary)
        assertContains(summary, "cloud nodes: 2 (")
        assertContains(summary, "2xlarge spot</span> running <a href=\"/jobs/7\">job 7</a>")
        assertContains(summary, "4xlarge on-demand</span> idle")
    }

    @Test
    fun `the gauge shows the cooldown left after a launch`() {
        val config = lab(cores = 10)
        val at = now()
        writeReport(config, NodePoolReport(at, 12.0, 1, emptyList(), threshold = 10.0, nextThreshold = 20.0,
            lastLaunch = at - 2 * 60_000, sustainMs = 600_000, cooldownMs = 300_000, blocker = LaunchGate.BELOW))

        val summary = summaryOf(config, listOf(work(1, Experiments.AUTO_HOST, 120)))

        assertContains(summary, "12 of 20 core-h for node 2")
        assertContains(summary, "cooldown 3 min left")
        assertContains(summary, "1 launching")
    }

    @Test
    fun `a launch held back by the quota or a lab update says so`() {
        val config = lab(cores = 10)
        for (blocker in listOf(LaunchGate.QUOTA_FULL, LaunchGate.UPDATE_WAITING, LaunchGate.AT_MAX)) {
            writeReport(config, NodePoolReport(now(), 15.0, 0, emptyList(), threshold = 10.0, nextThreshold = 10.0,
                overSince = now() - 20 * 60_000, sustainMs = 600_000, cooldownMs = 300_000, blocker = blocker))

            assertContains(summaryOf(config, emptyList()), "<span class=\"warn\">no node: $blocker</span>")
        }
    }

    @Test
    fun `a pool report from an older lab still shows the gauge, its threshold from the settings`() {
        val config = lab(cores = 10)
        config.dataDir.resolve("aws/nodes.json").toFile()
            .writeText("""{"updatedAt": ${now()}, "pressure": 3.25, "launching": 0, "nodes": [], "someday": 1}""")

        val summary = summaryOf(config, emptyList())

        assertContains(summary, "Work scheduled</b> <span class=\"muted\">none</span>")
        assertContains(summary, "3.3 of 10 core-h for the first node")
        assertFalse("no node:" in summary)
        assertContains(summaryOf(lab(), emptyList()), "Cloud: no pool report yet")
    }

    @Test
    fun `each active row shows its remaining estimate, and the local machine's time for work the local machine may run`() {
        val config = lab(cores = 4)
        writeReport(config, NodePoolReport(now(), 0.0, 0, emptyList()))
        val jobs = listOf(work(1, Experiments.AUTO_HOST, 10), work(2, Experiments.AWS_HOST, 50, done = 20, status = Status.RUNNING),
            work(3, Experiments.LAB_HOST, 0))

        val page = indexPage(config, host, jobs, emptyList(), emptyList(), 50, null, null, listOf(1L, 3L))

        assertContains(page, "≈1.0 core-h · ~15 min locally <span class=\"muted\">(upper bound)</span></div>")
        assertContains(page, "≈3.0 core-h <span class=\"muted\">(upper bound)</span></div>")
        // Unplanned: 20 problems by the pool's estimate, so 2 core-hours.
        assertContains(page, "≈2.0 core-h · ~30 min locally <span class=\"muted\">(guessed, upper bound)</span></div>")
        assertContains(page, "<div id=\"work\" data-live>")
        assertFalse("pressure" in page.substringBefore("<div id=\"work\""))
    }

    @Test
    fun `each estimate names where its case times came from`() {
        val config = lab(cores = 8, aws = null)
        val measured = work(1, Experiments.LAB_HOST, 100, done = 20, status = Status.RUNNING)
            .let { it.copy(counts = it.counts!!.copy(timed = 20, timedMs = 20 * 60_000)) }
        val unplanned = work(2, Experiments.LAB_HOST, 0)
        val history = { job: Job -> CaseHistory(6659, 977, 9_600.0).takeIf { job.id == 2L } }

        val page = indexPage(config, host, listOf(measured, unplanned), emptyList(), emptyList(), 50, null, null, listOf(2L), history)

        // 80 cases of a minute, four at once.
        assertContains(page, "≈1.3 core-h · ~20 min locally <span class=\"muted\">(measured)</span></div>")
        // 977 cases of 9.6 s, four at once: 2.6 core-h and 40 min.
        assertContains(page, "≈2.6 core-h · ~40 min locally <span class=\"muted\">(from run 6659)</span></div>")
        assertContains(page, "~1 h for the local machine alone")
    }

    @Test
    fun `the queue page renders hundreds of active jobs quickly`() {
        val config = lab(cores = 10)
        writeReport(config, NodePoolReport(now(), 0.0, 0, emptyList()))
        val jobs = (1L..500L).map { work(it, if (it % 2 == 0L) Experiments.AUTO_HOST else Experiments.LAB_HOST, 40, done = 5) }
        repeat(3) { indexPage(config, host, jobs, emptyList(), emptyList(), 50, null, null, jobs.map { it.id }) }

        val started = System.nanoTime()
        indexPage(config, host, jobs, emptyList(), emptyList(), 50, null, null, jobs.map { it.id })
        val ms = (System.nanoTime() - started) / 1_000_000

        assertTrue(ms < 200, "rendering took $ms ms")
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
