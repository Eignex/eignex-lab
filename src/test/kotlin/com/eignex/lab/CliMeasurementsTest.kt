package com.eignex.lab

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.concurrent.TimeUnit
import jdk.jfr.consumer.RecordingFile
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliMeasurementsTest {
    @Test
    @EnabledOnOs(OS.LINUX)
    fun `a profiled JVM returns a readable recording and physical resource measurements`() {
        val dir = Files.createTempDirectory("measurement")
        val cli = dir.resolve(CliMeasurements.CLI)
        Files.createDirectories(cli.parent)
        val javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
        Files.writeString(cli, "#!/bin/bash\nexec ${quote(javaExecutable.toString())} \$KLAUSE_CLI_OPTS -version\n")
        cli.toFile().setExecutable(true)
        val setup = ProcessBuilder("bash", "-c", CliMeasurements.install(dir.toString())).start()
        assertEquals(0, setup.waitFor(), setup.errorStream.bufferedReader().readText())
        val profile = dir.resolve("profile")
        Files.createDirectories(profile)
        val builder = ProcessBuilder(cli.toString()).redirectErrorStream(true)
        builder.environment()["EIGNEX_LAB_PROFILE_DIR"] = profile.toString()
        builder.environment()["KLAUSE_CLI_OPTS"] = "-Xmx64m -XX:ActiveProcessorCount=1 ${CliMeasurements.options(profile.toString())}"

        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(0, process.waitFor(), output)
        val events = RecordingFile.readAllEvents(profile.resolve("cli.jfr"))
        assertTrue(events.any { it.eventType.name == "jdk.JVMInformation" })
        val resources = Files.readString(profile.resolve("resources.txt"))
        assertTrue("exit=0" in resources)
        assertTrue(Regex("peakRssKiB=[1-9][0-9]*").containsMatchIn(resources))
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `a bare version check runs unmeasured and leaves the profile directory alone`() {
        val dir = Files.createTempDirectory("measurement")
        val cli = dir.resolve(CliMeasurements.CLI)
        Files.createDirectories(cli.parent)
        Files.writeString(cli, "#!/bin/bash\nprintf 'cli %s\\n' \"\$*\"\n")
        cli.toFile().setExecutable(true)
        val setup = ProcessBuilder("bash", "-c", CliMeasurements.install(dir.toString())).start()
        assertEquals(0, setup.waitFor(), setup.errorStream.bufferedReader().readText())
        val profile = Files.createDirectories(dir.resolve("profile"))
        fun run(vararg args: String): String {
            val builder = ProcessBuilder(listOf(cli.toString()) + args).redirectErrorStream(true)
            builder.environment()["EIGNEX_LAB_PROFILE_DIR"] = profile.toString()
            val process = builder.start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
            return output
        }

        assertEquals("cli --version", run("--version").trim())
        assertTrue(Files.list(profile).use { it.toList() }.isEmpty())
        assertTrue("cli solve x" in run("solve", "x"))
        assertTrue(Files.exists(profile.resolve("resources.txt")))
    }

    @Test
    fun `compilation profiling records every compilation, and with the log HotSpot's inlining too`() {
        assertEquals("-XX:StartFlightRecording=filename=/p/cli.jfr,settings=profile,dumponexit=true", CliMeasurements.options("/p"))
        assertEquals("-XX:StartFlightRecording=filename=/p/cli.jfr,settings=profile,jdk.Compilation#threshold=0ms,dumponexit=true",
            CliMeasurements.options("/p", "jfr"))
        assertEquals("-XX:StartFlightRecording=filename=/p/cli.jfr,settings=profile,jdk.Compilation#threshold=0ms,dumponexit=true " +
            "-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=/p/hotspot-compilation.log", CliMeasurements.options("/p", "jfr+log"))
        assertEquals(listOf("cli.jfr", "resources.txt"), CliMeasurements.artifacts("jfr"))
        assertEquals(listOf("cli.jfr", "resources.txt", "hotspot-compilation.log"), CliMeasurements.artifacts("jfr+log"))
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `the Linux launcher's CLI with compilation logging leaves a recording and a log the lab reports on`() {
        val dir = Files.createTempDirectory("measurement")
        val cli = dir.resolve(CliMeasurements.CLI)
        Files.createDirectories(cli.parent)
        val javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
        // A shell CLI reads its options as the real start script does: split on spaces, each word one argument.
        Files.writeString(cli, "#!/bin/bash\nexec ${quote(javaExecutable.toString())} \$KLAUSE_CLI_OPTS -Xshare:off -version\n")
        cli.toFile().setExecutable(true)
        val setup = ProcessBuilder("bash", "-c", CliMeasurements.install(dir.toString())).start()
        assertEquals(0, setup.waitFor(), setup.errorStream.bufferedReader().readText())
        val profile = Files.createDirectories(dir.resolve("profile"))
        val builder = ProcessBuilder(cli.toString()).redirectErrorStream(true)
        builder.environment()["EIGNEX_LAB_PROFILE_DIR"] = profile.toString()
        builder.environment()["KLAUSE_CLI_OPTS"] = "-Xmx64m ${CliMeasurements.options(profile.toString(), "jfr+log")}"

        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(0, process.waitFor(), output)
        CliMeasurements.writeManifest(profile, 0, 1, "m", "lab", "cmd", 0, "", null, macOs = false, compilation = "jfr+log")
        val report = CompilationReports.analyze(profile, SourceProvenance(1, 0), WorkerProvenance())
        assertEquals("jfr+log", report.mode)
        assertTrue(report.totals.compilations > 0)
        assertEquals("0 ms", report.coverage.compilationThreshold)
        assertEquals(true, report.log?.complete, report.incomplete.toString())
        assertTrue((report.log?.compilerThreads ?: 0) > 0)
        assertTrue(report.methods.any { it.inlined != null })
        val markers = (report.missing + report.incomplete).map { it.what }
        assertFalse(markers.any { it in setOf("jfr", "hotspot-log", "inlining", "jfr-threshold", "jfr-shutdown", "hotspot-log-parse") }, markers.toString())
        assertTrue(report.provenance.jvm?.jvmArguments.orEmpty().contains("LogCompilation"))
    }

    @Test
    fun `the macOS launcher hands the compilation options to the CLI as they were set`() {
        val dir = Files.createTempDirectory("measurement")
        val cli = macLauncher(dir, "#!/bin/bash\nprintf '%s\\n' \$KLAUSE_CLI_OPTS\n")
        val profile = Files.createDirectories(dir.resolve("profile"))
        val builder = ProcessBuilder(cli.toString()).redirectErrorStream(true)
        builder.environment()["EIGNEX_LAB_PROFILE_DIR"] = profile.toString()
        val options = CliMeasurements.options(profile.toString(), "jfr+log")
        builder.environment()["KLAUSE_CLI_OPTS"] = options

        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(0, process.waitFor(), output)
        assertEquals(options.split(' '), output.trim().lines())
        assertTrue("jdk.Compilation#threshold=0ms" in output && "-XX:LogFile=$profile/hotspot-compilation.log" in output)
        assertTrue("exit=0" in Files.readString(profile.resolve("resources.txt")))
    }

    @Test
    fun `an instrumented measurement's manifest says so and lists the compilation log`() {
        val dir = Files.createTempDirectory("measurement")

        CliMeasurements.writeManifest(dir, 2, 1, "m", "lab", "cmd", 0, "-Xmx1g", null, macOs = false, compilation = "jfr+log")

        val manifest = Files.readString(dir.resolve("measurement.json"))
        assertTrue("\"instrumented\":true" in manifest && "\"compilation\":\"jfr+log\"" in manifest, manifest)
        assertTrue("\"instrumentation\":\"jfr-profile+compilation+hotspot-log\"" in manifest && "hotspot-compilation.log" in manifest, manifest)
        CliMeasurements.writeManifest(dir, 2, 1, "m", "lab", "cmd", 0, "-Xmx1g", null, macOs = false)
        assertTrue("\"instrumented\":false" in Files.readString(dir.resolve("measurement.json")))
    }

    @Test
    fun `a resource measurement manifest binds its record without claiming a recording or allocation samples`() {
        val dir = Files.createTempDirectory("measurement")

        CliMeasurements.writeManifest(dir, 2, 1, "m", "lab", "cmd", 0, "-Xmx1g", "{}", macOs = false, recordsJfr = false)

        val manifest = Files.readString(dir.resolve("measurement.json"))
        assertTrue("\"instrumentation\":\"external-time\"" in manifest, manifest)
        assertTrue("\"allocation\":\"not collected\"" in manifest, manifest)
        assertTrue("\"instrumented\":false" in manifest, manifest)
        assertEquals(listOf("resources.txt"), CliMeasurements.artifacts(null, recordsJfr = false))
        assertTrue("\"artifacts\":[\"resources.txt\",\"solve-record.json\"]" in manifest, manifest)
        assertEquals("{}", Files.readString(dir.resolve("solve-record.json")))
        assertFalse("cli.jfr" in manifest || "hotspot-compilation" in manifest, manifest)
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `the Linux launcher measures with GNU time and the macOS one with BSD time, both valid shell, neither Python`() {
        val linux = CliMeasurements.install("/w", macOs = false)
        val mac = CliMeasurements.install("/w", macOs = true)

        for (text in listOf("test -x '/usr/bin/time'", "'/usr/bin/time' --format='peakRssKiB=%M", "setpriv --pdeathsig KILL")) assertTrue(text in linux, text)
        for (text in listOf("'/usr/bin/time' -l -o", "trap '' TERM HUP INT", "pkill -\$signal -P", "maximum resident set size")) assertTrue(text in mac, text)
        assertFalse("setpriv" in mac || "--format" in mac)
        assertFalse("python" in linux + mac)
        for (script in listOf(linux, mac)) {
            val check = ProcessBuilder("bash", "-n", "-c", script).redirectErrorStream(true).start()
            assertEquals(0, check.waitFor(), check.inputStream.bufferedReader().readText())
        }
    }

    /** A CLI at [dir] running [script] behind the macOS launcher, timed by a stand-in for BSD `time -l -o <file>`. */
    private fun macLauncher(dir: java.nio.file.Path, script: String): java.nio.file.Path {
        val time = dir.resolve("bsd-time")
        // It reports as macOS's does, the maximum resident set size in bytes, and like it ignores what it started ignoring.
        Files.writeString(time, """
            |#!/bin/bash
            |[[ ${'$'}1 == -l && ${'$'}2 == -o ]] || exit 64
            |out=${'$'}3; shift 3
            |status=0; "${'$'}@" || status=${'$'}?
            |printf '        1.25 real         0.50 user         0.25 sys\n%20d  maximum resident set size\n%20d  average shared memory size\n' 3145728 0 > "${'$'}out"
            |exit ${'$'}status
            |""".trimMargin())
        time.toFile().setExecutable(true)
        val cli = dir.resolve(CliMeasurements.CLI)
        Files.createDirectories(cli.parent)
        Files.writeString(cli, script)
        cli.toFile().setExecutable(true)
        val setup = ProcessBuilder("bash", "-c", CliMeasurements.install(dir.toString(), macOs = true, time = time.toString())).start()
        assertEquals(0, setup.waitFor(), setup.errorStream.bufferedReader().readText())
        return cli
    }

    @Test
    fun `the macOS launcher turns BSD time's report into the keys and units GNU time writes`() {
        val dir = Files.createTempDirectory("measurement")
        val cli = macLauncher(dir, "#!/bin/bash\necho \"args: \$*\"\nexit 3\n")
        val profile = Files.createDirectories(dir.resolve("profile"))
        val builder = ProcessBuilder(cli.toString(), "solve", "two words").redirectErrorStream(true)
        builder.environment()["EIGNEX_LAB_PROFILE_DIR"] = profile.toString()

        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(3, process.waitFor(), output)
        assertEquals("args: solve two words", output.trim())
        assertEquals("peakRssKiB=3072\nuserSeconds=0.50\nsystemSeconds=0.25\nwallSeconds=1.25\nexit=3\n",
            Files.readString(profile.resolve("resources.txt")))
        assertFalse(Files.exists(profile.resolve("time.txt")))
    }

    @Test
    fun `the macOS launcher passes SIGTERM on to the CLI and still writes its measurement`() {
        for (group in listOf(false, true)) {
            val dir = Files.createTempDirectory("measurement")
            val cli = macLauncher(dir, "#!/bin/bash\ntrap 'echo stopping; exit 143' TERM\necho ready\nsleep 30 &\nwait\n")
            val profile = Files.createDirectories(dir.resolve("profile"))
            // In a session of its own, as the runner starts a case, so the group can be signalled as the runner does.
            val builder = ProcessBuilder(inSession(cli.toString())).redirectErrorStream(true)
            builder.environment()["EIGNEX_LAB_PROFILE_DIR"] = profile.toString()
            val process = builder.start()
            try {
                val output = process.inputStream.bufferedReader()
                assertEquals("ready", output.readLine())
                // The launcher alone, or its whole group. Process.destroy would also close the CLI's output.
                if (group) signalGroups("TERM", listOf(process.pid()))
                else assertEquals(0, ProcessBuilder("kill", "-TERM", process.pid().toString()).start().waitFor())

                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "group=$group")
                assertEquals(143, process.exitValue(), "group=$group")
                assertEquals("stopping", output.readLine())
                assertTrue("exit=143" in Files.readString(profile.resolve("resources.txt")), "group=$group")
            } finally {
                killGroup(process.pid())
                process.destroyForcibly()
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `a measurement's manifest keeps the record it hashed and says how memory was measured`() {
        val dir = Files.createTempDirectory("measurement")

        CliMeasurements.writeManifest(dir, 2, 1, "m", "lab", "cmd", 0, "-Xmx1g", "{}", macOs = true)

        val manifest = Files.readString(dir.resolve("measurement.json"))
        assertEquals("{}", Files.readString(dir.resolve("solve-record.json")))
        assertTrue("getrusage" in manifest && "\"instance\":\"lab\"" in manifest && "solve-record.json" in manifest, manifest)
    }

    @Test
    fun `retrying a dropped transfer preserves bytes until complete output arrives`() {
        val dir = Files.createTempDirectory("measurement")
        val target = dir.resolve("output.tar")
        val old = byteArrayOf(4, 5, 6)
        Files.write(target, old)
        val failures = ArrayList<Pair<Int, Int?>>()
        var calls = 0

        val attempt = retryFileTransfer(Backoff(3, 0, 0), { false },
            onFailure = { number, error -> failures += number to error.exit }) {
            calls++
            if (calls == 1) {
                receiveFile(ProcessBuilder("sh", "-c", "printf partial; exit 255").start(), target, 5) { false }
            } else {
                assertContentEquals(old, Files.readAllBytes(target))
                receiveFile(ProcessBuilder("sh", "-c", "printf complete").start(), target, 5) { false }
            }
        }

        assertEquals(2, attempt)
        assertEquals(listOf<Pair<Int, Int?>>(1 to 255), failures)
        assertContentEquals("complete".toByteArray(), Files.readAllBytes(target))
        assertFalse(Files.exists(dir.resolve("output.tar.part")))
    }

    @Test
    fun `permanent transfer errors do not reopen result retrieval`() {
        var calls = 0
        val waits = ArrayList<Long>()

        val failure = assertFailsWith<FileTransferFailure> {
            retryFileTransfer(Backoff(3, 1, 1), { false }, sleep = { waits += it }) {
                calls++
                throw FileTransferFailure(1, "remote file unavailable")
            }
        }

        assertEquals(1, calls)
        assertEquals(1, failure.exit)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun `file retrieval exhausts its bounded transport attempts and retains every failure`() {
        val failures = ArrayList<Int>()
        var calls = 0

        val failure = assertFailsWith<FileTransferFailure> {
            retryFileTransfer(Backoff(3, 0, 0), { false },
                onFailure = { number, _ -> failures += number }) {
                calls++
                throw FileTransferFailure(255, "dropped $calls")
            }
        }

        assertEquals(3, calls)
        assertEquals(listOf(1, 2, 3), failures)
        assertEquals("dropped 3", failure.message)
    }

    @Test
    fun `cancellation during transfer backoff starts no further retrieval`() {
        var cancelled = false
        var calls = 0

        val failure = assertFailsWith<IllegalStateException> {
            retryFileTransfer(Backoff(3, 1_000, 1_000), { cancelled }, sleep = { cancelled = true }) {
                calls++
                throw FileTransferFailure(255, "dropped")
            }
        }

        assertEquals(1, calls)
        assertEquals("file transfer cancelled", failure.message)
    }

    @Test
    fun `uncleared cleanup or failure reporting prevents another transfer`() {
        for (cleanup in listOf(false, true)) {
            val failure = FileTransferFailure(255, "dropped")
            val secondary = IllegalStateException(if (cleanup) "cleanup" else "receipt")
            if (cleanup) failure.addSuppressed(secondary)
            var calls = 0

            val observed = assertFailsWith<FileTransferFailure> {
                retryFileTransfer(Backoff(3, 0, 0), { false },
                    onFailure = { _, _ -> if (!cleanup) throw secondary }) {
                    calls++
                    throw failure
                }
            }

            assertEquals(1, calls)
            assertTrue(observed === failure)
            assertTrue(observed.suppressed.single() === secondary)
        }
    }

    @Test
    fun `a complete binary transfer preserves every byte`() {
        val dir = Files.createTempDirectory("measurement")
        val source = dir.resolve("source")
        val target = dir.resolve("recording.jfr")
        val bytes = ByteArray(256) { it.toByte() }
        Files.write(source, bytes)

        receiveFile(ProcessBuilder("cat", source.toString()).start(), target, 5) { false }

        assertContentEquals(bytes, Files.readAllBytes(target))
        assertFalse(Files.exists(dir.resolve("recording.jfr.part")))
    }

    @Test
    fun `a failed transfer preserves a completed artifact`() {
        val dir = Files.createTempDirectory("measurement")
        val target = dir.resolve("recording.jfr")
        val bytes = byteArrayOf(4, 5, 6)
        Files.write(target, bytes)

        val failure = assertFailsWith<FileTransferFailure> {
            receiveFile(ProcessBuilder("sh", "-c", "printf partial; exit 255").start(), target, 5) { false }
        }

        assertEquals(255, failure.exit)
        assertContentEquals(bytes, Files.readAllBytes(target))
        assertFalse(Files.exists(dir.resolve("recording.jfr.part")))
    }

    @Test
    fun `cancelling a transfer stops its process without publishing a partial artifact`() {
        val dir = Files.createTempDirectory("measurement")
        val target = dir.resolve("recording.jfr")
        val process = ProcessBuilder("sleep", "30").start()

        assertFailsWith<IllegalStateException> { receiveFile(process, target, 5) { true } }

        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertFalse(Files.exists(target))
        assertFalse(Files.exists(dir.resolve("recording.jfr.part")))
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `profiling preserves arguments stdout and the CLI exit status`() {
        val dir = Files.createTempDirectory("measurement with spaces")
        val cli = dir.resolve(CliMeasurements.CLI)
        Files.createDirectories(cli.parent)
        Files.writeString(cli, "#!/bin/bash\nprintf '<%s>\\n' \"\$@\"\nexit 7\n")
        cli.toFile().setExecutable(true)
        val setup = ProcessBuilder("bash", "-c", CliMeasurements.install(dir.toString())).start()
        assertEquals(0, setup.waitFor(), setup.errorStream.bufferedReader().readText())
        val profile = dir.resolve("profile")
        Files.createDirectories(profile)
        val builder = ProcessBuilder(cli.toString(), "two words", "dollar\$value", "")
        builder.environment()["EIGNEX_LAB_PROFILE_DIR"] = profile.toString()

        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(7, process.waitFor())
        assertEquals("<two words>\n<dollar\$value>\n<>\n", output)
        val resources = Files.readString(profile.resolve("resources.txt"))
        assertTrue("exit=7" in resources)
        assertTrue(Regex("peakRssKiB=[1-9][0-9]*").containsMatchIn(resources))
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `forcibly stopping the timer also stops its CLI child`() {
        val dir = Files.createTempDirectory("measurement")
        val cli = dir.resolve(CliMeasurements.CLI)
        Files.createDirectories(cli.parent)
        Files.writeString(cli, "#!/bin/bash\necho \$\$\nexec sleep 30\n")
        cli.toFile().setExecutable(true)
        val setup = ProcessBuilder("bash", "-c", CliMeasurements.install(dir.toString())).start()
        assertEquals(0, setup.waitFor(), setup.errorStream.bufferedReader().readText())
        val profile = dir.resolve("profile")
        Files.createDirectories(profile)
        val builder = ProcessBuilder(cli.toString())
        builder.environment()["EIGNEX_LAB_PROFILE_DIR"] = profile.toString()
        val process = builder.start()
        var child: Long? = null
        try {
            child = process.inputStream.bufferedReader().readLine().toLong()
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            val status = java.nio.file.Path.of("/proc/$child/status")
            fun childStopped(): Boolean = try {
                Regex("(?m)^State:\\s+Z").containsMatchIn(Files.readString(status))
            } catch (_: NoSuchFileException) {
                true
            } catch (_: IOException) {
                false
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)
            while (!childStopped() && System.nanoTime() < deadline) Thread.sleep(5)

            assertTrue(childStopped())
        } finally {
            process.destroyForcibly()
            child?.let { ProcessHandle.of(it).ifPresent { handle -> handle.destroyForcibly() } }
        }
    }
}
