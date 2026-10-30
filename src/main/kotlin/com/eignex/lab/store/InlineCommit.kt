package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import kotlinx.serialization.Serializable
import java.util.Base64

internal const val INLINE_BYTES = 1024 * 1024
internal const val INLINE_FILES = 128

/** Small outputs and their durable acceptance share one request. Larger captures keep resumable uploads. */
@Serializable
data class WorkerInlineCommit(val owner: AttemptOwner, val result: IterationResult, val blobs: Map<String, String>) {
    fun decoded(): Map<String, ByteArray> {
        require(result.preparation == null && blobs.size <= INLINE_FILES)
        require(blobs.keys.all { SHA256.matches(it) && it in result.artifacts.values })
        var total = 0L
        return blobs.mapValues { (_, value) ->
            require(value.length <= (INLINE_BYTES + 2) / 3 * 4)
            Base64.getDecoder().decode(value).also { total += it.size; require(total <= INLINE_BYTES) }
        }
    }
}
