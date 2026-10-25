package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A `local or cloud` job shared by the local machine and cloud nodes, each pulling whole iterations from the store. */
class SharedJobsTest {
    private val config = Config(dataDir = Files.createTempDirectory("lab"))
    private val store = Store(config.database)
    private val runner = Runner(config, store)

    /** A host that records the cases it runs, each taking [caseMs]; the local machine yields to priorities, a node does not. */
    private open class FakeHost(override val id: String, private val caseMs: Long = 20, val ran: ConcurrentLinkedQueue<Int> = ConcurrentLinkedQueue()) : ExecutionHost {
        override val cores = 4
        override val maxParallel = 4
        override val corpus = ""
        override val yieldsToUpdates = false
        override val yieldsToPriority = id == ExecutionHost.LOCAL
        override fun worktree(sha: String) = ""
        override fun select(worktree: String, args: String) = emptyList<String>()
        override fun run(command: Command, dir: Path): Int {
            ran += command.index
            Thread.sleep(caseMs)
            return 0
        }
    }

    /** A planned `local or cloud` job of [problems] problems × two arms × three seeds, at [priority]. */
    private fun sharedJob(problems: Int, host: String = Experiments.AUTO_HOST, priority: Int = 0, parallel: Int = 2): Long {
        val spec = ExperimentSpec("ab", listOf(mapOf("suite" to "s")), grid = mapOf("x" to listOf("1", "2")), seeds = listOf(1, 2, 3),
            host = host, priority = priority)
        val id = store.create("ab", "main", emptyList(), parallel = parallel, priority = priority, experiment = spec)
        val cases = Experiments.cases(problems, 2, spec.seeds)
        store.plan(id, Experiments.arms(spec).map { PlannedArm(it, "sha") }, List(problems) { Problem("s", "p$it") }, cases,
            cases.map { "true" to 1_000L })
        return id
    }

    /** Run [host]'s part of job [id] as the runner and the cloud worker do: join its crew, pull its iterations, leave. */
    private fun part(id: Long, host: ExecutionHost): Thread = thread {
        runner.crews.join(id, host.id)
        var outcome: Dispatched? = null
        try {
            outcome = runner.dispatch(store.job(id)!!, config.jobDir(id), host, { store.claimIteration(id, host.id) }) { }
        } finally {
            if (host.id == ExecutionHost.LOCAL) store.setLocal(id, false)
            runner.leave(id, host.id, outcome)
        }
    }

    /** Whether every iteration of [id] ran all its arms on one host. */
    private fun unsplit(id: Long): Boolean =
        store.cases(id).groupBy { Triple(it.problem.problem, it.seed, it.repeat) }.values.all { it.map { case -> case.host }.distinct().size == 1 }

    @Test
    fun `the local machine and a node pull one job's iterations, never splitting one, and the last to leave ends it done`() {
        val id = sharedJob(problems = 4)
        assertEquals(id, store.next()?.id)
        val local = FakeHost(ExecutionHost.LOCAL)
        val node = FakeHost("i-1")

        listOf(part(id, local), part(id, node)).forEach { it.join() }

        assertEquals((0 until 24).toList(), (local.ran + node.ran).sorted())
        assertTrue(local.ran.isNotEmpty() && node.ran.isNotEmpty(), "both hosts ran cases: ${local.ran} ${node.ran}")
        assertTrue(unsplit(id))
        assertEquals(Status.DONE, store.job(id)!!.status)
        assertEquals(setOf(ExecutionHost.LOCAL, "i-1"), store.cases(id).mapNotNull { it.host }.toSet())
    }

    @Test
    fun `a node may help a job the local machine runs, and its unclaimed iterations are pressure`() {
        val id = sharedJob(problems = 4)
        val localOnly = sharedJob(problems = 4, host = Experiments.LAB_HOST, priority = -1)
        assertEquals(id, store.next()?.id)
        assertTrue(store.job(id)!!.local)
        store.claimIteration(id, ExecutionHost.LOCAL)

        val helpable = store.helpable()

        assertEquals(listOf(id to 22), helpable.map { (job, unclaimed) -> job.id to unclaimed })
        val demand = Demand(id, 22 * 0.1, 1, priority = 0)
        assertEquals(NodeWork.Help(id), NodePool.pick(NodeShape(4, false), listOf(demand), emptyList()))
        assertTrue(NodePool.pressure(listOf(demand)) > 0)
        assertFalse(localOnly in helpable.map { it.first.id })
    }

    @Test
    fun `the local machine joins a job nodes run once it is its next by priority`() {
        val id = sharedJob(problems = 2)
        val claimed = store.claimForNode(id)!!
        assertEquals(Experiments.AUTO_HOST, claimed.experiment!!.host)
        store.claimIteration(id, "i-1")
        val lower = sharedJob(problems = 1, priority = -1)

        assertEquals(listOf(id, lower), store.queueOrder())
        val joined = store.next()!!

        assertEquals(id, joined.id)
        assertEquals(Status.RUNNING, joined.status)
        assertTrue(joined.local)
        // Its own job is not in the local machine's queue while it runs it, nor one with nothing left to claim.
        assertEquals(listOf(lower), store.queueOrder())
        store.setLocal(id, false)
        assertEquals(listOf(id, lower), store.queueOrder())
        while (store.claimIteration(id, "i-1") != null) Unit
        assertEquals(listOf(lower), store.queueOrder())
    }

    @Test
    fun `among equal priorities the local machine starts a queued job before joining one nodes run`() {
        val crowded = sharedJob(problems = 3)
        store.claimForNode(crowded)!!
        store.claimIteration(crowded, "i-1")
        store.claimIteration(crowded, "i-2")
        val single = sharedJob(problems = 2)
        store.claimForNode(single)!!
        store.claimIteration(single, "i-3")
        val queued = sharedJob(problems = 1)
        val higher = sharedJob(problems = 2, priority = 1)
        store.claimForNode(higher)!!
        store.claimIteration(higher, "i-4")
        store.claimIteration(higher, "i-5")

        // Priority first, then the fewest hosts (a queued job has none), then age.
        assertEquals(listOf(higher, queued, single, crowded), store.queueOrder())
        assertEquals(listOf(higher, queued, single, crowded), store.queueOrder(nodesUp = true))
    }

    @Test
    fun `the local machine yields mid-job, its unstarted iterations go back whole, and the node finishes the job`() {
        val id = sharedJob(problems = 6, parallel = 1)
        assertEquals(id, store.next()?.id)
        val local = FakeHost(ExecutionHost.LOCAL, caseMs = 60)
        val node = FakeHost("i-1", caseMs = 60)
        val parts = listOf(part(id, local), part(id, node))
        Thread.sleep(200)
        val urgent = store.create("urgent", "main", emptyList(), priority = 5, experiment = ExperimentSpec("u", listOf(mapOf("suite" to "s"))))
        assertTrue(store.shouldYield(id))

        parts.forEach { it.join() }

        assertTrue(local.ran.size in 2..12 && local.ran.size % 2 == 0, "the local machine ran whole iterations: ${local.ran}")
        assertEquals((0 until 36).toList(), (local.ran + node.ran).sorted())
        assertTrue(unsplit(id))
        assertEquals(Status.DONE, store.job(id)!!.status)
        assertEquals(urgent, store.next()?.id)
    }

    @Test
    fun `a cancel stops every host and ends the job cancelled, its finished cases kept`() {
        val id = sharedJob(problems = 6, parallel = 1)
        store.next()
        val parts = listOf(part(id, FakeHost(ExecutionHost.LOCAL, caseMs = 50)), part(id, FakeHost("i-1", caseMs = 50)))
        Thread.sleep(200)

        store.requestCancel(id)
        parts.forEach { it.join() }

        val job = store.job(id)!!
        assertEquals(Status.CANCELLED, job.status)
        assertTrue(job.commands.none { it.status == Status.QUEUED || it.status == Status.RUNNING })
        assertTrue(job.commands.any { it.status == Status.DONE })
    }

    @Test
    fun `a pause stops every host after its started iterations and queues the job again, paused`() {
        val id = sharedJob(problems = 6, parallel = 1)
        store.next()
        val parts = listOf(part(id, FakeHost(ExecutionHost.LOCAL, caseMs = 50)), part(id, FakeHost("i-1", caseMs = 50)))
        Thread.sleep(200)

        store.setPaused(id, true)
        parts.forEach { it.join() }

        val job = store.job(id)!!
        assertEquals(listOf<Any>(Status.QUEUED, true, false), listOf(job.status, job.paused, job.local))
        assertTrue(job.commands.none { it.status == Status.RUNNING || it.status == Status.QUEUED && it.host != null })
        assertTrue(unsplit(id))
        assertNull(store.next())
    }

    @Test
    fun `a lost node gives its iterations back whole and the local machine finishes the job`() {
        val id = sharedJob(problems = 4, parallel = 1)
        store.next()
        val node = object : FakeHost("i-1", caseMs = 30) {
            override fun run(command: Command, dir: Path): Int {
                if (ran.size >= 3) throw HostLost("i-1 is gone (terminated, Server.SpotInstanceTermination)")
                return super.run(command, dir)
            }
        }
        val local = FakeHost(ExecutionHost.LOCAL, caseMs = 30)

        listOf(part(id, node), part(id, local)).forEach { it.join() }

        assertEquals(Status.DONE, store.job(id)!!.status)
        assertTrue(unsplit(id))
        // Each case's host says where its kept result ran: the node's one finished iteration, the rest the local machine's.
        assertEquals(24, store.cases(id).size)
        assertTrue(store.cases(id).count { it.host == "i-1" } <= 2)
    }

    @Test
    fun `local-only and profile jobs are never shared`() {
        val localOnly = sharedJob(problems = 2, host = Experiments.LAB_HOST, priority = 5)
        assertEquals(localOnly, store.next()?.id)
        val profile = store.create("p", "main", listOf("true" to 1L), experiment =
            ExperimentSpec("p", host = Experiments.AUTO_HOST, profile = ProfileMain("com.eignex.Main")))

        assertNull(store.claimForNode(localOnly))
        assertEquals(profile, store.claimForNode(profile)?.id)
        assertEquals(emptyList(), store.helpable())
        assertNull(store.next(exclude = emptyList()).takeIf { it?.id == profile })
        assertFalse(runner.shared(store.job(localOnly)!!))
    }

    @Test
    fun `at a restart a job taken whole before sharing is shared again, and a running shared job goes back whole`() {
        config.dataDir.resolve("aws").toFile().mkdirs()
        config.dataDir.resolve("aws/aws.properties").toFile().writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k.pem\nsecurityGroup=sg-1\n")
        val cloudy = Config(dataDir = config.dataDir)
        val id = sharedJob(problems = 2, host = Experiments.LAB_HOST)
        // What a runner before sharing left: an `auto` job the local machine took, one arm of its first iteration done.
        val spec = store.job(id)!!.experiment!!.copy(auto = true)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${config.database}").use { db ->
            db.prepareStatement("UPDATE jobs SET experiment = ? WHERE id = ?").use {
                it.setString(1, kotlinx.serialization.json.Json.encodeToString(ExperimentSpec.serializer(), spec))
                it.setLong(2, id)
                it.executeUpdate()
            }
        }
        store.next()
        store.commandStarted(id, 0)
        store.commandFinished(id, 0, 0)
        store.commandStarted(id, 1)

        Runner(cloudy, store).recoverShared()

        val job = store.job(id)!!
        assertEquals(listOf<Any?>(Experiments.AUTO_HOST, Status.QUEUED, false), listOf(job.experiment!!.host, job.status, job.local))
        assertTrue(job.commands.all { it.status == Status.QUEUED && it.host == null })
        assertEquals(listOf(0, 1), store.claimIteration(id, "i-1"))
    }

    @Test
    fun `the queue page, the job page and the placement report show each host's part of a shared job`() {
        val id = sharedJob(problems = 3)
        store.next()
        for ((holder, done) in listOf(ExecutionHost.LOCAL to true, "i-1" to true, "i-1" to false)) {
            val claimed = store.claimIteration(id, holder)!!
            claimed.forEach { store.commandStarted(id, it, holder) }
            if (done) claimed.forEach { store.commandFinished(id, it, 0) }
        }
        config.jobDir(id).toFile().mkdirs()
        config.jobDir(id).resolve(AwsWorker.INSTANCES_FILE).toFile().writeText("i-1 c7i.2xlarge spot\n")
        val job = store.job(id)!!
        val hosts = store.hostProgress(listOf(id))

        assertEquals(listOf(HostProgress("local", 2, 0), HostProgress("i-1", 2, 2)), hosts[id])
        val index = indexPage(config, HostReport("Test", 4, "java", "engine", false, emptyList()), listOf(job), emptyList(), emptyList(), 50,
            null, null, hosts = hosts)
        assertTrue("local + 1 node<br>local 2 done · i-1 2 done, 2 running" in index, index)
        val page = jobPage(config, job, failedOnly = false, hosts = hosts.getValue(id))
        assertTrue("<dt>hosts</dt><dd>local 2 done<br>i-1 2 done, 2 running" in page, page)
        val doing = Waiting.placement(config, job, QueueView(emptyList(), null, null, null, null, 8), null, { null }, hosts.getValue(id)).doing!!
        assertTrue(doing.endsWith("; by host: local 2 done, i-1 2 done, 2 running"), doing)
    }

    @Test
    fun `the placement report says a job runs on the local machine and its nodes`() {
        val id = sharedJob(problems = 2)
        val job = store.next()!!
        val pool = NodePoolReport(now(), 1.0, 0, listOf(NodeReport("i-1", "c7i.2xlarge", "spot", "running job $id", id),
            NodeReport("i-2", "c7i.2xlarge", "spot", "joining job $id", id)))
        val view = QueueView(emptyList(), null, Waiting.localWork(job, config.jobDir(id)), pool, null, 8)

        val doing = Waiting.placement(config, job, view, null, { null }).doing!!

        assertTrue(doing.startsWith("on local + 2 nodes: the local machine: "), doing)
        assertTrue(doing.endsWith("; 2 cloud nodes: c7i.2xlarge spot running job $id, c7i.2xlarge spot joining job $id"), doing)
    }

    /** One node's part of a job, sharing the node's [slots] with its other jobs, as [AwsHost] does; it records how many
     *  cases run at once on the node and of each job. */
    private class SlotHost(id: String, val jobId: Long, val slots: NodeSlots, val node: java.util.concurrent.atomic.AtomicInteger,
                           val peak: java.util.concurrent.atomic.AtomicInteger, caseMs: Long) : FakeHost(id, caseMs) {
        val mine = java.util.concurrent.atomic.AtomicInteger()
        val myPeak = java.util.concurrent.atomic.AtomicInteger()
        override fun acquire(cores: Int) = slots.acquire(jobId, NodePool.caseSlots(cores, slots.capacity))
        override fun release(cores: Int) = slots.release(NodePool.caseSlots(cores, slots.capacity))
        override fun run(command: Command, dir: Path): Int {
            peak.accumulateAndGet(node.incrementAndGet(), ::maxOf)
            myPeak.accumulateAndGet(mine.incrementAndGet(), ::maxOf)
            try {
                return super.run(command, dir)
            } finally {
                mine.decrementAndGet()
                node.decrementAndGet()
            }
        }
    }

    @Test
    fun `a node runs several jobs at once within its slots, each within its own parallel`() {
        val slots = NodeSlots(3)
        val running = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val narrow = sharedJob(problems = 1, parallel = 1)
        val wide = sharedJob(problems = 3, parallel = 4)
        listOf(narrow, wide).forEach { store.claimForNode(it)!! }
        val hosts = listOf(narrow, wide).map { SlotHost("i-1", it, slots, running, peak, caseMs = 250) }

        hosts.map { part(it.jobId, it) }.forEach { it.join() }

        assertEquals(listOf(Status.DONE, Status.DONE), listOf(narrow, wide).map { store.job(it)!!.status })
        assertEquals(3, peak.get(), "the node's three slots were all used, and never more")
        assertEquals(listOf(1, 3), hosts.map { it.myPeak.get() })
        assertEquals(0, slots.busy)
        assertTrue(unsplit(narrow) && unsplit(wide))
    }
}
