package com.eignex.lab

import kotlinx.serialization.json.Json
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
    private val root = Files.createTempDirectory("portable-worker").toAbsolutePath()
    private val supervisorScript = root.resolve("supervisor.py").also { file ->
        javaClass.getResourceAsStream("/nomad/process_supervisor.py")!!.use { Files.copy(it, file) }
    }
    private fun bundle(files: Map<String, String>): Pair<WorkerBundle, ByteArray> {
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            files.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
        } }.toByteArray()
        val manifest = WorkerBundle(MessageDigest.getInstance("SHA-256").digest(bytes).hex(), files.map { (name, text) ->
            InputFile(name, ExecutionStore.digest(text), text.toByteArray().size.toLong(), name.endsWith("/klause-bench"))
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

    @Test fun `preparation selects portable files and publishes verified build and corpus bundles`(): Unit = service { socket ->
        val script = """#!/usr/bin/env bash
            set -eu
            corpus="${'$'}{JAVA_OPTS#*-Dklause.bench.corpusCache=}"
            corpus="${'$'}{corpus%% *}"
            mkdir -p "${'$'}corpus"
            printf 'model contents' > "${'$'}corpus/model.mps"
            printf '{"suite":"s","problem":"p","model":"%s/model.mps","format":"mps"}\n' "${'$'}corpus"
        """.trimIndent()
        val (bundle, bytes) = bundle(mapOf("klause-bench/build/install/klause-bench/bin/klause-bench" to script))
        val spec = ExperimentSpec("prepare", problems = listOf(mapOf("suite" to "s")), base = mapOf("backend" to "reference", "solver" to "highs"))
        val arms = Experiments.arms(spec).map { PlannedArm(it, bundle.commit) }
        val manifest = WorkManifest("prepare", "release", spec, arms, emptyList(), listOf(bundle), ResourceBudget(1000, 512), preparationTimeoutSec = 60)
        val uploads = mutableMapOf<String, Path>()
        val executor = PreparationExecutor(ProcessSupervisor(socket), VerifiedCache(root.resolve("cache")),
            WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket)), { ByteArrayInputStream(bytes) }, bundle.os, bundle.arch)
        val result = executor.run(manifest, root.resolve("prepare"), { null }, { 80000 }) { file ->
            hashFile(file).also { sha -> uploads[sha] = file }
        }
        assertEquals("lab-corpus/model.mps", result.selections.single().single().model)
        val corpus = requireNotNull(result.corpus)
        val contents = VerifiedCache(root.resolve("verify")).stage(corpus) { Files.newInputStream(uploads.getValue(corpus.sha256)) }
        assertEquals("model contents", Files.readString(contents.resolve("model.mps")))
        val prepared = result.bundles.single()
        val source = VerifiedCache(root.resolve("verify")).stage(prepared) { Files.newInputStream(uploads.getValue(prepared.sha256)) }
        assertEquals(script, Files.readString(source.resolve(bundle.files.single().path)))
        assertEquals(contents.resolve("model.mps"), portablePath(result.selections.single().single().model!!, source, contents))
        assertEquals(contents, portablePath("lab-corpus/", source, contents))
        assertFailsWith<IllegalStateException> { portableProblem(Problem("s", "p", model = "/outside/model"), source, contents) }
        assertFailsWith<IllegalArgumentException> { portablePath("lab-corpus/../escape", source, contents) }
    }

    @Test fun `preparation resolves files only after central filtering and keeps classifier output`(): Unit = service { socket ->
        val marker = root.resolve("resolved-names")
        val script = """#!/usr/bin/env bash
            set -eu
            corpus="${'$'}{JAVA_OPTS#*-Dklause.bench.corpusCache=}"; corpus="${'$'}{corpus%% *}"
            if [[ " ${'$'}* " == *' features=true '* ]]; then
                echo '{"suite":"s","collection":"fixture","problem":"p1","themes":["mip"]}'
            elif [[ " ${'$'}* " == *' files=true '* ]]; then
                [[ " ${'$'}* " == *' name=p1 '* ]]
                echo p1 > '$marker'
                echo approved > "${'$'}corpus/p1.mps"
                printf '{"suite":"s","collection":"fixture","problem":"p1","model":"%s/p1.mps","format":"mps"}\n' "${'$'}corpus"
            else
                echo '{"suite":"s","collection":"fixture","problem":"p0"}'
                echo '{"suite":"s","collection":"fixture","problem":"p1"}'
            fi
        """.trimIndent()
        val (bundle, bytes) = bundle(mapOf("klause-bench/build/install/klause-bench/bin/klause-bench" to script))
        val spec = ExperimentSpec("filtered", problems = listOf(mapOf("suite" to "s")), base = mapOf("backend" to "reference", "solver" to "highs"))
        val manifest = WorkManifest("prepare", "release", spec, Experiments.arms(spec).map { PlannedArm(it, bundle.commit) }, emptyList(),
            listOf(bundle), ResourceBudget(1000, 512), preparationTimeoutSec = 60)
        val executor = PreparationExecutor(ProcessSupervisor(socket), VerifiedCache(root.resolve("cache")), WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket)),
            { ByteArrayInputStream(bytes) }, bundle.os, bundle.arch)
        val result = executor.run(manifest, root.resolve("filtered"), { null }, { 80000 }, selectProblems = { raw ->
            assertFalse(Files.exists(marker)); assertTrue(raw.flatten().all { it.model == null })
            raw.map { group -> group.filter { it.problem == "p1" } }
        }) { hashFile(it) }
        assertEquals("p1", Files.readString(marker).trim())
        assertEquals(listOf("p1"), result.selections.flatten().map { it.problem })
        assertEquals(listOf("p1.mps"), result.corpus!!.files.map { it.path })
        assertEquals(listOf("mip"), result.features.single().themes)
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
        assertFalse(Files.readString(tree.resolve(".git/config")).contains("private-token"))
        assertFalse(git("for-each-ref", "--format=%(refname)", "refs/lab-source").isNotBlank())
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
        assertEquals(tree, cache.prepare(bundle, source, budget, System.getenv()) { 10000 })
        assertEquals(1, Files.readAllLines(count).size)
        Files.writeString(tree.resolve("build/output"), "corrupt")
        cache.prepare(bundle, source, budget, System.getenv()) { 10000 }
        assertEquals(2, Files.readAllLines(count).size)
        assertEquals(script, Files.readString(source.resolve("gradlew")))
        assertFailsWith<IllegalArgumentException> { WorkerBuild(listOf("--init-script=untrusted")) }
        assertFailsWith<IllegalArgumentException> { cache.prepare(bundle, source, budget, System.getenv()) { 0 } }
    }

    @Test fun `failed build preserves a bounded diagnostic and never publishes a cache entry`(): Unit = service { socket ->
        val (input, bytes) = bundle(mapOf("gradlew" to "echo expected-compile-failure >&2; exit 3"))
        val source = VerifiedCache(root.resolve("sources")).stage(input) { ByteArrayInputStream(bytes) }
        val failure = assertFailsWith<WorkerBuildFailure> {
            WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket)).prepare(input.copy(build = WorkerBuild(listOf("installDist"))), source,
                ResourceBudget(1000, 512), System.getenv()) { 10000 }
        }
        assertTrue(failure.detail.contains("expected-compile-failure")); assertTrue(failure.detail.contains("exit 3"))
        assertTrue(Files.list(root.resolve("builds")).use { files -> files.noneMatch(Files::isDirectory) })
    }

    @Test fun `standalone executor runs a complete paired iteration and leaves baseline unchanged`(): Unit = service { socket ->
        val script = """#!/usr/bin/env bash
            set -eu
            for arg in "${'$'}@"; do case "${'$'}arg" in out=*) out="${'$'}{arg#out=}";; esac; done
            mkdir -p "${'$'}out"
            echo '{}' > "${'$'}out/record.json"
        """.trimIndent()
        val (bundle, bytes) = bundle(mapOf("klause-bench/build/install/klause-bench/bin/klause-bench" to script))
        val spec = ExperimentSpec("paired", grid = mapOf("x" to listOf("a", "b")))
        val arms = Experiments.arms(spec).map { PlannedArm(it, "a".repeat(40)) }
        val cases = arms.indices.map { WorkCase(it, it, Problem("s", "p"), 1, 0, 5) }
        val manifest = WorkManifest("session", "fixture", spec, arms, cases, listOf(bundle), ResourceBudget(2000, 512, 2))
        val cache = VerifiedCache(root.resolve("cache"))
        val executor = IterationExecutor(ProcessSupervisor(socket), cache, { ByteArrayInputStream(bytes) }, "unused", bundle.os, bundle.arch)
        val result = executor.run(manifest, listOf(0, 1), root.resolve("attempt"), "node", 1) { 10000 }
        assertEquals(listOf(0, 0), result.cases.map { it.exitCode }); assertTrue(result.cases.all { it.record?.trim() == "{}" })
        assertEquals(script, Files.readString(cache.stage(bundle) { error("cache") }.resolve(bundle.files.single().path)))
        assertFailsWith<IllegalArgumentException> { executor.run(manifest, listOf(0), root.resolve("partial"), "node", 1) { 10000 } }
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
        assertFailsWith<ProfileRunTimeout> {
            WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(socket)).prepare(input.copy(build = WorkerBuild(listOf("fixture"))),
                source, ResourceBudget(1000, 512), System.getenv(), remainingRunMs = { (deadline - System.nanoTime()) / 1_000_000 }) { 80000 }
        }
        assertTrue(System.nanoTime() < deadline + 2_000_000_000)
    }

    @Test fun `solver timeout retains the existing thirty second capture flush allowance`(): Unit = service { socket ->
        val flushed = root.resolve("capture-flushed")
        val script = """
            #!/usr/bin/env python3
            import signal, time
            from pathlib import Path
            def flush(_signal, _frame):
                time.sleep(12)
                Path('$flushed').write_text('complete')
                raise SystemExit(0)
            signal.signal(signal.SIGTERM, flush)
            time.sleep(60)
        """.trimIndent()
        val (bundle, bytes) = bundle(mapOf("klause-bench/build/install/klause-bench/bin/klause-bench" to script))
        val spec = ExperimentSpec("flush")
        val arms = Experiments.arms(spec).map { PlannedArm(it, "a".repeat(40)) }
        val manifest = WorkManifest("session", "fixture", spec, arms, listOf(WorkCase(0, 0, Problem("s", "p"), null, 0, 1)), listOf(bundle), ResourceBudget(1000, 512))
        val executor = IterationExecutor(ProcessSupervisor(socket), VerifiedCache(root.resolve("cache")), { ByteArrayInputStream(bytes) }, "unused", bundle.os, bundle.arch)
        val result = executor.run(manifest, listOf(0), root.resolve("flush-attempt"), "node", 1) { 80000 }
        assertEquals(124, result.cases.single().exitCode)
        assertFalse(result.runTimedOut)
        assertEquals("complete", Files.readString(flushed))
    }

    @Test fun `independent supervisor kills detached children after client connection closes`(): Unit = service { socket ->
        val pid = root.resolve("escaped.pid")
        val code = "import subprocess,time; p=subprocess.Popen(['sleep','60'],start_new_session=True); open('${pid}','w').write(str(p.pid)); time.sleep(60)"
        val supervisor = ProcessSupervisor(socket)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val future = executor.submit<Int> { supervisor.run(listOf("python3", "-c", code), root, System.getenv(), root.resolve("out"), root.resolve("err"), 60) { 10000 } }
        try {
            val until = System.nanoTime() + 5_000_000_000
            while (!Files.exists(pid)) { check(System.nanoTime() < until); Thread.sleep(20) }
            val child = Files.readString(pid).toLong()
            future.cancel(true) // Socket closes exactly as it does on a killed worker; service survives.
            val stopped = System.nanoTime() + 5_000_000_000
            while (live(child) && System.nanoTime() < stopped) Thread.sleep(20)
            assertFalse(live(child), "detached child survived worker loss")
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
            val second = ProcessSupervisor(socket).reserve("job-a")
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

    @Test fun `supervisor reports bounded timeout and worker refuses an expired lease`(): Unit = service { socket ->
        val supervisor = ProcessSupervisor(socket)
        assertEquals(124, supervisor.run(listOf("sleep", "60"), root, System.getenv(), root.resolve("out"), root.resolve("err"), 1, 10) { 10000 })
        assertFailsWith<CancellationException> { supervisor.run(listOf("sleep", "60"), root, System.getenv(), root.resolve("out2"), root.resolve("err2"), 60) { 0 } }
    }
    private fun live(pid: Long): Boolean {
        if (!ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) return false
        val stat = Path.of("/proc/$pid/stat")
        return if (!CliMeasurements.localMacOs) runCatching { Files.readString(stat).substringAfterLast(")").trim().first() != 'Z' }.getOrDefault(false)
        else ProcessBuilder("ps", "-p", pid.toString(), "-o", "stat=").start().let { process ->
            val state = process.inputStream.bufferedReader().readText().trim(); process.waitFor(); state.isNotEmpty() && !state.startsWith("Z")
        }
    }
}
