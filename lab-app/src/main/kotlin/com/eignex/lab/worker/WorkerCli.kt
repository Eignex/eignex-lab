package com.eignex.lab.worker

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** Offline acceptance runner; the connected session loop is supplied by the coordinator integration. */
object WorkerCli {
    fun main(args: List<String>): Int = runCatching {
        require(args.size == 4) { "usage: worker-local MANIFEST ROOT SUPERVISOR_SOCKET LAB_LAUNCHER" }
        val manifest = Json.decodeFromString<WorkManifest>(Files.readString(Path.of(args[0])))
        val root = Path.of(args[1]).toAbsolutePath(); Files.createDirectories(root)
        val mac = CliMeasurements.localMacOs
        val executor = IterationExecutor(ProcessSupervisor(Path.of(args[2])), VerifiedCache(root.resolve("cache")),
            { Files.newInputStream(root.resolve("inputs/${it.sha256}.zip")) }, args[3], if (mac) "mac" else "linux", workerArch(),
            WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(Path.of(args[2]))))
        val deadline = System.nanoTime() + 70_000_000_000
        val results = manifest.cases.groupBy { Triple(it.problem, it.seed, it.repeat) }.values.mapIndexed { index, cases ->
            executor.run(manifest, cases.map { it.index }, root.resolve("attempt-$index"), "offline-worker", 1) { (deadline - System.nanoTime()) / 1_000_000 }
        }
        Files.writeString(root.resolve("results.json"), Json.encodeToString(results))
        0
    }.getOrElse { System.err.println("worker-local: ${it.message}"); 2 }
}

internal fun workerArch(): String = when (System.getProperty("os.arch")) {
    "amd64", "x86_64" -> "amd64"
    "aarch64", "arm64" -> "arm64"
    else -> error("unsupported worker architecture")
}
