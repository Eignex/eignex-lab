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

import com.eignex.lab.bench.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class MpsReplayTest {
    @TempDir lateinit var dir: File
    @Test fun `remote replay freezes source and preserves original and deliberately changed records`() {
        val source = checkNotNull(javaClass.getResource("/checker/mps/pure-real-min.mps")).readText()
        File(dir, "point.mps").writeText(source)
        val record = buildJsonObject {
            put("solver", "klause"); put("kind", "optimize"); put("feasible", true)
            put("finalWitness", "v X=1/3"); put("exactObjective", "1/3")
            put("sourceHashes", buildJsonObject { put("model", sha256(source.toByteArray())) })
        }
        fun request(control: JsonObject, expected: String) = BenchRequest(mapOf("checks" to buildJsonArray {
            add(buildJsonObject {
                put("model", "point.mps"); put("record", record); put("origin", "retained-record")
                put("control", control); put("expected", expected)
            })
        }.toString()))
        for ((control, expected) in listOf(buildJsonObject {} to LabCheck.VALID,
            buildJsonObject { put("exactObjective", "2/3") } to LabCheck.INVALID)) {
            val case = MpsReplay.plan(request(control, expected)).single()
            assertEquals(sha256(source.toByteArray()), case.inputs.single().sha256)
            val context = BenchContext(File(dir, expected).toPath(), worktree = dir.toPath())
            val result = MpsReplay.run(case, context)
            assertEquals(BenchStatus.SUCCESS, result.status, result.message)
            assertEquals(record, Json.parseToJsonElement(context.outputDirectory.resolve("original-record.json").toFile().readText()))
            val verdict = LabCheck.parse(context.outputDirectory.resolve("lab-check.json").toFile().readText())!!
            assertEquals(expected, verdict.state)
            assertEquals(Policies.MPS_EXACT, verdict.policy)
            assertEquals(5, result.artifacts.size)
        }
        assertFailsWith<IllegalArgumentException> { MpsReplay.plan(BenchRequest(mapOf("checks" to "[]"))) }
    }
}
