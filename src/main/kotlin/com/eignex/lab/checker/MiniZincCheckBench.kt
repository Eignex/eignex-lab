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
import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Replay one frozen MiniZinc capture through the same checker used by source validation. */
object MiniZincCheckBench : BenchSuite {
    override fun describe() = BenchDescriptor("minizinc-compiled-point", "Official compile-only checking of a retained integer point")

    override fun plan(request: BenchRequest): List<BenchCase> {
        val parameters = parameters(request.parameters)
        val fields = setOf("record", "model", "fzn", "ozn", "stdout")
        require(parameters.keys == fields) { "Supply record, model, fzn, ozn and stdout as frozen UTF-8 text" }
        require(parameters.values.sumOf { it.length.toLong() } <= 24 * 1024 * 1024)
        require(request.parameters.values.sumOf { it.length.toLong() } <= 120 * 1024) {
            "Use capture-zstd-base64 to keep the frozen case below the shell argument limit"
        }
        return listOf(BenchCase("point-${sha256(parameters.getValue("record").toByteArray())}",
            request.parameters, BenchResources(memoryMiB = 4608, timeoutSeconds = 60)))
    }

    internal fun parameters(frozen: Map<String, String>): Map<String, String> {
        val packed = frozen["capture-zstd-base64"] ?: return frozen
        require(frozen.size == 1 && packed.length <= 120 * 1024)
        val bytes = ZstdInputStreamNoFinalizer(ByteArrayInputStream(Base64.getDecoder().decode(packed))).use {
            it.readNBytes(24 * 1024 * 1024 + 1)
        }
        require(bytes.size <= 24 * 1024 * 1024) { "unpacked capture exceeds 24 MiB" }
        return Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()),
            bytes.decodeToString(throwOnInvalidSequence = true))
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        val parameters = parameters(case.parameters)
        val report = context.outputDirectory.resolve("report").toFile().apply { mkdirs() }
        fun keep(key: String, name: String) = File(report, name).apply { writeText(parameters.getValue(key)) }
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
