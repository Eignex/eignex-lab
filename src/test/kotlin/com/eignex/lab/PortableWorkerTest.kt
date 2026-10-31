package com.eignex.lab

import com.eignex.lab.util.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.execution.*
import com.eignex.lab.worker.*
import com.eignex.lab.nomad.*
import com.eignex.lab.web.*
import com.eignex.lab.cli.*

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class PortableWorkerTest {
    @Test fun `consumer caches use the Linux host volume and preserve native or explicit cache homes`() {
        val environment = mapOf("HOME" to "/home/worker", "LAB_WORKER_CACHE_ROOT" to "/lab-cache", "UNRELATED_SECRET" to "private")
        assertEquals("/lab-cache/consumer", workerEnvironment(false, environment)["XDG_CACHE_HOME"])
        assertNull(workerEnvironment(true, environment)["XDG_CACHE_HOME"])
        val input = workerEnvironment(false, environment + mapOf("LAB_SHARED_INPUT_ROOT" to "/inputs", "LAB_CORPUS_MIRROR" to "https://inputs.invalid/packs"))
        assertEquals("true", input["LAB_CORPUS_KEEP_PACKED"])
        assertEquals("https://inputs.invalid/packs", input["LAB_CORPUS_MIRROR"])
        for (mac in listOf(false, true)) {
            assertEquals("/custom", workerEnvironment(mac, environment + ("XDG_CACHE_HOME" to "/custom"))["XDG_CACHE_HOME"])
            assertFalse("UNRELATED_SECRET" in workerEnvironment(mac, environment))
        }
    }

    private val root = Files.createTempDirectory("portable-worker").toAbsolutePath()
    private val supervisorScript = root.resolve("supervisor.py").also { file ->
        javaClass.getResourceAsStream("/nomad/process_supervisor.py")!!.use { Files.copy(it, file) }
    }
    private fun bundle(files: Map<String, String>): Pair<WorkerBundle, ByteArray> {
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            files.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
        } }.toByteArray()
        val manifest = WorkerBundle(MessageDigest.getInstance("SHA-256").digest(bytes).hex(), files.map { (name, text) ->
            InputFile(name, ExecutionStore.digest(text), text.toByteArray().size.toLong(), name.startsWith("bin/"))
        }, Repositories.DEFAULT, "a".repeat(40), if (CliMeasurements.localMacOs) "mac" else "linux", workerArch(), "fixture")
        return manifest to bytes
    }
    private fun <T> service(localLock: Boolean = false, block: (Path) -> T): T {
        val socket = root.resolve("supervisor.sock")
        val command = listOf("python3", supervisorScript.toString(), "--socket", socket.toString()) + if (localLock) listOf("--local-lock", root.resolve("native.lock").toString()) else emptyList()
        val process = ProcessBuilder(command).redirectError(root.resolve("service.err").toFile()).start()
        try {
            val deadline = System.nanoTime() + 5_000_000_000
            while (!Files.exists(socket)) { check(process.isAlive && System.nanoTime() < deadline) { root.resolve("service.err").toFile().readText() }; Thread.sleep(20) }
            return block(socket)
        } finally { process.destroy(); assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)); process.destroyForcibly() }
    }

    @Test fun `generic profile preparation preserves source and publishes progress timings`(): Unit = service { socket ->
        val (bundle, bytes) = bundle(mapOf("bin/profile" to "echo measured"))
        val spec = ExperimentSpec("profile", profile = ProfileMain(script = "bin/profile"))
        val manifest = WorkManifest("prepare", "release", spec, Experiments.arms(spec).map { PlannedArm(it, bundle.commit) },
            emptyList(), listOf(bundle), ResourceBudget(1000, 512), preparationTimeoutSec = 60)
        val trace = PreparationTrace()
        val uploads = mutableMapOf<String, Path>()
        var archivesCreated = 0
        val archiveCache = WorkerArchiveCache(root.resolve("archives")) { tree, zip, bundle, runtime, active ->
            archivesCreated++; archive(tree, zip, bundle, runtime, active)
        }
        val inputCache = WorkerInputCache(root.resolve("inputs"))
        val executor = PreparationExecutor(ProcessSupervisor(socket), VerifiedCache(root.resolve("cache")),
            WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket)), { ByteArrayInputStream(bytes) }, bundle.os, bundle.arch,
            inputCache = inputCache, trace = trace, archives = archiveCache)
        val result = executor.run(manifest, root.resolve("prepare"), { null }, { 80000 }) { file ->
            hashFile(file).also { uploads[it] = file }
        }
        assertEquals(listOf(Experiments.profileProblem(spec)), result.selections.single())
        val prepared = result.bundles.single()
        val source = VerifiedCache(root.resolve("verify")).stage(prepared) { Files.newInputStream(uploads.getValue(prepared.sha256)) }
        assertEquals("echo measured", Files.readString(source.resolve("bin/profile")))
        inputCache.use(prepared, {}, { error("preparation must seed the solve input cache") }) { seeded ->
            assertEquals("echo measured", Files.readString(seeded.resolve("bin/profile")))
            assertTrue(Files.isExecutable(seeded.resolve("bin/profile")))
        }
        val observations = Json.decodeFromString<Map<String, PreparationCacheEvent>>(Files.readString(root.resolve("prepare/preparation-cache.json")))
        assertEquals("miss", observations.getValue("archive").outcome)
        assertEquals(hashFile(root.resolve("prepare/preparation-cache.json")), result.artifacts["preparation/cache.json"])
        val timings = Json.decodeFromString<Map<String, Long>>(Files.readString(root.resolve("prepare/preparation-timings.json")))
        assertTrue(timings.keys.containsAll(setOf("inputs", "workspace", "selection", "archive", "upload")))
        assertTrue(timings.values.all { it >= 0 })
        assertEquals(hashFile(root.resolve("prepare/preparation-timings.json")), result.artifacts["preparation/timings.json"])
        assertEquals("ready", trace.snapshot().stage)
        val repeated = executor.run(manifest, root.resolve("prepare-again"), { null }, { 80000 }) { hashFile(it) }
        assertEquals(prepared.sha256, repeated.bundles.single().sha256)
        assertEquals(1, archivesCreated)
    }

    @Test fun `replay preserves accepted archive bytes without invoking the current archive writer`(): Unit = service { socket ->
        val (bundle, bytes) = bundle(mapOf("bin/tool" to "accepted build"))
        val spec = ExperimentSpec("replay", bench = BenchSpec("bench.json"))
        val arms = Experiments.arms(spec).map { PlannedArm(it, bundle.commit) }
        val case = BenchRuns.pinned(com.eignex.lab.bench.BenchCase("p"), root, root)
        val replay = BenchReplay(mapOf(arms.single().arm.label to com.eignex.lab.bench.BenchPlan(listOf(case))),
            com.eignex.lab.bench.BenchDescriptor("fixture", "accepted build replay"))
        val manifest = WorkManifest("replay", "release", spec, arms, emptyList(), listOf(bundle), ResourceBudget(1000, 2048),
            preparationTimeoutSec = 60, benchReplay = replay)
        val archiveCache = WorkerArchiveCache(root.resolve("archives")) { _, _, _, _, _ -> error("must reuse accepted archive") }
        val executor = PreparationExecutor(ProcessSupervisor(socket), VerifiedCache(root.resolve("cache")),
            WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket)), { ByteArrayInputStream(bytes) },
            bundle.os, bundle.arch, shared = null, archives = archiveCache)
        val result = executor.run(manifest, root.resolve("replay"), { null }, { 80000 }) { file ->
            assertFalse(file.fileName.toString().endsWith(".zip"), "accepted source archive needs no upload")
            hashFile(file)
        }
        assertEquals(bundle, result.bundles.single())
        assertEquals(bundle.sha256, result.artifacts["preparation/${bundle.commit}.zip"])
    }

    @Test fun `multi megabyte bench cases launch through files instead of shell arguments`(): Unit = service { socket ->
        val case = com.eignex.lab.bench.BenchCase("large", mapOf("opaque" to ("literal '$ value'\n" + "x".repeat(4 * 1024 * 1024))),
            resources = com.eignex.lab.bench.BenchResources(timeoutSeconds = 5))
        val encoded = com.eignex.lab.bench.BenchProtocol.json.encodeToString(com.eignex.lab.bench.BenchCase.serializer(), case) + "\n"
        val expected = ExecutionStore.digest(encoded)
        val java = """package fixture;
            import java.nio.file.*;
            import java.security.MessageDigest;
            import java.util.HexFormat;
            public class Bench {
                public static void main(String[] args) throws Exception {
                    byte[] bytes = Files.readAllBytes(Path.of(args[1]));
                    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    if (!sha.equals("$expected")) throw new Exception("frozen case changed");
                    Files.writeString(Path.of(System.getenv("OUT"), "result.json"), "{\"caseId\":\"large\"}");
                }
            }
        """.trimIndent()
        val entry = com.eignex.lab.bench.BenchEntryPoint("fixture.Bench", listOf("build/classes"), listOf("installDist"))
        val (source, bytes) = bundle(mapOf("Bench.java" to java,
            "bench.json" to com.eignex.lab.bench.BenchProtocol.json.encodeToString(com.eignex.lab.bench.BenchEntryPoint.serializer(), entry),
            "gradlew" to "mkdir -p build/classes; javac -d build/classes Bench.java"))
        val built = source.copy(build = WorkerBuild(listOf("installDist")))
        val spec = ExperimentSpec("large", bench = BenchSpec("bench.json"))
        val arms = Experiments.arms(spec).map { PlannedArm(it, source.commit) }
        val manifest = WorkManifest("large", "release", spec, arms,
            listOf(WorkCase(0, 0, BenchRuns.problem(case), null, 0, 5, case)), listOf(built), ResourceBudget(1000, 2048))
        val directory = root.resolve("large-run")
        val result = IterationExecutor(ProcessSupervisor(socket), VerifiedCache(root.resolve("cache")), { ByteArrayInputStream(bytes) },
            "unused", source.os, source.arch, buildCache = WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket)))
            .run(manifest, listOf(0), directory, "fixture", 1, remainingLeaseMs = { 80000 }).cases.single()
        assertEquals(0, result.exitCode, Files.readString(directory.resolve("0.err")))
        assertEquals(encoded, Files.readString(directory.resolve("cases/0/bench-case.json")))
        assertTrue(result.command!!.length < 8192)
        assertEquals("large", BenchRuns.result(result.record)!!.caseId)
    }

    @Test fun `committed benchmark Java and tool prerequisites fail before invoking the adapter`(): Unit = service { socket ->
        for ((index, runtime) in listOf("{\"tools\":[\"definitely-missing-bench-fixture-tool\"]}", "{\"java\":8}").withIndex()) {
            val entry = com.eignex.lab.bench.BenchEntryPoint("fixture.MustNotRun", listOf("classes"), emptyList())
            val manifestJson = Json.parseToJsonElement(com.eignex.lab.bench.BenchProtocol.json.encodeToString(com.eignex.lab.bench.BenchEntryPoint.serializer(), entry)).jsonObject
            val (source, bytes) = bundle(mapOf("bench.json" to kotlinx.serialization.json.JsonObject(manifestJson +
                ("runtime" to Json.parseToJsonElement(runtime))).toString(), "classes/placeholder" to "not a class"))
            val spec = ExperimentSpec("prerequisite-$index", bench = BenchSpec("bench.json"))
            val case = com.eignex.lab.bench.BenchCase("prerequisite", resources = com.eignex.lab.bench.BenchResources(timeoutSeconds = 5))
            val manifest = WorkManifest("prerequisite-$index", "release", spec, Experiments.arms(spec).map { PlannedArm(it, source.commit) },
                listOf(WorkCase(0, 0, BenchRuns.problem(case), null, 0, 5, case)), listOf(source), ResourceBudget(1000, 2048))
            val result = IterationExecutor(ProcessSupervisor(socket), VerifiedCache(root.resolve("prerequisite-cache-$index")), { ByteArrayInputStream(bytes) },
                "unused", source.os, source.arch).run(manifest, listOf(0), root.resolve("prerequisite-$index"), "fixture", 1, remainingLeaseMs = { 80000 }).cases.single()
            assertEquals(69, result.exitCode)
            assertEquals(com.eignex.lab.bench.BenchStatus.FAILED, BenchRuns.result(result.record)!!.status)
            assertFalse(Files.readString(root.resolve("prerequisite-$index/0.err")).contains("Could not find or load main class"))
        }
    }

    @Test fun `cache rejects corruption and traversal before publishing and verifies reuse`() {
        val (manifest, bytes) = bundle(mapOf("bin/tool" to "hello"))
        val cache = VerifiedCache(root.resolve("cache"), 1024 * 1024)
        val first = cache.stage(manifest) { ByteArrayInputStream(bytes) }
        assertEquals("hello", Files.readString(first.resolve("bin/tool")))
        assertEquals(first, cache.stage(manifest) { error("verified cache reused") })
        Files.writeString(first.resolve("bin/tool"), "bad")
        assertEquals("hello", Files.readString(cache.stage(manifest) { ByteArrayInputStream(bytes) }.resolve("bin/tool")))
        assertFailsWith<IllegalArgumentException> { cache.stage(manifest.copy(sha256 = "0".repeat(64))) { ByteArrayInputStream(bytes) } }
        assertFailsWith<IllegalArgumentException> { InputFile("../escape", "a".repeat(64), 1) }
        assertFailsWith<IllegalArgumentException> { cache.stage(manifest.copy(files = listOf(InputFile("huge", "a".repeat(64), Long.MAX_VALUE)))) { error("oversized declaration must fail before download") } }
        val undeclared = manifest.copy(files = listOf(InputFile("other", ExecutionStore.digest("hello"), 5)))
        assertFailsWith<IllegalArgumentException> { cache.stage(undeclared) { ByteArrayInputStream(bytes) } }
    }

    @Test fun `verified corpus inputs may include an empty data or lock file`() {
        val (manifest, bytes) = bundle(mapOf("empty.dzn" to ""))
        val tree = VerifiedCache(root.resolve("empty-corpus")).stage(manifest) { ByteArrayInputStream(bytes) }
        assertEquals(0, Files.size(tree.resolve("empty.dzn")))
        assertEquals(tree, VerifiedCache(root.resolve("empty-corpus")).stage(manifest) { error("verified input reused") })
    }

    @Test fun `approved Git source export excludes credentials and requires source trust`(): Unit = service { socket ->
        val repository = Files.createDirectory(root.resolve("repo"))
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-C", repository.toString()) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText(); assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), output)
            return output.trim()
        }
        git("init", "--initial-branch=main"); git("config", "user.name", "fixture"); git("config", "user.email", "fixture@example.com")
        Files.writeString(repository.resolve("gradlew"), "mkdir -p build; git rev-parse HEAD > build/commit")
        Files.writeString(repository.resolve(".rules"), "committed documentation")
        Files.createSymbolicLink(repository.resolve("AGENTS.md"), Path.of(".rules"))
        val outside = root.resolve("host-secret"); Files.writeString(outside, "must never be read by checkout")
        Files.createSymbolicLink(repository.resolve("outside.link"), outside)
        git("add", "."); git("commit", "-m", "fixture")
        val commit = git("rev-parse", "HEAD")
        git("remote", "add", "origin", "https://private-token@example.com/secret.git")
        var trusted = false
        val inputs = SourceInputs(requireTrusted = { repo, sha -> assertEquals(Repositories.DEFAULT, repo); assertEquals(commit, sha); check(trusted) })
        val output = root.resolve("source.zip")
        assertFailsWith<IllegalStateException> { inputs.publish(repository, Repositories.DEFAULT, commit, "linux", workerArch(), "fixture", WorkerBuild(listOf("installDist")), output) }
        assertFalse(Files.exists(output)); trusted = true
        val os = if (CliMeasurements.localMacOs) "mac" else "linux"
        val manifest = inputs.publish(repository, Repositories.DEFAULT, commit, os, workerArch(), "fixture", WorkerBuild(listOf("installDist")), output)
        assertEquals(listOf("source.git.bundle"), manifest.files.map { it.path })
        val source = VerifiedCache(root.resolve("sources")).stage(manifest) { Files.newInputStream(output) }
        val tree = WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket)).prepare(manifest, source, ResourceBudget(1000, 512), System.getenv()) { 10000 }
        assertEquals(commit, Files.readString(tree.resolve("build/commit")).trim())
        assertFalse(Files.isSymbolicLink(tree.resolve("AGENTS.md")))
        assertEquals(".rules", Files.readString(tree.resolve("AGENTS.md")))
        assertEquals(outside.toString(), Files.readString(tree.resolve("outside.link")))
        val status = ProcessBuilder("git", "-C", tree.toString(), "status", "--porcelain", "--", "AGENTS.md", "outside.link").start()
        assertEquals("", status.inputStream.bufferedReader().readText()); assertEquals(0, status.waitFor())
        assertFalse(Files.readString(tree.resolve(".git/config")).contains("private-token"))
        assertFalse(git("for-each-ref", "--format=%(refname)", "refs/lab-source").isNotBlank())

        val repacked = manifest.copy(sha256 = "0".repeat(64), files = manifest.files.map { it.copy(sha256 = "1".repeat(64)) })
        val reused = WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket))
            .withPrepared(repacked, source, ResourceBudget(1000, 512), System.getenv(), { null }, { 10000 }) { it }
        assertEquals(tree, reused, "transport hashes cannot invalidate an approved commit's identical build recipe")

        // Preparation sends this detached checkout through an archive before the solve allocation uses Git.
        Files.createDirectory(tree.resolve("empty-runtime-directory"))
        assertTrue(Files.isDirectory(tree.resolve(".git/refs/heads")))
        val preparedArchive = root.resolve("prepared.zip")
        val prepared = archive(tree, preparedArchive, manifest) {}
        val restored = VerifiedCache(root.resolve("prepared")).stage(prepared) { Files.newInputStream(preparedArchive) }
        assertTrue(Files.isDirectory(restored.resolve("empty-runtime-directory")))
        val revision = ProcessBuilder("git", "-C", restored.toString(), "rev-parse", "HEAD").redirectErrorStream(true).start()
        assertEquals(commit, revision.inputStream.bufferedReader().readText().trim())
        assertEquals(0, revision.waitFor())
        val tracked = ProcessBuilder("git", "-C", restored.toString(), "ls-files", "--error-unmatch", "gradlew").redirectErrorStream(true).start()
        assertEquals("gradlew", tracked.inputStream.bufferedReader().readText().trim())
        assertEquals(0, tracked.waitFor())
    }

    @Test fun `source builds share their session budget cache once and rebuild corrupt outputs`(): Unit = service { socket ->
        val count = root.resolve("build-count")
        val script = """#!/usr/bin/env bash
            set -eu
            printf 'build\n' >> '${count}'
            [[ "${'$'}GRADLE_USER_HOME" != "${'$'}HOME/.gradle" ]]
            [[ "${'$'}JAVA_TOOL_OPTIONS" == *'-Xmx170m'* ]]
            [[ " ${'$'}* " == *' --max-workers=2 '* ]]
            mkdir -p build
            echo prepared > build/output
        """.trimIndent()
        val (input, bytes) = bundle(mapOf("gradlew" to script))
        val source = VerifiedCache(root.resolve("source")).stage(input) { ByteArrayInputStream(bytes) }
        val bundle = input.copy(build = WorkerBuild(listOf(":cli:installDist")))
        val cache = WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket))
        val budget = ResourceBudget(2000, 512)
        val tree = cache.prepare(bundle, source, budget, System.getenv()) { 10000 }
        assertEquals("prepared", Files.readString(tree.resolve("build/output")).trim())
        assertEquals(tree, WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket)).prepare(bundle, source, budget, System.getenv()) { 10000 })
        assertEquals(1, Files.readAllLines(count).size)
        Files.writeString(tree.resolve("build/output"), "corrupt")
        cache.prepare(bundle, source, budget, System.getenv()) { 10000 }
        assertEquals(2, Files.readAllLines(count).size)
        assertEquals(script, Files.readString(source.resolve("gradlew")))
        assertFailsWith<IllegalArgumentException> { WorkerBuild(listOf("--init-script=untrusted")) }
        assertFailsWith<LeaseLost> { cache.prepare(bundle, source, budget, System.getenv()) { 0 } }
    }

    @Test fun `shared builds single flight across allocations and preserve dependency caches across commits`(): Unit = service { socket ->
        val count = root.resolve("shared-count")
        val script = """set -eu
            echo build >> '$count'
            mkdir -p build
            if test -f "${'$'}GRADLE_USER_HOME/dependency"; then echo warm > build/dependencies; else echo cold > build/dependencies; fi
            echo dependency > "${'$'}GRADLE_USER_HOME/dependency"
            sleep .2
        """.trimIndent()
        val (input, bytes) = bundle(mapOf("gradlew" to script))
        val source = VerifiedCache(root.resolve("inputs")).stage(input) { ByteArrayInputStream(bytes) }
        val built = input.copy(build = WorkerBuild(listOf("installDist")), toolchain = "jdk25:" + "1".repeat(40))
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val jobs = (1..2).map { executor.submit<Path> {
                WorkerBuildCache(root.resolve("shared"), ProcessSupervisor(socket))
                    .withPrepared(built, source, ResourceBudget(1000, 512), System.getenv(), { null }, { 10000 }) { it }
            } }
            assertEquals(jobs[0].get(), jobs[1].get())
            assertEquals(1, Files.readAllLines(count).size)
            val updated = WorkerBuildCache(root.resolve("shared"), ProcessSupervisor(socket))
                .prepare(built.copy(toolchain = "jdk25:" + "2".repeat(40)), source, ResourceBudget(1000, 512), System.getenv()) { 10000 }
            assertEquals(jobs[0].get(), updated)
            assertEquals(1, Files.readAllLines(count).size)
            val next = WorkerBuildCache(root.resolve("shared"), ProcessSupervisor(socket))
                .prepare(built.copy(commit = "b".repeat(40), toolchain = "jdk25"), source, ResourceBudget(1000, 512), System.getenv()) { 10000 }
            assertEquals("warm", Files.readString(next.resolve("build/dependencies")).trim())
            assertEquals(2, Files.readAllLines(count).size)
            val custom = WorkerBuildCache(root.resolve("shared"), ProcessSupervisor(socket))
                .prepare(built.copy(toolchain = "different-toolchain"), source, ResourceBudget(1000, 512), System.getenv()) { 10000 }
            assertNotEquals(updated, custom)
            assertEquals("cold", Files.readString(custom.resolve("build/dependencies")).trim())
            assertEquals(3, Files.readAllLines(count).size)
        } finally { executor.shutdownNow() }
    }

    @Test fun `dependency cache writers exclude other processes across build identities`(): Unit = service { socket ->
        val script = """set -eu
            python3 - <<'PY'
import fcntl, os
from pathlib import Path
home = Path(os.environ['GRADLE_USER_HOME'])
with (home.parent.parent / ('dependencies-' + home.name + '.lock')).open('rb') as lock:
    try:
        fcntl.lockf(lock, fcntl.LOCK_SH | fcntl.LOCK_NB)
    except BlockingIOError:
        pass
    else:
        raise AssertionError('another process can enter the Gradle user home while a build writes it')
(home / 'dependency').write_text('warm')
PY
            mkdir -p build
            cp "${'$'}GRADLE_USER_HOME/dependency" build/result
        """.trimIndent()
        val (input, bytes) = bundle(mapOf("gradlew" to script))
        val source = VerifiedCache(root.resolve("inputs")).stage(input) { ByteArrayInputStream(bytes) }
        val cache = WorkerBuildCache(root.resolve("shared"), ProcessSupervisor(socket))
        for (commit in listOf("a".repeat(40), "b".repeat(40))) {
            val tree = cache.prepare(input.copy(commit = commit, build = WorkerBuild(listOf("installDist"))), source,
                ResourceBudget(1000, 512), System.getenv()) { 10000 }
            assertEquals("warm", Files.readString(tree.resolve("build/result")))
        }
    }

    @Test fun `different builds use separate dependency homes concurrently then reuse idle homes`(): Unit = service { socket ->
        val entered = root.resolve("first-build-entered")
        val release = root.resolve("release-first-build")
        val script = """set -eu
            mkdir -p build
            printf '%s' "${'$'}GRADLE_USER_HOME" > build/home
            if test -f "${'$'}GRADLE_USER_HOME/dependency"; then echo warm > build/state; else echo cold > build/state; fi
            echo dependency > "${'$'}GRADLE_USER_HOME/dependency"
            if test "${'$'}LAB_SOURCE_COMMIT" = '${"a".repeat(40)}'; then
                touch '$entered'
                until test -f '$release'; do sleep .05; done
            fi
        """.trimIndent()
        val (input, bytes) = bundle(mapOf("gradlew" to script))
        val source = VerifiedCache(root.resolve("inputs")).stage(input) { ByteArrayInputStream(bytes) }
        val cache = WorkerBuildCache(root.resolve("shared"), ProcessSupervisor(socket))
        fun build(commit: String) = cache.prepare(input.copy(commit = commit.repeat(40), build = WorkerBuild(listOf("installDist"))),
            source, ResourceBudget(1000, 512), System.getenv()) { 20000 }
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Path> { build("a") }
            val deadline = System.nanoTime() + 5_000_000_000
            while (!Files.exists(entered)) { check(System.nanoTime() < deadline) { "first build did not start" }; Thread.sleep(20) }
            val second = executor.submit<Path> { build("b") }.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertFalse(first.isDone, "second build must complete while the first still owns its cache")
            Files.writeString(release, "")
            val initial = first.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertNotEquals(Files.readString(initial.resolve("build/home")), Files.readString(second.resolve("build/home")))
            val next = build("c")
            assertEquals(Files.readString(initial.resolve("build/home")), Files.readString(next.resolve("build/home")))
            assertEquals("warm", Files.readString(next.resolve("build/state")).trim())
        } finally { Files.writeString(release, ""); executor.shutdownNow() }
    }

    @Test fun `cache eviction keeps pinned trees until their attempt copy finishes`(): Unit = service { socket ->
        val (input, bytes) = bundle(mapOf("gradlew" to "mkdir -p build; head -c 48000 /dev/zero > build/output"))
        val source = VerifiedCache(root.resolve("inputs")).stage(input) { ByteArrayInputStream(bytes) }
        val cache = WorkerBuildCache(root.resolve("shared"), ProcessSupervisor(socket), maxBytes = 65536)
        val budget = ResourceBudget(1000, 512)
        val recipe = input.copy(build = WorkerBuild(listOf("installDist")))
        val trees = (1..3).map { cache.prepare(recipe.copy(commit = it.toString().repeat(40)), source, budget, System.getenv()) { 10000 } }
        cache.withPrepared(recipe.copy(commit = "1".repeat(40)), source, budget, System.getenv(), { null }, { 10000 }) { pinned ->
            cache.maintain(now() + 120000)
            assertTrue(Files.exists(pinned.resolve("build/output")))
            assertTrue(trees.drop(1).any { !Files.exists(it) })
        }
    }

    @Test fun `source builds take their tasks from the committed adapter manifest`(): Unit = service { socket ->
        val manifest = """{"main":"com.eignex.tools.AdapterKt","classpath":["build/lib/*"],"buildTasks":[":tools:prepare",":tools:linkNative"]}"""
        val script = """#!/usr/bin/env bash
            set -eu
            [[ "${'$'}1" == :tools:prepare && "${'$'}2" == :tools:linkNative ]]
            printf '%s\n' "${'$'}@" > invocation.txt
        """.trimIndent()
        val (input, bytes) = bundle(mapOf("gradlew" to script, "tools/lab/bench.json" to manifest))
        val source = VerifiedCache(root.resolve("sources")).stage(input) { ByteArrayInputStream(bytes) }
        val recipe = input.copy(build = WorkerBuild(listOf(":wrong:legacyTask"), benchManifest = "tools/lab/bench.json"))
        val prepared = WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket)).prepare(recipe, source,
            ResourceBudget(1000, 512), System.getenv()) { 10000 }
        val invocation = Files.readAllLines(prepared.resolve("invocation.txt"))
        assertEquals(listOf(":tools:prepare", ":tools:linkNative"), invocation.take(2))
        assertFalse(invocation.any { "legacyTask" in it })
    }

    @Test fun `failed build preserves a bounded diagnostic and never publishes a cache entry`(): Unit = service { socket ->
        val (input, bytes) = bundle(mapOf("gradlew" to "echo expected-compile-failure >&2; exit 3"))
        val source = VerifiedCache(root.resolve("sources")).stage(input) { ByteArrayInputStream(bytes) }
        val budget = ResourceBudget(1000, 512)
        val recorder = PreparationRecorder(ResourceObservation("advisory-native", budget, null, null, "test", "test", "test", emptyMap()), PreparationTrace())
        val failure = assertFailsWith<WorkerBuildFailure> {
            WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket), observeBuild = recorder::build)
                .prepare(input.copy(build = WorkerBuild(listOf("installDist"))), source, budget, System.getenv()) { 10000 }
        }
        val evidence = recorder.snapshot(10000, 10000).build!!
        assertEquals(3, evidence.exitCode)
        assertEquals(0, evidence.signal); assertEquals(false, evidence.runTimedOut)
        assertEquals("compilation", evidence.kind); assertEquals("compilation", evidence.stage)
        assertEquals(input.commit, evidence.sourceCommit)
        assertTrue(evidence.stderr.contains("expected-compile-failure"))
        assertTrue(failure.detail.contains("expected-compile-failure")); assertTrue(failure.detail.contains("exit 3"))
        assertTrue(Files.list(root.resolve("builds")).use { files -> files.noneMatch { Files.isDirectory(it) && it.fileName.toString().matches(SHA256) } })
    }

    @Test fun `long profile command succeeds and retains the literal invocation as an artifact`(): Unit = service { socket ->
        val argument = "x".repeat(76000)
        val build = "#!/usr/bin/env bash\nset -eu\ngit init -q\ngit config user.email fixture@example.com\ngit config user.name fixture\ngit add profile.sh\ngit commit -qm fixture\n"
        val (input, bytes) = bundle(mapOf("profile.sh" to "#!/usr/bin/env bash\n[[ ${'$'}{#1} == 76000 ]]\n", "gradlew" to build))
        val source = input.copy(files = input.files.map { it.copy(executable = true) }, build = WorkerBuild(listOf("fixture")))
        val spec = ExperimentSpec("long-command", profile = ProfileMain(script = "profile.sh", args = listOf(argument)))
        val manifest = WorkManifest("session", "fixture", spec, Experiments.arms(spec).map { PlannedArm(it, "a".repeat(40)) },
            listOf(WorkCase(0, 0, Experiments.profileProblem(spec), null, 0, 10)), listOf(source), ResourceBudget(1000, 512))
        val executor = IterationExecutor(ProcessSupervisor(socket), VerifiedCache(root.resolve("cache")), { ByteArrayInputStream(bytes) }, "unused", source.os, source.arch, WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket)))
        val directory = root.resolve("long-command")
        val result = executor.run(manifest, listOf(0), directory, "node", 1) { 80000 }
        assertEquals(0, result.cases.single().exitCode)
        assertNull(result.cases.single().command)
        assertTrue(Files.readString(directory.resolve("cases/0/command.sh")).contains(argument))
    }

    @Test fun `node local compiler slots bound concurrent cold builds across cache instances`() {
        val cacheRoot = Files.createDirectories(root.resolve("compile-slots"))
        val first = WorkerBuildCache(cacheRoot, ProcessSupervisor(root.resolve("unused")))
        val second = WorkerBuildCache(cacheRoot, ProcessSupervisor(root.resolve("unused")))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit { first.compilationSlot({ null }, { 10000 }, slots = 1) { entered.countDown(); release.await() } }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val b = pool.submit { second.compilationSlot({ null }, { 10000 }, slots = 1) { "compiled" } }
            assertFailsWith<java.util.concurrent.TimeoutException> { b.get(100, java.util.concurrent.TimeUnit.MILLISECONDS) }
            release.countDown(); a.get(2, java.util.concurrent.TimeUnit.SECONDS)
            b.get(2, java.util.concurrent.TimeUnit.SECONDS)
            assertFailsWith<IllegalStateException> { second.compilationSlot({ null }, { 0 }, slots = 1) { error("not admitted") } }
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `native profile deadline bounds preparation and the committed profile process`(): Unit = service { socket ->
        val started = root.resolve("profile-started")
        val script = "#!/usr/bin/env python3\nfrom pathlib import Path\nimport time\nPath('$started').write_text('started')\ntime.sleep(30)\n"
        val build = """#!/usr/bin/env bash
            set -eu
            git init -q
            git config user.email fixture@example.com
            git config user.name fixture
            git add profile.sh
            git commit -qm fixture
        """.trimIndent()
        val (input, bytes) = bundle(mapOf("profile.sh" to script, "gradlew" to build))
        val source = input.copy(files = input.files.map { it.copy(executable = it.path == "profile.sh") }, build = WorkerBuild(listOf("fixture")))
        val spec = ExperimentSpec("deadline", profile = ProfileMain(script = "profile.sh", runTimeoutSec = 3))
        val arms = Experiments.arms(spec).map { PlannedArm(it, "a".repeat(40)) }
        val manifest = WorkManifest("session", "fixture", spec, arms, listOf(WorkCase(0, 0, Experiments.profileProblem(spec), null, 0, 720)), listOf(source), ResourceBudget(1000, 512))
        val executor = IterationExecutor(ProcessSupervisor(socket), VerifiedCache(root.resolve("cache")), { ByteArrayInputStream(bytes) }, "unused",
            source.os, source.arch, WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket)))
        val before = System.nanoTime()
        val result = executor.run(manifest, listOf(0), root.resolve("profile-attempt"), "node", 1) { 80000 }
        assertTrue(Files.exists(started), "committed profile was launched")
        assertTrue(result.runTimedOut)
        assertEquals(124, result.cases.single().exitCode)
        assertTrue(Files.readString(root.resolve("profile-attempt/0.termination")).contains("whole-run"))
        assertTrue(System.nanoTime() - before < 6_000_000_000, "profile exceeded its whole-run budget")
    }

    @Test fun `profile preparation consumes the same deadline and cannot continue into execution`(): Unit = service { socket ->
        val (input, bytes) = bundle(mapOf("gradlew" to "#!/usr/bin/env bash\nexec sleep 30\n"))
        val source = VerifiedCache(root.resolve("source")).stage(input) { ByteArrayInputStream(bytes) }
        val deadline = System.nanoTime() + 500_000_000
        assertFailsWith<RunDeadlineExceeded> {
            WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket)).prepare(input.copy(build = WorkerBuild(listOf("fixture"))),
                source, ResourceBudget(1000, 512), System.getenv(), remainingRunMs = { (deadline - System.nanoTime()) / 1_000_000 }) { 80000 }
        }
        assertTrue(System.nanoTime() < deadline + 2_000_000_000)
    }

    @Test fun `zz repeat`() { repeat(500) { println("ITER $it"); Files.deleteIfExists(root.resolve("escaped.pid")); Files.deleteIfExists(root.resolve("supervisor.sock")); `independent supervisor kills detached children after client connection closes`() } }
    @Test fun `independent supervisor kills detached children after client connection closes`(): Unit = service { socket ->
        val pid = root.resolve("escaped.pid")
        val code = "import subprocess,time; p=subprocess.Popen(['sleep','60'],start_new_session=True); open('${pid}','w').write(str(p.pid)); time.sleep(60)"
        val supervisor = ProcessSupervisor(socket)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val future = executor.submit<Int> { supervisor.run(listOf("python3", "-c", code), root, System.getenv(), root.resolve("out"), root.resolve("err"), 60) { 10000 } }
        try {
            val until = System.nanoTime() + 5_000_000_000
            // Python creates the file before its buffered write lands, so wait for a complete pid.
            var child: Long? = null
            while (child == null) { check(System.nanoTime() < until) { "done=${future.isDone} " + runCatching { future.get() }.exceptionOrNull() + " err=" + runCatching { Files.readString(root.resolve("err")) + Files.readString(root.resolve("service.err")) }.getOrNull() }; child = runCatching { Files.readString(pid).toLongOrNull() }.getOrNull(); if (child == null) Thread.sleep(20) }
            future.cancel(true) // Socket closes exactly as it does on a killed worker; service survives.
            val stopped = System.nanoTime() + 5_000_000_000
            var alive = live(child)
            while (alive && System.nanoTime() < stopped) { Thread.sleep(20); alive = live(child) }
            assertFalse(alive, "detached child survived worker loss")
        } finally { future.cancel(true); executor.shutdownNow() }
    }

    @Test fun `native reservations and the legacy runner share one cross process ownership lock`(): Unit = service(localLock = true) { socket ->
        val path = root.resolve("native.lock")
        val legacy = NativeExecutionLock.tryAcquire(path)!!
        val thread = java.util.concurrent.Executors.newSingleThreadExecutor()
        val waiting = thread.submit<AutoCloseable> { ProcessSupervisor(socket).reserve("job-a") }
        try {
            Thread.sleep(150); assertFalse(waiting.isDone)
            // A failed second lock attempt in this JVM must not drop its first POSIX lock.
            assertNull(NativeExecutionLock.tryAcquire(path)); assertFalse(waiting.isDone)
            legacy.close()
            val first = waiting.get(5, java.util.concurrent.TimeUnit.SECONDS)
            val second = ProcessSupervisor(socket).reserve("job-b")
            try {
                assertNull(NativeExecutionLock.tryAcquire(path))
                first.close(); Thread.sleep(100); assertNull(NativeExecutionLock.tryAcquire(path))
            } finally { first.close(); second.close() }
            val until = System.nanoTime() + 3_000_000_000
            var available: NativeExecutionLock? = null
            while (available == null && System.nanoTime() < until) { available = NativeExecutionLock.tryAcquire(path); if (available == null) Thread.sleep(20) }
            assertNotNull(available).close()
        } finally { waiting.cancel(true); thread.shutdownNow() }
    }

    @Test fun `a completion concurrent with heartbeat preserves its process exit`(): Unit = service { socket ->
        repeat(3) { index ->
            assertEquals(0, ProcessSupervisor(socket).run(listOf("sleep", "1"), root, System.getenv(), root.resolve("out-$index"), root.resolve("err-$index"), 5) { 10000 })
        }
    }

    @Test fun `supervisor distinguishes a child signal from an ordinary exit with the same numeric code`(): Unit = service { socket ->
        val supervisor = ProcessSupervisor(socket)
        var observed: ProcessTermination? = null
        fun run(code: String) = supervisor.run(listOf("python3", "-c", code), root, System.getenv(), root.resolve("signal.out"), root.resolve("signal.err"),
            5, observeTermination = { observed = it }) { 10000 }
        assertEquals(137, run("import os, signal; os.kill(os.getpid(), signal.SIGKILL)"))
        assertEquals(ProcessTermination(137, 9, false), observed)
        assertEquals(137, run("raise SystemExit(137)"))
        assertEquals(ProcessTermination(137, 0, false), observed)
    }

    @Test fun `supervisor reports bounded timeout and worker refuses an expired lease`(): Unit = service { socket ->
        val supervisor = ProcessSupervisor(socket)
        assertEquals(124, supervisor.run(listOf("sleep", "60"), root, System.getenv(), root.resolve("out"), root.resolve("err"), 1, 10) { 10000 })
        assertFailsWith<CancellationException> { supervisor.run(listOf("sleep", "60"), root, System.getenv(), root.resolve("out2"), root.resolve("err2"), 60) { 0 } }
    }

    @Test fun `supervisor stops while clients disconnect before sending requests`(): Unit = service { socket ->
        repeat(20) {
            java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX).use {
                it.connect(java.net.UnixDomainSocketAddress.of(socket))
            }
        }
    }
    private fun live(pid: Long): Boolean {
        if (!ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) return false
        val stat = Path.of("/proc/$pid/stat")
        // A reaped zombie passes through X (EXIT_DEAD) before /proc forgets it; neither state is running.
        return if (!CliMeasurements.localMacOs) runCatching { Files.readString(stat).substringAfterLast(")").trim().first() !in "ZX" }.getOrDefault(false)
        else ProcessBuilder("ps", "-p", pid.toString(), "-o", "stat=").start().let { process ->
            val state = process.inputStream.bufferedReader().readText().trim(); process.waitFor(); state.isNotEmpty() && !state.startsWith("Z")
        }
    }
}
