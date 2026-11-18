package com.eignex.lab.checker

import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import java.io.ByteArrayInputStream
import java.util.Base64
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Bounded compressed parameter transport for retained captures. */
object FrozenCaptures {
    fun parameters(frozen: Map<String, String>): Map<String, String> {
        val packed = frozen["capture-zstd-base64"] ?: return frozen
        require(frozen.size == 1 && packed.length <= 120 * 1024)
        val bytes = ZstdInputStreamNoFinalizer(ByteArrayInputStream(Base64.getDecoder().decode(packed))).use {
            it.readNBytes(24 * 1024 * 1024 + 1)
        }
        require(bytes.size <= 24 * 1024 * 1024) { "unpacked capture exceeds 24 MiB" }
        return Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()),
            bytes.decodeToString(throwOnInvalidSequence = true))
    }
}
