package com.eignex.lab

import com.eignex.lab.bench.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

/** A repository-owned suite. The committed manifest owns its build and launch recipe. */
@Serializable
data class BenchSpec(
    val manifest: String,
    val request: BenchRequest = BenchRequest(),
    val runTimeoutSec: Long? = null,
    val sourceValidation: Boolean = false,
    /** Exact workloads retained by a confirmation; runtime parameters still come from each approved revision. */
    val frozenCases: List<BenchCase> = emptyList(),
    val referenceFilter: ReferenceFilterMode = ReferenceFilterMode.ANY,
    val referenceFilters: Map<String, ReferenceFilterMode> = emptyMap(),
    val replayJobs: Map<String, Long> = emptyMap(),
) {
    init {
        safeRelative(manifest)
        require(runTimeoutSec == null || runTimeoutSec in 1..7200) { "bench runTimeoutSec must be between 1 and 7200" }
    }
}

/** Translation at the coordinator/worker boundary; no repository-specific executable names. */
internal object BenchRuns {
    fun entry(tree: Path, spec: BenchSpec): BenchEntryPoint {
        val file = tree.resolve(safeRelative(spec.manifest))
        require(file.toRealPath().startsWith(tree.toRealPath())) { "bench manifest escapes source" }
        val entry = BenchProtocol.json.decodeFromString<BenchEntryPoint>(Files.readString(file))
        ProfileRuns.validate("Eignex/bench", ProfileMain(entry.main, buildTasks = entry.buildTasks,
            classpath = entry.classpath, jvmArgs = entry.jvmArgs))
        runtime(tree, spec).validate()
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
        return manifest["runtime"]?.let { BenchProtocol.json.decodeFromJsonElement(Runtime.serializer(), it) } ?: Runtime()
    }

    /** The lab passes opaque parameter overrides to the repository planner. */
    fun request(spec: BenchSpec, arm: Arm): BenchRequest = spec.request.copy(parameters = spec.request.parameters +
        arm.values.filterKeys { it.startsWith("param.") }.mapKeys { it.key.removePrefix("param.") })

    /** Suites may declare a stable comparison identity when runtime settings change their case id. */
    fun key(case: BenchCase): String = case.attributes["comparisonKey"] ?: case.id

    fun align(canonical: List<BenchCase>, planned: BenchPlan): BenchPlan {
        val byKey = planned.cases.groupBy(::key)
        require(byKey.values.all { it.size == 1 }) { "ambiguous bench comparison identities" }
        return BenchPlan(canonical.map { frozen ->
            val candidate = requireNotNull(byKey[key(frozen)]?.singleOrNull()) { "bench revision omitted workload ${key(frozen)}" }
            require(candidate.inputs == frozen.inputs && candidate.attributes["labInputDigest"] == frozen.attributes["labInputDigest"]) {
                "bench arms resolved different input bytes for ${key(frozen)}"
            }
            candidate
        })
    }

    fun pinned(case: BenchCase, source: Path, corpus: Path, runtime: Runtime = Runtime()): BenchCase {
        validateInputs(case, source, corpus)
        val inventory = case.inputs.flatMap { input ->
            val root = (if (input.root == BenchInputRoot.SOURCE) source else corpus).resolve(input.path)
            val files = if (Files.isDirectory(root)) Files.walk(root).use { it.sorted().toList() } else listOf(root)
            files.map { file ->
                require(!Files.isSymbolicLink(file)) { "bench input contains a link" }
                "${input.root}/${input.path}/${root.relativize(file)}:" + if (Files.isDirectory(file)) "directory" else hashFile(file)
            }
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

    fun command(tree: Path, spec: BenchSpec, case: BenchCase, index: Int, corpus: Path, repository: String): String {
        val encoded = BenchProtocol.json.encodeToString(case)
        return "cd ${quote(tree.toString())} && " +
            "export OUT=\"\$JOB_DIR/cases/$index/out\" LAB_WORKTREE=${quote(tree.toString())} " +
            "LAB_CORPUS=${quote(corpus.toString())} LAB_REPOSITORY=${quote(repository)} && " +
            "mkdir -p \"\$OUT\" && printf '%s\\n' ${quote(encoded)} > \"\$OUT/../bench-case.json\" && " +
            invocation(tree, spec, "run") + " \"\$OUT/../bench-case.json\""
    }

    fun problem(case: BenchCase): Problem {
        fun input(role: String) = case.inputs.firstOrNull { it.role == role }?.let(::portableInput)
        return Problem(case.attributes["suite"] ?: "bench", key(case),
            collection = case.attributes["collection"].orEmpty(), family = case.attributes["family"].orEmpty(),
            format = case.attributes["format"].orEmpty(), category = case.attributes["category"].orEmpty(),
            model = input("model"), data = input("data"),
            includeDirs = case.inputs.filter { it.role == "include" }.map(::portableInput), benchCase = case)
    }

    private fun portableInput(input: BenchInput): String =
        (if (input.root == BenchInputRoot.CORPUS) "lab-corpus/" else "lab-source/") + input.path

    fun validateInputs(case: BenchCase, source: Path, corpus: Path) {
        for (input in case.inputs) {
            val root = if (input.root == BenchInputRoot.SOURCE) source else corpus
            val path = root.resolve(safeRelative(input.path))
            require(path.toRealPath().startsWith(root.toRealPath())) { "bench input escapes its root" }
            input.sha256?.let { require(Files.isRegularFile(path) && hashFile(path) == it) { "bench input checksum mismatch" } }
        }
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
                metric.direction.name.lowercase(), case.host.orEmpty()).joinToString(",", transform = ::cell)) }
        } }
    }

    fun resources(plan: BenchPlan): BenchResources {
        plan.cases.forEach {
            require(it.resources.cpus in 1..1024 && it.resources.memoryMiB <= Int.MAX_VALUE && it.resources.timeoutSeconds <= 604800)
            NodeTags.validate(NodeSelector(require = it.resources.requiredTags))
        }
        require(plan.cases.isNotEmpty()) { "bench selection matched no cases" }
        return BenchResources(plan.cases.maxOf { it.resources.cpus }, plan.cases.maxOf { it.resources.memoryMiB },
            plan.cases.maxOf { it.resources.timeoutSeconds }, plan.cases.flatMap { it.resources.requiredTags }.toSet())
    }
}
