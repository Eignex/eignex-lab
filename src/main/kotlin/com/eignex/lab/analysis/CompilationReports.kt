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
 * The lab's compilation analyser: it reads a profiled case's captures with the JDK's own JFR consumer and an XML
 * stream reader, runs no solver code, and writes [REPORT] and [TASKS] beside them. The runner runs it after each case
 * of a compilation-recorded job, and an analysis job ([AnalyzeSpec]) reruns it over an ended job's retained captures,
 * or over a pinned archive's ([CaptureArchives]).
 */
internal object CompilationReports {
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
        target.writeAtomically(json.encodeToString(CompilationReport.serializer(), report))
        return target
    }

    /** Write [tasks] as [TASKS] in [dir], one JSON line each; what the report says of the file. */
    fun writeTasks(dir: Path, tasks: List<CompileTask>): TasksFile {
        val target = dir.resolve(TASKS)
        // A temp file of this writer's own, so two writers cannot interleave, and none left behind on a failure.
        val temporary = Files.createTempFile(dir, ".$TASKS.", ".tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        val counted = CountingStream(DigestOutputStream(Files.newOutputStream(temporary), digest))
        try {
            GZIPOutputStream(counted, 1 shl 16).bufferedWriter(Charsets.UTF_8).use { out ->
                for (task in tasks) {
                    out.write(lines.encodeToString(CompileTask.serializer(), task))
                    out.write("\n")
                }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return TasksFile(TASKS, records = tasks.size, bytes = counted.count, sha256 = digest.digest().hex())
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
                    .map { Capture(it.name, Files.size(it), hashFile(it)) },
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

