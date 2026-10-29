package com.eignex.lab

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.test.*

/** Enabled only by connected_probe.py against its isolated TLS/ACL sandbox and local pinned registry. */
class ConnectedNomadTest {
    @Test fun `automatic queue builds selects stages corpus and solves after coordinator restart`() {
        assumeTrue(System.getenv("LAB_NOMAD_INTEGRATION") == "1")
        val state = Path.of(System.getenv("LAB_NOMAD_TEST_STATE"))
        val root = Files.createTempDirectory(state, "queue-")
        val store = Store(root.resolve("lab.db")); val artifacts = LocalArtifactStore(root.resolve("artifacts"))
        val client = NomadClient(URI.create(System.getenv("LAB_NOMAD_TEST_ADDRESS")), state.resolve("coordinator.token"),
            NomadClient.tlsClient(state.resolve("client.p12"), state.resolve("trust.p12"), state.resolve("store-password")))
        val repository = Files.createDirectory(root.resolve("source"))
        val fixture = """package fixture;
            import java.nio.file.*;
            public class Bench {
                public static void main(String[] args) throws Exception {
                    Path corpus = Path.of(System.getenv("LAB_CORPUS"));
                    if (args[0].equals("describe")) {
                        System.out.println("{\"id\":\"fixture\",\"description\":\"Connected queue fixture\"}");
                    } else if (args[0].equals("plan")) {
                        Files.createDirectories(corpus);
                        Files.writeString(corpus.resolve("model.mps"), "prepared-model");
                        System.out.println("{\"cases\":[{\"id\":\"p\",\"resources\":{\"cpus\":1,\"memoryMiB\":512,\"timeoutSeconds\":10},\"inputs\":[{\"path\":\"model.mps\",\"root\":\"CORPUS\",\"role\":\"model\"}]}]}");
                    } else {
                        if (!Files.readString(corpus.resolve("model.mps")).equals("prepared-model")) throw new Exception("corpus changed");
                        Thread.sleep(1000);
                        Files.writeString(Path.of(System.getenv("OUT"), "result.json"),
                            "{\"caseId\":\"p\",\"details\":{\"stats\":{\"preparedCorpus\":true}}}");
                    }
                }
            }
        """.trimIndent()
        Files.writeString(repository.resolve("Bench.java"), fixture)
        Files.writeString(repository.resolve("bench.json"),
            """{"main":"fixture.Bench","classpath":["build/classes"],"buildTasks":["installDist"]}""")
        Files.writeString(repository.resolve("gradlew"), """#!/usr/bin/env bash
            set -eu
            mkdir -p build/classes
            javac -d build/classes Bench.java
        """.trimIndent())
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-C", repository.toString()) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText(); check(process.waitFor() == 0) { output }; return output.trim()
        }
        git("init", "--initial-branch=main"); git("config", "user.name", "fixture"); git("config", "user.email", "fixture@example.com")
        git("add", "."); git("commit", "-m", "test: fixture")
        val sha = git("rev-parse", "HEAD")
        val spec = ExperimentSpec("automatic-http", bench = BenchSpec("bench.json"), grid = mapOf("param.x" to listOf("a", "b")),
            repeats = 2, host = Experiments.AWS_HOST)
        val job = store.create(spec.name, "main", emptyList(), parallel = 2, experiment = spec)
        val budget = ResourceBudget(2000, 2048, 2)
        val template = NomadBatchJob("template", NomadPlatform.LINUX, "lab-prototype-linux",
            NomadCpuCapacity(System.getenv("LAB_NOMAD_TEST_MHZ").toInt(), System.getenv("LAB_NOMAD_TEST_CPUS").toInt()), budget,
            listOf("worker"), System.getenv("LAB_NOMAD_TEST_IMAGE"), networkMode = "host", statusHostVolume = "lab-status", cacheHostVolume = "lab-cache")
        val port = System.getenv("LAB_NOMAD_TEST_API_PORT").toInt()
        val settings = NomadQueueSettings("connected-probe", "http://127.0.0.1:$port",
            listOf(NomadQueueTarget(template, setOf(if (workerArch() == "arm64") "arch:arm64" else "arch:x86_64"))),
            preparationBudget = ResourceBudget(1000, 1024), preparationTimeoutSec = 60, caseMemoryMiB = 512, sessionMemoryMiB = 256, maxSessionsPerJob = 2)
        val config = Config(dataDir = root, jobsDir = root.resolve("jobs"), minFreeBytes = 0, solveJavaOpts = "-Xmx256m")
        NomadBuildVolumes(client, settings).reconcile()
        assertTrue(client.hostVolumes().any { it["Name"]?.jsonPrimitive?.content == "lab-cache" })
        var publications = 0
        val source: (Job, List<NomadQueueTarget>, NomadQueueSettings) -> Pair<List<PlannedArm>, List<WorkerBundle>> = { _, _, _ ->
            publications++
            val archive = root.resolve("source.zip")
            val bundle = SourceInputs({ _, commit -> check(commit == sha) }).publish(repository, Repositories.DEFAULT, sha, "linux", workerArch(), "fixture",
                WorkerBuild(listOf("installDist")), archive)
            Files.newInputStream(archive).use { artifacts.put(job, bundle.sha256, Files.size(archive), 0, it) }
            Experiments.arms(spec).map { PlannedArm(it, sha) } to listOf(bundle)
        }
        var backend = NomadBackend(store, client, artifacts, { root.resolve("jobs/$it") })
        var queue = NomadQueue(config, store, backend, artifacts, settings, { true }, completed = {}, source = source)
        var server = embeddedServer(Netty, host = "127.0.0.1", port = port) { workerApi(store, backend, artifacts) { true } }.start()
        try {
            var restarted = false
            val deadline = System.nanoTime() + 120_000_000_000
            while (System.nanoTime() < deadline) {
                backend.reconcile()
                if (!restarted && store.execution.accepted(job).any { it.second.preparation != null }) {
                    server.stop(0, 1000)
                    val fresh = Store(root.resolve("lab.db"))
                    backend = NomadBackend(fresh, client, artifacts, { root.resolve("jobs/$it") })
                    queue = NomadQueue(config, fresh, backend, artifacts, settings, { true }, completed = {}, source = source)
                    server = embeddedServer(Netty, host = "127.0.0.1", port = port) { workerApi(fresh, backend, artifacts) { true } }.start()
                    restarted = true
                }
                queue.reconcile()
                if (store.job(job)!!.status in setOf(Status.DONE, Status.FAILED)) break
                Thread.sleep(250)
            }
            assertEquals(Status.DONE, store.job(job)!!.status, store.job(job)!!.error)
            assertTrue(restarted)
            assertEquals(1, publications)
            assertEquals(4, store.job(job)!!.done)
            assertTrue(store.job(job)!!.commands.all { it.cmd.contains("workspaces/") })
            assertEquals(1, store.execution.accepted(job).count { it.second.preparation != null })
            assertEquals(2, store.execution.accepted(job).count { it.second.preparation == null })
            assertTrue(store.execution.sessions(job).filter { it.executionClass == ExecutionClass.SOLVE }.none {
                store.execution.submission(it.id).contains("node.unique.id")
            }, "workers share the node within their CPU and memory requests")
            assertTrue(store.plans.caseRecordText(job, 0)!!.contains("preparedCorpus"))
            assertEquals("prepared-model", Files.readString(root.resolve("jobs/$job/nomad/prepared-corpus/model.mps")).trim())
            assertTrue(store.plans.casePlan(job).all { it.problem.model == "lab-corpus/model.mps" })
        } finally {
            store.requestCancel(job)
            for (session in store.execution.sessions(job)) client.stop(session.nomadJobId)
            server.stop(1000, 3000)
        }
    }

    @Test fun `real Nomad allocation authenticates runs a paired iteration and commits through HTTP`() {
        assumeTrue(System.getenv("LAB_NOMAD_INTEGRATION") == "1")
        val state = Path.of(System.getenv("LAB_NOMAD_TEST_STATE"))
        val root = Files.createTempDirectory(state, "lab-")
        val store = Store(root.resolve("lab.db")); val artifacts = LocalArtifactStore(root.resolve("artifacts"))
        val client = NomadClient(URI.create(System.getenv("LAB_NOMAD_TEST_ADDRESS")), state.resolve("coordinator.token"), NomadClient.tlsClient(state.resolve("client.p12"), state.resolve("trust.p12"), state.resolve("store-password")))
        val spec = ExperimentSpec("nomad-http", profile = ProfileMain(script = "profile", buildTasks = listOf("installDist")),
            grid = mapOf("x" to listOf("a", "b")), host = HostPlacement.AUTO)
        val script = """#!/usr/bin/env bash
            set -eu
            [[ "${'$'}{1:-}" != --help ]] || { echo fixture; exit 0; }
            out="${'$'}OUT"
            echo portable-worker-start
            sleep 3
            mkdir -p "${'$'}out"
            echo '{"stats":{"fixture":true}}' > "${'$'}out/record.json"
            echo portable-worker-output
        """.trimIndent()
        val repository = Files.createDirectory(root.resolve("source"))
        Files.writeString(repository.resolve("profile"), script)
        Files.setPosixFilePermissions(repository.resolve("profile"), PosixFilePermissions.fromString("rwxr-xr-x"))
        Files.writeString(repository.resolve("gradlew"), """#!/usr/bin/env bash
            set -eu
            python3 -c 'import time; end=time.monotonic()+0.25; x=1; exec("while time.monotonic()<end: x=(x+1)%1234567")'
        """.trimIndent())
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-C", repository.toString()) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText(); check(process.waitFor() == 0) { output }; return output.trim()
        }
        git("init", "--initial-branch=main"); git("config", "user.name", "fixture"); git("config", "user.email", "fixture@example.com")
        git("add", "."); git("commit", "-m", "test: fixture")
        val sha = git("rev-parse", "HEAD"); val arms = Experiments.arms(spec).map { PlannedArm(it, sha) }
        val problems = listOf(Problem("s", "p")); val cases = Experiments.cases(1, arms.size, emptyList())
        val job = store.create(spec.name, "main", emptyList(), experiment = spec)
        store.plan(job, arms, problems, cases, cases.map { "fixture" to 5L })
        val archive = root.resolve("source.zip")
        val bundle = SourceInputs({ _, commit -> check(commit == sha) }).publish(repository, spec.repository, sha,
            "linux", workerArch(), "fixture", WorkerBuild(listOf("installDist")), archive)
        Files.newInputStream(archive).use { artifacts.put(job, bundle.sha256, Files.size(archive), 0, it) }
        val sessionId = UUID.randomUUID().toString()
        val budget = ResourceBudget(1000, 1024, 1)
        val manifest = WorkManifest(sessionId, "connected-probe", spec, arms, cases.mapIndexed { i, c -> WorkCase(i, c.arm, problems[c.problem], c.seed, c.repeat, 5) }, listOf(bundle), budget)
        val template = NomadBatchJob("placeholder", NomadPlatform.LINUX, "lab-prototype-linux",
            NomadCpuCapacity(System.getenv("LAB_NOMAD_TEST_MHZ").toInt(), System.getenv("LAB_NOMAD_TEST_CPUS").toInt()), budget,
            listOf("worker"), System.getenv("LAB_NOMAD_TEST_IMAGE"), datacenter = "lab-prototype", networkMode = "host", statusHostVolume = "lab-status")
        var backend = NomadBackend(store, client, artifacts, { root.resolve("jobs/$it") })
        val port = System.getenv("LAB_NOMAD_TEST_API_PORT").toInt()
        var server = embeddedServer(Netty, host = "127.0.0.1", port = port) { workerApi(store, backend, artifacts) { true } }.start()
        val session = backend.enqueue(job, manifest, template, "http://127.0.0.1:$port")
        try {
            var restarted = false; var lost = false; var sawLive = false
            val deadline = System.nanoTime() + 120_000_000_000
            while (System.nanoTime() < deadline) {
                backend.reconcile()
                val current = store.job(job)!!
                if (current.commands.any { it.status == Status.RUNNING } && !Files.exists(root.resolve("jobs/$job/0.out"))) {
                    WorkerLogs.resolve(store, root.resolve("jobs/$job"), job, listOf("0.out"))?.let { file ->
                        if (Files.readString(file).contains("portable-worker-start")) sawLive = true
                    }
                }
                if (!restarted && sawLive && current.commands.first().status == Status.RUNNING) {
                    server.stop(0, 1000)
                    backend = NomadBackend(Store(root.resolve("lab.db")), client, artifacts, { root.resolve("jobs/$it") })
                    server = embeddedServer(Netty, host = "127.0.0.1", port = port) { workerApi(Store(root.resolve("lab.db")), backend, artifacts) { true } }.start()
                    restarted = true
                }
                if (restarted && !lost && current.commands[1].status == Status.RUNNING) {
                    val allocation = store.execution.session(session.id)!!.allocation!!
                    val docker = ProcessBuilder("docker", "ps", "-q", "--filter", "label=com.hashicorp.nomad.alloc_id=$allocation").start()
                    val container = docker.inputStream.bufferedReader().readText().trim(); check(docker.waitFor() == 0 && container.isNotEmpty())
                    check(ProcessBuilder("docker", "kill", "--signal=KILL", container).start().waitFor() == 0)
                    lost = true
                }
                if (store.job(job)!!.status in setOf(Status.DONE, Status.FAILED)) break
                Thread.sleep(250)
            }
            assertEquals(Status.DONE, store.job(job)!!.status, "allocations: ${client.allocations(session.nomadJobId)}")
            assertTrue(store.job(job)!!.commands.all { it.exitCode == 0 }, "every committed profile process succeeded")
            assertTrue(sawLive, "case output was visible before paired acceptance")
            assertTrue(Files.readString(root.resolve("jobs/$job/job.log")).contains("status: DONE"))
            assertTrue(restarted && lost, "both coordinator restart and forced allocation loss ran")
            assertTrue(store.execution.sessions(job).size >= 2)
            assertEquals(2, store.job(job)!!.done)
            assertEquals(1, store.execution.accepted(job).size)
            assertEquals(1, store.plans.cases(job).map { it.host }.distinct().size)
            assertTrue(Files.readString(root.resolve("jobs/$job/0.out")).contains("portable-worker-output"))
            assertTrue(Files.readString(root.resolve("jobs/$job/cases/0/out/record.json")).contains("fixture"))
            assertTrue(Files.list(root.resolve("jobs/$job/nomad")).use { files -> files.anyMatch { it.fileName.toString().endsWith("-2-resources.json") } }, "replacement committed generation 2")
            val acceptedAllocation = assertNotNull(store.execution.reports(job).single { it.committed > 0 }.session.allocation)
            val exited = System.nanoTime() + 10_000_000_000
            while (client.allocation(acceptedAllocation)?.client !in setOf("complete", "failed", "lost") && System.nanoTime() < exited) Thread.sleep(100)
            assertTrue(client.allocation(acceptedAllocation)?.client in setOf("complete", "failed", "lost"), "accepted worker exited")
            val attempts = state.resolve("data/alloc/$acceptedAllocation/worker/local/worker/attempts")
            assertTrue(!Files.exists(attempts) || Files.list(attempts).use { !it.findAny().isPresent }, "accepted private workspaces are released")
            // A fresh coordinator replays no submission and preserves accepted work.
            NomadBackend(Store(root.resolve("lab.db")), client, artifacts, { root.resolve("jobs/$it") }).reconcile()
            assertEquals(Status.DONE, store.job(job)!!.status)
        } finally {
            store.requestCancel(job)
            for (s in store.execution.sessions(job)) client.stop(s.nomadJobId)
            server.stop(1000, 3000)
        }
    }
}
