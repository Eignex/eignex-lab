package com.eignex.lab

import com.eignex.lab.util.*
import com.eignex.lab.analysis.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.execution.*
import com.eignex.lab.worker.*

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

/** A cloud preparation reuses another job's accepted archive of the same build, and builds whenever it cannot. */
class AcceptedBuildReuseTest {
    private val root = Files.createTempDirectory("accepted-build-reuse").toAbsolutePath()
    private val supervisorScript = root.resolve("supervisor.py").also { file ->
        javaClass.getResourceAsStream("/nomad/process_supervisor.py")!!.use { Files.copy(it, file) }
    }

    @AfterTest fun cleanup() = deleteTree(root)

    private fun <T> service(block: (Path) -> T): T {
        val socket = root.resolve("supervisor.sock")
        val process = ProcessBuilder("python3", supervisorScript.toString(), "--socket", socket.toString())
            .redirectError(root.resolve("service.err").toFile()).start()
        try {
            val deadline = System.nanoTime() + 5_000_000_000
            while (!Files.exists(socket)) { check(process.isAlive && System.nanoTime() < deadline) { root.resolve("service.err").toFile().readText() }; Thread.sleep(20) }
            return block(socket)
        } finally { process.destroy(); assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)); process.destroyForcibly() }
    }

    /** A bench whose planner (`Bench.java`) is a plan input but not part of its runtime workspace. */
    private val program = """package fixture;
        public class Bench {
            public static void main(String[] args) throws Exception {
                if (args[0].equals("describe")) System.out.print("{\"id\":\"reuse\",\"description\":\"accepted build reuse\"}");
                else if (args[0].equals("plan")) System.out.print("{\"cases\":[{\"id\":\"p\",\"inputs\":[{\"path\":\"model.txt\",\"role\":\"model\"}]}]}");
                else throw new IllegalArgumentException(args[0]);
            }
        }""".trimIndent()
    private val files = mapOf("Bench.java" to program, "model.txt" to "source",
        "bench.json" to JsonObject(com.eignex.lab.bench.BenchProtocol.json.encodeToJsonElement(com.eignex.lab.bench.BenchEntryPoint.serializer(),
            com.eignex.lab.bench.BenchEntryPoint("fixture.Bench", listOf("build/classes"), listOf("installDist"))).jsonObject +
            mapOf("workspaceInputs" to Json.parseToJsonElement("[\"build/classes\",\"model.txt\"]"),
                "planInputs" to Json.parseToJsonElement("[\"Bench.java\"]"))).toString(),
        "gradlew" to "mkdir -p build/classes; javac -d build/classes Bench.java")
    private val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
        files.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
    } }.toByteArray()
    private val source = WorkerBundle(sha256Hex(bytes), files.map { (name, text) -> InputFile(name, ExecutionStore.digest(text), text.toByteArray().size.toLong()) },
        Repositories.DEFAULT, "a".repeat(40), if (CliMeasurements.localMacOs) "mac" else "linux", workerArch(), "fixture",
        build = WorkerBuild(listOf("installDist"), benchManifest = "bench.json"))
    private val spec = ExperimentSpec("reuse", bench = BenchSpec("bench.json"))
    private val uploads = Files.createDirectories(root.resolve("uploads"))

    /** The regional store: archives by content hash, as the cloud preparations above uploaded them. */
    private class Regional(private val directory: Path, var held: Boolean = true, var bytes: ((String) -> ByteArray?)? = null) : AcceptedBuildStore {
        var opened = 0
        override fun holds(bundle: WorkerBundle) = held && Files.isRegularFile(directory.resolve(bundle.sha256))
        override fun open(bundle: WorkerBundle): java.io.InputStream? {
            opened++
            bytes?.let { replace -> return replace(bundle.sha256)?.let(::ByteArrayInputStream) }
            return directory.resolve(bundle.sha256).takeIf(Files::isRegularFile)?.let { Files.newInputStream(it) }
        }
    }

    private class Prepared(val result: PreparationResult, val trace: PreparationProgress, val uploaded: List<String>)

    private fun prepare(socket: Path, name: String, offered: List<ReusableBuild>?, store: AcceptedBuildStore?,
                        sources: Map<String, String> = emptyMap(), cached: (List<String>) -> List<BenchSelectionEntry> = { emptyList() }): Prepared {
        val manifest = WorkManifest(name, "release", spec, Experiments.arms(spec).map { PlannedArm(it, source.commit) },
            emptyList(), listOf(source), ResourceBudget(1000, 2048), reusableBuilds = offered, selectionSources = sources, preparationTimeoutSec = 120)
        val trace = PreparationTrace()
        val uploaded = mutableListOf<String>()
        // Each preparation runs as if on a fresh node: no build, input or archive cache is shared with another.
        val result = PreparationExecutor(ProcessSupervisor(socket), VerifiedCache(root.resolve("$name/cache")),
            WorkerBuildCache(root.resolve("$name/builds"), ProcessSupervisor(socket)), { ByteArrayInputStream(bytes) }, source.os, source.arch,
            shared = null, inputCache = WorkerInputCache(root.resolve("$name/inputs")), trace = trace,
            archives = WorkerArchiveCache(root.resolve("$name/archives")), acceptedBuilds = store)
            .run(manifest, root.resolve("$name/preparing"), { null }, { 80000 }, cachedSelections = cached) { file ->
                hashFile(file).also { sha -> uploaded += file.fileName.toString(); Files.copy(file, uploads.resolve(sha), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            }
        return Prepared(result, trace.snapshot(), uploaded)
    }

    private fun hint(accepted: PreparationResult) = accepted.builds.single().let { ReusableBuild(it.identity, accepted.bundles.single(), it.planSource) }

    @Test fun `an accepted archive of the same build replaces compilation and keeps the selection identity`(): Unit = service { socket ->
        val first = prepare(socket, "first", emptyList(), null)
        assertTrue("compilation" in first.trace.timings)
        val built = first.result.builds.single()
        assertEquals(PreparedBuild(source.commit, source.os, source.arch, built.identity, built.planSource), built)
        assertTrue(built.planSource!!.startsWith("declared:"), "the planner is a declared plan input")
        val entry = first.result.selectionCache.single()
        assertTrue(first.uploaded.any { it.endsWith(".zip") })

        val regional = Regional(uploads)
        var asked = emptyList<String>()
        val second = prepare(socket, "second", listOf(hint(first.result)), regional) { keys -> asked = keys; listOf(entry) }
        assertEquals(1, regional.opened)
        assertFalse("compilation" in second.trace.timings, "a reused build must not compile")
        assertFalse("checkout" in second.trace.timings)
        assertEquals("hit" to "reused-accepted", second.trace.caches.getValue("build").let { it.outcome to it.reason })
        assertEquals(first.result.bundles, second.result.bundles)
        assertEquals(first.result.bundles.single().sha256, second.result.artifacts[preparationArtifact(source)])
        assertFalse(second.uploaded.any { it.endsWith(".zip") }, "the accepted archive is already in the regional store")
        // The workspace lacks the planner, so its plans are keyed by the plan source of the build they came from.
        assertEquals(listOf(entry.key), asked)
        assertEquals("accepted-selection", second.trace.caches.getValue("selection").reason)
        assertEquals(first.result.builds, second.result.builds)

        // Planning on the reused workspace offers its plan under the same key.
        val third = prepare(socket, "third", listOf(hint(first.result)), regional)
        assertFalse("compilation" in third.trace.timings)
        assertEquals(entry.key, third.result.selectionCache.single().key)
        assertEquals(first.result.armPlans, third.result.armPlans)

        // Without an offer the worker reports nothing that an older coordinator could not read.
        assertTrue(prepare(socket, "unoffered", null, regional).result.builds.isEmpty())

        // A committed source from admission keys the plans of a reused build too, and the build keeps its own plan
        // source for later preparations that have none; a committed source is never reported as a build's.
        val sources = mapOf(source.commit to "committed:" + "c".repeat(64))
        val bench = requireNotNull(spec.work.bench)
        val committedKey = BenchSelections.key("release", spec.repository, bench, BenchRuns.request(bench, Experiments.arms(spec).single()),
            "local", sources.getValue(source.commit))
        var committedAsked = emptyList<String>()
        val committed = prepare(socket, "committed", listOf(hint(first.result)), regional, sources) { keys -> committedAsked = keys; emptyList() }
        assertFalse("compilation" in committed.trace.timings)
        assertEquals(listOf(committedKey), committedAsked)
        assertEquals(committedKey, committed.result.selectionCache.single().key)
        assertEquals(first.result.builds, committed.result.builds)
        val fresh = prepare(socket, "committed-fresh", emptyList(), null, sources)
        assertEquals(committedKey, fresh.result.selectionCache.single().key)
        assertNull(fresh.result.builds.single().planSource)
    }

    @Test fun `a foreign missing or corrupt accepted archive falls back to an ordinary build`(): Unit = service { socket ->
        val first = prepare(socket, "accepted", emptyList(), null)
        val offer = hint(first.result)
        fun builds(name: String, offered: ReusableBuild, store: Regional) {
            val prepared = prepare(socket, name, listOf(offered), store)
            assertTrue("compilation" in prepared.trace.timings, "$name must build")
            assertNotEquals("reused-accepted", prepared.trace.caches.getValue("build").reason, name)
            assertEquals(first.result.armPlans, prepared.result.armPlans, name)
            assertEquals(first.result.builds.single().identity, prepared.result.builds.single().identity, name)
            assertTrue(prepared.uploaded.any { it.endsWith(".zip") }, "$name publishes its own archive")
        }
        // Another worker JDK or platform: never even read.
        val untouched = Regional(uploads)
        builds("foreign", offer.copy(identity = "0".repeat(64)), untouched)
        assertEquals(0, untouched.opened)
        // Expired from the regional store.
        builds("expired", offer, Regional(uploads, held = false))
        // Bytes that differ from the accepted bundle, or a bundle that differs from its bytes.
        builds("corrupt", offer, Regional(uploads, bytes = { bytes }))
        builds("unreadable", offer, Regional(uploads, bytes = { null }))
        val altered = offer.bundle.files.map { if (it.path.endsWith("Bench.class")) it.copy(sha256 = "0".repeat(64)) else it }
        assertNotEquals(offer.bundle.files, altered)
        builds("altered", offer.copy(bundle = offer.bundle.copy(files = altered)), Regional(uploads))
    }
}
