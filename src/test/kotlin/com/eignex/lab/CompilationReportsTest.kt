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
        assertEquals("c2" to 4, log.nmethods[2701])
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
        assertEquals(listOf(Tier("c1", 3, 1, 2.0, 2.0), Tier("c2", 4, 2, 4.0, 4.0), Tier(null, null, 1, 7.0, 7.0)), report.byTier)
        assertEquals(listOf(Bucket("failed", 1, 7.0), Bucket("succeeded", 3, 6.0)), report.byStatus)
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
            "failures", "log", "coverage", "methods", "provenance")) assertTrue(key in json, key)
        assertEquals("1", json.getValue("schemaVersion").toString())
        assertTrue("worker" in json.getValue("provenance").jsonObject && "captures" in json.getValue("provenance").jsonObject)
    }

    @Test
    fun `each arm's mean compiler work is set against the first arm's`() {
        val jobDir = Files.createTempDirectory("job")
        val cases = listOf(0 to "base", 1 to "candidate").map { (index, arm) ->
            val dir = jobDir.resolve("cases/$index/profile-$index").createDirectories()
            dir.resolve(CliMeasurements.COMPILATION_LOG).writeText(if (arm == "base") sample else sample.replace("stamp='0.956'", "stamp='0.953'"))
            manifest(dir, Experiments.COMPILATION_JFR_LOG)
            CompilationReports.analyzeAndWrite(dir, SourceProvenance(1, index), worker)
            CaseResult(index, Status.DONE, Problem("s", "p"), arm, instrumented = true)
        } + CaseResult(2, Status.FAILED, Problem("s", "q"), "candidate", instrumented = true)

        val view = CompilationReports.view(1, jobDir, ExperimentSpec("x", profileCli = true, profileCompilation = "jfr+log"),
            listOf("base", "candidate"), cases)

        assertTrue(view.instrumented)
        assertEquals(listOf(2), view.withoutReport)
        val (base, candidate) = view.arms
        assertEquals(13.0, base.compilerMs, 1e-6)
        assertNull(base.compilerMsRatio)
        assertEquals(10.0, candidate.compilerMs, 1e-6)
        assertEquals(10.0 / 13.0, candidate.compilerMsRatio!!, 1e-3)
        assertEquals(0.0, candidate.compilationsDelta)
        assertEquals("cases/1/profile-1/${CompilationReports.REPORT}", view.cases[1].report)
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
        )) assertEquals(kept, JobTrim.keeps(Path.of(path)), path)
    }
}
