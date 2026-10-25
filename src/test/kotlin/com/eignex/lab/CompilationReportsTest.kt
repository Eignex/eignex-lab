package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.GZIPOutputStream
import jdk.jfr.Recording
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class CompilationReportsTest {
    private val sample: String = checkNotNull(javaClass.getResource("/compilation/hotspot-compilation.log")).readText()
    private val worker = WorkerProvenance(labRelease = "abc1234-20261020", labSha = "abc1234", host = "test", analyzedAt = 1)

    /** Code no other test runs, hot enough that the JIT compiles it while a recording is on. */
    private object Workload {
        fun mix(n: Int): Long {
            var sum = 0L
            for (i in 0 until n) sum += step(i, sum)
            return sum
        }

        private fun step(i: Int, sum: Long): Long = (i * 31L) xor (sum ushr 3)

        fun strings(n: Int): Int = (0 until n).sumOf { "k$it".hashCode() and 7 }
    }

    /** A recording of this JVM with `jdk.Compilation` at [threshold], over code that gets compiled, in [dir]. */
    private fun record(dir: Path, threshold: Duration = Duration.ZERO): Path {
        val file = dir.resolve(CliMeasurements.JFR)
        Recording().use { recording ->
            recording.enable("jdk.Compilation").withThreshold(threshold)
            recording.enable("jdk.CompilationFailure")
            recording.enable("jdk.CompilerStatistics").withPeriod(Duration.ofMillis(50))
            recording.enable("jdk.JVMInformation")
            recording.enable("jdk.InitialSystemProperty")
            recording.enable("jdk.ActiveSetting")
            recording.start()
            var sink = 0L
            repeat(20) { sink += Workload.mix(200_000) + Workload.strings(20_000) }
            Thread.sleep(200)
            recording.stop()
            recording.dump(file)
            check(sink != 42L)
        }
        return file
    }

    private fun manifest(dir: Path, compilation: String?, exit: Int = 0) =
        CliMeasurements.writeManifest(dir, 3, 1, "m-1", "lab", "solve-one", exit, "-Xmx1g", null, macOs = false, compilation = compilation)

    @Test
    fun `an in-process recording with no compilation threshold reads back every recorded compilation and the JVM`() {
        val dir = Files.createTempDirectory("compilation")
        val file = record(dir)

        val capture = JfrCompilations.read(file)

        assertNull(capture.error)
        assertTrue(capture.compilations.isNotEmpty(), "no compilation recorded")
        assertTrue(capture.compilations.all { it.ms >= 0 && it.method.contains('.') && it.method.contains('(') })
        assertTrue(capture.compilations.any { it.compiler in setOf("c1", "c2") && it.level in 1..4 })
        assertTrue(CompilationReports.zeroDuration(checkNotNull(capture.threshold)), capture.threshold)
        assertTrue(System.getProperty("java.vm.version") in capture.jvm?.version.orEmpty(), capture.jvm?.version)
        assertNotNull(capture.statistics)
        // A recording dumped from a running JVM has no shutdown in it.
        assertNull(capture.shutdown)
    }

    @Test
    fun `a report of a recording totals its compilations by tier, kind and status and keeps its provenance`() {
        val dir = Files.createTempDirectory("compilation")
        val file = record(dir)
        manifest(dir, Experiments.COMPILATION_JFR)
        val source = SourceProvenance(7, 3, "s/p", "candidate", "feature", "f".repeat(40))

        val report = CompilationReports.analyzeAndWrite(dir, source, worker)

        val read = checkNotNull(CompilationReports.read(dir.resolve(CompilationReports.REPORT)))
        assertEquals(report, read)
        assertEquals(Experiments.COMPILATION_JFR, report.mode)
        assertEquals(report.totals.compilations, report.byTier.sumOf { it.compilations })
        assertEquals(report.totals.compilations, report.byKind.sumOf { it.compilations })
        assertEquals(report.totals.compilations, report.byStatus.sumOf { it.compilations })
        assertEquals(report.totals.compilations, report.methods.sumOf { it.compilations })
        assertEquals(report.totals.compilerMs, CompilationReports.round(report.methods.sumOf { it.compilerMs }), 0.01)
        assertTrue(report.methods.zipWithNext().all { (a, b) -> a.compilerMs >= b.compilerMs })
        assertTrue(report.methods.all { it.inlined == null }, "no inlining without a HotSpot log")
        assertEquals(source, report.provenance.source)
        assertEquals(worker, report.provenance.worker)
        assertEquals("lab", report.provenance.execution?.instance)
        assertEquals("jfr-profile+compilation", report.provenance.execution?.instrumentation)
        val capture = report.provenance.captures.single { it.name == CliMeasurements.JFR }
        assertEquals(Files.size(file), capture.bytes)
        assertEquals(64, capture.sha256.length)
        assertTrue(report.coverage.jfrReadComplete && report.coverage.jfrCompilations == report.totals.compilations)
        // This JVM compiled long before the recording began, and it was not shut down.
        val incomplete = report.incomplete.map { it.what }
        assertTrue("before-recording" in incomplete && "jfr-shutdown" in incomplete, incomplete.toString())
        assertFalse("jfr-threshold" in incomplete)
        assertFalse(report.complete)
    }

    @Test
    fun `a recording with the profile threshold says shorter compilations are missing`() {
        val dir = Files.createTempDirectory("compilation")
        record(dir, Duration.ofMillis(100))

        val report = CompilationReports.analyze(dir, null, worker)

        val threshold = report.incomplete.single { it.what == "jfr-threshold" }
        assertTrue("profileCompilation" in threshold.why, threshold.why)
        assertFalse(CompilationReports.zeroDuration(checkNotNull(report.coverage.compilationThreshold)))
        assertTrue(report.missing.any { it.what == "measurement" } && report.missing.any { it.what == "source" })
    }

    @Test
    fun `the HotSpot log parser reads tasks, tiers, failures and each root's inlined callees`() {
        val dir = Files.createTempDirectory("compilation")
        val path = dir.resolve(CliMeasurements.COMPILATION_LOG).apply { writeText(sample) }

        val log = HotSpotLog.read(path)

        assertNull(log.error)
        assertTrue(log.done)
        assertEquals(2, log.threads)
        assertEquals(listOf(17, 300, 2701, 2704), log.tasks.keys.toList())
        assertEquals(2, log.inlineSuccess)
        assertEquals(3, log.inlineFail)
        assertEquals(mapOf("callee is too large" to 2, "no static binding" to 1), log.inlineFailures)
        val osr = log.tasks.getValue(2704)
        assertTrue(osr.osr)
        assertEquals("Hot.g(I)I", osr.method)
        assertEquals(mapOf("Hot.f(I)I" to 1, "java.lang.System.arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V" to 1), osr.inlined)
        assertEquals(4.0, osr.durationMs!!, 1e-6)
        assertEquals(8L, osr.inlinedBytes)
        assertEquals("concurrent class loading", log.tasks.getValue(300).failure)
        assertEquals(false, log.tasks.getValue(300).success)
        assertEquals("c2" to 4, log.nmethods[2701]?.let { it.compiler to it.level })
        assertEquals(3, log.tasks.getValue(17).level)
        assertEquals("java.lang.module.ModuleDescriptor\$Exports.hashCode()I", log.tasks.getValue(17).method)
    }

    @Test
    fun `a report from a HotSpot log alone fills tiers from it and summarises inlining per root`() {
        val dir = Files.createTempDirectory("compilation")
        GZIPOutputStream(Files.newOutputStream(dir.resolve(CliMeasurements.COMPILATION_LOG_GZ))).use { it.write(sample.toByteArray()) }
        manifest(dir, Experiments.COMPILATION_JFR_LOG)

        val report = CompilationReports.analyze(dir, SourceProvenance(1, 3), worker)

        assertEquals(Experiments.COMPILATION_JFR_LOG, report.mode)
        assertEquals(listOf("jfr"), report.missing.map { it.what })
        assertEquals(4, report.totals.compilations)
        assertEquals(1, report.totals.failed)
        assertEquals(1, report.totals.osr)
        assertEquals(13.0, report.totals.compilerMs, 1e-6)
        // The C2 task the log names no level for is at C2's tier, as HotSpot leaves the highest level out.
        assertEquals(listOf(Tier("c1", 3, 1, 2.0, 2.0), Tier("c2", 4, 3, 11.0, 7.0)), report.byTier)
        assertEquals(listOf(Bucket("bailout", 1, 7.0), Bucket("succeeded", 3, 6.0)), report.byStatus)
        assertEquals(listOf(Reason("concurrent class loading", 1)), report.failures)
        val g = report.methods.single { it.method == "Hot.g(I)I" }
        assertEquals(listOf(Callee("Hot.f(I)I", 1), Callee("java.lang.System.arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V", 1)), g.inlined)
        val exports = report.methods.single { it.method.startsWith("java.lang.module.ModuleDescriptor\$Exports.hashCode") }
        assertEquals(listOf(Reason("callee is too large", 2), Reason("no static binding", 1)), exports.inlineFailures)
        assertEquals(4, report.log?.tasks)
        assertEquals(true, report.log?.complete)
        assertEquals(4, report.coverage.logOnly)
        assertEquals(CliMeasurements.COMPILATION_LOG_GZ, report.provenance.captures.first().name)
        assertTrue(report.incomplete.any { it.what == "before-recording" })
    }

    @Test
    fun `a log cut short and a case stopped at its limit are marked incomplete, an unmerged log as missing inlining`() {
        val dir = Files.createTempDirectory("compilation")
        // As a JVM killed mid-run leaves it: its tty section, and none of the compiler threads' logs.
        dir.resolve(CliMeasurements.COMPILATION_LOG).writeText(sample.substringBefore("<tty_done"))
        manifest(dir, Experiments.COMPILATION_JFR_LOG, exit = 143)

        val report = CompilationReports.analyze(dir, SourceProvenance(1, 3), worker)

        val incomplete = report.incomplete.map { it.what }
        assertTrue("hotspot-log-parse" in incomplete && "case-exit" in incomplete, incomplete.toString())
        assertTrue(report.missing.any { it.what == "inlining" } && report.missing.any { it.what == "jfr" })
        assertFalse(report.complete)
        assertEquals(false, report.log?.complete)
        assertEquals(0, report.log?.compilerThreads)
        // What the tty section said still counts: the compile ids and their tiers, with no duration.
        assertEquals(0, report.coverage.logTasks)
    }

    @Test
    fun `the report's JSON keeps its schema fields, the empty marker lists included`() {
        val dir = Files.createTempDirectory("compilation")
        dir.resolve(CliMeasurements.COMPILATION_LOG).writeText(sample)
        manifest(dir, Experiments.COMPILATION_JFR_LOG)
        CompilationReports.analyzeAndWrite(dir, SourceProvenance(1, 3), worker)

        val json = Json.parseToJsonElement(Files.readString(dir.resolve(CompilationReports.REPORT))).jsonObject

        for (key in listOf("schemaVersion", "kind", "mode", "complete", "missing", "incomplete", "totals", "byTier", "byKind", "byStatus",
            "failures", "log", "coverage", "methods", "provenance", "tasks")) assertTrue(key in json, key)
        assertEquals("2", json.getValue("schemaVersion").toString())
        assertTrue("worker" in json.getValue("provenance").jsonObject && "captures" in json.getValue("provenance").jsonObject)
    }

    @Test
    fun `an analysis is refused for a trimmed, missing, running or unprofiled job`() {
        val jobDir = Files.createTempDirectory("job")
        val spec = ExperimentSpec("x", profileCli = true, profileCompilation = "jfr")
        fun job(status: Status, experiment: ExperimentSpec? = spec) = Job(5, "x", "main", status = status, cancelRequested = false,
            setupDone = true, createdAt = 0, experiment = experiment)

        assertTrue("no retained captures" in CompilationReports.refusal(job(Status.DONE), jobDir)!!)
        val profile = jobDir.resolve("cases/0/profile-a").createDirectories()
        profile.resolve(CliMeasurements.JFR).writeText("")
        assertNull(CompilationReports.refusal(job(Status.DONE), jobDir))
        assertTrue("running" in CompilationReports.refusal(job(Status.RUNNING), jobDir)!!)
        assertTrue("without profileCli" in CompilationReports.refusal(job(Status.DONE, ExperimentSpec("y")), jobDir)!!)
        assertEquals("no such job", CompilationReports.refusal(null, jobDir))
        jobDir.resolve(JobTrim.MARKER).writeText("1 2\n")
        assertTrue("trimmed" in CompilationReports.refusal(job(Status.DONE), jobDir)!!)
    }

    @Test
    fun `a trim keeps a profiled case's compilation report and manifest and removes its raw captures`() {
        for ((path, kept) in listOf(
            "cases/0/profile-a/compilation-report.json" to true,
            "cases/0/profile-a/measurement.json" to true,
            "cases/0/profile-a/cli.jfr" to false,
            "cases/0/profile-a/hotspot-compilation.log.gz" to false,
            "cases/0/profile-a/solve-record.json" to false,
            "analysis.json" to true,
            "cases/0/profile-a/compilation-tasks.jsonl.gz" to true,
            "archive.manifest.json" to true,
            "archive/evidence/returned-files/cases/0/profile-a/compilation-report.json" to true,
            "archive/evidence/returned-files/cases/0/profile-a/compilation-tasks.jsonl.gz" to true,
            "archive/evidence/returned-files/cases/0/profile-a/hotspot-compilation.log" to false,
            "archive/evidence/returned-files/cases/0/profile-a/archived-compilation-report.json" to false,
            "archive/compilation-report.json" to false,
        )) assertEquals(kept, JobTrim.keeps(Path.of(path)), path)
    }

    @Serializable
    private class JfrFixture(val compilations: List<JfrEvent>, val failures: Map<String, String>)

    @Serializable
    private class JfrEvent(
        val id: Int, val method: String, val compiler: String, val level: Int, val osr: Boolean, val succeeded: Boolean, val ms: Double,
        val codeBytes: Long, val inlinedBytes: Long, val startMs: Long,
    )

    /** The captures of `tasks.log` and `tasks-jfr.json`: a retry, an OSR task, a bailout and a failure, and tasks in one capture only. */
    private fun taskCaptures(): Triple<Path, JfrCompilations.Capture, HotSpotLog.Capture> {
        val fixture = Json { ignoreUnknownKeys = true }.decodeFromString(JfrFixture.serializer(),
            checkNotNull(javaClass.getResource("/compilation/tasks-jfr.json")).readText())
        val jfr = JfrCompilations.Capture(
            fixture.compilations.map { JfrCompilations.Compilation(it.id, it.method, it.compiler, it.level, it.osr, it.succeeded, it.ms, it.codeBytes,
                it.inlinedBytes, it.startMs) },
            fixture.failures.mapKeys { it.key.toInt() }, JvmCompilerStatistics(7, 1, 0, 1, 6, 66.0, 40.0), JvmProvenance(name = "test"),
            "0 ms", true, "No remaining non-daemon Java threads", 0, 100, null)
        val dir = Files.createTempDirectory("tasks")
        val text = checkNotNull(javaClass.getResource("/compilation/tasks.log")).readText()
        val log = dir.resolve(CliMeasurements.COMPILATION_LOG).apply { writeText(text) }
        manifest(dir, Experiments.COMPILATION_JFR_LOG)
        return Triple(dir, jfr, HotSpotLog.read(log))
    }

    @Test
    fun `every compile id is a task record of its own, retries, OSR, failures and one-capture tasks kept apart`() {
        val (dir, jfr, log) = taskCaptures()

        val (report, tasks) = CompilationReports.build(dir, jfr, log, true, SourceProvenance(1, 0), worker)

        assertEquals((10..19).toList(), tasks.map { it.compileId }, "the native wrapper (20) is no compilation")
        val byId = tasks.associateBy { it.compileId }
        // A retry of the same method at the same tier, after its first C2 code was made not entrant, stays distinct.
        val first = byId.getValue(11)
        val retry = byId.getValue(12)
        assertEquals(listOf("c2", "c2"), listOf(first.compiler, retry.compiler))
        assertEquals(1 to null, first.attempt to first.retryOf)
        assertEquals(2 to 11, retry.attempt to retry.retryOf)
        assertEquals(10, first.previous)
        assertEquals("unstable_if", first.notEntrant)
        assertTrue("log-not-entrant" in first.observed)
        assertEquals(4.0, first.ms)
        assertEquals(9.5, retry.jfrMs)
        assertEquals(10.0, retry.logMs!!, 1e-6)
        assertEquals(0.4, retry.queuedAt)
        assertEquals(0.41, retry.endedAt)
        assertEquals(1792501577192, retry.jfrStartMs)
        // The OSR task, its bci and its own inlining, at depth, with C2's late inlining.
        val osr = byId.getValue(13)
        assertEquals("osr" to 4, osr.kind to osr.osrBci)
        assertEquals("matched", osr.join)
        assertEquals(listOf(
            InlineDecision(1, "Hot.g(I)I", "Hot.f(I)I", 9, true, "inline (hot)", 8),
            InlineDecision(2, "Hot.f(I)I", "java.lang.Math.abs(I)I", 3, true, "intrinsic", 11),
            InlineDecision(1, "Hot.g(I)I", "Hot.big(I)I", 20, false, "hot method too big", 400),
            InlineDecision(1, "Hot.g(I)I", "Hot.late()I", 24, true, "late inline", 6, late = true),
            InlineDecision(2, "Hot.late()I", "Hot.leaf()I", 2, true, "inline (hot)", 5),
        ), osr.inlining)
        assertEquals(emptyList(), byId.getValue(10).inlining, "a task with no calls has an empty inlining list, not none")
        // A bailout says why, a failure without a reason is a failure, and neither is merged into its method's others.
        assertEquals(CompilationReports.BAILOUT to "out of nodes during matching", byId.getValue(14).let { it.status to it.failure })
        assertEquals(CompilationReports.FAILED to null, byId.getValue(15).let { it.status to it.failure })
        // One capture only: the log saw 16 and 19 and not JFR, JFR saw 18 and not the log; 17 was only queued.
        assertEquals("log-only" to CompilationReports.SUCCEEDED, byId.getValue(16).let { it.join to it.status })
        assertNull(byId.getValue(16).jfrMs)
        assertEquals(1.0, byId.getValue(16).ms!!, 1e-6)
        assertEquals("jfr-only" to listOf("jfr"), byId.getValue(18).let { it.join to it.observed })
        assertNull(byId.getValue(18).inlining)
        assertEquals("log-only" to CompilationReports.QUEUED, byId.getValue(17).let { it.join to it.status })
        assertEquals(listOf("log-queued"), byId.getValue(17).observed)
        assertEquals(4, byId.getValue(17).level, "a queued task with no level is C2's")
        assertEquals("log-only" to CompilationReports.UNKNOWN, byId.getValue(19).let { it.join to it.status })
        assertTrue(tasks.all { it.disagreements == null })

        assertEquals(CompileTotals(compilations = 9, compilerMs = 66.75, maxMs = 40.0, succeeded = 6, failed = 2, osr = 1, codeBytes = 1856,
            inlinedBytes = 13, bailouts = 1, unknown = 1, queuedOnly = 1), report.totals.copy(compilerMs = CompilationReports.round(report.totals.compilerMs)))
        assertEquals(listOf("bailout", "failed", "succeeded", "unknown"), report.byStatus.map { it.key })
        val coverage = report.coverage
        assertEquals(listOf(7, 8, 6, 1, 2), listOf(coverage.jfrCompilations, coverage.logTasks, coverage.matched, coverage.jfrOnly, coverage.logOnly))
        assertEquals(listOf(1, 1, 0, 0), listOf(coverage.queuedOnly, coverage.jfrWithoutLogRecord, coverage.disagreements, coverage.idsWithoutRecord))
        assertEquals(10 to 20, coverage.firstCompileId to coverage.lastCompileId)
        assertEquals(1, coverage.nativeWrappers)
        val incomplete = report.incomplete.map { it.what }
        assertTrue("jfr-without-log-task" in incomplete && "log-without-jfr" in incomplete && "hotspot-log-tasks" in incomplete, incomplete.toString())
        assertFalse(report.complete)
    }

    @Test
    fun `each root has its tiers and kinds apart, with their compile ids`() {
        val (dir, jfr, log) = taskCaptures()

        val report = CompilationReports.build(dir, jfr, log, true, SourceProvenance(1, 0), worker).first

        val f = report.methods.single { it.method == "Hot.f(I)I" }
        assertEquals(3, f.compilations)
        assertEquals(listOf(
            RootTier("c1", 3, "normal", 1, 1.5, 1.5, 1, 0, 0, 0, 0, 240, 0, listOf(10)),
            RootTier("c2", 4, "normal", 2, 13.5, 9.5, 2, 0, 0, 0, 0, 232, 0, listOf(11, 12)),
        ), f.byTier)
        val g = report.methods.single { it.method == "Hot.g(I)I" }
        assertEquals(listOf(RootTier("c2", 4, "osr", 1, 7.25, 7.25, 1, 0, 0, 0, 0, 1160, 13, listOf(13))), g.byTier)
        val h = report.methods.single { it.method == "Hot.h()V" }
        assertEquals(1 to 1, h.byTier.single().failed to h.byTier.single().bailouts)
        val n = report.methods.single { it.method == "Hot.n()V" }
        assertEquals(0 to 1, n.compilations to n.queued)
        assertEquals(RootTier("c2", 4, "normal", 0, 0.0, 0.0, 0, 0, 0, 0, 1, 0, 0, listOf(17)), n.byTier.single())
        assertEquals(report.totals.compilations, report.methods.sumOf { it.compilations })
        assertEquals(report.totals.compilations, report.methods.sumOf { m -> m.byTier.sumOf { it.compilations } })
    }

    @Test
    fun `the task records go to their own gzipped file, which the report names with its hash`() {
        val dir = Files.createTempDirectory("compilation")
        dir.resolve(CliMeasurements.COMPILATION_LOG).writeText(checkNotNull(javaClass.getResource("/compilation/tasks.log")).readText())
        manifest(dir, Experiments.COMPILATION_JFR_LOG)

        val report = CompilationReports.analyzeAndWrite(dir, SourceProvenance(1, 0), worker)

        val file = dir.resolve(CompilationReports.TASKS)
        val tasks = checkNotNull(report.tasks)
        assertEquals(CompilationReports.TASKS, tasks.file)
        assertEquals(Files.size(file), tasks.bytes)
        assertEquals(CompilationReports.sha256(file), tasks.sha256)
        val records = CompilationReports.readTasks(file)
        assertEquals(tasks.records, records.size)
        assertEquals((10..17).toList() + 19, records.map { it.compileId })
        assertTrue(records.all { it.join == "log" })
        assertEquals(report, CompilationReports.read(dir.resolve(CompilationReports.REPORT)))
        // Nulls are left out of a line, so a record is as long as what is known of it.
        val line = String(java.util.zip.GZIPInputStream(Files.newInputStream(file)).readAllBytes()).lines().first { "\"compileId\":17" in it }
        assertFalse("null" in line, line)
    }

    @Test
    fun `a schema 1 report still reads, the fields schema 2 added at their defaults`() {
        val dir = Files.createTempDirectory("v1")
        val path = dir.resolve(CompilationReports.REPORT)
        Files.writeString(path, checkNotNull(javaClass.getResource("/compilation/report-v1.json")).readText())

        val report = checkNotNull(CompilationReports.read(path))

        assertEquals(1, report.schemaVersion)
        assertTrue(report.complete)
        assertEquals(3, report.totals.compilations)
        assertEquals(0 to 0, report.totals.bailouts to report.totals.queuedOnly)
        assertNull(report.tasks)
        assertEquals(emptyList(), report.methods.single().byTier)
        assertEquals(SourceProvenance(6971, 0, "s/p", "control", "main", "5374cd79b487183b51bbe1981a70f1312bc31888"), report.provenance.source)
        assertNull(report.provenance.archive)
        assertEquals("eignex-lab compilation analyser, report schema 1", report.provenance.worker.analyzer)
    }
}
