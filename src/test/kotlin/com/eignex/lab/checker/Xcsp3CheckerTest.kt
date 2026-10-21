package com.eignex.lab.checker

import com.eignex.lab.Problem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The official XCSP3 checker (xcsp3-tools 2.5, on the test classpath as on the lab's) over real instances. */
class Xcsp3CheckerTest {
    private val circuit = """
        <instance format="XCSP3" type="COP">
          <variables>
            <array id="x" size="[4]"> 0..3 </array>
          </variables>
          <constraints>
            <circuit> x[] </circuit>
          </constraints>
          <objectives>
            <minimize> x[0] </minimize>
          </objectives>
        </instance>
    """.trimIndent()

    private val lexico = """
        <instance format="XCSP3" type="COP">
          <variables>
            <array id="x" size="[2]"> 0..3 </array>
          </variables>
          <constraints>
            <intension> lt(x[0],x[1]) </intension>
          </constraints>
          <objectives combination="lexico">
            <minimize> x[0] </minimize>
            <minimize> x[1] </minimize>
          </objectives>
        </instance>
    """.trimIndent()

    private val corpus = Files.createTempDirectory("corpus").toFile()
    private val work = Files.createTempDirectory("checks").toFile()

    private fun source(name: String, text: String): File =
        File(corpus, "xcsp3-test/COP/$name.xml").apply { parentFile.mkdirs(); writeText(text) }

    private val problem = Problem("xcsp3-test", "Circuit-4", collection = "xcsp3-test", format = "XCSP3")

    /** A Klause record claiming [values] for `x[]` with objective [objective], its witness as the bench keeps it. */
    private fun record(values: String, objective: String?, model: File? = null, witness: Boolean = true): JsonObject = buildJsonObject {
        put("problem", "Circuit-4")
        put("solver", "klause")
        put("kind", if (objective == null) "satisfy" else "optimize")
        put("feasible", true)
        objective?.let { put("objective", it.toDouble()); put("exactObjective", it) }
        put("validationPolicy", "reported-result-v1")
        if (witness) put("finalWitness", "v <instantiation>\n<list> x[] </list>\n<values> $values </values>\n</instantiation>")
        model?.let { put("sourceHashes", buildJsonObject { put("model", sha256(it)) }) }
    }

    private fun checker(adapter: FormatChecker = Xcsp3Checker()) = SourceChecker(listOf(adapter), SourceLocator(corpus, hash = ::sha256), work)

    @Test
    fun `a valid circuit is accepted by the official checker, with what ran recorded`() {
        val model = source("Circuit-4", circuit)
        val record = record("1 2 3 0", "1", model)

        val check = assertNotNull(checker().check(problem, record))

        assertEquals(LabCheck.VALID, check.state, check.reason)
        assertEquals(Policies.XCSP3, check.policy)
        assertEquals("2.5", check.checkerVersion)
        assertEquals(64, check.checkerSha256?.length)
        assertEquals(sha256((record["finalWitness"] as JsonPrimitive).content.toByteArray()), check.witnessSha256)
        assertEquals(mapOf("model" to sha256(model)), check.sourceSha256)
        assertTrue(work.listFiles().orEmpty().isEmpty(), "scratch inputs are deleted")
    }

    @Test
    fun `a violated circuit is rejected, naming the constraint`() {
        val model = source("Circuit-4", circuit)

        val check = assertNotNull(checker().check(problem, record("1 0 3 2", "1", model)))

        assertEquals(LabCheck.INVALID, check.state)
        assertContains(check.reason, "circuit")
    }

    @Test
    fun `a claimed objective the instantiation does not reach is rejected`() {
        val model = source("Circuit-4", circuit)

        val check = assertNotNull(checker().check(problem, record("1 2 3 0", "5", model)))

        assertEquals(LabCheck.INVALID, check.state)
        assertContains(check.reason, "invalid objective")
    }

    @Test
    fun `a lexicographic objective is unsupported, in the checker's words`() {
        val model = source("Circuit-4", lexico)
        val record = record("0 1", "0", model).let { r ->
            JsonObject(r + ("finalWitness" to JsonPrimitive("v <instantiation> <list> x[] </list> <values> 0 1 </values> </instantiation>")))
        }

        val check = assertNotNull(checker().check(problem, record))

        assertEquals(LabCheck.UNSUPPORTED, check.state)
        assertContains(check.reason, "cost for each objective")
    }

    @Test
    fun `a check that cannot run is not-run, never a crash`() {
        val model = source("Circuit-4", circuit)
        val missing = Problem("xcsp3-gone", "Circuit-4", collection = "xcsp3-gone", format = "XCSP3")
        assertEquals(LabCheck.NOT_RUN, checker().check(missing, record("1 2 3 0", "1", model))?.state)
        // A file of the problem's name that is not the source the case solved is never checked against.
        val edited = record("1 2 3 0", "1").let { JsonObject(it + ("sourceHashes" to buildJsonObject { put("model", "0".repeat(64)) })) }
        assertContains(checker().check(problem, edited)!!.reason, "SHA-256")
        assertEquals(LabCheck.NOT_RUN, checker().check(problem, record("1 2 3 0", "1", model, witness = false))?.state)
        val timedOut = checker(Xcsp3Checker(timeoutMs = 1)).check(problem, record("1 2 3 0", "1", model))!!
        assertEquals(LabCheck.NOT_RUN, timedOut.state)
        assertContains(timedOut.reason, "did not finish")
        val broken = checker(Xcsp3Checker(java = "/nonexistent/java")).check(problem, record("1 2 3 0", "1", model))!!
        assertEquals(LabCheck.NOT_RUN, broken.state)
        assertContains(broken.reason, "could not run")
    }

    @Test
    fun `only a Klause solution claim in a checked format gets a verdict`() {
        val model = source("Circuit-4", circuit)
        val claimed = record("1 2 3 0", "1", model)
        assertNull(checker().check(problem, JsonObject(claimed + ("solver" to JsonPrimitive("cp-sat")))))
        assertNull(checker().check(problem, JsonObject(claimed + ("feasible" to JsonPrimitive(false)))))
        assertNull(checker().check(problem.copy(format = "DIMACS"), claimed))
    }

    @Test
    fun `the claimed cost replaces any the witness carries`() {
        assertEquals("<instantiation cost=\"7\"> <list> x </list> </instantiation>",
            Xcsp3Checker.withCost("<instantiation cost='3'> <list> x </list> </instantiation>", "7"))
        assertEquals("<instantiation type=\"solution\"> x", Xcsp3Checker.withCost("<instantiation type=\"solution\" cost=\"3\"> x", null))
    }
}
