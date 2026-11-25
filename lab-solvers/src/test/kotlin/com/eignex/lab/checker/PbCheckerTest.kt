package com.eignex.lab.checker

import com.eignex.lab.domain.Problem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** VeriPB checks OPB and WCNF points: feasibility and the exact claimed objective, never optimality. */
class PbCheckerTest {
    private val corpus = Files.createTempDirectory("corpus").toFile()
    private val work = Files.createTempDirectory("checks").toFile()
    private val required = System.getenv("LAB_REQUIRE_CHECKERS") == "1"

    private val veripb: File by lazy {
        val got = runCatching { VeriPb(Files.createTempDirectory("checkers").toFile()).binary() }
        if (!required) assumeTrue(got.isSuccess, "not available here: ${got.exceptionOrNull()?.message}")
        got.getOrThrow()
    }

    private fun file(path: String, text: String) = File(corpus, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun record(witness: String, objective: String?, source: File, maximize: Boolean = false): JsonObject = buildJsonObject {
        put("solver", "klause")
        put("kind", if (objective == null) "satisfy" else "optimize")
        put("maximize", maximize)
        put("feasible", true)
        objective?.let { put("objective", it.toDouble()); put("exactObjective", it) }
        put("finalWitness", witness)
        put("sourceHashes", buildJsonObject { put("model", sha256(source)) })
    }

    private fun check(format: String, path: String, source: String, witness: String, objective: String?,
                      maximize: Boolean = false, binary: () -> File = { veripb }, timeoutMs: Long = PbChecker.TIMEOUT_MS): LabCheck {
        val tool = binary() // outside the checker, which turns a skipped assumption into a not-run verdict
        val model = file(path, source)
        val problem = Problem("pb-test", path.substringAfter('/').substringBefore('.'), collection = "pb-test", format = format)
        return SourceChecker(listOf(PbChecker(format, { tool }, timeoutMs)), SourceLocator(corpus, hash = ::sha256), work)
            .check(problem, record(witness, objective, model, maximize))!!
    }

    // min 2 x1 + 3 x2 + x3 subject to x1 + x2 >= 1 and x2 + x3 >= 1
    private val opb = "* #variable= 3 #constraint= 2\nmin: +2 x1 +3 x2 +1 x3 ;\n+1 x1 +1 x2 >= 1 ;\n+1 x2 +1 x3 >= 1 ;\n"
    private fun opb(witness: String, objective: String?, source: String = opb, maximize: Boolean = false) =
        check("OPB", "pb-test/p.opb", source, witness, objective, maximize)

    @Test
    fun `an OPB point is valid only when feasible and its objective is exactly the claim`() {
        val valid = opb("v -x1 x2 -x3", "3")
        assertEquals(LabCheck.VALID, valid.state, valid.reason)
        assertEquals(Policies.OPB, valid.policy)
        assertEquals("veripb", valid.checker)
        assertEquals(VeriPb.VERSION, valid.checkerVersion)
        assertEquals(sha256(veripb), valid.checkerSha256)
        assertContains(valid.inputs.single().text, "sol ~x1 x2 ~x3;")
        assertEquals(LabCheck.VALID, opb("v x1 -x2\nv x3", "3").state, "multiple v lines")
        assertEquals(LabCheck.INVALID, opb("v x1 -x2 -x3", "2").state, "violates x2 + x3 >= 1")
        assertEquals(LabCheck.INVALID, opb("v -x1 x2 -x3", "2").state, "a better objective than the witness has")
        assertEquals(LabCheck.INVALID, opb("v -x1 x2 -x3", "4").state, "a worse objective than the witness has")
        assertEquals(LabCheck.INVALID, opb("v -x1 x2 x1 -x3", "3").state, "contradictory")
        assertEquals(LabCheck.UNKNOWN, opb("v -x1", "3").state, "an objective variable left unassigned")
        assertEquals(LabCheck.INVALID, opb("v -x1 x2; conclusion SAT", "3").state, "proof syntax in the witness")
        assertEquals(LabCheck.UNKNOWN, opb("v -x1 x2 -x3", "3.5").state, "a fractional claim")
    }

    @Test
    fun `decision and maximisation OPB claims follow the source`() {
        val decision = "+1 x1 +1 x2 >= 1 ;\n"
        assertEquals(LabCheck.VALID, opb("v x1 -x2", null, decision).state)
        assertEquals(LabCheck.INVALID, opb("v -x1 -x2", null, decision).state)
        assertEquals(LabCheck.INVALID, opb("v -x1", null, "+1 x1 +1 x2 +1 x3 >= 1 ;\n").state, "incomplete")
        val maximum = "max: +2 x1 +3 x2 ;\n+1 x1 +1 x2 <= 1 ;\n"
        assertEquals(LabCheck.VALID, opb("v -x1 x2", "3", maximum, maximize = true).state)
        assertEquals(LabCheck.INVALID, opb("v -x1 x2", "2", maximum, maximize = true).state)
    }

    @Test
    fun `WBO and nonlinear OPB sources are unsupported in VeriPB's words`() {
        val wbo = "* #variable= 2 #constraint= 1 #soft= 1\nsoft: 10 ;\n[1] +1 x1 >= 1 ;\n+1 x1 +1 x2 >= 1 ;\n"
        val unsupported = opb("v -x1 x2", "1", wbo)
        assertEquals(LabCheck.UNSUPPORTED, unsupported.state, unsupported.reason)
        assertContains(unsupported.reason, "cannot read this source")
    }

    // hard x1 or x2; soft x1 (1), x2 or x3 (2), not x3 (4)
    private fun wcnf(source: String, witness: String, objective: String) = check("WCNF", "pb-test/w.wcnf", source, witness, objective)
    private val units = "c units only\nh 1 2 0\n1 1 0\n4 -3 0\n"
    private val clauses = "h 1 2 0\n1 1 0\n2 2 3 0\n4 -3 0\n"

    @Test
    fun `a WCNF point is checked from a MaxSAT Evaluation bit string or signed literals`() {
        val valid = wcnf(units, "v 011", "5")
        assertEquals(LabCheck.VALID, valid.state, valid.reason)
        assertEquals(Policies.WCNF, valid.policy)
        assertEquals(LabCheck.VALID, wcnf(units, "v -1 2 -3 0", "1").state)
        assertEquals(LabCheck.INVALID, wcnf(units, "v 011", "4").state)
        assertEquals(LabCheck.INVALID, wcnf(units, "v 001", "5").state, "violates the hard clause")
        assertEquals(LabCheck.VALID, wcnf(clauses, "v 100", "2").state, "a falsified clause's blocking variable propagates")
        assertEquals(LabCheck.INVALID, wcnf(clauses, "v 100", "3").state)
        val blocking = wcnf(clauses, "v 010", "1")
        assertEquals(LabCheck.UNKNOWN, blocking.state, "a satisfied clause leaves its blocking variable to the witness")
        assertContains(blocking.reason, "not assigned by the solution")
        val old = wcnf("p wcnf 3 3 10\n10 1 2 0\n1 1 0\n4 -3 0\n", "v 011", "5")
        assertEquals(LabCheck.UNSUPPORTED, old.state, old.reason)
    }

    @Test
    fun `VeriPB's output decides, and a hang is not-run`() {
        fun fake(script: String) = Files.createTempFile("veripb", "").toFile().apply { writeText("#!/bin/sh\n$script\n"); setExecutable(true) }
        fun run(script: String, timeoutMs: Long = PbChecker.TIMEOUT_MS) =
            check("OPB", "pb-test/p.opb", opb, "v -x1 x2 -x3", "3", binary = { fake(script) }, timeoutMs = timeoutMs)
        assertEquals(LabCheck.VALID, run("echo 's VERIFIED BOUNDS ${PbChecker.LOWER} <= obj <= 3'").state)
        assertEquals(LabCheck.UNKNOWN, run("echo 's VERIFIED BOUNDS 0 <= obj <= 3'").state, "a different conclusion")
        assertEquals(LabCheck.UNKNOWN, run("echo 's VERIFIED BOUNDS ${PbChecker.LOWER} <= obj <= 3'; echo 'Warning: The proof used unchecked assumptions.'").state)
        assertEquals(LabCheck.UNKNOWN, run("echo 'Error: something else' >&2; exit 1").state)
        assertEquals(LabCheck.NOT_RUN, run("sleep 5", timeoutMs = 300).state)
        assertNull(VeriPb(work, os = "Windows 11", arch = "amd64").artifact)
    }

    @Test
    fun `witnesses translate to VeriPB literals without interpretation`() {
        assertEquals("~x1 x2 ~x3", PbChecker.solution("v -x1 x2 ~x3", "OPB"))
        assertEquals("x1 ~x2 x3", PbChecker.solution("v 101", "WCNF"))
        assertEquals("x1 ~x2 x10", PbChecker.solution("v 1 -2\nv 10 0", "WCNF"))
        assertNull(PbChecker.solution("", "OPB"))
        assertNull(PbChecker.solution("s OPTIMUM FOUND\nv x1", "OPB"))
        assertNull(PbChecker.solution("v x1 _b3", "OPB"))
        assertNull(PbChecker.solution("v x1;", "OPB"))
        assertNull(PbChecker.solution("v 1 0 2", "WCNF"))
        assertNull(PbChecker.solution("v 012", "WCNF"))
    }
}
