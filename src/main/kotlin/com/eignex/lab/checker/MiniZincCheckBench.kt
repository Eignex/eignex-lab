package com.eignex.lab.checker

import com.eignex.lab.Problem
import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchCli
import com.eignex.lab.bench.BenchContext
import com.eignex.lab.bench.BenchDescriptor
import com.eignex.lab.bench.BenchRequest
import com.eignex.lab.bench.BenchResources
import com.eignex.lab.bench.BenchResult
import com.eignex.lab.bench.BenchSuite
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Replay one frozen MiniZinc capture through the same checker used by source validation. */
object MiniZincCheckBench : BenchSuite {
    override fun describe() = BenchDescriptor("minizinc-compiled-point", "Official compile-only checking of a retained integer point")

    override fun plan(request: BenchRequest): List<BenchCase> {
        val fields = setOf("record", "model", "fzn", "ozn", "stdout")
        require(request.parameters.keys == fields) { "Supply record, model, fzn, ozn and stdout as frozen UTF-8 text" }
        require(request.parameters.values.sumOf { it.length.toLong() } <= 24 * 1024 * 1024)
        return listOf(BenchCase("point-${sha256(request.parameters.getValue("record").toByteArray())}",
            request.parameters, BenchResources(memoryMiB = 4608, timeoutSeconds = 60)))
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        val report = context.outputDirectory.resolve("report").toFile().apply { mkdirs() }
        fun keep(key: String, name: String) = File(report, name).apply { writeText(case.parameters.getValue(key)) }
        val model = keep("model", "source.mzn")
        val recordFile = keep("record", "record.json")
        val record = Json.parseToJsonElement(recordFile.readText()) as JsonObject
        val fzn = keep("fzn", "model.fzn")
        val ozn = keep("ozn", "model.ozn")
        val stdout = keep("stdout", "cli.stdout")
        val problem = Problem("capture", "point", collection = "capture", format = "MINIZINC", model = model.path)
        val checker = SourceChecker(listOf(MiniZincChecker()), SourceLocator(report, hash = ::sha256), File(report, "scratch"))
        val verdict = checkNotNull(checker.check(problem, record, miniZincFrame = MiniZincFrame(
            fzn.readText(), ozn.readText(), stdout.readText()))) { "record has no solution claim" }
        File(report, "lab-check.json").writeText(verdict.json())
        return BenchResult(case.id, artifacts = context.artifacts("report"),
            details = Json.parseToJsonElement(verdict.json()) as JsonObject,
            message = "${verdict.state}: ${verdict.reason}")
    }
}

fun main(args: Array<String>) = BenchCli.execute(MiniZincCheckBench, args)
