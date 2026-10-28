package com.eignex.lab.checker

import com.eignex.lab.reference.MiniZincIdentity
import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Compile a fully pinned retained FlatZinc frame, then apply its official output model. No backend is invoked. */
class MiniZincChecker : FormatChecker {
    override val format = "MINIZINC"
    override val policy = Policies.MINIZINC
    override val checker = "MiniZinc compile-only"

    override fun check(case: CheckCase, work: File): Verdict {
        val frame = case.miniZincFrame ?: return Verdict(LabCheck.UNSUPPORTED,
            "no retained FlatZinc/OZN/native point; solver-based witness completion is disabled", checker = "none")
        val bound = try {
            MiniZincPoint.bind(frame, case.claimedObjective)
        } catch (failure: IllegalArgumentException) {
            return Verdict(LabCheck.UNSUPPORTED, failure.message ?: "unsupported complete-point protocol")
        } catch (failure: IllegalStateException) {
            return Verdict(LabCheck.UNSUPPORTED, failure.message ?: "incomplete point capture")
        }
        fun capture(text: String) = text.lineSequence().filter { it.startsWith("% klause-assignment") }.joinToString("\n")
        if (capture(frame.stdout.split("----------").dropLast(1).last()) != capture(case.witness)) {
            return Verdict(LabCheck.UNSUPPORTED, "record and native stdout kept different complete-point frames")
        }
        val started = System.nanoTime()
        val identity = MiniZincIdentity.capture("gecode", TIMEOUT_MS)
        val receipts = Json.decodeFromString(ListSerializer(ToolResourceReceipt.serializer()),
            identity.stats.getValue("referenceIdentityResources")).toMutableList()
        val inputs = ArrayList<CheckedInput>()
        fun input(name: String, text: String): File = File(work, name).apply {
            writeText(text)
            inputs += CheckedInput(canonicalPath, sha256(this), text)
        }
        val flatZinc = input("original.fzn", frame.flatZinc)
        val ozn = input("original.ozn", frame.outputModel)
        val native = input("point.dzn", bound.native)
        val model = input("pinned.mzn", bound.model)
        val output = File(work, "checked.fzn")
        fun run(arguments: List<String>, files: List<File>, stdin: File? = null): ToolRun {
            val remaining = TIMEOUT_MS - (System.nanoTime() - started) / 1_000_000
            check(remaining > 0) { "MiniZinc assignment check exhausted its allowance" }
            return ToolProcess.run(listOf(identity.binary.path) + arguments, work, remaining, stdin,
                limits = ToolLimits((remaining + 999) / 1000, remaining),
                inputFiles = listOf(identity.binary) + files).also { it.resources?.let(receipts::add) }
        }
        fun verdict(state: String, reason: String, run: ToolRun) = Verdict(
            state, reason, identity.stats.getValue("referenceVersion"),
            identity.stats.getValue("referenceCompilerSha256"), diagnostic = run.tail(),
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
            return verdict(LabCheck.UNKNOWN, "MiniZinc left residual declarations or constraints; no completion was attempted", compile)
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
            "MiniZinc discharged the pinned integer/Boolean FlatZinc frame and reproduced its model witness; no optimality claim", rendered)
    }

    companion object { private const val TIMEOUT_MS = 15_000L }
}
