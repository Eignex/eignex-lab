package com.eignex.lab

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class TrendTest {
    private fun run(store: Store, name: String, solvedOf: List<Boolean>, finish: Boolean = true): Long {
        val problems = solvedOf.indices.map { Problem(if (it % 2 == 0) "a" else "b", "p$it") }
        val id = store.create(name, "sha", emptyList(), experiment = ExperimentSpec(name, listOf(mapOf("suite" to "a"))))
        store.next()
        store.plan(id, listOf(PlannedArm(Arm("base", emptyMap()), "sha")), problems, Experiments.cases(problems.size, 1, emptyList()), problems.map { "true" to 1L })
        solvedOf.forEachIndexed { i, solved ->
            store.caseRecord(id, i, if (solved) """{"kind":"satisfy","feasible":true,"timeToBestMs":5,"budgetMs":1000}""" else """{"kind":"satisfy","budgetMs":1000}""")
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
        store.next()
        val problem = Problem("a", "p0")
        store.plan(confirmation, listOf(PlannedArm(Arm(Confirm.BEFORE, emptyMap()), "a"),
            PlannedArm(Arm(Confirm.AFTER, emptyMap()), "b")), listOf(problem),
            Experiments.cases(1, 2, emptyList(), 3), (0..5).map { "true" to 1L })
        store.cases(confirmation).forEach {
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
        store.putReferences(listOf(("" to "p0") to Reference("clasp", false, null, false, true, 10, 1000)), "test")
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
        cache.put(1, stamp = 5, run)

        now = 500
        val young = listOf(cache.lookup(1, stamp = 5), cache.lookup(1, stamp = 6)).map { it?.fresh }
        now = 2000
        val aged = listOf(cache.lookup(1, stamp = 5), cache.lookup(1, stamp = 6)).map { it?.fresh }

        assertEquals(listOf(true, true), young)
        assertEquals(listOf(true, false), aged)
        assertEquals(run, cache.lookup(1, stamp = 6)?.run)
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
        while (cache.lookup(done, store.referencesStamp()) == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val later = ArrayList<Long>()
        assertEquals(listOf(done, going), Trend.runs(store, "sweep", cache, missing = later).map { it.job })
        assertEquals(emptyList(), later)
    }

    @Test
    fun `a run's solved problems are counted by theme, a problem of several themes in each`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        run(store, "sweep@aaa", listOf(true, false))
        store.putFeatures(listOf(ProblemFeatures("", "p0", listOf("sat", "pb")), ProblemFeatures("", "p1", listOf("sat"))))

        val themes = Trend.runs(store, "sweep").single().themes

        assertEquals(setOf("pb", "sat"), themes.keys)
        assertEquals(1.0, themes.getValue("pb").solved)
        assertEquals(listOf(2, 1), listOf(themes.getValue("sat").problems, themes.getValue("pb").problems))
    }
}
