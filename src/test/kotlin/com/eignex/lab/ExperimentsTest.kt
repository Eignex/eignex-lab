package com.eignex.lab

import kotlinx.serialization.json.Json
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
    fun `a missing bench launcher cannot be mistaken for legacy provenance support`() {
        val root = Files.createTempDirectory("missing bench ").toFile()
        try {
            root.resolve("klause-bench").mkdirs()
            val process = ProcessBuilder("bash", "-c", Experiments.captureProvenance(root.path)).start()
            assertNotEquals(0, process.waitFor())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `successful capture without a manifest fails setup`() {
        val root = Files.createTempDirectory("empty manifest ").toFile()
        try {
            val bench = root.resolve("klause-bench/build/install/klause-bench/bin/klause-bench")
            bench.parentFile.mkdirs()
            bench.writeText("#!/bin/sh\ncase \"\$1\" in --help) echo 'bench provenance out=<file>'; exit 1;; provenance) exit 0;; esac\n")
            bench.setExecutable(true)
            val process = ProcessBuilder("bash", "-c", Experiments.captureProvenance(root.path)).start()
            assertNotEquals(0, process.waitFor())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `setup captures manifests for supported revisions and keeps older revisions runnable`() {
        for (supported in listOf(true, false)) {
            val root = Files.createTempDirectory("provenance path ").toFile()
            try {
                val bench = root.resolve("klause-bench/build/install/klause-bench/bin/klause-bench")
                bench.parentFile.mkdirs()
                val help = if (supported) "bench provenance out=<file>" else "bench solve-one"
                // A bench's --help exits 1, as klause-bench's does, whether or not it can write a manifest.
                bench.writeText("#!/bin/sh\ncase \"\$1\" in --help) echo '$help'; exit 1;; provenance) pwd > \"\${2#out=}\";; esac\n")
                bench.setExecutable(true)

                val process = ProcessBuilder("bash", "-c", Experiments.captureProvenance(root.path))
                    .redirectErrorStream(true).start()
                process.inputStream.bufferedReader().readText()
                val exit = process.waitFor()

                assertEquals(0, exit)
                assertEquals(supported, root.resolve("klause-bench/build/provenance.json").exists())
                if (supported) {
                    assertEquals(root.resolve("klause-bench").path,
                        root.resolve("klause-bench/build/provenance.json").readText().trim())
                }
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `case commands select their manifest and clear stale inherited paths for older revisions`() {
        for (captured in listOf(true, false)) {
            val root = Files.createTempDirectory("case provenance ").toFile()
            try {
                val bench = root.resolve("klause-bench/build/install/klause-bench/bin/klause-bench")
                bench.parentFile.mkdirs()
                bench.writeText("#!/bin/sh\nprintf '%s' \"\${KLAUSE_BENCH_PROVENANCE-unset}\" > \"\$RECORDED\"\n")
                bench.setExecutable(true)
                val manifest = root.resolve("klause-bench/build/provenance.json")
                if (captured) manifest.writeText("{}")
                val recorded = root.resolve("environment.txt")
                val command = Experiments.command(root.path, Problem("s", "p"), Arm("a", emptyMap()), null, 0, "/corpus")
                val builder = ProcessBuilder("bash", "-c", command).redirectErrorStream(true)
                builder.environment().putAll(mapOf("KLAUSE_BENCH_PROVENANCE" to "/stale",
                    "JOB_DIR" to root.path, "RECORDED" to recorded.path))

                val process = builder.start()
                process.inputStream.bufferedReader().readText()
                val exit = process.waitFor()

                assertEquals(0, exit)
                assertEquals(if (captured) manifest.path else "unset", recorded.readText())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `a failed manifest capture fails setup`() {
        val root = Files.createTempDirectory("provenance failure").toFile()
        try {
            val bench = root.resolve("klause-bench/build/install/klause-bench/bin/klause-bench")
            bench.parentFile.mkdirs()
            bench.writeText("#!/bin/sh\ncase \"\$1\" in --help) echo 'bench provenance out=<file>';; provenance) exit 7;; esac\n")
            bench.setExecutable(true)

            val process = ProcessBuilder("bash", "-c", Experiments.captureProvenance(root.path))
                .redirectErrorStream(true).start()
            process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()

            assertEquals(7, exit)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `CLI profiling requires an explicit serial AWS experiment`() {
        val experiment = spec().copy(profileCli = true)

        assertFailsWith<IllegalArgumentException> { Experiments.validate(experiment, maxParallel = 4) }
        assertFailsWith<IllegalArgumentException> {
            Experiments.validate(experiment.copy(host = "aws"), maxParallel = 4)
        }
        Experiments.validate(experiment.copy(host = "aws", parallel = 1), maxParallel = 4)
        assertNotEquals(Experiments.fingerprint(spec()), Experiments.fingerprint(experiment))
    }

    @Test
    fun `exact policy is validated and forwarded to each case`() {
        for (exact in listOf("true", "false")) {
            val experiment = spec(base = mapOf("exact" to exact))
            Experiments.validate(experiment, maxParallel = 4)
            val arm = Experiments.arms(experiment).single()

            val command = Experiments.command("/w/7", Problem("hakank", "agprice"), arm, 1, 0, "/corpus")

            assertContains(command, "'exact=$exact'")
        }
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

        assertContains(error.message.orEmpty(), "unknown config key 'cmd'")
    }

    @Test
    fun `a value with whitespace is refused`() {
        assertFailsWith<IllegalArgumentException> {
            Experiments.validate(spec(base = mapOf("engine" to "cp; rm -rf /")), maxParallel = 4)
        }
    }

    @Test
    fun `a case solves one problem with its arm's arguments and seed`() {
        val arm = Arm("x", mapOf("ref" to "main", "timeout" to "3000", "param.restarts" to "luby"))

        val command = Experiments.command("/w/7", Problem("xcsp3-cop", "Rack-1"), arm, 5, 12, "/corpus")

        assertContains(
            command,
            "solve-one 'suite=xcsp3-cop' 'problem=Rack-1' 'param=restarts=luby' 'timeout=3000' 'solver-seed=5' " +
                "out=\"\$JOB_DIR/cases/12\"",
        )
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
    fun `an experiment that runs the reference solver is not filtered by it`() {
        val reference = spec(base = mapOf("backend" to "reference"))

        assertEquals(listOf(ReferenceFilterMode.DECIDED, ReferenceFilterMode.ANY),
            listOf(spec(), reference).map { Experiments.referenceFilter(it, mapOf("suite" to "s")) })
    }

    @Test
    fun `a filtered selection is capped per family and in all after filtering`() {
        val problems = listOf("a" to 1, "a" to 2, "a" to 3, "b" to 1, "b" to 2).map { (f, i) -> Problem("s", "$f$i", family = f) }

        assertEquals(listOf("a1", "b1", "a2"), Experiments.cap(problems, perFamily = 2, max = 3).map { it.problem })
    }

    @Test
    fun `a capped selection is asked of the bench without its caps`() {
        assertEquals(mapOf("suite" to "s", "per-family" to "1000000", "seed" to "1"),
            Experiments.uncapped(mapOf("suite" to "s", "per-family" to "1", "max" to "20", "seed" to "1")))
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
    fun `a selection with one problem per family and no per-family is flagged as capped by the suite`() {
        val one = listOf("a", "b", "c").map { Problem("s", "$it/1", family = it) }
        val two = one + Problem("s", "a/2", family = "a")

        assertNotNull(Experiments.defaultCapped(mapOf("suite" to "s"), one))
        assertNull(Experiments.defaultCapped(mapOf("suite" to "s", "per-family" to "1"), one))
        assertNull(Experiments.defaultCapped(mapOf("suite" to "s"), two))
    }

    @Test
    fun `a fingerprint ignores how a spec runs but not what it measures`() {
        val sweep = spec(base = mapOf("timeout" to "10000"))

        assertEquals(Experiments.fingerprint(sweep),
            Experiments.fingerprint(sweep.copy(name = "x@abc", base = sweep.base + ("ref" to "abc"), priority = -1, parallel = 3)))
        assertNotEquals(Experiments.fingerprint(sweep), Experiments.fingerprint(spec(base = mapOf("timeout" to "60000"))))
    }

    @Test
    fun `a named set is selected whole, without a suite`() {
        val sweep = ExperimentSpec("e", listOf(mapOf("set" to "sweep")))

        Experiments.validate(sweep, maxParallel = 6)
        assertEquals(ReferenceFilterMode.ANY, Experiments.referenceFilter(sweep, mapOf("set" to "sweep")))
        assertFailsWith<IllegalArgumentException> { Experiments.validate(ExperimentSpec("e", listOf(mapOf("max" to "5"))), maxParallel = 6) }
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
    fun `a script job names one script and no problems, configs, seeds or repeats`() {
        val script = ExperimentSpec("s", emptyList(), script = "echo hi", description = "d")

        Experiments.validate(script, maxParallel = 4)
        Experiments.validate(script.copy(script = null, scriptFile = "tools/bench.sh"), maxParallel = 4)
        assertFailsWith<IllegalArgumentException> { Experiments.validate(script.copy(scriptFile = "tools/bench.sh"), maxParallel = 4) }
        assertFailsWith<IllegalArgumentException> {
            Experiments.validate(script.copy(problems = listOf(mapOf("suite" to "s"))), maxParallel = 4)
        }
        assertFailsWith<IllegalArgumentException> { Experiments.validate(script.copy(repeats = 2), maxParallel = 4) }
    }

    @Test
    fun `a script runs in its worktree with OUT and the built tools named, and keeps what it writes to OUT`() {
        val root = Files.createTempDirectory("script job").toFile()
        try {
            val worktree = root.resolve("work").apply { mkdirs() }
            val jobDir = root.resolve("job").apply { mkdirs() }
            val spec = ExperimentSpec("s", emptyList(), script = "pwd > \"\$OUT/where\"; echo \"\$KLAUSE_CLI\" > \"\$OUT/cli\"", description = "d")
            val builder = ProcessBuilder("bash", "-c", Experiments.scriptCommand(worktree.path, spec, 0, "/corpus")).redirectErrorStream(true)
            builder.environment()["JOB_DIR"] = jobDir.path

            val process = builder.start()
            process.inputStream.bufferedReader().readText()

            assertEquals(0, process.waitFor())
            assertEquals(worktree.canonicalPath, jobDir.resolve("cases/0/out/where").readText().trim())
            assertTrue(jobDir.resolve("cases/0/out/cli").readText().trim().endsWith("klause-cli/build/install/klause-cli-jvm/bin/klause-cli"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a script job gets an hour unless it gives a timeout`() {
        assertEquals(listOf(3_720L, 720L), listOf(
            Experiments.scriptTimeoutSec(Arm("a", emptyMap())),
            Experiments.scriptTimeoutSec(Arm("a", mapOf("timeout" to "600000"))),
        ))
    }

    @Test
    fun `a script job's spec reads from JSON without problems`() {
        val spec = Json.decodeFromString<ExperimentSpec>(
            """{"name":"s","description":"d","script":"echo hi","base":{"ref":"main","timeout":"300000"}}""",
        )

        Experiments.validate(spec, maxParallel = 4)
        assertTrue(spec.isScript && spec.problems.isEmpty())
    }
}
