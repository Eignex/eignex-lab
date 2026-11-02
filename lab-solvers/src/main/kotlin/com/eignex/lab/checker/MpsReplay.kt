package com.eignex.lab.checker

import com.eignex.lab.domain.*
import com.eignex.lab.reference.*

import com.eignex.lab.domain.Problem
import com.eignex.lab.bench.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.io.path.writeText

/** Repository-owned artifact replay, never a solver or a new proof authority. */
object MpsReplay : BenchSuite {
    override fun describe() = BenchDescriptor("mps-source-replay", "Check retained original MPS points and explicit negative controls")
    override fun plan(request: BenchRequest): List<BenchCase> {
        require(request.parameters.keys == setOf("checks")) { "Expected checks only" }
        val text = request.parameters.getValue("checks")
        require(text.length <= 1024 * 1024) { "Replay request exceeds 1 MiB" }
        val checks = Json.parseToJsonElement(text).jsonArray
        require(checks.size in 1..12) { "Replay requires 1..12 explicit checks" }
        return checks.mapIndexed { index, value ->
            val item = value.jsonObject
            val required = setOf("model", "record", "expected", "origin", "control")
            require(item.keys.containsAll(required) && item.keys.all { it in required + setOf("root", "numericalSemantics", "semanticsOrigin", "expectedConfigured") })
            require(("numericalSemantics" in item) == ("semanticsOrigin" in item)) { "Supplemental semantics require an explicit origin" }
            val model = item.getValue("model").jsonPrimitive.content
            val root = item["root"]?.jsonPrimitive?.content?.let { BenchInputRoot.valueOf(it) } ?: BenchInputRoot.SOURCE
            val record = item.getValue("record").jsonObject
            val hash = record.getValue("sourceHashes").jsonObject.getValue("model").jsonPrimitive.content
            require(item.getValue("expected").jsonPrimitive.content in LabCheck.STATES)
            item["expectedConfigured"]?.let { require(it.jsonPrimitive.content in LabCheck.STATES) }
            require(item.getValue("control").jsonObject.keys.all { it in setOf("finalWitness", "exactObjective") })
            BenchCase("mps-replay-$index", parameters = mapOf("check" to item.toString()),
                resources = BenchResources(cpus = 1, memoryMiB = 512, timeoutSeconds = 150, requiredTags = setOf("platform:linux", "arch:x86_64")),
                inputs = listOf(BenchInput(model, root = root, role = "model", sha256 = hash)))
        }
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        val item = Json.parseToJsonElement(case.parameters.getValue("check")).jsonObject
        val input = case.inputs.single()
        val root = requireNotNull(when (input.root) {
            BenchInputRoot.SOURCE -> context.worktree
            BenchInputRoot.CORPUS -> context.corpusDirectory
        }).toRealPath()
        val path = root.resolve(input.path).toRealPath()
        require(path.startsWith(root)) { "Frozen source escapes its declared input root" }
        val source = path.toFile()
        require(sha256(source) == input.sha256) { "Frozen original source digest differs" }
        val original = item.getValue("record").jsonObject
        require("numericalSemantics" !in item || (original["numericalSemantics"] == null || original["numericalSemantics"] == JsonNull)) { "Cannot replace retained semantics" }
        val effective = JsonObject(original + item.getValue("control").jsonObject +
            item["numericalSemantics"]?.let { mapOf("numericalSemantics" to it,
                "numericalSemanticsOrigin" to JsonPrimitive("conditional-replay"),
                "numericalSemanticsEvidence" to item.getValue("semanticsOrigin")) }.orEmpty())
        val output = context.outputDirectory
        output.resolve("original-record.json").writeText(original.toString())
        output.resolve("effective-record.json").writeText(effective.toString())
        output.resolve("origin.json").writeText(item.getValue("origin").toString())
        source.copyTo(output.resolve("original.mps").toFile())
        val scratch = output.resolve("scratch").toFile()
        // Use the ordinary point-checking path, without starting a solver or external tool.
        val adapter = RationalMpsChecker()
        val verdict = SourceChecker(listOf(adapter), SourceLocator(root.toFile()), scratch)
            .check(Problem("mps-replay", source.nameWithoutExtension, format = "MPS", model = source.path), effective)
        output.resolve("lab-check.json").writeText(verdict?.json() ?: "null")
        val expected = item.getValue("expected").jsonPrimitive.content
        val strict = verdict?.mpsChecks?.exact
        val expectedConfigured = item["expectedConfigured"]?.jsonPrimitive?.content
        val accepted = strict != null && strict.state == expected && strict.policy == Policies.MPS_EXACT &&
            (expectedConfigured == null || verdict?.mpsChecks?.configured?.state == expectedConfigured)
        return BenchResult(case.id, status = if (accepted) BenchStatus.SUCCESS else BenchStatus.FAILED,
            message = if (accepted) null else "Expected exact policy $expected; got ${verdict?.state}: ${verdict?.reason}",
            artifacts = listOf("original-record.json", "effective-record.json", "origin.json", "original.mps", "lab-check.json")
                .map { context.artifact(it) },
            details = buildJsonObject {
                put("scope", "retained exact feasible point; no optimality/proof/performance claim")
                put("origin", item.getValue("origin")); put("expected", expected)
                item["semanticsOrigin"]?.let { put("semanticsOrigin", it) }
                put("labCheck", verdict?.let { Json.parseToJsonElement(it.json()) } ?: JsonNull)
                put("javaVersion", System.getProperty("java.runtime.version"))
                put("os", System.getProperty("os.name")); put("arch", System.getProperty("os.arch"))
                put("memoryLimitMiB", case.resources.memoryMiB); put("cpus", case.resources.cpus)
            })
    }
}

fun main(args: Array<String>) = BenchCli.execute(MpsReplay, args)
