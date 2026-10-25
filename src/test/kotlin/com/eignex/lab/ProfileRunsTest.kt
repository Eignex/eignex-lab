package com.eignex.lab

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ProfileRunsTest {
    @Test
    fun `a generic JVM main runs on its prepared classpath and records JFR`() {
        val root = Files.createTempDirectory("generic jvm")
        try {
            val source = root.resolve("Main.java")
            source.writeText("""
                package com.eignex.demo;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("OUT"), "received.txt"),
                            System.getProperty("demo.marker") + ":" + args[0]);
                    }
                }
            """.trimIndent())
            assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", root.resolve("classes").toString(), source.toString()))
            val job = root.resolve("job").createDirectories()
            val profile = ProfileMain("com.eignex.demo.Main", args = listOf("literal ' argument"),
                classpath = listOf("classes"), jvmArgs = listOf("-Ddemo.marker=prepared"))
            val command = ProfileRuns.command(root.toString(), "Eignex/skema", profile, 0, "/c", true)
            val process = ProcessBuilder("bash", "-c", command).redirectErrorStream(true).apply {
                environment()["JOB_DIR"] = job.toString()
                environment()["JAVA_HOME"] = System.getProperty("java.home")
            }.start()
            val output = process.inputStream.bufferedReader().readText()

            assertEquals(0, process.waitFor(), output)
            assertEquals("prepared:literal ' argument", job.resolve("cases/0/out/received.txt").readText())
            assertFalse(Files.size(job.resolve("cases/0/out/profile.jfr")) == 0L)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `generic JVM profiles build their own tasks and classpath`() {
        val profile = ProfileMain("com.eignex.skema.profile.MainKt", buildTasks = listOf(":tools:installDist"),
            classpath = listOf("tools/build/install/tools/lib/*"), jvmArgs = listOf("-Xmx1g"))
        val spec = ExperimentSpec("skema", repository = "Eignex/skema", profile = profile)

        Experiments.validate(spec, 4)
        val command = ProfileRuns.command("/work", spec.repository, profile, 0, "/corpus", true)

        assertEquals(listOf(":tools:installDist"), ProfileRuns.buildTasks(spec.repository, profile))
        assertContains(command, "'/work/tools/build/install/tools/lib/*'")
        assertContains(command, "'-Xmx1g'")
        assertContains(command, "StartFlightRecording")
        assertFalse("klause" in command)
    }

    @Test
    fun `profile entry points cannot escape the checkout or inject build options`() {
        for (profile in listOf(
            ProfileMain(script = "../outside.sh"), ProfileMain(script = "/bin/sh"), ProfileMain(script = "./profile.sh"),
            ProfileMain("com.eignex.demo.Main", script = "profile.sh"), ProfileMain(),
            ProfileMain(script = "profile.sh", buildTasks = listOf("--init-script=/tmp/other")),
            ProfileMain("com.eignex.demo.Main", classpath = listOf("../lib/*")),
            ProfileMain("com.eignex.demo.Main"),
            ProfileMain(script = "profile.sh", args = listOf("--output", "/tmp/lost"), outputArgument = "--output"),
        )) assertFailsWith<IllegalArgumentException> { ProfileRuns.validate("Eignex/koblas", profile) }
    }

    @Test
    fun `a committed capture script retains its report and receives literal arguments`() {
        val root = Files.createTempDirectory("profile run ")
        try {
            val repo = root.resolve("repo").createDirectories()
            val job = root.resolve("job").createDirectories()
            val script = repo.resolve("capture.sh")
            script.writeText("#!/usr/bin/env bash\nset -eu\nprintf '%s\\n' \"\$1\" > \"\$OUT/argument\"\nshift\n[[ \$1 == --output ]]\nmkdir -p \"\$2\"\nprintf '%s' \"\$LAB_REPOSITORY\" > \"\$2/metadata.txt\"\n")
            script.toFile().setExecutable(true)
            check(gitIn(repo.toFile(), listOf("init", "-q", "-b", "main")).first == 0)
            check(gitIn(repo.toFile(), listOf("add", "capture.sh")).first == 0)
            val arg = "literal \$(touch pwned) ' with spaces"
            val command = ProfileRuns.command(repo.toString(), "Eignex/koblas",
                ProfileMain(script = "capture.sh", args = listOf(arg), outputArgument = "--output"), 0, "/corpus", true)
            val process = ProcessBuilder("bash", "-c", command).redirectErrorStream(true)
                .apply { environment()["JOB_DIR"] = job.toString() }.start()
            val output = process.inputStream.bufferedReader().readText()

            assertEquals(0, process.waitFor(), output)
            assertEquals(arg, job.resolve("cases/0/out/argument").readText().trimEnd())
            assertEquals("Eignex/koblas", job.resolve("cases/0/out/report/metadata.txt").readText())
            assertFalse(Files.exists(repo.resolve("pwned")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `untracked executables and symlinks outside the repository do not run`() {
        val root = Files.createTempDirectory("profile source")
        try {
            val repo = root.resolve("repo").createDirectories()
            val job = root.resolve("job").createDirectories()
            val outside = root.resolve("outside.sh")
            outside.writeText("#!/bin/sh\ntouch \"\$OUT/ran\"\n")
            outside.toFile().setExecutable(true)
            check(gitIn(repo.toFile(), listOf("init", "-q")).first == 0)
            Files.copy(outside, repo.resolve("untracked.sh"))
            Files.createSymbolicLink(repo.resolve("escape.sh"), outside)
            check(gitIn(repo.toFile(), listOf("add", "escape.sh")).first == 0)
            for (script in listOf("untracked.sh", "escape.sh")) {
                val command = ProfileRuns.command(repo.toString(), "Eignex/koblas", ProfileMain(script = script), 0, "/c", true)
                val process = ProcessBuilder("bash", "-c", command).redirectErrorStream(true)
                    .apply { environment()["JOB_DIR"] = job.toString() }.start()
                process.inputStream.bufferedReader().readText()
                assertFalse(process.waitFor() == 0)
                assertFalse(Files.exists(job.resolve("cases/0/out/ran")))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
