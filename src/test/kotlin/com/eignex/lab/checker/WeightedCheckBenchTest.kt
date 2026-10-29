package com.eignex.lab.checker

import com.eignex.lab.bench.BenchRequest
import java.nio.file.Files
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WeightedCheckBenchTest {
    @Test fun `retained capture plan freezes original text and bounded resources`() {
        val parameters = mapOf("format" to "OPB", "model" to "soft: ; [3] 1 x1 >= 1;", "record" to "{}")
        val case = WeightedCheckBench.plan(BenchRequest(parameters)).single()
        assertEquals(parameters, case.parameters)
        assertEquals(1, case.resources.cpus)
        assertEquals(30, case.resources.timeoutSeconds)
        assertFailsWith<IllegalArgumentException> { WeightedCheckBench.plan(BenchRequest(parameters + ("format" to "MPS"))) }
    }

    @Test fun `missing source identity cannot run or accept an adapter`() {
        val work = Files.createTempDirectory("weighted-identity").toFile()
        try {
            val model = work.resolve("source.opb").apply { writeText("1 x1 >= 1;") }
            val case = CheckCase(buildJsonObject { put("kind", "satisfy") }, SourceFiles(model), "v x1")
            for (adapter in listOf(OpbChecker(), WboChecker(), WcnfChecker())) {
                val result = adapter.check(case, work)
                assertEquals(LabCheck.NOT_RUN, result.state)
                assertEquals(WeightedSourceEvaluation.VERSION, result.version)
                assertEquals(emptyList(), result.resourceReceipts)
            }
        } finally { work.deleteRecursively() }
    }

    @Test fun `changed original source snapshot cannot reach checker evaluation`() {
        val work = Files.createTempDirectory("weighted-mismatch").toFile()
        try {
            val model = work.resolve("source.opb").apply { writeText("1 x1 >= 1;") }
            val record = buildJsonObject {
                put("sourceHashes", buildJsonObject { put("model", "0".repeat(64)) })
            }
            val result = OpbChecker().check(CheckCase(record, SourceFiles(model), "v x1"), work)
            assertEquals(LabCheck.NOT_RUN, result.state)
            assertEquals(emptyList(), result.resourceReceipts)
        } finally { work.deleteRecursively() }
    }
}
