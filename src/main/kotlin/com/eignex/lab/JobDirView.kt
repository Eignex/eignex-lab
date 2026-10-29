package com.eignex.lab

import kotlinx.serialization.json.Json

/**
 * What the pages show of a job's directory on the jobs volume, read once before a page is rendered ([read]), so no
 * renderer touches the volume: a page renders from this whether the volume answers or not.
 */
internal class JobDirView(
    /** The setup and job logs the directory holds. */
    val logs: Set<String> = emptySet(),
    /** A script job's exit, as its `0.exit` says. */
    val exit: String? = null,
    /** Why each case's processes were ended ([Termination]), by case. */
    val terminations: Map<Int, List<String>> = emptyMap(),
    /** The size of each command's `<index>.out` and `<index>.err` there is, by name. */
    val outputs: Map<String, Long> = emptyMap(),
    /** What a script job left in `cases/0/out`, relative to the directory. */
    val produced: List<String> = emptyList(),
    val recheck: RecheckResult? = null,
    val analysis: AnalysisResult? = null,
    /** The cloud instances it ran on (`aws-instances`), a line each. */
    val instances: List<String> = emptyList(),
) {
    companion object {
        /** A directory the pages cannot read: its volume is missing or does not answer. */
        val NONE = JobDirView()

        private val lenient = Json { ignoreUnknownKeys = true }

        /** [job]'s directory as the pages show it; [NONE] while the jobs volume does not answer ([Volumes.jobFiles]). */
        fun read(config: Config, job: Job): JobDirView {
            if (Volumes.jobFiles(config) != null) return NONE
            val dir = config.jobDir(job.id).toFile()
            val indices = job.commands.map { it.index }.ifEmpty { listOf(0) }
            return JobDirView(
                logs = listOf("setup.log", "job.log").filter { dir.resolve(it).isFile }.toSet(),
                exit = dir.resolve("0.exit").takeIf { it.isFile }?.readText()?.trim(),
                terminations = indices.associateWith { Termination.read(dir.toPath(), it) }.filterValues { it.isNotEmpty() },
                outputs = indices.flatMap { listOf("$it.out", "$it.err") }
                    .mapNotNull { name -> dir.resolve(name).takeIf { it.isFile }?.let { name to it.length() } }.toMap(),
                produced = dir.resolve("cases/0/out").walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.sorted().toList(),
                recheck = dir.resolve(CaseChecks.RESULT).takeIf { it.isFile }
                    ?.let { runCatching { lenient.decodeFromString(RecheckResult.serializer(), it.readText()) }.getOrNull() },
                analysis = dir.resolve(CompilationReports.ANALYSIS).takeIf { it.isFile }
                    ?.let { runCatching { lenient.decodeFromString(AnalysisResult.serializer(), it.readText()) }.getOrNull() },
                instances = instances(config, job),
            )
        }

        /** Only the cloud instances [job] ran on: what the queue page shows of each job's directory. */
        fun instances(config: Config, job: Job): List<String> =
            config.jobDir(job.id).resolve("aws-instances").toFile().takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }.orEmpty()
    }
}
