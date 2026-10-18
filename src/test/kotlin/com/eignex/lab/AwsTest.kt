package com.eignex.lab

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `the node pool's settings have their defaults, and the file can change them`() {
        val dir = Files.createTempDirectory("aws")
        val file = dir.resolve("aws.properties")
        file.toFile().writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k.pem\nsecurityGroup=sg-1\nmarket=spot\nspotFallback=false\n")
        val default = AwsConfig.load(file)!!
        file.toFile().appendText("onDemand=true\nnodeIdleMin=2\nnodeMinPriority=1\nnodeLaunchCoreHours=4.5\nnodeSustainMin=1\nnodeCooldownMin=9\nnodeMaxVcpus=32\n")
        val set = AwsConfig.load(file)!!

        assertEquals(listOf<Any>(false, 15L, 5L, 0, 1.0, 3L, 5L, 16),
            with(default) { listOf(onDemand, spotWaitMin, nodeIdleMin, nodeMinPriority, nodeLaunchCoreHours, nodeSustainMin, nodeCooldownMin, nodeMaxVcpus) })
        assertEquals(listOf<Any>(true, 2L, 1, 4.5, 1L, 9L, 32),
            with(set) { listOf(onDemand, nodeIdleMin, nodeMinPriority, nodeLaunchCoreHours, nodeSustainMin, nodeCooldownMin, nodeMaxVcpus) })
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
    fun `the Mac never takes an aws job, and a node never a lab one`() {
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

        assertEquals(listOf("c7i.2xlarge", "c7i.4xlarge", "c7i.8xlarge"), aws.sizes.map { it.type })
        assertEquals(listOf("c7i.2xlarge", "c7i.2xlarge", "c7i.4xlarge", "c7i.8xlarge", null),
            listOf(1, 4, 5, 16, 17).map { aws.sizeFor(it)?.type })
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
    fun `an AWS case reports executing, its retry backoff after SSH dropped, and its output transfer`() {
        val dir = Files.createTempDirectory("aws")
        dir.resolve("aws.properties").toFile().writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k.pem\nsecurityGroup=sg-1\n")
        val replies = ArrayDeque(listOf("Connection reset", "exit=1\n----klause-lab-record----\n{\"elapsedMs\":10175}", ""))
        val ssh = object : Ssh("i-1", "10.0.0.1", AwsConfig.load(dir.resolve("aws.properties"))!!, dir.resolve("known")) {
            override fun start(script: String, mergeErrors: Boolean): Process =
                ProcessBuilder("printf", "%s\n", replies.removeFirstOrNull().orEmpty()).start()
        }
        val phases = ArrayList<CasePhase>()
        val host = AwsHost("i-1", ssh, 4, "", onPhase = { _, phase -> phases += phase },
            retry = Backoff(attempts = 3, baseMs = 0, maxMs = 0)) { false }

        val exit = host.run(Command(7, "solve", 10, Status.RUNNING), dir)

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
