package com.eignex.lab.execution

import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import com.eignex.lab.bench.BenchDescriptor
import com.eignex.lab.bench.BenchInputRoot
import com.eignex.lab.bench.BenchPlan
import com.eignex.lab.bench.BenchProtocol
import com.eignex.lab.bench.BenchRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/**
 * One repository arm's accepted planner output, kept by the coordinator under [key] ([BenchSelections.key]) so a later
 * preparation with the same selection inputs reuses it instead of running `describe` and `plan` and hashing the corpus
 * again. [plan] is the pinned plan before arm alignment and coordinator selection, so a reuse selects afresh.
 * [sharedInputs] carry every corpus byte the plan names, by checksum; [sourceDigest] binds its source-root inputs,
 * which a reuse recomputes against its own tree.
 */
@Serializable
data class BenchSelectionEntry(val key: String, val arm: String, val descriptor: BenchDescriptor, val plan: BenchPlan,
                               val sourceDigest: String, val sharedInputs: List<SharedInput> = emptyList()) {
    init { require(key.matches(SHA256) && sourceDigest.matches(SHA256) && arm.isNotBlank()) }
}

/**
 * Selection cache keys and the checks that make a cached plan safe to reuse.
 *
 * The key of a repository arm is the SHA-256 of, in order: a format version, the worker release (pinning and input
 * digest formats belong to it), the repository, the bench manifest path, the arm's [BenchRequest] with sorted
 * parameters, the corpus transport identity (the shared input store URL, or `local`) and a source identity:
 * - by default the arm's commit, which fixes the source tree, the built planner and every file it can read;
 * - when the bench manifest declares top-level `planInputs` (portable relative paths), instead the content of the
 *   manifest file and every file under those paths in the prepared tree. The repository then asserts that its plan
 *   is a function of those files, its request and its corpus; this is what lets different commits share a plan.
 * The coordinator's selection filters and frozen cases are not part of the key: they apply after the cached plan.
 */
internal object BenchSelections {
    private const val VERSION = "bench-selection-v1"
    const val MAX_KEYS = 64

    /** The manifest's declared plan inputs, or null when it declares none and the commit identifies the plan. */
    fun planInputs(tree: Path, spec: BenchSpec): List<String>? {
        val manifest = BenchProtocol.json.parseToJsonElement(Files.readString(tree.resolve(safeRelative(spec.manifest)))).jsonObject
        val declared = manifest["planInputs"] ?: return null
        return BenchProtocol.json.decodeFromJsonElement(ListSerializer(String.serializer()), declared)
            .onEach { safeRelative(it) }.also { require(it.isNotEmpty()) { "planInputs must name at least one path" } }
    }

    fun key(release: String, repository: String, spec: BenchSpec, request: BenchRequest, commit: String, tree: Path,
            corpus: String, checkActive: () -> Unit = {}, digests: BenchInputDigests = BenchInputDigests(checkActive)): String {
        require(CommitSha.isValid(commit))
        val source = planInputs(tree, spec)?.let { "declared:" + inventory(tree, listOf(spec.manifest) + it, checkActive, digests) } ?: "commit:$commit"
        val canonical = BenchProtocol.json.encodeToString(BenchRequest.serializer(), request.copy(parameters = request.parameters.toSortedMap()))
        return ExecutionStore.digest(listOf(VERSION, "release=$release", "repository=$repository", "manifest=${spec.manifest}",
            "request=$canonical", "corpus=$corpus", "source=$source").joinToString("\n"))
    }

    /** Path and checksum of every file under [paths]; a missing path is part of the identity, a link is refused. */
    private fun inventory(tree: Path, paths: List<String>, checkActive: () -> Unit, digests: BenchInputDigests): String {
        val canonical = tree.toRealPath()
        val lines = paths.distinct().sorted().flatMap { relative ->
            val root = tree.resolve(safeRelative(relative))
            if (!Files.exists(root, NOFOLLOW_LINKS)) return@flatMap listOf("$relative:absent")
            require(root.toRealPath().startsWith(canonical)) { "plan input escapes source" }
            Files.walk(root).use { it.sorted().toList() }.map { file ->
                checkActive()
                require(!Files.isSymbolicLink(file)) { "plan input contains a link" }
                "$relative/${root.relativize(file)}:" + if (Files.isDirectory(file, NOFOLLOW_LINKS)) "directory" else digests.checksum(tree, file)
            }
        }
        return ExecutionStore.digest(lines.joinToString("\n"))
    }

    fun corpusPaths(plan: BenchPlan): List<String> =
        plan.cases.flatMap { it.inputs }.filter { it.root == BenchInputRoot.CORPUS }.map { it.path }.distinct()

    /** Digest of the plan's source-root input bytes in [source]; cheap, as sources are small and hashed during pinning. */
    fun sourceDigest(plan: BenchPlan, source: Path, digests: BenchInputDigests): String {
        val canonical = source.toRealPath()
        val lines = plan.cases.flatMap { it.inputs }.filter { it.root == BenchInputRoot.SOURCE }.map { it.path }.distinct().sorted().flatMap { relative ->
            val root = source.resolve(safeRelative(relative))
            require(root.toRealPath().startsWith(canonical)) { "bench input escapes its root" }
            val files = if (Files.isDirectory(root)) Files.walk(root).use { it.sorted().toList() } else listOf(root)
            files.map { file ->
                require(!Files.isSymbolicLink(file)) { "bench input contains a link" }
                "$relative/${root.relativize(file)}:" + if (Files.isDirectory(file)) "directory" else digests.checksum(source, file)
            }
        }
        return ExecutionStore.digest(lines.joinToString("\n"))
    }

    /** The [inputs] under [paths], or null when some path has none: then the shared inputs cannot stand in for it. */
    fun covering(inputs: List<SharedInput>, paths: Collection<String>): List<SharedInput>? {
        fun under(input: SharedInput, path: String) = input.file.path == path || input.file.path.startsWith("$path/")
        val selected = inputs.filter { input -> paths.any { under(input, it) } }.distinctBy { it.file.path }
        return selected.takeIf { paths.all { path -> selected.any { under(it, path) } } }
    }
}
