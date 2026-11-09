package com.eignex.lab.execution

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import com.eignex.lab.bench.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

/** Translation at the coordinator/worker boundary; no repository-specific executable names. */
internal object BenchRuns {
    fun entry(tree: Path, spec: BenchSpec): BenchEntryPoint {
        val file = tree.resolve(safeRelative(spec.manifest))
        require(file.toRealPath().startsWith(tree.toRealPath())) { "bench manifest escapes source" }
        val entry = BenchProtocol.json.decodeFromString<BenchEntryPoint>(Files.readString(file))
        ProfileRules.validate("Eignex/bench", ProfileMain(entry.main, buildTasks = entry.buildTasks,
            classpath = entry.classpath, jvmArgs = entry.jvmArgs))
        runtime(tree, spec).validate()
        preparation(tree, spec)
        return entry
    }

    /** Additive manifest metadata, enforced by the lab without changing SDK 0.1.0. */
    @Serializable
    data class Runtime(val java: Int? = null, val requiredTags: Set<String> = emptySet(), val tools: List<String> = emptyList(),
                       val environment: Map<String, String> = emptyMap()) {
        fun validate() {
            require(java == null || java in 8..100)
            NodeTags.validate(NodeSelector(require = requiredTags))
            require(tools.all { it.matches(Regex("[A-Za-z0-9_.+-]+")) })
            require(environment.keys.all { it.matches(Regex("[A-Z][A-Z0-9_]*")) && !it.startsWith("LAB_") && it !in setOf("PATH", "HOME", "JAVA_HOME", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS") })
            require(environment.values.none { value -> value.any(Char::isISOControl) })
        }
    }

    fun runtime(tree: Path, spec: BenchSpec): Runtime {
        val manifest = BenchProtocol.json.parseToJsonElement(Files.readString(tree.resolve(safeRelative(spec.manifest)))).jsonObject
        val runtime = manifest["runtime"]?.let { BenchProtocol.json.decodeFromJsonElement(Runtime.serializer(), it) } ?: Runtime()
        return if (spec.adapterResources) runtime.copy(requiredTags = runtime.requiredTags + "platform:linux") else runtime
    }

    /** Lab-only phase contract; absent manifests retain the original shared host/runtime requirements. */
    fun preparation(tree: Path, spec: BenchSpec): WorkerPreparation? =
        preparation(Files.readString(tree.resolve(safeRelative(spec.manifest))))

    internal fun preparation(text: String): WorkerPreparation? {
        val manifest = BenchProtocol.json.parseToJsonElement(text).jsonObject
        return manifest["preparation"]?.let {
            try { BenchProtocol.json.decodeFromJsonElement(WorkerPreparation.serializer(), it) }
            catch (failure: kotlinx.serialization.SerializationException) { throw IllegalArgumentException("invalid committed preparation contract", failure) }
        }
    }

    /** Full history is retained unless the committed manifest explicitly needs only its current source snapshot. */
    internal fun sourceHistory(text: String): Boolean {
        val value = BenchProtocol.json.parseToJsonElement(text).jsonObject["sourceHistory"] ?: return true
        require(value is JsonPrimitive && value.isString && value.content in setOf("full", "snapshot")) { "sourceHistory must be full or snapshot" }
        return value.content == "full"
    }

    /** Opt-in private workspace contents; omitted manifests retain the entire built tree. */
    fun workspaceInputs(tree: Path, spec: BenchSpec): List<String>? {
        val manifest = BenchProtocol.json.parseToJsonElement(Files.readString(tree.resolve(safeRelative(spec.manifest)))).jsonObject
        val value = manifest["workspaceInputs"] ?: return null
        val paths = BenchProtocol.json.decodeFromJsonElement(ListSerializer(String.serializer()), value)
        require(paths.isNotEmpty()) { "workspaceInputs must name at least one path" }
        return (paths + spec.manifest).onEach { safeRelative(it) }.distinct()
    }

    /** The lab passes opaque parameter overrides to the repository planner. */
    fun request(spec: BenchSpec, arm: Arm): BenchRequest = spec.request.copy(parameters = spec.request.parameters +
        arm.values.filterKeys { it.startsWith("param.") }.mapKeys { it.key.removePrefix("param.") })

    /** Suites may declare a stable comparison identity when runtime settings change their case id. */
    fun align(canonical: List<BenchCase>, planned: BenchPlan): BenchPlan {
        val byKey = planned.cases.groupBy(BenchCases::key)
        require(byKey.values.all { it.size == 1 }) { "ambiguous bench comparison identities" }
        return BenchPlan(canonical.map { frozen ->
            val candidate = requireNotNull(byKey[BenchCases.key(frozen)]?.singleOrNull()) { "bench revision omitted workload ${BenchCases.key(frozen)}" }
            require(candidate.inputs == frozen.inputs && candidate.attributes["labInputDigest"] == frozen.attributes["labInputDigest"]) {
                "bench arms resolved different input bytes for ${BenchCases.key(frozen)}"
            }
            candidate
        })
    }

    /**
     * Pins [case]'s input bytes into `labInputDigest`. With [trustDeclared], a file input that declares its sha256 is
     * inventoried from that declaration without reading or even requiring the file: a planner can then select from a
     * corpus index instead of materializing the corpus. The inventory line is the one a read would produce, so the
     * digest (and every comparison keyed on it) is unchanged. Only callers whose bytes are verified against the
     * declaration later may trust it: shared-input publication checks local bytes before upload and every iteration
     * checks fetched bytes before the case runs. A corpus directory declared by its files ([declaredTrees]) is likewise
     * inventoried from those files as the directory that holds exactly them; read, it must hold exactly them.
     */
    fun pinned(case: BenchCase, source: Path, corpus: Path, runtime: Runtime = Runtime(), digests: BenchInputDigests = BenchInputDigests(),
               trustDeclared: Boolean = false): BenchCase {
        validateInputs(case, source, corpus, digests, trustDeclared)
        val trees = declaredTrees(case)
        val inventory = case.inputs.flatMap { input ->
            val members = trees[input]
            // A source tree is local anyway: its directory is read, so files beside the declared ones are noticed.
            if (members != null && trustDeclared && input.root == BenchInputRoot.CORPUS)
                return@flatMap treeInventory(members).map { "${input.root}/${input.path}/$it" }
            val declared = input.sha256?.takeIf { trustDeclared && members == null }
            if (declared != null) return@flatMap listOf("${input.root}/${input.path}/:$declared")
            val base = if (input.root == BenchInputRoot.SOURCE) source else corpus
            val root = base.resolve(input.path)
            digests.inventory(base, root).also { lines ->
                if (members != null) requireTree(input, lines.filterNot { it.endsWith(":directory") }.associate {
                    it.substringBeforeLast(':') to it.substringAfterLast(':')
                })
            }.map { "${input.root}/${input.path}/$it" }
        }
        return case.copy(resources = case.resources.copy(requiredTags = case.resources.requiredTags + runtime.requiredTags),
            attributes = case.attributes + ("labInputDigest" to ExecutionStore.digest(inventory.joinToString("\n"))))
    }

    fun referencePlan(plan: BenchPlan, arm: Arm): BenchPlan = BenchPlan(plan.cases.map { case ->
        require(case.attributes["recordSchema"] == "solver-v1" && problem(case).model != null) {
            "reference arms require file-backed solver-v1 workloads"
        }
        case.copy(resources = case.resources.copy(cpus = arm.cores,
            timeoutSeconds = Experiments.caseTimeoutSec(arm)))
    })

    fun invocation(tree: Path, spec: BenchSpec, vararg args: String): String {
        val entry = entry(tree, spec)
        val cp = entry.classpath.joinToString(":") { tree.resolve(it).toString() }
        val runtime = runtime(tree, spec)
        val preflight = runtime.tools.joinToString("") { "test -x \"$(type -P -- ${quote(it)})\" || exit 69; " } +
            (runtime.java?.let { "actual=\"$(\"${'$'}{JAVA_HOME:+${'$'}JAVA_HOME/bin/}java\" -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.specification.version = //p')\"; test \"${'$'}actual\" = ${quote(it.toString())} || { echo 'bench requires Java $it' >&2; exit 69; }; " } ?: "") +
            if (runtime.environment.isEmpty()) "" else "export " + runtime.environment.entries.joinToString(" ") { "${it.key}=${quote(it.value)}" } + " && "
        return preflight + "exec \"\${JAVA_HOME:+\$JAVA_HOME/bin/}java\" " +
            (entry.jvmArgs + listOf("-cp", cp, entry.main) + args).joinToString(" ", transform = ::quote)
    }

    fun writeCase(directory: Path, index: Int, case: BenchCase): Path {
        require(index >= 0)
        val file = directory.resolve("cases/$index/bench-case.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, BenchProtocol.json.encodeToString(case) + "\n")
        return file
    }

    fun command(tree: Path, spec: BenchSpec, index: Int, corpus: Path, repository: String): String {
        val invocation = invocation(tree, spec, "run") + " \"\$OUT/../bench-case.json\""
        return "cd ${quote(tree.toString())} && " +
            "export OUT=\"\$JOB_DIR/cases/$index/out\" LAB_WORKTREE=${quote(tree.toString())} " +
            "LAB_CORPUS=${quote(corpus.toString())} LAB_REPOSITORY=${quote(repository)} && " +
            "mkdir -p \"\$OUT\" && " +
            if (spec.adapterResources) AdapterResources.command(invocation, index) else invocation
    }

    fun problem(case: BenchCase): Problem {
        fun input(role: String) = case.inputs.firstOrNull { it.role == role }?.let(::portableInput)
        return Problem(case.attributes["suite"] ?: "bench", BenchCases.key(case),
            collection = case.attributes["collection"].orEmpty(), family = case.attributes["family"].orEmpty(),
            format = case.attributes["format"].orEmpty(), category = case.attributes["category"].orEmpty(),
            model = input("model"), data = input("data"),
            includeDirs = case.inputs.filter { it.role == "include" }.map(::portableInput), benchCase = case)
    }

    private fun portableInput(input: BenchInput): String =
        (if (input.root == BenchInputRoot.CORPUS) "lab-corpus/" else "lab-source/") + input.path

    fun validateInputs(case: BenchCase, source: Path, corpus: Path, digests: BenchInputDigests = BenchInputDigests(),
                       trustDeclared: Boolean = false) {
        val trees = declaredTrees(case)
        for (input in case.inputs) {
            val root = if (input.root == BenchInputRoot.SOURCE) source else corpus
            val path = root.resolve(safeRelative(input.path))
            if (trustDeclared && input.sha256 != null) continue
            require(path.toRealPath().startsWith(root.toRealPath())) { "bench input escapes its root" }
            // A declared tree's files are inputs of their own, each checked against its checksum.
            if (input in trees) { require(Files.isDirectory(path)) { "declared input tree is not a directory: ${input.path}" }; continue }
            input.sha256?.let { require(Files.isRegularFile(path) && digests.checksum(root, path) == it) { "bench input checksum mismatch" } }
        }
    }

    /**
     * [case]'s directories declared by their files ([BenchInputTrees]), each with those files by path relative to it.
     * An input with a checksum is such a tree when another checksummed input of the same root lies below it; its files
     * are the checksummed inputs below it that are not trees themselves, and their tree checksum must equal its own.
     * A mismatch is the repository's declaration error, permanent like a declared file checksum mismatch.
     */
    fun declaredTrees(case: BenchCase): Map<BenchInput, Map<String, String>> {
        val declared = case.inputs.filter { it.sha256 != null }
        fun below(input: BenchInput, directory: BenchInput) = input.root == directory.root && input.path.startsWith(directory.path + "/")
        val trees = declared.filter { directory -> declared.any { below(it, directory) } }.distinct()
        if (trees.isEmpty()) return emptyMap()
        return trees.associateWith { tree ->
            val files = declared.filter { below(it, tree) && it !in trees }.groupBy({ it.path.removePrefix(tree.path + "/") }, { it.sha256!! })
            for ((path, hashes) in files) if (hashes.distinct().size != 1) throw SharedInputs.DeclaredInputMismatch(
                "declared input checksums differ: ${tree.path}/$path (${hashes.distinct().joinToString()})")
            files.mapValues { it.value.first() }.also { requireTree(tree, it) }
        }
    }

    private fun requireTree(tree: BenchInput, files: Map<String, String>) {
        val actual = if (files.isEmpty()) "no files" else BenchInputTrees.sha256(files)
        if (actual != tree.sha256) throw SharedInputs.DeclaredInputMismatch(
            "declared input tree checksum mismatch: ${tree.path} (declared ${tree.sha256}, files $actual, ${files.size} files)")
    }

    /** The inventory lines [BenchInputDigests.inventory] reads from a directory holding exactly [files], and no empty
     * subdirectories: as a shared input view of a declared tree does. */
    private fun treeInventory(files: Map<String, String>): List<String> {
        val entries = sortedMapOf<Path, String>(Path.of("") to "directory")
        for ((relative, sha) in files) {
            val path = safeRelative(relative)
            var parent = path.parent
            while (parent != null) { entries[parent] = "directory"; parent = parent.parent }
            entries[path] = sha
        }
        return entries.map { (path, value) -> "$path:$value" }
    }

    fun result(text: String?): BenchResult? = text?.let {
        runCatching {
            val objectValue = BenchProtocol.json.parseToJsonElement(it) as JsonObject
            BenchProtocol.json.decodeFromJsonElement(BenchResult.serializer(), objectValue["labBench"] ?: objectValue)
        }.getOrNull()
    }

    fun record(text: String, case: BenchCase, exit: Int): String {
        val result = BenchProtocol.json.decodeFromString<BenchResult>(text)
        require(result.caseId == case.id) { "bench returned a different case" }
        require((exit == 0) == (result.status != BenchStatus.FAILED)) { "bench result status disagrees with process exit $exit" }
        val envelope = BenchProtocol.json.encodeToJsonElement(BenchResult.serializer(), result)
        return if (case.attributes["recordSchema"] == "solver-v1" && result.details != null) {
            val domain = requireNotNull(result.details) { "solver adapter omitted its domain record" }
            require("labBench" !in domain)
            JsonObject(domain + ("labBench" to envelope)).toString()
        } else envelope.toString()
    }

    data class ExecutionRecord(val exit: Int, val json: String)

    fun executionRecord(text: String?, case: BenchCase, exit: Int): ExecutionRecord = runCatching {
        require(text != null) { "bench exited without its result (exit $exit)" }
        ExecutionRecord(exit, record(text, case, exit))
    }.getOrElse { failure ->
        ExecutionRecord(if (exit == 0) 70 else exit, BenchProtocol.json.encodeToString(BenchResult(
            case.id, BenchStatus.FAILED, message = "Invalid adapter result: ${failure.message}")))
    }

    fun metricsCsv(cases: List<CaseResult>): String = buildString {
        fun cell(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
        appendLine("index,arm,seed,repeat,caseId,status,metric,value,unit,direction,host")
        cases.forEach { case -> result(case.record?.toString())?.let { result ->
            result.metrics.forEach { metric -> appendLine(listOf(case.index.toString(), case.arm, case.seed?.toString().orEmpty(), case.repeat.toString(), result.caseId,
                result.status.name.lowercase(), metric.name, metric.value.toString(), metric.unit,
                metric.direction.name.lowercase(), case.host?.text.orEmpty()).joinToString(",", transform = ::cell)) }
        } }
    }

}
