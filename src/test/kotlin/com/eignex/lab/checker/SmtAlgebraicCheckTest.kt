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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Algebraic SMT-LIB models, which Dolmen cannot read and Z3 decides against the original source with every constant
 * pinned. The real Dolmen and Z3 run where they can be had: CI installs Z3 and downloads the pinned Dolmen, and
 * `LAB_REQUIRE_CHECKERS=1` fails these tests there rather than skipping them.
 */
class SmtAlgebraicCheckTest {
    private val corpus = Files.createTempDirectory("corpus").toFile()
    private val work = Files.createTempDirectory("checks").toFile()
    private val required = System.getenv("LAB_REQUIRE_CHECKERS") == "1"

    private fun <T> tool(get: () -> T): T {
        val got = runCatching(get)
        if (required) return got.getOrThrow()
        assumeTrue(got.isSuccess, "not available here: ${got.exceptionOrNull()?.message}")
        return got.getOrThrow()
    }

    private val problem = Problem("smt-test", "QF_NRA/circle", collection = "smt-test", format = "SMTLIB")

    /** A point on the unit circle on the diagonal: both coordinates are 1/sqrt(2), the second root of 2x^2 - 1. */
    private val source = File(corpus, "smt-test/QF_NRA/circle.smt2").apply {
        parentFile.mkdirs()
        writeText("(set-info :smt-lib-version 2.6)\n(set-logic QF_NRA)\n(set-info :source |a (check-sat) in a quoted symbol|)\n" +
            "; (check-sat) in a comment\n(declare-fun x () Real)\n(declare-const |y| Real)\n" +
            "(assert (= (+ (* x x) (* y y)) 1))\n(assert (= x y))\n(check-sat)\n(exit)\n")
    }

    private fun root(index: Int) = "(root-obj (+ (- 1) (* 2 (^ x 2))) $index)"

    private fun model(x: String, y: String) = "(\n  (define-fun x () Real $x)\n  (define-fun y () Real $y)\n)"

    private fun record(witness: String): JsonObject = buildJsonObject {
        put("solver", "klause")
        put("kind", "satisfy")
        put("feasible", true)
        put("finalWitness", witness)
        put("sourceHashes", buildJsonObject { put("model", sha256(source)) })
    }

    private fun script(text: String) = Files.createTempFile("tool", "").toFile().apply { writeText("#!/bin/sh\n$text\n"); setExecutable(true) }

    private val unbound = { script("cat > /dev/null; echo E:unbound-id >&2; exit 4") }

    private fun fakeZ3(text: String) = Z3Tool(script(text), "4.16.0", "fake", 2048)

    private fun checker(dolmen: () -> File, z3: () -> Z3Tool, timeoutMs: Long = Z3AlgebraicCheck.TIMEOUT_MS) =
        SourceChecker(listOf(SmtChecker(dolmen, algebraic = Z3AlgebraicCheck(z3, timeoutMs))), SourceLocator(corpus, hash = ::sha256), work)

    @Test
    fun `Z3 decides an algebraic model Dolmen cannot read, valid when it satisfies the source and invalid when it does not`() {
        val dolmen = Dolmen(Files.createTempDirectory("checkers").toFile())
        tool { dolmen.binary() }
        val install = Z3Install()
        val z3 = tool { install.tool() }
        val checks = checker(dolmen::binary, install::tool)

        val valid = checks.check(problem, record(model(root(2), root(2))))!!
        assertEquals(LabCheck.VALID, valid.state, valid.reason + "\n" + valid.diagnostic)
        assertEquals("lab-smtlib-z3-algebraic-${z3.version}-v1", valid.policy)
        assertEquals("z3", valid.checker)
        assertEquals(z3.version, valid.checkerVersion)
        assertEquals(z3.sha256, valid.checkerSha256)
        assertEquals(mapOf("model" to sha256(source)), valid.sourceSha256)
        assertEquals(sha256(model(root(2), root(2)).toByteArray()), valid.witnessSha256)
        val diagnostic = valid.diagnostic!!
        assertContains(diagnostic, "dolmen: unknown: Dolmen could not decide (exit 4): E:unbound-id")
        assertContains(diagnostic, "-smt2 pinned.smt2")
        assertContains(diagnostic, "(assert (= |y| ${root(2)}))")
        assertContains(diagnostic, "output: sat")

        // The negative root, -1/sqrt(2), for y alone breaks x = y; for both it satisfies the source as well.
        val invalid = checks.check(problem, record(model(root(2), root(1))))!!
        assertEquals(LabCheck.INVALID, invalid.state, invalid.reason + "\n" + invalid.diagnostic)
        assertEquals(valid.policy, invalid.policy)
        assertEquals(LabCheck.VALID, checks.check(problem, record(model(root(1), root(1))))!!.state)
        // A rational model off the circle is invalid too, and Dolmen, reading it, decides it alone.
        val rational = checks.check(problem, record(model("1.0", "1.0")))!!
        assertEquals(LabCheck.INVALID, rational.state)
        assertEquals(Policies.SMTLIB, rational.policy)
        // A root-obj Z3 refuses (2x^2 - 1 has no third root) is undecided: Z3 reports it, then would check without it.
        val refused = checks.check(problem, record(model(root(3), root(2))))!!
        assertEquals(LabCheck.UNKNOWN, refused.state, refused.reason)
        assertContains(refused.reason, "Z3 could not read the pinned source")
    }

    @Test
    fun `Dolmen decides first, and Z3 only an algebraic model Dolmen left undecided`() {
        var asked = 0
        val z3 = { asked++; fakeZ3("echo sat") }
        val algebraic = record(model(root(2), root(2)))
        fun dolmen(text: String) = { script("cat > /dev/null; $text") }

        for (decided in listOf("exit 0", "echo E:bad-model >&2; exit 6")) {
            val check = checker(dolmen(decided), z3).check(problem, algebraic)!!
            assertEquals(Policies.SMTLIB, check.policy)
            assertEquals("dolmen", check.checker)
            assertContains(check.diagnostic!!, "--check-model=true")
        }
        val rational = checker(unbound, z3).check(problem, record(model("1.0", "0.0")))!!
        assertEquals(LabCheck.UNKNOWN, rational.state)
        assertEquals(Policies.SMTLIB, rational.policy)
        assertEquals(0, asked)

        val decided = checker(unbound, z3).check(problem, algebraic)!!
        assertEquals(1, asked)
        assertEquals(LabCheck.VALID, decided.state)
        assertEquals(Z3AlgebraicCheck.policy("4.16.0"), decided.policy)

        // No Z3 keeps Dolmen's verdict, saying why.
        val missing = checker(unbound, { error("z3 is not installed") }).check(problem, algebraic)!!
        assertEquals(LabCheck.UNKNOWN, missing.state)
        assertEquals(Policies.SMTLIB, missing.policy)
        assertContains(missing.reason, "Z3 could not run: z3 is not installed")
    }

    @Test
    fun `Z3's unknown, its own timeout and a hang are all unknown`() {
        val algebraic = record(model(root(2), root(2)))
        for (output in listOf("echo unknown", "echo timeout", "echo '(error \"line 9: invalid root object\")'; echo sat", "exit 1")) {
            val check = checker(unbound, { fakeZ3(output) }).check(problem, algebraic)!!
            assertEquals(LabCheck.UNKNOWN, check.state, output)
            assertEquals(Z3AlgebraicCheck.policy("4.16.0"), check.policy)
        }
        val started = System.nanoTime()
        val hang = checker(unbound, { fakeZ3("exec sleep 60") }, timeoutMs = 300).check(problem, algebraic)!!
        assertTrue((System.nanoTime() - started) / 1_000_000 < 20_000, "the hang was not cut short")
        assertEquals(LabCheck.UNKNOWN, hang.state)
        assertContains(hang.reason, "Z3 did not finish")
        assertContains(hang.diagnostic!!, "none (killed)")
    }

    @Test
    fun `pinning cuts the source at its one check-sat and needs a value for every declared constant`() {
        val text = source.readText()
        val pinned = assertIs<Z3AlgebraicCheck.Pinned.Input>(Z3AlgebraicCheck.pin(text, "sat\n" + model("1", "2")))
        assertEquals(text.substringBefore("(assert (= x y))\n") + "(assert (= x y))\n\n(assert (= x 1))\n(assert (= |y| 2))\n(check-sat)\n(exit)\n", pinned.text)

        val partial = assertIs<Z3AlgebraicCheck.Pinned.Refused>(Z3AlgebraicCheck.pin(text, "((define-fun x () Real 1))"))
        assertEquals(LabCheck.UNKNOWN, partial.state)
        assertContains(partial.reason, "no value to |y|")
        val function = assertIs<Z3AlgebraicCheck.Pinned.Refused>(Z3AlgebraicCheck.pin(text, "((define-fun x ((a Real)) Real a) (define-fun y () Real 1))"))
        assertEquals(LabCheck.UNKNOWN, function.state)
        val incremental = assertIs<Z3AlgebraicCheck.Pinned.Refused>(Z3AlgebraicCheck.pin("$text(push 1)(check-sat)", model("1", "2")))
        assertEquals(LabCheck.UNSUPPORTED, incremental.state)
        val uninterpreted = assertIs<Z3AlgebraicCheck.Pinned.Refused>(
            Z3AlgebraicCheck.pin("(declare-fun f (Real) Real)(assert (= (f 1) 2))(check-sat)", "()"))
        assertEquals(LabCheck.UNSUPPORTED, uninterpreted.state)
        assertTrue(Z3AlgebraicCheck.algebraic(model(root(2), "1")))
        assertFalse(Z3AlgebraicCheck.algebraic(model("1", "(/ 1 2)")))
    }
}
