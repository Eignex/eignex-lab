package com.eignex.lab.bench

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.serialization.encodeToString
import kotlin.io.path.readText

/** File-based entry point for committed benchmark executables; it never builds or times workloads itself. */
public object BenchCli {
    private const val USAGE = "Usage: describe | plan REQUEST.json | run CASE.json [OUTPUT_DIR]"

    /**
     * Dispatch a command. Describe and plan write JSON to [stdout]; run writes `result.json` under OUTPUT_DIR or OUT.
     * Exceptions propagate so a JVM main exits unsuccessfully while retaining a failed result when execution began.
     */
    public fun execute(
        suite: BenchSuite,
        args: Array<String>,
        environment: Map<String, String> = System.getenv(),
        stdout: Appendable = System.out,
    ) {
        when (args.firstOrNull()) {
            "describe" -> {
                require(args.size == 1) { USAGE }
                stdout.appendLine(BenchProtocol.json.encodeToString(suite.describe()))
            }
            "plan" -> {
                require(args.size == 2) { USAGE }
                val request = BenchProtocol.json.decodeFromString<BenchRequest>(Path.of(args[1]).readText())
                stdout.appendLine(BenchProtocol.json.encodeToString(BenchPlan(suite.plan(request))))
            }
            "run" -> {
                require(args.size in 2..3) { USAGE }
                val case = BenchProtocol.json.decodeFromString<BenchCase>(Path.of(args[1]).readText())
                val output = args.getOrNull(2) ?: environment["OUT"]?.takeIf(String::isNotBlank)
                require(!output.isNullOrBlank()) { "Supply OUTPUT_DIR or set OUT" }
                val context = BenchContext(
                    Path.of(output), environment["LAB_REPOSITORY"],
                    environment["LAB_WORKTREE"]?.let(Path::of),
                )
                run(suite, case, context)
            }
            else -> throw IllegalArgumentException(USAGE)
        }
    }

    private fun run(suite: BenchSuite, case: BenchCase, context: BenchContext) {
        val target = context.outputDirectory.resolve("result.json")
        // A killed retry must not leave an earlier successful result describing this attempt.
        Files.deleteIfExists(target)
        val result = try {
            suite.run(case, context).also {
                require(it.caseId == case.id) { "Result case id ${it.caseId} does not match ${case.id}" }
                it.artifacts.forEach(context::validateArtifact)
                writeResult(target, it)
            }
        } catch (failure: Exception) {
            try {
                writeResult(target, BenchResult(
                    case.id, BenchStatus.FAILED,
                    message = failure.message?.takeIf(String::isNotBlank) ?: failure.javaClass.name,
                ))
            } catch (writeFailure: Exception) {
                failure.addSuppressed(writeFailure)
            }
            throw failure
        }
        check(result.status != BenchStatus.FAILED) { result.message ?: "Benchmark failed" }
    }

    private fun writeResult(target: Path, result: BenchResult) {
        val temporary = Files.createTempFile(target.parent, ".bench-result-", ".json")
        try {
            Files.writeString(temporary, BenchProtocol.json.encodeToString(result) + "\n")
            try {
                Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
