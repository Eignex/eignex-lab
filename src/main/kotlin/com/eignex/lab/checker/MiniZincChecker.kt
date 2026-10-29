package com.eignex.lab.checker

import com.eignex.lab.reference.MiniZincIdentity
import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Check a fully pinned frame with MiniZinc and Choco's existing assignment API, then apply its output model. */
class MiniZincChecker : FormatChecker {
    override val format = "MINIZINC"
    override val policy = Policies.MINIZINC
    override val checker = "MiniZinc / Choco Constraint.isSatisfied"

    override fun check(case: CheckCase, work: File): Verdict {
        val frame = case.miniZincFrame ?: return Verdict(LabCheck.UNSUPPORTED,
            "no retained FlatZinc/OZN/native point; solver-based witness completion is disabled", checker = "none")
        val witnessLevel = ((case.record["stats"] as? JsonObject)?.get("finalWitnessLevel") as? JsonPrimitive)?.content
        if (witnessLevel != null && witnessLevel != "model") {
            return Verdict(LabCheck.UNSUPPORTED, "the record did not retain a model-level witness for output checking")
        }
        val sourceHashes = case.record["sourceHashes"] as? JsonObject
        val modelHash = (sourceHashes?.get("model") as? JsonPrimitive)?.content
        val source = source(case.sources.model)
        if (modelHash == null || modelHash != sha256(source.toByteArray())) {
            return Verdict(LabCheck.UNSUPPORTED, "compiled-point checking requires the matching original source digest")
        }
        val bound = try {
            MiniZincPoint.bind(frame, case.claimedObjective)
        } catch (failure: IllegalArgumentException) {
            return Verdict(LabCheck.UNSUPPORTED, failure.message ?: "unsupported complete-point protocol")
        } catch (failure: IllegalStateException) {
            return Verdict(LabCheck.UNSUPPORTED, failure.message ?: "incomplete point capture")
        }
        if (case.optimize != bound.optimize) {
            return Verdict(LabCheck.UNSUPPORTED, "record objective kind disagrees with the retained solve item")
        }
        fun capture(text: String) = text.lineSequence().filter { it.startsWith("% klause-assignment") }.joinToString("\n")
        if (capture(frame.stdout.split("----------").dropLast(1).last()) != capture(case.witness)) {
            return Verdict(LabCheck.UNSUPPORTED, "record and native stdout kept different complete-point frames")
        }
        val started = System.nanoTime()
        val identity = MiniZincIdentity.capture("gecode", TIMEOUT_MS)
        var version = identity.stats.getValue("referenceVersion")
        var binaryHash = identity.stats.getValue("referenceCompilerSha256")
        var checkerName = "MiniZinc compile-only"
        val receipts = Json.decodeFromString(ListSerializer(ToolResourceReceipt.serializer()),
            identity.stats.getValue("referenceIdentityResources")).toMutableList()
        val inputs = ArrayList<CheckedInput>()
        fun input(name: String, text: String): File = File(work, name).apply {
            writeText(text)
            inputs += CheckedInput(canonicalPath, sha256(this), text)
        }
        val flatZinc = input("original.fzn", frame.flatZinc)
        input("source.mzn", source)
        case.sources.data?.let { input("source.dzn", source(it)) }
        val ozn = input("original.ozn", frame.outputModel)
        val native = input("point.dzn", bound.native)
        val model = input("pinned.mzn", bound.model)
        val output = File(work, "checked.fzn")
        fun run(arguments: List<String>, files: List<File>, stdin: File? = null,
                binary: File = identity.binary, environment: Map<String, String> = emptyMap()): ToolRun {
            val remaining = TIMEOUT_MS - (System.nanoTime() - started) / 1_000_000
            if (remaining <= 0) return ToolRun(null, "", "MiniZinc assignment check exhausted its allowance", true, 0)
            return ToolProcess.run(listOf(binary.path) + arguments, work, remaining, stdin, environment,
                limits = ToolLimits((remaining + 999) / 1000, remaining),
                inputFiles = listOf(binary) + files).also { it.resources?.let(receipts::add) }
        }
        fun verdict(state: String, reason: String, run: ToolRun) = Verdict(
            state, reason, version, binaryHash, checker = checkerName, diagnostic = run.tail(),
            resourceReceipts = receipts, inputs = inputs,
        )
        val compile = run(listOf("--compile", "--solver", "gecode", "--no-output-ozn",
            "--output-fzn-to-file", output.path, model.path), listOf(model, flatZinc))
        if (compile.resources?.qualified() != true || compile.exit != 0 || !output.isFile) {
            return verdict(LabCheck.UNKNOWN, "MiniZinc could not discharge the pinned frame: ${compile.tail()}", compile)
        }
        val compiled = output.readText()
        inputs += CheckedInput(output.canonicalPath, sha256(output), compiled)
        if (MiniZincPoint.inconsistent(compiled)) {
            return verdict(LabCheck.INVALID, "MiniZinc rejected the pinned original FlatZinc constraints or claimed objective", compile)
        }
        if (!MiniZincPoint.discharged(compiled)) {
            val runtime = try { FlatZincGroundChecker.runtime() } catch (failure: Exception) {
                return verdict(LabCheck.UNKNOWN, "the assignment checker could not establish its pinned jars: ${failure.message}", compile)
            }
            binaryHash = runtime.sha256
            checkerName = "Choco Constraint.isSatisfied / MiniZinc output"
            version += "|${runtime.version}|sha256=$binaryHash"
            val checked = run(runtime.arguments + output.path, listOf(output) + runtime.files,
                binary = runtime.java, environment = mapOf("JAVA_TOOL_OPTIONS" to "", "JDK_JAVA_OPTIONS" to "", "_JAVA_OPTIONS" to ""))
            val answer = checked.stdout.lineSequence().filter { it.startsWith("${FlatZincGroundCheckerMain.MARKER}\t") }
                .singleOrNull()?.substringAfter('\t')
            if (checked.resources?.qualified() != true || checked.exit != 0) {
                return verdict(LabCheck.UNKNOWN, "the official checker could not evaluate the fixed frame: ${checked.tail()}", checked)
            }
            if (answer == "INVALID") {
                return verdict(LabCheck.INVALID, "Choco Constraint.isSatisfied rejected the fixed original assignment", checked)
            }
            if (answer != "VALID") {
                return verdict(LabCheck.UNKNOWN, "the assignment checker returned no recognized verdict", checked)
            }
        }
        val rendered = run(listOf("--ozn-file", ozn.path), listOf(ozn, native), native)
        if (rendered.resources?.qualified() != true || rendered.exit != 0 || "----------" !in rendered.stdout) {
            return verdict(LabCheck.UNKNOWN, "MiniZinc could not apply the retained output model: ${rendered.tail()}", rendered)
        }
        fun content(text: String) = text.substringBefore("----------").lineSequence()
            .filter { it.isNotBlank() && !it.trimStart().startsWith('%') }.joinToString("\n").trim()
        if (content(rendered.stdout) != content(case.witness)) {
            return verdict(LabCheck.INVALID, "the retained output model disagrees with the recorded model witness", rendered)
        }
        return verdict(LabCheck.VALID,
            "The pinned integer/Boolean FlatZinc frame was accepted and its model witness reproduced; " +
                "${bound.diagnosticCoordinates} extra diagnostic coordinates excluded; no optimality claim", rendered)
    }

    companion object {
        private const val TIMEOUT_MS = 15_000L

        internal fun source(file: File): String = file.inputStream().use { raw ->
            val input = if (file.name.endsWith(".zst")) ZstdInputStreamNoFinalizer(raw) else raw
            input.use {
                val bytes = it.readNBytes(8 * 1024 * 1024 + 1)
                require(bytes.size <= 8 * 1024 * 1024) { "MiniZinc source exceeds the 8 MiB capture limit" }
                bytes.decodeToString(throwOnInvalidSequence = true)
            }
        }
    }
}
