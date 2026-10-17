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
    fun `a queued profiling job cannot move to the local host`() {
        val store = store()
        val experiment = ExperimentSpec("profile", listOf(mapOf("suite" to "miplib2017")),
            parallel = 1, host = "aws", profileCli = true)
        val id = store.create("profile", "main", emptyList(), parallel = 1, experiment = experiment)

        assertFailsWith<IllegalArgumentException> { store.setHost(id, "lab", null) }

        assertEquals("aws", store.job(id)!!.experiment!!.host)
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
        store.requeue(id)

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
    fun `a launch asked for makes a schedule due and lasts until its run is queued`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val id = store.createSchedule("sweep", "main", ExperimentSpec("sweep", listOf(mapOf("suite" to "s")), description = "d"), 3600)
        store.scheduleChecked(id, 1_000)

        val launched = store.launchNow(id)
        val asked = store.schedules().single()
        store.scheduleRan(id, "abc", 7)
        val ran = store.schedules().single()

        assertEquals(listOf(true, true, null), listOf(launched, asked.launchRequested, asked.checkedAt))
        assertEquals(false, ran.launchRequested)
    }

    private fun autoJob(store: Store, name: String, priority: Int = 0) =
        store.create(name, "main", emptyList(), priority = priority,
            experiment = ExperimentSpec(name, listOf(mapOf("suite" to "s")), host = Experiments.AUTO_HOST))

    @Test
    fun `a free lab machine takes an auto job at once and keeps it`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val store = Store(file)
        val id = autoJob(store, "a")

        assertEquals(null, store.next(Experiments.AWS_HOST))
        val taken = store.next()!!
        assertEquals(id, taken.id)
        assertEquals(listOf(Experiments.LAB_HOST, "true"), listOf(taken.experiment!!.host, taken.experiment!!.auto.toString()))
    }

    @Test
    fun `a job queued for the lab machine goes before an auto job there, and an auto job running there gives way to it`() {
        val store = store()
        val auto = autoJob(store, "a", priority = 5)
        assertEquals(auto, store.next()?.id)
        assertEquals(false, store.shouldYield(auto))
        store.create("sweep", "main", listOf("true" to 10L), priority = -2)

        assertEquals(true, store.shouldYield(auto))
    }

    @Test
    fun `AWS takes an auto job only once it has waited for the lab machine`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val store = Store(file)
        val id = autoJob(store, "a")
        assertEquals(null, store.next(Experiments.AWS_HOST))
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use { it.executeUpdate("UPDATE jobs SET created_at = created_at - ${AUTO_GRACE_MS + 1}") }
        }

        val taken = store.next(Experiments.AWS_HOST)!!

        assertEquals(id, taken.id)
        assertEquals(Experiments.AWS_HOST, taken.experiment!!.host)
        assertEquals(true, taken.experiment!!.auto)
    }

    @Test
    fun `AWS sees an auto job the lab machine left waiting, as it would take it`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val store = Store(file)
        val id = autoJob(store, "a")
        assertEquals(id, store.peek(Experiments.LAB_HOST))
        assertEquals(null, store.peek(Experiments.AWS_HOST))
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use { it.executeUpdate("UPDATE jobs SET created_at = created_at - ${AUTO_GRACE_MS + 1}") }
        }

        assertEquals(id, store.peek(Experiments.AWS_HOST))
        assertEquals(id, store.next(Experiments.AWS_HOST)?.id)
        assertEquals(null, store.peek(Experiments.AWS_HOST))
    }

    @Test
    fun `a Mac job never gives way to an auto job the Mac took, and goes before it in the queue`() {
        val store = store()
        val auto = autoJob(store, "a", priority = 5)
        assertEquals(auto, store.next()?.id)
        store.requeue(auto)
        val sweep = store.create("sweep@abc", "main", listOf("true" to 10L), priority = -2)

        assertEquals(sweep, store.next()?.id)
        assertEquals(false, store.shouldYield(sweep))
        assertEquals(listOf(auto), store.queueOrder())
    }

    @Test
    fun `the longer the line for the Mac, the sooner AWS takes an auto job`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val store = Store(file)
        val ids = (1..5).map { autoJob(store, "a$it") }
        fun age(ms: Long) = java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use { it.executeUpdate("UPDATE jobs SET created_at = ${now()} - $ms") }
        }
        // Five waiting: three minutes each, not fifteen.
        age(AUTO_GRACE_MS / 5 - 10_000)
        assertEquals(null, store.peek(Experiments.AWS_HOST))
        age(AUTO_GRACE_MS / 5 + 10_000)
        assertEquals(ids.first(), store.peek(Experiments.AWS_HOST))
    }

    @Test
    fun `with on demand off AWS passes over profile jobs, its own and auto ones alike`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val store = Store(file)
        val profile = ProfileMain("com.eignex.klause.bench.profile.Timing")
        store.create("p", "main", emptyList(), experiment = ExperimentSpec("p", emptyList(), host = Experiments.AWS_HOST, profile = profile))
        store.create("ap", "main", emptyList(), experiment = ExperimentSpec("ap", emptyList(), host = Experiments.AUTO_HOST, profile = profile))
        val plain = autoJob(store, "a")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use { it.executeUpdate("UPDATE jobs SET created_at = created_at - ${AUTO_GRACE_MS + 1}") }
        }

        assertEquals(plain, store.peek(Experiments.AWS_HOST, profiles = false))
        assertEquals(plain, store.next(Experiments.AWS_HOST, profiles = false)?.id)
        assertEquals(null, store.next(Experiments.AWS_HOST, profiles = false))
    }
}
