package com.eignex.lab

import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Bounded, locked, checksum-verified extraction with atomic publication. No archive-supplied links or modes. */
class VerifiedCache(private val root: Path, private val maxBytes: Long = 32L * 1024 * 1024 * 1024) {
    fun stage(bundle: WorkerBundle, checkActive: () -> Unit = {}, download: () -> InputStream): Path {
        checkActive()
        val declaredBytes = bundle.files.fold(0L) { total, file ->
            require(file.bytes <= maxBytes - total) { "expanded archive exceeds cache limit" }
            total + file.bytes
        }
        require(declaredBytes <= maxBytes)
        Files.createDirectories(root)
        val key = ExecutionStore.digest(kotlinx.serialization.json.Json.encodeToString(WorkerBundle.serializer(), bundle))
        val destination = root.resolve(key)
        synchronized(fileLockFor(root.resolve("$key.lock"))) {
        FileChannel.open(root.resolve("$key.lock"), CREATE, WRITE).use { channel -> channel.lock().use {
            checkActive()
            if (valid(destination, bundle)) return destination
            if (Files.exists(destination, NOFOLLOW_LINKS)) remove(destination)
            val staging = Files.createTempDirectory(root, "$key-")
            val archive = staging.resolve("input.zip")
            val tree = Files.createDirectory(staging.resolve("tree"))
            try {
                download().use { input -> Files.newOutputStream(archive).use { output ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024); var bytes = 0L
                    while (true) {
                        checkActive()
                        val n = input.read(buffer); if (n < 0) break
                        bytes += n; require(bytes <= maxBytes) { "archive exceeds cache limit" }
                        output.write(buffer, 0, n); digest.update(buffer, 0, n)
                    }
                    require(digest.digest().hex() == bundle.sha256) { "archive checksum mismatch" }
                } }
                val expected = bundle.files.associateBy { it.path }; val seen = mutableSetOf<String>()
                ZipInputStream(Files.newInputStream(archive)).use { zip ->
                    while (true) {
                        checkActive()
                        val entry = zip.nextEntry ?: break
                        val relative = safeRelative(entry.name.removeSuffix("/"))
                        val target = tree.resolve(relative)
                        if (entry.isDirectory) { Files.createDirectories(target); continue }
                        val file = requireNotNull(expected[entry.name]) { "undeclared archive entry" }
                        require(seen.add(entry.name)) { "duplicate archive entry" }
                        Files.createDirectories(target.parent)
                        Files.newOutputStream(target, CREATE_NEW).use { output ->
                            var size = 0L; val buffer = ByteArray(64 * 1024)
                            while (true) { checkActive(); val n = zip.read(buffer); if (n < 0) break; size += n; require(size <= file.bytes) { "expanded file exceeds declared size" }; output.write(buffer, 0, n) }
                            require(size == file.bytes)
                        }
                        require(hashFile(target) == file.sha256) { "file checksum mismatch" }
                        if (file.executable) require(target.toFile().setExecutable(true, true))
                    }
                }
                require(seen == expected.keys) { "archive is incomplete" }
                require(valid(tree, bundle))
                checkActive()
                Files.move(tree, destination, ATOMIC_MOVE)
                return destination
            } finally { remove(staging) }
        } }
        }
    }
    private fun valid(directory: Path, bundle: WorkerBundle): Boolean = runCatching {
        require(Files.isDirectory(directory, NOFOLLOW_LINKS))
        val files = Files.walk(directory).use { it.filter { p -> !Files.isDirectory(p, NOFOLLOW_LINKS) }.toList() }
        require(files.size == bundle.files.size)
        bundle.files.all { entry ->
            val file = directory.resolve(safeRelative(entry.path))
            Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) == entry.bytes && hashFile(file) == entry.sha256 && (!entry.executable || Files.isExecutable(file))
        }
    }.getOrDefault(false)
    private fun remove(path: Path) { Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
}

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
internal fun hashFile(path: Path): String = Files.newInputStream(path).use { input ->
    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }; digest.digest().hex()
}

private val localFileLocks = Array(256) { Any() }
internal fun fileLockFor(path: Path): Any = localFileLocks[Math.floorMod(path.toAbsolutePath().normalize().toString().hashCode(), localFileLocks.size)]
