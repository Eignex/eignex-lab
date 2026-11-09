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
            corpus: String, checkActive: () -> Unit = {}, digests: BenchInputDigests = BenchInputDigests(checkActive), bulkMetadata: Boolean = true): String =
        key(release, repository, spec, request, corpus, source(spec, commit, tree, checkActive, digests, bulkMetadata))

    /**
     * The source identity of [commit]'s plans in its complete prepared [tree]: the commit, or the content of the declared
     * plan inputs. A preparation that reuses an accepted build gets only that build's runtime workspace, which may lack
     * the plan inputs, so it keys its plans with the source identity the accepted preparation computed on the same build.
     */
    fun source(spec: BenchSpec, commit: String, tree: Path, checkActive: () -> Unit = {},
               digests: BenchInputDigests = BenchInputDigests(checkActive), bulkMetadata: Boolean = true): String {
        require(CommitSha.isValid(commit))
        return planInputs(tree, spec)?.let { "declared:" + inventory(tree, listOf(spec.manifest) + it, checkActive, digests, bulkMetadata) } ?: "commit:$commit"
    }

    fun key(release: String, repository: String, spec: BenchSpec, request: BenchRequest, corpus: String, source: String): String {
        require(validPlanSource(source)) { "invalid plan source identity" }
        val canonical = BenchProtocol.json.encodeToString(BenchRequest.serializer(), request.copy(parameters = request.parameters.toSortedMap()))
        return ExecutionStore.digest(listOf(VERSION, "release=$release", "repository=$repository", "manifest=${spec.manifest}",
            "request=$canonical", "corpus=$corpus", "source=$source").joinToString("\n"))
    }

    /** Path and checksum of every file under [paths]; a missing path is part of the identity, a link is refused. */
    private fun inventory(tree: Path, paths: List<String>, checkActive: () -> Unit, digests: BenchInputDigests, bulkMetadata: Boolean): String {
        val canonical = tree.toRealPath()
        val lines = paths.distinct().sorted().flatMap { relative ->
            val root = tree.resolve(safeRelative(relative))
            if (!Files.exists(root, NOFOLLOW_LINKS)) return@flatMap listOf("$relative:absent")
            require(root.toRealPath().startsWith(canonical)) { "plan input escapes source" }
            digests.inventory(tree, root, bulkMetadata, checkActive).map { "$relative/$it" }
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
            digests.inventory(source, root).map { "$relative/$it" }
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
