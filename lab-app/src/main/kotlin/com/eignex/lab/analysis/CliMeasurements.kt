package com.eignex.lab.analysis

import com.eignex.lab.domain.*

import kotlinx.serialization.Serializable

/**
 * The files a measured CLI case left: its JFR recording, resources, HotSpot compilation log and manifest. The lab no
 * longer measures CLIs itself; these name what archived captures hold, which [CompilationReports] still reads.
 */
internal object CliMeasurements {
    const val JFR = "cli.jfr"
    const val RESOURCES = "resources.txt"
    const val MANIFEST = "measurement.json"
    /** HotSpot's compilation log, written with `measurement: "compilation+log"`. */
    const val COMPILATION_LOG = "hotspot-compilation.log"
    /** The compilation log as it comes back from a node, gzipped. */
    const val COMPILATION_LOG_GZ = "$COMPILATION_LOG.gz"
    val artifacts = listOf(JFR, RESOURCES)

    /** The measured CLI's artifacts, with a recording and compilation log only when requested. */
    fun artifacts(compilation: String?, recordsJfr: Boolean = true): List<String> =
        (if (recordsJfr) artifacts else listOf(RESOURCES)) +
            if (compilation == Experiments.COMPILATION_JFR_LOG) listOf(COMPILATION_LOG) else emptyList()

    /** Whether the lab itself runs on macOS, as the lab Mac does. */
    val localMacOs: Boolean get() = System.getProperty("os.name").orEmpty().startsWith("Mac")
}

@Serializable
internal data class CliMeasurementManifest(
    val caseIndex: Int,
    val attempt: Int,
    val measurementId: String,
    val instance: String,
    val command: String,
    val commandExit: Int,
    val javaOptions: String,
    val recordSha256: String?,
    val schemaVersion: Int = 1,
    val scope: String = "whole-cli-jvm",
    val allocation: String = "JFR weighted allocation samples; statistical estimate",
    val memory: String = "GNU time peak RSS of the CLI process, KiB",
    val artifacts: List<String> = CliMeasurements.artifacts +
        if (recordSha256 == null) emptyList() else listOf("solve-record.json"),
    /** Whether the case recorded every compilation ([ExperimentSpec.instrumented]): its times are no runtime measurement. */
    val instrumented: Boolean = false,
    val instrumentation: String = "jfr-profile",
    /** The job's [ExperimentSpec.compilationRecording], null without one. */
    val compilation: String? = null,
)
