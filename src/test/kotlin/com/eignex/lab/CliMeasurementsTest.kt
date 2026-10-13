package com.eignex.lab

import java.nio.file.Files
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
        builder.environment()["KLAUSE_LAB_PROFILE_DIR"] = profile.toString()
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
        builder.environment()["KLAUSE_LAB_PROFILE_DIR"] = profile.toString()

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
        builder.environment()["KLAUSE_LAB_PROFILE_DIR"] = profile.toString()
        val process = builder.start()
        var child: Long? = null
        try {
            child = process.inputStream.bufferedReader().readLine().toLong()
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            val status = java.nio.file.Path.of("/proc/$child/status")
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)
            while (Files.exists(status) && !Regex("(?m)^State:\\s+Z").containsMatchIn(Files.readString(status)) &&
                System.nanoTime() < deadline) Thread.sleep(5)

            assertTrue(!Files.exists(status) || Regex("(?m)^State:\\s+Z").containsMatchIn(Files.readString(status)))
        } finally {
            process.destroyForcibly()
            child?.let { ProcessHandle.of(it).ifPresent { handle -> handle.destroyForcibly() } }
        }
    }
}
