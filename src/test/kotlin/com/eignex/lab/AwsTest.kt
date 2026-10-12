package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AwsTest {
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
    fun `instances launch as spot unless the settings say on demand`() {
        val dir = Files.createTempDirectory("aws")
        val file = dir.resolve("aws.properties")
        file.toFile().writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k.pem\nsecurityGroup=sg-1\n")
        val spot = AwsConfig.load(file)!!
        file.toFile().appendText("market=on-demand\nspotFallback=false\n")
        val onDemand = AwsConfig.load(file)!!

        assertEquals(listOf(true, true, false, false), listOf(spot.spot, spot.spotFallback, onDemand.spot, onDemand.spotFallback))
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
    fun `the lab runner and the AWS worker each take only their own host's jobs`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val spec = ExperimentSpec("e", listOf(mapOf("suite" to "s")), description = "d")
        val onAws = store.create("aws", "main", emptyList(), experiment = spec.copy(host = Experiments.AWS_HOST), priority = 5)
        val onLab = store.create("lab", "main", emptyList(), experiment = spec)

        assertEquals(listOf(listOf(onLab), listOf(onAws)), listOf(store.queueOrder(), store.queueOrder(Experiments.AWS_HOST)))
        assertEquals(listOf(onLab, onAws), listOf(store.next()?.id, store.next(Experiments.AWS_HOST)?.id))
    }

    @Test
    fun `a queued job moves to AWS until it is planned`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val id = store.create("e", "main", emptyList(), experiment = ExperimentSpec("e", listOf(mapOf("suite" to "s")), description = "d"))

        val moved = store.setHost(id, Experiments.AWS_HOST, 2)
        val spec = store.job(id)?.experiment
        val planned = store.create("p", "main", listOf("true" to 10L), experiment = ExperimentSpec("p", listOf(mapOf("suite" to "s"))))

        assertEquals(listOf(true, Experiments.AWS_HOST, 2), listOf(moved, spec?.host, spec?.machines))
        assertEquals(false, store.setHost(planned, Experiments.AWS_HOST, null))
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
    fun `an AWS job's machine count changes until it ends`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val spec = ExperimentSpec("e", listOf(mapOf("suite" to "s")), host = Experiments.AWS_HOST, description = "d")
        val queued = store.create("e", "main", emptyList(), experiment = spec)
        val onLab = store.create("l", "main", emptyList(), experiment = spec.copy(host = Experiments.LAB_HOST))

        assertEquals(listOf(true, 3, false), listOf(store.setMachines(queued, 3), store.job(queued)?.experiment?.machines, store.setMachines(onLab, 3)))
    }
}
