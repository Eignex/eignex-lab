package com.eignex.lab.execution

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Coordinator-side publication of approved Git objects. It never builds or transfers repository credentials. */
class SourceInputs(private val requireTrusted: (repository: String, commit: String) -> Unit,
                   private val maxBytes: Long = 32L * 1024 * 1024 * 1024) {
    fun publish(mirror: Path, repository: String, commit: String, os: String, arch: String, toolchain: String,
                build: WorkerBuild, output: Path, files: Map<String, Path> = emptyMap()): WorkerBundle {
        Repositories.requireAllowed(repository)
        require(CommitSha.isValid(commit))
        requireTrusted(repository, commit) // Call before exporting any objects, including on retries.
        val bundleName = "source.git.bundle"
        require(bundleName !in files && files.keys.all { safeRelative(it).first().toString() == "lab-inputs" })
        require(!Files.exists(output, NOFOLLOW_LINKS)) { "source archive output already exists" }
        Files.createDirectories(output.toAbsolutePath().parent)
        val staging = Files.createTempDirectory(output.toAbsolutePath().parent, ".source-")
        try {
            val gitBundle = staging.resolve(bundleName)
            // Export only the approved tip and its reachable history, not other branches, hooks, remotes or tokens.
            synchronized(fileLockFor(mirror.resolve("lab-source.lock"))) {
            FileChannel.open(mirror.resolve("lab-source.lock"), CREATE, WRITE).use { channel -> channel.lock().use {
                val ref = "refs/lab-source/$commit"
                fun git(vararg args: String) {
                    val process = ProcessBuilder(listOf("git", "-C", mirror.toString()) + args)
                        .redirectOutput(staging.resolve("git.log").toFile()).redirectErrorStream(true).start()
                    try {
                        check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { "approved source export failed" }
                    } finally { if (process.isAlive) process.destroyForcibly() }
                }
                git("update-ref", ref, commit)
                try { git("bundle", "create", gitBundle.toString(), ref) }
                finally { git("update-ref", "-d", ref, commit) }
            } }
            }
            val declared = (mapOf(bundleName to gitBundle) + files).map { (relative, path) ->
                require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "source inputs must be regular files" }
                InputFile(relative, hashFile(path), Files.size(path), Files.isExecutable(path))
            }.sortedBy { it.path }
            require(declared.sumOf { it.bytes } in 1..maxBytes) { "source archive exceeds disk budget" }
            val inputPaths = mapOf(bundleName to gitBundle) + files
            ZipOutputStream(Files.newOutputStream(output, CREATE_NEW)).use { zip ->
                for (file in declared) {
                    zip.putNextEntry(ZipEntry(file.path).apply { time = 0 })
                    Files.newInputStream(inputPaths.getValue(file.path)).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            return WorkerBundle(hashFile(output), declared, repository, commit, os, arch, toolchain,
                build = build.copy(gitBundle = bundleName))
        } catch (error: Throwable) { Files.deleteIfExists(output); throw error }
        finally { Files.walk(staging).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
