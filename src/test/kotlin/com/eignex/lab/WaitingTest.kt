package com.eignex.lab

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaitingTest {
    private val host = HostReport("Test/Arm64", 4, "java", "engine", true, emptyList(), vendor = "Accelerate")
    private val aws = AwsConfig("eu-north-1", "p", "c7i.2xlarge", 4, 32, "k", "/k.pem", "sg", null, 4, 24, "2.9.7", null)

    /** A queued experiment of one-core cases, 6 minutes each, placed on [host]. */
    private fun job(id: Long, host: String = Experiments.AUTO_HOST, priority: Int = 0, name: String = "e$id", paused: Boolean = false,
                    cancelRequested: Boolean = false, status: Status = Status.QUEUED) = Job(
        id, name, "main", status = status, cancelRequested = cancelRequested, setupDone = false, createdAt = 0, priority = priority,
        paused = paused, experiment = ExperimentSpec("e", listOf(mapOf("suite" to "s")), base = mapOf("timeout" to "360000"), host = host),
        counts = CommandCounts(0, 0, 0, 0),
    )

    private val setup = LocalWork(7246, "klause-reference-bt@0cdb35141", -4, Waiting.SETUP, "selecting problems for selection 0", 0,
        doing = "setting up: selecting problems for selection 0")
    private val cases = LocalWork(7246, "klause-reference-bt@0cdb35141", -4, Waiting.CASES, null, null, 93, 1015, 4,
        doing = "running cases: 93 of 1015 done, 4 running")

    private fun pool(pressure: Double = 0.6, blocker: String? = LaunchGate.BELOW, nodes: List<NodeReport> = emptyList(), launching: Int = 0,
                     at: Long = now(), updateWaiting: Boolean = false) =
        NodePoolReport(at, pressure, launching, nodes, threshold = 8.0, nextThreshold = NodePool.nextThreshold(8.0, nodes.size + launching),
            sustainMs = 600_000, cooldownMs = 300_000, blocker = blocker, updateWaiting = updateWaiting)

    private fun view(localQueue: List<Long> = listOf(1, 2), localNext: Long? = null, local: LocalWork? = setup,
                     pool: NodePoolReport? = pool(), aws: AwsConfig? = this.aws, diskHeld: String? = null) =
        QueueView(localQueue, localNext, local, pool, aws, 8, emptyList(), diskHeld)

    @Test
    fun `a job behind the local machine's job in setup names it, the step and its place in the local queue`() {
        assertEquals(
            "local machine busy with job 7246 (setting up: selecting problems for selection 0); #2 in the local queue; " +
                "cloud: pressure 0.6 of 8.0 core-h for the first node, below the launch threshold",
            Waiting.waiting(job(2), view()),
        )
        // The first waits for the running job to end, which gives way only to a higher priority.
        assertEquals(
            "local machine busy with job 7246 (running cases: 93 of 1015 done, 4 running); #1 in the local queue, taken when it ends " +
                "(it gives way only to a priority above its -4)",
            Waiting.waiting(job(1, Experiments.LAB_HOST, priority = -4), view(local = cases)),
        )
    }

    @Test
    fun `the job the local machine takes next says so, and no node takes it`() {
        assertEquals("next for the local machine, which is free: it takes it within seconds",
            Waiting.waiting(job(1), view(localNext = 1, local = null)))
        assertEquals("next for the local machine: job 7246 (priority -4, running cases: 93 of 1015 done, 4 running) gives way to it before its next case",
            Waiting.waiting(job(1, priority = 2), view(localNext = 1, local = cases)))
        assertEquals("next for the local machine, after the lab update that waits for it",
            Waiting.waiting(job(1), view(localNext = 1, local = null, pool = pool(updateWaiting = true))))
        assertEquals("#2 in the local queue; the free local machine takes job 1 first; cloud: pressure 0.6 of 8.0 core-h for the first node, below the launch threshold",
            Waiting.waiting(job(2), view(localNext = 1, local = null)))
    }

    @Test
    fun `work below the priority floor launches no node, but an idle node takes it`() {
        val floor = aws.copy(nodeMinPriority = 0)
        assertEquals(
            "local machine busy with job 7246 (setting up: selecting problems for selection 0); #2 in the local queue; " +
                "below the cloud priority floor (priority -3 < 0): no node launches for it, but an idle node takes it",
            Waiting.waiting(job(2, priority = -3), view(aws = floor)),
        )
        // A job only a node runs has no floor.
        assertContains(Waiting.waiting(job(3, Experiments.AWS_HOST, priority = -3), view(aws = floor)), "below the launch threshold")
    }

    @Test
    fun `the launch gate's blocker says what holds the next node back`() {
        fun cloud(pool: NodePoolReport) = Waiting.waiting(job(5, Experiments.AWS_HOST), view(pool = pool))
        assertEquals("cloud: pressure 0.6 of 8.0 core-h for the first node, below the launch threshold", cloud(pool()))
        assertEquals("cloud: pressure 12 of 8.0 core-h for the first node, over the threshold: a node launches once it holds 6 min more",
            cloud(pool(12.0, "waiting 6 min more")))
        val node = NodeReport("i-1", "c7i.2xlarge", "spot", "running job 9", 9)
        assertEquals("cloud: 1 node up, none with room; pressure 20 of 16 core-h for node 2: the cooldown after the last launch holds the next for 3 min",
            cloud(pool(20.0, "cooldown 3 min", listOf(node))))
        assertEquals("cloud: 1 node up, none with room; the 32-vCPU quota is full: no node launches", cloud(pool(20.0, LaunchGate.QUOTA_FULL, listOf(node))))
        assertEquals("cloud: 1 node up, none with room; at maxInstances (4): no more nodes launch", cloud(pool(20.0, LaunchGate.AT_MAX, listOf(node))))
        assertEquals("cloud: 1 launching; pressure 9.0 of 16 core-h for node 2, below the launch threshold", cloud(pool(9.0, launching = 1)))
        assertEquals("cloud: a node with room takes it at its next poll", cloud(pool(nodes = listOf(node.copy(doing = "idle", job = null)))))
        // A node runs several jobs at once: one running a job with slots to spare takes it too, but not an on-demand
        // node kept for profiles.
        assertEquals("cloud: a node with room takes it at its next poll", cloud(pool(nodes = listOf(node.copy(slots = 4, busySlots = 1, free = true)))))
        assertContains(cloud(pool(20.0, "cooldown 3 min", listOf(node.copy(doing = "idle", job = null, free = true, market = AwsWorker.ON_DEMAND, ordinary = false)))),
            "none with room")
        assertEquals("cloud: no pool report yet", Waiting.waiting(job(5, Experiments.AWS_HOST), view(pool = null)))
        // The view keeps its own clock, so a slow run may round the age up a minute.
        assertContains(cloud(pool(at = now() - 10 * 60_000)), Regex("""\(pool report 1[01] min old\)"""))
        assertEquals("placed on the cloud, but the lab has no cloud settings", Waiting.waiting(job(5, Experiments.AWS_HOST), view(aws = null)))
    }

    @Test
    fun `paused, cancelled, local-only and held jobs say why`() {
        assertEquals("paused: no host takes it until it is resumed (lab resume 2)", Waiting.waiting(job(2, paused = true), view()))
        assertEquals("cancel requested: it ends without running", Waiting.waiting(job(2, cancelRequested = true), view()))
        assertEquals(
            "local machine busy with job 7246 (setting up: selecting problems for selection 0); #2 in the local queue; local only: a confirmation",
            Waiting.waiting(job(2, Experiments.LAB_HOST, name = "e${Confirm.SUFFIX}1"), view()),
        )
        assertEquals("the local machine holds its queue: 9 GB free; #2 in the local queue; cloud: pressure 0.6 of 8.0 core-h for the first node, below the launch threshold",
            Waiting.waiting(job(2), view(diskHeld = "9 GB free")))
        // A case larger than any size within the quota never goes to a node.
        val big = job(2).let { it.copy(experiment = it.experiment!!.copy(base = it.experiment.base + ("processors" to "64"))) }
        assertContains(Waiting.waiting(big, view()), "cloud: no size within the 32-vCPU quota holds its 64-core cases")
    }

    @Test
    fun `the local machine's job is in setup while it has a setup step or has not finished its setup`() {
        val dir = Files.createTempDirectory("job")
        val running = job(7, Experiments.LAB_HOST, status = Status.RUNNING)
        assertEquals("setting up", Waiting.localWork(running, dir).doing)
        SetupStep.write(dir, SetupStep.STARTING, at = 5)
        assertEquals(LocalWork(7, "e7", 0, Waiting.SETUP, null, 5, doing = "setting up"), Waiting.localWork(running, dir))
        SetupStep.write(dir, "building 0cdb35141", at = 9)
        val building = Waiting.localWork(running, dir)
        assertEquals("setting up: building 0cdb35141", building.doing)
        assertEquals(9L, building.stepSince)
        SetupStep.clear(dir)
        val planned = running.copy(setupDone = true, counts = CommandCounts(10, 3, 1, 2))
        assertEquals(LocalWork(7, "e7", 0, Waiting.CASES, null, null, 3, 10, 2, "running cases: 3 of 10 done, 2 running"),
            Waiting.localWork(planned, dir))
    }

    @Test
    fun `a retried setup step names itself while it runs and gives the name back after`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val runner = Runner(config, Store(config.database))
        val dir = config.jobDir(1).createDirectories()
        // A cloud node's steps, with no setup marker, leave nothing behind.
        runner.withRetry(1, "building on i-1") { assertNull(SetupStep.read(dir)) }
        SetupStep.write(dir, SetupStep.STARTING, at = 3)
        runner.withRetry(1, "selecting problems for selection 0") { assertEquals("selecting problems for selection 0", SetupStep.read(dir)?.first) }
        assertEquals(SetupStep.STARTING to 3L, SetupStep.read(dir))
    }

    /** A lab of 8 cores with cloud settings and a pool report below the threshold, a job set up locally, two waiting. */
    private fun lab(): Triple<Config, Store, List<Long>> {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null, cores = 8, minFreeBytes = 0)
        config.dataDir.resolve("aws").createDirectories()
        config.dataDir.resolve("aws/aws.properties").toFile().writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k.pem\nsecurityGroup=sg-1\n")
        config.dataDir.resolve("aws/nodes.json").toFile().writeText(Json.encodeToString(NodePoolReport.serializer(), pool()))
        val store = Store(config.database)
        fun spec(host: String) = ExperimentSpec("e", listOf(mapOf("suite" to "s")), base = mapOf("timeout" to "360000"), host = host,
            description = "a test")
        val reference = store.create("klause-reference-bt@0cdb35141", "main", emptyList(), 4, 5, spec(Experiments.LAB_HOST))
        val first = store.create("e7347", "main", emptyList(), 4, 0, spec(Experiments.AUTO_HOST))
        val second = store.create("e7348", "main", emptyList(), 4, 0, spec(Experiments.AUTO_HOST))
        assertEquals(reference, store.next()?.id)
        SetupStep.write(config.jobDir(reference).createDirectories(), "selecting problems for selection 0")
        return Triple(config, store, listOf(reference, first, second))
    }

    @Test
    fun `GET placement reports the pool, the local machine's job in setup and each job's reason`() {
        val (config, store, ids) = lab()
        val (reference, first, second) = ids
        testApplication {
            application { api(config, store, host) }

            val report = Json.parseToJsonElement(client.get("/placement").bodyAsText()).jsonObject
            assertEquals(setOf("at", "nodesUp", "cloud", "pool", "local", "jobs", "held"), report.keys)
            assertEquals(JsonNull, report.getValue("held"))
            assertEquals(false, report.getValue("nodesUp").jsonPrimitive.content.toBoolean())
            assertEquals("pressure 0.6 of 8.0 core-h for the first node, below the launch threshold · no nodes up",
                report.getValue("cloud").jsonPrimitive.content)
            val pool = report.getValue("pool").jsonObject
            assertEquals(0.6, pool.getValue("pressure").jsonPrimitive.double)
            assertEquals(LaunchGate.BELOW, pool.getValue("blocker").jsonPrimitive.content)
            val local = report.getValue("local").jsonObject
            assertEquals(reference, local.getValue("id").jsonPrimitive.long)
            assertEquals("setup", local.getValue("phase").jsonPrimitive.content)
            assertEquals("selecting problems for selection 0", local.getValue("step").jsonPrimitive.content)

            val jobs = report.getValue("jobs").jsonArray.map { it.jsonObject }
            assertEquals(listOf(reference, first, second), jobs.map { it.getValue("id").jsonPrimitive.long })
            val running = jobs[0]
            assertEquals("RUNNING", running.getValue("status").jsonPrimitive.content)
            assertEquals("on the local machine: setting up: selecting problems for selection 0", running.getValue("doing").jsonPrimitive.content)
            assertEquals(JsonNull, running.getValue("waiting"))
            val waiting = jobs[2]
            assertEquals(setOf("id", "name", "status", "placement", "host", "priority", "paused", "cancelRequested", "queuePosition",
                "localPosition", "estimate", "waiting", "doing", "hosts"), waiting.keys)
            assertEquals(JsonNull, waiting.getValue("doing"))
            assertEquals("local or cloud", waiting.getValue("placement").jsonPrimitive.content)
            assertEquals("auto", waiting.getValue("host").jsonPrimitive.content)
            assertEquals(2, waiting.getValue("queuePosition").jsonPrimitive.int)
            assertEquals(2, waiting.getValue("localPosition").jsonPrimitive.int)
            assertEquals(setOf("cases", "coreHours", "localHours", "note"), waiting.getValue("estimate").jsonObject.keys)
            assertEquals(
                "local machine busy with job $reference (setting up: selecting problems for selection 0); #2 in the local queue; " +
                    "cloud: pressure 0.6 of 8.0 core-h for the first node, below the launch threshold",
                waiting.getValue("waiting").jsonPrimitive.content,
            )

            val one = Json.parseToJsonElement(client.get("/placement/$second").bodyAsText()).jsonObject
            assertEquals(waiting, one)
            assertEquals(HttpStatusCode.NotFound, client.get("/placement/999").status)
            store.finish(first, Status.DONE)
            assertEquals("ended DONE", Json.parseToJsonElement(client.get("/placement/$first").bodyAsText()).jsonObject.getValue("doing").jsonPrimitive.content)

            // The queue page and the job page give the same reason.
            val reason = "#1 in the local queue, taken when it ends (it gives way only to a priority above its 5); cloud: pressure 0.6"
            // The pages leave the reason to `lab placement`: a queued job shows only its place in the queue.
            assertFalse(reason in client.get("/") { header(HttpHeaders.Accept, "text/html") }.bodyAsText())
            assertFalse(reason in client.get("/jobs/$second") { header(HttpHeaders.Accept, "text/html") }.bodyAsText())
        }
    }

    @Test
    fun `GET jobs with active lists every active job however old`() {
        val (config, store, ids) = lab()
        repeat(5) { store.finish(store.create("old$it", "main", listOf("true" to 1L)), Status.DONE) }
        testApplication {
            application { api(config, store, host) }
            val active = Json.parseToJsonElement(client.get("/jobs?active=true").bodyAsText()).jsonArray
            assertEquals(ids, active.map { it.jsonObject.getValue("id").jsonPrimitive.long })
        }
    }

    @Test
    fun `lab placement, lab show and lab ls explain the queue from a real server`() {
        val (config, store, ids) = lab()
        val (reference, first, second) = ids
        // Newer jobs, all ended, push the active ones out of the newest 30.
        repeat(40) { store.finish(store.create("later$it", "main", listOf("true" to 1L)), Status.DONE) }
        withServer(config, store) { port ->
            val summary = lab(port, "placement")
            val lines = summary.lines()
            assertEquals("cloud: pressure 0.6 of 8.0 core-h for the first node, below the launch threshold · no nodes up", lines[0])
            assertTrue(lines[1].startsWith("local: job $reference klause-reference-bt@0cdb35141: setting up: selecting problems for selection 0 (for "),
                lines[1])
            val row = lines.single { it.startsWith("$second\t") }.split('\t')
            assertEquals(listOf("$second", "QUEUED", "local or cloud", "p0", "local #2"), row.take(5))
            assertContains(row[6], "local machine busy with job $reference")

            val one = lab(port, "placement", "$first")
            assertContains(one, "$first e7347: QUEUED, local or cloud, priority 0, #1 in the queue, #1 in the local queue")
            assertContains(one, "waiting: local machine busy with job $reference (setting up: selecting problems for selection 0); #1 in the local queue")
            assertContains(one, "estimate: ≈")

            val json = Json.parseToJsonElement(lab(port, "placement", "--json")).jsonObject
            assertEquals(3, json.getValue("jobs").jsonArray.size)
            assertEquals(second, Json.parseToJsonElement(lab(port, "placement", "$second", "--json")).jsonObject.getValue("id").jsonPrimitive.long)

            val shown = Json.parseToJsonElement(lab(port, "show", "$second")).jsonObject
            assertEquals("local or cloud", shown.getValue("placement").jsonPrimitive.content)
            assertContains(shown.getValue("waiting").jsonPrimitive.content, "#2 in the local queue")
            val running = Json.parseToJsonElement(lab(port, "show", "$reference")).jsonObject
            assertFalse("waiting" in running)

            val listed = lab(port, "ls").lines().filter { it.isNotBlank() }.map { it.substringBefore('\t').toLong() }
            assertEquals(listOf(second, first, reference), listed.take(3))
            assertEquals(33, listed.size)
            assertEquals(listed.distinct(), listed)
        }
    }

    /** A real lab server on a free port for [block]. */
    private fun withServer(config: Config, store: Store, block: (Int) -> Unit) {
        val server = embeddedServer(Netty, port = 0) { api(config, store, host) }.start(wait = false)
        try {
            block(runBlocking { server.engine.resolvedConnectors().first().port })
        } finally {
            server.stop(0, 0)
        }
    }

    /** What `deploy/lab <args>` prints against the lab on [port]; it must exit 0. */
    private fun lab(port: Int, vararg args: String): String {
        val out = Files.createTempFile("lab", ".out")
        val process = ProcessBuilder(listOf("bash", Path.of("deploy/lab").toAbsolutePath().toString()) + args)
            .redirectOutput(out.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT)
            .apply { environment()["LAB_URL"] = "http://127.0.0.1:$port"; environment()["LAB_PASSWORD"] = "test" }
            .start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "lab ${args.joinToString(" ")} timed out")
        assertEquals(0, process.exitValue(), "lab ${args.joinToString(" ")}")
        assertTrue(out.exists())
        return Files.readString(out)
    }
}
