package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream

internal const val PACKED_BYTES = 64 * 1024 * 1024
internal const val PACKED_HEADER_BYTES = 4 * 1024 * 1024

@Serializable data class ResultBlob(val sha: String, val bytes: Long)

/** A length-prefixed JSON manifest followed by raw blobs in manifest order. No base64 or whole-body buffering. */
@Serializable data class WorkerPackedCommit(val owner: AttemptOwner, val result: IterationResult, val blobs: List<ResultBlob>) {
    fun validate() {
        require(result.preparation == null)
        validateResultBlobs(blobs)
        require(blobs.all { it.sha in result.artifacts.values })
    }
    companion object {
        fun read(input: InputStream): WorkerPackedCommit {
            val prefix = input.readNBytes(4); require(prefix.size == 4)
            val size = java.nio.ByteBuffer.wrap(prefix).int; require(size in 1..PACKED_HEADER_BYTES)
            val header = input.readNBytes(size); require(header.size == size)
            return Json.decodeFromString<WorkerPackedCommit>(String(header)).also { it.validate() }
        }
    }
}

internal fun validateResultBlobs(blobs: List<ResultBlob>) {
    require(blobs.size in 1..INLINE_FILES && blobs.map { it.sha }.distinct().size == blobs.size)
    require(blobs.all { SHA256.matches(it.sha) && it.bytes in 0..PACKED_BYTES.toLong() })
    require(blobs.sumOf { it.bytes } <= PACKED_BYTES)
}
