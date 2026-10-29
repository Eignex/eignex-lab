package com.eignex.lab.checker

import com.eignex.lab.Problem
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
            require(item.keys == setOf("model", "record", "expected", "origin", "control"))
            val model = item.getValue("model").jsonPrimitive.content
            val record = item.getValue("record").jsonObject
            val hash = record.getValue("sourceHashes").jsonObject.getValue("model").jsonPrimitive.content
            require(item.getValue("expected").jsonPrimitive.content in LabCheck.STATES)
            require(item.getValue("control").jsonObject.keys.all { it in setOf("finalWitness", "exactObjective") })
            BenchCase("mps-replay-$index", parameters = mapOf("check" to item.toString()),
                resources = BenchResources(cpus = 1, memoryMiB = 512, timeoutSeconds = 150, requiredTags = setOf("platform:linux", "arch:x86_64")),
                inputs = listOf(BenchInput(model, role = "model", sha256 = hash)))
        }
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        val item = Json.parseToJsonElement(case.parameters.getValue("check")).jsonObject
        val source = requireNotNull(context.worktree).resolve(case.inputs.single().path).toFile()
        require(sha256(source) == case.inputs.single().sha256) { "Frozen original source digest differs" }
        val original = item.getValue("record").jsonObject
        val effective = JsonObject(original + item.getValue("control").jsonObject)
        val output = context.outputDirectory
        output.resolve("original-record.json").writeText(original.toString())
        output.resolve("effective-record.json").writeText(effective.toString())
        output.resolve("origin.json").writeText(item.getValue("origin").toString())
        source.copyTo(output.resolve("original.mps").toFile())
        val scratch = output.resolve("scratch").toFile()
        // The same router used by Checkers.of; legacy tool setup is lazy, so exact rational checks need none.
        val adapter = RationalMpsChecker(MpsChecker(Solchecker(output.resolve("tools").toFile())::binary))
        val verdict = SourceChecker(listOf(adapter), SourceLocator(requireNotNull(context.worktree).toFile()), scratch)
            .check(Problem("mps-replay", source.nameWithoutExtension, format = "MPS", model = source.path), effective)
        output.resolve("lab-check.json").writeText(verdict?.json() ?: "null")
        val expected = item.getValue("expected").jsonPrimitive.content
        val accepted = verdict != null && verdict.state == expected && verdict.policy == Policies.MPS_EXACT
        return BenchResult(case.id, status = if (accepted) BenchStatus.SUCCESS else BenchStatus.FAILED,
            message = if (accepted) null else "Expected exact policy $expected; got ${verdict?.state}: ${verdict?.reason}",
            artifacts = listOf("original-record.json", "effective-record.json", "origin.json", "original.mps", "lab-check.json")
                .map { context.artifact(it) },
            details = buildJsonObject {
                put("scope", "retained exact feasible point; no optimality/proof/performance claim")
                put("origin", item.getValue("origin")); put("expected", expected)
                put("labCheck", verdict?.let { Json.parseToJsonElement(it.json()) } ?: JsonNull)
                put("javaVersion", System.getProperty("java.runtime.version"))
                put("os", System.getProperty("os.name")); put("arch", System.getProperty("os.arch"))
                put("memoryLimitMiB", case.resources.memoryMiB); put("cpus", case.resources.cpus)
            })
    }
}

fun main(args: Array<String>) = BenchCli.execute(MpsReplay, args)
