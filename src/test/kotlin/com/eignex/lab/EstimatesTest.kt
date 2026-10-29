package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EstimatesTest {
    /** A six-minute timeout on one core: 0.1 core-hours a case at most. */
    private val spec = ExperimentSpec("e", listOf(mapOf("suite" to "s", "max" to "10")), base = mapOf("timeout" to "360000"))

    private fun job(status: Status, counts: CommandCounts?, experiment: ExperimentSpec = spec, parallel: Int = 1, name: String = "e") =
        Job(1, name, "main", status = status, cancelRequested = false, setupDone = true, createdAt = 0, parallel = parallel,
            experiment = experiment, counts = counts)

    private val none: (Job) -> CaseHistory? = { null }

    @Test
    fun `without measurements a job counts its queued cases, a running one its unfinished ones, an unplanned one its guess`() {
        fun hours(status: Status, counts: CommandCounts?) = Estimates.estimate(job(status, counts), none, 8)!!.coreHours

        assertEquals(0.1 * 4, hours(Status.QUEUED, CommandCounts(10, 4, 0, 2)), 1e-9)
        assertEquals(0.1 * 6, hours(Status.RUNNING, CommandCounts(10, 4, 0, 2)), 1e-9)
        assertEquals(0.1 * 10, hours(Status.RUNNING, CommandCounts(0, 0, 0, 0)), 1e-9)
        val guess = Estimates.estimate(job(Status.QUEUED, CommandCounts(0, 0, 0, 0)), none, 8)!!
        assertEquals(EstimateSource.TIMEOUT, guess.source)
        assertEquals("guessed, upper bound", guess.note)
        assertEquals("upper bound", Estimates.estimate(job(Status.QUEUED, CommandCounts(10, 4, 0, 2)), none, 8)!!.note)
    }

    @Test
    fun `a job's own mean case time is used once ten of its cases finished`() {
        // Nine finished cases of a minute each: still the timeout.
        val early = Estimates.estimate(job(Status.RUNNING, CommandCounts(100, 9, 0, 1, timed = 9, timedMs = 9 * 60_000)), none, 8)!!
        assertEquals(EstimateSource.TIMEOUT, early.source)
        assertEquals(91 * 0.1, early.coreHours, 1e-9)

        val measured = Estimates.estimate(job(Status.RUNNING, CommandCounts(100, 10, 0, 1, timed = 10, timedMs = 10 * 60_000)), none, 8)!!
        assertEquals(EstimateSource.MEASURED, measured.source)
        assertEquals("measured", measured.note)
        assertEquals(60_000.0, measured.caseMs, 1e-9)
        assertEquals(90 / 60.0, measured.coreHours, 1e-9)
        // Before ten, an earlier run's mean comes first.
        val earlier = Estimates.estimate(job(Status.RUNNING, CommandCounts(100, 9, 0, 1, timed = 9, timedMs = 9 * 60_000)),
            { CaseHistory(7, 977, 30_000.0) }, 8)!!
        assertEquals(EstimateSource.HISTORY, earlier.source)
        assertEquals("from run 7", earlier.note)
        assertEquals(91, earlier.cases)
        assertEquals(91 * 0.5 / 60, earlier.coreHours, 1e-9)
    }

    @Test
    fun `the local machine's time for a job runs its parallel cases at once, as many as fit the cores`() {
        val portfolio = spec.copy(base = spec.base + ("processors" to "4"))
        fun localHours(parallel: Int, cores: Int, experiment: ExperimentSpec = portfolio) = Estimates.estimate(
            job(Status.QUEUED, CommandCounts(40, 0, 0, 0, timed = 0), experiment, parallel = parallel), { CaseHistory(7, 40, 600_000.0) }, cores,
        )!!.localHours

        // 40 cases of ten minutes: four at once would take 100 min, but two four-core cases fill eight cores.
        assertEquals(40 * 10 / 2 / 60.0, localHours(parallel = 4, cores = 8), 1e-9)
        assertEquals(40 * 10 / 4 / 60.0, localHours(parallel = 4, cores = 16), 1e-9)
        assertEquals(40 * 10 / 60.0, localHours(parallel = 1, cores = 16), 1e-9)
        // A case larger than the local machine still runs, one at a time.
        assertEquals(40 * 10 / 60.0, localHours(parallel = 4, cores = 2), 1e-9)
        assertEquals(40 * 10 / 4 / 60.0, localHours(parallel = 4, cores = 8, experiment = spec), 1e-9)
        // Core-hours count every core a case holds.
        assertEquals(40 * 10 * 4 / 60.0, Estimates.estimate(job(Status.QUEUED, CommandCounts(40, 0, 0, 0), portfolio, parallel = 4),
            { CaseHistory(7, 40, 600_000.0) }, 8)!!.coreHours, 1e-9)
    }

    @Test
    fun `a finished run's mean comes from its case times, else from its running time at its concurrency`() {
        val timed = job(Status.DONE, CommandCounts(100, 100, 0, 0, timed = 100, timedMs = 100 * 90_000)).copy(id = 3)
        assertEquals(CaseHistory(3, 100, 90_000.0), Estimates.history(timed, 8))

        // 4 h of running at two cases at once over 960 cases: 30 s a case.
        val portfolio = spec.copy(base = spec.base + ("processors" to "4"))
        val untimed = job(Status.DONE, CommandCounts(960, 960, 0, 0), portfolio, parallel = 4).copy(id = 4, runMs = 4 * 3_600_000L)
        assertEquals(CaseHistory(4, 960, 30_000.0), Estimates.history(untimed, 8))
        assertNull(Estimates.history(job(Status.DONE, CommandCounts(0, 0, 0, 0)), 8))
    }

    /** A done run of [experiment] in [file]'s store: [cases] cases of [caseMs] each. */
    private fun doneRun(store: Store, file: Path, name: String, experiment: ExperimentSpec, cases: Int, caseMs: Long): Long {
        val id = store.create(name, experiment.base["ref"] ?: "main", List(cases) { "true" to 360L }, experiment = experiment)
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.prepareStatement("UPDATE commands SET status = 'DONE', started_at = 1000, finished_at = ? WHERE job_id = ?").use {
                it.setLong(1, 1000 + caseMs)
                it.setLong(2, id)
                it.executeUpdate()
            }
        }
        store.finish(id, Status.DONE)
        return id
    }

    @Test
    fun `an unplanned run learns from the last done run of the same experiment at another ref, its schedule's first`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val store = Store(file)
        val scheduled = spec.copy(name = "ref@aaaaaaaaa", base = spec.base + ("ref" to "a".repeat(40)))
        val first = doneRun(store, file, "ref@aaaaaaaaa", scheduled, cases = 30, caseMs = 120_000)
        val other = doneRun(store, file, "other", spec.copy(name = "other", description = "elsewhere"), cases = 12, caseMs = 60_000)
        doneRun(store, file, "slow", spec.copy(name = "slow", base = mapOf("timeout" to "720000")), cases = 50, caseMs = 1_000)
        val history = EstimateHistory(store, cores = 8)

        val next = job(Status.QUEUED, CommandCounts(0, 0, 0, 0), name = "ref@bbbbbbbbb",
            experiment = scheduled.copy(name = "ref@bbbbbbbbb", base = spec.base + ("ref" to "b".repeat(40)), host = Experiments.LAB_HOST))
        assertEquals(CaseHistory(first, 30, 120_000.0), history.lookup(next))
        // Another name runs the same experiment: the newest of those.
        assertEquals(CaseHistory(other, 12, 60_000.0), history.lookup(next.copy(name = "mine", experiment = next.experiment!!.copy(name = "mine"))))
        val estimate = Estimates.estimate(next, history::lookup, 8)!!
        assertEquals(30, estimate.cases)
        assertEquals("from run $first", estimate.note)
        assertEquals(30 * 2 / 60.0, estimate.coreHours, 1e-9)

        // Another timeout is another experiment: an ad hoc job then has no history, so the guess from its selection.
        val different = next.copy(name = "mine", experiment = spec.copy(name = "mine", base = mapOf("timeout" to "1000")))
        assertNull(history.lookup(different))
        val fallback = Estimates.estimate(different, history::lookup, 8)!!
        assertEquals(10, fallback.cases)
        assertEquals(EstimateSource.TIMEOUT, fallback.source)
    }

    @Test
    fun `a scheduled run whose experiment changed learns from its schedule's last run, marked as another experiment`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val store = Store(file)
        val old = spec.copy(name = "ref@aaaaaaaaa", base = spec.base + ("ref" to "a".repeat(40)))
        val last = doneRun(store, file, "ref@aaaaaaaaa", old, cases = 30, caseMs = 120_000)
        val history = EstimateHistory(store, cores = 8)

        val edited = old.copy(name = "ref@bbbbbbbbb", base = mapOf("timeout" to "1000", "ref" to "b".repeat(40)))
        val next = job(Status.QUEUED, CommandCounts(0, 0, 0, 0), name = "ref@bbbbbbbbb", experiment = edited)
        assertEquals(CaseHistory(last, 30, 120_000.0, sameExperiment = false), history.lookup(next))
        assertEquals("from run $last, an earlier experiment", Estimates.estimate(next, history::lookup, 8)!!.note)
        // An ad hoc job has no schedule to fall back to.
        assertNull(history.lookup(next.copy(name = "adhoc", experiment = edited.copy(name = "adhoc"))))
    }

    @Test
    fun `the history is reread at most once a minute`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val store = Store(file)
        var clock = 0L
        val history = EstimateHistory(store, cores = 8, clock = { clock })
        val next = job(Status.QUEUED, CommandCounts(0, 0, 0, 0))
        assertNull(history.lookup(next))

        val id = doneRun(store, file, "e", spec, cases = 5, caseMs = 1_000)
        clock = 30_000
        assertNull(history.lookup(next))
        clock = 60_000
        assertEquals(id, history.lookup(next)?.jobId)
    }

    @Test
    fun `the AWS pressure counts the realistic estimate, the timeouts only without one`() {
        val queued = job(Status.QUEUED, CommandCounts(0, 0, 0, 0))

        assertEquals(10 * 0.1, Estimates.pressureCoreHours(queued, none, 8), 1e-9)
        // 977 cases of 30 s from the last run, not 10 guessed cases of six minutes.
        assertEquals(977 * 30.0 / 3600, Estimates.pressureCoreHours(queued, { CaseHistory(7, 977, 30_000.0) }, 8), 1e-9)
        val measured = job(Status.QUEUED, CommandCounts(100, 50, 0, 0, timed = 50, timedMs = 50 * 36_000))
        assertEquals(50 * 0.01, Estimates.pressureCoreHours(measured, none, 8), 1e-9)
        val running = job(Status.RUNNING, CommandCounts(100, 50, 0, 0, timed = 50, timedMs = 50 * 36_000))
        assertEquals(0.01, Estimates.caseCoreHours(running, none), 1e-9)
        assertEquals(0.1, Estimates.caseCoreHours(job(Status.RUNNING, CommandCounts(100, 0, 0, 0)), none), 1e-9)
    }
}
