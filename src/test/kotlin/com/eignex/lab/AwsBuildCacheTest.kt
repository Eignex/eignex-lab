package com.eignex.lab

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AwsBuildCacheTest {
    @Test
    fun `a failed download never installs partial Gradle files`() = fixture { root ->
        val target = root.resolve("target").apply { mkdirs() }
        val exit = run(root, "restore_lab_cache s3://bucket/cache ${quote(target.path)} gradle region", failedDownload = true)
        assertTrue(exit != 0)
        assertFalse(target.resolve("partial").exists())
    }

    @Test
    fun `a complete archive without solver installs is rejected`() = fixture { root ->
        val content = root.resolve("content").apply { mkdirs() }
        content.resolve("unrelated").writeText("not an installation")
        pack(root, content)
        val target = root.resolve("target").apply { mkdirs() }
        assertTrue(run(root, "restore_lab_cache s3://bucket/cache ${quote(target.path)} install region") != 0)
        assertFalse(target.resolve("unrelated").exists())
    }

    @Test
    fun `complete solver installs restore but a corrupt library does not`() = fixture { root ->
        val content = root.resolve("content").apply { mkdirs() }
        for ((relative, name, main) in listOf(
            Triple("klause-cli/build/install/klause-cli-jvm", "klause-cli", "com/eignex/klause/cli/MainKt.class"),
            Triple("klause-bench/build/install/klause-bench", "klause-bench", "com/eignex/klause/bench/target/BenchCli.class"),
        )) {
            val dist = content.resolve(relative)
            dist.resolve("bin").mkdirs(); dist.resolve("lib").mkdirs()
            dist.resolve("bin/$name").apply { writeText("#!/bin/sh\nCLASSPATH=\$APP_HOME/lib/main.jar\n"); setExecutable(true) }
            ZipOutputStream(dist.resolve("lib/main.jar").outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(main)); zip.write(byteArrayOf(0)); zip.closeEntry()
            }
        }
        pack(root, content)
        val target = root.resolve("target").apply { mkdirs() }
        assertEquals(0, run(root, "restore_lab_cache s3://bucket/cache ${quote(target.path)} install region"))
        content.resolve("klause-cli/build/install/klause-cli-jvm/lib/main.jar").writeText("broken")
        pack(root, content)
        val empty = root.resolve("empty").apply { mkdirs() }
        assertTrue(run(root, "restore_lab_cache s3://bucket/cache ${quote(empty.path)} install region") != 0)
        assertFalse(empty.resolve("klause-cli").exists())
    }

    @Test
    fun `a failed tar never uploads a cache`() = fixture { root ->
        val content = root.resolve("content").apply { mkdirs() }
        assertEquals(0, run(root, "publish_lab_cache s3://bucket/cache ${quote(content.path)} region missing-install"))
        assertFalse(root.resolve("uploaded").exists())
    }

    @Test
    fun `a failed actual build stops setup before cache publication or provenance`() = fixture { root ->
        val tree = root.resolve("worktree").apply { mkdirs() }
        tree.resolve("gradlew").apply { writeText("#!/bin/sh\nexit 42\n"); setExecutable(true) }
        val steps = AwsBuildCache.worktree(tree.path, 2, "s3://bucket/build", "region").joinToString("\n")
        assertEquals(42, run(root, steps, failedDownload = true))
        assertFalse(root.resolve("uploaded").exists())
        assertFalse(tree.resolve("klause-bench/build/provenance.json").exists())
    }

    @Test
    fun `truncated archive never contaminates a dependency cache`() = fixture { root ->
        root.resolve("archive").writeText("not a complete tar archive")
        val target = root.resolve("target").apply { mkdirs() }
        assertTrue(run(root, "restore_lab_cache s3://bucket/cache ${quote(target.path)} gradle region") != 0)
        assertTrue(target.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a Gradle distribution missing bootstrap is rejected before installation`() = fixture { root ->
        val content = root.resolve("content").apply { mkdirs() }
        val lib = content.resolve(".gradle/wrapper/dists/gradle-test/gradle-test/lib").apply { mkdirs() }
        ZipOutputStream(lib.resolve("incomplete.jar").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Other.class")); zip.write(byteArrayOf(0)); zip.closeEntry()
        }
        pack(root, content)
        val target = root.resolve("target").apply { mkdirs() }
        assertTrue(run(root, "restore_lab_cache s3://bucket/cache ${quote(target.path)} gradle region") != 0)
        assertFalse(target.resolve(".gradle").exists())
    }

    @Test
    fun `invalid provenance cannot pass setup`() = fixture { root ->
        val directory = root.resolve("klause-bench/build").apply { mkdirs() }
        directory.resolve("provenance.json").writeText("not json")
        assertTrue(run(root, "validate_lab_provenance ${quote(root.path)}") != 0)
    }

    private fun fixture(block: (java.io.File) -> Unit) {
        val root = Files.createTempDirectory("cache test ").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    private fun pack(root: java.io.File, content: java.io.File) {
        assertEquals(0, ProcessBuilder("tar", "-c", "-C", content.path, "-f", root.resolve("archive").path, ".").start().waitFor())
    }

    private fun run(root: java.io.File, command: String, failedDownload: Boolean = false): Int {
        val bin = root.resolve("bin").apply { mkdirs() }
        bin.resolve("zstd").apply {
            writeText("#!/bin/sh\nif [ \"\$1\" = -dcq ]; then cat \"\$2\"; else cat; fi\n"); setExecutable(true)
        }
        bin.resolve("aws").apply {
            writeText(if (failedDownload) "#!/bin/sh\nprintf partial > \"\$4\"; exit 1\n" else
                "#!/bin/sh\ncase \"\$3\" in s3://*) cp ${quote(root.resolve("archive").path)} \"\$4\";; *) touch ${quote(root.resolve("uploaded").path)};; esac\n")
            setExecutable(true)
        }
        val script = "set -euo pipefail\n" + AwsBuildCache.functions + "\n" + command
        val process = ProcessBuilder("bash", "-c", script).redirectErrorStream(true).apply {
            environment()["PATH"] = bin.path + ":" + System.getenv("PATH")
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor().also { if (it != 0) println(output) }
    }
}
