package com.eignex.lab

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

import com.eignex.lab.checker.*

import com.eignex.lab.bench.*
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CaseChecksTest {
    @ParameterizedTest
    @ValueSource(strings = ["MINIZINC", "MPS", "WBO"])
    fun `formats without a checker retain their native record through ordinary checks and rechecks`(format: String) {
        val root = Files.createTempDirectory("native-checks")
        try {
            val store = Store(root.resolve("lab.db"))
            val spec = ExperimentSpec("native", bench = BenchSpec("bench.json", sourceValidation = true))
            val problem = Problem("fixture", "native", format = format)
            val id = store.create("native", "main", emptyList(), experiment = spec)
            store.plan(id, listOf(PlannedArm(Arm("native", emptyMap()), "revision")), listOf(problem),
                Experiments.cases(1, 1, emptyList()), listOf("retained native claim" to 1L))
            val record = """{"solver":"klause","kind":"optimize","feasible":true,"proven":true,"objective":7}"""
            store.caseRecord(id, 0, record)
            store.commandFinished(id, 0, 0)
            val checks = CaseChecks(store, { Checkers.of(Config(dataDir = root).paths) }) { _, _ -> }

            checks.afterCase(id, 0)
            checks.await(id)
            assertNull(checks.check(id, 0, problem))
            val recheck = checks.recheck(id) { false }
            assertEquals(1, recheck.cases)
            assertEquals(0, recheck.checked)
            assertEquals(mapOf(LabCheck.UNSUPPORTED to 1), recheck.states)
            assertContains(recheck.outcomes.single().reason, format)
            assertEquals(record, store.plans.caseRecordText(id, 0))
            assertTrue(requireNotNull(Outcome.of(store.plans.cases(id).single().record)).proven)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `saved SDK cases report unsupported formats and check supported witnesses without changing producer records`() {
        val root = Files.createTempDirectory("sdk-recheck")
        try {
            val store = Store(root.resolve("lab.db"))
            val model = root.resolve("model.cnf").also { Files.writeString(it, "p cnf 1 1\n1 0\n") }
            val spec = ExperimentSpec("saved", bench = BenchSpec("bench.json", sourceValidation = false))
            val cases = listOf("MPS", "DIMACS").map { format ->
                BenchCase(format, inputs = listOf(BenchInput("model.cnf", role = "model", sha256 = hashFile(model))),
                    attributes = mapOf("recordSchema" to "solver-v1", "format" to format))
            }
            val problems = cases.map { BenchRuns.problem(it).copy(model = model.toString()) }
            val id = store.create("saved", "main", emptyList(), experiment = spec)
            store.plan(id, listOf(PlannedArm(Arm("saved", emptyMap()), "revision")), problems,
                Experiments.cases(2, 1, emptyList()), List(2) { "must never run" to 1L })
            for ((index, case) in cases.withIndex()) {
                val details = buildJsonObject {
                    put("solver", "klause"); put("feasible", true); put("finalWitness", "v 1 0")
                    put("validationPolicy", "original-producer-policy")
                    putJsonObject("sourceHashes") { put("model", hashFile(model)) }
                }
                val result = BenchResult(case.id, details = details)
                store.caseRecord(id, index, BenchRuns.record(BenchProtocol.json.encodeToString(BenchResult.serializer(), result), case, 0))
                store.commandFinished(id, index, 0)
            }
            val original = cases.indices.map { store.plans.caseRecordText(id, it) }
            val historical = LabCheck("retired-mps-policy", LabCheck.VALID, "historical", "retired-checker")
            store.plans.caseCheck(id, 0, historical.json())
            val checks = CaseChecks(store, {
                SourceChecker(listOf(DimacsChecker()), SourceLocator(root.toFile()), root.resolve("scratch").toFile())
            }) { _, _ -> }

            val report = checks.recheck(id) { false }

            assertEquals(2, report.cases)
            assertEquals(1, report.checked)
            assertEquals(mapOf(LabCheck.UNSUPPORTED to 1, LabCheck.VALID to 1), report.states)
            assertEquals(listOf(DimacsChecker().policy), report.policies)
            assertEquals(listOf(0, 1), report.outcomes.map { it.index })
            assertFalse(report.outcomes[0].receiptWritten)
            assertTrue(report.outcomes[1].receiptWritten)
            assertContains(report.outcomes[0].reason, "MPS")
            val records = store.plans.cases(id).map { it.record!!.jsonObject }
            assertEquals(historical, LabCheck.parse(records[0].getValue("labCheck").toString()))
            val receipt = LabCheck.parse(records[1].getValue("labCheck").toString())!!
            assertEquals(mapOf("model" to hashFile(model)), receipt.sourceSha256)
            assertEquals(sha256("v 1 0".toByteArray()), receipt.witnessSha256)
            assertTrue(receipt.elapsedMs >= 0)
            assertEquals(original, cases.indices.map { store.plans.caseRecordText(id, it) })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `saved rechecks report absent records and claims and exclude unfinished or failed cases`() {
        val root = Files.createTempDirectory("recheck-outcomes")
        try {
            val store = Store(root.resolve("lab.db"))
            val id = store.create("saved", "main", emptyList(), experiment = ExperimentSpec("saved", bench = BenchSpec("bench.json")))
            val problems = (0..4).map { Problem("fixture", "$it", format = "DIMACS") }
            store.plan(id, listOf(PlannedArm(Arm("saved", emptyMap()), "revision")), problems,
                Experiments.cases(5, 1, emptyList()), List(5) { "must never run" to 1L })
            store.commandFinished(id, 0, 0) // no record
            store.caseRecord(id, 1, "[]")
            store.commandFinished(id, 1, 0)
            store.caseRecord(id, 2, """{"solver":"klause","feasible":false,"proven":true}""")
            store.commandFinished(id, 2, 0)
            store.commandFinished(id, 3, 1)
            val checks = CaseChecks(store, { Checkers.of(Config(dataDir = root).paths) }) { _, _ -> }

            val report = checks.recheck(id) { false }

            assertEquals(3, report.cases)
            assertEquals(0, report.checked)
            assertEquals(mapOf(LabCheck.NOT_RUN to 2, "skipped" to 1), report.states)
            assertContains(report.outcomes[0].reason, "no retained record")
            assertContains(report.outcomes[1].reason, "not a JSON object")
            assertContains(report.outcomes[2].reason, "no Klause solution claim")
            assertTrue(report.outcomes.none { it.receiptWritten })
            assertTrue(report.policies.isEmpty())
            val cancelled = checks.recheck(id) { true }
            assertEquals(3, cancelled.cases)
            assertTrue(cancelled.outcomes.isEmpty())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `recheck setup failures are explicit and historical reports remain readable`() {
        val root = Files.createTempDirectory("recheck-failure")
        try {
            val store = Store(root.resolve("lab.db"))
            val id = store.create("saved", "main", emptyList(), experiment = ExperimentSpec("saved", bench = BenchSpec("bench.json")))
            store.plan(id, listOf(PlannedArm(Arm("saved", emptyMap()), "revision")), listOf(Problem("fixture", "p", format = "MPS")),
                Experiments.cases(1, 1, emptyList()), listOf("must never run" to 1L))
            store.commandFinished(id, 0, 0)
            val checks = CaseChecks(store, { error("checker setup unavailable") }) { _, _ -> }
            val report = checks.recheck(id) { false }
            assertEquals(1, report.cases)
            assertEquals(0, report.checked)
            assertEquals(mapOf(LabCheck.NOT_RUN to 1), report.states)
            assertContains(report.outcomes.single().reason, "checker setup unavailable")
            assertEquals(report, Json.decodeFromString<RecheckResult>(Json.encodeToString(RecheckResult.serializer(), report)))
            val old = Json.decodeFromString<RecheckResult>("""{"job":12211,"cases":0,"checked":0,"states":{},"policies":[]}""")
            assertTrue(old.outcomes.isEmpty())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `historical source checks retain the original mirror after repository defaults change`() {
        val root = Files.createTempDirectory("checker-history")
        try {
            val config = Config(dataDir = root)
            val historical = Json.decodeFromString<ExperimentSpec>("""{"name":"old","problems":[{"suite":"s"}]}""")
            assertNull(checkerRepository(historical))
            assertEquals(config.mirror, checkerMirror(config.paths, checkerRepository(historical)))
            assertEquals(config.mirror, checkerMirror(config.paths, "Eignex/klause"))
            val bench = ExperimentSpec("new", repository = "Eignex/example", bench = BenchSpec("bench.json"))
            assertEquals(bench.repository, checkerRepository(bench))
            assertEquals(Repositories.mirror(config.paths, bench.repository), checkerMirror(config.paths, bench.repository))
            val current = Repositories.mirror(config.paths, "Eignex/klause")
            Files.createDirectories(current)
            assertEquals(current, checkerMirror(config.paths, "Eignex/klause"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `bench source check requests dispatch the preserved solver record and keep its envelope`() {
        val root = Files.createTempDirectory("bench-checks-")
        try {
            val model = root.resolve("model.txt").also { Files.writeString(it, "source") }
            val store = Store(root.resolve("lab.db"))
            var checked = 0
            val adapter = object : FormatChecker {
                override val format = "FIXTURE"
                override val policy = "fixture-v1"
                override val checker = "fixture"
                override fun check(case: CheckCase, work: File): Verdict {
                    assertEquals("source", case.sources.model.readText())
                    assertEquals("witness", case.witness)
                    checked++
                    return Verdict(LabCheck.VALID, "accepted")
                }
            }
            val checks = CaseChecks(store, { SourceChecker(listOf(adapter), SourceLocator(root.toFile()), root.resolve("scratch").toFile()) }) { _, _ -> }
            for (requested in listOf(false, true)) {
                val spec = ExperimentSpec("bench", bench = BenchSpec("bench.json", sourceValidation = requested))
                val case = BenchCase("p", attributes = mapOf("recordSchema" to "solver-v1"))
                val problem = Problem("bench", "p", format = "FIXTURE", model = model.toString(), benchCase = case)
                val id = store.create(spec.name, "main", emptyList(), experiment = spec)
                val plans = Experiments.cases(1, 1, emptyList())
                store.plan(id, Experiments.arms(spec).map { PlannedArm(it, "a".repeat(40)) }, listOf(problem), plans, plans.map { "true" to 10L })
                val result = BenchResult(case.id, details = buildJsonObject {
                    put("solver", "klause"); put("feasible", true); put("finalWitness", "witness")
                    putJsonObject("sourceHashes") { put("model", hashFile(model)) }
                })
                store.caseRecord(id, 0, BenchRuns.record(BenchProtocol.json.encodeToString(BenchResult.serializer(), result), case, 0))
                checks.afterCase(id, 0)
                checks.await(id)
                val record = store.plans.cases(id).single().record!!.jsonObject
                assertEquals(requested, "labCheck" in record)
                assertEquals(result, BenchRuns.result(record.toString()))
            }
            assertEquals(1, checked)
        } finally { root.toFile().deleteRecursively() }
    }
}
