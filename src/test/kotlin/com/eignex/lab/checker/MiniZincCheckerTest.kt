package com.eignex.lab.checker

import com.eignex.lab.Problem
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiniZincCheckerTest {
    @Test
    fun `complete and partial MiniZinc assignments stay unsupported without solver completion`() {
        val corpus = Files.createTempDirectory("corpus").toFile()
        val work = Files.createTempDirectory("checks").toFile()
        val model = File(corpus, "mzn-test/partial/model.mzn").apply {
            parentFile.mkdirs()
            writeText("var 1..5: x; var 1..5: y; constraint x + y = 7; solve satisfy;")
        }
        val problem = Problem("mzn-test", "partial/model", collection = "mzn-test", format = "MINIZINC")
        val checker = SourceChecker(listOf(MiniZincChecker()), SourceLocator(corpus, hash = ::sha256), work)
        for (witness in listOf("x = 3; y = 4;", "x = 3;", "x = 1; y = 1;")) {
            val record = buildJsonObject {
                put("solver", "klause")
                put("kind", "satisfy")
                put("feasible", true)
                put("finalWitness", witness)
                put("sourceHashes", buildJsonObject { put("model", sha256(model)) })
            }
            val check = checker.check(problem, record)!!
            assertEquals(LabCheck.UNSUPPORTED, check.state)
            assertEquals(Policies.MINIZINC, check.policy)
            assertEquals("none", check.checker)
            assertContains(check.reason, "solver-based witness completion is disabled")
            assertNull(check.checkerSha256)
            assertTrue(check.resourceReceipts.isEmpty())
            assertTrue(check.inputs.isEmpty())
        }
    }
}
