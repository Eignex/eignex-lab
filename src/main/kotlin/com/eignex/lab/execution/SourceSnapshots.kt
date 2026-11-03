package com.eignex.lab.execution

import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.TimeUnit

/** An opt-in source ZIP understood by existing workers: exact files and the original commit, without its history. */
internal object SourceSnapshots {
    const val RECEIPT = ".git/lab-source-snapshot"
    fun receipt(commit: String): String = "approved-source-snapshot-v1\n$commit\n"

    fun usable(bundle: WorkerBundle): Boolean {
        val marker = bundle.files.singleOrNull { it.path == RECEIPT } ?: return false
        val content = receipt(bundle.commit)
        return bundle.build != null && bundle.build.gitBundle == null && !marker.executable &&
            marker.bytes == content.toByteArray().size.toLong() && marker.sha256 == ExecutionStore.digest(content)
    }

    fun publish(mirror: Path, repository: String, commit: String, os: String, arch: String, toolchain: String,
                build: WorkerBuild, output: Path, files: Map<String, Path>, maxBytes: Long): WorkerBundle {
        val started = System.nanoTime()
        val staging = Files.createTempDirectory(output.toAbsolutePath().parent, ".source-snapshot-")
        val tree = Files.createDirectory(staging.resolve("tree"))
        val log = staging.resolve("git.log")
        fun git(directory: Path, vararg arguments: String, stdout: Path = log) {
            val builder = ProcessBuilder(listOf("git", "-c", "core.hooksPath=/dev/null", "-C", directory.toString()) + arguments)
                .redirectOutput(stdout.toFile()).redirectError(log.toFile()).redirectErrorStream(stdout == log)
            // No global filters, URL rewrites, includes or inherited Git directories reach the temporary checkout.
            builder.environment().apply {
                keys.removeIf { it.startsWith("GIT_") }
                put("GIT_CONFIG_NOSYSTEM", "1"); put("GIT_CONFIG_GLOBAL", "/dev/null")
                put("GIT_NO_REPLACE_OBJECTS", "1"); put("GIT_TERMINAL_PROMPT", "0")
            }
            val process = builder.start()
            try { check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { "approved source snapshot export failed" } }
            finally { if (process.isAlive) process.destroyForcibly() }
        }
        try {
            git(tree, "init")
            Files.writeString(tree.resolve(".git/config"), """
                [core]
                    repositoryformatversion = 0
                    bare = false
                    filemode = true
                    symlinks = false
                    autocrlf = false
                    ignorecase = false
                    precomposeunicode = true
                    logallrefupdates = false
            """.trimIndent() + "\n")
            synchronized(fileLockFor(mirror.resolve("lab-source.lock"))) {
                FileChannel.open(mirror.resolve("lab-source.lock"), CREATE, WRITE).use { channel -> channel.lock().use {
                    val ref = "refs/lab-source/$commit"
                    git(mirror, "update-ref", ref, commit)
                    try {
                        // file:// negotiates depth; a local clone/fetch optimization must not copy all objects.
                        git(tree, "-c", "fetch.unpackLimit=1", "fetch", "--depth=1", "--no-tags", "--no-write-fetch-head", mirror.toUri().toString(), ref)
                    } finally { git(mirror, "update-ref", "-d", ref, commit) }
                } }
            }
            git(tree, "checkout", "--detach", commit)
            check(Files.readString(tree.resolve(".git/HEAD")).trim() == commit) { "source snapshot changed the approved commit" }
            // Keep .git/refs backed by a declared file, so a file-only cache validation cannot lose Git's
            // required directory. This private ref is not an imported branch or tag.
            val sourceRef = tree.resolve(".git/refs/lab-source/$commit")
            Files.createDirectories(sourceRef.parent); Files.writeString(sourceRef, "$commit\n")
            // Repack only this shallow commit, independently of the mirror's delta/pack layout. This also bounds
            // imported history and makes repeated exports stable without changing the worker's cache identity.
            git(tree, "-c", "pack.compression=1", "repack", "-adF", "--window=0", "--threads=1")
            Files.delete(tree.resolve(".git/index"))
            git(tree, "read-tree", commit) // Zero inode/mtime fields instead of exporting coordinator-local stat data.
            val listing = staging.resolve("tracked")
            git(tree, "ls-files", "--stage", "-z", stdout = listing)
            require(Files.size(listing) <= 16L * 1024 * 1024) { "source snapshot file inventory exceeds limit" }
            val paths = mutableListOf<Path>()
            val directories = mutableListOf(".git/refs/heads/", ".git/refs/tags/")
            for (entry in Files.readString(listing).split('\u0000').filter(String::isNotEmpty)) {
                val separator = entry.indexOf('\t'); require(separator > 0)
                val mode = entry.substringBefore(' ')
                val relative = safeRelative(entry.substring(separator + 1))
                require(!relative.first().toString().equals(".git", ignoreCase = true)) { "source cannot replace Git metadata" }
                when (mode) {
                    "100644", "100755", "120000" -> paths.add(tree.resolve(relative))
                    "160000" -> directories.add(relative.toString().replace('\\', '/') + "/") // Uninitialized submodule, as with an ordinary checkout.
                    else -> error("unsupported source entry mode")
                }
            }
            paths.addAll(listOf("HEAD", "config", "index", "shallow").map { tree.resolve(".git/$it") }.filter { Files.exists(it, NOFOLLOW_LINKS) })
            paths.add(sourceRef)
            Files.list(tree.resolve(".git/objects/pack")).use { entries ->
                paths.addAll(entries.filter { it.fileName.toString().matches(Regex("pack-[a-f0-9]{40}\\.(pack|idx)")) }.toList())
            }
            for ((relative, source) in files) {
                require(Files.isRegularFile(source, NOFOLLOW_LINKS)) { "source inputs must be regular files" }
                val target = tree.resolve(safeRelative(relative))
                require(!Files.exists(target, NOFOLLOW_LINKS)) { "additional input cannot overwrite committed code" }
                Files.createDirectories(target.parent); Files.copy(source, target, COPY_ATTRIBUTES); paths.add(target)
            }
            // Supplemented archives are deliberately ineligible for the source-only hint cache.
            if (files.isEmpty()) {
                val marker = tree.resolve(RECEIPT); Files.writeString(marker, receipt(commit)); paths.add(marker)
            }
            require(paths.all { Files.isRegularFile(it, NOFOLLOW_LINKS) }) { "source snapshot contains unsupported links" }
            require(paths.fold(0L) { total, path -> Math.addExact(total, Files.size(path)) } in 1..maxBytes) { "source archive exceeds disk budget" }
            val identity = WorkerBundle("0".repeat(64), listOf(InputFile(RECEIPT, "0".repeat(64), 0)), repository, commit, os, arch, toolchain)
            val result = archiveInventory(tree, output, identity, paths, directories) {}.copy(build = build.copy(gitBundle = null))
            println("source snapshot export: files=${result.files.size} bytes=${result.files.sumOf { it.bytes }} elapsedMs=${(System.nanoTime()-started)/1_000_000}")
            return result
        } catch (failure: Throwable) { Files.deleteIfExists(output); throw failure }
        finally { Files.walk(staging).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
