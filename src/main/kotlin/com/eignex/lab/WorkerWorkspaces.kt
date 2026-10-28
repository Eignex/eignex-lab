package com.eignex.lab

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Mutable trees belong to one serial worker session, never to the node-shared cache. */
class WorkerWorkspaces(private val root: Path) {
    private data class Ready(val tree: Path, val files: List<InputFile>, val receipt: Path)
    private val ready = mutableMapOf<String, Ready>()

    internal fun reuse(key: String, checkActive: () -> Unit): Path? {
        val entry = ready[key] ?: return null
        // Consumers may generate caches (for example compiled MiniZinc files). Remove
        // undeclared outputs before reuse, including links, without following them.
        // Changes to sealed runtime/setup files still require a fresh private copy.
        val expected = entry.files.map { it.path }.toSet()
        try { Files.walkFileTree(entry.tree, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                checkActive()
                if (entry.tree.relativize(file).toString().replace('\\', '/') !in expected) Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        }) } catch (_: java.io.IOException) { ready.remove(key); return null }
        if (TreeVerification().valid(entry.tree, entry.files, entry.receipt, checkActive, exactExecutables = true)) return entry.tree
        ready.remove(key)
        return null
    }

    internal fun create(key: String, checkActive: () -> Unit, copy: (Path) -> Unit): Path {
        require(SHA256.matches(key)); checkActive(); Files.createDirectories(root)
        val tree = root.resolve(key)
        if (Files.exists(tree, NOFOLLOW_LINKS)) deleteTree(tree)
        Files.deleteIfExists(root.resolve("$key.verified.json"))
        copy(tree)
        return tree
    }

    /** Seal after trusted measurement installation/provenance capture, which bind this stable private path. */
    internal fun seal(key: String, tree: Path, checkActive: () -> Unit) {
        check(tree == root.resolve(key))
        var bytes = 0L
        val files = Files.walk(tree).use { paths -> paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.map { file ->
            checkActive(); require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "workspace contains unsupported links" }
            val size = Files.size(file); bytes = Math.addExact(bytes, size); require(bytes <= 32L * 1024 * 1024 * 1024)
            InputFile(tree.relativize(file).toString().replace('\\', '/'), hashFile(file, checkActive), size, Files.isExecutable(file))
        }.toList() }
        val receipt = root.resolve("$key.verified.json")
        require(TreeVerification().valid(tree, files, receipt, checkActive, exactExecutables = true))
        ready[key] = Ready(tree, files, receipt)
    }
}
