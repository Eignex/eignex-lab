package com.eignex.lab.nomad

import com.eignex.lab.execution.*
import com.eignex.lab.store.*

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The coordinator's artifacts, with cloud build archives left in the regional store ([BuildArchives]). An archive
 * missing here is checked there by its content hash alone, and copied here only once a reader here opens it, which
 * is the only time its bytes cross the internet.
 */
internal class RegionalArtifactStore(private val local: ArtifactStore, private val builds: BuildArchives,
                                     private val staging: Path) : ArtifactStore by local {
    private val fetches = ConcurrentHashMap<Pair<Long, String>, Any>()
    private fun held(job: Long, sha: String) = runCatching { local.readFile(job, sha) }.isSuccess

    override fun regional(job: Long, sha: String): Boolean = !held(job, sha) && builds.size(sha) != null
    override fun verify(job: Long, sha: String) {
        if (held(job, sha)) local.verify(job, sha) else checkNotNull(builds.size(sha)) { "artifact missing" }
    }
    override fun size(job: Long, sha: String): Long =
        if (held(job, sha)) local.size(job, sha) else checkNotNull(builds.size(sha)) { "artifact missing" }
    override fun file(job: Long, sha: String): Path { fetch(job, sha); return local.file(job, sha) }
    override fun readFile(job: Long, sha: String): Path { fetch(job, sha); return local.readFile(job, sha) }
    override fun open(job: Long, sha: String, verify: Boolean): InputStream { fetch(job, sha); return local.open(job, sha, verify) }

    /** Copy regional archive [sha] into [job]'s local artifacts; the local store checks its hash before publishing it. */
    private fun fetch(job: Long, sha: String) {
        if (held(job, sha)) return
        synchronized(fetches.computeIfAbsent(job to sha) { Any() }) {
            if (held(job, sha)) return
            // Not held anywhere: leave the local read to fail as it always has.
            val size = builds.size(sha) ?: return
            Files.createDirectories(staging)
            val temporary = Files.createTempFile(staging, ".build-", ".zip")
            try {
                builds.get(sha, temporary)
                val offset = local.offset(job, sha)
                Files.newInputStream(temporary).use { input -> input.skipNBytes(offset); local.put(job, sha, size, offset, input) }
            } finally { Files.deleteIfExists(temporary) }
        }
    }
}
