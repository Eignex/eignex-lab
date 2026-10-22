package com.eignex.lab

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StoreTest {
    private fun store() = Store(Files.createTempDirectory("lab").resolve("lab.db"))

    @Test
    fun `each case says where it ran, one run before the lab recorded that reading as its job's host`() {
        val store = store()
        fun job(host: String): Long {
            val spec = ExperimentSpec("e", listOf(mapOf("suite" to "s")), host = host)
            val id = store.create("e", "main", emptyList(), experiment = spec)
            store.plan(id, listOf(PlannedArm(Arm("a", emptyMap()), "sha")), listOf(Problem("s", "p")), Experiments.cases(1, 1, listOf(1, 2, 3)),
                List(3) { "true" to 1L })
            return id
        }
        val local = job(Experiments.LAB_HOST)
        val cloud = job(Experiments.AWS_HOST)
        for (id in listOf(local, cloud)) {
            store.commandStarted(id, 0)
            store.commandFinished(id, 0, 0)
            store.commandStarted(id, 1, "i-7", "echo rendered")
        }

        assertEquals(listOf("local", "i-7", null), store.cases(local).map { it.host })
        assertEquals(listOf("cloud", "i-7", null), store.cases(cloud).map { it.host })
        assertEquals(listOf(null, "i-7", null), store.job(local)!!.commands.map { it.host })
        assertEquals("echo rendered", store.job(local)!!.commands[1].cmd)
    }

    @Test
    fun `reclaiming a bounded profile retains its interrupted running allowance`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val store = Store(file)
        val spec = ExperimentSpec("bounded", profile = ProfileMain("com.eignex.klause.bench.profile.Tool", runTimeoutSec = 10))
        val id = store.create("bounded", "main", listOf("true" to 1L), experiment = spec)
        store.next()
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.prepareStatement("UPDATE jobs SET run_ms = 2000, running_since = ? WHERE id = ?").use { statement ->
                statement.setLong(1, now() - 1000)
                statement.setLong(2, id)
                statement.executeUpdate()
            }
        }

        val reclaimed = store.next()!!

        assertEquals(id, reclaimed.id)
        assertTrue(reclaimed.runMs >= 3000)
    }

    @Test
    fun `a cancel from a second process lands while the other one keeps writing`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val runner = Store(file)
        val api = Store(file)
        val id = runner.create("a", "main", List(200) { "true" to 10L })
        runner.next()
        val writer = thread {
            repeat(200) { index ->
                runner.commandStarted(id, index)
                runner.commandFinished(id, index, 0)
            }
        }

        val cancelled = api.requestCancel(id)
        writer.join()

        assertTrue(cancelled == CancelOutcome.REQUESTED && runner.cancelRequested(id))
    }

    @Test
    fun `a job left running by a crashed runner is resumed before a queued one`() {
        val store = store()
        val crashed = store.create("a", "main", listOf("true" to 10L))
        store.create("b", "main", listOf("true" to 10L))
        store.next()

        assertEquals(crashed, store.next()?.id)
    }

    @Test
    fun `an interrupted command is queued again and finished ones keep their result`() {
        val store = store()
        val id = store.create("a", "main", listOf("true" to 10L, "sleep 9" to 10L))
        store.next()
        store.commandStarted(id, 0)
        store.commandFinished(id, 0, 0)
        store.commandStarted(id, 1)

        store.requeueInterrupted(id)

        assertEquals(listOf(Status.DONE, Status.QUEUED), store.job(id)!!.commands.map { it.status })
    }

    @Test
    fun `cancelling a queued job cancels it without a runner`() {
        val store = store()
        val id = store.create("a", "main", listOf("true" to 10L))

        store.requestCancel(id)

        assertEquals(Status.CANCELLED, store.job(id)!!.status)
    }

    @Test
    fun `a running job whose cancel was requested is cancelled when requeued, never queued again`() {
        val store = store()
        val id = store.create("a", "main", listOf("true" to 10L, "true" to 10L))
        store.next()
        store.commandStarted(id, 0)
        store.commandFinished(id, 0, 0)
        store.commandStarted(id, 1)
        assertEquals(CancelOutcome.REQUESTED, store.requestCancel(id))
        store.requeueInterrupted(id)

        assertEquals(Status.CANCELLED, store.requeue(id))
        val job = store.job(id)!!
        assertEquals(listOf(Status.CANCELLED, Status.DONE, Status.CANCELLED), listOf(job.status) + job.commands.map { it.status })
        assertTrue(job.finishedAt != null && job.runningSince == null)
        assertEquals(listOf(null, null), listOf(store.next(), store.requeue(id)))
    }

    @Test
    fun `a queued job left with its cancel requested is found for the runner to end`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val store = Store(file)
        val stuck = store.create("a", "main", listOf("true" to 10L))
        val waiting = store.create("b", "main", listOf("true" to 10L))
        // What a runner from before left: requeued after its cancel was requested.
        DriverManager.getConnection("jdbc:sqlite:$file").use { it.createStatement().executeUpdate("UPDATE jobs SET cancel_requested = 1 WHERE id = $stuck") }

        assertEquals(listOf(stuck), store.queuedCancels())
        assertEquals(Status.CANCELLED, store.requeue(stuck))
        assertEquals(listOf(Status.CANCELLED, Status.QUEUED), listOf(stuck, waiting).map { store.job(it)!!.status })
        assertEquals(emptyList(), store.queuedCancels())
    }

    @Test
    fun `cancelling a finished job reports it as finished`() {
        val store = store()
        val id = store.create("a", "main", listOf("true" to 10L))
        store.next()
        store.finish(id, Status.DONE)

        assertEquals(CancelOutcome.FINISHED, store.requestCancel(id))
    }

    @Test
    fun `a running job's parallel limit can be changed`() {
        val store = store()
        val id = store.create("a", "main", listOf("true" to 10L), parallel = 2)
        store.next()

        store.setParallel(id, 4)

        assertEquals(4, store.parallel(id))
    }

    @Test
    fun `a profiling job retains serial execution when its parallel limit is edited`() {
        val store = store()
        val experiment = ExperimentSpec("profile", listOf(mapOf("suite" to "miplib2017")),
            parallel = 1, host = "aws", profileCli = true)
        val id = store.create("profile", "main", listOf("true" to 10L), parallel = 1, experiment = experiment)

        assertFailsWith<IllegalArgumentException> { store.setParallel(id, 6) }

        assertEquals(1, store.parallel(id))
    }

    @Test
    fun `a resource measured job retains serial execution when its parallel limit is edited`() {
        val store = store()
        val experiment = ExperimentSpec("resources", listOf(mapOf("suite" to "s")), parallel = 1, measureCliResources = true)
        val id = store.create("resources", "main", listOf("true" to 10L), parallel = 1, experiment = experiment)

        assertFailsWith<IllegalArgumentException> { store.setParallel(id, 6) }

        assertEquals(1, store.parallel(id))
    }

    @Test
    fun `a queued profiling job may move to the local machine like any other`() {
        val store = store()
        val experiment = ExperimentSpec("profile", listOf(mapOf("suite" to "miplib2017")),
            parallel = 1, host = "aws", profileCli = true)
        val id = store.create("profile", "main", emptyList(), parallel = 1, experiment = experiment)

        assertTrue(store.place(id, "lab"))

        assertEquals("lab", store.job(id)!!.experiment!!.host)
    }

    @Test
    fun `among equal priorities the series that ran longest ago goes first`() {
        val store = store()
        val ran = store.create("a@1", "main", listOf("true" to 10L))
        store.next()
        store.finish(ran, Status.DONE)
        val again = store.create("a@2", "main", listOf("true" to 10L))
        val waiting = store.create("b@1", "main", listOf("true" to 10L))

        val first = store.next()?.id
        store.finish(waiting, Status.DONE)

        assertEquals(listOf(waiting, again), listOf(first, store.next()?.id))
    }

    @Test
    fun `a cancelled job retried runs its unfinished cases again and keeps its finished ones`() {
        val store = store()
        val id = store.create("a", "main", List(3) { "true" to 10L })
        store.next()
        store.commandStarted(id, 0)
        store.commandFinished(id, 0, 0)
        store.cancelRemaining(id)
        store.finish(id, Status.CANCELLED)

        val retried = store.retry(id)
        val job = checkNotNull(store.job(id))

        assertEquals(listOf(true, Status.QUEUED, null), listOf(retried, job.status, job.finishedAt))
        assertEquals(listOf(Status.DONE, Status.QUEUED, Status.QUEUED), job.commands.map { it.status })
        assertEquals(listOf(false, id), listOf(store.retry(id), store.next()?.id))
    }

    @Test
    fun `the queue order is the order jobs are taken in`() {
        val store = store()
        val low = store.create("low", "main", listOf("true" to 10L))
        val high = store.create("high", "main", listOf("true" to 10L), priority = 5)

        assertEquals(listOf(high, low), store.queueOrder())
    }

    @Test
    fun `a stored record that does not parse reads as missing instead of failing`() {
        val store = store()
        val problems = listOf(Problem("s", "a"), Problem("s", "b"))
        val id = store.create("e", "main", emptyList(), experiment = ExperimentSpec("e", listOf(mapOf("suite" to "s"))))
        store.next()
        store.plan(id, listOf(PlannedArm(Arm("base", emptyMap()), "sha")), problems, Experiments.cases(2, 1, emptyList()), problems.map { "true" to 1L })
        store.caseRecord(id, 0, """{"kind":"satisfy","budgetMs":1000}""")
        store.caseRecord(id, 1, """{"kind":"satisfy","conTimeout, server 1.2.3.4 not responding.""")

        assertEquals(listOf(true, false), store.cases(id).map { it.record != null })
    }

    @Test
    fun `scoring cases keep what scoring reads and drop provenance, witnesses and unread stats`() {
        val store = store()
        val problems = listOf(Problem("s", "a"), Problem("s", "b"))
        val id = store.create("e", "main", emptyList(), experiment = ExperimentSpec("e", listOf(mapOf("suite" to "s"))))
        store.next()
        store.plan(id, listOf(PlannedArm(Arm("base", emptyMap()), "sha")), problems, Experiments.cases(2, 1, emptyList()), problems.map { "true" to 1L })
        store.caseRecord(id, 0, """{"kind":"optimize","objective":3,"proven":true,"buildFingerprint":"f","buildProvenance":{"x":1},""" +
            """"finalWitness":[1,2],"attribution":{"y":2},"stats":{"profile":"Sat","loadError":"none","nodes":"99"}}""")
        store.caseRecord(id, 1, """{"kind":"satisfy","conTimeout""")

        val records = store.scoringCases(id).map { it.record as? kotlinx.serialization.json.JsonObject }
        val first = records[0]!!

        assertEquals(setOf("kind", "objective", "proven", "buildFingerprint", "stats"), first.keys)
        assertEquals(setOf("profile", "loadError"), (first["stats"] as kotlinx.serialization.json.JsonObject).keys)
        assertEquals(Outcome.of(store.cases(id)[0].record), Outcome.of(first))
        assertEquals(null, records[1])
    }

    @Test
    fun `the highest priority queued job is taken first`() {
        val store = store()
        store.create("low", "main", listOf("true" to 10L))
        val high = store.create("high", "main", listOf("true" to 10L), priority = 5)

        assertEquals(high, store.next()?.id)
    }

    @Test
    fun `a paused queued job is passed over until it resumes`() {
        val store = store()
        val held = store.create("held", "main", listOf("true" to 10L))
        store.setPaused(held, true)
        val nextWhilePaused = store.next()?.id
        store.setPaused(held, false)

        assertEquals(listOf(null, held), listOf(nextWhilePaused, store.next()?.id))
    }

    @Test
    fun `a running job yields to a higher priority job that arrives`() {
        val store = store()
        val running = store.create("running", "main", listOf("true" to 10L, "true" to 10L))
        store.next()
        val before = store.shouldYield(running)
        store.create("urgent", "main", listOf("true" to 10L), priority = 1)

        assertEquals(listOf(false, true), listOf(before, store.shouldYield(running)))
    }

    @Test
    fun `a yielded job keeps its finished commands and is taken again`() {
        val store = store()
        val id = store.create("a", "main", listOf("true" to 10L, "true" to 10L))
        store.next()
        store.commandStarted(id, 0)
        store.commandFinished(id, 0, 0)
        assertEquals(Status.QUEUED, store.requeue(id))

        val resumed = store.next()

        assertEquals(listOf(Status.DONE, Status.QUEUED), resumed?.commands?.map { it.status })
    }

    @Test
    fun `a schedule keeps its experiment and remembers the commit it last queued`() {
        val store = store()
        val spec = ExperimentSpec("status", listOf(mapOf("suite" to "hakank")), parallel = 2, priority = -1)
        val id = store.createSchedule("status", "main", spec, 3600)
        store.scheduleRan(id, "abc123", 7L)

        val schedule = store.schedules().single()

        assertEquals(
            listOf(spec, "abc123", 7L, -1),
            listOf(schedule.experiment, schedule.lastSha, schedule.lastJob, schedule.priority),
        )
    }

    @Test
    fun `a page of jobs starts below the id it is given`() {
        val store = store()
        val ids = List(5) { store.create("j$it", "main", listOf("true" to 10L)) }

        assertEquals(listOf(ids[2], ids[1]), store.jobs(2, before = ids[3]).map { it.id })
    }

    @Test
    fun `a name keeps its own jobs and the ones its schedule queued`() {
        val store = store()
        val plain = store.create("sweep", "main", listOf("true" to 10L))
        val scheduled = store.create("sweep@abc123", "abc123", listOf("true" to 10L))
        store.create("sweeper", "main", listOf("true" to 10L))
        store.create("s_eep@abc123", "main", listOf("true" to 10L))

        assertEquals(listOf(scheduled, plain), store.jobs(name = "sweep").map { it.id })
    }

    @Test
    fun `the active jobs are the queued and running ones`() {
        val store = store()
        val running = store.create("a", "main", listOf("true" to 10L))
        val cancelled = store.create("b", "main", listOf("true" to 10L))
        val queued = store.create("c", "main", listOf("true" to 10L))
        store.next()
        store.requestCancel(cancelled)

        assertEquals(listOf(running, queued), store.active().map { it.id })
    }

    @Test
    fun `a planned experiment lists each case with its problem, arm and record`() {
        val store = store()
        val spec = ExperimentSpec("e", listOf(mapOf("suite" to "s")))
        val id = store.create("e", "main", emptyList(), experiment = spec)
        val arms = listOf(PlannedArm(Arm("a", emptyMap()), "sha-a"), PlannedArm(Arm("b", emptyMap()), "sha-b"))
        val cases = Experiments.cases(problems = 1, arms = 2, seeds = emptyList())
        store.plan(id, arms, listOf(Problem("s", "p")), cases, listOf("true" to 10L, "true" to 10L))
        store.caseRecord(id, 1, """{"feasible":true}""")

        val listed = store.cases(id)

        assertEquals(
            listOf(Triple("a", "p", false), Triple("b", "p", true)),
            listed.map { Triple(it.arm, it.problem.problem, it.record != null) },
        )
    }

    @Test
    fun `an experiment job keeps its spec`() {
        val store = store()
        val spec = ExperimentSpec("e", listOf(mapOf("suite" to "s")), grid = mapOf("engine" to listOf("cp", "ls")))

        val id = store.create("e", "main", emptyList(), experiment = spec)

        assertEquals(spec, store.job(id)?.experiment)
    }

    @Test
    fun `a schedule asked to check now is due whatever its interval`() {
        val store = store()
        val id = store.createSchedule("s", "main", ExperimentSpec("s", listOf(mapOf("suite" to "s"))), 3600)
        store.scheduleChecked(id, now())

        store.checkNow(id)

        assertEquals(null, store.schedules().single().checkedAt)
    }

    @Test
    fun `a new experiment keeps a schedule's phase, and its next check can be set`() {
        val store = store()
        val id = store.createSchedule("s", "main", ExperimentSpec("s", listOf(mapOf("suite" to "s"))), 3600)
        store.scheduleChecked(id, 1_000_000)
        store.scheduleRan(id, "abc", 1)

        store.updateSchedule(id, ExperimentSpec("s", listOf(mapOf("suite" to "s")), description = "now described"), intervalSec = null)
        val described = store.schedules().single()
        store.updateSchedule(id, ExperimentSpec("s", listOf(mapOf("suite" to "t"))), intervalSec = null)
        val kept = store.schedules().single()
        store.updateSchedule(id, experiment = null, intervalSec = 7200, nextCheckAt = 9_000_000)
        val moved = store.schedules().single()

        assertEquals("abc", described.lastSha)
        assertEquals(listOf(1_000_000L, null, "t"), listOf(kept.checkedAt, kept.lastSha, kept.experiment.problems.single()["suite"]))
        assertEquals(listOf(9_000_000L - 7_200_000, 7200L, "t"), listOf(moved.checkedAt, moved.intervalSec, moved.experiment.problems.single()["suite"]))
    }

    @Test
    fun `a job's clock stops while it waits in the queue`() {
        val store = store()
        val id = store.create("a", "main", listOf("true" to 10L))
        store.next()
        store.requeue(id)

        val waiting = checkNotNull(store.job(id))

        assertEquals(null to waiting.runMs, waiting.runningSince to waiting.elapsedMs(now() + 3_600_000))
    }

    @Test
    fun `a launch asked for lasts until its run is queued and keeps the schedule's check time`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val id = store.createSchedule("sweep", "main", ExperimentSpec("sweep", listOf(mapOf("suite" to "s")), description = "d"), 3600)
        store.scheduleChecked(id, 1_000)

        val launched = store.launchNow(id)
        val asked = store.schedules().single()
        store.scheduleRan(id, "abc", 7)
        val ran = store.schedules().single()

        assertEquals(listOf(true, true, 1_000L), listOf(launched, asked.launchRequested, asked.checkedAt))
        assertEquals(false, ran.launchRequested)
    }

    private fun autoJob(store: Store, name: String, priority: Int = 0, host: String = Experiments.AUTO_HOST) =
        store.create(name, "main", emptyList(), priority = priority,
            experiment = ExperimentSpec(name, listOf(mapOf("suite" to "s")), host = host))

    @Test
    fun `a free lab machine takes an auto job at once, which stays shared`() {
        val store = store()
        val id = autoJob(store, "a")

        val taken = store.next()!!
        assertEquals(id, taken.id)
        assertEquals(listOf(Experiments.AUTO_HOST, "false", "true"), listOf(taken.experiment!!.host, taken.experiment.auto.toString(), taken.local.toString()))
        assertEquals(null, store.claimForNode(id))
    }

    @Test
    fun `an auto job the local machine took keeps running ahead of a lower priority local job, and gives way to a higher one`() {
        val store = store()
        val auto = autoJob(store, "a", priority = -1)
        assertEquals(auto, store.next()?.id)
        store.create("sweep@abc", "main", listOf("true" to 10L), priority = -2)
        val before = store.shouldYield(auto)
        store.create("ab", "main", listOf("true" to 10L))

        assertEquals(listOf(false, true), listOf(before, store.shouldYield(auto)))
    }

    @Test
    fun `a local job gives way to a queued auto job of higher priority, never to a lower or equal one`() {
        val store = store()
        val reference = autoJob(store, "klause-reference@abc", priority = -3, host = Experiments.LAB_HOST)
        assertEquals(reference, store.next()?.id)
        autoJob(store, "low", priority = -5)
        val equal = autoJob(store, "equal", priority = -3)
        val held = store.shouldYield(reference)
        val high = autoJob(store, "high", priority = 3)

        assertEquals(listOf(false, true), listOf(held, store.shouldYield(reference)))
        assertEquals(listOf(high, equal), store.queueOrder().take(2))
    }

    @Test
    fun `a paused, cancelled or node-claimed job does not make a local job give way`() {
        val store = store()
        val reference = autoJob(store, "klause-reference@abc", priority = -3, host = Experiments.LAB_HOST)
        store.next()
        val paused = autoJob(store, "paused", priority = 3)
        store.setPaused(paused, true)
        val cancelled = autoJob(store, "cancelled", priority = 3)
        store.requestCancel(cancelled)
        val claimed = autoJob(store, "claimed", priority = 3)
        store.claimForNode(claimed)

        assertEquals(listOf(true, Status.RUNNING), listOf(store.job(cancelled)!!.cancelRequested, store.job(claimed)!!.status))
        assertEquals(false, store.shouldYield(reference))
    }

    @Test
    fun `a local job that gives way to an auto job keeps its progress and resumes after it`() {
        val store = store()
        val reference = store.create("klause-reference@abc", "main", listOf("true" to 10L, "true" to 10L), priority = -3)
        store.next()
        store.commandStarted(reference, 0)
        store.commandFinished(reference, 0, 0)
        val high = autoJob(store, "high", priority = 3)
        assertEquals(true, store.shouldYield(reference))
        store.requeue(reference)

        assertEquals(high, store.next()?.id)
        assertEquals(Experiments.AUTO_HOST, store.job(high)!!.experiment!!.host)
        store.finish(high, Status.DONE)
        assertEquals(reference, store.next()?.id)
        assertEquals(listOf(Status.DONE, Status.QUEUED), store.job(reference)!!.commands.map { it.status })
    }

    @Test
    fun `a node claims an auto or aws job at once, with no wait for the local machine, which cannot join it before it is planned`() {
        val store = store()
        val auto = autoJob(store, "a")
        val aws = autoJob(store, "b", host = Experiments.AWS_HOST)

        val taken = store.claimForNode(auto)!!
        val other = store.claimForNode(aws)!!

        assertEquals(listOf(Experiments.AUTO_HOST, false, Status.RUNNING), listOf(taken.experiment!!.host, taken.experiment.auto, taken.status))
        assertEquals(listOf(Experiments.AWS_HOST, false), listOf(other.experiment!!.host, other.experiment.auto))
        assertEquals(null, store.next())
        assertEquals(null, store.claimForNode(auto))
    }

    @Test
    fun `a node never claims a job placed on the local machine, nor a paused one`() {
        val store = store()
        val mac = autoJob(store, "sweep@abc", host = Experiments.LAB_HOST)
        val paused = autoJob(store, "p")
        store.setPaused(paused, true)

        assertEquals(listOf(null, null), listOf(store.claimForNode(mac), store.claimForNode(paused)))
        assertEquals(emptyList(), store.nodeQueue().map { it.id })
    }

    @Test
    fun `the node queue holds auto and aws jobs by priority, and the local machine's next is what it would take`() {
        val store = store()
        val low = autoJob(store, "low", priority = -1)
        val aws = autoJob(store, "aws", host = Experiments.AWS_HOST)
        val high = autoJob(store, "high", priority = 3)
        val mac = autoJob(store, "sweep@abc", priority = -5, host = Experiments.LAB_HOST)

        assertEquals(listOf(high, aws, low), store.nodeQueue().map { it.id })
        assertEquals(listOf(high, low, mac), store.queueOrder())
        assertEquals(listOf(high, high), listOf(store.peek(), store.localNext()))
        assertEquals(listOf(high, aws, low, mac), store.queue())
        assertEquals(false, store.localBusy())
        store.next()
        assertEquals(listOf(true, low, null), listOf(store.localBusy(), store.peek(), store.localNext()))
    }

    @Test
    fun `the local machine's next is left to it while the job it runs is about to give way to that one`() {
        val store = store()
        val reference = autoJob(store, "klause-reference@abc", priority = -3, host = Experiments.LAB_HOST)
        store.next()
        val low = autoJob(store, "low", priority = -5)
        val held = store.localNext()
        val high = autoJob(store, "high", priority = 3)

        assertEquals(listOf(null, high), listOf(held, store.localNext()))
        assertEquals(listOf(high, low), store.queueOrder())
        assertEquals(true, store.shouldYield(reference))
    }

    @Test
    fun `while a node is up the local machine takes a local-only job before an older auto job of equal priority`() {
        val store = store()
        val auto = autoJob(store, "auto")
        val local = autoJob(store, "local", host = Experiments.LAB_HOST)

        assertEquals(listOf(local, auto), store.queueOrder(nodesUp = true))
        assertEquals(listOf(local, local), listOf(store.peek(nodesUp = true), store.localNext(nodesUp = true)))
        assertEquals(listOf(local, auto), store.queue(nodesUp = true))
        assertEquals(local, store.next(nodesUp = true)?.id)
        assertEquals(listOf(auto), store.nodeQueue().map { it.id })
    }

    @Test
    fun `with no node up or launching the older auto job goes first at equal priority`() {
        val store = store()
        val auto = autoJob(store, "auto")
        val local = autoJob(store, "local", host = Experiments.LAB_HOST)

        assertEquals(listOf(auto, local), store.queueOrder(nodesUp = false))
        assertEquals(listOf(auto, auto), listOf(store.peek(nodesUp = false), store.localNext(nodesUp = false)))
        assertEquals(listOf(auto, local), store.queue(nodesUp = false))
        assertEquals(auto, store.next(nodesUp = false)?.id)
    }

    @Test
    fun `a higher-priority auto job goes ahead of a local-only one whether or not a node is up`() {
        for (nodesUp in listOf(false, true)) {
            val store = store()
            val local = autoJob(store, "local", host = Experiments.LAB_HOST)
            val auto = autoJob(store, "auto", priority = 1)

            assertEquals(listOf(auto, local), store.queueOrder(nodesUp), "nodesUp=$nodesUp")
            assertEquals(auto, store.next(nodesUp)?.id, "nodesUp=$nodesUp")
        }
    }

    @Test
    fun `the local machine's next agrees with the job it takes whether or not a node is up`() {
        for (nodesUp in listOf(false, true)) {
            val store = store()
            autoJob(store, "auto")
            autoJob(store, "sweep@abc", host = Experiments.LAB_HOST)
            autoJob(store, "low", priority = -1, host = Experiments.LAB_HOST)

            val next = store.localNext(nodesUp)
            assertEquals(next, store.next(nodesUp)?.id, "nodesUp=$nodesUp")
            // Busy at equal priority: nothing is about to be taken, and the running job keeps the machine.
            assertEquals(null, store.localNext(nodesUp), "nodesUp=$nodesUp")
            val running = store.active(withCommands = false).single { it.status == Status.RUNNING }.id
            assertEquals(false, store.shouldYield(running), "nodesUp=$nodesUp")
        }
    }

    @Test
    fun `an auto job the local machine took and gave back competes with local jobs by priority`() {
        val store = store()
        val auto = autoJob(store, "a", priority = 5)
        assertEquals(auto, store.next()?.id)
        store.requeue(auto)
        val sweep = store.create("sweep@abc", "main", listOf("true" to 10L), priority = -2)

        assertEquals(listOf(auto, sweep), store.queueOrder())
        assertEquals(auto, store.next()?.id)
        assertEquals(false, store.shouldYield(auto))
    }

    @Test
    fun `placing moves only a queued job that has not been planned, and clears a machine count`() {
        val store = store()
        val id = store.create("e", "main", emptyList(),
            experiment = ExperimentSpec("e", listOf(mapOf("suite" to "s")), host = Experiments.AWS_HOST, machines = 3))
        val planned = store.create("p", "main", listOf("true" to 10L), experiment = ExperimentSpec("p", listOf(mapOf("suite" to "s"))))

        assertEquals(true, store.place(id, Experiments.AUTO_HOST))
        assertEquals(listOf(Experiments.AUTO_HOST, null), store.job(id)!!.experiment!!.let { listOf(it.host, it.machines) })
        assertEquals(false, store.place(planned, Experiments.AUTO_HOST))
    }

    @Test
    fun `a running command keeps the phase the lab reports until it finishes`() {
        val store = store()
        val id = store.create("a", "main", listOf("true" to 10L, "true" to 10L))
        store.next()
        val backoff = CasePhase(CasePhase.Phase.RETRY_BACKOFF, 5, attempt = 2, attempts = 8, retryAt = 30_005)
        store.commandPhase(id, 0, backoff)
        store.commandStarted(id, 1)
        store.commandPhase(id, 1, backoff)

        assertEquals(listOf(null, backoff), store.job(id)!!.commands.map { it.phase })
        store.commandFinished(id, 1, 0)
        assertEquals(listOf(null, null), store.job(id)!!.commands.map { it.phase })
    }

    @Test
    fun `a database from before case phases gains the column`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use {
                it.execute(
                    """CREATE TABLE commands (
                        job_id INTEGER NOT NULL, idx INTEGER NOT NULL, cmd TEXT NOT NULL, timeout_sec INTEGER NOT NULL,
                        status TEXT NOT NULL, exit_code INTEGER, started_at INTEGER, finished_at INTEGER,
                        cores INTEGER NOT NULL DEFAULT 1, PRIMARY KEY (job_id, idx))""",
                )
            }
        }
        val store = Store(file)
        val id = store.create("a", "main", listOf("true" to 10L))
        store.next()
        store.commandStarted(id, 0)
        store.commandPhase(id, 0, CasePhase(CasePhase.Phase.EXECUTING, 1))

        assertEquals(CasePhase.Phase.EXECUTING, store.job(id)!!.commands.single().phase?.phase)
    }
}
