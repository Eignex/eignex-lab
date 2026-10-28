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
        // Prune consumer-generated caches and verify sealed inputs in the same walk, without following links.
        if (TreeVerification().valid(entry.tree, entry.files, entry.receipt, checkActive,
                exactExecutables = true, removeUndeclared = true)) return entry.tree
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

    internal fun copy(key: String, source: Path, checkActive: () -> Unit, runtimeOnly: Boolean): Path =
        create(key, checkActive) { destination ->
            val sourceReceipt = if (Files.isRegularFile(source.parent.resolve("manifest.json"))) source.parent.resolve("verified.json")
                else source.resolveSibling("${source.fileName}.verified.json")
            TreeVerification().copy(source, sourceReceipt, destination, root.resolve("$key.verified.json"), checkActive, { !runtimeOnly || WorkerTrees.runtimePath(it) }) {
                if (runtimeOnly) WorkerTrees.copyRuntime(source, destination, checkActive) else WorkerTrees.copy(source, destination, checkActive)
            }
        }

    /** Seal after trusted measurement installation/provenance capture, which bind this stable private path. */
    internal fun seal(key: String, tree: Path, checkActive: () -> Unit) {
        check(tree == root.resolve(key))
        val receipt = root.resolve("$key.verified.json")
        val files = TreeVerification().capture(tree, receipt, checkActive)
        ready[key] = Ready(tree, files, receipt)
    }
}
