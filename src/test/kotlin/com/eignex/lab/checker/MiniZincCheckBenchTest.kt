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

import com.eignex.lab.bench.BenchRequest
import com.github.luben.zstd.Zstd
import java.util.Base64
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MiniZincCheckBenchTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `planning packed captures keeps process parameters packed`(paired: Boolean) {
        val inputs = mapOf("record" to "{}", "model" to "source", "fzn" to "frame", "ozn" to "output", "stdout" to "point") +
            if (paired) mapOf("data" to "original data") else emptyMap()
        val text = Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), inputs)
        val frozen = mapOf("capture-zstd-base64" to Base64.getEncoder().encodeToString(Zstd.compress(text.toByteArray())))

        val case = MiniZincCheckBench.plan(BenchRequest(frozen)).single()

        assertEquals(frozen, case.parameters)
    }

    @Test
    fun `unexpected capture fields remain rejected`() {
        val inputs = mapOf("record" to "{}", "model" to "source", "fzn" to "frame", "ozn" to "output", "stdout" to "point",
            "unexpected" to "field")

        assertFailsWith<IllegalArgumentException> { MiniZincCheckBench.plan(BenchRequest(inputs)) }
    }
}
