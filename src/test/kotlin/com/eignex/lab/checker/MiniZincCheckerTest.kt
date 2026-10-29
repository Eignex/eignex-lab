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
    fun `FlatZinc fallback output is unavailable for source witness comparison rather than invalid`() {
        val work = Files.createTempDirectory("checks").toFile()
        try {
            val record = buildJsonObject {
                put("stats", buildJsonObject { put("finalWitnessLevel", "flatzinc") })
            }

            val check = MiniZincChecker().check(CheckCase(record, SourceFiles(File(work, "source.mzn")), "x=3;",
                MiniZincFrame("var 1..5: x; solve satisfy;", "", "")), work)

            assertEquals(LabCheck.UNSUPPORTED, check.state)
            assertTrue(check.resourceReceipts.isEmpty())
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun `captured compiler output cannot validate a source without its recorded digest`() {
        val work = Files.createTempDirectory("checks").toFile()
        try {
            val model = File(work, "source.mzn").apply { writeText("var 1..5: x; solve satisfy;") }

            val check = MiniZincChecker().check(CheckCase(buildJsonObject { }, SourceFiles(model), "x=3;",
                MiniZincFrame("var 1..5: x; solve satisfy;", "", "")), work)

            assertEquals(LabCheck.UNSUPPORTED, check.state)
            assertContains(check.reason, "matching original source digest")
            assertTrue(check.resourceReceipts.isEmpty())
        } finally {
            work.deleteRecursively()
        }
    }

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
