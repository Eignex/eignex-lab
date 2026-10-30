package com.eignex.lab.analysis

import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import java.io.OutputStream
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedMethod
import jdk.jfr.consumer.RecordingFile
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a profiled case's CLI JVM spent on JIT compilation, as the lab worked it out from the case's retained captures
 * ([CompilationReports.analyze]): the JFR recording and, with `measurement: "compilation+log"`, HotSpot's compilation
 * log. Every compilation is one compile id. [missing] names an observation the captures do not hold at all, and
 * [incomplete] one they hold only in part; the report is [complete] when both are empty. Durations are in ms.
 *
 * Schema 2 adds each root's tiers ([MethodCompilations.byTier]) and a record per compile id in a file of its own
 * ([tasks]), so the report stays a summary however many tasks the JVM ran. A schema 1 report reads with the fields
 * schema 2 added left at their defaults.
 */
@Serializable
data class CompilationReport(
    val schemaVersion: Int = CompilationReports.SCHEMA,
    val kind: String = AnalyzeSpec.COMPILATION,
    /** What the case captured: `jfr`, or `jfr+log` with HotSpot's compilation log too. */
    val mode: String,
    val complete: Boolean,
    val missing: List<Marker>,
    val incomplete: List<Marker>,
    /** The compilations: every compile id a compiler took up, not those only queued ([CompileTotals.queuedOnly]). */
    val totals: CompileTotals,
    /** By compiler and tier: tiers 1 to 3 are C1's, 4 is C2's. */
    val byTier: List<Tier>,
    /** `normal` and `osr` (on-stack replacement) compilations. */
    val byKind: List<Bucket>,
    /**
     * `succeeded`; `bailout`, a compilation the compiler gave up on and said why; `failed`, one that did not succeed
     * and gave no reason; and `unknown`, one whose end neither capture saw.
     */
    val byStatus: List<Bucket>,
    /** Why compilations failed or bailed out, with how often: JFR's failure messages, else the log's. */
    val failures: List<Reason>,
    /** The JVM's own running totals, from its last `jdk.CompilerStatistics` event; null when it sent none. */
    val jvmStatistics: JvmCompilerStatistics? = null,
    /** What the HotSpot log held, null without one. */
    val log: LogSummary? = null,
    val coverage: Coverage,
    /** Per compiled root method, the most compiler time first. */
    val methods: List<MethodCompilations>,
    val provenance: Provenance,
    /** The record of every compile id, [CompilationReports.TASKS] beside the report; null when none was written. */
    val tasks: TasksFile? = null,
)

/** One missing or partial observation: [what] it is, and [why] it is missing or partial. */
@Serializable
data class Marker(val what: String, val why: String)

@Serializable
data class CompileTotals(
    val compilations: Int,
    val compilerMs: Double,
    val maxMs: Double,
    val succeeded: Int,
    /** Those that did not succeed, bailouts included. */
    val failed: Int,
    val osr: Int,
    /** Machine code the compilations produced, in bytes, where they said. */
    val codeBytes: Long,
    /** Bytecode inlined into them, in bytes, where they said. */
    val inlinedBytes: Long,
    /** Of [failed], those the compiler gave up on and said why. */
    val bailouts: Int = 0,
    /** Compilations whose end neither capture saw. */
    val unknown: Int = 0,
    /** Compile ids HotSpot queued that no compiler took up before the log ended: no compilation, and not in the others. */
    val queuedOnly: Int = 0,
)

@Serializable
data class Tier(val compiler: String?, val level: Int?, val compilations: Int, val compilerMs: Double, val maxMs: Double)

@Serializable
data class Bucket(val key: String, val compilations: Int, val compilerMs: Double)

@Serializable
data class Reason(val reason: String, val count: Int)

@Serializable
data class Callee(val method: String, val count: Int)

/**
 * A compiled root's compilations: [method] as `holder.name(descriptor)`. [inlined] counts each callee HotSpot's log
 * says was inlined into it, at any depth and over all its compilations, and [inlineFailures] the reasons calls were
 * not; both are null without a log. [byTier] keeps its tiers and kinds apart, and each compilation's own inlining is
 * in its task record ([CompileTask]).
 */
@Serializable
data class MethodCompilations(
    val method: String,
    val compilations: Int,
    val compilerMs: Double,
    val maxMs: Double,
    val tiers: List<Int>,
    val osr: Int,
    val failed: Int,
    val codeBytes: Long,
    val inlinedBytes: Long,
    val inlined: List<Callee>? = null,
    val inlineFailures: List<Reason>? = null,
    /** Its compile ids HotSpot queued that no compiler took up. */
    val queued: Int = 0,
    val byTier: List<RootTier> = emptyList(),
)

/**
 * A root's compilations by one compiler at one tier, of one [kind] (`normal` or `osr`): how many, their time, how
 * they ended, and their compile ids, which name their task records.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class RootTier(
    val compiler: String?,
    val level: Int?,
    val kind: String,
    val compilations: Int,
    val compilerMs: Double,
    val maxMs: Double,
    val succeeded: Int,
    // A root has thousands of tiers in a run; the counts that are mostly zero are left out when they are.
    @EncodeDefault(EncodeDefault.Mode.NEVER) val failed: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val bailouts: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val unknown: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val queued: Int = 0,
    val codeBytes: Long,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val inlinedBytes: Long = 0,
    val compileIds: List<Int>,
)

/**
 * The task records' file: [file] beside the report, gzipped JSON lines of [CompileTask] in compile id order, with how
 * many, its size and its SHA-256, so a reader can tell it is the file the report was written with.
 */
@Serializable
data class TasksFile(
    val file: String,
    val format: String = "jsonl.gz",
    val schemaVersion: Int = CompilationReports.SCHEMA,
    val records: Int,
    val bytes: Long,
    val sha256: String,
)

/**
 * One compile id, never merged with another: a retry of the same method is a record of its own, with [attempt] and
 * [retryOf] tying it to the earlier ones. [status] is `succeeded`, `bailout`, `failed`, `unknown` (begun, its end in
 * neither capture) or `queued` (queued and never taken up). [join] says which captures hold it: `matched` (JFR and a
 * log task), `jfr-only`, `log-only`, `jfr` (the case had no log) or `log` (no recording); [observed] lists each
 * observation: `jfr`, `log-queued`, `log-task`, `log-done`, `log-nmethod` and `log-not-entrant`. Times from the log
 * are seconds since the JVM started; [jfrStartMs] is epoch ms. [inlining] is the task's own inlining decisions in the
 * order the compiler made them, null when the log holds no task for it.
 */
@Serializable
data class CompileTask(
    val compileId: Int,
    val method: String,
    val compiler: String? = null,
    val level: Int? = null,
    val kind: String,
    val osrBci: Int? = null,
    val status: String,
    val failure: String? = null,
    val join: String,
    val observed: List<String>,
    /** The how-manyth compilation of [method] at this compiler, tier and kind this is, from 1. */
    val attempt: Int,
    /** The compile id of [attempt] - 1, the one this compiles again. */
    val retryOf: Int? = null,
    /** The compile id of [method]'s compilation before this one, at any tier. */
    val previous: Int? = null,
    val queuedAt: Double? = null,
    val startedAt: Double? = null,
    val endedAt: Double? = null,
    val jfrStartMs: Long? = null,
    /** JFR's duration where it has one, else the log's. */
    val ms: Double? = null,
    val jfrMs: Double? = null,
    val logMs: Double? = null,
    val bytecodeBytes: Long? = null,
    val codeBytes: Long? = null,
    val inlinedBytes: Long? = null,
    /** How many `jdk.Compilation` events hold this compile id; [jfrMs] is their sum. */
    val jfrEvents: Int = 0,
    /** Why HotSpot made this compilation's code not entrant, as the log says: a deoptimisation, or code no longer used. */
    val notEntrant: String? = null,
    /** The fields JFR and the log disagree on for this compile id: `method`, `level`, `osr` or `success`. */
    val disagreements: List<String>? = null,
    val inlining: List<InlineDecision>? = null,
)

/**
 * One call site's inlining decision in a task: [depth] 1 for a call in the root, 2 for one in a callee inlined into it,
 * and so on; [caller] the method the call is in, at bytecode [bci]; [inlined] whether it was, and HotSpot's [reason].
 * [bytes] is the callee's bytecode size. A [late] decision is C2's inlining of a call it had first left out.
 */
@Serializable
data class InlineDecision(
    val depth: Int,
    val caller: String,
    val callee: String,
    val bci: Int? = null,
    val inlined: Boolean,
    val reason: String,
    val bytes: Long? = null,
    val late: Boolean? = null,
)

@Serializable
data class JvmCompilerStatistics(
    val compileCount: Int,
    val bailoutCount: Int?,
    val invalidatedCount: Int?,
    val osrCompileCount: Int?,
    val standardCompileCount: Int?,
    val totalTimeMs: Double?,
    val peakTimeMs: Double?,
)

@Serializable
data class LogSummary(
    val tasks: Int,
    /** Tasks the log saw end, with a `task_done`. */
    val finished: Int,
    val inlineSuccess: Int,
    val inlineFail: Int,
    val inlineFailures: List<Reason>,
    /** Compiler threads whose own logs HotSpot merged in at exit: none when the JVM did not exit cleanly. */
    val compilerThreads: Int,
    /** Whether the log parsed to its end (`hotspot_log_done`). */
    val complete: Boolean,
)

/**
 * How much of the run the captures saw, and where each compilation's figures came from. The captures are joined by
 * compile id alone, and only where the ids are equal.
 */
@Serializable
data class Coverage(
    val jfrCompilations: Int,
    val logTasks: Int? = null,
    /** Compile ids in JFR and with a log task, in JFR without one, and with a log task but not in JFR. */
    val matched: Int? = null,
    val jfrOnly: Int? = null,
    val logOnly: Int? = null,
    val firstCompileId: Int? = null,
    val lastCompileId: Int? = null,
    /** Ids between the first and the last that neither capture holds at all: tasks lost, or ids HotSpot used otherwise. */
    val idsWithoutRecord: Int = 0,
    /** Compilations with no duration in either capture. */
    val withoutDuration: Int = 0,
    /** The JVM's own count of compilations, at its last statistics event. */
    val jvmCompileCount: Int? = null,
    /** `jdk.Compilation`'s threshold as the recording held it, `0 ms` for every compilation. */
    val compilationThreshold: String? = null,
    val jfrEvents: Long = 0,
    val jfrReadComplete: Boolean = false,
    val dataLossBytes: Long = 0,
    val logBytes: Long? = null,
    /** The log's queued tasks (`task_queued`) and installed code (`nmethod`, native wrappers left out). */
    val logQueued: Int? = null,
    val logNMethods: Int? = null,
    /** Compile ids of native and intrinsic wrappers (`nmethod` of a `compile_kind` such as `c2n`): ids, but no compilation. */
    val nativeWrappers: Int? = null,
    /** Compile ids only queued, never taken up. */
    val queuedOnly: Int = 0,
    /** Compile ids in JFR that the log holds nothing at all of, not even a queued task. */
    val jfrWithoutLogRecord: Int? = null,
    /** Compile ids both captures hold that they disagree on ([CompileTask.disagreements]). */
    val disagreements: Int = 0,
)

@Serializable
data class Provenance(
    val jvm: JvmProvenance? = null,
    val source: SourceProvenance? = null,
    val worker: WorkerProvenance,
    val execution: ExecutionProvenance? = null,
    /** The captures the report was made from, with their sizes and SHA-256. */
    val captures: List<Capture> = emptyList(),
    /** The pinned archive the captures came from, when an analysis job read them from one instead of a lab job. */
    val archive: ArchiveProvenance? = null,
)

@Serializable
data class JvmProvenance(
    val name: String? = null,
    val version: String? = null,
    val vendor: String? = null,
    val javaVersion: String? = null,
    val jvmArguments: String? = null,
    val jvmFlags: String? = null,
    val javaArguments: String? = null,
    val pid: Long? = null,
)

/**
 * The case the captures belong to: its job, index, problem, and its arm's label, ref and commit. [job] is null when
 * the captures came from an archive that does not say; [from] names where the rest was read when it was not the lab's
 * own database, such as the archived report beside the captures.
 */
@Serializable
data class SourceProvenance(
    val job: Long? = null,
    val case: Int,
    val problem: String? = null,
    val arm: String? = null,
    val ref: String? = null,
    val sha: String? = null,
    val from: String? = null,
)

/** Who made the report: the lab release it ran on, its host, when, and the analysis job that asked, if one did. */
@Serializable
data class WorkerProvenance(
    val analyzer: String = "eignex-lab compilation analyser, report schema ${CompilationReports.SCHEMA}",
    val labRelease: String? = null,
    val labSha: String? = null,
    val host: String? = null,
    val analyzedAt: Long = 0,
    val analyzeJob: Long? = null,
)

/** Where and how the case ran, from its `measurement.json` and `resources.txt`. */
@Serializable
data class ExecutionProvenance(
    val instance: String? = null,
    val measurementId: String? = null,
    val attempt: Int? = null,
    val commandExit: Int? = null,
    val cliExit: Int? = null,
    val javaOptions: String? = null,
    val instrumentation: String? = null,
)

@Serializable
data class Capture(val name: String, val bytes: Long, val sha256: String)

/**
 * What an analysis job wrote ([CompilationReports.ANALYSIS]): the reports it wrote afresh, for [job]'s cases, or for
 * the cases in [archive]. Reports of a job are in that job's files, and reports of an archive in the analysis job's own.
 */
@Serializable
data class AnalysisResult(
    val job: Long? = null,
    val kind: String,
    val reports: List<AnalysisEntry>,
    val archive: ArchiveProvenance? = null,
)

@Serializable
data class AnalysisEntry(
    val case: Int,
    /** The report's path within the files of [AnalysisResult.job], or of the analysis job for an archive. */
    val report: String,
    val compilations: Int,
    val compilerMs: Double,
    val complete: Boolean,
    val markers: List<String>,
    /** The task records' path, beside [report]; null in a schema 1 analysis. */
    val tasks: String? = null,
)

/**
 * The lab's compilation analyser: it reads a profiled case's captures with the JDK's own JFR consumer and an XML
 * stream reader, runs no solver code, and writes [REPORT] and [TASKS] beside them. The runner runs it after each case
 * of a compilation-recorded job, and an analysis job ([AnalyzeSpec]) reruns it over an ended job's retained captures,
 * or over a pinned archive's ([CaptureArchives]).
 */
object CompilationReports {
    const val REPORT = "compilation-report.json"
    /**
     * Every compile id's [CompileTask], gzipped JSON lines. A run compiles thousands of tasks, each with its own
     * inlining, which as JSON runs to tens of MB: apart from the report, the report stays a summary a page or `jq` reads
     * at once, the records compress tenfold, and `zcat | jq -c` streams them a line at a time.
     */
    const val TASKS = "compilation-tasks.jsonl.gz"
    const val SCHEMA = 2
    /** What an analysis job writes in its own directory: each case's fresh report in brief. */
    const val ANALYSIS = "analysis.json"

    private val json = Json { encodeDefaults = true; prettyPrint = false }
    private val lines = Json { encodeDefaults = true; explicitNulls = false }
    private val lenient = Json { ignoreUnknownKeys = true }

    /** The profile directories (`cases/<i>/profile-<uuid>`) under job directory [jobDir], of case [index] alone when given. */
    fun profileDirs(jobDir: Path, index: Int? = null): List<Path> {
        val cases = jobDir.resolve("cases")
        if (!cases.isDirectory(LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val caseDirs = if (index != null) listOf(cases.resolve(index.toString())) else Files.list(cases).use { it.toList() }
        return caseDirs.filter { it.isDirectory(LinkOption.NOFOLLOW_LINKS) }.flatMap { case ->
            Files.list(case).use { entries -> entries.filter { it.name.startsWith("profile-") && it.isDirectory(LinkOption.NOFOLLOW_LINKS) }.toList() }
        }.sortedWith(compareBy({ it.parent.name.toIntOrNull() ?: Int.MAX_VALUE }, { it.name }))
    }

    /** Whether [dir], a profile directory, still holds a capture to analyse. */
    fun hasCaptures(dir: Path): Boolean =
        listOf(CliMeasurements.JFR, CliMeasurements.COMPILATION_LOG, CliMeasurements.COMPILATION_LOG_GZ)
            .any { Files.isRegularFile(dir.resolve(it), LinkOption.NOFOLLOW_LINKS) }

    /** [dir]'s HotSpot compilation log: as the CLI wrote it locally, gzipped as it came back from a node. */
    private fun logOf(dir: Path): Path? = listOf(CliMeasurements.COMPILATION_LOG, CliMeasurements.COMPILATION_LOG_GZ)
        .map(dir::resolve).firstOrNull { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }

    /**
     * Why an analysis of job [job], whose directory is [jobDir], cannot run, or null when it can: it must be an ended
     * JFR-recorded experiment whose captures are still there, neither trimmed ([JobTrim]) nor missing.
     */
    fun refusal(job: Job?, jobDir: Path): String? {
        if (job == null) return "no such job"
        val spec = job.experiment
        if (spec.isAnalysis) return "job ${job.id} is an analysis job itself; name the job it analysed (${spec.work.analysis?.job})"
        if (!spec.recordsCliJfr) return "job ${job.id} ran without JFR measurement: it recorded no captures to analyse"
        if (job.status.isActive) return "job ${job.id} is ${job.status.name.lowercase()}: analyse it once it has ended"
        val trimmed = jobDir.resolve(JobFileNames.TRIMMED)
        if (Files.exists(trimmed)) {
            return "job ${job.id} was trimmed (${Files.readString(trimmed).trim()}): its raw captures (cli.jfr, hotspot-compilation.log) " +
                "are gone, and only the reports written when its cases ran are kept; rerun the experiment for new captures, " +
                "or analyse a pinned archive of them (analyze.archive)"
        }
        val dirs = profileDirs(jobDir)
        if (dirs.none(::hasCaptures)) return "job ${job.id} has no retained captures under cases/<i>/profile-<uuid>/ to analyse"
        return null
    }

    /** The report of the captures in profile directory [dir], written as [REPORT] there with its [TASKS]; returns it. */
    fun analyzeAndWrite(
        dir: Path,
        source: SourceProvenance?,
        worker: WorkerProvenance = worker(),
        archive: ArchiveProvenance? = null,
    ): CompilationReport {
        val (report, tasks) = analyzeTasks(dir, source, worker, archive)
        val file = writeTasks(dir, tasks)
        return report.copy(tasks = file).also { write(dir, it) }
    }

    fun write(dir: Path, report: CompilationReport): Path {
        val target = dir.resolve(REPORT)
        val temporary = dir.resolve("$REPORT.part")
        Files.writeString(temporary, json.encodeToString(CompilationReport.serializer(), report))
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return target
    }

    /** Write [tasks] as [TASKS] in [dir], one JSON line each; what the report says of the file. */
    fun writeTasks(dir: Path, tasks: List<CompileTask>): TasksFile {
        val target = dir.resolve(TASKS)
        val temporary = dir.resolve("$TASKS.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val counted = CountingStream(DigestOutputStream(Files.newOutputStream(temporary), digest))
        GZIPOutputStream(counted, 1 shl 16).bufferedWriter(Charsets.UTF_8).use { out ->
            for (task in tasks) {
                out.write(lines.encodeToString(CompileTask.serializer(), task))
                out.write("\n")
            }
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return TasksFile(TASKS, records = tasks.size, bytes = counted.count, sha256 = hex(digest.digest()))
    }

    /** The task records of [file], a [TASKS] file, in order. */
    fun readTasks(file: Path): List<CompileTask> = Files.newInputStream(file).use { raw ->
        java.util.zip.GZIPInputStream(raw, 1 shl 16).bufferedReader(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }
            .map { lenient.decodeFromString(CompileTask.serializer(), it) }.toList()
    }

    /** A report as either schema wrote it, null when [path] holds none. */
    fun read(path: Path): CompilationReport? =
        runCatching { lenient.decodeFromString(CompilationReport.serializer(), Files.readString(path)) }.getOrNull()

    /** This lab's identity as a report's worker: its release (the `releases/<id>` its jars run from), host and the time. */
    fun worker(analyzeJob: Long? = null): WorkerProvenance {
        val release = runCatching {
            val jar = Path.of(CompilationReports::class.java.protectionDomain.codeSource.location.toURI())
            jar.parent?.parent?.takeIf { it.parent?.name == "releases" }?.name
        }.getOrNull()
        val host = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
        return WorkerProvenance(labRelease = release, labSha = release?.substringBefore('-'), host = host,
            analyzedAt = System.currentTimeMillis(), analyzeJob = analyzeJob)
    }

    /** The report of the captures in profile directory [dir]. Nothing it cannot read fails it: that is a marker. */
    fun analyze(dir: Path, source: SourceProvenance?, worker: WorkerProvenance = worker()): CompilationReport =
        analyzeTasks(dir, source, worker).first

    /** The report of the captures in profile directory [dir] and its task records, before either is written. */
    internal fun analyzeTasks(
        dir: Path,
        source: SourceProvenance?,
        worker: WorkerProvenance = worker(),
        archive: ArchiveProvenance? = null,
    ): Pair<CompilationReport, List<CompileTask>> {
        val jfrPath = dir.resolve(CliMeasurements.JFR)
        val jfr = if (Files.isRegularFile(jfrPath)) JfrCompilations.read(jfrPath) else null
        val log = logOf(dir)?.let(HotSpotLog::read)
        return build(dir, jfr, log, logOf(dir) != null, source, worker, archive)
    }

    /**
     * What a profile directory holds beside its captures: its measurement manifest and resources, when readable, and
     * each capture file's size and digest, for provenance. [read] is the one place [build] gets it from the disk.
     */
    internal class CaptureDirectory(
        val manifest: CliMeasurementManifest?,
        val resources: Map<String, String>?,
        val captures: List<Capture>,
    ) {
        companion object {
            fun read(dir: Path) = CaptureDirectory(
                dir.resolve(CliMeasurements.MANIFEST).takeIf { Files.isRegularFile(it) }
                    ?.let { runCatching { lenient.decodeFromString(CliMeasurementManifest.serializer(), Files.readString(it)) }.getOrNull() },
                dir.resolve(CliMeasurements.RESOURCES).takeIf { Files.isRegularFile(it) }?.let { file ->
                    Files.readAllLines(file).mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
                },
                listOf(CliMeasurements.JFR, CliMeasurements.COMPILATION_LOG, CliMeasurements.COMPILATION_LOG_GZ,
                    CliMeasurements.RESOURCES, CliMeasurements.MANIFEST, "solve-record.json")
                    .map(dir::resolve).filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .map { Capture(it.name, Files.size(it), sha256(it)) },
            )
        }
    }

    /** [build] of the captures in profile directory [dir], as read from it ([CaptureDirectory.read]). */
    internal fun build(
        dir: Path,
        jfr: JfrCompilations.Capture?,
        log: HotSpotLog.Capture?,
        hasLog: Boolean,
        source: SourceProvenance?,
        worker: WorkerProvenance,
        archive: ArchiveProvenance? = null,
    ): Pair<CompilationReport, List<CompileTask>> = build(CaptureDirectory.read(dir), jfr, log, hasLog, source, worker, archive)

    /**
     * The report and task records of [jfr] and [log] and what their profile directory holds ([directory]): the
     * analysis itself, pure, so a test hands it captures it made up. Nothing missing fails it: that is a marker.
     */
    internal fun build(
        directory: CaptureDirectory,
        jfr: JfrCompilations.Capture?,
        log: HotSpotLog.Capture?,
        hasLog: Boolean,
        source: SourceProvenance?,
        worker: WorkerProvenance,
        archive: ArchiveProvenance? = null,
    ): Pair<CompilationReport, List<CompileTask>> {
        val missing = mutableListOf<Marker>()
        val incomplete = mutableListOf<Marker>()
        val manifest = directory.manifest
        val resources = directory.resources
        val mode = manifest?.compilation ?: if (hasLog) Experiments.COMPILATION_JFR_LOG else Experiments.COMPILATION_JFR
        if (manifest == null) missing += Marker("measurement", "no readable ${CliMeasurements.MANIFEST}: where and how the case ran is unknown")

        if (jfr == null) {
            missing += Marker("jfr", "no ${CliMeasurements.JFR}: the CLI left no recording")
        } else {
            jfr.error?.let { incomplete += Marker("jfr-read", "the recording stops being readable after ${jfr.events} events ($it): it was cut short") }
            if (jfr.shutdown == null) {
                incomplete += Marker("jfr-shutdown", "the recording has no JVM shutdown event: the JVM was killed or the recording ended before " +
                    "it exited, so its last compilations may be missing")
            }
            when {
                jfr.compilationEnabled == false -> missing += Marker("jfr-compilation", "jdk.Compilation was disabled in the recording")
                jfr.threshold == null -> incomplete += Marker("jfr-threshold", "the recording does not say jdk.Compilation's threshold: short compilations may be missing")
                !zeroDuration(jfr.threshold) -> incomplete += Marker("jfr-threshold", "jdk.Compilation was recorded with a threshold of " +
                    "${jfr.threshold}: shorter compilations are missing (the job ran without compilation measurement)")
            }
            if (jfr.dataLossBytes > 0) incomplete += Marker("jfr-data-loss", "JFR lost ${jfr.dataLossBytes} bytes of events (jdk.DataLoss)")
            if (jfr.jvm == null) missing += Marker("jvm", "the recording has no jdk.JVMInformation event: the JVM's version and flags are unknown")
            if (jfr.statistics == null) {
                missing += Marker("jvm-statistics", "no jdk.CompilerStatistics event, which JFR sends every second: the run was shorter, or the recording was cut")
            }
        }
        if (log == null && mode == Experiments.COMPILATION_JFR_LOG) {
            missing += Marker("hotspot-log", "no ${CliMeasurements.COMPILATION_LOG}: inlining decisions are unknown")
        }
        if (log != null) {
            log.error?.let { incomplete += Marker("hotspot-log-parse", "the HotSpot log stops parsing ($it) after ${log.tasks.size} tasks") }
            if (log.error == null && !log.done) incomplete += Marker("hotspot-log-end", "the HotSpot log has no end mark: HotSpot did not finish writing it")
            if (log.threads == 0) {
                missing += Marker("inlining", "the HotSpot log holds no compiler thread's log, which HotSpot merges in only when the JVM exits " +
                    "cleanly: inlining decisions are unknown")
            }
            val unfinished = log.tasks.values.count { it.done == null }
            if (unfinished > 0) incomplete += Marker("hotspot-log-tasks", "$unfinished of the log's ${log.tasks.size} tasks have no end")
        }
        manifest?.commandExit?.takeIf { it != 0 }?.let {
            incomplete += Marker("case-exit", "the case exited $it (a stop at its time limit, a cancel, or a failure): the captures may end before the solve did")
        }
        resources?.get("exit")?.trim()?.toIntOrNull()?.takeIf { it != 0 }?.let {
            incomplete += Marker("cli-exit", "the CLI exited $it: its JVM may not have run its shutdown, and the captures may be cut")
        }

        val all = merge(jfr, log)
        val compiled = all.filter { it.status != QUEUED }
        val withoutDuration = compiled.count { it.ms == null }
        if (withoutDuration > 0) incomplete += Marker("durations", "$withoutDuration compilations have no duration in either capture, and count as 0 ms")
        val wrappers = log?.wrappers.orEmpty()
        val ids = all.map { it.id } + wrappers
        val first = ids.minOrNull()
        val last = ids.maxOrNull()
        if (first != null && first > 1) {
            incomplete += Marker("before-recording", "compile ids 1 to ${first - 1} are in no capture: the JVM compiled them at startup, " +
                "before the recording began" + if (log == null) "; measurement \"compilation+log\" covers them" else "")
        }
        val jfrIds = jfr?.compilations?.mapTo(HashSet()) { it.id }.orEmpty()
        val logIds = log?.tasks?.keys.orEmpty()
        val logRecords = log?.let { it.tasks.keys + it.queued.keys + it.nmethods.keys }.orEmpty()
        // A join: with both captures whole, a compile id in one and not the other is an observation lost.
        if (jfr != null && log != null && log.threads > 0) {
            val withoutTask = all.count { it.join == "jfr-only" }
            if (withoutTask > 0) {
                incomplete += Marker("jfr-without-log-task", "$withoutTask compilations in the recording have no task in the HotSpot log: " +
                    "their inlining is unknown")
            }
            val firstJfr = jfrIds.minOrNull()
            val unrecorded = all.count { it.join == "log-only" && it.status in ENDED && firstJfr != null && it.id > firstJfr }
            if (unrecorded > 0) {
                incomplete += Marker("log-without-jfr", "$unrecorded compilations the HotSpot log saw end after the recording began have " +
                    "no jdk.Compilation event, such as those while JFR starts up or after its last chunk: their figures are the log's")
            }
        }
        val disagreements = all.count { it.disagreements.isNotEmpty() }
        if (disagreements > 0) {
            incomplete += Marker("jfr-log-disagree", "JFR and the HotSpot log disagree on $disagreements compile ids: their task records say on what")
        }
        val coverage = Coverage(
            jfrCompilations = jfrIds.size,
            logTasks = log?.tasks?.size,
            matched = log?.let { jfrIds.count { it in logIds } },
            jfrOnly = log?.let { jfrIds.count { it !in logIds } },
            logOnly = log?.let { logIds.count { it !in jfrIds } },
            firstCompileId = first,
            lastCompileId = last,
            idsWithoutRecord = if (first == null || last == null) 0 else (last - first + 1) - ids.distinct().size,
            withoutDuration = withoutDuration,
            jvmCompileCount = jfr?.statistics?.compileCount,
            compilationThreshold = jfr?.threshold,
            jfrEvents = jfr?.events ?: 0,
            jfrReadComplete = jfr != null && jfr.error == null,
            dataLossBytes = jfr?.dataLossBytes ?: 0,
            logBytes = log?.bytes,
            logQueued = log?.queued?.size,
            logNMethods = log?.nmethods?.size,
            nativeWrappers = log?.wrappers?.size,
            queuedOnly = all.size - compiled.size,
            jfrWithoutLogRecord = log?.let { jfrIds.count { it !in logRecords } },
            disagreements = disagreements,
        )
        val captures = directory.captures
        val execution = ExecutionProvenance(
            instance = manifest?.instance, measurementId = manifest?.measurementId, attempt = manifest?.attempt,
            commandExit = manifest?.commandExit, cliExit = resources?.get("exit")?.trim()?.toIntOrNull(),
            javaOptions = manifest?.javaOptions, instrumentation = manifest?.instrumentation,
        )
        if (source == null) missing += Marker("source", "the case's job, arm and commit are unknown")
        else if (source.job == null) missing += Marker("source", "the archive does not say which job, arm and commit case ${source.case} ran")
        val report = CompilationReport(
            mode = mode,
            complete = missing.isEmpty() && incomplete.isEmpty(),
            missing = missing,
            incomplete = incomplete,
            totals = CompileTotals(
                compilations = compiled.size,
                compilerMs = round(compiled.sumOf { it.ms ?: 0.0 }),
                maxMs = round(compiled.maxOfOrNull { it.ms ?: 0.0 } ?: 0.0),
                succeeded = compiled.count { it.status == SUCCEEDED },
                failed = compiled.count { it.status == FAILED || it.status == BAILOUT },
                osr = compiled.count { it.osr },
                codeBytes = compiled.sumOf { it.codeBytes ?: 0 },
                inlinedBytes = compiled.sumOf { it.inlinedBytes ?: 0 },
                bailouts = compiled.count { it.status == BAILOUT },
                unknown = compiled.count { it.status == UNKNOWN },
                queuedOnly = all.size - compiled.size,
            ),
            byTier = compiled.groupBy { it.compiler to it.level }.map { (key, group) ->
                Tier(key.first, key.second, group.size, round(group.sumOf { it.ms ?: 0.0 }), round(group.maxOf { it.ms ?: 0.0 }))
            }.sortedWith(compareBy({ it.level ?: Int.MAX_VALUE }, { it.compiler.orEmpty() })),
            byKind = buckets(compiled) { it.kind },
            byStatus = buckets(compiled) { it.status },
            failures = reasons(compiled.mapNotNull { it.failure }),
            jvmStatistics = jfr?.statistics,
            log = log?.let { l ->
                LogSummary(l.tasks.size, l.tasks.values.count { it.done != null }, l.inlineSuccess, l.inlineFail,
                    reasons(l.inlineFailures), l.threads, l.error == null && l.done)
            },
            coverage = coverage,
            methods = methods(all, log != null),
            provenance = Provenance(jfr?.jvm, source, worker, execution.takeIf { manifest != null || resources != null }, captures, archive),
        )
        return report to records(all, log != null && log.threads > 0)
    }

    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val BAILOUT = "bailout"
    const val UNKNOWN = "unknown"
    const val QUEUED = "queued"
    private val ENDED = setOf(SUCCEEDED, FAILED, BAILOUT)

    /** One compile id's figures, JFR's where it has them and the log's otherwise. */
    internal class Compiled(
        val id: Int,
        val method: String,
        val compiler: String?,
        val level: Int?,
        val osr: Boolean,
        val osrBci: Int?,
        val succeeded: Boolean?,
        val status: String,
        val join: String,
        val observed: List<String>,
        val ms: Double?,
        val jfrMs: Double?,
        val jfrEvents: Int,
        val jfrStartMs: Long?,
        val codeBytes: Long?,
        val inlinedBytes: Long?,
        val failure: String?,
        val disagreements: List<String>,
        val notEntrant: String?,
        val queued: HotSpotLog.Queued?,
        val task: HotSpotLog.Task?,
    ) {
        val kind: String get() = if (osr) "osr" else "normal"
    }

    internal fun merge(jfr: JfrCompilations.Capture?, log: HotSpotLog.Capture?): List<Compiled> {
        val events = jfr?.compilations.orEmpty().groupBy { it.id }
        val tasks = log?.tasks.orEmpty()
        val queued = log?.queued.orEmpty()
        val nmethods = log?.nmethods.orEmpty()
        return (events.keys + tasks.keys + queued.keys + nmethods.keys).sorted().map { id ->
            // A compile id is one task; JFR may hold more than one event for it, and their durations add up.
            val attempts = events[id].orEmpty()
            val event = attempts.lastOrNull()
            val task = tasks[id]
            val queue = queued[id]
            val nmethod = nmethods[id]
            val level = event?.level ?: task?.level ?: nmethod?.level ?: queue?.level
            val compiler = event?.compiler?.takeIf { it.isNotBlank() } ?: nmethod?.compiler?.takeIf { it.isNotBlank() } ?: when (level) {
                in 1..3 -> "c1"
                4 -> "c2"
                else -> null
            }
            // Installed code is a compilation that succeeded, though the log holds no task end for it.
            val succeeded = event?.succeeded ?: task?.success ?: if (nmethod != null) true else null
            val failure = jfr?.failures?.get(id) ?: task?.failure
            val status = when {
                event == null && task == null && nmethod == null -> QUEUED
                succeeded == true -> SUCCEEDED
                succeeded == false -> if (failure.isNullOrBlank()) FAILED else BAILOUT
                else -> UNKNOWN
            }
            val disagreements = buildList {
                if (event != null && task != null) {
                    if (event.method != task.method) add("method")
                    if (event.level != null && task.level != null && event.level != task.level) add("level")
                    if (event.osr != task.osr) add("osr")
                    if (task.success != null && event.succeeded != task.success) add("success")
                }
            }
            val jfrMs = if (attempts.isNotEmpty()) attempts.sumOf { it.ms } else null
            Compiled(
                id = id,
                method = event?.method ?: task?.method ?: queue?.method ?: nmethod?.method ?: "unknown",
                compiler = compiler,
                level = level ?: if (compiler == "c2") 4 else null,
                osr = event?.osr ?: task?.osr ?: nmethod?.osr ?: queue?.osr ?: false,
                osrBci = task?.osrBci ?: queue?.osrBci,
                succeeded = succeeded,
                status = status,
                join = when {
                    jfr == null -> "log"
                    log == null -> "jfr"
                    event != null && task != null -> "matched"
                    event != null -> "jfr-only"
                    else -> "log-only"
                },
                observed = buildList {
                    if (event != null) add("jfr")
                    if (queue != null) add("log-queued")
                    if (task != null) add("log-task")
                    if (task?.done != null) add("log-done")
                    if (nmethod != null) add("log-nmethod")
                    if (log?.notEntrant?.containsKey(id) == true) add("log-not-entrant")
                },
                ms = jfrMs ?: task?.durationMs,
                jfrMs = jfrMs,
                jfrEvents = attempts.size,
                jfrStartMs = attempts.firstOrNull()?.startMs,
                codeBytes = event?.codeBytes ?: task?.nmsize,
                inlinedBytes = event?.inlinedBytes ?: task?.inlinedBytes,
                failure = failure,
                disagreements = disagreements,
                notEntrant = log?.notEntrant?.get(id),
                queued = queue,
                task = task,
            )
        }
    }

    /** Each compile id's record, with its place among its method's compilations; [logged] when tasks' inlining is known. */
    internal fun records(all: List<Compiled>, logged: Boolean): List<CompileTask> {
        val attempts = HashMap<List<Any?>, Pair<Int, Int>>()
        val previous = HashMap<String, Int>()
        return all.map { c ->
            val key = listOf(c.method, c.compiler, c.level, c.kind)
            val before = attempts[key]
            attempts[key] = (before?.first ?: 0) + 1 to c.id
            val earlier = previous.put(c.method, c.id)
            CompileTask(
                compileId = c.id, method = c.method, compiler = c.compiler, level = c.level, kind = c.kind, osrBci = c.osrBci,
                status = c.status, failure = c.failure, join = c.join, observed = c.observed,
                attempt = (before?.first ?: 0) + 1, retryOf = before?.second, previous = earlier,
                queuedAt = c.queued?.stamp, startedAt = c.task?.start, endedAt = c.task?.done, jfrStartMs = c.jfrStartMs,
                ms = c.ms?.let(::round), jfrMs = c.jfrMs?.let(::round), logMs = c.task?.durationMs?.let(::round),
                bytecodeBytes = c.task?.bytes ?: c.queued?.bytes, codeBytes = c.codeBytes, inlinedBytes = c.inlinedBytes,
                jfrEvents = c.jfrEvents, notEntrant = c.notEntrant, disagreements = c.disagreements.ifEmpty { null },
                inlining = if (logged) c.task?.inlining?.toList() else null,
            )
        }
    }

    private fun methods(all: List<Compiled>, logged: Boolean): List<MethodCompilations> =
        all.groupBy { it.method }.map { (method, records) ->
            val group = records.filter { it.status != QUEUED }
            val tasks = group.mapNotNull { it.task }
            MethodCompilations(
                method = method,
                compilations = group.size,
                compilerMs = round(group.sumOf { it.ms ?: 0.0 }),
                maxMs = round(group.maxOfOrNull { it.ms ?: 0.0 } ?: 0.0),
                tiers = group.mapNotNull { it.level }.distinct().sorted(),
                osr = group.count { it.osr },
                failed = group.count { it.status == FAILED || it.status == BAILOUT },
                codeBytes = group.sumOf { it.codeBytes ?: 0 },
                inlinedBytes = group.sumOf { it.inlinedBytes ?: 0 },
                inlined = if (!logged) null else tasks.flatMap { it.inlined.entries }.groupBy({ it.key }, { it.value })
                    .map { (callee, counts) -> Callee(callee, counts.sum()) }.sortedWith(compareBy({ -it.count }, { it.method })),
                inlineFailures = if (!logged) null else tasks.flatMap { it.inlineFailures.entries }.groupBy({ it.key }, { it.value })
                    .map { (reason, counts) -> Reason(reason, counts.sum()) }.sortedWith(compareBy({ -it.count }, { it.reason })),
                queued = records.size - group.size,
                byTier = records.groupBy { Triple(it.compiler, it.level, it.kind) }.map { (key, tier) ->
                    val ran = tier.filter { it.status != QUEUED }
                    RootTier(
                        compiler = key.first, level = key.second, kind = key.third,
                        compilations = ran.size,
                        compilerMs = round(ran.sumOf { it.ms ?: 0.0 }),
                        maxMs = round(ran.maxOfOrNull { it.ms ?: 0.0 } ?: 0.0),
                        succeeded = ran.count { it.status == SUCCEEDED },
                        failed = ran.count { it.status == FAILED || it.status == BAILOUT },
                        bailouts = ran.count { it.status == BAILOUT },
                        unknown = ran.count { it.status == UNKNOWN },
                        queued = tier.size - ran.size,
                        codeBytes = ran.sumOf { it.codeBytes ?: 0 },
                        inlinedBytes = ran.sumOf { it.inlinedBytes ?: 0 },
                        compileIds = tier.map { it.id },
                    )
                }.sortedWith(compareBy({ it.level ?: Int.MAX_VALUE }, { it.compiler.orEmpty() }, { it.kind })),
            )
        }.sortedWith(compareBy({ -it.compilerMs }, { it.method }))

    private fun buckets(compiled: List<Compiled>, key: (Compiled) -> String): List<Bucket> =
        compiled.groupBy(key).map { (name, group) -> Bucket(name, group.size, round(group.sumOf { it.ms ?: 0.0 })) }.sortedBy { it.key }

    private fun reasons(all: List<String>): List<Reason> = reasons(all.groupingBy { it }.eachCount())

    private fun reasons(counts: Map<String, Int>): List<Reason> =
        counts.map { (reason, count) -> Reason(reason, count) }.sortedWith(compareBy({ -it.count }, { it.reason }))

    /** Whether [setting], a JFR duration setting such as `0 ms` or `100 ms`, is zero. */
    internal fun zeroDuration(setting: String): Boolean = setting.trim().takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() == 0.0

    internal fun round(value: Double): Double = Math.round(value * 1000.0) / 1000.0

    internal fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private class CountingStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()

        override fun close() = out.close()
    }
}

/** A recording's compilations and what says how far it can be trusted, read with the JDK's own JFR consumer. */
internal object JfrCompilations {
    class Compilation(
        val id: Int,
        val method: String,
        val compiler: String?,
        val level: Int?,
        val osr: Boolean,
        val succeeded: Boolean,
        val ms: Double,
        val codeBytes: Long?,
        val inlinedBytes: Long?,
        /** When it began, in epoch ms. */
        val startMs: Long? = null,
    )

    class Capture(
        val compilations: List<Compilation>,
        /** Each failed compile id's failure message. */
        val failures: Map<Int, String>,
        val statistics: JvmCompilerStatistics?,
        val jvm: JvmProvenance?,
        /** `jdk.Compilation`'s threshold setting, as the recording's settings events held it; null when they did not. */
        val threshold: String?,
        val compilationEnabled: Boolean?,
        /** The JVM's shutdown reason, null when the recording has no shutdown event. */
        val shutdown: String?,
        val dataLossBytes: Long,
        val events: Long,
        /** Why reading stopped before the end, null when it read to the end. */
        val error: String?,
    )

    fun read(path: Path): Capture {
        val compilations = ArrayList<Compilation>()
        val failures = HashMap<Int, String>()
        val properties = HashMap<String, String>()
        var statistics: Pair<java.time.Instant, JvmCompilerStatistics>? = null
        var information: RecordedEvent? = null
        var threshold: String? = null
        var enabled: Boolean? = null
        var shutdown: String? = null
        var dataLoss = 0L
        var events = 0L
        var error: String? = null
        try {
            RecordingFile(path).use { file ->
                val compilationType = runCatching { file.readEventTypes().firstOrNull { it.name == "jdk.Compilation" }?.id }.getOrNull()
                while (file.hasMoreEvents()) {
                    val event = file.readEvent()
                    events++
                    when (event.eventType.name) {
                        "jdk.Compilation" -> compilations += compilation(event)
                        "jdk.CompilationFailure" -> failures[event.getInt("compileId")] = event.getString("failureMessage").orEmpty()
                        "jdk.CompilerStatistics" -> {
                            val at = event.startTime
                            if (statistics == null || !at.isBefore(statistics.first)) statistics = at to statistics(event)
                        }
                        "jdk.JVMInformation" -> information = event
                        "jdk.InitialSystemProperty" -> properties[event.getString("key").orEmpty()] = event.getString("value").orEmpty()
                        "jdk.ActiveSetting" -> if (compilationType != null && event.getLong("id") == compilationType) {
                            when (event.getString("name")) {
                                "threshold" -> threshold = event.getString("value")
                                "enabled" -> enabled = event.getString("value")?.toBooleanStrictOrNull()
                            }
                        }
                        "jdk.Shutdown" -> shutdown = event.getString("reason").orEmpty()
                        "jdk.DataLoss" -> dataLoss += event.getLong("amount")
                    }
                }
            }
        } catch (e: Exception) {
            error = e.message ?: e::class.simpleName
        }
        val jvm = information?.let { event ->
            JvmProvenance(
                name = field(event, "jvmName"), version = field(event, "jvmVersion"),
                vendor = properties["java.vm.vendor"] ?: properties["java.vendor"], javaVersion = properties["java.vm.version"] ?: properties["java.version"],
                jvmArguments = field(event, "jvmArguments"), jvmFlags = field(event, "jvmFlags"),
                javaArguments = field(event, "javaArguments"), pid = runCatching { event.getLong("pid") }.getOrNull(),
            )
        }
        return Capture(compilations, failures, statistics?.second, jvm, threshold, enabled, shutdown, dataLoss, events, error)
    }

    private fun compilation(event: RecordedEvent): Compilation {
        val method = event.getValue<RecordedMethod?>("method")
        return Compilation(
            id = event.getInt("compileId"),
            method = method?.let { "${it.type.name}.${it.name}${it.descriptor}" } ?: "unknown",
            compiler = field(event, "compiler"),
            level = if (event.hasField("compileLevel")) event.getInt("compileLevel") else null,
            osr = event.hasField("isOsr") && event.getBoolean("isOsr"),
            succeeded = !event.hasField("succeded") || event.getBoolean("succeded"),
            ms = event.duration.toNanos() / 1e6,
            codeBytes = if (event.hasField("codeSize")) event.getLong("codeSize") else null,
            inlinedBytes = if (event.hasField("inlinedBytes")) event.getLong("inlinedBytes") else null,
            startMs = runCatching { event.startTime.toEpochMilli() }.getOrNull(),
        )
    }

    private fun statistics(event: RecordedEvent): JvmCompilerStatistics {
        fun int(name: String) = if (event.hasField(name)) event.getInt(name) else null
        fun ms(name: String) = if (event.hasField(name)) event.getDuration(name).toNanos() / 1e6 else null
        return JvmCompilerStatistics(int("compileCount") ?: 0, int("bailoutCount"), int("invalidatedCount"), int("osrCompileCount"),
            int("standardCompileCount"), ms("totalTimeSpent"), ms("peakTimeSpent"))
    }

    private fun field(event: RecordedEvent, name: String): String? = if (event.hasField(name)) event.getString(name) else null
}

/**
 * HotSpot's compilation log (`-XX:+LogCompilation`), read as an XML stream: its `tty` section's queued tasks, installed
 * code and code made not entrant, and each compiler thread's own log, which HotSpot merges in when the JVM exits, with
 * every task's calls and whether each was inlined, at what depth. A log cut short reads up to where it stops.
 */
internal object HotSpotLog {
    /**
     * HotSpot writes a task's `level` only when it is below the highest tier, C2's: a `task` or `task_queued` without
     * one is a C2 task.
     */
    const val HIGHEST_LEVEL = 4

    class Task(
        val id: Int,
        val method: String,
        val osr: Boolean,
        var level: Int?,
        val start: Double?,
        val osrBci: Int? = null,
        /** The root's bytecode size. */
        val bytes: Long? = null,
    ) {
        var done: Double? = null
        var success: Boolean? = null
        var failure: String? = null
        var nmsize: Long? = null
        var inlinedBytes: Long? = null
        val inlined = HashMap<String, Int>()
        val inlineFailures = HashMap<String, Int>()
        /** Every inlining decision, in the order the compiler made it. */
        val inlining = ArrayList<InlineDecision>()

        val durationMs: Double? get() = done?.let { end -> start?.let { (end - it) * 1000.0 } }
    }

    /** A `task_queued`: a compile id HotSpot handed a compiler, which may never have taken it up. */
    class Queued(val id: Int, val method: String, val osr: Boolean, val osrBci: Int?, val level: Int?, val stamp: Double?, val bytes: Long?)

    /** An `nmethod`: code a compilation installed. */
    data class NMethod(val compiler: String?, val level: Int?, val osr: Boolean = false, val method: String? = null, val stamp: Double? = null)

    class Capture(
        val tasks: Map<Int, Task>,
        /** Each compile id's installed code, from the log's `nmethod` entries; native wrappers are in [wrappers]. */
        val nmethods: Map<Int, NMethod>,
        val threads: Int,
        val inlineSuccess: Int,
        val inlineFail: Int,
        val inlineFailures: Map<String, Int>,
        /** Why parsing stopped before the end, null when it read to the end. */
        val error: String?,
        /** Whether the log has HotSpot's end mark, `hotspot_log_done`. */
        val done: Boolean,
        val bytes: Long,
        val queued: Map<Int, Queued> = emptyMap(),
        /** Why each compile id's code was first made not entrant. */
        val notEntrant: Map<Int, String> = emptyMap(),
        /** Compile ids of native and intrinsic wrappers (a `compile_kind` other than `osr`, such as `c2n`), which compile nothing. */
        val wrappers: Set<Int> = emptySet(),
    )

    /** Read the log at [path], gunzipping it on the way when its name ends in `.gz`; [Capture.bytes] is the file's size. */
    fun read(path: Path): Capture = Files.newInputStream(path).use { raw ->
        val input = if (path.name.endsWith(".gz")) java.util.zip.GZIPInputStream(raw, 1 shl 16) else raw.buffered(1 shl 16)
        read(input, Files.size(path))
    }

    private class MethodEntry(val holder: String, val name: String, val returns: String?, val arguments: String?, val bytes: Long?)

    /** A method being parsed into the task, the root or an inlined callee, and the bytecode it is at. */
    private class Frame(val method: String) {
        var bci: Int? = null
    }

    fun read(input: java.io.InputStream, bytes: Long): Capture {
        val tasks = LinkedHashMap<Int, Task>()
        val queued = HashMap<Int, Queued>()
        val nmethods = HashMap<Int, NMethod>()
        val notEntrant = HashMap<Int, String>()
        val wrappers = HashSet<Int>()
        val failures = HashMap<String, Int>()
        var threads = 0
        var successes = 0
        var fails = 0
        var done = false
        var error: String? = null
        // The ids a task's klass, type and method entries define, valid within that task.
        val klasses = HashMap<String, String>()
        val types = HashMap<String, String>()
        val methods = HashMap<String, MethodEntry>()
        val names = HashMap<String, String>()
        var task: Task? = null
        var call: String? = null
        var callBci: Int? = null
        var callBytes: Long? = null
        val frames = ArrayDeque<Frame>()
        // C2's late inlining of a call it first left out: the callee, the call's chain of sites (`jvms`, innermost
        // first), and the depth the callee's parse then starts below.
        var late: String? = null
        var lateBytes: Long? = null
        val lateChain = ArrayList<Pair<String, Int?>>()
        var lateBase = 0
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        var reader: XMLStreamReader? = null
        try {
            reader = factory.createXMLStreamReader(input)
            fun name(id: String): String = names.getOrPut(id) { methods[id]?.let { describe(it, klasses, types) } ?: "method#$id" }
            while (reader.hasNext()) {
                val type = reader.next()
                if (type == XMLStreamConstants.END_ELEMENT) {
                    when (reader.localName) {
                        "task" -> task = null
                        "parse" -> if (frames.isNotEmpty()) {
                            frames.removeLast()
                            if (frames.isEmpty()) lateBase = 0
                        }
                        "late_inline" -> {
                            val callee = late
                            val site = lateChain.firstOrNull()
                            if (callee != null && site != null) {
                                task?.inlining?.add(InlineDecision(lateChain.size, site.first, callee, site.second, true, "late inline",
                                    lateBytes, late = true))
                            }
                            lateBase = lateChain.size
                            late = null
                        }
                    }
                    continue
                }
                if (type != XMLStreamConstants.START_ELEMENT) continue
                fun attr(name: String): String? = reader.getAttributeValue(null, name)
                when (reader.localName) {
                    "compilation_log" -> threads++
                    "task_queued" -> attr("compile_id")?.toIntOrNull()?.let { id ->
                        queued[id] = Queued(id, method(attr("method")), attr("compile_kind") == "osr", attr("osr_bci")?.toIntOrNull(),
                            attr("level")?.toIntOrNull() ?: HIGHEST_LEVEL, attr("stamp")?.toDoubleOrNull(), attr("bytes")?.toLongOrNull())
                    }
                    "nmethod" -> attr("compile_id")?.toIntOrNull()?.let { id ->
                        val kind = attr("compile_kind")
                        if (kind != null && kind != "osr") wrappers += id
                        else nmethods[id] = NMethod(attr("compiler"), attr("level")?.toIntOrNull(), attr("compile_kind") == "osr",
                            attr("method")?.let(::method), attr("stamp")?.toDoubleOrNull())
                    }
                    "make_not_entrant" -> attr("compile_id")?.toIntOrNull()?.let { id -> notEntrant.putIfAbsent(id, attr("reason") ?: "unknown") }
                    "task" -> {
                        val id = attr("compile_id")?.toIntOrNull() ?: continue
                        klasses.clear(); types.clear(); methods.clear(); names.clear(); frames.clear()
                        call = null; late = null; lateChain.clear(); lateBase = 0
                        task = Task(id, method(attr("method")), attr("compile_kind") == "osr", attr("level")?.toIntOrNull() ?: HIGHEST_LEVEL,
                            attr("stamp")?.toDoubleOrNull(), attr("osr_bci")?.toIntOrNull(), attr("bytes")?.toLongOrNull()).also { tasks[id] = it }
                    }
                    "klass" -> attr("id")?.let { id -> klasses[id] = attr("name").orEmpty() }
                    "type" -> attr("id")?.let { id -> types[id] = attr("name").orEmpty() }
                    "method" -> attr("id")?.let { id ->
                        methods[id] = MethodEntry(attr("holder").orEmpty(), attr("name").orEmpty(), attr("return"), attr("arguments"),
                            attr("bytes")?.toLongOrNull())
                        names.remove(id)
                    }
                    "parse" -> frames.addLast(Frame(attr("method")?.let(::name) ?: task?.method ?: "unknown"))
                    "bc" -> frames.lastOrNull()?.bci = attr("bci")?.toIntOrNull()
                    "call" -> {
                        val id = attr("method")
                        call = id?.let(::name)
                        callBci = frames.lastOrNull()?.bci
                        callBytes = id?.let { methods[it]?.bytes }
                    }
                    "late_inline" -> {
                        val id = attr("method")
                        late = id?.let(::name) ?: "unknown"
                        lateBytes = id?.let { methods[it]?.bytes }
                        lateChain.clear()
                    }
                    "jvms" -> if (late != null) lateChain += (attr("method")?.let(::name) ?: "unknown") to attr("bci")?.toIntOrNull()
                    "inline_success", "inline_fail" -> {
                        val inlined = reader.localName == "inline_success"
                        val reason = attr("reason").orEmpty()
                        if (inlined) {
                            successes++
                            task?.inlined?.merge(call ?: "unknown", 1, Int::plus)
                        } else {
                            fails++
                            failures.merge(reason, 1, Int::plus)
                            task?.inlineFailures?.merge(reason, 1, Int::plus)
                        }
                        task?.let {
                            it.inlining += InlineDecision(lateBase + frames.size, frames.lastOrNull()?.method ?: it.method, call ?: "unknown",
                                callBci, inlined, reason, callBytes)
                        }
                    }
                    "failure" -> task?.let { it.failure = it.failure ?: attr("reason") }
                    "task_done" -> task?.let {
                        it.success = attr("success")?.let { s -> s == "1" }
                        it.done = attr("stamp")?.toDoubleOrNull()
                        it.nmsize = attr("nmsize")?.toLongOrNull()
                        it.inlinedBytes = attr("inlined_bytes")?.toLongOrNull()
                    }
                    "hotspot_log_done" -> done = true
                }
            }
        } catch (e: Exception) {
            val line = reader?.location?.lineNumber?.takeIf { it > 0 }
            error = (if (line != null) "line $line: " else "") + (e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: e::class.simpleName)
        } finally {
            runCatching { reader?.close() }
        }
        return Capture(tasks, nmethods, threads, successes, fails, failures, error, done, bytes, queued, notEntrant, wrappers)
    }

    /**
     * A task's `method` attribute, `holder name descriptor`, as JFR names methods: `holder.name(descriptor)`. A hidden
     * class's holder, which the log writes `Name/0x…`, JFR writes `Name.0x…`.
     */
    internal fun method(attribute: String?): String {
        val parts = attribute.orEmpty().split(' ')
        return if (parts.size >= 3) "${holder(parts[0])}.${parts[1]}${parts[2]}" else attribute ?: "unknown"
    }

    private fun holder(name: String): String = name.replace('/', '.')

    /** A `method` entry as `holder.name(descriptor)`, its descriptor rebuilt from the task's type and klass entries. */
    private fun describe(method: MethodEntry, klasses: Map<String, String>, types: Map<String, String>): String {
        fun descriptor(id: String): String {
            types[id]?.let { name -> PRIMITIVES[name]?.let { return it } }
            val klass = klasses[id] ?: return "?"
            return if (klass.startsWith("[")) klass.replace('.', '/') else "L${klass.replace('.', '/')};"
        }
        val arguments = method.arguments.orEmpty().split(' ').filter { it.isNotEmpty() }.joinToString("") { descriptor(it) }
        val returns = method.returns?.let { descriptor(it) } ?: "?"
        return "${klasses[method.holder]?.let(::holder) ?: "?"}.${method.name}($arguments)$returns"
    }

    private val PRIMITIVES = mapOf(
        "void" to "V", "boolean" to "Z", "byte" to "B", "char" to "C", "short" to "S", "int" to "I", "long" to "J",
        "float" to "F", "double" to "D",
    )
}
