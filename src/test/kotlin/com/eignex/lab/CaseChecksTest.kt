package com.eignex.lab

import com.eignex.lab.checker.*

import com.eignex.lab.bench.*
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class CaseChecksTest {
    @Test fun `historical source checks retain the original mirror after repository defaults change`() {
        val root = Files.createTempDirectory("checker-history")
        try {
            val config = Config(dataDir = root)
            val historical = Json.decodeFromString<ExperimentSpec>("""{"name":"old","problems":[{"suite":"s"}]}""")
            assertNull(checkerRepository(historical))
            assertEquals(config.mirror, checkerMirror(config, checkerRepository(historical)))
            assertEquals(config.mirror, checkerMirror(config, "Eignex/klause"))
            val bench = ExperimentSpec("new", repository = "Eignex/example", bench = BenchSpec("bench.json"))
            assertEquals(bench.repository, checkerRepository(bench))
            assertEquals(Repositories.mirror(config, bench.repository), checkerMirror(config, bench.repository))
            val current = Repositories.mirror(config, "Eignex/klause")
            Files.createDirectories(current)
            assertEquals(current, checkerMirror(config, "Eignex/klause"))
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
