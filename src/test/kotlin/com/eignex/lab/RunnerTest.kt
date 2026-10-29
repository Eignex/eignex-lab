package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class RunnerTest {
    @Test fun `retired schedules remain editable and show the reason without fetching or creating jobs`() = io.ktor.server.testing.testApplication {
        val config = Config(dataDir = Files.createTempDirectory("retired-schedules"), auth = Auth.Disabled)
        val store = Store(config.database)
        val spec = ExperimentSpec("old-reference", problems = listOf(mapOf("set" to "sweep")))
        val id = store.createSchedule(spec.name, "main", spec, 120)
        store.launchNow(id)
        var fetched = 0
        val runner = Runner(config, store, sourceUrl = { fetched++; error("must not access source") })
        repeat(3) { runner.enqueueScheduled() }
        assertEquals(0, fetched)
        assertTrue(store.jobs().isEmpty())
        val schedule = store.schedules().single()
        assertTrue(schedule.launchRequested)
        assertTrue(schedule.blockedReason()!!.contains("legacy solver recipes must be migrated"))
        val page = indexPage(config, HostReport("fixture", 4, "java"), emptyList(), listOf(schedule), emptyList(), 50, null, null)
        assertTrue(page.contains("blocked: execution requires a repository bench manifest or explicit profile"))
        assertTrue(page.contains("edit schedule to resume"))
        application { api(config, store, HostReport("fixture", 4, "java")) }
        val response = client.get("/schedules").bodyAsText()
        assertTrue(response.contains("blockedReason"))
        assertTrue(response.contains("legacy solver recipes must be migrated"))
        store.updateSchedule(id, spec.copy(problems = emptyList(), bench = BenchSpec("bench.json")), null)
        assertEquals(null, store.schedules().single().blockedReason(), "repair removes the block without deleting history")
    }

    @Test
    fun `confirmations inherit a changed schedule priority even when the original run used an older priority`() {
        for (priority in listOf(0, 100)) {
            val config = Config(dataDir = Files.createTempDirectory("confirmation-priority"))
            val store = Store(config.database)
            val original = ExperimentSpec("status-sweep", bench = BenchSpec("bench.json"), priority = -2)
            val schedule = store.createSchedule("status-sweep", "main", original, 120)
            fun run(sha: String, solved: Boolean): Long {
                val spec = original.copy(name = "status-sweep@$sha", base = mapOf("ref" to sha))
                val id = store.create(spec.name, sha, emptyList(), experiment = spec)
                store.plan(id, Experiments.arms(spec).map { PlannedArm(it, sha) }, listOf(BenchRuns.problem(com.eignex.lab.bench.BenchCase("p"))),
                    Experiments.cases(1, 1, emptyList()), listOf("true" to 1L))
                store.setup(id, sha)
                store.caseRecord(id, 0, if (solved) """{"kind":"satisfy","feasible":true,"budgetMs":1000}"""
                    else """{"kind":"satisfy","budgetMs":1000}""")
                store.commandFinished(id, 0, 0)
                store.finish(id, Status.DONE)
                return id
            }
            run("aaa", true)
            val current = run("bbb", false)
            store.updateSchedule(schedule, original.copy(priority = priority), null)
            Runner(config, store).queueConfirmation(store.job(current)!!)
            assertEquals(priority + 1, store.active().single().priority)
        }
    }

    @Test
    fun `a launch asked for while the last run is still queued stays due instead of waiting out the interval`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))
        val spec = ExperimentSpec("ref", listOf(mapOf("suite" to "s")))
        val id = store.createSchedule("ref", "main", spec, 3 * 86400)
        val last = store.create("ref@abc", "abc", emptyList(), experiment = spec)
        store.scheduleRan(id, "abc", last)
        store.launchNow(id)

        Runner(config, store).enqueueScheduled()

        val schedule = store.schedules().single()
        // No second run while the first is queued; the launch is still asked for, and the regular check that ran
        // stamped its time as usual.
        assertEquals(last, schedule.lastJob)
        assertTrue(schedule.launchRequested)
        val checked = checkNotNull(schedule.checkedAt)

        // Another poll while it waits leaves the regular check's time alone: a launch never moves a schedule's phase.
        Runner(config, store).enqueueScheduled()
        assertEquals(checked, store.schedules().single().checkedAt)
        assertTrue(store.schedules().single().launchRequested)
    }

    private val sampleLog: String = checkNotNull(javaClass.getResource("/compilation/hotspot-compilation.log")).readText()

    /** A finished compilation measurement job whose case 0 left [log] as its HotSpot log; returns its id. */
    private fun profiledJob(config: Config, store: Store, log: String = sampleLog): Long {
        val spec = ExperimentSpec("cli", listOf(mapOf("suite" to "s")), parallel = 1, measurement = Measurement.COMPILATION_LOG)
        val id = store.create("cli", "main", emptyList(), experiment = spec)
        store.plan(id, Experiments.arms(spec).map { PlannedArm(it, "abc123") }, listOf(Problem("s", "p")),
            Experiments.cases(1, 1, emptyList()), listOf("recorded" to 60L))
        val profile = config.jobDir(id).resolve("cases/0/profile-fixture").createDirectories()
        profile.resolve("hotspot-compilation.log").writeText(log)
        Runner(config, store).reportCompilation(id, 0, config.jobDir(id))
        store.commandFinished(id, 0, 0)
        store.finish(id, Status.DONE)
        return id
    }

    private fun report(config: Config, id: Long): Path =
        CompilationReports.profileDirs(config.jobDir(id), 0).single().resolve(CompilationReports.REPORT)

    @Test
    fun `an analysis job writes its job's reports again from the retained captures, without a build`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))
        val target = profiledJob(config, store)
        // As an older analyser would have left it.
        report(config, target).writeText("{}")
        val spec = ExperimentSpec("analyze-$target", description = "again", analyze = AnalyzeSpec(target))
        Experiments.validate(spec, 4)
        val id = store.create(spec.name, "main", emptyList(), experiment = spec)
        val job = checkNotNull(store.nextAnalysis())
        assertEquals(id, job.id)

        Runner(config, store).analyze(job)

        assertEquals(Status.DONE, store.job(id)!!.status)
        val report = checkNotNull(CompilationReports.read(report(config, target)))
        assertEquals(id, report.provenance.worker.analyzeJob)
        assertEquals(4, report.totals.compilations)
        val analysis = config.jobDir(id).resolve(CompilationReports.ANALYSIS).toFile().readText()
        assertTrue("\"compilations\":4" in analysis && "cases/0/profile-" in analysis, analysis)
        assertFalse(config.worktree(id).exists())
    }

    @Test
    fun `an analysis job of a trimmed job fails, saying its captures are gone`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))
        val target = profiledJob(config, store)
        val trim = JobTrim(config.dataDir.resolve("jobs"), { listOf(target to 0L) }, { Long.MAX_VALUE }, { _, _ -> }, clock = { JobTrim.MAX_AGE_MS })
        trim.run()
        assertTrue(Files.exists(report(config, target)), "the trim keeps the report")
        val spec = ExperimentSpec("analyze-$target", description = "again", analyze = AnalyzeSpec(target))
        val id = store.create(spec.name, "main", emptyList(), experiment = spec)

        Runner(config, store).analyze(checkNotNull(store.nextAnalysis()))

        val job = store.job(id)!!
        assertEquals(Status.FAILED, job.status)
        assertTrue("was trimmed" in job.error.orEmpty() && "rerun the experiment" in job.error.orEmpty(), job.error)
    }

    @Test
    fun `deleting a worktree removes a shared link without touching what it points at`() {
        val root = Files.createTempDirectory("lab")
        val shared = root.resolve("shared").createDirectories()
        shared.resolve("result.json").writeText("{}")
        val worktree = root.resolve("work").resolve("build").createDirectories().parent
        Files.createSymbolicLink(worktree.resolve("build").resolve("shared"), shared)

        deleteTree(worktree)

        assertTrue(!worktree.exists() && shared.resolve("result.json").exists())
    }

    @Test
    fun `killing the group reaches a child whose parent shell already died`() {
        val process = ProcessBuilder(inSession("bash", "-c", "sleep 30 & echo \$!; exit 0"))
            .redirectErrorStream(true).start()
        val orphan = process.inputStream.bufferedReader().readLine().trim().toLong()
        process.waitFor(5, TimeUnit.SECONDS)

        killGroup(process.pid())

        val child = ProcessHandle.of(orphan)
        child.ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
        assertFalse(child.map { it.isAlive }.orElse(false))
    }

    @Test
    fun `collecting a tree copies its files and skips the links in it`() {
        val root = Files.createTempDirectory("lab")
        val source = root.resolve("output").resolve("config").createDirectories().parent
        source.resolve("config").resolve("p.json").writeText("{}")
        Files.createSymbolicLink(source.resolve("cache"), root.resolve("elsewhere").createDirectories())

        copyTree(source, root.resolve("collected"))

        assertTrue(
            root.resolve("collected/config/p.json").exists() &&
                !Files.exists(root.resolve("collected/cache"), java.nio.file.LinkOption.NOFOLLOW_LINKS),
        )
    }

    /** A runner over a fresh lab, its store and a job of [commands] taken and set up, ready for [Runner.dispatch]. */
    private fun eventually(check: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            if (check()) return true
            Thread.sleep(50)
        }
        return check()
    }


}
