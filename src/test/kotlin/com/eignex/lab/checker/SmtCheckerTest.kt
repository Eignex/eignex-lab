package com.eignex.lab.checker

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

/** Dolmen checks supplied SMT-LIB models; unsupported models never fall back to a solver. */
class SmtCheckerTest {
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
