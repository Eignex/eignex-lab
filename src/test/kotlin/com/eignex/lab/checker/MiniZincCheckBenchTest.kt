package com.eignex.lab.checker

import com.eignex.lab.bench.BenchRequest
import com.github.luben.zstd.Zstd
import java.util.Base64
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class MiniZincCheckBenchTest {
    @Test
    fun `planning packed captures keeps process parameters packed`() {
        val inputs = mapOf("record" to "{}", "model" to "source", "fzn" to "frame", "ozn" to "output", "stdout" to "point")
        val text = Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), inputs)
        val frozen = mapOf("capture-zstd-base64" to Base64.getEncoder().encodeToString(Zstd.compress(text.toByteArray())))

        val case = MiniZincCheckBench.plan(BenchRequest(frozen)).single()

        assertEquals(frozen, case.parameters)
    }
}
