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
        assertEquals("100000000000000000000000000000000000000000", MpsChecker.decimal("1e41"))
        assertEquals("0.1234567890123456789012345678901", MpsChecker.decimal("0.1234567890123456789012345678901"))
        assertFailsWith<IllegalArgumentException> { MpsChecker.solution("X=1", "1") }
    }

    @Test
    fun `a long exact value is written whole up to the patched solchecker's limit, and refused past it`() {
        // Job 7165's Bell3a objective: a denominator of 2^44 * 100, so an exact decimal of 53 characters.
        assertEquals("889515.2199999999576328946204739622771739959716796875", MpsChecker.decimal("1564851723957962726619/1759218604441600"))
        val longest = "0." + "1".repeat(MpsChecker.MAX_NUMBER - 2)
        assertEquals(longest, MpsChecker.decimal(longest))
        for (over in listOf(longest + "1", "1/" + java.math.BigInteger.TWO.pow(4100), "1e5000", "1e-5000", "1e999999999")) {
            assertContains(assertFailsWith<IllegalArgumentException> { MpsChecker.decimal(over) }.message!!,
                "no exact decimal of at most 4095 characters")
        }
        val name = "X".repeat(MpsChecker.MAX_LINE)
        assertContains(assertFailsWith<IllegalArgumentException> { MpsChecker.solution("v $name=1", "1") }.message!!, "over 8190 characters")
        assertFailsWith<IllegalArgumentException> { MpsChecker.solution("v X=1 X=2", "1") }
    }

    @Test
    fun `solchecker's verdict line decides, and anything else is unsupported or not-run`() {
        val model = model()
        val witness = "v X=1 Y=0.5"
        fun check(binary: File, timeoutMs: Long = MpsChecker.TIMEOUT_MS) =
            assertNotNull(checker({ binary }, timeoutMs).check(problem, record(witness, "2", model)))

        val accepting = fake("Read MPS: 1\nCheck SOL: Integrality 1 Constraints 1 Objective 1\nMaximum violations: Integrality 0 Constraints 0 Objective 0")
        val valid = check(accepting)
        assertEquals(LabCheck.VALID, valid.state, valid.reason)
        assertEquals(Policies.MPS, valid.policy)
        // The verdict names the patched build: its version, the patch's digest and the binary's own.
        assertEquals("lab-mps-solchecker-1.0.4-longnum-v1", valid.policy)
        assertEquals("1.0.4+eignex-lab-longnum-1 (patch sha256 ${Solchecker.PATCH_SHA256})", valid.checkerVersion)
        assertEquals(sha256(accepting), valid.checkerSha256)
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
    private fun realSolchecker(dir: File = Files.createTempDirectory("checkers").toFile()): Solchecker {
        val flags = System.getenv("SOLCHECKER_FLAGS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
        val solchecker = Solchecker(dir, flags = Solchecker.INCLUDES + flags)
        val built = runCatching { solchecker.binary() }
        if (System.getenv("LAB_REQUIRE_CHECKERS") == "1") built.getOrThrow()
        assumeTrue(built.isSuccess, "solchecker could not be built here: ${built.exceptionOrNull()?.message}")
        return solchecker
    }

    @Test
    fun `the bundled upstream archive is the pinned one, with its license, and one that differs is never built`() {
        val archive = checkNotNull(Solchecker::class.java.getResourceAsStream(Solchecker.ARCHIVE)).use { it.readBytes() }
        assertEquals(89_885, archive.size)
        assertEquals(Solchecker.ARCHIVE_SHA256, sha256(archive))
        val license = checkNotNull(Solchecker::class.java.getResourceAsStream("miplib2017-testscript-LICENSE")).use { it.readBytes() }
        assertContains(license.decodeToString(), "Copyright 2019 Zuse Institute Berlin")

        val dir = Files.createTempDirectory("checkers").toFile()
        val other = Solchecker(dir, archive = Solchecker.PATCH)
        assertContains(assertFailsWith<IllegalStateException> { other.binary() }.message!!, "differs from its pin")
        val missing = Solchecker(Files.createTempDirectory("checkers").toFile(), archive = "absent.zip")
        assertContains(assertFailsWith<IllegalStateException> { missing.binary() }.message!!, "is not packaged")
        assertEquals(emptyList(), dir.walkTopDown().filter { it.isFile }.toList())
    }

    @Test
    fun `the packaged patch is the pinned one and only raises upstream's input limits`() {
        val patch = checkNotNull(Solchecker::class.java.getResourceAsStream(Solchecker.PATCH)).use { it.readBytes() }
        assertEquals(Solchecker.PATCH_SHA256, sha256(patch))
        // The diff follows a header of plain prose.
        val changed = patch.decodeToString().lines().dropWhile { !it.startsWith("--- ") }.filter { (it.startsWith("+") || it.startsWith("-")) && !it.startsWith("+++") && !it.startsWith("---") }
        assertEquals(listOf(
            "-   assert(strlen(num) <  32);",
            "+   assert(strlen(num) <  4096);",
            "-   assert( mpz_sizeinbase(mpq_numref(number), 10 ) + mpz_sizeinbase(mpq_denref(number), 10) + 3 < 1024 );",
            "+   assert( mpz_sizeinbase(mpq_numref(number), 10 ) + mpz_sizeinbase(mpq_denref(number), 10) + 3 < 16384 );",
            "-   static char buffer[1024];",
            "+   static char buffer[16384];",
            "-#define SOL_MAX_LINELEN 1024",
            "+#define SOL_MAX_LINELEN 8192",
        ), changed)
    }

    @Test
    fun `the real MIPLIB solchecker accepts a feasible point and rejects violations and a wrong objective`() {
        val dir = Files.createTempDirectory("checkers").toFile()
        val solchecker = realSolchecker(dir)
        // Only the patched binary is kept, under its own version: no archive, no build tree, no unpatched build reused.
        assertEquals(listOf("solchecker-1.0.4+eignex-lab-longnum-1/solchecker"),
            dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.toList())
        assertEquals(solchecker.binary(), solchecker.binary())
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

    /** Values past upstream's 31 characters reach the patched solchecker whole and get its verdict either way. */
    @Test
    fun `the patched solchecker judges long exact values, up to the limit`() {
        val solchecker = realSolchecker()
        val model = model()
        fun check(witness: String, objective: String) = checker(solchecker::binary).check(problem, record(witness, objective, model))!!

        // Y = 1759218604441599/1759218604441600, a denominator of 2^44 * 100 as in job 7165's Bell3a points.
        val y = check("v X=1 Y=1759218604441599/1759218604441600", "2638827906662399/879609302220800")
        assertEquals(LabCheck.VALID, y.state, y.reason)
        val decimal = check("v X=1 Y=0.9999999999999994315658113919198513031005859375", "2.999999999999998863131622783839702606201171875")
        assertEquals(LabCheck.VALID, decimal.state, decimal.reason)
        val wrong = check("v X=1 Y=0.9999999999999994315658113919198513031005859375", "3.0000999999999999999999999999999999999999")
        assertEquals(LabCheck.INVALID, wrong.state, wrong.reason)
        assertContains(wrong.reason, "objective")
        val longest = check("v X=1 Y=0.${"1".repeat(MpsChecker.MAX_NUMBER - 2)}", "1.${"2".repeat(MpsChecker.MAX_NUMBER - 2)}")
        assertEquals(LabCheck.VALID, longest.state, longest.reason)
        val over = check("v X=1 Y=0.${"1".repeat(MpsChecker.MAX_NUMBER - 1)}", "1.${"2".repeat(MpsChecker.MAX_NUMBER - 1)}")
        assertEquals(LabCheck.UNSUPPORTED, over.state)
        assertContains(over.reason, "no exact decimal of at most 4095 characters")
    }

    /**
     * Job 7165's Bell3a witness (cases 25, 26, 27, 30 and 31, all with this one), checked against miplib3's bell3a.mps
     * where the lab's corpus (`LAB_CORPUS`) holds it. Its objective and several coordinates have exact decimals of up
     * to 53 characters, which left them unsupported under upstream's 31.
     */
    @Test
    fun `job 7165's Bell3a witness gets a real verdict from the patched solchecker`() {
        val corpus = File(System.getenv("LAB_CORPUS") ?: "${System.getProperty("user.home")}/.cache/klause-bench/corpus")
        assumeTrue(File(corpus, "miplib3-mps").isDirectory, "the lab corpus has no miplib3-mps here")
        val witness = checkNotNull(javaClass.getResource("/checker/bell3a-job7165.witness")).readText()
        assertEquals("b7038bec98ed5a09759f861a4322c970cbccfcde048c57c5122c2eb2ab5557df", sha256(witness.toByteArray()))
        val solchecker = realSolchecker()
        val bell3a = Problem("miplib3", "bell3a", collection = "miplib3-mps", format = "MPS")
        val record = buildJsonObject {
            put("problem", "bell3a")
            put("solver", "klause")
            put("kind", "optimize")
            put("feasible", true)
            put("exactObjective", "1564851723957962726619/1759218604441600")
            put("finalWitness", witness)
            put("sourceHashes", buildJsonObject { put("model", "1ada0bcc162defc9d8f17769920d3531d6e9f8c69f466e3eb165939ae845fd43") })
        }
        val check = SourceChecker(listOf(MpsChecker(solchecker::binary)), SourceLocator(corpus), work).check(bell3a, record)!!

        assertEquals(LabCheck.VALID, check.state, check.reason)
        assertEquals(Policies.MPS, check.policy)
    }
}
