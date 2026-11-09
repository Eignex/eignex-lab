package com.eignex.lab.execution

import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.util.deleteTree
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/** Approved current-source exports, retaining the original commit without its history. */
internal object SourceSnapshots {
    private data class Entry(val name: String, val executable: Boolean, val objectId: String? = null, val file: Path? = null)
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
        val packed = build.gitBundle == PackedGitSource.MARKER
        val staging = Files.createTempDirectory(output.toAbsolutePath().parent, ".source-snapshot-")
        val tree = Files.createDirectory(staging.resolve("tree"))
        val log = staging.resolve("git.log")
        fun command(directory: Path, vararg arguments: String): ProcessBuilder {
            val builder = ProcessBuilder(listOf("git", "-c", "core.hooksPath=/dev/null", "-C", directory.toString()) + arguments)
                .redirectError(log.toFile())
            // No global filters, URL rewrites, includes or inherited Git directories reach the temporary checkout.
            builder.environment().apply {
                keys.removeIf { it.startsWith("GIT_") }
                put("GIT_CONFIG_NOSYSTEM", "1"); put("GIT_CONFIG_GLOBAL", "/dev/null")
                put("GIT_NO_REPLACE_OBJECTS", "1"); put("GIT_TERMINAL_PROMPT", "0")
            }
            return builder
        }
        fun git(directory: Path, vararg arguments: String, stdout: Path = log) {
            val process = command(directory, *arguments).redirectOutput(stdout.toFile()).redirectErrorStream(stdout == log).start()
            val operation = arguments.firstOrNull { it in setOf("init", "update-ref", "fetch", "repack", "read-tree", "ls-tree") }
            try { check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { "approved source snapshot $operation failed" } }
            finally { if (process.isAlive) process.destroyForcibly() }
        }
        try {
            git(tree, "init", "--template=")
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
            // Set Git authority without materializing thousands of working files on the coordinator's disk.
            // Current blobs stream from one cat-file process straight into the verified worker ZIP below.
            Files.writeString(tree.resolve(".git/HEAD"), "$commit\n")
            // Keep .git/refs backed by a declared file, so a file-only cache validation cannot lose Git's
            // required directory. This private ref is not an imported branch or tag.
            val sourceRef = tree.resolve(".git/refs/lab-source/$commit")
            Files.createDirectories(sourceRef.parent); Files.writeString(sourceRef, "$commit\n")
            // Repack only this shallow commit, independently of the mirror's delta/pack layout. This also bounds
            // imported history and makes repeated exports stable without changing the worker's cache identity.
            git(tree, "-c", "pack.compression=1", "repack", "-adF", "--window=0", "--threads=1")
            if (!packed) git(tree, "read-tree", commit) // Zero inode/mtime fields instead of exporting coordinator-local stat data.
            val listing = staging.resolve("tracked")
            git(tree, "ls-tree", "-rz", "--full-tree", commit, stdout = listing)
            require(Files.size(listing) <= 16L * 1024 * 1024) { "source snapshot file inventory exceeds limit" }
            val entries = mutableListOf<Entry>()
            val committedPaths = mutableListOf<String>()
            val directories = mutableListOf(".git/refs/heads/", ".git/refs/tags/")
            for (entry in Files.readString(listing).split('\u0000').filter(String::isNotEmpty)) {
                val separator = entry.indexOf('\t'); require(separator > 0)
                val fields = entry.substring(0, separator).split(' '); require(fields.size == 3)
                val mode = fields[0]
                val relative = safeRelative(entry.substring(separator + 1))
                require(!relative.first().toString().equals(".git", ignoreCase = true)) { "source cannot replace Git metadata" }
                val name = relative.toString().replace('\\', '/')
                committedPaths.add(name)
                when (mode) {
                    "100644", "100755", "120000" -> {
                        require(fields[1] == "blob" && fields[2].matches(Regex("[a-f0-9]{40}")))
                        if (!packed) entries.add(Entry(name, mode == "100755", objectId = fields[2]))
                    }
                    "160000" -> if (!packed) directories.add("$name/") // Uninitialized submodule, as with an ordinary checkout.
                    else -> error("unsupported source entry mode")
                }
            }
            val paths = listOf("HEAD", "config", "index", "shallow").map { tree.resolve(".git/$it") }.filter { Files.exists(it, NOFOLLOW_LINKS) }.toMutableList()
            paths.add(sourceRef)
            Files.list(tree.resolve(".git/objects/pack")).use { entries ->
                paths.addAll(entries.filter { it.fileName.toString().matches(Regex("pack-[a-f0-9]{40}\\.(pack|idx)")) }.toList())
            }
            for ((relative, source) in files) {
                require(Files.isRegularFile(source, NOFOLLOW_LINKS)) { "source inputs must be regular files" }
                val name = safeRelative(relative).toString().replace('\\', '/')
                require(committedPaths.none { it == name || it.startsWith("$name/") || name.startsWith("$it/") }) { "additional input cannot overwrite committed code" }
                entries.add(Entry(name, Files.isExecutable(source), file = source))
            }
            // Supplemented archives are deliberately ineligible for the source-only hint cache.
            if (packed) {
                val marker = tree.resolve(PackedGitSource.MARKER); Files.writeString(marker, PackedGitSource.receipt(commit)); paths.add(marker)
            } else if (files.isEmpty()) {
                val marker = tree.resolve(RECEIPT); Files.writeString(marker, receipt(commit)); paths.add(marker)
            }
            require(paths.all { Files.isRegularFile(it, NOFOLLOW_LINKS) }) { "source snapshot contains unsupported links" }
            entries.addAll(paths.map { Entry(tree.relativize(it).toString().replace('\\', '/'), Files.isExecutable(it), file = it) })
            val process = if (entries.any { it.objectId != null }) command(tree, "cat-file", "--batch").start() else null
            val deadline = process?.let { objects -> Thread.ofVirtual().name("source-snapshot-deadline").start {
                try { if (!objects.waitFor(120, TimeUnit.SECONDS)) objects.destroyForcibly() }
                catch (_: InterruptedException) { /* Export completed or failed before the deadline. */ }
            } }
            val inventory = mutableListOf<InputFile>(); var archiveSha = ""
            var total = 0L
            try {
                (process?.inputStream?.buffered(64 * 1024) ?: InputStream.nullInputStream()).use { objects ->
                    (process?.outputStream?.buffered() ?: java.io.OutputStream.nullOutputStream()).use { requests ->
                    DeterministicZip(Files.newOutputStream(output, CREATE_NEW).buffered(256 * 1024)).use { zip ->
                        val buffer = ByteArray(64 * 1024)
                        fun append(entry: Entry, stream: InputStream, bytes: Long) {
                            require(bytes >= 0 && bytes <= maxBytes - total) { "source archive exceeds disk budget" }
                            total += bytes
                            val sha = zip.file(entry.name) { sink ->
                                var remaining = bytes
                                while (remaining > 0) {
                                    val count = stream.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                                    check(count > 0) { "source snapshot object was truncated" }
                                    sink(buffer, 0, count); remaining -= count
                                }
                            }
                            inventory.add(InputFile(entry.name, sha, bytes, entry.executable))
                        }
                        zip.directories(directories)
                        for (entry in entries.sortedBy { it.name }) {
                            if (entry.objectId != null) {
                                requests.write((entry.objectId + "\n").toByteArray(Charsets.US_ASCII)); requests.flush()
                                val header = StringBuilder()
                                while (true) {
                                    val next = objects.read(); check(next >= 0 && header.length < 128) { "source snapshot object header was truncated" }
                                    if (next == 10) break
                                    header.append(next.toChar())
                                }
                                val fields = header.toString().split(' ')
                                check(fields.size == 3 && fields[0] == entry.objectId && fields[1] == "blob") { "source snapshot object identity changed" }
                                append(entry, objects, fields[2].toLong())
                                check(objects.read() == 10) { "source snapshot object framing changed" }
                            } else {
                                val file = checkNotNull(entry.file)
                                val before = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                                require(before.isRegularFile) { "source inputs must be regular files" }
                                Files.newInputStream(file).use { append(entry, it, before.size()) }
                                val after = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                                require(before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime() && before.fileKey() == after.fileKey()) { "source input changed" }
                            }
                        }
                        archiveSha = zip.finish()
                    }
                } }
                check(total > 0 && (process == null || process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0)) { "source snapshot object export failed" }
            } finally { deadline?.interrupt(); if (process?.isAlive == true) process.destroyForcibly() }
            val result = WorkerBundle(archiveSha, inventory, repository, commit, os, arch, toolchain,
                build = build.copy(gitBundle = if (packed) PackedGitSource.MARKER else null))
            println("source snapshot export: files=${result.files.size} bytes=${result.files.sumOf { it.bytes }} elapsedMs=${(System.nanoTime()-started)/1_000_000}")
            return result
        } catch (failure: Throwable) { Files.deleteIfExists(output); throw failure }
        finally { deleteTree(staging) }
    }
}
