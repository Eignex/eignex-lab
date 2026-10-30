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

import com.eignex.lab.execution.Checkers
import com.eignex.lab.analysis.DimacsCheckBench

import com.eignex.lab.domain.Config
import com.eignex.lab.domain.Problem
import com.eignex.lab.bench.BenchRequest
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class DimacsCheckerTest {
    @Test fun `production registry covers DIMACS with its own versioned complete point policy`() {
        val root = Files.createTempDirectory("dimacs-registry")
        try {
            assertTrue(Checkers.of(Config(dataDir = root)).covers("DIMACS"))
            assertEquals("lab-dimacs-independent-complete-point-v1", DimacsChecker().policy)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `missing and changed source identity remain not run before child launch`() {
        val root = Files.createTempDirectory("dimacs-identity").toFile()
        try {
            val source = root.resolve("original.cnf").apply { writeText("p cnf 1 1\n1 0") }
            for (record in listOf(buildJsonObject {}, buildJsonObject { putJsonObject("sourceHashes") { put("model", "0".repeat(64)) } })) {
                val result = DimacsChecker().check(CheckCase(record, SourceFiles(source), "v 1 0"), root)
                assertEquals(LabCheck.NOT_RUN, result.state)
                assertEquals(DimacsSourceEvaluation.VERSION, result.version)
                assertTrue(result.resourceReceipts.isEmpty())
            }
            val record = buildJsonObject {
                put("kind", "optimize"); putJsonObject("sourceHashes") { put("model", sha256(source)) }
            }
            assertEquals(LabCheck.UNSUPPORTED, DimacsChecker().check(CheckCase(record, SourceFiles(source), "v 1"), root).state)
        } finally { root.deleteRecursively() }
    }

    @Test fun `UNSAT native terminal flags and absent witnesses cannot create SAT checks`() {
        val root = Files.createTempDirectory("dimacs-claims").toFile()
        try {
            val source = root.resolve("original.cnf").apply { writeText("p cnf 1 1\n1 0") }
            val checker = SourceChecker(listOf(DimacsChecker()), SourceLocator(root), root.resolve("scratch"))
            val problem = Problem("capture", "p", format = "DIMACS", model = source.path)
            val nativeUnsat = buildJsonObject { put("solver", "klause"); put("feasible", false); put("proven", true) }
            assertNull(checker.check(problem, nativeUnsat))
            val missing = JsonObject(nativeUnsat + ("feasible" to JsonPrimitive(true)))
            assertEquals(LabCheck.NOT_RUN, checker.check(problem, missing)?.state)
            val absentSource = JsonObject(missing + ("finalWitness" to JsonPrimitive("v 1")) +
                ("sourceHashes" to buildJsonObject { put("model", "0".repeat(64)) }))
            assertEquals(LabCheck.NOT_RUN, checker.check(problem, absentSource)?.state)
            assertTrue(root.resolve("scratch").listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun `remote capture plans freeze explicit controls and compatible bounded resources`() {
        val capture = buildJsonObject {
            put("model", "p cnf 1 1\n1 0"); put("record", buildJsonObject {})
            put("control", buildJsonObject { put("finalWitness", "v -1") }); put("expected", LabCheck.INVALID)
            put("origin", "retained")
        }
        val parameters = mapOf("captures" to JsonArray(listOf(capture)).toString())
        val case = DimacsCheckBench.plan(BenchRequest(parameters)).single()
        assertEquals(capture.toString(), case.parameters.getValue("capture"))
        assertEquals(1, case.resources.cpus)
        assertEquals(60L, case.resources.timeoutSeconds)
        assertEquals(setOf("platform:linux", "arch:x86_64"), case.resources.requiredTags)
        assertFailsWith<IllegalArgumentException> { DimacsCheckBench.plan(BenchRequest(mapOf("captures" to "[]"))) }
        val bad = JsonObject(capture + ("control" to buildJsonObject { put("sourceHashes", "changed") }))
        assertFailsWith<IllegalArgumentException> { DimacsCheckBench.plan(BenchRequest(mapOf("captures" to JsonArray(listOf(bad)).toString()))) }
    }
}
