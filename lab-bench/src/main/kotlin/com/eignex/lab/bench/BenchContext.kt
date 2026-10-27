package com.eignex.lab.bench

import java.nio.file.Files
import java.nio.file.Path

/** The output directory and optional source identity supplied by the lab's profile runner. */
public class BenchContext(
    outputDirectory: Path,
    public val repository: String? = null,
    public val worktree: Path? = null,
) {
    /** Absolute directory where the caller retains this case's files. */
    public val outputDirectory: Path = Files.createDirectories(outputDirectory.toAbsolutePath().normalize()).toRealPath()

    /** Register an existing file within [outputDirectory]; escaping symlinks are rejected. */
    public fun artifact(path: String, mediaType: String? = null, description: String = ""): BenchArtifact =
        BenchArtifact(path, mediaType, description).also(::validateArtifact)

    internal fun validateArtifact(artifact: BenchArtifact) {
        val path = outputDirectory.resolve(artifact.path).toRealPath()
        require(path.startsWith(outputDirectory) && Files.isRegularFile(path)) {
            "Artifact must be a file inside the output directory: ${artifact.path}"
        }
        require(path != outputDirectory.resolve("result.json")) { "result.json is reserved for the execution result" }
    }
}
