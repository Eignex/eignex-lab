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

    /** Runs [command] with an `aws` that keeps the bucket in `root/s3` and counts its requests in `root/requests`. */
    private fun withBucket(root: java.io.File, command: String): Int {
        val bin = root.resolve("bin").apply { mkdirs() }
        val s3 = root.resolve("s3").apply { mkdirs() }
        bin.resolve("aws").apply {
            writeText("""#!/bin/bash
                |echo "${'$'}2 ${'$'}3" >> ${quote(root.resolve("requests").path)}
                |local() { echo ${quote(s3.path)}/"${'$'}{1#s3://}"; }
                |case "${'$'}2" in
                |  cp) if [[ "${'$'}3" == s3://* ]]; then src=${'$'}(local "${'$'}3"); [ -f "${'$'}src" ] || exit 1
                |        if [ "${'$'}4" = - ]; then cat "${'$'}src"; else cp "${'$'}src" "${'$'}4"; fi
                |      else dst=${'$'}(local "${'$'}4"); mkdir -p "${'$'}(dirname "${'$'}dst")"; cp "${'$'}3" "${'$'}dst"; fi ;;
                |  sync) src=${'$'}(local "${'$'}3"); [ -d "${'$'}src" ] && cp -r "${'$'}src/." "${'$'}4" ;;
                |esac
                |""".trimMargin())
            setExecutable(true)
        }
        val script = "set -euo pipefail\n" + AwsBuildCache.functions + "\n" + command
        val process = ProcessBuilder("bash", "-c", script).redirectErrorStream(true).apply {
            environment()["PATH"] = bin.path + ":" + System.getenv("PATH")
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor().also { if (it != 0) println(output) }
    }

    @Test
    fun `the corpus moves as one pack per collection, and only a changed one moves again`() = fixture { root ->
        val first = root.resolve("first").apply { mkdirs() }
        first.resolve("suite-a/x").mkdirs(); first.resolve("suite-a/x/p1.zst").writeText("one")
        first.resolve("suite-a/.plain").mkdirs(); first.resolve("suite-a/.plain/p1").writeText("staging")
        first.resolve("suite-b").mkdirs(); first.resolve("suite-b/p2.zst").writeText("two")
        assertEquals(0, withBucket(root, "push_lab_corpus bucket region ${quote(first.path)}"))
        assertTrue(root.resolve("s3/bucket/corpus-packs/suite-a.tar").isFile())

        val second = root.resolve("second").apply { mkdirs() }
        assertEquals(0, withBucket(root, "pull_lab_corpus bucket region ${quote(second.path)}"))
        assertEquals("one", second.resolve("suite-a/x/p1.zst").readText())
        assertEquals("two", second.resolve("suite-b/p2.zst").readText())
        assertFalse(second.resolve("suite-a/.plain").exists())

        first.resolve("suite-b/p3.zst").writeText("three")
        assertEquals(0, withBucket(root, "push_lab_corpus bucket region ${quote(first.path)}"))
        root.resolve("requests").delete()
        assertEquals(0, withBucket(root, "pull_lab_corpus bucket region ${quote(second.path)}"))
        // The index, then only suite-b's pack: suite-a is unchanged.
        assertEquals(listOf("cp s3://bucket/corpus-packs/index.tsv", "cp s3://bucket/corpus-packs/suite-b.tar"),
            root.resolve("requests").readLines())
        assertEquals("three", second.resolve("suite-b/p3.zst").readText())
    }

    @Test
    fun `before any pack exists the corpus comes down file by file`() = fixture { root ->
        root.resolve("s3/bucket/corpus/suite-a").mkdirs()
        root.resolve("s3/bucket/corpus/suite-a/p.zst").writeText("old")
        val target = root.resolve("target").apply { mkdirs() }
        assertEquals(0, withBucket(root, "pull_lab_corpus bucket region ${quote(target.path)}"))
        assertEquals("old", target.resolve("suite-a/p.zst").readText())
    }
}
