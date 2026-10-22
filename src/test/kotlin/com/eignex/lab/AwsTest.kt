package com.eignex.lab

import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AwsTest {
    @Test
    fun `terminal output transfer receipts record incomplete custody without changing command exit`() {
        val dir = Files.createTempDirectory("aws-transfer")
        val case = dir.resolve("cases/0")
        Files.createDirectories(case)
        Files.writeString(dir.resolve("0.exit"), "0\n")

        recordOutputTransferFailure(case, "transfer", FileTransferFailure(255, "dropped"))

        assertEquals("incomplete\tstage=transfer\n", Files.readString(case.resolve("out.transfer-status")))
        assertEquals("dropped\n", Files.readString(case.resolve("out.transfer-error")))
        assertEquals("0\n", Files.readString(dir.resolve("0.exit")))
    }

    @Test
    fun `terminal receipt failures attempt both writes and preserve the primary transfer error`() {
        val names = listOf("out.transfer-status", "out.transfer-error")
        for (failed in listOf(listOf(names.first()), listOf(names.last()), names)) {
            val case = Files.createTempDirectory("aws-transfer")
            val primary = FileTransferFailure(255, "dropped")
            val reports = failed.associateWith { IOException("cannot write $it") }
            val attempted = ArrayList<String>()

            val observed = assertFailsWith<FileTransferFailure> {
                recordOutputTransferFailure(case, "unpack", primary) { path, text ->
                    val name = path.fileName.toString()
                    attempted += name
                    reports[name]?.let { throw it }
                    Files.writeString(path, text)
                }
            }

            assertSame(primary, observed)
            assertEquals(names, attempted)
            assertEquals(failed.map(reports::getValue), observed.suppressed.toList())
            for (name in names - failed.toSet()) {
                assertTrue(Files.isRegularFile(case.resolve(name)))
            }
        }
    }

    @Test
    fun `aws settings load with their defaults, and there are none without the file`() {
        val dir = Files.createTempDirectory("aws")
        val file = dir.resolve("aws.properties")

        assertNull(AwsConfig.load(file))
        file.toFile().writeText("region=eu-north-1\nkeyName=klause-lab\nkeyFile=/k.pem\nsecurityGroup=sg-1\n")
        val aws = AwsConfig.load(file)!!

        assertEquals(listOf("c7i.2xlarge", "4", "4", "klause-lab"), listOf(aws.instanceType, "${aws.maxInstances}", "${aws.cores}", aws.profile))
    }

    @Test
    fun `a job's problems are taken whole, in plan order, skipping cases already run`() {
        val cases = (0 until 6).map { i -> CaseResult(i, if (i == 2) Status.DONE else Status.QUEUED, Problem("s", "p${i / 2}"), "a") }
        val problems = ProblemQueue(cases)

        assertEquals(listOf(listOf(0, 1), listOf(3), listOf(4, 5), null), List(4) { problems.take() })
    }

    @Test
    fun `hosts sharing a job's problems run each case once, a problem's cases all on one host`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))
        val id = store.create("e", "main", List(12) { "true" to 1_000L }, parallel = 2)
        val cases = (0 until 12).map { i -> CaseResult(i, Status.QUEUED, Problem("s", "p${i / 3}"), "a") }
        val problems = ProblemQueue(cases)
        val runner = Runner(config, store)
        val ran = List(2) { java.util.concurrent.ConcurrentLinkedQueue<Int>() }
        val hosts = ran.map { log -> FakeHost(log) }

        val threads = hosts.map { host -> thread { runner.dispatch(store.job(id)!!, config.jobDir(id), host, problems::take) } }
        threads.forEach { it.join() }

        val byHost = ran.map { log -> log.map { it / 3 }.toSet() }
        assertEquals((0 until 12).toList(), ran.flatten().sorted())
        assertTrue(byHost[0].intersect(byHost[1]).isEmpty())
    }

    @Test
    fun `a host AWS takes back hands its cases back to the queue instead of failing the job`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))
        val id = store.create("e", "main", List(4) { "true" to 1_000L }, parallel = 2)
        val problems = ProblemQueue((0 until 4).map { i -> CaseResult(i, Status.QUEUED, Problem("s", "p${i / 2}"), "a") })
        val handedBack = mutableListOf<Int>()
        val lost = object : ExecutionHost by FakeHost(java.util.concurrent.ConcurrentLinkedQueue()) {
            override fun run(command: Command, dir: Path): Int = throw HostLost("i-1 is gone (terminated, Server.SpotInstanceTermination)")
        }

        val outcome = Runner(config, store).dispatch(store.job(id)!!, config.jobDir(id), lost, problems::take) { held ->
            handedBack += held
            problems.giveBack(held)
        }

        assertEquals(Dispatched.LOST, outcome)
        assertEquals(listOf(0, 1), handedBack.sorted())
        assertEquals(listOf(0, 1), problems.take())
        assertTrue(store.job(id)!!.commands.all { it.status == Status.QUEUED })
    }

    /** A job of [problems] problems × two arms × [seeds], planned with a command per case that does nothing. */
    private fun abJob(store: Store, problems: Int, seeds: List<Long> = emptyList(), parallel: Int = 2): Long {
        val spec = ExperimentSpec("ab", listOf(mapOf("suite" to "s")), grid = mapOf("x" to listOf("1", "2")), seeds = seeds)
        val id = store.create("ab", "main", emptyList(), parallel = parallel, experiment = spec)
        val cases = Experiments.cases(problems, 2, seeds)
        store.plan(id, Experiments.arms(spec).map { PlannedArm(it, "sha") }, List(problems) { Problem("s", "p$it") }, cases,
            cases.map { "true" to 1_000L })
        return id
    }

    @Test
    fun `hosts sharing an A-B experiment run every iteration's arms on one host`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))
        val id = abJob(store, problems = 4, seeds = listOf(1, 2))
        val problems = ProblemQueue(store.cases(id))
        val runner = Runner(config, store)
        val ran = List(2) { java.util.concurrent.ConcurrentLinkedQueue<Int>() }

        ran.map { log -> thread { runner.dispatch(store.job(id)!!, config.jobDir(id), FakeHost(log), problems::take) } }.forEach { it.join() }

        val hostOf = ran.withIndex().flatMap { (host, log) -> log.map { it to host } }.toMap()
        val iterations = store.cases(id).groupBy { Triple(it.problem.problem, it.seed, it.repeat) }.values
        assertEquals(8, iterations.size)
        assertTrue(iterations.all { iteration -> iteration.size == 2 && iteration.map { hostOf.getValue(it.index) }.distinct().size == 1 })
    }

    @Test
    fun `a host lost after one arm of an iteration hands the whole iteration back, its finished arm to run again`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))
        val id = abJob(store, problems = 1, parallel = 1)
        val problems = ProblemQueue(store.cases(id))
        val handedBack = mutableListOf<Int>()
        val lost = object : ExecutionHost by FakeHost(java.util.concurrent.ConcurrentLinkedQueue()) {
            override fun run(command: Command, dir: Path): Int {
                if (command.index == 1) throw HostLost("i-1 is gone (terminated, Server.SpotInstanceTermination)")
                dir.resolve("cases/0").toFile().mkdirs()
                dir.resolve("cases/0/record.json").toFile().writeText("{}")
                return 0
            }
        }

        val outcome = Runner(config, store).dispatch(store.job(id)!!, config.jobDir(id), lost, problems::take) { held ->
            handedBack += held
            problems.giveBack(held)
        }

        assertEquals(Dispatched.LOST, outcome)
        assertEquals(listOf(0, 1), handedBack.sorted())
        assertEquals(listOf(0, 1), problems.take())
        assertTrue(store.job(id)!!.commands.all { it.status == Status.QUEUED && it.exitCode == null })
        assertTrue(store.cases(id).all { it.record == null })
    }

    @Test
    fun `an iteration only part done is queued again whole, and finished ones keep their results`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val id = abJob(store, problems = 2)
        for (index in listOf(0, 2, 3)) {
            store.commandStarted(id, index)
            store.commandFinished(id, index, if (index == 3) 1 else 0)
            store.caseRecord(id, index, "{}")
        }

        assertEquals(emptyList(), store.requeueSplitIterations(id, among = listOf(2, 3)))
        assertEquals(listOf(0), store.requeueSplitIterations(id))
        assertEquals(listOf(Status.QUEUED, Status.QUEUED, Status.DONE, Status.FAILED), store.job(id)!!.commands.map { it.status })
        assertEquals(listOf(false, false, true, true), store.cases(id).map { it.record != null })
    }

    @Test
    fun `a host taking over a planned job writes its queued commands for itself and reruns a half-done iteration`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))
        val id = abJob(store, problems = 2)
        val spec = store.job(id)!!.experiment!!
        // What a node finished before it gave the job back: problem 0 whole, and one arm of problem 1.
        for (index in listOf(0, 1, 2)) {
            store.commandStarted(id, index)
            store.commandFinished(id, index, 0)
        }
        val mac = object : ExecutionHost by FakeHost(java.util.concurrent.ConcurrentLinkedQueue()) {
            override val corpus = "/mac/corpus"
            override fun worktree(sha: String) = "/mac/work/$sha"
        }

        Runner(config, store).adopt(store.job(id)!!, spec, mac)

        val commands = store.job(id)!!.commands
        assertEquals(listOf(Status.DONE, Status.DONE, Status.QUEUED, Status.QUEUED), commands.map { it.status })
        assertEquals(listOf(false, false, true, true), commands.map { "cd '/mac/work/sha/klause-bench'" in it.cmd && "/mac/corpus" in it.cmd })
        assertTrue(commands.drop(2).all { "out=\"\$JOB_DIR/cases/${it.index}\"" in it.cmd })
    }

    @Test
    fun `a planned job a node gives back goes local first, and its cancel ends it rather than queueing it`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        config.dataDir.resolve("aws").toFile().mkdirs()
        config.dataDir.resolve("aws/aws.properties").toFile().writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k.pem\nsecurityGroup=sg-1\n")
        val store = Store(config.database)
        val given = abJob(store, problems = 1)
        val cancelled = abJob(store, problems = 1)
        for (id in listOf(given, cancelled)) {
            store.release(id, Experiments.AWS_HOST)
            assertEquals(id, store.claimForNode(id)?.id)
        }
        store.requestCancel(cancelled)

        assertEquals(listOf(Status.QUEUED, Status.CANCELLED), listOf(given, cancelled).map { store.requeue(it) })
        assertEquals(true, Placement.release(store, config, given))
        assertEquals(Experiments.AUTO_HOST, store.job(given)!!.experiment!!.host)
        assertEquals(given, store.next()?.id)
        assertEquals(Experiments.LAB_HOST, store.job(given)!!.experiment!!.host)
    }

    @Test
    fun `the node pool's settings have their defaults, and the file can change them`() {
        val dir = Files.createTempDirectory("aws")
        val file = dir.resolve("aws.properties")
        file.toFile().writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k.pem\nsecurityGroup=sg-1\nmarket=spot\nspotFallback=false\n")
        val default = AwsConfig.load(file)!!
        file.toFile().appendText("onDemand=true\nnodeIdleMin=2\nnodeMinPriority=1\nnodeLaunchMacHours=4.5\nnodeSustainMin=1\nnodeCooldownMin=9\nnodeMaxVcpus=32\n")
        val set = AwsConfig.load(file)!!

        assertEquals(listOf<Any>(false, 15L, 5L, 0, 1.0, 10L, 5L, 16),
            with(default) { listOf(onDemand, spotWaitMin, nodeIdleMin, nodeMinPriority, nodeLaunchLocalHours, nodeSustainMin, nodeCooldownMin, nodeMaxVcpus) })
        assertEquals(listOf<Any>(true, 2L, 1, 4.5, 1L, 9L, 32),
            with(set) { listOf(onDemand, nodeIdleMin, nodeMinPriority, nodeLaunchLocalHours, nodeSustainMin, nodeCooldownMin, nodeMaxVcpus) })
        // The setting's agnostic name wins over its older one, `nodeLaunchMacHours`, still read above.
        file.toFile().appendText("nodeLaunchLocalHours=2.5\n")
        assertEquals(2.5, AwsConfig.load(file)!!.nodeLaunchLocalHours)
    }

    @Test
    fun `the pool's report carries the launch gate for the pages, and an old report still reads`() {
        val aws = AwsConfig("eu-north-1", "p", "c7i.2xlarge", 4, 32, "k", "/k.pem", "sg", null, 4, 24, "2.9.7", null,
            nodeSustainMin = 10, nodeCooldownMin = 5)
        val gate = LaunchGate()
        gate.shouldLaunch(1_000, 12.0, 0, 10.0, room = false, sustainMs = 600_000, cooldownMs = 300_000)
        val nodes = listOf(NodeReport("i-1", "c7i.2xlarge", "spot", "idle"))

        val report = AwsWorker.poolReport(2_000, 25.0, 1, nodes, 10.0, gate, aws, LaunchGate.AT_MAX)
        val read = Json.decodeFromString(NodePoolReport.serializer(), Json.encodeToString(NodePoolReport.serializer(), report))
        val old = Json.decodeFromString(NodePoolReport.serializer(), """{"updatedAt": 1, "pressure": 0.5, "launching": 0, "nodes": []}""")

        assertEquals(listOf<Any?>(10.0, 40.0, 1_000L, null, 600_000L, 300_000L, LaunchGate.AT_MAX),
            with(read) { listOf(threshold, nextThreshold, overSince, lastLaunch, sustainMs, cooldownMs, blocker) })
        assertEquals(listOf<Any?>(null, null, null, null), with(old) { listOf(threshold, nextThreshold, overSince, blocker) })
    }

    @Test
    fun `a node runs its own share of cases unless the job says how many, within its cores`() {
        val ssh = Ssh("i-1", "10.0.0.1", AwsConfig("eu-north-1", "p", "c7i.2xlarge", 4, 32, "k", "/k.pem", "sg", null, 4, 24, "2.9.7", null),
            Files.createTempDirectory("aws").resolve("known"))
        val unset = AwsHost("i-1", ssh, 8, "", parallel = 4) { false }
        val explicit = AwsHost("i-1", ssh, 8, "") { false }

        assertEquals(listOf(4, 4, 2, 8), listOf(unset.parallel(1), unset.parallel(6), explicit.parallel(2), explicit.parallel(20)))
    }

    private class FakeHost(private val log: java.util.Queue<Int>) : ExecutionHost {
        override val cores = 4
        override val maxParallel = 4
        override val corpus = ""
        override val yieldsToUpdates = false
        override val yieldsToPriority = false
        override fun worktree(sha: String) = ""
        override fun select(worktree: String, args: String) = emptyList<String>()
        override fun run(command: Command, dir: Path): Int {
            log += command.index
            Thread.sleep(20)
            return 0
        }
    }

    @Test
    fun `the local machine never takes an aws job, and a node never a lab one`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val spec = ExperimentSpec("e", listOf(mapOf("suite" to "s")), description = "d")
        val onAws = store.create("aws", "main", emptyList(), experiment = spec.copy(host = Experiments.AWS_HOST), priority = 5)
        val onLab = store.create("lab", "main", emptyList(), experiment = spec)

        assertEquals(listOf(listOf(onLab), listOf(onAws)), listOf(store.queueOrder(), store.nodeQueue().map { it.id }))
        assertEquals(null, store.claimForNode(onLab))
        assertEquals(listOf(onLab, onAws), listOf(store.next()?.id, store.claimForNode(onAws)?.id))
        assertEquals(null, store.peek())
    }

    @Test
    fun `a job gets the smallest instance with the cores one of its cases needs, within the quota`() {
        val aws = AwsConfig("eu-north-1", "p", "c7i.2xlarge", 4, 32, "k", "/k.pem", "sg", null, 4, 24, "2.9.7", null)

        // Up to nodeMaxVcpus (16 by default): no case needs more than the local machine's cores.
        assertEquals(listOf("c7i.2xlarge", "c7i.4xlarge"), aws.sizes.map { it.type })
        assertEquals(listOf("c7i.2xlarge", "c7i.2xlarge", "c7i.4xlarge", "c7i.4xlarge", null),
            listOf(1, 4, 5, 8, 9).map { aws.sizeFor(it)?.type })
        assertEquals("c7i.8xlarge", aws.copy(nodeMaxVcpus = 32).sizes.last().type)
    }

    @Test
    fun `an AWS job runs one case per two physical cores unless it says otherwise`() {
        val sizes = listOf(InstanceSize("c7i.large", 2), InstanceSize("c7i.2xlarge", 8), InstanceSize("c7i.4xlarge", 16))

        assertEquals(listOf(1, 2, 4), sizes.map { AwsWorker.defaultParallel(it) })
    }

    @Test
    fun `an AWS case's output comes back only when its record cannot explain it`() {
        val kept = listOf(
            AwsHost.keepsOutput(0, recorded = true, profileCli = false),
            AwsHost.keepsOutput(1, recorded = true, profileCli = false),
            AwsHost.keepsOutput(0, recorded = false, profileCli = false),
            AwsHost.keepsOutput(0, recorded = true, profileCli = true),
        )

        assertEquals(listOf(false, true, true, true), kept)
    }

    @Test
    fun `a profiling job's launcher is installed before the provenance manifest describes the build`() {
        val profiled = AwsWorker.finishWorktree("/w", profileCli = true)
        val plain = AwsWorker.finishWorktree("/w", profileCli = false)

        assertEquals(listOf(CliMeasurements.install("/w"), Experiments.captureProvenance("/w")), profiled)
        assertEquals(listOf(Experiments.captureProvenance("/w")), plain)
    }

    @Test
    fun `a job's problem queue counts the cases left and empties when the job fails`() {
        val problems = ProblemQueue((0 until 5).map { i -> CaseResult(i, Status.QUEUED, Problem("s", "p${i / 2}"), "a") })

        assertEquals(listOf(3, 5), listOf(problems.size, problems.cases))
        problems.take()
        assertEquals(3, problems.cases)
        problems.clear()
        assertEquals(listOf(true, null), listOf(problems.isEmpty(), problems.take()))
    }

    @Test
    fun `a profile's OUT comes back smallest first up to the cap, the rest listed as omitted`() {
        val case = Files.createTempDirectory("case").toFile()
        val out = case.resolve("out").apply { mkdirs() }
        out.resolve("small.json").writeText("x".repeat(10))
        out.resolve("sub").mkdirs()
        out.resolve("sub/medium.xml").writeText("y".repeat(50))
        out.resolve("huge.json").writeText("z".repeat(500))
        val process = ProcessBuilder("bash", "-c", AwsHost.packOut(case.path, 100) + " | tar -t").redirectErrorStream(true).start()
        val listed = process.inputStream.bufferedReader().readLines().toSet()

        assertEquals(0, process.waitFor())
        assertEquals(setOf("out/small.json", "out/sub/medium.xml", "out/omitted.tsv"), listed)
        assertEquals("500\tout/huge.json", out.resolve("omitted.tsv").readText().trim())
        case.deleteRecursively()
    }

    @Test
    fun `a node's compilation-profiled case records every compilation and brings its HotSpot log back gzipped`() {
        val dir = Files.createTempDirectory("aws")
        dir.resolve("aws.properties").toFile().writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k.pem\nsecurityGroup=sg-1\n")
        val scripts = ArrayList<String>()
        val ssh = object : Ssh("i-1", "10.0.0.1", AwsConfig.load(dir.resolve("aws.properties"))!!, dir.resolve("known")) {
            override fun start(script: String, mergeErrors: Boolean): Process {
                scripts += script
                val reply = if (scripts.size == 1) "exit=0\n----klause-lab-record----\n{\"elapsedMs\":5}" else "captured"
                return ProcessBuilder("printf", "%s\n", reply).start()
            }
        }
        val host = AwsHost("i-1", ssh, 4, "", profileCli = true, compilation = "jfr+log") { false }

        assertEquals(0, host.run(Command(7, "solve", 10, Status.RUNNING), dir))

        assertTrue("jdk.Compilation#threshold=0ms" in scripts.first() && "-XX:+LogCompilation" in scripts.first(), scripts.first())
        assertTrue(scripts.any { it.startsWith("gzip -1 -c -- ") && it.endsWith("/hotspot-compilation.log'") }, scripts.toString())
        val profile = Files.list(dir.resolve("cases/7")).use { it.toList() }.single { it.fileName.toString().startsWith("profile-") }
        for (name in listOf("cli.jfr", "resources.txt", "hotspot-compilation.log.gz", "measurement.json")) {
            assertTrue(Files.exists(profile.resolve(name)), name)
        }
        assertTrue("\"instrumented\":true" in profile.resolve("measurement.json").readText())
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `an AWS case reports executing, its retry backoff after SSH dropped, and its output transfer`() {
        val dir = Files.createTempDirectory("aws")
        dir.resolve("aws.properties").toFile().writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k.pem\nsecurityGroup=sg-1\n")
        val replies = ArrayDeque(listOf("Connection reset", "exit=1\n----klause-lab-record----\n{\"elapsedMs\":10175}", ""))
        val scripts = ArrayList<String>()
        val ssh = object : Ssh("i-1", "10.0.0.1", AwsConfig.load(dir.resolve("aws.properties"))!!, dir.resolve("known")) {
            override fun start(script: String, mergeErrors: Boolean): Process {
                scripts += script
                return ProcessBuilder("printf", "%s\n", replies.removeFirstOrNull().orEmpty()).start()
            }
        }
        val phases = ArrayList<CasePhase>()
        val host = AwsHost("i-1", ssh, 4, "", onPhase = { _, phase -> phases += phase },
            retry = Backoff(attempts = 3, baseMs = 0, maxMs = 0)) { false }

        val exit = host.run(Command(7, "solve", 10, Status.RUNNING), dir)

        assertTrue("KLAUSE_DEBUG=true" in scripts.first(), scripts.first())
        assertFalse("StartFlightRecording" in scripts.first() || "LogCompilation" in scripts.first(), scripts.first())
        assertEquals(1, exit)
        assertEquals(
            listOf(
                CasePhase.Phase.EXECUTING to 1, CasePhase.Phase.RETRY_BACKOFF to 1,
                CasePhase.Phase.EXECUTING to 2, CasePhase.Phase.TRANSFERRING_OUTPUT to 2,
            ),
            phases.map { it.phase to it.attempt },
        )
        assertEquals(3, phases[1].attempts)
        assertTrue(phases[1].retryAt != null)
        assertEquals(10175L, phases.last().elapsedMs)
        dir.toFile().deleteRecursively()
    }
}
