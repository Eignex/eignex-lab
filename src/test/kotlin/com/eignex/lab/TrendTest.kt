package com.eignex.lab

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TrendTest {
    @Test
    fun `controlled multi seed baselines count sources rather than runtime case ids`() {
        val cases = listOf(1L, 3L).flatMap { seed -> (0..1).map { repeat ->
            val plan = com.eignex.lab.bench.BenchCase("s/p/$seed/$repeat", attributes = mapOf("problem" to "p"))
            val record = kotlinx.serialization.json.Json.parseToJsonElement(
                """{"kind":"satisfy","budgetMs":1000,"comparisonControls":{},"feasible":${if (seed == 1L) "true" else "null"}}""")
            CaseResult(repeat, Status.DONE, Problem("s", plan.id, benchCase = plan), "base", seed, record, repeat)
        } }
        val job = Job(1, "baseline", "sha", status = Status.DONE, cancelRequested = false,
            setupDone = true, createdAt = 0, experiment = ExperimentSpec("baseline", emptyList()))

        val run = Trend.run(job, listOf(PlannedArm(Arm("base", emptyMap()), "sha")), cases)!!

        assertEquals(1, run.problems)
        assertEquals(4, run.cases)
        assertEquals(0.5, run.solved.value)
    }

    private fun run(store: Store, name: String, solvedOf: List<Boolean>, finish: Boolean = true, first: Int = 0): Long {
        val problems = solvedOf.indices.map { Problem(if (it % 2 == 0) "a" else "b", "p${first + it}") }
        val id = store.create(name, "sha", emptyList(), experiment = ExperimentSpec(name, listOf(mapOf("suite" to "a"))))
        store.startForTest()
        store.plan(id, listOf(PlannedArm(Arm("base", emptyMap()), "sha")), problems, Experiments.cases(problems.size, 1, emptyList()), problems.map { "true" to 1L })
        solvedOf.forEachIndexed { i, solved ->
            store.caseRecord(id, i, if (solved) """{"kind":"satisfy","feasible":true,"timeToBestMs":5,"budgetMs":1000}""" else """{"kind":"satisfy","budgetMs":1000}""")
            store.commandFinished(id, i, 0)
        }
        if (finish) store.finish(id, Status.DONE)
        return id
    }

    @Test
    fun `proof only losses appear in trend and in completed confirmation counts`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val first = run(store, "sweep@aaa", listOf(true))
        val second = run(store, "sweep@bbb", listOf(true))
        val optimum = """{"kind":"optimize","feasible":true,"proven":true,"exactObjective":"7"}"""
        val incumbent = """{"kind":"optimize","feasible":true,"exactObjective":"7"}"""
        store.caseRecord(first, 0, optimum)
        store.caseRecord(second, 0, incumbent)
        store.commandFinished(first, 0, 0)
        store.commandFinished(second, 0, 0)
        val name = Confirm.name("sweep", "sha", second)
        val confirmation = store.create(name, "sha", emptyList(), experiment = ExperimentSpec(name, emptyList()))
        store.startForTest()
        val problem = Problem("a", "p0")
        store.plan(confirmation, listOf(PlannedArm(Arm(Confirm.BEFORE, emptyMap()), "a"),
            PlannedArm(Arm(Confirm.AFTER, emptyMap()), "b")), listOf(problem),
            Experiments.cases(1, 2, emptyList(), 3), (0..5).map { "true" to 1L })
        store.plans.cases(confirmation).forEach {
            store.caseRecord(confirmation, it.index, if (it.arm == Confirm.BEFORE) optimum else incumbent)
            store.commandFinished(confirmation, it.index, 0)
        }
        store.finish(confirmation, Status.DONE)
        val result = Trend.runs(store, "sweep").last()
        assertEquals(0, result.lost)
        assertEquals(1, result.proofLost)
        assertEquals(0, result.objectiveLost)
        assertEquals(confirmation, result.confirmJob)
        assertEquals(1, result.confirmedProofLost)
        assertEquals(0, result.confirmedLost)
    }

    @Test
    fun `a schedule's runs come oldest first with their solved shares, other jobs left out`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val first = run(store, "sweep@aaa", listOf(true, false, false, false))
        run(store, "other@aaa", listOf(true))
        val second = run(store, "sweep@bbb", listOf(true, true, true, false), finish = false)

        val runs = Trend.runs(store, "sweep")

        assertEquals(listOf(first to 0.25, second to 0.75), runs.map { it.job to it.solved.value })
        assertEquals(listOf(true, false), runs.map { it.finished })
        assertEquals(mapOf("a" to 1.0, "b" to 0.5), runs[1].suites.mapValues { it.value.solved })
    }

    @Test
    fun `a run that contradicts the reference counts its disagreements`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        store.references.put(listOf(ProblemKey("", "p0") to Reference("clasp", false, null, false, true, 10, 1000)), "test")
        run(store, "sweep@aaa", listOf(true, false))

        val run = Trend.runs(store, "sweep").single()

        assertEquals(listOf(1, 1), listOf(run.disagreements, run.disagreementsBySuite["a"]))
        assertEquals(null, run.disagreementsBySuite["b"])
    }

    @Test
    fun `each run counts what it lost and gained against the run before`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        run(store, "sweep@aaa", listOf(true, true, false))
        run(store, "sweep@bbb", listOf(false, true, true))

        val runs = Trend.runs(store, "sweep")

        assertEquals(listOf(null to null, 1 to 1), runs.map { it.lost to it.gained })
    }

    @Test
    fun `a finished run's point stays fresh until the references change and the point has aged`() {
        var now = 0L
        val cache = TrendCache(maxReferenceLagMs = 1000) { now }
        val run = TrendRun(job = 1, sha = "a", spec = "s", at = 0, finished = true, cases = 1, problems = 1,
            solved = Estimate(1.0, 1.0, 1.0), proven = 0.0, unsupported = 0, errors = 0,
            par2 = Estimate(1.0, 1.0, 1.0), suites = emptyMap())
        cache.sync(5) { emptyMap() }
        cache.put(1, "v", stamp = 5, run, setOf(ProblemKey("", "p0")))
        cache.sync(6) { mapOf(ProblemKey("", "p0") to 6L) }

        now = 500
        val young = listOf(cache.lookup(1, "v", stamp = 5), cache.lookup(1, "v", stamp = 6)).map { it?.fresh }
        now = 2000
        val aged = listOf(cache.lookup(1, "v", stamp = 5), cache.lookup(1, "v", stamp = 6)).map { it?.fresh }

        assertEquals(listOf(true, true), young)
        assertEquals(listOf(true, false), aged)
        assertEquals(run, cache.lookup(1, "v", stamp = 6)?.run)
        assertEquals(null, cache.lookup(1, "retried", stamp = 6))
    }

    @Test
    fun `a reference change on one problem marks stale only the points that ran it`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        store.references.put(listOf(ProblemKey("", "p9") to Reference("clasp", false, null, false, true, 10, 1000)), "test")
        val ranA = run(store, "sweep@aaa", listOf(true, false))
        val other = run(store, "sweep@bbb", listOf(true, false), first = 5)
        var now = 0L
        val cache = TrendCache(maxReferenceLagMs = 1000) { now }
        Trend.runs(store, "sweep", cache)

        Thread.sleep(5)
        store.references.put(listOf(ProblemKey("", "p0") to Reference("clasp", false, null, false, true, 10, 1000)), "test")
        now = 2000
        val stamp = cache.sync(store)
        val fresh = Trend.scheduled(store, "sweep").associate { it.id to cache.lookup(it.id, TrendCache.version(it), stamp)?.fresh }

        assertEquals(mapOf(ranA to false, other to true), fresh)
        // A recompute takes the changed row in: the run now disagrees with it.
        assertEquals(1, Trend.runs(store, "sweep", cache, refreshing = true).first { it.job == ranA }.disagreements)
        assertEquals(true, cache.lookup(ranA, TrendCache.version(store.job(ranA)!!), cache.sync(store))?.fresh)
    }

    @Test
    fun `a finished run with no cases is remembered as having no point, not as being computed`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val failed = store.create("sweep@aaa", "sha", emptyList(), experiment = ExperimentSpec("sweep", listOf(mapOf("suite" to "a"))))
        store.startForTest()
        store.finish(failed, Status.FAILED)
        val done = run(store, "sweep@bbb", listOf(true))
        val cache = TrendCache()
        Trend.runs(store, "sweep", cache)

        val missing = ArrayList<Long>()
        val pointless = ArrayList<Long>()
        val runs = Trend.runs(store, "sweep", cache, missing = missing, pointless = pointless)

        assertEquals(listOf(done), runs.map { it.job })
        assertEquals(emptyList(), missing)
        assertEquals(listOf(failed), pointless)
        val page = trendPage("sweep", runs, "https://example.com/repo", computing = missing.size)
        assertFalse("<body data-refresh" in page)
        assertFalse("/trend/progress" in page)
    }

    @Test
    fun `the warm-up computes a schedule's older runs as well as its current ones`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val older = run(store, "sweep@aaa", listOf(true))
        store.schedules.create("sweep", "main", ExperimentSpec("sweep", listOf(mapOf("suite" to "b"))), 3600)
        val cache = TrendCache()

        Trend.warm(store, cache)

        val job = store.job(older)!!
        val deadline = System.currentTimeMillis() + 10_000
        while (cache.lookup(older, TrendCache.version(job), store.references.stamp()) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(older, cache.lookup(older, TrendCache.version(job), store.references.stamp())?.run?.job)
    }

    @Test
    fun `a schedule is refreshed once however many loads find it stale`() {
        val cache = TrendCache()
        val gate = java.util.concurrent.CountDownLatch(1)
        val ran = java.util.concurrent.atomic.AtomicInteger()

        repeat(3) { cache.refreshLater("sweep") { ran.incrementAndGet(); gate.await() } }
        gate.countDown()
        cache.refreshLater("other") {}
        Thread.sleep(200)

        assertEquals(1, ran.get())
    }

    @Test
    fun `a page load leaves an uncached finished run to the background and shows it once computed`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val done = run(store, "sweep@aaa", listOf(true, false))
        val going = run(store, "sweep@bbb", listOf(true), finish = false)
        val cache = TrendCache()
        val missing = ArrayList<Long>()

        val first = Trend.runs(store, "sweep", cache, missing = missing)

        assertEquals(listOf(going), first.map { it.job })
        assertEquals(listOf(done), missing)
        val deadline = System.currentTimeMillis() + 10_000
        while (cache.lookup(done, TrendCache.version(store.job(done)!!), store.references.stamp()) == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val later = ArrayList<Long>()
        assertEquals(listOf(done, going), Trend.runs(store, "sweep", cache, missing = later).map { it.job })
        assertEquals(emptyList(), later)
    }

    @Test
    fun `a solution whose checker could not run or could not decide counts in its theme`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val id = run(store, "sweep@aaa", listOf(false, false, false))
        fun mps(reason: String) = """{"solver":"klause","kind":"optimize","maximize":false,"feasible":null,"objective":null,
            "timeToBestMs":null,"proven":false,"budgetMs":1000,"validationPolicy":"mps-miplib2017-point-gzip-v1",
            "stats":{"sourceValidation":"unknown","sourceValidationReason":"$reason","reportedFeasible":"true",
            "reportedObjective":"-16.0","reportedProven":"false"}}"""
        store.caseRecord(id, 0, """{"solver":"klause","kind":"optimize","feasible":true,"objective":3,"timeToBestMs":5,"budgetMs":1000,
            "validationPolicy":"mps-miplib2017-point-gzip-v1","stats":{"sourceValidation":"valid"}}""")
        store.caseRecord(id, 1, mps("MPS source checking failed: missing packaged MIPLIB solchecker"))
        store.caseRecord(id, 2, mps("MPS source checking failed: lower bounds following upper bounds are unsupported"))
        store.references.putFeatures((0..2).map { ProblemFeatures("", "p$it", listOf("linear-real")) })

        val run = Trend.runs(store, "sweep").single()

        assertEquals(3, run.themes.getValue("linear-real").problems)
        assertEquals(1.0, run.themes.getValue("linear-real").solved)
    }

    @Test
    fun `a run's solved problems are counted by theme, a problem of several themes in each`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        run(store, "sweep@aaa", listOf(true, false))
        store.references.putFeatures(listOf(ProblemFeatures("", "p0", listOf("sat", "pb")), ProblemFeatures("", "p1", listOf("sat"))))

        val themes = Trend.runs(store, "sweep").single().themes

        assertEquals(setOf("pb", "sat"), themes.keys)
        assertEquals(1.0, themes.getValue("pb").solved)
        assertEquals(listOf(2, 1), listOf(themes.getValue("sat").problems, themes.getValue("pb").problems))
    }
    @Test
    fun `bench workload identities retain themes and reference disagreements in completed chart points`() {
        val store = Store(Files.createTempDirectory("bench-trend-identity").resolve("lab.db"))
        val benchCase = com.eignex.lab.bench.BenchCase("hakank/model/default/1/0", attributes = mapOf(
            "suite" to "hakank", "collection" to "hakank", "problem" to "model"))
        val problem = BenchRuns.problem(benchCase)
        val id = store.create("sweep@bench", "sha", emptyList(), experiment = ExperimentSpec("sweep", bench = BenchSpec("bench.json")))
        store.startForTest()
        store.plan(id, listOf(PlannedArm(Arm("base", emptyMap()), "sha")), listOf(problem),
            Experiments.cases(1, 1, emptyList()), listOf("true" to 1L))
        store.caseRecord(id, 0, """{"kind":"satisfy","feasible":true,"budgetMs":1000}""")
        store.references.putFeatures(listOf(ProblemFeatures("hakank", "model", listOf("sat"))))
        store.references.put(listOf(problem.referenceKey to Reference("reference", false, null, false, true, 10, 1000)), "fixture")
        store.finish(id, Status.DONE)

        val cache = TrendCache()
        val point = Trend.runs(store, "sweep", cache).single()
        assertEquals(id, point.job)
        assertEquals(SuiteShare(1, 1.0), point.themes.getValue("sat"))
        assertEquals(1, point.disagreements)
        assertEquals(mapOf("sat" to 1), point.disagreementsByTheme)
        assertEquals(listOf(point), Trend.runs(store, "sweep", cache, missing = mutableListOf()))
    }

}
