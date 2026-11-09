package com.eignex.lab.dev

import com.eignex.lab.capacity.CapacityCollector
import com.eignex.lab.domain.*
import com.eignex.lab.nomad.render
import com.eignex.lab.domain.NomadBatchJob
import com.eignex.lab.domain.NomadPlatform
import com.eignex.lab.store.*
import com.eignex.lab.util.now
import com.eignex.lab.util.writeAtomically
import com.eignex.lab.web.ApiServices
import com.eignex.lab.web.api
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Run with ./gradlew devUi. Neither this entry point nor its fake cluster is packaged in the application. */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: LocalUi <port> <scratch-root>" }
    val port = args[0].toInt().also { require(it in 1..65535) { "UI port must be between 1 and 65535" } }
    val scratch = Files.createDirectories(Path.of(args[1]))
    val root = Files.createTempDirectory(scratch, "run-")
    LocalUi(root, port).use { lab ->
        val server = embeddedServer(Netty, configure = {
            connectors.add(EngineConnectorBuilder().apply { host = "127.0.0.1"; this.port = lab.config.port })
            connectionGroupSize = 1; workerGroupSize = 2; callGroupSize = 2
        }) {
            api(lab.services)
            monitor.subscribe(ApplicationStopped) { lab.close() }
        }
        Runtime.getRuntime().addShutdownHook(Thread { server.stop(250, 2000); lab.close() })
        lab.start()
        println("\nLocal lab UI: http://127.0.0.1:$port/\nSynthetic data: $root\nFake Nomad: no workers, containers, cloud APIs, or SSH. Restart for fresh sample data.\n")
        server.start(wait = true)
    }
}

/** Real API/store/capacity collector around fixture-only job transitions and an in-memory NomadControl. */
internal class LocalUi(val root: Path, port: Int = 8080, private val clock: () -> Long = ::now) : AutoCloseable {
    val settings = CapacitySettings(listOf(
        CapacityPool("general-spot", "preview-amd64", 16, 65536, 4, setOf("arch:x86_64", "simd:avx512"),
            warmNodes = 1, sustainedMs = 0, cooldownMs = 0, idleMs = 1000),
        CapacityPool("arm-spot", "preview-arm64", 8, 32768, 4, setOf("arch:arm64", "simd:neon"),
            sustainedMs = 0, cooldownMs = 0, idleMs = 1000),
    ), 64)
    val config: Config
    val store: Store
    val host = HostReport("Local UI preview/arm64", 10, System.getProperty("java.runtime.version"))
    val nomad = FakeNomad(settings.pools, clock)
    val services: ApiServices
    private val fixtures: UiFixtures
    private val collector: CapacityCollector
    private val sessions = mutableMapOf<Long, String>()
    private val executor = Executors.newSingleThreadScheduledExecutor { work -> Thread(work, "local-ui-simulation").apply { isDaemon = true } }
    private var nextCasesAt = clock()
    private val closed = AtomicBoolean()

    init {
        Files.createDirectories(root.resolve("nomad"))
        require(!Files.exists(root.resolve("lab.db"))) { "Local UI requires a fresh scratch directory" }
        val capacityFile = root.resolve("nomad/capacity.json")
        Files.writeString(capacityFile, Json.encodeToString(settings))
        val queueFile = root.resolve("nomad/queue.json")
        val targets = settings.pools.map { pool ->
            NomadQueueTarget(template(pool), pool.tags)
        } + NomadQueueTarget(NomadBatchJob("preview-native", NomadPlatform.MAC, "preview-native",
            NomadCpuCapacity(24000, 8), ResourceBudget(8000, 16384), listOf("/preview/never-executed", "worker")),
            setOf("arch:arm64", "simd:neon"))
        Files.writeString(queueFile, Json.encodeToString(NomadQueueSettings("local-preview", "http://127.0.0.1:$port", targets)))
        config = Config(dataDir = root, corpusDir = root.resolve("corpus"), volumesRoot = root.resolve("volumes"),
            port = port, auth = Auth.Disabled, repoUrl = "https://github.com/Eignex/klause", minFreeBytes = 0,
            cores = 8, maxParallel = 8, defaultParallel = 4, updateCheckSec = 0, dockerCleanupDays = 0,
            cpuCapabilities = CpuCapabilities("arm64", setOf("asimd"), "Simulated preview node", true),
            nomadQueueSettings = queueFile, nomadCapacitySettings = capacityFile)
        store = Store(config.database)
        fixtures = UiFixtures(config, store, clock).also { it.seed() }
        val token = root.resolve("nomad/capacity.token").also { Files.writeString(it, "local-preview-token-".repeat(4)) }
        collector = CapacityCollector(settings, listOf(root.resolve("nomad/inventory.json")), token,
            root.resolve("nomad/capacity-state.json"), store, nomad, { true }, config.localNodeTags, clock)
        services = ApiServices(config, store, host, LocalArtifactStore(root.resolve("nomad/artifacts")))
        tick()
    }

    fun start() {
        executor.scheduleWithFixedDelay({
            try { tick() }
            catch (failure: Exception) { System.err.println("local UI simulation: ${failure.message}") }
        }, 1, 1, TimeUnit.SECONDS)
    }

    /** Exposed to tests so all time and capacity transitions can be checked without sleeping. */
    @Synchronized fun tick() {
        for ((job, sessionId) in sessions.toMap()) {
            if (store.job(job)?.status?.isActive != true) stopSession(job, sessionId)
        }
        for (job in store.active().sortedWith(compareBy<Job> { it.status != Status.RUNNING }.thenByDescending { it.priority }.thenBy { it.id })) {
            if (job.paused || job.cancelRequested || job.commands.isEmpty() || job.commands.any { !it.cmd.startsWith("preview solve ") }) continue
            if (job.id !in sessions) {
                val pool = settings.pools.firstOrNull { nomad.readyNode(it.nodeClass) != null } ?: continue
                val id = UUID.randomUUID().toString()
                val token = "local-preview-session-".repeat(3)
                val nomadId = "lab-${job.id}-${ExecutionClass.SOLVE}-$id-1"
                val payload = template(pool).copy(id = nomadId, budget = ResourceBudget(4000, 4096, 2)).render()
                val session = store.execution.createSession(id, job.id, ExecutionClass.SOLVE, "local-preview", ResourceBudget(4000, 4096, 2), token, payload.toString())
                nomad.submit(payload)
                val allocation = nomad.allocations(nomadId).single()
                store.execution.bind(id, token, allocation.id, allocation.node, "local-preview", clock())
                store.execution.acknowledge("$id:submit")
                sessions[job.id] = session.id
            }
            if (clock() < nextCasesAt) continue
            val cases = store.plans.casePlan(job.id).filter { it.status == Status.QUEUED }
            val first = cases.firstOrNull()
            if (first == null) {
                store.finish(job.id, Status.DONE)
                stopSession(job.id, sessions.getValue(job.id))
            } else {
                val node = checkNotNull(store.execution.session(sessions.getValue(job.id))?.node)
                // A simulated row completes all its arms on the same node, seed and repeat.
                for (case in cases.filter { it.problem == first.problem && it.seed == first.seed && it.repeat == first.repeat })
                    fixtures.complete(job.id, case, 7, node)
                store.execution.observed(sessions.getValue(job.id), "Simulated worker: paired row ${first.problem.problem} completed", clock())
            }
        }
        if (clock() >= nextCasesAt) nextCasesAt = clock() + 3000
        root.resolve("nomad/inventory.json").writeAtomically(Json.encodeToString(nomad.inventory()))
        collector.collect()
        nomad.scale(collector.snapshot().decision.desired)
        root.resolve("nomad/result-journal-status.json").writeAtomically(Json.encodeToString(
            ResultJournalReport(clock(), ResultJournal.Status(0, 8192, 128L * 1024 * 1024, null, 0, 0))))
    }

    private fun stopSession(job: Long, id: String) {
        val session = store.execution.session(id) ?: return
        nomad.stop(session.nomadJobId)
        // Fixture outcomes bypass worker ingestion, so finish their synthetic session and acknowledge its stop here.
        store.executionTransaction { db ->
            db.sqlUpdate("UPDATE execution_sessions SET desired = 'stop' WHERE id = ?", id)
            db.sqlUpdate("INSERT OR REPLACE INTO execution_outbox (id, job_id, session_id, operation, payload, acknowledged) VALUES (?, ?, ?, 'stop', ?, 1)",
                "$id:stop", job, id, session.nomadJobId)
        }
        sessions.remove(job)
    }

    private fun template(pool: CapacityPool): NomadBatchJob = NomadBatchJob("preview-worker", NomadPlatform.LINUX, pool.nodeClass,
        NomadCpuCapacity(pool.vcpus * 3000, pool.vcpus), ResourceBudget(pool.cpuMillis, pool.availableMemoryMiB), listOf("worker"),
        "example.invalid/never-pulled@sha256:" + "0".repeat(64))

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
        services.close()
    }
}
