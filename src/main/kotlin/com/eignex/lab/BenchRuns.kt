package com.eignex.lab

import com.eignex.lab.bench.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path

/** A repository-owned suite. The committed manifest owns its build and launch recipe. */
@Serializable
data class BenchSpec(val manifest: String, val request: BenchRequest = BenchRequest()) {
    init { safeRelative(manifest) }
}

/** Translation at the coordinator/worker boundary; no repository-specific executable names. */
internal object BenchRuns {
    fun entry(tree: Path, spec: BenchSpec): BenchEntryPoint {
        val file = tree.resolve(safeRelative(spec.manifest))
        require(file.toRealPath().startsWith(tree.toRealPath())) { "bench manifest escapes source" }
        val entry = BenchProtocol.json.decodeFromString<BenchEntryPoint>(Files.readString(file))
        ProfileRuns.validate("Eignex/bench", ProfileMain(entry.main, buildTasks = entry.buildTasks,
            classpath = entry.classpath, jvmArgs = entry.jvmArgs))
        return entry
    }

    fun invocation(tree: Path, spec: BenchSpec, vararg args: String): String {
        val entry = entry(tree, spec)
        val cp = entry.classpath.joinToString(":") { tree.resolve(it).toString() }
        return "exec \"\${JAVA_HOME:+\$JAVA_HOME/bin/}java\" " +
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
        return Problem(case.attributes["suite"] ?: "bench", case.id,
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
        appendLine("index,caseId,status,metric,value,unit,direction,host")
        cases.forEach { case -> result(case.record?.toString())?.let { result ->
            result.metrics.forEach { metric -> appendLine(listOf(case.index.toString(), result.caseId,
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
