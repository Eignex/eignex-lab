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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    @Test fun `native startup manifests require protocol eight`() {
        val (input, _) = bundle(mapOf("fixture" to "bytes"))
        val spec = ExperimentSpec("capture", bench = BenchSpec("bench.json"), measurement = Measurement.NATIVE_STARTUP)
        val manifest = WorkManifest("capture", "release", spec, Experiments.arms(spec).map { PlannedArm(it, input.commit) },
            emptyList(), listOf(input), ResourceBudget(1000, 512), preparationTimeoutSec = 30)
        assertEquals(8, manifest.schemaVersion)
        assertFailsWith<IllegalArgumentException> { manifest.copy(schemaVersion = 7) }
    }

    @Test fun `native startup wrapper preserves literal arguments and case exit`(): Unit = service { socket ->
        val marker = root.resolve("native-root.pid")
        val command = NativeStartupCapture.wrap(listOf("bash", "-c", "printf '%s' \"\$1\"; exit 7", "fixture", "literal argument"), marker)
        val exit = ProcessSupervisor(socket).run(command, root, System.getenv(), root.resolve("native.out"), root.resolve("native.err"), 5) { 80000 }
        assertEquals(7, exit)
        assertEquals("literal argument", Files.readString(root.resolve("native.out")))
        assertTrue(Files.readString(marker).trim().toLong() > 0)
    }

    @Test fun `native startup observation cannot extend the supervised case deadline`(): Unit = service { socket ->
        val supervisor = ProcessSupervisor(socket)
        val output = root.resolve("native-evidence")
        val deadline = System.nanoTime() + 1_000_000_000
        val capture = NativeStartupCapture(listOf("sleep", "30"), root, output, root,
            NativeStartupCapture.Binding("session", 0, 1, "worker", "source"), supervisor,
            { ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0) }, true, Path.of("/usr/bin/true"))
        val exit = capture.use {
            supervisor.run(it.argv, root, System.getenv(), root.resolve("timeout.out"), root.resolve("timeout.err"), 1, graceMs = 0) { 80000 }
        }
        assertEquals(124, exit)
        assertTrue(System.nanoTime() < deadline + 3_000_000_000)
        val receipt = Json.decodeFromString<NativeStartupCapture.Receipt>(Files.readString(output.resolve("status.json")))
        assertEquals("no-matching-query-observed", receipt.outcome)
    }

    @Test fun `native sampling captures an owned live process without extending its lifetime`() {
        if (!CliMeasurements.localMacOs) return
        service { socket ->
            val target = ProcessBuilder("/bin/sleep", "10").start()
            val identity = target.info().startInstant().orElseThrow()
            val report = root.resolve("native-sample.txt")
            try {
                val exit = ProcessSupervisor(socket).run(NativeStartupCapture.sampleCommand(Path.of("/usr/bin/sample"), target.pid(), report),
                    root, System.getenv(), root.resolve("sample.out"), root.resolve("sample.err"), 2, graceMs = 0) { 80000 }
                assertEquals(0, exit, Files.readString(root.resolve("sample.err")))
                assertTrue(Files.size(report) in 1..1024L * 1024)
                assertTrue(Files.readString(report).contains("sleep"))
                assertEquals(identity, target.info().startInstant().orElseThrow())
                assertTrue(target.isAlive)
                val evidence = Path.of("build/native-startup-tests")
                Files.createDirectories(evidence)
                Files.copy(report, evidence.resolve("owned-process.sample.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                Files.writeString(evidence.resolve("owned-process.json"), """{"fixture":"owned sleep process, not a MiniZinc reliability run","pid":${target.pid()},"startedAt":"$identity","samplerExit":$exit,"nativeSampleRetained":true}""")
            } finally {
                target.destroyForcibly()
                target.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["success", "failure", "native"])
    fun `native startup observer verifies its supervised executable and sampler exit`(sampler: String) {
        val nativeSampler = sampler == "native"
        if (nativeSampler && !CliMeasurements.localMacOs) return
        val samplerExit = if (sampler == "failure") 7 else 0
        service { socket ->
            // Sampling a suspended JVM can spend the entire sampler budget processing JDK symbols.
            // A tiny native executable exercises the same ownership checks without that unrelated work.
            val executable = root.resolve("minizinc")
            val source = root.resolve("observer.c")
            Files.writeString(source, """
                #include <string.h>
                #include <unistd.h>
                int main(int argc, char **argv) {
                    if (argc != 2 || strcmp(argv[1], "--version") != 0) return 2;
                    for (;;) pause();
                }
            """.trimIndent())
            val compilerLog = root.resolve("observer-compiler.log")
            val compiler = ProcessBuilder("/usr/bin/cc", "-O0", source.toString(), "-o", executable.toString())
                .redirectErrorStream(true).redirectOutput(compilerLog.toFile()).start()
            try {
                assertTrue(compiler.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "native observer fixture compilation timed out")
                assertEquals(0, compiler.exitValue(), Files.readString(compilerLog))
            } finally { compiler.destroyForcibly() }
            val supervisor = ProcessSupervisor(socket)
            val output = root.resolve("observer-evidence")
            val binding = NativeStartupCapture.Binding("observer-fixture", 0, 1, "worker", "source")
            // Exercise receipt verification deterministically on both platforms, plus the real
            // sampler against the small native fixture on macOS.
            val sampleTool = if (nativeSampler) Path.of("/usr/bin/sample") else root.resolve("sample-fixture").also {
                Files.writeString(it, """#!/bin/bash
                test "${'$'}#" -eq 5 && test "${'$'}2" = 1 && test "${'$'}3" = 10 && test "${'$'}4" = -file || exit 2
                printf 'sampled pid=%s\n' "${'$'}1" > "${'$'}5"
                exit $samplerExit
                """.trimIndent())
                assertTrue(it.toFile().setExecutable(true))
            }
            val deadline = System.nanoTime() + 6_000_000_000
            val remaining = { ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0) }
            val capture = NativeStartupCapture(listOf(executable.toString(), "--version"), root, output, root,
                binding, supervisor, remaining, true, sampleTool)
            val exit = capture.use {
                supervisor.run(it.argv, root, System.getenv(), root.resolve("observer.out"), root.resolve("observer.err"),
                    4, graceMs = 0, runRemainingMs = remaining(), remainingLeaseMs = { 80000 })
            }
            val evidence = Files.createDirectories(Path.of("build/native-startup-tests/observer-$sampler"))
            Files.list(output).use { entries -> entries.forEach {
                Files.copy(it, evidence.resolve(it.fileName.toString()), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } }
            Files.copy(root.resolve("observer.err"), evidence.resolve("target.stderr"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            Files.writeString(evidence.resolve("fixture.txt"),
                "Native fixture waiting in pause() with $sampler sampler; validates observer ownership and receipt verification.\n")
            assertEquals(124, exit, Files.readString(root.resolve("observer.err")))
            val receiptFile = output.resolve("capture-1.json")
            assertTrue(Files.isRegularFile(receiptFile), Files.readString(output.resolve("status.json")))
            val receipt = Json.decodeFromString<NativeStartupCapture.Receipt>(Files.readString(receiptFile))
            assertEquals(if (samplerExit == 0) "captured" else "sample-unavailable", receipt.outcome, receipt.toString())
            assertEquals(samplerExit, receipt.exitCode, receipt.toString())
            assertEquals(samplerExit == 0, receipt.sampleVerified)
            assertEquals(binding, receipt.binding)
            val target = assertNotNull(receipt.target)
            assertEquals(executable.toRealPath().toString(), target.executable)
            assertEquals("--version", target.option)
            assertTrue(Files.size(output.resolve(assertNotNull(receipt.sample))) in 1..1024L * 1024)
            if (!nativeSampler) assertEquals("sampled pid=${target.pid}\n", Files.readString(output.resolve(receipt.sample)))
        }
    }

    @Test fun `packed Git inputs require protocol seven and materialize the original shallow source once`(): Unit = service { socket ->
        val repository = Files.createDirectory(root.resolve("pack-repository"))
        fun command(directory: Path, vararg args: String): Pair<Int, String> {
            val process = ProcessBuilder(listOf("git", "-C", directory.toString()) + args).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText().trim()
            assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS))
            return process.exitValue() to text
        }
        fun git(directory: Path, vararg args: String): String {
            val (exit, text) = command(directory, *args); assertEquals(0, exit, text); return text
        }
        git(repository, "init", "--template=", "--initial-branch=main")
        git(repository, "config", "user.name", "fixture"); git(repository, "config", "user.email", "fixture@example.com")
        Files.writeString(repository.resolve("removed.txt"), "old source")
        git(repository, "add", "."); git(repository, "commit", "-m", "old source")
        val parent = git(repository, "rev-parse", "HEAD")
        Files.delete(repository.resolve("removed.txt"))
        Files.writeString(repository.resolve("café.txt"), "current source")
        Files.writeString(repository.resolve("tool"), "executable"); repository.resolve("tool").toFile().setExecutable(true)
        val outside = root.resolve("outside-secret"); Files.writeString(outside, "not followed")
        Files.createSymbolicLink(repository.resolve("outside.link"), outside)
        git(repository, "add", "-A"); git(repository, "commit", "-m", "selected source")
        val commit = git(repository, "rev-parse", "HEAD")
        val source = Files.createDirectory(root.resolve("pack-inputs"))
        git(source, "init", "--template=")
        git(source, "config", "core.symlinks", "false")
        git(source, "-c", "fetch.unpackLimit=1", "fetch", "--depth=1", "--no-tags", "--no-write-fetch-head", repository.toUri().toString(), commit)
        Files.writeString(source.resolve(".git/HEAD"), "$commit\n")
        val ref = source.resolve(".git/refs/lab-source/$commit"); Files.createDirectories(ref.parent); Files.writeString(ref, "$commit\n")
        Files.writeString(source.resolve(PackedGitSource.MARKER), PackedGitSource.receipt(commit))
        val files = Files.walk(source).use { paths -> paths.filter { Files.isRegularFile(it) }.map {
            InputFile(source.relativize(it).toString().replace('\\', '/'), hashFile(it), Files.size(it), Files.isExecutable(it))
        }.toList() }
        val bundle = WorkerBundle("b".repeat(64), files, Repositories.DEFAULT, commit,
            if (CliMeasurements.localMacOs) "mac" else "linux", workerArch(), "fixture", build = WorkerBuild(gitBundle = PackedGitSource.MARKER))
        assertTrue(files.all { it.path.startsWith(".git/") }); assertFalse(Files.exists(source.resolve("café.txt")))
        val spec = ExperimentSpec("packed-source", bench = BenchSpec("bench.json"))
        val manifest = WorkManifest("packed", "release", spec, Experiments.arms(spec).map { PlannedArm(it, commit) },
            emptyList(), listOf(bundle), ResourceBudget(1000, 512), preparationTimeoutSec = 60)
        assertEquals(7, manifest.schemaVersion)
        assertFailsWith<IllegalArgumentException> { manifest.copy(schemaVersion = 6) }
        assertFailsWith<IllegalArgumentException> { bundle.copy(commit = parent) }
        val tree = WorkerBuildCache(root.resolve("pack-builds"), ProcessSupervisor(socket)).prepare(bundle, source,
            ResourceBudget(1000, 512), System.getenv()) { 10000 }
        assertEquals("current source", Files.readString(tree.resolve("café.txt")))
        assertTrue(Files.isExecutable(tree.resolve("tool")))
        assertEquals(outside.toString(), Files.readString(tree.resolve("outside.link"))); assertFalse(Files.isSymbolicLink(tree.resolve("outside.link")))
        assertEquals(commit, git(tree, "rev-parse", "HEAD")); assertEquals("1", git(tree, "rev-list", "--count", "HEAD"))
        assertEquals("true", git(tree, "rev-parse", "--is-shallow-repository")); assertNotEquals(0, command(tree, "cat-file", "-e", parent).first)
        assertFalse(Files.exists(tree.resolve("removed.txt"))); assertFalse(Files.exists(source.resolve("café.txt")))
    }

    @Test fun `preparation builds on its source host but publishes only the explicitly declared output platform`(): Unit = service { socket ->
        val outputArch = if (workerArch() == "arm64") "amd64" else "arm64"
        val target = WorkerBuildTarget(if (CliMeasurements.localMacOs) "mac" else "linux", outputArch)
        val host = WorkerPreparation(setOf("platform:${target.os}", "arch:${if (workerArch() == "amd64") "x86_64" else "arm64"}"), target)
        val program = """package fixture;
            public class CrossBench {
                public static void main(String[] args) {
                    if (args[0].equals("describe")) System.out.print("{\"id\":\"cross\",\"description\":\"cross build fixture\"}");
                    else if (args[0].equals("plan")) System.out.print("{\"cases\":[{\"id\":\"p\"}]}");
                    else throw new IllegalArgumentException(args[0]);
                }
            }""".trimIndent()
        val entry = com.eignex.lab.bench.BenchEntryPoint("fixture.CrossBench", listOf("build/classes"), listOf("installDist"))
        val entryJson = com.eignex.lab.bench.BenchProtocol.json.encodeToJsonElement(com.eignex.lab.bench.BenchEntryPoint.serializer(), entry).jsonObject
        val metadata = kotlinx.serialization.json.JsonObject(entryJson + mapOf(
            "preparation" to Json.encodeToJsonElement(WorkerPreparation.serializer(), host),
            "runtime" to Json.parseToJsonElement("""{"requiredTags":["arch:${if (outputArch == "amd64") "x86_64" else "arm64"}"]}""")))
        val (source, bytes) = bundle(mapOf("CrossBench.java" to program, "bench.json" to metadata.toString(),
            "gradlew" to "mkdir -p build/classes; javac -d build/classes CrossBench.java"))
        val build = WorkerBuild(benchManifest = "bench.json", requiredTags = target.tags, preparation = host)
        val input = source.copy(build = build)
        val spec = ExperimentSpec("cross", bench = BenchSpec("bench.json"))
        val manifest = WorkManifest("cross-prepare", "release", spec, Experiments.arms(spec).map { PlannedArm(it, source.commit) },
            emptyList(), listOf(input, input.copy(arch = outputArch)), ResourceBudget(1000, 2048), preparationTimeoutSec = 120)
        assertEquals(6, manifest.schemaVersion)
        val uploads = mutableMapOf<String, Path>(); val trace = PreparationTrace()
        val result = PreparationExecutor(ProcessSupervisor(socket), VerifiedCache(root.resolve("cross-cache")),
            WorkerBuildCache(root.resolve("cross-builds"), ProcessSupervisor(socket)), { ByteArrayInputStream(bytes) }, source.os, source.arch,
            shared = null, inputCache = WorkerInputCache(root.resolve("cross-inputs")), trace = trace,
            archives = WorkerArchiveCache(root.resolve("cross-archives")))
            .run(manifest, root.resolve("cross-preparing"), { null }, { 80000 }) { file -> hashFile(file).also { uploads[it] = file } }
        val built = result.bundles.single()
        assertEquals(target.os, built.os); assertEquals(outputArch, built.arch); assertNull(built.build)
        assertEquals(built.sha256, result.artifacts[preparationArtifact(built)])
        assertEquals("miss", trace.snapshot().caches.getValue("archive").outcome)
        assertNull(trace.snapshot().caches["prepared-input"], "cross-host output must not seed an unusable local solve cache")
        val unpacked = VerifiedCache(root.resolve("cross-verify")).stage(built) { Files.newInputStream(uploads.getValue(built.sha256)) }
        assertTrue(Files.isRegularFile(unpacked.resolve("build/classes/fixture/CrossBench.class")))
        assertEquals(setOf("arch:${if (outputArch == "amd64") "x86_64" else "arm64"}"), result.benchPlan!!.cases.single().resources.requiredTags)
    }

    @Test fun `native bench preparation hands off a sealed tree without archiving or seeding another input copy`(): Unit = service { socket ->
        val program = """package fixture;
            public class Bench {
                public static void main(String[] args) throws Exception {
                    if (args[0].equals("describe")) System.out.print("{\"id\":\"native\",\"description\":\"native handoff\"}");
                    else if (args[0].equals("plan")) System.out.print("{\"cases\":[{\"id\":\"p\",\"inputs\":[{\"path\":\"model.txt\",\"role\":\"model\"}]}]}");
                    else if (args[0].equals("run")) {
                        if (System.getenv("LAB_WORKSPACE_CACHE") == null) throw new IllegalStateException("workspace cache unavailable");
                        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("OUT"), "result.json"), "{\"caseId\":\"p\"}");
                    }
                    else throw new IllegalArgumentException(args[0]);
                }
            }""".trimIndent()
        val entry = com.eignex.lab.bench.BenchEntryPoint("fixture.Bench", listOf("build/classes"), listOf("installDist"))
        val (source, bytes) = bundle(mapOf("Bench.java" to program, "model.txt" to "source",
            "bench.json" to (com.eignex.lab.bench.BenchProtocol.json.encodeToJsonElement(com.eignex.lab.bench.BenchEntryPoint.serializer(), entry).jsonObject +
                mapOf("workspaceInputs" to Json.parseToJsonElement("[\"build/classes\",\"model.txt\"]"),
                    "planInputs" to Json.parseToJsonElement("[\"Bench.java\"]"))).let { kotlinx.serialization.json.JsonObject(it).toString() },
            "unused.txt" to "compiler-only input",
            "gradlew" to "mkdir -p build/classes; javac -d build/classes Bench.java"))
        val input = source.copy(build = WorkerBuild(listOf("installDist")))
        val spec = ExperimentSpec("native", bench = BenchSpec("bench.json"))
        val manifest = WorkManifest("native", "release", spec, Experiments.arms(spec).map { PlannedArm(it, source.commit) },
            emptyList(), listOf(input), ResourceBudget(1000, 2048), preparationTimeoutSec = 120)
        val snapshots = NativePreparedTrees(root.resolve("jobs"))
        val trace = PreparationTrace()
        val result = PreparationExecutor(ProcessSupervisor(socket), VerifiedCache(root.resolve("cache")),
            WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket)), { ByteArrayInputStream(bytes) }, source.os, source.arch,
            shared = null, trace = trace, nativeTrees = snapshots, nativeJob = 1).run(manifest, root.resolve("preparing"), { null }, { 80000 }) { file ->
                assertFalse(file.fileName.toString().endsWith(".zip"), "native preparation must not upload a ZIP")
                hashFile(file)
            }
        val built = result.bundles.single()
        assertEquals(1L, built.tree?.job)
        assertEquals(built.sha256, result.artifacts[preparationArtifact(built)])
        assertTrue(snapshots.valid(built, {}))
        assertFalse(built.files.any { it.path in setOf("Bench.java", "unused.txt", "gradlew") })
        assertTrue(built.files.any { it.path == "build/classes/fixture/Bench.class" })
        val completeBuild = Files.walk(root.resolve("builds")).use { paths ->
            paths.filter { it.fileName.toString() == "Bench.java" && it.parent.fileName.toString() == "tree" }.findFirst().orElseThrow().parent
        }
        assertEquals(BenchSelections.key("release", spec.repository, requireNotNull(spec.work.bench),
            BenchRuns.request(spec.work.bench!!, manifest.arms.first().arm), source.commit, completeBuild, "local"),
            result.selectionCache.single().key, "excluded planner files must still determine the selection identity")
        assertFalse("archive" in trace.snapshot().timings)
        assertFalse("cache-publication" in trace.snapshot().timings)
        val case = result.benchPlan!!.cases.single()
        val solving = WorkManifest("solve", "release", spec, manifest.arms, listOf(WorkCase(0, 0, result.selections.single().single(), null, 0,
            case.resources.timeoutSeconds, case)), listOf(built), manifest.budget)
        assertEquals(5, solving.schemaVersion)
        assertFailsWith<IllegalArgumentException> { solving.copy(schemaVersion = 4) }
        val workspaces = WorkerWorkspaces(root.resolve("private"))
        val executor = IterationExecutor(ProcessSupervisor(socket), VerifiedCache(root.resolve("solve-cache")),
            { error("a local native snapshot must not download a ZIP") }, "unused", source.os, source.arch,
            privateWorkspaces = workspaces, nativeTrees = snapshots)
        fun solve(attempt: Int) {
            val directory = root.resolve("solve-$attempt")
            val solved = executor.run(solving, listOf(0), directory, "native", attempt, remainingLeaseMs = { 80000 })
            assertEquals(0, solved.cases.single().exitCode, Files.readString(directory.resolve("0.err")))
        }
        solve(1)
        val key = ExecutionStore.digest(Json.encodeToString(WorkerBundle.serializer(), built))
        val tree = assertNotNull(workspaces.reuse(key) {})
        val model = tree.resolve("model.txt")
        val inode = Files.readAttributes(model, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        Files.writeString(tree.resolve("generated-output"), "discard me")
        solve(2)
        assertEquals(inode, Files.readAttributes(model, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey(),
            "a second row must retain the verified private copy")
        assertFalse(Files.exists(tree.resolve("generated-output")))
        val modified = Files.getLastModifiedTime(model)
        Files.writeString(model, "broken")
        Files.setLastModifiedTime(model, modified)
        solve(3)
        assertEquals("source", Files.readString(model), "changed input must restore a new private copy")
        assertTrue(snapshots.valid(built, {}), "consumer changes cannot mutate the retained snapshot")
    }
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

    @Test fun `declared checksum mismatches fail preparation before publication including after materialization`(): Unit = service { socket ->
        val declared = ExecutionStore.digest("declared")
        val program = """package fixture;
            import java.nio.file.*;
            public class Bench {
                public static void main(String[] args) throws Exception {
                    if (args[0].equals("describe")) {
                        System.out.print("{\"id\":\"fixture\",\"description\":\"declared input\"}");
                    } else if (args[0].equals("plan")) {
                        Path corpus = Path.of(System.getenv("LAB_CORPUS"));
                        Files.writeString(corpus.resolve("plans"), "plan\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        if (System.getenv("LAB_DECLARED_INPUTS") == null) Files.writeString(corpus.resolve("model.cnf"), "local");
                        System.out.print("{\"cases\":[{\"id\":\"p\",\"inputs\":[{\"path\":\"model.cnf\",\"root\":\"CORPUS\",\"role\":\"model\",\"sha256\":\"$declared\"}]}]}");
                    } else throw new Exception("unexpected invocation");
                }
            }
        """.trimIndent()
        val (input, bytes) = bundle(mapOf("Bench.java" to program,
            "bench.json" to """{"main":"fixture.Bench","classpath":["build/classes"],"buildTasks":["installDist"]}""",
            "gradlew" to "mkdir -p build/classes; javac -d build/classes Bench.java"))
        val source = input.copy(build = WorkerBuild(listOf("installDist")))
        val spec = ExperimentSpec("checksum", bench = BenchSpec("bench.json"))
        val manifest = WorkManifest("checksum", "release", spec, Experiments.arms(spec).map { PlannedArm(it, source.commit) },
            emptyList(), listOf(source), ResourceBudget(1000, 2048), preparationTimeoutSec = 60)
        val transport = object : InputTransport {
            override fun stored(key: String, sha: String): Long? = null
            override fun matches(key: String, file: InputFile, md5: String) = error("unexpected local publication")
            override fun put(key: String, source: Path, sha: String) = error("mismatched input must not be uploaded")
            override fun get(key: String, destination: Path) = error("preparation must not fetch case inputs")
        }
        for (materialize in listOf(false, true)) {
            val corpus = Files.createDirectories(root.resolve("corpus-$materialize"))
            if (!materialize) Files.writeString(corpus.resolve("model.cnf"), "local")
            val directory = root.resolve("checksum-$materialize")
            val failure = assertFailsWith<WorkerBuildFailure> {
                PreparationExecutor(ProcessSupervisor(socket), VerifiedCache(root.resolve("cache")),
                    WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket)), { ByteArrayInputStream(bytes) },
                    source.os, source.arch, shared = SharedInputs(corpus, transport)).run(manifest, directory, { null }, { 80000 }) {
                        error("failed preparation must not publish an archive")
                    }
            }
            assertEquals("declared input checksum mismatch: model.cnf (declared $declared, local ${ExecutionStore.digest("local")}, 5 bytes)", failure.detail)
            assertEquals(if (materialize) 2 else 1, Files.readAllLines(corpus.resolve("plans")).size)
            assertEquals("local", Files.readString(corpus.resolve("model.cnf")))
        }
    }

    @Test fun `an accepted selection with the same key skips describe plan and pinning`(): Unit = service { socket ->
        fun source(program: String, data: String): Pair<WorkerBundle, ByteArray> {
            val java = """package fixture;
                public class Bench {
                    public static void main(String[] args) throws Exception {
                        $program
                    }
                }
            """.trimIndent()
            val entry = com.eignex.lab.bench.BenchEntryPoint("fixture.Bench", listOf("build/classes"), listOf("installDist"))
            val (bundle, bytes) = bundle(mapOf("Bench.java" to java, "data/p.txt" to data,
                "bench.json" to com.eignex.lab.bench.BenchProtocol.json.encodeToString(com.eignex.lab.bench.BenchEntryPoint.serializer(), entry),
                "gradlew" to "mkdir -p build/classes; javac -d build/classes Bench.java"))
            return bundle.copy(build = WorkerBuild(listOf("installDist"))) to bytes
        }
        val planner = """switch (args[0]) {
                case "describe": System.out.print("{\"id\":\"fixture\",\"description\":\"selection cache\"}"); break;
                case "plan": System.out.print("{\"cases\":[{\"id\":\"p\",\"inputs\":[{\"path\":\"data/p.txt\",\"role\":\"model\"}]}]}"); break;
                default: throw new Exception("unexpected " + args[0]);
            }"""
        val single = ExperimentSpec("cached", bench = BenchSpec("bench.json"))
        fun prepare(name: String, source: Pair<WorkerBundle, ByteArray>, spec: ExperimentSpec = single,
                    cached: (List<String>) -> List<BenchSelectionEntry>): Pair<PreparationResult, Path> {
            val (bundle, bytes) = source
            val manifest = WorkManifest(name, "release", spec, Experiments.arms(spec).map { PlannedArm(it, bundle.commit) },
                emptyList(), listOf(bundle), ResourceBudget(1000, 2048), preparationTimeoutSec = 120)
            val directory = root.resolve(name)
            val result = PreparationExecutor(ProcessSupervisor(socket), VerifiedCache(root.resolve("cache")),
                WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket)), { ByteArrayInputStream(bytes) },
                bundle.os, bundle.arch, shared = null).run(manifest, directory, { null }, { 80000 }, cachedSelections = cached) { hashFile(it) }
            return result to directory
        }
        fun outcome(directory: Path) = Json.decodeFromString<Map<String, PreparationCacheEvent>>(
            Files.readString(directory.resolve("preparation-cache.json"))).getValue("selection")

        val (planned, first) = prepare("planned", source(planner, "model")) { emptyList() }
        assertTrue(Files.exists(first.resolve("bench-plan-0.jsonl")))
        assertEquals("absent", outcome(first).reason)
        val entry = planned.selectionCache.single()
        assertEquals(planned.armPlans.values.single(), entry.plan)
        assertNotNull(entry.plan.cases.single().attributes["labInputDigest"])

        // The same commit and request: the adapter must not run at all.
        val refusing = source("throw new Exception(\"describe and plan must not run on a hit\");", "model")
        var asked = emptyList<String>()
        val (reused, second) = prepare("reused", refusing) { keys -> asked = keys; listOf(entry) }
        assertEquals(listOf(entry.key), asked)
        assertFalse(Files.exists(second.resolve("bench-plan-0.jsonl")) || Files.exists(second.resolve("bench-descriptor-0.jsonl")))
        assertEquals(planned.armPlans, reused.armPlans)
        assertEquals(planned.benchPlan, reused.benchPlan)
        assertEquals(planned.benchDescriptor, reused.benchDescriptor)
        assertEquals(planned.selections, reused.selections)
        assertTrue(reused.selectionCache.isEmpty())
        assertEquals("hit" to "accepted-selection", outcome(second).let { it.outcome to it.reason })

        // Changed source input bytes under the same key fail verification and plan again.
        val (replanned, third) = prepare("replanned", source(planner, "changed model")) { listOf(entry) }
        assertTrue(Files.exists(third.resolve("bench-plan-0.jsonl")))
        assertEquals("verification-failed", outcome(third).reason)
        assertNotEquals(entry.plan.cases.single().attributes["labInputDigest"], replanned.armPlans.values.single().cases.single().attributes["labInputDigest"])

        // A reused plan whose input digest disagrees with an arm that planned is planned again rather than failing the job.
        val paired = ExperimentSpec("cached-pair", bench = BenchSpec("bench.json"), grid = mapOf("param.x" to listOf("a", "b")))
        val stale = entry.plan.copy(cases = entry.plan.cases.map { it.copy(attributes = it.attributes + ("labInputDigest" to "0".repeat(64))) })
        var pairKeys = emptyList<String>()
        val (recovered, fourth) = prepare("recovered", source(planner, "model"), paired) { keys ->
            pairKeys = keys; listOf(entry.copy(key = keys.first(), plan = stale))
        }
        assertEquals(2, pairKeys.size)
        assertTrue(Files.exists(fourth.resolve("bench-plan-0.jsonl")) && Files.exists(fourth.resolve("bench-plan-1.jsonl")))
        assertEquals(setOf(entry.plan.cases.single().attributes["labInputDigest"]),
            recovered.armPlans.values.map { it.cases.single().attributes["labInputDigest"] }.toSet())
        assertEquals(listOf(1, 2, 0), outcome(fourth).let { listOf(it.hits, it.misses, it.bypasses) })
        assertEquals(pairKeys.toSet(), recovered.selectionCache.map { it.key }.toSet())
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

    @Test fun `concurrent cases of one commit build in separate trees`(): Unit = service { socket ->
        // A case that writes into its tree, as a consumer build does, must not meet another running case there.
        val java = """package fixture;
            import java.nio.file.*;
            public class Bench {
                public static void main(String[] args) throws Exception {
                    Path claim = Path.of("case-running");
                    Files.createFile(claim);
                    Thread.sleep(1500);
                    Files.delete(claim);
                    Files.writeString(Path.of(System.getenv("OUT"), "tree.txt"), Path.of("").toAbsolutePath().toString());
                    Files.writeString(Path.of(System.getenv("OUT"), "result.json"), "{\"caseId\":\"shared\"}");
                }
            }
        """.trimIndent()
        val entry = com.eignex.lab.bench.BenchEntryPoint("fixture.Bench", listOf("build/classes"), listOf("installDist"))
        val (source, bytes) = bundle(mapOf("Bench.java" to java,
            "bench.json" to com.eignex.lab.bench.BenchProtocol.json.encodeToString(com.eignex.lab.bench.BenchEntryPoint.serializer(), entry),
            "gradlew" to "mkdir -p build/classes; javac -d build/classes Bench.java"))
        val built = source.copy(build = WorkerBuild(listOf("installDist")))
        val spec = ExperimentSpec("shared-tree", bench = BenchSpec("bench.json"))
        val case = com.eignex.lab.bench.BenchCase("shared", resources = com.eignex.lab.bench.BenchResources(timeoutSeconds = 30))
        val arms = listOf("a", "b").map { PlannedArm(Arm(it, mapOf("variant" to it)), source.commit) }
        val manifest = WorkManifest("shared-tree", "release", spec, arms,
            listOf(0, 1).map { WorkCase(it, it, BenchRuns.problem(case), null, 0, 30, case) }, listOf(built), ResourceBudget(2000, 2048, 2))
        val directory = root.resolve("shared-tree-run")
        val result = IterationExecutor(ProcessSupervisor(socket), VerifiedCache(root.resolve("cache")), { ByteArrayInputStream(bytes) },
            "unused", source.os, source.arch, buildCache = WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket)))
            .run(manifest, listOf(0, 1), directory, "fixture", 1, remainingLeaseMs = { 80000 })
        for (case in result.cases) assertEquals(0, case.exitCode, Files.readString(directory.resolve("${case.index}.err")))
        val trees = result.cases.map { Files.readString(directory.resolve("cases/${it.index}/out/tree.txt")) }
        assertEquals(2, trees.toSet().size)
    }

    @Test fun `a claim of several whole rows runs every case and a partial row is refused`(): Unit = service { socket ->
        val java = """package fixture;
            import java.nio.file.*;
            public class Bench {
                public static void main(String[] args) throws Exception {
                    Files.writeString(Path.of(System.getenv("OUT"), "result.json"), "{\"caseId\":\"row\"}");
                }
            }
        """.trimIndent()
        val entry = com.eignex.lab.bench.BenchEntryPoint("fixture.Bench", listOf("build/classes"), listOf("installDist"))
        val (source, bytes) = bundle(mapOf("Bench.java" to java,
            "bench.json" to com.eignex.lab.bench.BenchProtocol.json.encodeToString(com.eignex.lab.bench.BenchEntryPoint.serializer(), entry),
            "gradlew" to "mkdir -p build/classes; javac -d build/classes Bench.java"))
        val built = source.copy(build = WorkerBuild(listOf("installDist")))
        val spec = ExperimentSpec("rows", bench = BenchSpec("bench.json"))
        val case = com.eignex.lab.bench.BenchCase("row", resources = com.eignex.lab.bench.BenchResources(timeoutSeconds = 30))
        val arms = listOf("a", "b").map { PlannedArm(Arm(it, mapOf("variant" to it)), source.commit) }
        // Two rows (repeats 0 and 1) of two arms each.
        val cases = (0..3).map { WorkCase(it, it % 2, BenchRuns.problem(case), null, it / 2, 30, case) }
        val manifest = WorkManifest("rows", "release", spec, arms, cases, listOf(built), ResourceBudget(4000, 2048, 3))
        val executor = IterationExecutor(ProcessSupervisor(socket), VerifiedCache(root.resolve("cache")), { ByteArrayInputStream(bytes) },
            "unused", source.os, source.arch, buildCache = WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket)))
        val directory = root.resolve("rows-run")
        val result = executor.run(manifest, listOf(0, 1, 2, 3), directory, "fixture", 1, remainingLeaseMs = { 80000 })
        assertEquals(listOf(0, 1, 2, 3), result.cases.map { it.index }.sorted())
        for (row in result.cases) assertEquals(0, row.exitCode, Files.readString(directory.resolve("${row.index}.err")))
        assertFailsWith<IllegalArgumentException> {
            executor.run(manifest, listOf(0, 1, 2), root.resolve("partial-run"), "fixture", 1, remainingLeaseMs = { 80000 })
        }
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

    @Test fun `source snapshot transports preserve current Git authority without ancestors or credentials`(): Unit = service { socket ->
        val repository = Files.createDirectory(root.resolve("snapshot-repo"))
        fun command(directory: Path, vararg args: String): Pair<Int, String> {
            val process = ProcessBuilder(listOf("git", "-C", directory.toString()) + args).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS))
            return process.exitValue() to text.trim()
        }
        fun git(vararg args: String): String {
            val (exit, text) = command(repository, *args); assertEquals(0, exit, text); return text
        }
        git("init", "--initial-branch=main"); git("config", "user.name", "fixture"); git("config", "user.email", "fixture@example.com")
        Files.write(repository.resolve("removed.bin"), kotlin.random.Random(42).nextBytes(1024 * 1024))
        git("add", "."); git("commit", "-m", "old source")
        val parent = git("rev-parse", "HEAD"); val oldBlob = git("rev-parse", "HEAD:removed.bin")
        git("tag", "old-tag"); Files.delete(repository.resolve("removed.bin"))
        Files.writeString(repository.resolve("gradlew"), "mkdir -p build; git rev-parse HEAD > build/commit; git rev-list --count HEAD > build/history; git show HEAD:café.txt > build/from-git")
        Files.writeString(repository.resolve("café.txt"), "current source")
        Files.writeString(repository.resolve(".gitattributes"), "café.txt export-ignore\nbinary.bin -text\n")
        val binary = ByteArray(256 * 1024) { (it % 251).toByte() }
        Files.write(repository.resolve("binary.bin"), binary)
        Files.write(repository.resolve("empty.txt"), byteArrayOf())
        Files.writeString(repository.resolve("duplicate.txt"), "current source")
        Files.writeString(repository.resolve("tool"), "executable source"); repository.resolve("tool").toFile().setExecutable(true)
        val outside = root.resolve("snapshot-host-secret"); Files.writeString(outside, "do not read")
        Files.createSymbolicLink(repository.resolve("outside.link"), outside)
        git("add", "-A"); git("commit", "-m", "selected source")
        val commit = git("rev-parse", "HEAD")
        git("remote", "add", "origin", "https://private-token@example.com/secret.git")
        Files.writeString(repository.resolve("untracked-secret"), "do not export")
        val inputs = SourceInputs({ repo, sha -> assertEquals(Repositories.DEFAULT, repo); assertEquals(commit, sha) })
        val os = if (CliMeasurements.localMacOs) "mac" else "linux"
        for (packed in listOf(false, true)) {
            val mode = if (packed) "packed-snapshot" else "file-snapshot"
            val build = WorkerBuild(listOf("installDist"), gitBundle = if (packed) PackedGitSource.MARKER else null)
            val output = root.resolve("$mode-source.zip")
            val manifest = inputs.publish(repository, Repositories.DEFAULT, commit, os, workerArch(), "fixture", build, output, includeHistory = false)
            if (packed) {
                assertTrue(PackedGitSource.usable(manifest))
                assertTrue(manifest.files.size < 16 && manifest.files.all { it.path.startsWith(".git/") }, "packed inputs must not extract working files before checkout")
            } else {
                assertNull(manifest.build!!.gitBundle)
                assertTrue(manifest.files.single { it.path == "tool" }.executable)
                assertTrue(com.eignex.lab.execution.SourceSnapshots.usable(manifest))
                assertEquals(0L, manifest.files.single { it.path == "empty.txt" }.bytes)
            }
            assertFalse(manifest.files.any { it.path == "removed.bin" || it.path == "untracked-secret" || it.path.startsWith(".git/hooks/") || it.path.startsWith(".git/logs/") })
            val limited = root.resolve("$mode-source-too-large.zip")
            assertFailsWith<IllegalArgumentException> {
                SourceInputs({ _, _ -> }, maxBytes = 1024).publish(repository, Repositories.DEFAULT, commit, os, workerArch(), "fixture", build, limited, includeHistory = false)
            }
            assertFalse(Files.exists(limited), "a failed streaming budget check must not publish partial source")

            // A mirror repack and a new export directory must not invalidate the worker's existing ZIP-keyed cache.
            git("repack", "-adF", "--window=0")
            val repeated = root.resolve("$mode-source-repeated.zip")
            val second = inputs.publish(repository, Repositories.DEFAULT, commit, os, workerArch(), "fixture", build, repeated, includeHistory = false)
            assertEquals(manifest, second)
            val full = root.resolve("$mode-source-with-history.zip")
            inputs.publish(repository, Repositories.DEFAULT, commit, os, workerArch(), "fixture", build, full)
            assertTrue(Files.size(output) * 10 < Files.size(full), "deleted historical bytes must not travel with the snapshot")

            val source = VerifiedCache(root.resolve("$mode-sources")).stage(manifest) { Files.newInputStream(output) }
            if (packed) assertFalse(Files.exists(source.resolve("binary.bin"))) else {
                assertContentEquals(binary, Files.readAllBytes(source.resolve("binary.bin")))
                assertEquals("current source", Files.readString(source.resolve("café.txt")), "archive attributes must not omit committed build inputs")
            }
            val tree = WorkerBuildCache(root.resolve("$mode-builds"), ProcessSupervisor(socket)).prepare(manifest, source,
                ResourceBudget(1000, 512), System.getenv()) { 10000 }
            assertContentEquals(binary, Files.readAllBytes(tree.resolve("binary.bin")))
            assertEquals("current source", Files.readString(tree.resolve("duplicate.txt")))
            assertTrue(Files.isExecutable(tree.resolve("tool"))); assertEquals(0L, Files.size(tree.resolve("empty.txt")))
            assertEquals(commit, Files.readString(tree.resolve("build/commit")).trim())
            assertEquals("1", Files.readString(tree.resolve("build/history")).trim())
            assertEquals("current source", Files.readString(tree.resolve("build/from-git")))
            assertEquals(outside.toString(), Files.readString(tree.resolve("outside.link"))); assertFalse(Files.isSymbolicLink(tree.resolve("outside.link")))
            assertEquals(0 to "", command(tree, "status", "--porcelain", "--untracked-files=no"))
            assertEquals(0 to "current source", command(tree, "show", "HEAD:café.txt"))
            assertEquals(0 to "true", command(tree, "rev-parse", "--is-shallow-repository"))
            assertNotEquals(0, command(tree, "cat-file", "-e", parent).first)
            assertNotEquals(0, command(tree, "cat-file", "-e", oldBlob).first)
            assertEquals(0 to "", command(tree, "tag", "--list"))
            assertTrue(Files.isDirectory(tree.resolve(".git/refs/heads")))
            assertFalse(Files.readString(tree.resolve(".git/config")).contains("private-token"))
            assertEquals("", git("for-each-ref", "--format=%(refname)", "refs/lab-source"))
        }
    }

    @Test fun `declared preparation verifies planner and runtime inputs without visiting compiler scratch`(): Unit = service { socket ->
        val count = root.resolve("selected-build-count")
        val script = """#!/usr/bin/env bash
            set -eu
            echo build >> '$count'
            mkdir -p runtime build/classes
            echo prepared > runtime/program
            for n in {1..200}; do echo compiler > "build/classes/file-${'$'}n"; done
        """.trimIndent()
        val entry = """{"main":"fixture.Bench","classpath":["runtime/*"],"buildTasks":["assemble"],"workspaceInputs":["runtime"],"planInputs":["planner.txt"]}"""
        val (input, bytes) = bundle(mapOf("gradlew" to script, "bench.json" to entry, "planner.txt" to "planner"))
        val source = VerifiedCache(root.resolve("selected-source")).stage(input) { ByteArrayInputStream(bytes) }
        val recipe = input.copy(build = WorkerBuild(listOf("assemble"), benchManifest = "bench.json"))
        val cache = WorkerBuildCache(root.resolve("selected-builds"), ProcessSupervisor(socket))
        val budget = ResourceBudget(1000, 512)
        val tree = cache.prepare(recipe, source, budget, System.getenv()) { 10000 }
        Files.writeString(tree.resolve("build/classes/file-1"), "unused corruption")
        fun prepared(trace: PreparationTrace = PreparationTrace(), mayBuild: Boolean = true): Path =
            cache.withPreparedSource(recipe, budget, System.getenv(), { null }, { 10000 }, trace,
                { consume -> check(mayBuild) { "warm declared workspace must bypass source" }; consume(source) }, declaredWorkspace = true, use = { it })
        val trace = PreparationTrace()
        assertEquals(tree, prepared(trace, mayBuild = false))
        assertEquals(1, Files.readAllLines(count).size)
        assertTrue(requireNotNull(trace.snapshot().caches["build"]?.checkedFiles) < 10)
        // A caller needing the complete build still detects the excluded corruption.
        cache.prepare(recipe, source, budget, System.getenv()) { 10000 }
        assertEquals(2, Files.readAllLines(count).size)
        for (file in listOf("runtime/program", "planner.txt", "bench.json")) {
            Files.writeString(tree.resolve(file), "corrupt")
            assertEquals(tree, prepared())
        }
        assertEquals(5, Files.readAllLines(count).size)
        assertEquals(entry, Files.readString(tree.resolve("bench.json")))
        assertEquals("prepared", Files.readString(tree.resolve("runtime/program")).trim())
    }

    @Test fun `dependency accounting stays excluded throughout cold build sealing`(): Unit = service { socket ->
        val script = "mkdir -p build; echo prepared > build/output"
        val (input, bytes) = bundle(mapOf("gradlew" to script))
        val source = VerifiedCache(root.resolve("pin-source")).stage(input) { ByteArrayInputStream(bytes) }
        val buildRoot = root.resolve("pinned-builds")
        val trace = PreparationTrace()
        var checked = 0
        val pending = java.util.concurrent.CopyOnWriteArrayList<Thread>()
        val cache = WorkerBuildCache(buildRoot, ProcessSupervisor(socket), startMaintenance = { pending.add(it) })
        try {
            val tree = cache.prepare(input.copy(build = WorkerBuild(listOf("assemble"))), source,
                ResourceBudget(1000, 512), System.getenv(), remainingRunMs = {
                    if (trace.snapshot().stage == "build-verification") {
                        val homes = Files.list(buildRoot.resolve("dependencies")).use { it.toList() }
                        assertEquals(1, homes.size)
                        assertNull(ProcessFilePin.tryLock(buildRoot.resolve("dependencies-${homes.single().fileName}.lock")),
                            "sealing must retain the dirty Gradle home pin, excluding recursive accounting")
                        checked++
                    }
                    null
                }, trace = trace) { 10000 }
            assertTrue(checked > 0)
            assertEquals("prepared", Files.readString(tree.resolve("build/output")).trim())
            pending.forEach { it.start() }
            assertTrue(cache.awaitMaintenanceIdle())
            cache.maintain(now() + 120_000)
            val home = Files.list(buildRoot.resolve("dependencies")).use { it.toList() }.single()
            assertTrue(Files.isRegularFile(buildRoot.resolve("dependency-sizes/${home.fileName}.json")),
                "publication must still release and account dependency bytes")
        } finally {
            pending.filter { it.state == Thread.State.NEW }.forEach { it.start() }
            assertTrue(cache.awaitMaintenanceIdle())
        }
    }

    @Test fun `outer preparation scope retains one dependency home through consumers and failures`(): Unit = service { socket ->
        val (input, bytes) = bundle(mapOf("gradlew" to "mkdir -p build; echo prepared > build/output"))
        val source = VerifiedCache(root.resolve("scope-source")).stage(input) { ByteArrayInputStream(bytes) }
        for (fail in listOf(false, true)) {
            val buildRoot = root.resolve("scoped-builds-$fail")
            val pending = java.util.concurrent.CopyOnWriteArrayList<Thread>()
            val cache = WorkerBuildCache(buildRoot, ProcessSupervisor(socket), startMaintenance = { pending.add(it) })
            fun home(): Path = Files.list(buildRoot.resolve("dependencies")).use { it.toList() }.single()
            fun assertPinned() = assertNull(ProcessFilePin.tryLock(buildRoot.resolve("dependencies-${home().fileName}.lock")))
            try {
                val outcome = runCatching { cache.withDependenciesPinned {
                    for (commit in listOf("a".repeat(40), "b".repeat(40))) {
                        cache.withPreparedSource(input.copy(commit = commit, build = WorkerBuild(listOf("assemble"))),
                            ResourceBudget(1000, 512), System.getenv(), { null }, { 10_000 },
                            source = { it(source) }) { tree ->
                                assertPinned()
                                assertEquals("prepared", Files.readString(tree.resolve("build/output")).trim())
                            }
                        assertPinned() // Later planning, shared-input publication and handoff still own this scope.
                    }
                    if (fail) error("failed handoff")
                } }
                if (fail) assertEquals("failed handoff", outcome.exceptionOrNull()?.message) else outcome.getOrThrow()
                ProcessFilePin.tryLock(buildRoot.resolve("dependencies-${home().fileName}.lock"))!!.close()
                pending.forEach { it.start() }
                assertTrue(cache.awaitMaintenanceIdle())
                cache.maintain(now() + 120_000)
                assertTrue(Files.isRegularFile(buildRoot.resolve("dependency-sizes/${home().fileName}.json")))
            } finally {
                pending.filter { it.state == Thread.State.NEW }.forEach { it.start() }
                assertTrue(cache.awaitMaintenanceIdle())
            }
        }
    }

    @Test fun `source builds share their session budget cache once and rebuild corrupt outputs`(): Unit = service { socket ->
        val count = root.resolve("build-count")
        val script = """#!/usr/bin/env bash
            set -eu
            printf 'build\n' >> '${count}'
            [[ "${'$'}GRADLE_USER_HOME" != "${'$'}HOME/.gradle" ]]
            [[ "${'$'}JAVA_TOOL_OPTIONS" == *'-Xmx${buildHeapMiB(512)}m'* ]]
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

    @Test fun `shared builds single flight across allocations and budgets and preserve dependency caches across commits`(): Unit = service { socket ->
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
            val resized = WorkerBuildCache(root.resolve("shared"), ProcessSupervisor(socket))
                .prepare(built, source, ResourceBudget(4000, 4096), System.getenv()) { 10000 }
            assertEquals(jobs[0].get(), resized)
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

    @Test fun `build JVMs get most of a preparation's memory`() {
        // A third of the cloud preparation's 4 GiB ran klause's Kotlin compiler out of heap.
        assertEquals(2457, buildHeapMiB(4096))
        assertTrue(buildHeapMiB(4096) > 4096 / 3)
        assertEquals(16, buildHeapMiB(8))
    }
}
