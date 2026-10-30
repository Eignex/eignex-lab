package com.eignex.lab

import com.eignex.lab.util.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.execution.*
import com.eignex.lab.worker.*
import com.eignex.lab.nomad.*
import com.eignex.lab.web.*
import com.eignex.lab.cli.*

import kotlinx.serialization.json.Json
import com.eignex.lab.bench.BenchRequest
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.nio.file.Files

class ExperimentsTest {
    @Test
    fun `controlled series distinguish aggregate execution capacity`() {
        val sweep = ExperimentSpec("sweep", bench = BenchSpec("bench.json",
            BenchRequest(parameters = mapOf("comparison-series" to "sdk-linux-v1"))), parallel = 1)

        assertNotEquals(Experiments.fingerprint(sweep), Experiments.fingerprint(sweep.copy(parallel = 2)))
    }

    @Test
    fun `historical bench capacity changes preserve their existing fingerprint`() {
        val sweep = ExperimentSpec("sweep", bench = BenchSpec("bench.json"), parallel = 1)

        assertEquals(Experiments.fingerprint(sweep), Experiments.fingerprint(sweep.copy(parallel = 2)))
    }

    @Test
    fun `a local profile can opt into a whole run deadline`() {
        val spec = Json.decodeFromString<ExperimentSpec>(
            """{"name":"bounded","host":"lab","profile":{"main":"com.eignex.tools.profile.Tool","classpath":["tools/lib/*"],"runTimeoutSec":180}}""",
        )

        Experiments.validate(spec, maxParallel = 4)

        assertEquals(180L, spec.profile?.runTimeoutSec)
    }

    @Test
    fun `whole run deadlines reject nonpositive and overflowing limits`() {
        for (limit in listOf(0L, -1L, Long.MAX_VALUE)) {
            val spec = ExperimentSpec("bounded", profile = ProfileMain("com.eignex.tools.profile.Tool", runTimeoutSec = limit, classpath = listOf("tools/lib/*")))

            assertFailsWith<IllegalArgumentException> { Experiments.validate(spec, maxParallel = 4) }
        }
    }

    @Test
    fun `Nomad supports whole run deadlines on portable jobs`() {
        for (host in listOf(Experiments.AWS_HOST, Experiments.AUTO_HOST)) {
            val spec = ExperimentSpec("bounded", host = host,
                profile = ProfileMain("com.eignex.tools.profile.Tool", runTimeoutSec = 180, classpath = listOf("tools/lib/*")))

            Experiments.validate(spec, maxParallel = 4)
        }
    }

    @Test
    fun `profile deadlines do not change the measurement fingerprint`() {
        val spec = ExperimentSpec("bounded", profile = ProfileMain("com.eignex.tools.profile.Tool", classpath = listOf("tools/lib/*")))

        assertEquals(Experiments.fingerprint(spec), Experiments.fingerprint(
            spec.copy(profile = spec.profile!!.copy(runTimeoutSec = 180)),
        ))
    }

    @Test
    fun `an analysis job names a job and a kind and nothing an experiment runs, and stays on the local machine`() {
        val analysis = ExperimentSpec("analyze-9", description = "again", analyze = AnalyzeSpec(9))

        Experiments.validate(analysis, maxParallel = 4)
        assertTrue(analysis.isAnalysis)
        assertTrue(Placement.localOnly(analysis, analysis.name))
        for (wrong in listOf(
            analysis.copy(analyze = AnalyzeSpec(9, "cpu")),
            analysis.copy(analyze = AnalyzeSpec(0)),
            analysis.copy(problems = listOf(mapOf("suite" to "s"))),
            analysis.copy(base = mapOf("ref" to "main")),
            analysis.copy(measurement = Measurement.JFR),
            analysis.copy(measurement = Measurement.RESOURCES),
        )) assertFailsWith<IllegalArgumentException>(wrong.toString()) { Experiments.validate(wrong, maxParallel = 4) }
        // An analysis that also names a profile cannot even be built: an experiment is one kind of work.
        assertFailsWith<IllegalArgumentException> {
            analysis.copy(profile = ProfileMain("com.eignex.tools.profile.Tool", classpath = listOf("tools/lib/*")))
        }
        val defaulted = Json.decodeFromJsonElement(ExperimentSpec.serializer(),
            analysisDefaults(Json.parseToJsonElement("""{"analyze": {"job": 9}}""").jsonObject))
        assertEquals("analyze-9", defaulted.name)
        assertEquals(AnalyzeSpec(9, "compilation"), defaulted.analyze)
        assertTrue(defaulted.description.isNotBlank())
    }

    @Test
    fun `a reference case runs the lab's own launcher on the problem's resolved files`() {
        val arm = Arm("ref", mapOf("backend" to "reference", "solver" to "chuffed", "timeout" to "60000", "lp" to "on"))
        val problem = Problem("hakank", "golf", format = "MINIZINC", model = "/c/golf.mzn", data = "/c/golf.dzn",
            includeDirs = listOf("/c/lib"))

        val command = Experiments.referenceCommand("/lab/bin/eignex-lab", problem, arm, 4, 2)

        assertEquals(
            "exec '/lab/bin/eignex-lab' reference 'problem=golf' 'format=MINIZINC' 'model=/c/golf.mzn' " +
                "'data=/c/golf.dzn' 'include=/c/lib' 'solver=chuffed' 'timeout=60000' 'solver-seed=4' " +
                "out=\"\$JOB_DIR/cases/2\"",
            command,
        )
    }

    @Test
    fun `a reference arm keeps routing to the lab's reference command with its solver and search`() {
        val arm = Arm("ref", mapOf("backend" to "reference", "solver" to "chuffed", "fixed" to "true"))
        val problem = Problem("hakank", "golf", format = "MINIZINC", model = "/c/golf.mzn")

        val command = Experiments.referenceCommand("/lab/bin/eignex-lab", problem, arm, null, 0)

        assertContains(command, "'solver=chuffed' 'fixed=true'")
        assertFalse("backend=" in command)
    }

    @Test
    fun `an invalid exact policy is refused`() {
        assertFailsWith<IllegalArgumentException> {
            Experiments.validate(spec(base = mapOf("exact" to "yes")), maxParallel = 4)
        }
    }

    private fun spec(
        configs: List<Map<String, String>> = listOf(emptyMap()),
        grid: Map<String, List<String>> = emptyMap(),
        base: Map<String, String> = emptyMap(),
    ) = ExperimentSpec("e", listOf(mapOf("suite" to "hakank")), base, configs, grid)

    @Test
    fun `a grid crosses every axis over the base`() {
        val arms = Experiments.arms(spec(base = mapOf("timeout" to "5000"), grid = mapOf("ref" to listOf("main", "fix"), "engine" to listOf("cp", "ls"))))

        assertEquals(
            listOf("ref=main engine=cp", "ref=main engine=ls", "ref=fix engine=cp", "ref=fix engine=ls"),
            arms.map { it.label },
        )
    }

    @Test
    fun `a config's own label names its arm and is not passed on`() {
        val arms = Experiments.arms(spec(configs = listOf(mapOf("label" to "a"), mapOf("label" to "b", "engine" to "ls"))))

        assertEquals(listOf("a" to emptyMap(), "b" to mapOf("engine" to "ls")), arms.map { it.label to it.values })
    }

    @Test
    fun `each problem runs every arm with the order rotated`() {
        val cases = Experiments.cases(problems = 3, arms = 2, seeds = emptyList())

        assertEquals(listOf(0 to 0, 0 to 1, 1 to 1, 1 to 0, 2 to 0, 2 to 1), cases.map { it.problem to it.arm })
    }

    @Test
    fun `the arms alternate over seeds and repeats`() {
        val cases = Experiments.cases(problems = 1, arms = 2, seeds = listOf(1, 2), repeats = 2)

        assertEquals(
            listOf("0/1/0", "1/1/0", "0/1/1", "1/1/1", "0/2/0", "1/2/0", "0/2/1", "1/2/1"),
            cases.map { "${it.arm}/${it.seed}/${it.repeat}" },
        )
    }

    @Test
    fun `a problem whose rotation puts the second arm first still alternates`() {
        val cases = Experiments.cases(problems = 2, arms = 2, seeds = emptyList(), repeats = 2).filter { it.problem == 1 }

        assertEquals(listOf(1, 0, 1, 0), cases.map { it.arm })
    }

    @Test
    fun `a spec with an unknown key is refused`() {
        val error = assertFailsWith<IllegalArgumentException> {
            Experiments.validate(spec(configs = listOf(mapOf("cmd" to "rm"))), maxParallel = 4)
        }

        assertContains(error.message.orEmpty(), "execution requires a repository bench manifest")
    }

    @Test
    fun `a value with whitespace is refused`() {
        assertFailsWith<IllegalArgumentException> {
            Experiments.validate(spec(base = mapOf("engine" to "cp; rm -rf /")), maxParallel = 4)
        }
    }

    @Test
    fun `problems read as one selection or a list of them`() {
        val one = """{"name":"e","problems":{"suite":"a"}}"""
        val two = """{"name":"e","problems":[{"suite":"a"},{"suite":"b","max":"5"}]}"""

        val read = listOf(one, two).map { Json.decodeFromString<ExperimentSpec>(it).problems }

        assertEquals(listOf(listOf(mapOf("suite" to "a")), listOf(mapOf("suite" to "a"), mapOf("suite" to "b", "max" to "5"))), read)
    }

    @Test
    fun `repeats run back to back within each seed`() {
        val cases = Experiments.cases(problems = 1, arms = 1, seeds = listOf(7), repeats = 3)

        assertEquals(listOf(7L to 0, 7L to 1, 7L to 2), cases.map { it.seed to it.repeat })
    }

    @Test
    fun `selections interleave so every one is reached early`() {
        assertEquals(listOf("a1", "b1", "c1", "a2", "c2", "a3"), interleave(listOf(listOf("a1", "a2", "a3"), listOf("b1"), listOf("c1", "c2"))))
    }

    @Test
    fun `the default filter leaves out only what the reference ran and left undecided`() {
        val decided = Reference("scip", false, 3.0, true, true, 100, 10_000)
        val undecided = decided.copy(objective = null, feasible = null, proven = false)

        assertEquals(listOf(true, false, true), listOf(decided, undecided, null).map { ReferenceFilterMode.DECIDED.keeps(it, 60_000) })
    }

    @Test
    fun `the missing filter keeps only problems without a reference verdict`() {
        val decided = Reference("scip", false, 3.0, true, true, 100, 10_000)

        assertEquals(listOf(false, true), listOf(decided, null).map { ReferenceFilterMode.MISSING.keeps(it, 60_000) })
    }

    @Test
    fun `the unsettled filter reruns what a smaller budget left unproven`() {
        val proven = Reference("scip", false, 3.0, true, true, 100, 10_000)
        val short = proven.copy(proven = false)
        val full = short.copy(budgetMs = 60_000)

        assertEquals(listOf(false, true, false, true), listOf(proven, short, full, null).map { ReferenceFilterMode.UNSETTLED.keeps(it, 60_000) })
    }

    @Test
    fun `the default filter leaves out what the reference took over five seconds to decide`() {
        val fast = Reference("scip", false, 3.0, true, true, 2_000, 60_000)
        val slow = fast.copy(elapsedMs = 6_000)

        assertEquals(listOf(true, false), listOf(fast, slow).map { ReferenceFilterMode.DECIDED.keeps(it, 60_000) })
        assertEquals(listOf(true, false), listOf(fast, slow).map { ReferenceFilterMode.PROVEN.keeps(it, 60_000) })
    }

    @Test
    fun `a fingerprint ignores how a spec runs but not what it measures`() {
        val sweep = spec(base = mapOf("timeout" to "10000"))

        assertEquals(Experiments.fingerprint(sweep),
            Experiments.fingerprint(sweep.copy(name = "x@abc", base = sweep.base + ("ref" to "abc"), priority = -1, parallel = 3)))
        assertNotEquals(Experiments.fingerprint(sweep), Experiments.fingerprint(spec(base = mapOf("timeout" to "60000"))))
    }

    @Test
    fun `runs of one spec over different problems are told apart`() {
        val sweep = spec(base = mapOf("timeout" to "10000"))
        val a = listOf(Problem("s", "a"), Problem("s", "b"))

        assertEquals(Experiments.fingerprint(sweep, a), Experiments.fingerprint(sweep, a.reversed()))
        assertNotEquals(Experiments.fingerprint(sweep, a), Experiments.fingerprint(sweep, a + Problem("s", "c")))
    }

    @Test
    fun `a description is not part of what a spec measures`() {
        val sweep = spec(base = mapOf("timeout" to "10000"))

        assertEquals(Experiments.fingerprint(sweep), Experiments.fingerprint(sweep.copy(description = "the status sweep")))
    }

    @Test
    fun `a script job is refused, inline or from a file`() {
        val script = ExperimentSpec("s", emptyList(), script = "echo hi", description = "d")

        assertFailsWith<IllegalArgumentException> { Experiments.validate(script, maxParallel = 4) }
        assertFailsWith<IllegalArgumentException> { Experiments.validate(script.copy(script = null, scriptFile = "tools/bench.sh"), maxParallel = 4) }
    }

    @Test
    fun `a profile job names an Eignex main and no problems configs seeds or repeats`() {
        val profile = ExperimentSpec("p", emptyList(), profile = ProfileMain("com.eignex.tools.profile.Timing", listOf("a=1"), classpath = listOf("tools/lib/*")), description = "d")

        Experiments.validate(profile, maxParallel = 4)
        for (main in listOf("unqualified", "9bad.Class", "com.eignex.tools.profile.X; rm -rf /", "com.eignex.bad-name.Main")) {
            assertFailsWith<IllegalArgumentException>(main) { Experiments.validate(profile.copy(profile = ProfileMain(main, classpath = listOf("tools/lib/*"))), maxParallel = 4) }
        }
        assertFailsWith<IllegalArgumentException> {
            Experiments.validate(profile.copy(problems = listOf(mapOf("suite" to "s"))), maxParallel = 4)
        }
        assertFailsWith<IllegalArgumentException> { Experiments.validate(profile.copy(repeats = 2), maxParallel = 4) }
        assertFailsWith<IllegalArgumentException> { Experiments.validate(profile.copy(measurement = Measurement.JFR), maxParallel = 4) }
    }

    @Test
    fun `a profile runs its main under JFR in its worktree, each argument passed as it is, with OUT made`() {
        val root = Files.createTempDirectory("profile job").toFile()
        try {
            val worktree = root.resolve("work").apply { mkdirs() }
            val jobDir = root.resolve("job").apply { mkdirs() }
            // A stand-in java that records where it ran and what it was given, one argument a line.
            val javaHome = root.resolve("jdk").apply { resolve("bin").mkdirs() }
            javaHome.resolve("bin/java").apply {
                writeText("#!/usr/bin/env bash\npwd > \"\$OUT/where\"\nprintf '%s\\n' \"\$@\" > \"\$OUT/argv\"\n")
                setExecutable(true)
            }
            val args = listOf("plain", "with space", "\$(touch pwned)", "it's")
            val command = Experiments.profileCommand(worktree.path, ProfileMain("com.eignex.tools.Tool", args, classpath = listOf("tools/lib/*")), 0, "/corpus")
            val builder = ProcessBuilder("bash", "-c", command).directory(root).redirectErrorStream(true)
            builder.environment()["JOB_DIR"] = jobDir.path
            builder.environment()["JAVA_HOME"] = javaHome.path

            val process = builder.start()
            process.inputStream.bufferedReader().readText()

            assertEquals(0, process.waitFor())
            val out = jobDir.resolve("cases/0/out")
            assertEquals(worktree.canonicalPath, out.resolve("where").readText().trim())
            val argv = out.resolve("argv").readLines()
            assertTrue(argv.first().startsWith("-XX:StartFlightRecording=filename=${out.path}/profile.jfr"))
            assertEquals(listOf("com.eignex.tools.Tool") + args, argv.takeLast(args.size + 1))
            assertTrue(root.walkTopDown().none { it.name == "pwned" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a profile job gets ten minutes unless it gives a timeout, and never over two hours`() {
        assertEquals(listOf(720L, 1_920L, 7_320L, 7_320L), listOf(
            Experiments.profileTimeoutSec(Arm("a", emptyMap())),
            Experiments.profileTimeoutSec(Arm("a", mapOf("timeout" to "1800000"))),
            Experiments.profileTimeoutSec(Arm("a", mapOf("timeout" to "7200000"))),
            Experiments.profileTimeoutSec(Arm("a", mapOf("timeout" to "36000000"))),
        ))
    }

    @Test
    fun `a capped profile limit says what was asked, and one within the cap says nothing`() {
        val main = ProfileMain("com.eignex.tools.profile.Tool", classpath = listOf("tools/lib/*"))
        val within = ExperimentSpec("p", base = mapOf("timeout" to "1800000"), profile = main)
        val over = ExperimentSpec("p", host = Experiments.LAB_HOST, base = mapOf("timeout" to "36000000"),
            profile = main.copy(runTimeoutSec = 86_400))

        assertEquals("10 min, the default", Experiments.profileLimitText(ExperimentSpec("p", profile = main)))
        assertEquals("30 min", Experiments.profileLimitText(within))
        assertTrue(Experiments.profileCaps(within).isEmpty())
        assertEquals(listOf("a timeout of 10 h was asked, capped at 2 h",
            "a runTimeoutSec of 86400 s was asked, capped at 7200 s"), Experiments.profileCaps(over))
        assertEquals(7_200L, Experiments.profileRunTimeoutSec(over.profile!!))
        assertEquals(180L, Experiments.profileRunTimeoutSec(main.copy(runTimeoutSec = 180)))
        Experiments.validate(over, maxParallel = 4)
    }

    @Test
    fun `a profile timeout must be a positive number of ms`() {
        for (timeout in listOf("0", "-5", "soon")) {
            val spec = ExperimentSpec("p", base = mapOf("timeout" to timeout), profile = ProfileMain("com.eignex.tools.profile.Tool", classpath = listOf("tools/lib/*")))

            val error = assertFailsWith<IllegalArgumentException> { Experiments.validate(spec, maxParallel = 4) }
            assertContains(error.message!!, "10 min unless it says")
        }
    }

    @Test
    fun `a profile job's spec reads from JSON without problems, and a retired script job's still reads`() {
        val spec = Json.decodeFromString<ExperimentSpec>(
            """{"name":"p","description":"d","profile":{"main":"com.eignex.tools.profile.Tool","classpath":["tools/lib/*"],"args":["x"]},"base":{"ref":"main"}}""",
        )
        val old = Json.decodeFromString<ExperimentSpec>("""{"name":"s","description":"d","script":"echo hi","base":{"ref":"main"}}""")

        Experiments.validate(spec, maxParallel = 4)
        assertTrue(spec.isProfile && spec.problems.isEmpty())
        assertTrue(old.isScript && !old.isProfile)
    }

    @Test
    fun `an experiment may not set what a case solves or where it writes, nor odd option names`() {
        val spec = ExperimentSpec("e", bench = BenchSpec("bench.json"), description = "d")

        Experiments.validate(spec, maxParallel = 4)
        for (base in listOf(mapOf("out" to "/tmp/x"), mapOf("problem" to "p"), mapOf("suite" to "s"), mapOf("a b" to "1"), mapOf("x" to "a\nb"))) {
            assertFailsWith<IllegalArgumentException>(base.toString()) { Experiments.validate(spec.copy(base = base), maxParallel = 4) }
        }
        assertFailsWith<IllegalArgumentException> { Experiments.validate(spec.copy(configs = listOf(mapOf("out" to "/x"))), maxParallel = 4) }
        assertFailsWith<IllegalArgumentException> { Experiments.validate(spec.copy(grid = mapOf("out" to listOf("/x"))), maxParallel = 4) }
        assertFailsWith<IllegalArgumentException> { Experiments.validate(spec.copy(problems = listOf(mapOf("suite" to "s", "out" to "/x"))), maxParallel = 4) }
    }

    private val batched = ExperimentSpec(
        "ab-batch1", problems = listOf(mapOf("suite" to "s", "name" to "p1")), description = "first batch",
        base = mapOf("timeout" to "10000"), grid = mapOf("ref" to listOf("main", "fix/x")), seeds = listOf(1, 2), repeats = 3,
    )

    private fun activeJob(spec: ExperimentSpec, status: Status = Status.RUNNING, cancelRequested: Boolean = false) =
        Job(7, spec.name, "main", status = status, cancelRequested = cancelRequested, setupDone = false, createdAt = 0, experiment = spec)

    @Test
    fun `an experiment differing from an active one only in its problems is a split of it`() {
        val batch = batched.copy(name = "ab-batch2", description = "second batch", problems = listOf(mapOf("suite" to "s", "name" to "p2")))
        val listed = batch.copy(problems = emptyList(), problemList = listOf(Problem("s", "p3")))
        // The same arms, written another way: seeds reordered, the grid as configs, labels and the default ref spelt out.
        val reworded = batch.copy(grid = emptyMap(), seeds = listOf(2, 1),
            configs = listOf(mapOf("ref" to "main", "label" to "A"), mapOf("ref" to "fix/x", "label" to "B")))

        for (status in listOf(Status.QUEUED, Status.RUNNING)) {
            for (spec in listOf(batch, listed, reworded, batched)) assertEquals(7L, Experiments.splitOf(spec, listOf(activeJob(batched, status)))?.id)
        }
        assertContains(Experiments.splitMessage(activeJob(batched)), "lab cases 7")
    }

    @Test
    fun `other arms, seeds, repeats, timeouts or profiling are another experiment`() {
        val active = listOf(activeJob(batched))

        for (spec in listOf(
            batched.copy(grid = mapOf("ref" to listOf("main", "fix/y"))),
            batched.copy(seeds = listOf(1, 2, 3)),
            batched.copy(repeats = 5),
            batched.copy(base = mapOf("timeout" to "20000")),
            batched.copy(base = emptyMap()),
            batched.copy(measurement = Measurement.JFR, parallel = 1),
            batched.copy(measurement = Measurement.RESOURCES, parallel = 1),
        )) assertNull(Experiments.splitOf(spec, active), spec.toString())
    }

    @Test
    fun `scheduled runs, confirmations, profile jobs and ended or cancelling jobs are exempt`() {
        for (name in listOf("nightly@0123456789ab", "nightly${Confirm.SUFFIX}@0123456-41")) {
            assertNull(Experiments.splitOf(batched.copy(name = name), listOf(activeJob(batched))))
            assertNull(Experiments.splitOf(batched, listOf(activeJob(batched.copy(name = name)))))
        }
        val profile = ExperimentSpec("profile", base = mapOf("ref" to "main"), profile = ProfileMain("com.eignex.tools.profile.Tool", listOf("a"), classpath = listOf("tools/lib/*")))
        assertNull(Experiments.splitOf(profile.copy(profile = ProfileMain("com.eignex.tools.profile.Tool", listOf("b"), classpath = listOf("tools/lib/*"))), listOf(activeJob(profile))))
        for (job in listOf(
            activeJob(batched, Status.RUNNING, cancelRequested = true),
            activeJob(batched, Status.CANCELLED, cancelRequested = true),
            activeJob(batched, Status.DONE),
            activeJob(batched, Status.FAILED),
        )) assertNull(Experiments.splitOf(batched, listOf(job)), job.toString())
    }
}
