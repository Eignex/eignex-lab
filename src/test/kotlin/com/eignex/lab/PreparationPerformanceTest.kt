package com.eignex.lab

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Whole preparations, rather than a ZIP microbenchmark. Explicit task keeps the ~2 GiB fixture off ordinary tests. */
@Tag("preparation-performance")
class PreparationPerformanceTest {
    @Test fun `representative warm preparations reuse real input build archive and uploaded artifacts`() {
        val parent = Path.of(requireNotNull(System.getenv("LAB_PREPARATION_PERF_ROOT")) { "set LAB_PREPARATION_PERF_ROOT to a scratch directory" })
        Files.createDirectories(parent)
        require(Files.getFileStore(parent).usableSpace >= 4L * 1024 * 1024 * 1024) { "performance fixture requires 4 GiB free" }
        val root = Files.createTempDirectory(parent, "preparation-perf-")
        val snapshots = linkedMapOf<String, PreparationProgress>()
        val report = parent.resolve("preparation-performance.json")
        val serviceErrors = root.resolve("supervisor.err")
        val socket = root.resolve("supervisor.sock")
        var process: Process? = null
        var server: HttpServer? = null
        try {
            val source = Files.createDirectory(root.resolve("source"))
            val calls = root.resolve("build-calls")
            val random = Random(42)
            fun content(relative: String, bytes: Long) {
                val path = source.resolve(relative); Files.createDirectories(path.parent)
                Files.newOutputStream(path).use { output ->
                    val buffer = ByteArray(64 * 1024); var remaining = bytes
                    while (remaining > 0) { random.nextBytes(buffer); val count = minOf(remaining, buffer.size.toLong()).toInt(); output.write(buffer, 0, count); remaining -= count }
                }
            }
            // Similar shape to the measured native distribution: thousands of source files, large JARs and a Git pack.
            repeat(4090) { content("src/p${it / 64}/File$it.kt", 8 * 1024) }
            repeat(4) { content("tool/build/install/tool/lib/dependency$it.jar", 32L * 1024 * 1024) }
            content(".git/objects/pack/fixture.pack", 96L * 1024 * 1024)
            Files.writeString(source.resolve("gradlew"), "#!/bin/bash\nprintf 'build\\n' >> '${calls.toString().replace("'", "'\\''")}'\n")
            val identity = WorkerBundle("a".repeat(64), listOf(InputFile("placeholder", "b".repeat(64), 0)),
                Repositories.DEFAULT, "c".repeat(40), if (CliMeasurements.localMacOs) "mac" else "linux", workerArch(), "fixture")
            val sourceZip = root.resolve("source.zip")
            val bundle = archive(source, sourceZip, identity) {}.copy(build = WorkerBuild(listOf("fixture")))
            assertEquals(4096, bundle.files.size)
            assertTrue(bundle.files.sumOf { it.bytes } > 250L * 1024 * 1024)
            val script = root.resolve("supervisor.py")
            javaClass.getResourceAsStream("/nomad/process_supervisor.py")!!.use { Files.copy(it, script) }
            process = ProcessBuilder("python3", script.toString(), "--socket", socket.toString()).redirectError(serviceErrors.toFile()).start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!Files.exists(socket)) { check(process.isAlive && System.nanoTime() < deadline) { Files.readString(serviceErrors) }; Thread.sleep(20) }
            val supervisor = ProcessSupervisor(socket)
            val spec = ExperimentSpec("representative-preparation", profile = ProfileMain(script = "gradlew"))
            val manifest = WorkManifest("prepare", "release", spec, Experiments.arms(spec).map { PlannedArm(it, bundle.commit) },
                emptyList(), listOf(bundle), ResourceBudget(1000, 512), preparationTimeoutSec = 600)
            val downloads = AtomicInteger(); val uploadPuts = AtomicInteger()
            val artifacts = LocalArtifactStore(root.resolve("coordinator-artifacts"))
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/worker/v1/sessions/performance/uploads/") { exchange ->
                try {
                    val sha = exchange.requestURI.path.substringAfterLast('/')
                    val offset = if (exchange.requestMethod == "GET") artifacts.offset(1, sha) else {
                        uploadPuts.incrementAndGet()
                        val query = exchange.requestURI.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
                        artifacts.put(1, sha, query.getValue("bytes").toLong(), query.getValue("offset").toLong(), exchange.requestBody)
                    }
                    val bytes = """{"offset":$offset}""".toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
                } finally { exchange.close() }
            }
            server.start()
            val signedIdentity = root.resolve("identity"); Files.writeString(signedIdentity, "performance-fixture")
            val owner = AttemptOwner("performance", "allocation", "preparation", 1, "fixture")
            fun prepare(name: String): PreparationProgress {
                val trace = PreparationTrace()
                // Reconstruct helpers between passes to exercise receipts on disk, not only in-memory state.
                val builds = WorkerBuildCache(root.resolve("builds"), supervisor)
                val inputs = WorkerInputCache(root.resolve("inputs"), builds = builds)
                val archiveCache = WorkerArchiveCache(root.resolve("archives"))
                val worker = WorkerSession(URI.create("http://127.0.0.1:${server.address.port}"), "performance", "token", "allocation",
                    signedIdentity, "release", root, socket, "unused")
                val executor = PreparationExecutor(supervisor, VerifiedCache(root.resolve("fallback-inputs")), builds,
                    { downloads.incrementAndGet(); Files.newInputStream(sourceZip) }, bundle.os, bundle.arch, shared = null,
                    inputCache = inputs, privateWorkspaces = WorkerWorkspaces(root.resolve("workspaces-$name")), trace = trace, archives = archiveCache)
                val result = executor.run(manifest, root.resolve(name), { null }, { 700_000 }) { file ->
                    worker.upload(file, owner, reusable = file.fileName.toString().endsWith(".zip"), trace = trace)
                }
                assertEquals(1, result.bundles.size)
                val snapshot = trace.snapshot(); snapshots[name] = snapshot
                Files.writeString(report, Json.encodeToString(snapshots))
                println("PREPARATION_PERFORMANCE $name ${Json.encodeToString(snapshot)}")
                return snapshot
            }
            prepare("cold")
            assertEquals(1, downloads.get())
            fun archiveMetadata(): Map<String, String> = Files.walk(root.resolve("archives")).use { paths ->
                paths.filter { it.fileName.toString() == "archive.zip" }.toList().associate { zip ->
                    val attrs = Files.readAttributes(zip, java.nio.file.attribute.BasicFileAttributes::class.java)
                    root.relativize(zip).toString() to "${attrs.fileKey()}:${attrs.lastModifiedTime()}:${attrs.size()}"
                }
            }
            val originalArchives = archiveMetadata(); assertEquals(1, originalArchives.size)
            assertEquals(1, Files.readAllLines(calls).size)
            repeat(2) { pass ->
                val beforePuts = uploadPuts.get()
                val warm = prepare("warm-${pass + 1}")
                assertEquals("bypass", warm.caches["input"]?.outcome, "a verified build must bypass source staging")
                assertEquals("build-hit", warm.caches["input"]?.reason)
                assertEquals(0, warm.caches["input"]?.checkedFiles, "source acquisition performed verification work")
                for (cache in listOf("input", "prepared-input", "build", "archive")) {
                    val event = assertNotNull(warm.caches[cache], "missing $cache observation")
                    assertEquals(if (cache == "input") "bypass" else "hit", event.outcome, "$cache must reuse prepared work")
                    assertEquals(0, event.hashedFiles, "$cache unexpectedly rehashed file contents")
                    assertEquals(0L, event.hashedBytes, "$cache unexpectedly reread file contents")
                    assertEquals(0, event.misses, "$cache had an earlier miss hidden by a later hit")
                    if (cache != "input") assertTrue(event.checkedFiles >= 4096,
                        "$cache must report verification of the representative tree, rather than default zero telemetry")
                }
                assertEquals("hit", warm.caches["upload"]?.outcome, "prepared ZIP must already be on coordinator")
                assertEquals(1, downloads.get(), "warm preparation downloaded inputs")
                assertEquals(originalArchives, archiveMetadata(), "warm preparation rebuilt or replaced an archive")
                assertEquals(1, Files.readAllLines(calls).size, "warm preparation invoked build command")
                assertFalse(warm.timings.containsKey("compilation"), "warm preparation entered compilation")
                assertFalse(warm.timings.containsKey("checkout"), "warm preparation checked out sources")
                // Only small timing/cache sidecars may be new. Any repeated archive PUT is caught by its cache event above.
                assertTrue(uploadPuts.get() - beforePuts <= 8, "warm preparation unexpectedly streamed large uploads")
            }
        } finally {
            server?.stop(0)
            process?.let { it.destroy(); if (!it.waitFor(10, TimeUnit.SECONDS)) { it.destroyForcibly(); it.waitFor(10, TimeUnit.SECONDS) } }
            deleteTree(root)
        }
    }
}
