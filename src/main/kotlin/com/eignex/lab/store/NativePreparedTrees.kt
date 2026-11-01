package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/** Native preparation hands its sealed workspace to job retention by rename. No ZIP, loopback transfer or second
 * input-cache copy. Consumers still receive private filesystem clones; a missing cache never loses the snapshot. */
internal class NativePreparedTrees(private val jobs: Path) {
    private fun directory(bundle: WorkerBundle): Path = jobs.resolve(requireNotNull(bundle.tree).job.toString())
        .resolve("${JobFileNames.PREPARED_TREES}/${bundle.sha256}")

    fun publish(job: Long, source: Path, receipt: Path, identity: WorkerBundle, check: () -> Unit,
                force: (Path) -> Unit = { path -> FileChannel.open(path, WRITE).use { it.force(true) } }): Pair<Path, WorkerBundle> {
        val directories = mutableListOf<String>()
        val verification = TreeVerification()
        val files = verification.capture(source, receipt, check, directories)
        val bundle = identity.copy(sha256 = preparedTreeDigest(files, directories), files = files, build = null,
            tree = PreparedTree(job, directories.distinct().sorted()))
        val target = directory(bundle)
        Files.createDirectories(target.parent)
        locked(target.resolveSibling("${bundle.sha256}.lock"), check) {
            if (Files.isDirectory(target, NOFOLLOW_LINKS)) {
                require(valid(bundle, check)) { "existing prepared snapshot is corrupt" }
            } else {
                require(Files.getFileStore(source) == Files.getFileStore(target.parent)) { "native handoff requires one filesystem" }
                // Flush newly built files without reading them again. Publish the receipt and metadata last.
                for (file in files) { check(); force(source.resolve(file.path)) }
                val staging = Files.createTempDirectory(target.parent, ".handoff-")
                try {
                    check(); Files.move(source, staging.resolve("tree"), ATOMIC_MOVE)
                    Files.move(receipt, staging.resolve("verified.json"), REPLACE_EXISTING)
                    val manifest = staging.resolve("manifest.json")
                    Files.writeString(manifest, preparedTreeManifest(files, directories))
                    force(manifest)
                    Files.walk(staging.resolve("tree")).use { paths -> paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.forEach { dir ->
                        check(); FileChannel.open(dir, READ).use { it.force(true) }
                    } }
                    FileChannel.open(staging, READ).use { it.force(true) }
                    Files.move(staging, target, ATOMIC_MOVE)
                    FileChannel.open(target.parent, READ).use { it.force(true) }
                } finally { if (Files.exists(staging)) deleteTree(staging) }
            }
        }
        return target.resolve("manifest.json") to bundle
    }

    fun valid(bundle: WorkerBundle, check: () -> Unit): Boolean {
        val root = directory(bundle)
        return Files.isRegularFile(root.resolve("manifest.json"), NOFOLLOW_LINKS) &&
            Files.readString(root.resolve("manifest.json")) == preparedTreeManifest(bundle.files, requireNotNull(bundle.tree).directories) &&
            TreeVerification().valid(root.resolve("tree"), bundle.files, root.resolve("verified.json"), check,
                exactExecutables = true, expectedDirectories = bundle.tree.directories)
    }

    fun <T> use(bundle: WorkerBundle, check: () -> Unit, action: (Path, Path) -> T): T {
        check(); require(valid(bundle, check)) { "prepared snapshot is missing or corrupt" }
        val root = directory(bundle)
        return action(root.resolve("tree"), root.resolve("verified.json"))
    }

    fun materialize(bundle: WorkerBundle, files: List<InputFile>, tree: Path, receipt: Path, check: () -> Unit): Boolean {
        if (bundle.tree == null) return false
        val root = directory(bundle)
        check()
        require(Files.isRegularFile(root.resolve("manifest.json"), NOFOLLOW_LINKS) &&
            Files.readString(root.resolve("manifest.json")) == preparedTreeManifest(bundle.files, bundle.tree.directories)) { "prepared snapshot manifest changed" }
        run {
            val source = root.resolve("tree"); val proof = root.resolve("verified.json")
            val wanted = files.map { it.path }.toSet()
            TreeVerification().copy(source, proof, tree, receipt, check, include = { it.toString().replace('\\', '/') in wanted }) {
                // Selected source checking need not copy or read the installed runtime.
                if (files.size == bundle.files.size) copyPrivateTree(source, tree, check)
                else {
                    Files.createDirectories(tree)
                    for (file in files) {
                        check(); val target = tree.resolve(file.path); Files.createDirectories(target.parent)
                        Files.copy(source.resolve(file.path), target, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
            }
        }
        require(TreeVerification().valid(tree, files, receipt, check, exactExecutables = true)) { "copied snapshot differs from accepted files" }
        return true
    }

    /** Replays own their snapshot, so trimming their source job cannot invalidate them. */
    fun retain(bundle: WorkerBundle, job: Long, check: () -> Unit = {}): WorkerBundle {
        if (bundle.tree == null || bundle.tree.job == job) return bundle
        val targetBundle = bundle.copy(tree = bundle.tree.copy(job = job))
        val target = directory(targetBundle)
        Files.createDirectories(target.parent)
        if (Files.isDirectory(target, NOFOLLOW_LINKS)) require(valid(targetBundle, check))
        else {
            val staging = Files.createTempDirectory(target.parent, ".handoff-")
            try {
                materialize(bundle, bundle.files, staging.resolve("tree"), staging.resolve("verified.json"), check)
                publish(job, staging.resolve("tree"), staging.resolve("verified.json"), targetBundle, check)
            } finally { deleteTree(staging) }
        }
        return targetBundle
    }

    /** Portable export is generated only when a reader on another machine actually needs it. */
    fun archive(bundle: WorkerBundle, check: () -> Unit = {}): Path {
        val root = directory(bundle)
        return locked(root.resolveSibling("${bundle.sha256}.export.lock"), check) {
            val data = Files.createDirectories(root.resolve("export-data"))
            val output = data.resolve("archive.zip")
            val manifest = root.resolve("export.json")
            val cached = runCatching { kotlinx.serialization.json.Json.decodeFromString<WorkerBundle>(Files.readString(manifest)) }.getOrNull()
            if (cached != null && cached.files.toSet() == bundle.files.toSet() && Files.isRegularFile(output, NOFOLLOW_LINKS) &&
                TreeVerification().valid(data, listOf(InputFile("archive.zip", cached.sha256, Files.size(output))),
                    root.resolve("export-verified.json"), check)) return@locked output
            use(bundle, check) { tree, _ ->
                val temporary = Files.createTempFile(root, ".export-", ".zip")
                try {
                    val exported = archiveInventory(tree, temporary, bundle.copy(tree = null),
                        bundle.files.map { tree.resolve(it.path) }, requireNotNull(bundle.tree).directories.map { "$it/" }, check)
                    require(exported.files.toSet() == bundle.files.toSet()) { "prepared export changed" }
                    FileChannel.open(temporary, WRITE).use { it.force(true) }
                    Files.move(temporary, output, ATOMIC_MOVE, REPLACE_EXISTING)
                    Files.writeString(manifest, kotlinx.serialization.json.Json.encodeToString(WorkerBundle.serializer(), exported))
                    val verifier = TreeVerification()
                    verifier.record(data, listOf(verifier.written(output, InputFile("archive.zip", exported.sha256, Files.size(output)))), root.resolve("export-verified.json"), check)
                } finally { Files.deleteIfExists(temporary) }
            }
            output
        }
    }

    private fun <T> locked(path: Path, check: () -> Unit, action: () -> T): T {
        while (true) { check(); val pin = ProcessFilePin.tryLock(path); if (pin != null) return pin.use { action() }; Thread.sleep(10) }
    }

    companion object {
        fun environment(): NativePreparedTrees? {
            if (!System.getProperty("os.name").lowercase().contains("mac")) return null
            return (System.getenv("LAB_NATIVE_PREPARED_ROOT") ?: System.getenv("LAB_JOBS"))?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it).also { path -> require(path.isAbsolute) } }?.let(::NativePreparedTrees)
        }
    }
}
