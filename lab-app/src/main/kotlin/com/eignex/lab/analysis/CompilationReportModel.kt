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
internal data class CompilationReport(
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
internal data class Marker(val what: String, val why: String)

@Serializable
internal data class CompileTotals(
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
internal data class Tier(val compiler: String?, val level: Int?, val compilations: Int, val compilerMs: Double, val maxMs: Double)

@Serializable
internal data class Bucket(val key: String, val compilations: Int, val compilerMs: Double)

@Serializable
internal data class Reason(val reason: String, val count: Int)

@Serializable
internal data class Callee(val method: String, val count: Int)

/**
 * A compiled root's compilations: [method] as `holder.name(descriptor)`. [inlined] counts each callee HotSpot's log
 * says was inlined into it, at any depth and over all its compilations, and [inlineFailures] the reasons calls were
 * not; both are null without a log. [byTier] keeps its tiers and kinds apart, and each compilation's own inlining is
 * in its task record ([CompileTask]).
 */
@Serializable
internal data class MethodCompilations(
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
internal data class RootTier(
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
internal data class TasksFile(
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
internal data class CompileTask(
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
internal data class InlineDecision(
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
internal data class JvmCompilerStatistics(
    val compileCount: Int,
    val bailoutCount: Int?,
    val invalidatedCount: Int?,
    val osrCompileCount: Int?,
    val standardCompileCount: Int?,
    val totalTimeMs: Double?,
    val peakTimeMs: Double?,
)

@Serializable
internal data class LogSummary(
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
internal data class Coverage(
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
internal data class Provenance(
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
internal data class JvmProvenance(
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
internal data class SourceProvenance(
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
internal data class WorkerProvenance(
    val analyzer: String = "eignex-lab compilation analyser, report schema ${CompilationReports.SCHEMA}",
    val labRelease: String? = null,
    val labSha: String? = null,
    val host: String? = null,
    val analyzedAt: Long = 0,
    val analyzeJob: Long? = null,
)

/** Where and how the case ran, from its `measurement.json` and `resources.txt`. */
@Serializable
internal data class ExecutionProvenance(
    val instance: String? = null,
    val measurementId: String? = null,
    val attempt: Int? = null,
    val commandExit: Int? = null,
    val cliExit: Int? = null,
    val javaOptions: String? = null,
    val instrumentation: String? = null,
)

@Serializable
internal data class Capture(val name: String, val bytes: Long, val sha256: String)

/**
 * What an analysis job wrote ([CompilationReports.ANALYSIS]): the reports it wrote afresh, for [job]'s cases, or for
 * the cases in [archive]. Reports of a job are in that job's files, and reports of an archive in the analysis job's own.
 */
@Serializable
internal data class AnalysisResult(
    val job: Long? = null,
    val kind: String,
    val reports: List<AnalysisEntry>,
    val archive: ArchiveProvenance? = null,
)

@Serializable
internal data class AnalysisEntry(
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

