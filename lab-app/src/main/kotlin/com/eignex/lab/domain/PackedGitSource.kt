package com.eignex.lab.domain

import com.eignex.lab.util.sha256Hex

/** A shallow Git object store, materialized once by a protocol-7 worker. */
internal object PackedGitSource {
    const val MARKER = ".git/lab-source-pack"
    fun receipt(commit: String) = "approved-source-pack-v1\n$commit\n"

    fun usable(bundle: WorkerBundle): Boolean {
        val marker = bundle.files.singleOrNull { it.path == MARKER } ?: return false
        val bytes = receipt(bundle.commit).toByteArray(Charsets.UTF_8)
        return bundle.build?.gitBundle == MARKER && !marker.executable && marker.bytes == bytes.size.toLong() &&
            marker.sha256 == sha256Hex(bytes)
    }
}
