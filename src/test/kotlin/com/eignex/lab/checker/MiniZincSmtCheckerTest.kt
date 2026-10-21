package com.eignex.lab.checker

import com.eignex.lab.Problem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * MiniZinc with Gecode and Dolmen, the real tools where they can be had: CI installs MiniZinc and downloads the pinned
 * Dolmen, and `LAB_REQUIRE_CHECKERS=1` fails these tests there rather than skipping them.
 */
class MiniZincSmtCheckerTest {
    private val corpus = Files.createTempDirectory("corpus").toFile()
    private val work = Files.createTempDirectory("checks").toFile()
    private val required = System.getenv("LAB_REQUIRE_CHECKERS") == "1"

    private fun <T> tool(get: () -> T): T {
        val got = runCatching(get)
        if (required) return got.getOrThrow()
        assumeTrue(got.isSuccess, "not available here: ${got.exceptionOrNull()?.message}")
        return got.getOrThrow()
    }

    private fun file(path: String, text: String) = File(corpus, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun record(witness: String, objective: String?, hashes: Map<String, File>, maximize: Boolean = false,
                       extra: Map<String, String> = emptyMap()): JsonObject = buildJsonObject {
        put("solver", "klause")
        put("kind", if (objective == null) "satisfy" else "optimize")
        put("maximize", maximize)
        put("feasible", true)
        objective?.let { put("objective", it.toDouble()); put("exactObjective", it) }
        put("finalWitness", witness)
        put("sourceHashes", buildJsonObject { hashes.forEach { (k, f) -> put(k, sha256(f)) } })
        put("stats", buildJsonObject { extra.forEach { (k, v) -> put(k, v) } })
    }

    // MiniZinc

    private val permutation = """
        int: n;
        array[1..n] of var 1..n: q;
        include "alldifferent.mzn";
        constraint alldifferent(q);
        solve minimize sum(i in 1..n)(i * q[i]);
    """.trimIndent()

    private val mzn = Problem("mzn-test", "perm/three", collection = "mzn-test", format = "MINIZINC")

    private fun minizinc(install: () -> MiniZincTool) = SourceChecker(listOf(MiniZincChecker(install)), SourceLocator(corpus, hash = ::sha256), work)

    @Test
    fun `MiniZinc with Gecode completes a valid witness and refutes a violated one or an unreachable objective`() {
        val install = MiniZincInstall()
        tool { install.tool() }
        val model = file("mzn-test/perm/perm.mzn", permutation)
        val data = file("mzn-test/perm/three.dzn", "n = 3;\n")
        val hashes = mapOf("model" to model, "data" to data)
        fun check(witness: String, objective: String) = minizinc(install::tool).check(mzn, record(witness, objective, hashes))!!

        val valid = check("q = [3, 2, 1];\n_objective = 10;", "10")
        assertEquals(LabCheck.VALID, valid.state, valid.reason)
        assertEquals(Policies.MINIZINC, valid.policy)
        assertContains(valid.checkerVersion!!, "gecode")
        assertEquals(mapOf("model" to sha256(model), "data" to sha256(data)), valid.sourceSha256)
        assertEquals(LabCheck.INVALID, check("q = [1, 1, 2];\n_objective = 7;", "7").state)
        val mismatch = check("q = [3, 2, 1];\n_objective = 9;", "9")
        assertEquals(LabCheck.INVALID, mismatch.state, mismatch.reason)
        assertContains(mismatch.reason, "claimed objective 9")

        val satisfy = file("mzn-test/sat/sat.mzn", "var 1..5: x;\nvar 1..5: y;\nconstraint x + y = 7;\nsolve satisfy;\n")
        val problem = Problem("mzn-test", "sat/sat", collection = "mzn-test", format = "MINIZINC")
        assertEquals(LabCheck.VALID, minizinc(install::tool).check(problem, record("x = 3;\ny = 4;", null, mapOf("model" to satisfy)))!!.state)
        assertEquals(LabCheck.INVALID, minizinc(install::tool).check(problem, record("x = 1;\ny = 1;", null, mapOf("model" to satisfy)))!!.state)

        // A witness MiniZinc cannot read as data for the model is unsupported, in its words; the data file's name
        // keeps a `.json` its problem name has too.
        val enums = file("mzn-test/e/e.mzn", "enum C = {R, G};\narray[1..2] of var C: c;\nconstraint c[1] != c[2];\nsolve satisfy;\n")
        val enumData = file("mzn-test/e/d1.json.dzn", "% no data\n")
        val enumProblem = Problem("mzn-test", "e/d1.json", collection = "mzn-test", format = "MINIZINC")
        val unreadable = minizinc(install::tool).check(enumProblem, record("c = [1, 2];", null, mapOf("model" to enums, "data" to enumData)))!!
        assertEquals(LabCheck.UNSUPPORTED, unreadable.state, unreadable.reason)
        assertContains(unreadable.reason, "Error")
        assertEquals(LabCheck.VALID, minizinc(install::tool).check(enumProblem, record("c = [R, G];", null, mapOf("model" to enums, "data" to enumData)))!!.state)
    }

    @Test
    fun `floats have no external exact checker, and a missing MiniZinc is not-run`() {
        val none: () -> MiniZincTool = { error("minizinc is not installed (not on PATH, LAB_MINIZINC unset)") }
        val floats = file("mzn-test/f/f.mzn", "var 0.0..1.0: x;\nsolve maximize x;\n")
        val problem = Problem("mzn-test", "f/f", collection = "mzn-test", format = "MINIZINC")
        val unsupported = minizinc(none).check(problem, record("x = 1.0;", "1", mapOf("model" to floats)))!!
        assertEquals(LabCheck.UNSUPPORTED, unsupported.state)
        assertEquals("no external exact checker for floats", unsupported.reason)

        val ints = file("mzn-test/i/i.mzn", "var 0..1: x;\nsolve maximize x;\n")
        val intProblem = Problem("mzn-test", "i/i", collection = "mzn-test", format = "MINIZINC")
        val exact = minizinc(none).check(intProblem, record("x = 1;\n% klause-exact: x = 1", "1", mapOf("model" to ints)))!!
        assertEquals(LabCheck.UNSUPPORTED, exact.state)
        val approximated = minizinc(none).check(intProblem, record("x = 1;", "1", mapOf("model" to ints), extra = mapOf("floatApproximation" to "true")))!!
        assertEquals(LabCheck.UNSUPPORTED, approximated.state)
        val flat = minizinc(none).check(intProblem, record("x = 1;\nX_INTRODUCED_24_ = [1, 2];", "1", mapOf("model" to ints)))!!
        assertEquals(LabCheck.UNSUPPORTED, flat.state)
        assertContains(flat.reason, "X_INTRODUCED_")
        // A solver that will not start is a check that did not run, not a witness MiniZinc cannot read.
        val broken = Files.createTempFile("minizinc", "").toFile().apply {
            writeText("#!/bin/sh\necho '=====ERROR====='\necho 'fzn-gecode: error while loading shared libraries' >&2\nexit 1\n")
            setExecutable(true)
        }
        val crashed = minizinc { MiniZincTool(broken, "minizinc test", "0") }.check(intProblem, record("x = 1;", "1", mapOf("model" to ints)))!!
        assertEquals(LabCheck.NOT_RUN, crashed.state, crashed.reason)
        assertContains(crashed.reason, "shared libraries")
        val notRun = minizinc(none).check(intProblem, record("x = 1;", "1", mapOf("model" to ints)))!!
        assertEquals(LabCheck.NOT_RUN, notRun.state)
        assertContains(notRun.reason, "minizinc is not installed")
        assertEquals("x = 1;\ny = [1, 2];\n", MiniZincChecker.dzn("% klause-attribution: 3\nx = 1;\n_objective = 4;\ny = [1, 2];"))
    }

    // SMT-LIB

    private val smt = Problem("smt-test", "QF_LIA/p", collection = "smt-test", format = "SMTLIB")
    private val source = "(set-logic QF_LIA)\n(declare-fun x () Int)\n(declare-fun y () Int)\n(assert (> (+ x y) 4))\n(assert (< x 3))\n(check-sat)\n(exit)\n"

    private fun smtChecker(dolmen: () -> File, timeoutMs: Long = SmtChecker.TIMEOUT_MS) =
        SourceChecker(listOf(SmtChecker(dolmen, timeoutMs)), SourceLocator(corpus, hash = ::sha256), work)

    private fun model(x: Int, y: Int) = "(\n(define-fun x () Int $x)\n(define-fun y () Int $y)\n)"

    @Test
    fun `Dolmen accepts a satisfying model and rejects a bad one`() {
        val dolmen = Dolmen(Files.createTempDirectory("checkers").toFile())
        tool { dolmen.binary() }
        val problem = file("smt-test/QF_LIA/p.smt2", source)
        val valid = smtChecker(dolmen::binary).check(smt, record(model(1, 4), null, mapOf("model" to problem)))!!
        assertEquals(LabCheck.VALID, valid.state, valid.reason)
        assertEquals(Policies.SMTLIB, valid.policy)
        assertEquals(Dolmen.LINUX_SHA256.takeIf { System.getProperty("os.name") == "Linux" } ?: Dolmen.MAC_SHA256, valid.checkerSha256)
        val invalid = smtChecker(dolmen::binary).check(smt, record(model(3, 4), null, mapOf("model" to problem)))!!
        assertEquals(LabCheck.INVALID, invalid.state, invalid.reason)
    }

    @Test
    fun `Dolmen's exit decides, an optimisation claim is unsupported, and a hang is not-run`() {
        val problem = file("smt-test/QF_LIA/p.smt2", source)
        fun fake(script: String) = Files.createTempFile("dolmen", "").toFile().apply { writeText("#!/bin/sh\n$script\n"); setExecutable(true) }
        fun check(script: String, timeoutMs: Long = SmtChecker.TIMEOUT_MS) =
            smtChecker({ fake(script) }, timeoutMs).check(smt, record(model(1, 4), null, mapOf("model" to problem)))!!

        assertEquals(LabCheck.VALID, check("cat > /dev/null; exit 0").state)
        assertEquals(LabCheck.INVALID, check("echo E:bad-model >&2; exit 6").state)
        assertEquals(LabCheck.UNKNOWN, check("echo E:limit >&2; exit 6").state)
        assertEquals(LabCheck.NOT_RUN, check("sleep 5", timeoutMs = 300).state)
        val optimisation = smtChecker({ fake("exit 0") }).check(smt, record(model(1, 4), "5", mapOf("model" to problem)))!!
        assertEquals(LabCheck.UNSUPPORTED, optimisation.state)
        assertContains(optimisation.reason, "UNSAT claims are compared with stored reference verdicts")
        assertEquals(null, Dolmen(work, os = "Windows 11", arch = "amd64").artifact)
    }
}
