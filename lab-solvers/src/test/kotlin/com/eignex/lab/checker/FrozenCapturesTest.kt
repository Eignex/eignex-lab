package com.eignex.lab.checker

import com.github.luben.zstd.Zstd
import java.util.Base64
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrozenCapturesTest {
    @Test fun `packed captures decode their exact parameter map`() {
        val parameters = mapOf("captures" to "retained source and witness")
        val text = Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), parameters)
        val packed = mapOf("capture-zstd-base64" to Base64.getEncoder().encodeToString(Zstd.compress(text.toByteArray())))

        assertEquals(parameters, FrozenCaptures.parameters(packed))
    }

    @Test fun `packed captures cannot hide additional parameters`() {
        assertFailsWith<IllegalArgumentException> {
            FrozenCaptures.parameters(mapOf("capture-zstd-base64" to "", "captures" to "extra"))
        }
    }
}
