package com.eignex.lab.analysis

import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/** A measured case's manifest as archived captures carry it, for the report and archive tests. */
internal object CliMeasurementFixtures {
    private fun instrumentation(compilation: String?): String = when (compilation) {
        null -> "jfr-profile"
        com.eignex.lab.domain.Experiments.COMPILATION_JFR -> "jfr-profile+compilation"
        else -> "jfr-profile+compilation+hotspot-log"
    }

    private fun memory(macOs: Boolean): String =
        if (macOs) "BSD time -l, getrusage ru_maxrss of the CLI process (bytes on macOS, written as KiB), KiB"
        else "GNU time peak RSS of the CLI process, KiB"

    fun writeManifest(
        files: Path, index: Int, attempt: Int, measurementId: String, instance: String, command: String, exit: Int,
        javaOptions: String, record: String?, macOs: Boolean, compilation: String? = null,
        recordsJfr: Boolean = true,
    ) {
        val recordHash = record?.takeIf { it.isNotEmpty() }?.let { snapshot ->
            files.resolve("solve-record.json").toFile().writeText(snapshot)
            MessageDigest.getInstance("SHA-256").digest(snapshot.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        files.resolve(CliMeasurements.MANIFEST).toFile().writeText(Json { encodeDefaults = true }.encodeToString(
            CliMeasurementManifest.serializer(),
            CliMeasurementManifest(
                index, attempt, measurementId, instance, command, exit, javaOptions, recordHash, memory = memory(macOs),
                instrumented = compilation != null,
                instrumentation = if (recordsJfr) instrumentation(compilation) else "external-time",
                compilation = compilation,
                allocation = if (recordsJfr) "JFR weighted allocation samples; statistical estimate" else "not collected",
                artifacts = CliMeasurements.artifacts(compilation, recordsJfr) + if (recordHash == null) emptyList() else listOf("solve-record.json"),
            ),
        ))
    }
}
