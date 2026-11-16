package com.eignex.lab.execution

import com.eignex.lab.domain.*

import java.net.URI
import java.nio.file.Path

/**
 * Large solve outputs of cloud workers (JFR recordings, compilation logs, big stdout/stderr captures), kept by content
 * hash in the regional store under `result-blobs/<sha256>` instead of passing through the coordinator's bounded result
 * journal. A worker uploads one with a PUT its session signs, bound to the content hash, and commits only the hash and
 * size ([IterationResult.regionalBlobs]). The coordinator checks each one's presence and size before it accepts the
 * result and copies its bytes only when it first reads them, checking their hash then. The store's lifecycle may expire
 * them; an expired output stays listed and reads as unavailable.
 *
 * Native workers write to the coordinator's disk and never use this route.
 */
internal object ResultBlobs {
    const val PREFIX = "result-blobs"
    /** Outputs at least this large go to the regional store unless `LAB_RESULT_BLOB_MIN_BYTES` says otherwise. */
    const val DEFAULT_MIN_BYTES = 8L * 1024 * 1024
    /** The most one signed PUT stores; a larger output keeps the coordinator's resumable upload. */
    const val MAX_BYTES = 5L * 1024 * 1024 * 1024

    fun key(sha: String): String { require(sha.matches(SHA256)); return "$PREFIX/$sha" }

    /** A cloud worker's uploads of outputs of at least [minBytes]; every PUT is signed through [sign], its session. */
    interface Uploader {
        val minBytes: Long
        fun put(source: Path, sha: String, bytes: Long, sign: (InputSignature) -> SignedInput)
    }

    /**
     * The uploader of a cloud worker: one with a regional store and no loopback signer. Null on the local machine, and
     * when `LAB_RESULT_BLOB_MIN_BYTES` is `off`; any other value that is not a positive byte count keeps the default.
     */
    fun worker(environment: Map<String, String> = System.getenv()): Uploader? {
        if (environment["LAB_SHARED_INPUT_SIGNER"] != null) return null
        val base = environment["LAB_SHARED_INPUT_URL"] ?: return null
        val minBytes = minBytes(environment["LAB_RESULT_BLOB_MIN_BYTES"]) ?: return null
        return RegionalUploader(URI(base), minBytes)
    }

    internal fun minBytes(value: String?): Long? =
        if (value?.trim().equals("off", ignoreCase = true)) null else value?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_MIN_BYTES

    /** The coordinator's view: presence, size and reads, signed by its loopback signer. Null without a regional store. */
    fun coordinator(environment: Map<String, String> = System.getenv()): BuildArchives? {
        val base = environment["LAB_SHARED_INPUT_URL"] ?: return null
        val signer = environment["LAB_SHARED_INPUT_SIGNER"] ?: return null
        return RegionalBuildArchives(HttpInputTransport(URI(base), URI(signer)), PREFIX)
    }

    private class RegionalUploader(private val base: URI, override val minBytes: Long) : Uploader {
        override fun put(source: Path, sha: String, bytes: Long, sign: (InputSignature) -> SignedInput) {
            require(bytes in 0..MAX_BYTES)
            // The store accepts only bytes whose checksum is [sha]; an existing object (HTTP 412) holds the same bytes.
            HttpInputTransport(base, publishSignature = { sign(it.copy(bytes = bytes)) }).put(key(sha), source, sha)
        }
    }
}
