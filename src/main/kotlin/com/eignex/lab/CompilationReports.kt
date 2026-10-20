package com.eignex.lab

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedMethod
import jdk.jfr.consumer.RecordingFile
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a profiled case's CLI JVM spent on JIT compilation, as the lab worked it out from the case's retained captures
 * ([CompilationReports.analyze]): the JFR recording and, with `profileCompilation: "jfr+log"`, HotSpot's compilation
 * log. Every compilation is one compile id. [missing] names an observation the captures do not hold at all, and
 * [incomplete] one they hold only in part; the report is [complete] when both are empty. Durations are in ms.
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
    val totals: CompileTotals,
    /** By compiler and tier: tiers 1 to 3 are C1's, 4 is C2's. */
    val byTier: List<Tier>,
    /** `normal` and `osr` (on-stack replacement) compilations. */
    val byKind: List<Bucket>,
    /** `succeeded`, `failed`, and `unknown` for a compilation whose end neither capture saw. */
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
    val failed: Int,
    val osr: Int,
    /** Machine code the compilations produced, in bytes, where they said. */
    val codeBytes: Long,
    /** Bytecode inlined into them, in bytes, where they said. */
    val inlinedBytes: Long,
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
 * not; both are null without a log.
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

/** How much of the run the captures saw, and where each compilation's figures came from. */
@Serializable
data class Coverage(
    val jfrCompilations: Int,
    val logTasks: Int? = null,
    /** Compile ids in both captures, in JFR alone, and in the log alone. */
    val matched: Int? = null,
    val jfrOnly: Int? = null,
    val logOnly: Int? = null,
    val firstCompileId: Int? = null,
    val lastCompileId: Int? = null,
    /** Ids between the first and the last that neither capture holds: tasks dropped before they compiled, or lost. */
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
)

@Serializable
data class Provenance(
    val jvm: JvmProvenance? = null,
    val source: SourceProvenance? = null,
    val worker: WorkerProvenance,
    val execution: ExecutionProvenance? = null,
    /** The captures the report was made from, with their sizes and SHA-256. */
    val captures: List<Capture> = emptyList(),
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

/** The case the captures belong to: its job, index, problem, and its arm's label, ref and commit. */
@Serializable
data class SourceProvenance(
    val job: Long,
    val case: Int,
    val problem: String? = null,
    val arm: String? = null,
    val ref: String? = null,
    val sha: String? = null,
)

/** Who made the report: the lab release it ran on, its host, when, and the analysis job that asked, if one did. */
@Serializable
data class WorkerProvenance(
    val analyzer: String = "klause-lab compilation analyser, report schema ${CompilationReports.SCHEMA}",
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

/** What an analysis job wrote ([CompilationReports.ANALYSIS]): the reports it wrote afresh for [job]'s cases. */
@Serializable
data class AnalysisResult(val job: Long, val kind: String, val reports: List<AnalysisEntry>)

@Serializable
data class AnalysisEntry(
    val case: Int,
    /** The report's path within [AnalysisResult.job]'s files. */
    val report: String,
    val compilations: Int,
    val compilerMs: Double,
    val complete: Boolean,
    val markers: List<String>,
)

/**
 * The lab's compilation analyser: it reads a profiled case's captures with the JDK's own JFR consumer and an XML
 * stream reader, runs no klause code, and writes [REPORT] beside them. The runner runs it after each case of a
 * `profileCompilation` job, and an analysis job ([AnalyzeSpec]) reruns it over an ended job's retained captures.
 */
object CompilationReports {
    const val REPORT = "compilation-report.json"
    const val SCHEMA = 1
    /** What an analysis job writes in its own directory: each case's fresh report in brief. */
    const val ANALYSIS = "analysis.json"

    private val json = Json { encodeDefaults = true; prettyPrint = false }
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
     * `profileCli` experiment whose captures are still there, neither trimmed ([JobTrim]) nor missing.
     */
    fun refusal(job: Job?, jobDir: Path): String? {
        if (job == null) return "no such job"
        val spec = job.experiment ?: return "job ${job.id} is not an experiment"
        if (spec.isAnalysis) return "job ${job.id} is an analysis job itself; name the job it analysed (${spec.analyze?.job})"
        if (!spec.profileCli) return "job ${job.id} ran without profileCli: it recorded no captures to analyse"
        if (job.status in ACTIVE) return "job ${job.id} is ${job.status.name.lowercase()}: analyse it once it has ended"
        val trimmed = jobDir.resolve(JobTrim.MARKER)
        if (Files.exists(trimmed)) {
            return "job ${job.id} was trimmed (${Files.readString(trimmed).trim()}): its raw captures (cli.jfr, hotspot-compilation.log) " +
                "are gone, and only the reports written when its cases ran are kept; rerun the experiment for new captures"
        }
        val dirs = profileDirs(jobDir)
        if (dirs.none(::hasCaptures)) return "job ${job.id} has no retained captures under cases/<i>/profile-<uuid>/ to analyse"
        return null
    }

    /** The report of the captures in profile directory [dir], written as [REPORT] there; returns it. */
    fun analyzeAndWrite(dir: Path, source: SourceProvenance?, worker: WorkerProvenance = worker()): CompilationReport =
        analyze(dir, source, worker).also { write(dir, it) }

    fun write(dir: Path, report: CompilationReport): Path {
        val target = dir.resolve(REPORT)
        val temporary = dir.resolve("$REPORT.part")
        Files.writeString(temporary, json.encodeToString(CompilationReport.serializer(), report))
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return target
    }

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
    fun analyze(dir: Path, source: SourceProvenance?, worker: WorkerProvenance = worker()): CompilationReport {
        val missing = mutableListOf<Marker>()
        val incomplete = mutableListOf<Marker>()
        val manifest = dir.resolve(CliMeasurements.MANIFEST).takeIf { Files.isRegularFile(it) }
            ?.let { runCatching { lenient.decodeFromString(CliMeasurementManifest.serializer(), Files.readString(it)) }.getOrNull() }
        val resources = dir.resolve(CliMeasurements.RESOURCES).takeIf { Files.isRegularFile(it) }?.let { file ->
            Files.readAllLines(file).mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        }
        val jfrPath = dir.resolve(CliMeasurements.JFR)
        val logPath = logOf(dir)
        val mode = manifest?.compilation ?: if (logPath != null) Experiments.COMPILATION_JFR_LOG else Experiments.COMPILATION_JFR
        if (manifest == null) missing += Marker("measurement", "no readable ${CliMeasurements.MANIFEST}: where and how the case ran is unknown")

        val jfr = if (Files.isRegularFile(jfrPath)) JfrCompilations.read(jfrPath) else null
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
                    "${jfr.threshold}: shorter compilations are missing (the job ran without profileCompilation)")
            }
            if (jfr.dataLossBytes > 0) incomplete += Marker("jfr-data-loss", "JFR lost ${jfr.dataLossBytes} bytes of events (jdk.DataLoss)")
            if (jfr.jvm == null) missing += Marker("jvm", "the recording has no jdk.JVMInformation event: the JVM's version and flags are unknown")
            if (jfr.statistics == null) {
                missing += Marker("jvm-statistics", "no jdk.CompilerStatistics event, which JFR sends every second: the run was shorter, or the recording was cut")
            }
        }
        val log = logPath?.let(HotSpotLog::read)
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

        val compiled = merge(jfr, log)
        val withoutDuration = compiled.count { it.ms == null }
        if (withoutDuration > 0) incomplete += Marker("durations", "$withoutDuration compilations have no duration in either capture, and count as 0 ms")
        val ids = compiled.map { it.id }
        val first = ids.minOrNull()
        val last = ids.maxOrNull()
        if (first != null && first > 1) {
            incomplete += Marker("before-recording", "compile ids 1 to ${first - 1} are in no capture: the JVM compiled them at startup, " +
                "before the recording began" + if (log == null) "; profileCompilation \"jfr+log\" covers them" else "")
        }
        val jfrIds = jfr?.compilations?.mapTo(HashSet()) { it.id }.orEmpty()
        val logIds = log?.tasks?.keys.orEmpty()
        val coverage = Coverage(
            jfrCompilations = jfr?.compilations?.size ?: 0,
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
        )
        val captures = listOf(CliMeasurements.JFR, CliMeasurements.COMPILATION_LOG, CliMeasurements.COMPILATION_LOG_GZ,
            CliMeasurements.RESOURCES, CliMeasurements.MANIFEST, "solve-record.json")
            .map(dir::resolve).filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .map { Capture(it.name, Files.size(it), sha256(it)) }
        val execution = ExecutionProvenance(
            instance = manifest?.instance, measurementId = manifest?.measurementId, attempt = manifest?.attempt,
            commandExit = manifest?.commandExit, cliExit = resources?.get("exit")?.trim()?.toIntOrNull(),
            javaOptions = manifest?.javaOptions, instrumentation = manifest?.instrumentation,
        )
        if (source == null) missing += Marker("source", "the case's job, arm and commit are unknown")
        return CompilationReport(
            mode = mode,
            complete = missing.isEmpty() && incomplete.isEmpty(),
            missing = missing,
            incomplete = incomplete,
            totals = CompileTotals(
                compilations = compiled.size,
                compilerMs = round(compiled.sumOf { it.ms ?: 0.0 }),
                maxMs = round(compiled.maxOfOrNull { it.ms ?: 0.0 } ?: 0.0),
                succeeded = compiled.count { it.succeeded == true },
                failed = compiled.count { it.succeeded == false },
                osr = compiled.count { it.osr },
                codeBytes = compiled.sumOf { it.codeBytes ?: 0 },
                inlinedBytes = compiled.sumOf { it.inlinedBytes ?: 0 },
            ),
            byTier = compiled.groupBy { it.compiler to it.level }.map { (key, group) ->
                Tier(key.first, key.second, group.size, round(group.sumOf { it.ms ?: 0.0 }), round(group.maxOf { it.ms ?: 0.0 }))
            }.sortedWith(compareBy({ it.level ?: Int.MAX_VALUE }, { it.compiler.orEmpty() })),
            byKind = buckets(compiled) { if (it.osr) "osr" else "normal" },
            byStatus = buckets(compiled) { when (it.succeeded) { true -> "succeeded"; false -> "failed"; null -> "unknown" } },
            failures = reasons(compiled.mapNotNull { it.failure }),
            jvmStatistics = jfr?.statistics,
            log = log?.let { l ->
                LogSummary(l.tasks.size, l.tasks.values.count { it.done != null }, l.inlineSuccess, l.inlineFail,
                    reasons(l.inlineFailures), l.threads, l.error == null && l.done)
            },
            coverage = coverage,
            methods = methods(compiled, log != null),
            provenance = Provenance(jfr?.jvm, source, worker, execution.takeIf { manifest != null || resources != null }, captures),
        )
    }

    /** One compile id's figures, JFR's where it has them and the log's otherwise. */
    internal class Compiled(
        val id: Int,
        val method: String,
        val compiler: String?,
        val level: Int?,
        val osr: Boolean,
        val succeeded: Boolean?,
        val ms: Double?,
        val codeBytes: Long?,
        val inlinedBytes: Long?,
        val failure: String?,
        val task: HotSpotLog.Task?,
    )

    internal fun merge(jfr: JfrCompilations.Capture?, log: HotSpotLog.Capture?): List<Compiled> {
        val events = jfr?.compilations.orEmpty().groupBy { it.id }
        val tasks = log?.tasks.orEmpty()
        val nmethods = log?.nmethods.orEmpty()
        return (events.keys + tasks.keys).sorted().map { id ->
            // A compile id is one task; JFR may hold more than one event for it when a compilation is retried.
            val attempts = events[id].orEmpty()
            val event = attempts.lastOrNull()
            val task = tasks[id]
            val nmethod = nmethods[id]
            val level = event?.level ?: task?.level ?: nmethod?.second
            val compiler = event?.compiler?.takeIf { it.isNotBlank() } ?: nmethod?.first?.takeIf { it.isNotBlank() } ?: when (level) {
                in 1..3 -> "c1"
                4 -> "c2"
                else -> null
            }
            Compiled(
                id = id,
                method = event?.method ?: task?.method ?: "unknown",
                compiler = compiler,
                level = level ?: if (compiler == "c2") 4 else null,
                osr = event?.osr ?: task?.osr ?: false,
                succeeded = event?.succeeded ?: task?.success,
                ms = if (attempts.isNotEmpty()) attempts.sumOf { it.ms } else task?.durationMs,
                codeBytes = event?.codeBytes ?: task?.nmsize,
                inlinedBytes = event?.inlinedBytes ?: task?.inlinedBytes,
                failure = jfr?.failures?.get(id) ?: task?.failure,
                task = task,
            )
        }
    }

    private fun methods(compiled: List<Compiled>, logged: Boolean): List<MethodCompilations> =
        compiled.groupBy { it.method }.map { (method, group) ->
            val tasks = group.mapNotNull { it.task }
            MethodCompilations(
                method = method,
                compilations = group.size,
                compilerMs = round(group.sumOf { it.ms ?: 0.0 }),
                maxMs = round(group.maxOf { it.ms ?: 0.0 }),
                tiers = group.mapNotNull { it.level }.distinct().sorted(),
                osr = group.count { it.osr },
                failed = group.count { it.succeeded == false },
                codeBytes = group.sumOf { it.codeBytes ?: 0 },
                inlinedBytes = group.sumOf { it.inlinedBytes ?: 0 },
                inlined = if (!logged) null else tasks.flatMap { it.inlined.entries }.groupBy({ it.key }, { it.value })
                    .map { (callee, counts) -> Callee(callee, counts.sum()) }.sortedWith(compareBy({ -it.count }, { it.method })),
                inlineFailures = if (!logged) null else tasks.flatMap { it.inlineFailures.entries }.groupBy({ it.key }, { it.value })
                    .map { (reason, counts) -> Reason(reason, counts.sum()) }.sortedWith(compareBy({ -it.count }, { it.reason })),
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

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
 * HotSpot's compilation log (`-XX:+LogCompilation`), read as an XML stream: its `tty` section's queued tasks and
 * compiled methods, and each compiler thread's own log, which HotSpot merges in when the JVM exits, with every task's
 * calls and whether each was inlined. A log cut short reads up to where it stops.
 */
internal object HotSpotLog {
    class Task(val id: Int, val method: String, val osr: Boolean, var level: Int?, val start: Double?) {
        var done: Double? = null
        var success: Boolean? = null
        var failure: String? = null
        var nmsize: Long? = null
        var inlinedBytes: Long? = null
        val inlined = HashMap<String, Int>()
        val inlineFailures = HashMap<String, Int>()

        val durationMs: Double? get() = done?.let { end -> start?.let { (end - it) * 1000.0 } }
    }

    class Capture(
        val tasks: Map<Int, Task>,
        /** Each compile id's compiler and level, from the log's `nmethod` entries. */
        val nmethods: Map<Int, Pair<String?, Int?>>,
        val threads: Int,
        val inlineSuccess: Int,
        val inlineFail: Int,
        val inlineFailures: Map<String, Int>,
        /** Why parsing stopped before the end, null when it read to the end. */
        val error: String?,
        /** Whether the log has HotSpot's end mark, `hotspot_log_done`. */
        val done: Boolean,
        val bytes: Long,
    )

    /** Read the log at [path], gunzipping it on the way when its name ends in `.gz`; [Capture.bytes] is the file's size. */
    fun read(path: Path): Capture = Files.newInputStream(path).use { raw ->
        val input = if (path.name.endsWith(".gz")) java.util.zip.GZIPInputStream(raw, 1 shl 16) else raw.buffered(1 shl 16)
        read(input, Files.size(path))
    }

    fun read(input: java.io.InputStream, bytes: Long): Capture {
        val tasks = LinkedHashMap<Int, Task>()
        val nmethods = HashMap<Int, Pair<String?, Int?>>()
        val failures = HashMap<String, Int>()
        var threads = 0
        var successes = 0
        var fails = 0
        var done = false
        var error: String? = null
        // The ids a task's klass, type and method entries define, valid within that task.
        val klasses = HashMap<String, String>()
        val types = HashMap<String, String>()
        val methods = HashMap<String, Triple<String, String, Pair<String?, String?>>>()
        var task: Task? = null
        var call: String? = null
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        var reader: XMLStreamReader? = null
        try {
            reader = factory.createXMLStreamReader(input)
            while (reader.hasNext()) {
                val type = reader.next()
                if (type == XMLStreamConstants.END_ELEMENT && reader.localName == "task") {
                    task = null
                    continue
                }
                if (type != XMLStreamConstants.START_ELEMENT) continue
                fun attr(name: String): String? = reader.getAttributeValue(null, name)
                when (reader.localName) {
                    "compilation_log" -> threads++
                    "task" -> {
                        val id = attr("compile_id")?.toIntOrNull() ?: continue
                        klasses.clear(); types.clear(); methods.clear(); call = null
                        task = Task(id, method(attr("method")), attr("compile_kind") == "osr", attr("level")?.toIntOrNull(),
                            attr("stamp")?.toDoubleOrNull()).also { tasks[id] = it }
                    }
                    "klass" -> attr("id")?.let { id -> klasses[id] = attr("name").orEmpty() }
                    "type" -> attr("id")?.let { id -> types[id] = attr("name").orEmpty() }
                    "method" -> attr("id")?.let { id ->
                        methods[id] = Triple(attr("holder").orEmpty(), attr("name").orEmpty(), attr("return") to attr("arguments"))
                    }
                    "call" -> call = attr("method")?.let { id -> methods[id]?.let { describe(it, klasses, types) } ?: "method#$id" }
                    "inline_success" -> {
                        successes++
                        task?.inlined?.merge(call ?: "unknown", 1, Int::plus)
                    }
                    "inline_fail" -> {
                        fails++
                        val reason = attr("reason").orEmpty()
                        failures.merge(reason, 1, Int::plus)
                        task?.inlineFailures?.merge(reason, 1, Int::plus)
                    }
                    "failure" -> task?.let { it.failure = it.failure ?: attr("reason") }
                    "task_done" -> task?.let {
                        it.success = attr("success")?.let { s -> s == "1" }
                        it.done = attr("stamp")?.toDoubleOrNull()
                        it.nmsize = attr("nmsize")?.toLongOrNull()
                        it.inlinedBytes = attr("inlined_bytes")?.toLongOrNull()
                    }
                    "nmethod" -> attr("compile_id")?.toIntOrNull()?.let { id -> nmethods[id] = attr("compiler") to attr("level")?.toIntOrNull() }
                    "hotspot_log_done" -> done = true
                }
            }
        } catch (e: Exception) {
            val line = reader?.location?.lineNumber?.takeIf { it > 0 }
            error = (if (line != null) "line $line: " else "") + (e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: e::class.simpleName)
        } finally {
            runCatching { reader?.close() }
        }
        return Capture(tasks, nmethods, threads, successes, fails, failures, error, done, bytes)
    }

    /** A task's `method` attribute, `holder name descriptor`, as JFR names methods: `holder.name(descriptor)`. */
    internal fun method(attribute: String?): String {
        val parts = attribute.orEmpty().split(' ')
        return if (parts.size >= 3) "${parts[0]}.${parts[1]}${parts[2]}" else attribute ?: "unknown"
    }

    /** A `method` entry as `holder.name(descriptor)`, its descriptor rebuilt from the task's type and klass entries. */
    private fun describe(
        method: Triple<String, String, Pair<String?, String?>>,
        klasses: Map<String, String>,
        types: Map<String, String>,
    ): String {
        fun descriptor(id: String): String {
            types[id]?.let { name -> PRIMITIVES[name]?.let { return it } }
            val klass = klasses[id] ?: return "?"
            return if (klass.startsWith("[")) klass.replace('.', '/') else "L${klass.replace('.', '/')};"
        }
        val (holder, name, signature) = method
        val arguments = signature.second.orEmpty().split(' ').filter { it.isNotEmpty() }.joinToString("") { descriptor(it) }
        val returns = signature.first?.let { descriptor(it) } ?: "?"
        return "${klasses[holder] ?: "?"}.$name($arguments)$returns"
    }

    private val PRIMITIVES = mapOf(
        "void" to "V", "boolean" to "Z", "byte" to "B", "char" to "C", "short" to "S", "int" to "I", "long" to "J",
        "float" to "F", "double" to "D",
    )
}
