package com.eignex.lab.checker

import com.eignex.lab.Problem
import com.eignex.lab.bench.*
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Replays retained source bytes and the complete original-coordinate record through production adapters. */
object WeightedCheckBench : BenchSuite {
    override fun describe() = BenchDescriptor("weighted-source-point", "Independent exact checking of retained OPB/WBO/WCNF points")
    override fun plan(request: BenchRequest): List<BenchCase> {
        val parameters = MiniZincCheckBench.parameters(request.parameters)
        require(parameters.keys == setOf("format", "model", "record") || parameters.keys == setOf("format", "model", "record", "alteredControl")) {
            "Supply format, model and record; optionally alteredControl=true"
        }
        require(parameters.getValue("format") in setOf("OPB", "WBO", "WCNF"))
        require(parameters["alteredControl"] in setOf(null, "true"))
        require(parameters.values.sumOf { it.length.toLong() } <= 24 * 1024 * 1024)
        require(request.parameters.values.sumOf { it.length.toLong() } <= 120 * 1024) { "Use capture-zstd-base64 for large captures" }
        val identity = sha256(parameters.getValue("record").toByteArray())
        return (if (parameters["alteredControl"] == "true") listOf(false, true) else listOf(false)).map { altered ->
            BenchCase("point-$identity${if (altered) "-altered" else ""}", request.parameters,
                BenchResources(cpus = 1, memoryMiB = 1024, timeoutSeconds = 30), attributes = mapOf("altered" to altered.toString()))
        }
    }
    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        val parameters = MiniZincCheckBench.parameters(case.parameters)
        val report = context.outputDirectory.resolve("report").toFile().apply { mkdirs() }
        val model = File(report, "original.source").apply { writeText(parameters.getValue("model")) }
        val original = Json.parseToJsonElement(parameters.getValue("record")) as JsonObject
        File(report, "original-record.json").writeText(parameters.getValue("record"))
        val record = if (case.attributes["altered"] == "true") altered(original, parameters.getValue("format")) else original
        File(report, "record.json").writeText(record.toString())
        val problem = Problem("capture", "point", collection = "capture", format = parameters.getValue("format"), model = model.path)
        val checker = SourceChecker(listOf(OpbChecker(), WboChecker(), WcnfChecker()), SourceLocator(report, hash = ::sha256), File(report, "scratch"))
        val verdict = requireNotNull(checker.check(problem, record)) { "record has no solution claim" }
        File(report, "lab-check.json").writeText(verdict.json())
        return BenchResult(case.id, artifacts = context.artifacts("report"), details = Json.parseToJsonElement(verdict.json()) as JsonObject,
            message = "${verdict.state}: ${verdict.reason}")
    }

    /** Flip exactly the first retained coordinate. The original record remains an artifact beside the changed one. */
    internal fun altered(record: JsonObject, format: String): JsonObject {
        val witness = (record["finalWitness"] as? JsonPrimitive)?.content ?: error("no witness for altered control")
        val changed = if (format == "WCNF" && Regex("v\\s+[01]+\\s*").matches(witness)) {
            val at = witness.indexOfFirst { it == '0' || it == '1' }
            witness.replaceRange(at, at + 1, if (witness[at] == '1') "0" else "1")
        } else {
            val first = Regex("(?<=v\\s)[-]?(?:x)?[1-9][0-9]*").find(witness) ?: error("no first coordinate for altered control")
            witness.replaceRange(first.range, if (first.value.startsWith('-')) first.value.drop(1) else "-${first.value}")
        }
        return JsonObject(record + ("finalWitness" to JsonPrimitive(changed)))
    }
}

fun main(args: Array<String>) = BenchCli.execute(WeightedCheckBench, args)
