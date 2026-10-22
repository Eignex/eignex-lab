package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class RunnerTest {
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
        // No second run while the first is queued, but the launch is still asked for and the schedule is still due.
        assertEquals(last, schedule.lastJob)
        assertTrue(schedule.launchRequested)
        assertEquals(null, schedule.checkedAt)
    }

    @Test
    fun `a whole run timeout fails the profile and preserves its output after cleanup`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))
        val spec = ExperimentSpec("bounded", profile = ProfileMain("com.eignex.klause.bench.profile.Tool", runTimeoutSec = 2))
        val id = store.create("bounded", "main", listOf("echo captured; sleep 30" to 60L), experiment = spec)
        config.worktree(id).createDirectories()
        store.next()
        store.setup(id, "head")

        Runner(config, store).workWithDeadline(store.job(id)!!)

        val job = store.job(id)!!
        assertEquals(Status.FAILED, job.status)
        assertTrue(job.error!!.contains("whole-run deadline"))
        assertTrue(config.jobDir(id).resolve("0.out").toFile().readText().contains("captured"))
        assertFalse(config.worktree(id).exists())
        // The case thread records the kill once it sees its process gone, which can trail the job's end a little.
        assertTrue(eventually { Termination.read(config.jobDir(id), 0).any { "deadline:" in it } })
    }

    @Test
    fun `a profileCli case on the local machine gets its own profile directory and leaves its measurement manifest there`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))
        val spec = ExperimentSpec("cli", listOf(mapOf("suite" to "s")), parallel = 1, profileCli = true)
        val case = "mkdir -p \"\$JOB_DIR/cases/0\" && echo '{\"ok\":true}' > \"\$JOB_DIR/cases/0/record.json\" && " +
            "echo \"\$KLAUSE_LAB_PROFILE_DIR\" > \"\$JOB_DIR/dir\" && echo \"\$KLAUSE_CLI_OPTS\" > \"\$JOB_DIR/opts\" && " +
            "echo \"\$KLAUSE_CLI_PREFLIGHT_OPTS\" > \"\$JOB_DIR/preflight\""
        val id = store.create("cli", "main", listOf(case to 60L), experiment = spec)
        config.worktree(id).createDirectories()
        val dir = config.jobDir(id).createDirectories()

        assertEquals(Dispatched.FINISHED, Runner(config, store).dispatch(store.job(id)!!, dir))

        val profile = Path.of(dir.resolve("dir").toFile().readText().trim())
        assertTrue(profile.startsWith(dir.resolve("cases/0")) && profile.fileName.toString().startsWith("profile-"))
        assertTrue("-XX:StartFlightRecording=filename=$profile/cli.jfr" in dir.resolve("opts").toFile().readText())
        // The bench's version check gets the same options without the recording.
        val preflight = dir.resolve("preflight").toFile().readText().trim()
        assertTrue("-XX:ActiveProcessorCount=" in preflight && "FlightRecording" !in preflight, preflight)
        assertEquals("{\"ok\":true}", profile.resolve("solve-record.json").toFile().readText().trim())
        assertTrue("\"commandExit\":0" in profile.resolve("measurement.json").toFile().readText())
        assertEquals(Status.DONE, store.job(id)!!.commands.single().status)
    }

    @Test
    fun `a resource measured local case keeps ordinary CLI options and binds its solve record`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))
        val spec = ExperimentSpec("cli", listOf(mapOf("suite" to "s")), parallel = 1, measureCliResources = true)
        val case = "mkdir -p \"\$JOB_DIR/cases/0\" && echo '{\"ok\":true}' > \"\$JOB_DIR/cases/0/record.json\" && " +
            "echo \"\$KLAUSE_LAB_PROFILE_DIR\" > \"\$JOB_DIR/dir\" && echo \"\$KLAUSE_CLI_OPTS\" > \"\$JOB_DIR/opts\" && " +
            "echo \"\$KLAUSE_CLI_PREFLIGHT_OPTS\" > \"\$JOB_DIR/preflight\""
        val id = store.create("cli", "main", listOf(case to 60L), experiment = spec)
        config.worktree(id).createDirectories()
        val dir = config.jobDir(id).createDirectories()

        assertEquals(Dispatched.FINISHED, Runner(config, store).dispatch(store.job(id)!!, dir))

        val profile = Path.of(dir.resolve("dir").toFile().readText().trim())
        assertTrue(profile.startsWith(dir.resolve("cases/0")))
        assertEquals(dir.resolve("preflight").toFile().readText(), dir.resolve("opts").toFile().readText())
        val manifest = profile.resolve("measurement.json").toFile().readText()
        assertTrue("\"instrumentation\":\"external-time\"" in manifest, manifest)
        assertFalse("cli.jfr" in manifest || "FlightRecording" in manifest, manifest)
        assertEquals("{\"ok\":true}", profile.resolve("solve-record.json").toFile().readText().trim())
    }

    private val sampleLog: String = checkNotNull(javaClass.getResource("/compilation/hotspot-compilation.log")).readText()

    /** A finished profileCompilation job whose case 0 left [log] as its HotSpot log; returns its id. */
    private fun profiledJob(config: Config, store: Store, log: String = sampleLog): Long {
        val spec = ExperimentSpec("cli", listOf(mapOf("suite" to "s")), parallel = 1, profileCli = true, profileCompilation = "jfr+log")
        val source = config.dataDir.resolve("sample.log").apply { writeText(log) }
        val case = "mkdir -p \"\$JOB_DIR/cases/0\" && echo '{\"ok\":true}' > \"\$JOB_DIR/cases/0/record.json\" && " +
            "cp ${quote(source.toString())} \"\$KLAUSE_LAB_PROFILE_DIR/hotspot-compilation.log\" && echo \"\$KLAUSE_CLI_OPTS\" > \"\$JOB_DIR/opts\""
        val id = store.create("cli", "main", emptyList(), experiment = spec)
        store.plan(id, Experiments.arms(spec).map { PlannedArm(it, "abc123") }, listOf(Problem("s", "p")), Experiments.cases(1, 1, emptyList()),
            listOf(case to 60L))
        config.worktree(id).createDirectories()
        store.next()
        assertEquals(Dispatched.FINISHED, Runner(config, store).dispatch(store.job(id)!!, config.jobDir(id).createDirectories()))
        store.finish(id, Status.DONE)
        return id
    }

    private fun report(config: Config, id: Long): Path =
        CompilationReports.profileDirs(config.jobDir(id), 0).single().resolve(CompilationReports.REPORT)

    @Test
    fun `a profileCompilation case records every compilation and gets its compilation report as it ends`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))

        val id = profiledJob(config, store)

        val opts = config.jobDir(id).resolve("opts").toFile().readText()
        assertTrue("jdk.Compilation#threshold=0ms" in opts && "-XX:+LogCompilation" in opts, opts)
        val report = checkNotNull(CompilationReports.read(report(config, id)))
        assertEquals(4, report.totals.compilations)
        assertEquals(SourceProvenance(id, 0, "s/p", "base", "main", "abc123"), report.provenance.source)
        assertTrue(report.missing.any { it.what == "jfr" })
        assertTrue("\"instrumented\":true" in report(config, id).resolveSibling("measurement.json").toFile().readText())
        assertTrue("compilation report" in config.jobDir(id).resolve("job.log").toFile().readText())
        assertTrue(store.cases(id).single().instrumented)
        assertTrue(store.scoringCases(id).single().instrumented)
    }

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
        val job = checkNotNull(store.next())
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

        Runner(config, store).analyze(checkNotNull(store.next()))

        val job = store.job(id)!!
        assertEquals(Status.FAILED, job.status)
        assertTrue("was trimmed" in job.error.orEmpty() && "rerun the experiment" in job.error.orEmpty(), job.error)
    }

    @Test
    fun `a bounded profile is done after its worktree cleanup succeeds`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))
        val spec = ExperimentSpec("bounded", profile = ProfileMain("com.eignex.klause.bench.profile.Tool", runTimeoutSec = 5))
        val id = store.create("bounded", "main", listOf("true" to 60L), experiment = spec)
        config.worktree(id).createDirectories()
        store.next()
        store.setup(id, "head")

        Runner(config, store).workWithDeadline(store.job(id)!!)

        assertEquals(Status.DONE, store.job(id)!!.status)
        assertFalse(config.worktree(id).exists())
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
    private class Dispatchable(vararg commands: Pair<String, Long>, graceMs: Long = Experiments.STOP_GRACE_SEC * 1000) {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))
        val id = store.create("kills", "main", commands.toList())
        val dir: Path = config.jobDir(id).createDirectories()
        val runner = Runner(config, store, stopGraceMs = graceMs)

        init {
            config.worktree(id).createDirectories()
            store.next()
            store.setup(id, "head")
        }

        fun dispatch(): Dispatched = runner.dispatch(checkNotNull(store.job(id)), dir)

        fun reasons(index: Int): List<String> = Termination.read(dir, index).map { it.substringAfter(' ') }
    }

    private fun eventually(check: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            if (check()) return true
            Thread.sleep(50)
        }
        return check()
    }

    @Test
    fun `a timeout kill says so, a SIGKILL the lab did not send is external, and an ordinary failure says nothing`() {
        val lab = Dispatchable("sleep 30" to 1L, "kill -9 \$\$" to 60L, "exit 3" to 60L)

        assertEquals(Dispatched.FINISHED, lab.dispatch())

        assertEquals(listOf(Termination.timeoutGraceful(1, Experiments.STOP_GRACE_SEC * 1000)), lab.reasons(0))
        assertEquals("137", lab.dir.resolve("1.exit").toFile().readText().trim())
        assertEquals(listOf(Termination.external(137)), lab.reasons(1))
        assertTrue(lab.reasons(2).isEmpty())
    }

    @Test
    fun `a case at its time limit gets SIGTERM and the grace to flush what it has`() {
        val lab = Dispatchable(
            "OUT=\"\$JOB_DIR/cases/0/out\"; mkdir -p \"\$OUT\"; trap 'echo flushed > \"\$OUT/f\"; exit 0' TERM; sleep 600" to 1L,
            graceMs = 20_000,
        )
        val started = System.nanoTime()

        assertEquals(Dispatched.FINISHED, lab.dispatch())

        // Stopped by its own trap well inside the grace, not by SIGKILL at its end.
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 15)
        assertEquals("flushed", lab.dir.resolve("cases/0/out/f").toFile().readText().trim())
        assertEquals("timeout", lab.dir.resolve("0.exit").toFile().readText().trim())
        assertEquals(listOf(Termination.timeoutGraceful(1, 20_000)), lab.reasons(0))
    }

    @Test
    fun `a case that ignores SIGTERM is killed when its grace runs out`() {
        val lab = Dispatchable("trap '' TERM; echo started; sleep 600" to 1L, graceMs = 500)

        assertEquals(Dispatched.FINISHED, lab.dispatch())

        val leader = lab.dir.resolve("0.pid").toFile().readText().trim().split(" ").first().toLong()
        assertFalse(signalGroups("0", listOf(leader)))
        assertEquals("timeout", lab.dir.resolve("0.exit").toFile().readText().trim())
        assertTrue(lab.dir.resolve("0.out").toFile().readText().contains("started"))
        assertEquals(listOf(Termination.timeoutGraceful(1, 500), Termination.graceExpired("timeout", 500)), lab.reasons(0))
    }

    @Test
    fun `a whole run deadline during the command stops the profile gracefully, keeping what it flushed`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))
        val spec = ExperimentSpec("bounded", profile = ProfileMain("com.eignex.klause.bench.profile.Tool", runTimeoutSec = 5))
        val command = "OUT=\"\$JOB_DIR/cases/0/out\"; mkdir -p \"\$OUT\"; trap 'echo flushed > \"\$OUT/f\"; exit 0' TERM; sleep 600"
        val id = store.create("bounded", "main", listOf(command to 60L), experiment = spec)
        config.worktree(id).createDirectories()
        store.next()
        store.setup(id, "head")

        // The grace is at most a tenth of what is left of the allowance: just under 500 ms here.
        Runner(config, store, stopGraceMs = 2_000).workWithDeadline(store.job(id)!!)

        assertEquals(Status.FAILED, store.job(id)!!.status)
        assertEquals("flushed", config.jobDir(id).resolve("cases/0/out/f").toFile().readText().trim())
        assertTrue(eventually {
            Termination.read(config.jobDir(id), 0).map { it.substringAfter(' ') }.let { reasons ->
                reasons.size == 1 && reasons.single().startsWith("deadline:") && "SIGTERM" in reasons.single()
            }
        })
    }

    @Test
    fun `a cancelled case says it was cancelled`() {
        val lab = Dispatchable("sleep 30" to 60L)
        val canceller = thread {
            eventually { lab.dir.resolve("0.pid").exists() }
            lab.store.requestCancel(lab.id)
        }

        assertEquals(Dispatched.CANCELLED, lab.dispatch())
        canceller.join()

        assertEquals(listOf(Termination.CANCEL), lab.reasons(0))
    }

    @Test
    fun `a runner shutdown says why it killed the case and records no exit, so the case reruns`() {
        val lab = Dispatchable("sleep 30" to 60L)
        // It never returns: a stopping runner waits for its own end with the case left RUNNING.
        thread(isDaemon = true) { lab.dispatch() }
        assertTrue(eventually { lab.dir.resolve("0.pid").exists() })

        lab.runner.stop()

        assertEquals(listOf(Termination.SHUTDOWN), lab.reasons(0))
        Thread.sleep(500)
        assertFalse(lab.dir.resolve("0.exit").exists())
        assertEquals(Status.RUNNING, lab.store.job(lab.id)!!.commands.single().status)
    }

    @Test
    fun `a reference job plans lab-native cases on the files the bench resolves by name`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))
        val problem = Problem("smtlib-qfnra", "hong/hong_13", collection = "smtlib-qfnra", format = "SMTLIB")
        val spec = ExperimentSpec("ref", base = mapOf("backend" to "reference", "solver" to "cvc5"),
            problemList = listOf(problem))
        val id = store.create("ref", "main", emptyList(), experiment = spec)
        val asked = ArrayList<String>()
        val host = object : ExecutionHost by ResolvingHost {
            override fun select(worktree: String, args: String): List<String> {
                asked += args
                return if ("files=true" in args) {
                    listOf("""{"suite":"smtlib-qfnra","problem":"hong/hong_13","format":"SMTLIB","model":"/c/hong_13.smt2.zst"}""")
                } else {
                    emptyList()
                }
            }
        }

        Runner(config, store).plan(store.job(id)!!, spec, Experiments.arms(spec), mapOf("main" to "abc"), "abc",
            config.jobDir(id), host)

        val command = store.job(id)!!.commands.single().cmd
        assertTrue(command.startsWith("exec '${config.launcher}' reference "))
        assertTrue("'model=/c/hong_13.smt2.zst'" in command && "'solver=cvc5'" in command)
        assertTrue(asked.any { "'name=hong/hong_13'" in it && "'files=true'" in it })
    }

    private object ResolvingHost : ExecutionHost {
        override val cores = 4
        override val maxParallel = 4
        override val corpus = ""
        override val yieldsToUpdates = false
        override val yieldsToPriority = false
        override fun worktree(sha: String) = ""
        override fun select(worktree: String, args: String) = emptyList<String>()
        override fun run(command: Command, dir: Path): Int = 0
    }
}
