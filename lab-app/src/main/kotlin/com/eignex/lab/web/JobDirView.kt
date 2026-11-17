package com.eignex.lab.web

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.worker.*

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
) {
    companion object {
        /** A directory the pages cannot read: its volume is missing or does not answer. */
        val NONE = JobDirView()


        /** Only the files the requested view displays; [NONE] while the jobs volume does not answer ([Volumes.jobFiles]). */
        fun read(paths: DataPaths, job: Job, showCommands: Boolean = true, failedOnly: Boolean = false): JobDirView {
            if (Volumes.jobFiles(paths) != null) return NONE
            val dir = paths.jobDir(job.id).toFile()
            val work = job.experiment.work
            val singleCase = work is Work.Profile || work is Work.Script
            // The overview does not render command outputs. Archived jobs can have thousands of these files.
            val indices = if (showCommands) job.commands.filter { !failedOnly || it.status == Status.FAILED }.map { it.index }
                else emptyList()
            val terminationIndices = if (singleCase) (indices + 0).distinct() else indices
            val analyze = work.analysis
            return JobDirView(
                logs = listOf("setup.log", "job.log").filter { dir.resolve(it).isFile }.toSet(),
                exit = if (singleCase) dir.resolve("0.exit").takeIf { it.isFile }?.readText()?.trim() else null,
                terminations = terminationIndices.associateWith { Termination.read(dir.toPath(), it) }.filterValues { it.isNotEmpty() },
                outputs = indices.flatMap { listOf("$it.out", "$it.err") }
                    .mapNotNull { name -> dir.resolve(name).takeIf { it.isFile }?.let { name to it.length() } }.toMap(),
                produced = if (singleCase) dir.resolve("cases/0/out").walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.sorted().toList() else emptyList(),
                recheck = if (analyze?.kind == AnalyzeSpec.CHECK) dir.resolve(CaseChecks.RESULT).takeIf { it.isFile }
                    ?.let { runCatching { lenientJson.decodeFromString(RecheckResult.serializer(), it.readText()) }.getOrNull() } else null,
                analysis = if (analyze != null && analyze.kind != AnalyzeSpec.CHECK) dir.resolve(CompilationReports.ANALYSIS).takeIf { it.isFile }
                    ?.let { runCatching { lenientJson.decodeFromString(AnalysisResult.serializer(), it.readText()) }.getOrNull() } else null,
            )
        }
    }
}
