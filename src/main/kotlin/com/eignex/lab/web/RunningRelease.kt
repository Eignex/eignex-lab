package com.eignex.lab.web

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The release this process runs: [id] is its directory under `releases/` (`<short sha>-<build time>`), [commit] the
 * full commit `deploy/release.sh` recorded in it, and [startedAt] when this process started (epoch ms). A process run
 * from a build tree instead of a release reports none.
 */
@Serializable
data class RunningRelease(val id: String, val commit: String? = null, val startedAt: Long? = null) {
    companion object {
        val current: RunningRelease? by lazy {
            runCatching {
                val jar = Paths.get(RunningRelease::class.java.protectionDomain.codeSource.location.toURI())
                of(jar, ProcessHandle.current().info().startInstant().map { it.toEpochMilli() }.orElse(null))
            }.getOrNull()
        }

        /** [jar] is `<data>/releases/<id>/lib/<name>.jar` when the process runs a release. */
        internal fun of(jar: Path, startedAt: Long?): RunningRelease? {
            val release = jar.parent?.parent ?: return null
            if (jar.parent.fileName?.toString() != "lib" || release.parent?.fileName?.toString() != "releases") return null
            val commit = release.resolve("COMMIT").takeIf(Files::isRegularFile)
                ?.let { Files.readString(it).trim() }?.takeIf { it.matches(Regex("[0-9a-f]{40}")) }
            return RunningRelease(release.fileName.toString(), commit, startedAt)
        }
    }
}
