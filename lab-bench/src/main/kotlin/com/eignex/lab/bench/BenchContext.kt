package com.eignex.lab.bench

import java.nio.file.FileVisitOption.FOLLOW_LINKS
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** The output directory and optional source identity supplied by the lab's profile runner. */
public class BenchContext @JvmOverloads constructor(
    outputDirectory: Path,
    public val repository: String? = null,
    public val worktree: Path? = null,
    public val corpusDirectory: Path? = null,
) {
    /** Absolute directory where the caller retains this case's files. */
    public val outputDirectory: Path = Files.createDirectories(outputDirectory.toAbsolutePath().normalize()).toRealPath()

    /** Register an existing file within [outputDirectory]; escaping symlinks are rejected. */
    public fun artifact(path: String, mediaType: String? = null, description: String = ""): BenchArtifact =
        BenchArtifact(path, mediaType, description).also(::validateArtifact)

    /** A child destination that remains absent until the repository's harness creates it. */
    public fun reportPath(name: String = "report"): Path {
        portableRelative(name)
        val path = outputDirectory.resolve(name)
        require(!Files.exists(path, NOFOLLOW_LINKS)) { "Report destination already exists: $name" }
        requireContainedParent(path)
        return path
    }

    /** Register a report tree without changing the harness's filenames or formats. */
    public fun artifacts(path: String = "report"): List<BenchArtifact> {
        portableRelative(path)
        val directory = outputDirectory.resolve(path)
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return emptyList()
        require(directory.toRealPath().startsWith(outputDirectory))
        return Files.walk(directory, FOLLOW_LINKS).use { files ->
            files.peek {
                require(it.toRealPath().startsWith(outputDirectory)) { "Report path escapes the output directory: $it" }
            }.filter { !Files.isDirectory(it) }.sorted().map {
                artifact(outputDirectory.relativize(it).toString().replace('\\', '/'))
            }.toList()
        }
    }

    /** Run a repository-owned subprocess with its original argv, closed stdin and retained combined output. */
    public fun process(
        argv: List<String>,
        log: String,
        directory: Path = worktree ?: Path.of("").toAbsolutePath(),
        environment: Map<String, String> = emptyMap(),
    ): Int {
        require(argv.isNotEmpty() && argv.none { '\u0000' in it })
        portableRelative(log)
        val logPath = outputDirectory.resolve(log)
        requireContainedParent(logPath)
        Files.createDirectories(logPath.parent)
        require(logPath.parent.toRealPath().startsWith(outputDirectory))
        require(!Files.isSymbolicLink(logPath))
        val process = ProcessBuilder(argv).directory(directory.toFile()).redirectErrorStream(true)
            .redirectOutput(logPath.toFile()).apply {
                // A scalar child must not inherit a parent's instrumentation or Vector API flags.
                environment().keys.removeAll(setOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"))
                environment().putAll(environment)
            }.start()
        return try {
            process.outputStream.close()
            process.waitFor()
        } catch (failure: InterruptedException) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw failure
        }
    }

    private fun requireContainedParent(path: Path) {
        var parent = path.parent
        while (!Files.exists(parent, NOFOLLOW_LINKS)) parent = parent.parent
        require(parent.toRealPath().startsWith(outputDirectory)) { "Path escapes the output directory: $path" }
    }

    internal fun validateArtifact(artifact: BenchArtifact) {
        val path = outputDirectory.resolve(artifact.path).toRealPath()
        require(path.startsWith(outputDirectory) && Files.isRegularFile(path)) {
            "Artifact must be a file inside the output directory: ${artifact.path}"
        }
        require(path != outputDirectory.resolve("result.json")) { "result.json is reserved for the execution result" }
    }
}
