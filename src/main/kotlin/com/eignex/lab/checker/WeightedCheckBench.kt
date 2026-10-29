package com.eignex.lab.checker

import com.eignex.lab.Problem
import com.eignex.lab.bench.*
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Replays retained source bytes and the complete original-coordinate record through production adapters. */
object WeightedCheckBench : BenchSuite {
    override fun describe() = BenchDescriptor("weighted-source-point", "Independent exact checking of retained OPB/WBO/WCNF points")
    override fun plan(request: BenchRequest): List<BenchCase> {
        val parameters = MiniZincCheckBench.parameters(request.parameters)
        require(parameters.keys == setOf("format", "model", "record")) { "Supply format, model and record" }
        require(parameters.getValue("format") in setOf("OPB", "WBO", "WCNF"))
        require(parameters.values.sumOf { it.length.toLong() } <= 24 * 1024 * 1024)
        require(request.parameters.values.sumOf { it.length.toLong() } <= 120 * 1024) { "Use capture-zstd-base64 for large captures" }
        return listOf(BenchCase("point-${sha256(parameters.getValue("record").toByteArray())}", request.parameters,
            BenchResources(cpus = 1, memoryMiB = 1024, timeoutSeconds = 30)))
    }
    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        val parameters = MiniZincCheckBench.parameters(case.parameters)
        val report = context.outputDirectory.resolve("report").toFile().apply { mkdirs() }
        val model = File(report, "original.source").apply { writeText(parameters.getValue("model")) }
        val record = Json.parseToJsonElement(parameters.getValue("record")) as JsonObject
        File(report, "record.json").writeText(record.toString())
        val problem = Problem("capture", "point", collection = "capture", format = parameters.getValue("format"), model = model.path)
        val checker = SourceChecker(listOf(OpbChecker(), WboChecker(), WcnfChecker()), SourceLocator(report, hash = ::sha256), File(report, "scratch"))
        val verdict = requireNotNull(checker.check(problem, record)) { "record has no solution claim" }
        File(report, "lab-check.json").writeText(verdict.json())
        return BenchResult(case.id, artifacts = context.artifacts("report"), details = Json.parseToJsonElement(verdict.json()) as JsonObject,
            message = "${verdict.state}: ${verdict.reason}")
    }
}

fun main(args: Array<String>) = BenchCli.execute(WeightedCheckBench, args)
