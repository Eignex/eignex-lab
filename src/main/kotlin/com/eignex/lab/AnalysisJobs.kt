package com.eignex.lab

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlinx.serialization.json.Json

/** Runs the lab's own analysis jobs: compilation reports over retained or archived captures, and source rechecks. */
internal class AnalysisJobs(private val config: Config, private val store: Store, private val checks: CaseChecks) {
    fun reportCompilation(jobId: Long, index: Int, dir: Path, analyzeJob: Long? = null): List<Pair<Path, CompilationReport>> =
        runCatching {
            val source = store.caseOf(jobId, index)?.let { (problem, arm) ->
                SourceProvenance(jobId, index, if (problem.suite.isEmpty()) problem.problem else "${problem.suite}/${problem.problem}",
                    arm.label, arm.ref, store.arms(jobId).firstOrNull { it.arm.label == arm.label }?.sha)
            } ?: SourceProvenance(jobId, index)
            val worker = CompilationReports.worker(analyzeJob)
            CompilationReports.profileDirs(dir, index)
                .filter { if (analyzeJob == null) !Files.exists(it.resolve(CompilationReports.REPORT)) else CompilationReports.hasCaptures(it) }
                .map { profile ->
                    val report = CompilationReports.analyzeAndWrite(profile, source, worker)
                    val gaps = (report.missing + report.incomplete).joinToString { it.what }
                    logJob(config, jobId, "case $index: compilation report ${dir.relativize(profile)}: ${report.totals.compilations} compilations, " +
                        "${"%.1f".format(report.totals.compilerMs)} ms compiler time" + if (gaps.isEmpty()) "" else "; missing or incomplete: $gaps")
                    profile to report
                }
        }.getOrElse { e ->
            logJob(config, jobId, "case $index: compilation report not written: ${e.message}")
            emptyList()
        }

    /**
     * Run analysis job [job]: the lab's analyser over the retained captures of the ended job it names, writing each
     * case's report afresh beside them, and a brief of them all in its own directory ([CompilationReports.ANALYSIS]).
     * Nothing is built and no consumer code runs. It fails, saying why, when the captures were trimmed or are missing.
     */
    fun analyze(job: Job) {
        val spec = checkNotNull(job.experiment?.analyze)
        val dir = config.createJobDir(job.id)
        if (spec.kind == AnalyzeSpec.CHECK) return recheck(job, checkNotNull(spec.job), dir)
        if (spec.archive != null) return analyzeArchive(job, spec, dir)
        val analysed = checkNotNull(spec.job)
        val target = config.jobDir(analysed)
        CompilationReports.refusal(store.job(analysed), target)?.let { refusal ->
            logJob(config, job.id, "job failed: $refusal")
            store.finish(job.id, Status.FAILED, refusal)
            return
        }
        logJob(config, job.id, "analysing job $analysed's retained captures with the lab's ${spec.kind} analyser")
        logJob(config, analysed, "analysis job ${job.id} writes its compilation reports again from the retained captures")
        val indices = CompilationReports.profileDirs(target).mapNotNull { it.parent.name.toIntOrNull() }.distinct()
        val written = ArrayList<AnalysisEntry>()
        for (index in indices) {
            if (store.cancelRequested(job.id)) return cancel(job.id)
            for ((profile, report) in reportCompilation(analysed, index, target, analyzeJob = job.id)) {
                written += analysisEntry(index, target, profile, report)
            }
        }
        finishAnalysis(job, dir, AnalysisResult(analysed, spec.kind, written), "job $analysed's captures could not be analysed: see its job.log",
            "for job $analysed")
    }

    /**
     * Run recheck job [job]: the lab's source checkers over ended job [target]'s stored records, each verdict replacing
     * the case's earlier one, and a summary in [CaseChecks.RESULT]. Nothing is built and no solver runs.
     */
    private fun recheck(job: Job, target: Long, dir: Path) {
        CaseChecks.refusal(store.job(target))?.let { refusal ->
            logJob(config, job.id, "job failed: $refusal")
            store.finish(job.id, Status.FAILED, refusal)
            return
        }
        logJob(config, job.id, "checking job $target's records again with the lab's source checkers")
        logJob(config, target, "recheck job ${job.id} checks this job's solution claims again with the lab's source checkers")
        val result = checks.recheck(target) { store.cancelRequested(job.id) }
        if (store.cancelRequested(job.id)) return cancel(job.id)
        dir.resolve(CaseChecks.RESULT).writeAtomically(Json { encodeDefaults = true }.encodeToString(RecheckResult.serializer(), result))
        val states = result.states.entries.joinToString(", ") { "${it.value} ${it.key}" }.ifEmpty { "none" }
        logJob(config, job.id, "job finished: ${result.checked} of job $target's ${result.cases} cases in checked formats have a lab verdict: $states")
        store.finish(job.id, Status.DONE)
    }

    private fun analysisEntry(index: Int, root: Path, profile: Path, report: CompilationReport) = AnalysisEntry(
        index, root.relativize(profile.resolve(CompilationReports.REPORT)).joinToString("/"), report.totals.compilations,
        report.totals.compilerMs, report.complete, (report.missing + report.incomplete).map { it.what },
        report.tasks?.let { root.relativize(profile.resolve(it.file)).joinToString("/") },
    )

    private fun finishAnalysis(job: Job, dir: Path, result: AnalysisResult, empty: String, of: String) {
        dir.resolve(CompilationReports.ANALYSIS).writeAtomically(Json { encodeDefaults = true }.encodeToString(AnalysisResult.serializer(), result))
        if (result.reports.isEmpty()) {
            logJob(config, job.id, "job failed: $empty")
            store.finish(job.id, Status.FAILED, empty)
            return
        }
        store.finish(job.id, Status.DONE)
        logJob(config, job.id, "job finished: ${result.reports.size} reports written $of")
    }

    /**
     * Run analysis job [job] over the pinned archive [spec] names ([CaptureArchives]), a file under the lab's archive
     * directory: check it against its SHA-256, verify every member against its manifest, extract the profile
     * directories' captures under [CaptureArchives.ROOT], and write each one's report and task records there, with the
     * archive in their provenance. No original job is needed, nothing is built and no consumer code runs; a refused archive fails the job,
     * saying why.
     */
    private fun analyzeArchive(job: Job, spec: AnalyzeSpec, dir: Path) {
        logJob(config, job.id, "analysing the captures in archive ${spec.archive} (sha256 ${spec.sha256}) with the lab's ${spec.kind} analyser")
        val fetched = try {
            CaptureArchives.fetch(spec, config, dir)
        } catch (e: Exception) {
            if (e !is IOException && e !is EvidenceException && e !is IllegalArgumentException) throw e
            val error = "archive refused: ${e.message}"
            logJob(config, job.id, "job failed: $error")
            store.finish(job.id, Status.FAILED, error)
            return
        }
        val archive = fetched.provenance
        logJob(config, job.id, "archive ${archive.name}: ${archive.bytes} bytes, sha256 ${archive.sha256} as pinned; ${archive.verifiedFiles} files verified " +
            "against manifest ${archive.manifest} (sha256 ${archive.manifestSha256}); ${fetched.profiles.size} profile directories extracted")
        val root = dir.resolve(CaptureArchives.ROOT)
        val worker = CompilationReports.worker(job.id)
        val written = ArrayList<AnalysisEntry>()
        for (profile in fetched.profiles) {
            if (store.cancelRequested(job.id)) return cancel(job.id)
            val member = root.relativize(profile).joinToString("/")
            val index = profile.parent.name.toInt()
            runCatching {
                val report = CompilationReports.analyzeAndWrite(profile, CaptureArchives.source(profile), worker, archive.copy(member = member))
                val gaps = (report.missing + report.incomplete).joinToString { it.what }
                logJob(config, job.id, "$member: ${report.totals.compilations} compilations, ${"%.1f".format(report.totals.compilerMs)} ms compiler time" +
                    if (gaps.isEmpty()) "" else "; missing or incomplete: $gaps")
                written += analysisEntry(index, dir, profile, report)
            }.onFailure { logJob(config, job.id, "$member: compilation report not written: ${it.message}") }
        }
        finishAnalysis(job, dir, AnalysisResult(kind = spec.kind, reports = written, archive = archive),
            "the archive holds no profile directory whose captures could be analysed", "from archive ${archive.name}")
    }

    fun cancel(jobId: Long) {
        store.cancelRemaining(jobId)
        store.finish(jobId, Status.CANCELLED)
        logJob(config, jobId, "analysis cancelled")
    }
}

/** Append [message] to job [jobId]'s log, and print it. A job directory that cannot be written to only loses the line. */
internal fun logJob(config: Config, jobId: Long, message: String) {
    println("[job $jobId] $message")
    runCatching { config.createJobDir(jobId).resolve("job.log").toFile().appendText("${now()} $message\n") }
}
