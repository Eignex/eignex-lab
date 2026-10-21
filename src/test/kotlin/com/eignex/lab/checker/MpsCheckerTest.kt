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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MpsCheckerTest {
    /** min x + 2y s.t. x + y <= 4, x >= 1, x integer in [0, 3], y in [0, 2.5]. */
    private val tiny = """
        NAME          TINY
        ROWS
         N  COST
         L  LIM1
         G  LIM2
        COLUMNS
            MARKER                 'MARKER'                 'INTORG'
            X         COST         1.0   LIM1         1.0
            X         LIM2         1.0
            MARKER                 'MARKER'                 'INTEND'
            Y         COST         2.0   LIM1         1.0
        RHS
            RHS       LIM1         4.0   LIM2         1.0
        BOUNDS
         UP BND       X            3.0
         UP BND       Y            2.5
        ENDATA
    """.trimIndent() + "\n"

    private val corpus = Files.createTempDirectory("corpus").toFile()
    private val work = Files.createTempDirectory("checks").toFile()
    private val problem = Problem("miplib-test", "tiny", collection = "miplib-test", format = "MPS")

    private fun model(text: String = tiny): File = File(corpus, "miplib-test/tiny.mps").apply { parentFile.mkdirs(); writeText(text) }

    private fun record(witness: String, objective: String, model: File, hash: String = sha256(model)): JsonObject = buildJsonObject {
        put("problem", "tiny")
        put("solver", "klause")
        put("kind", "optimize")
        put("feasible", true)
        put("objective", objective.toDoubleOrNull())
        put("exactObjective", objective)
        put("finalWitness", witness)
        put("sourceHashes", buildJsonObject { put("model", hash) })
    }

    private fun checker(binary: () -> File, timeoutMs: Long = MpsChecker.TIMEOUT_MS) =
        SourceChecker(listOf(MpsChecker(binary, timeoutMs)), SourceLocator(corpus, hash = ::sha256), work)

    /** A stand-in solchecker that prints [output] and exits [exit], after echoing its model's first line. */
    private fun fake(output: String, exit: Int = 0, sleep: Int = 0): File = Files.createTempFile("solchecker", "").toFile().apply {
        writeText("#!/bin/sh\nhead -n 1 \"$1\" > \"$(dirname \"$2\")/model-head\"\nsleep $sleep\nprintf '%s\\n' '${output.replace("'", "")}'\nexit $exit\n")
        setExecutable(true)
    }

    @Test
    fun `the SOL file is the witness and claimed objective as exact decimals`() {
        assertEquals("=obj= 2\nX 1\nY 0.5\n", MpsChecker.solution("v X=1 Y=1/2", "2"))
        assertEquals("0.25", MpsChecker.decimal("1/4"))
        assertEquals("1000", MpsChecker.decimal("1e3"))
        assertEquals("2.5", MpsChecker.decimal("2.50"))
        assertEquals("-3", MpsChecker.decimal("-3"))
        assertContains(assertFailsWith<IllegalArgumentException> { MpsChecker.decimal("1/3") }.message!!, "no exact decimal")
        assertContains(assertFailsWith<IllegalArgumentException> { MpsChecker.decimal("0.1234567890123456789012345678901") }.message!!,
            "under 32 characters")
        assertFailsWith<IllegalArgumentException> { MpsChecker.solution("v X=1 X=2", "1") }
        assertFailsWith<IllegalArgumentException> { MpsChecker.solution("X=1", "1") }
    }

    @Test
    fun `solchecker's verdict line decides, and anything else is unsupported or not-run`() {
        val model = model()
        val witness = "v X=1 Y=0.5"
        fun check(binary: File, timeoutMs: Long = MpsChecker.TIMEOUT_MS) =
            assertNotNull(checker({ binary }, timeoutMs).check(problem, record(witness, "2", model)))

        val valid = check(fake("Read MPS: 1\nCheck SOL: Integrality 1 Constraints 1 Objective 1\nMaximum violations: Integrality 0 Constraints 0 Objective 0"))
        assertEquals(LabCheck.VALID, valid.state, valid.reason)
        assertEquals(Policies.MPS, valid.policy)
        assertEquals(Solchecker.VERSION, valid.checkerVersion)
        val invalid = check(fake("Check SOL: Integrality 1 Constraints 0 Objective 0"))
        assertEquals(LabCheck.INVALID, invalid.state)
        assertContains(invalid.reason, "bounds or constraints and objective 2")
        val unreadable = check(fake("Read MPS: 0", exit = 255))
        assertEquals(LabCheck.UNSUPPORTED, unreadable.state)
        assertContains(unreadable.reason, "Read MPS: 0")
        val slow = check(fake("never", sleep = 5), timeoutMs = 300)
        assertEquals(LabCheck.NOT_RUN, slow.state)
        val missing = checker({ error("the MIPLIB solchecker could not be built: no c++") }).check(problem, record(witness, "2", model))!!
        assertEquals(LabCheck.NOT_RUN, missing.state)
        assertContains(missing.reason, "no c++")
        val fraction = checker({ fake("unused") }).check(problem, record("v X=1 Y=1/3", "5/3", model))!!
        assertEquals(LabCheck.UNSUPPORTED, fraction.state)
        assertContains(fraction.reason, "no exact decimal")
    }

    @Test
    fun `a compressed model reaches solchecker decompressed through a pipe, never as a copy on disk`() {
        assumeTrue(runCatching { ProcessBuilder("zstd", "--version").start().waitFor() == 0 }.getOrDefault(false), "zstd is not installed")
        val plain = model()
        val plainHash = sha256(plain)
        val compressed = File(plain.path + ".zst")
        assertEquals(0, ProcessBuilder("zstd", "-q", "--rm", plain.path, "-o", compressed.path).start().waitFor())
        var head = ""
        val binary = fake("Check SOL: Integrality 1 Constraints 1 Objective 1")
        val spy = MpsChecker({ binary }).let { inner ->
            object : FormatChecker by inner {
                override fun check(case: CheckCase, work: File): Verdict =
                    inner.check(case, work).also { head = File(work, "model-head").readText().trim() }
            }
        }
        val check = SourceChecker(listOf(spy), SourceLocator(corpus), work).check(problem, record("v X=1 Y=0.5", "2", compressed, plainHash))!!

        assertEquals(LabCheck.VALID, check.state, check.reason)
        assertEquals("NAME          TINY", head)
    }

    /**
     * The real solchecker, built from the pinned archive: CI installs GMP so this runs there (`LAB_REQUIRE_CHECKERS=1`);
     * elsewhere it runs where the build succeeds. `SOLCHECKER_FLAGS` adds compiler flags, for GMP in another prefix.
     */
    @Test
    fun `the real MIPLIB solchecker accepts a feasible point and rejects violations and a wrong objective`() {
        val dir = Files.createTempDirectory("checkers").toFile()
        val flags = System.getenv("SOLCHECKER_FLAGS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
        val solchecker = Solchecker(dir, flags = Solchecker.INCLUDES + flags)
        val built = runCatching { solchecker.binary() }
        if (System.getenv("LAB_REQUIRE_CHECKERS") == "1") built.getOrThrow()
        assumeTrue(built.isSuccess, "solchecker could not be built here: ${built.exceptionOrNull()?.message}")
        // Only the binary is kept: no archive, no build tree.
        assertEquals(listOf("solchecker-1.0.4/solchecker"), dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.toList())
        assertEquals(built.getOrThrow(), solchecker.binary())
        val model = model()
        fun check(witness: String, objective: String) = checker(solchecker::binary).check(problem, record(witness, objective, model))!!

        assertEquals(LabCheck.VALID, check("v X=1 Y=0.5", "2").state)
        assertEquals(LabCheck.VALID, check("v X=1 Y=1/2", "2").state)
        assertContains(check("v X=1.5 Y=0", "1.5").reason, "integrality")
        assertContains(check("v X=3 Y=2", "7").reason, "bounds or constraints")
        val objective = check("v X=1 Y=0", "5")
        assertEquals(LabCheck.INVALID, objective.state)
        assertContains(objective.reason, "objective 5")
        model("this is no MPS file\n")
        val unreadable = checker(solchecker::binary).check(problem, record("v X=1 Y=0", "1", File(corpus, "miplib-test/tiny.mps")))!!
        assertTrue(unreadable.state == LabCheck.UNSUPPORTED || unreadable.state == LabCheck.INVALID, unreadable.toString())
    }
}
