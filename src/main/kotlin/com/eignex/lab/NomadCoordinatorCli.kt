package com.eignex.lab

import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.netty.Netty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

@Serializable
data class NomadCoordinatorSettings(
    val address: String, val tokenFile: String, val keyStore: String, val trustStore: String, val passwordFile: String,
    val workerPort: Int = 8091, val bindAddress: String = "127.0.0.1", val namespace: String = "default",
    val privateNetwork: Boolean = false,
    val capacity: CapacityIntegration? = null,
    val privateBindAddress: String? = null,
) {
    init {
        require(workerPort in 1..65535)
        require(privateNetwork || bindAddress in setOf("127.0.0.1", "localhost", "::1")) { "non-loopback worker listener requires an authenticated private transport" }
        require(privateBindAddress == null || privateNetwork && privateBindAddress != bindAddress) { "additional listener requires a distinct authenticated private interface" }
        require(URI.create(address).scheme == "https") { "coordinator requires Nomad mTLS" }
    }
}

/** Reconciles the lab's Nomad sessions and capacity. */
object NomadCoordinatorCli {
    fun main(args: List<String>): Int = runCatching {
        require(args.size == 1) { "usage: nomad-coordinator SETTINGS.json" }
        val settings = Json.decodeFromString<NomadCoordinatorSettings>(Files.readString(Path.of(args.single())))
        val config = Config()
        val queueSettings = requireNotNull(config.nomadQueue) { "Nomad requires LAB_NOMAD_QUEUE_SETTINGS" }
        val nativeCapacity = queueSettings.targets.filter { it.os == "mac" }.minOfOrNull { it.template.budget.memoryMiB }
            ?.let { ResourceBudget(config.cores * 1000, it) }
        val volumeHealth = VolumeHealth(reason = { Volumes.hold(config) })
        volumeHealth.refresh()
        require(volumeHealth.problem() == null) { "coordinator volumes unavailable" }
        Files.createDirectories(config.dataDir)
        val store = Store(config.database)
        val disk = DiskGuard(volumeHealth::problem)
        thread(isDaemon = true, name = "nomad-volume-health") {
            while (!Thread.currentThread().isInterrupted) {
                volumeHealth.refresh()
                disk.allowsWork()
                Thread.sleep(5_000)
            }
        }
        // Actual writes retain synchronous mount/free-space validation, on the background/IO workers only.
        val journalRoot = config.dataDir.resolve("nomad/result-journal")
        Files.createDirectories(journalRoot.parent)
        java.nio.channels.FileChannel.open(config.dataDir, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        val artifacts = ResultJournal(journalRoot,
            LocalArtifactStore(config.dataDir.resolve("nomad/artifacts"), writable = { Volumes.hold(config) == null }),
            writable = { Files.getFileStore(journalRoot).usableSpace >= config.minFreeBytes })
        thread(isDaemon = true, name = "nomad-result-journal-status") {
            while (!Thread.currentThread().isInterrupted) {
                runCatching { writeResultJournalReport(config.dataDir.resolve("nomad/result-journal-status.json"), artifacts) }
                    .onFailure { System.err.println("result journal status: ${it.javaClass.simpleName}") }
                Thread.sleep(5_000)
            }
        }
        val admissionSignal = CoordinatorSignal()
        val reconcileSignal = CoordinatorSignal()
        val projections = CoordinatorWork<Pair<Long, String>, Unit>("nomad-result-files", completed = reconcileSignal::wake)
        val logWrites = CoordinatorWork<Long, Unit>("nomad-job-logs")
        val client = NomadClient(URI.create(settings.address), Path.of(settings.tokenFile), NomadClient.tlsClient(Path.of(settings.keyStore), Path.of(settings.trustStore), Path.of(settings.passwordFile)), settings.namespace)
        val checks = com.eignex.lab.checker.CaseChecks(store, { com.eignex.lab.checker.Checkers.of(config) }) { job, line ->
            config.createJobDir(job).resolve("setup.log").toFile().appendText("$line\n")
        }
        val checkPool = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "nomad-source-checks").apply { isDaemon = true } }
        val pendingChecks = java.util.concurrent.ConcurrentHashMap<Pair<Long, Int>, java.util.concurrent.Future<String>>()
        val capacitySettings = settings.capacity?.let { Json.decodeFromString<CapacitySettings>(Files.readString(Path.of(it.settingsFile))) }
        val backend = NomadBackend(store, client, artifacts, config::createJobDir, disk::allowsWork,
            changed = { admissionSignal.wake(); reconcileSignal.wake() },
            projectResult = { job, iteration, work -> projections.poll(job to iteration, work) != null },
            writeLog = { job, work -> logWrites.submit(job, work) }, checkCase = check@{ job, index ->
            val (problem, arm) = store.caseOf(job, index) ?: error("case missing")
            if (arm.values["backend"] != REFERENCE_BACKEND && !SourceValidation.requested(store.job(job)?.experiment, arm.values)) return@check "not-required"
            val key = job to index
            if (pendingChecks.size >= 64 && !pendingChecks.containsKey(key)) return@check null
            val future = pendingChecks.computeIfAbsent(key) {
                val task = object : java.util.concurrent.FutureTask<String>(java.util.concurrent.Callable {
                    if (arm.values["backend"] == REFERENCE_BACKEND) {
                        if (problem.collection.isNotEmpty()) store.caseRecordText(job, index)?.let { text ->
                            References.of(Json.parseToJsonElement(text))?.let { reference ->
                                store.putReferences(listOf((problem.referenceKey) to reference), "lab:$job")
                            }
                        }
                        "not-required"
                    } else {
                        checks.check(job, index, problem)?.json() ?: "unchecked: no applicable source checker"
                    }
                }) { override fun done() { reconcileSignal.wake() } }
                checkPool.execute(task)
                task
            }
            if (!future.isDone) null else {
                try { future.get() }
                catch (failure: java.util.concurrent.ExecutionException) {
                    if (failure.cause is SharedCheckInputUnavailable) return@check null
                    val detail = "unchecked: source checker failed (${failure.cause?.javaClass?.simpleName})"
                    config.createJobDir(job).resolve("setup.log").toFile().appendText("case $index: $detail\n")
                    detail
                } finally { pendingChecks.remove(key, future) }
            }
        }, placementPolicy = NomadPlacementPolicy(queueSettings)::apply, workerRuntime = NomadWorkerRuntime(queueSettings)::replace, nativeMayClaim = { _ -> !Files.exists(config.nativeYield) }, nativeCapacity = nativeCapacity, admit = { manifest, template ->
            if (template.platform == NomadPlatform.MAC) {
                require(manifest.budget.cpuMillis <= config.cores * 1000 && manifest.budget.parallel <= config.maxParallel)
            } else {
                require(template.networkMode == null && template.statusHostVolume == "lab-status") { "cloud workers require private container networking and read-only interruption status" }
                val pool = requireNotNull(capacitySettings?.pools?.singleOrNull { it.nodeClass == template.nodeClass }) { "cloud pool is not configured" }
                require(manifest.budget.cpuMillis <= pool.cpuMillis && manifest.budget.memoryMiB <= pool.availableMemoryMiB && template.cpuCapacity.logicalCpus == pool.vcpus)
                require(NodeTags.matches(manifest.experiment.nodeTags, pool.capabilities))
                require(pool.market == if (NodeTags.onDemand(false, manifest.experiment.nodeTags)) CapacityMarket.ON_DEMAND else CapacityMarket.SPOT)
            }
        }, nativeNodeTags = config.localNodeTags)
        // Git mirror publication stays serial; it must not block admission of already prepared experiments.
        val sourceWork = CoordinatorWork<Long, Pair<List<PlannedArm>, List<WorkerBundle>>>("nomad-source-publication", 1, admissionSignal::wake)
        val planWork = CoordinatorWork<Long, Unit>("nomad-preparation-plan", completed = admissionSignal::wake)
        val sources = TrustedQueueSources(config, artifacts, store)
        val queue = NomadQueue(config, store, backend, artifacts, queueSettings, disk::allowsWork,
            source = { job, targets, settings -> sourceWork.poll(job.id) { sources.publish(job, targets, settings) } },
            preparationPlan = { job, work -> planWork.poll(job, work) != null })
        val buildVolumes = NomadBuildVolumes(client, queueSettings)
        thread(isDaemon = true, name = "nomad-admission") {
            while (!Thread.currentThread().isInterrupted) {
                runCatching {
                    queue.reconcile()
                    val active = store.active(withCommands = false)
                    sourceWork.retain(active.map { it.id }.toSet())
                    planWork.retain(active.filter { !it.setupDone }.map { it.id }.toSet())
                }.onFailure { System.err.println("nomad admission: ${it.javaClass.simpleName}") }
                admissionSignal.await()
            }
        }
        val capacity = settings.capacity?.let { integration ->
            CapacityCollector(checkNotNull(capacitySettings), integration.inventoryFiles.map(Path::of),
                Path.of(integration.healthTokenFile), config.dataDir.resolve("nomad/capacity-state.json"), store, client, disk::allowsWork, config.localNodeTags)
        }
        // Capacity freshness must not depend on worker reconciliation, source checks or cache maintenance.
        if (capacity != null) thread(isDaemon = true, name = "nomad-capacity") {
            while (!Thread.currentThread().isInterrupted) {
                runCatching { capacity.collect() }.onFailure { System.err.println("nomad capacity: ${it.javaClass.simpleName}: ${it.message?.take(256)}") }
                Thread.sleep(5_000)
            }
        }
        val cleanup = NomadAllocationCleanup(client, store, queueSettings)
        thread(isDaemon = true, name = "nomad-allocation-cleanup") {
            while (!Thread.currentThread().isInterrupted) {
                runCatching { cleanup.reconcile() }.onFailure { System.err.println("nomad allocation cleanup: ${it.javaClass.simpleName}") }
                Thread.sleep(300_000)
            }
        }
        thread(isDaemon = true, name = "nomad-reconcile") {
            while (!Thread.currentThread().isInterrupted) {
                runCatching {
                    backend.reconcile()
                    runCatching { buildVolumes.reconcile() }.onFailure { System.err.println("nomad build cache: ${it.javaClass.simpleName}") }
                }.onFailure { System.err.println("nomad reconcile: ${it.javaClass.simpleName}") }
                reconcileSignal.await()
            }
        }
        embeddedServer(Netty, configure = {
            connectors.add(EngineConnectorBuilder().apply { host = settings.bindAddress; port = settings.workerPort })
            settings.privateBindAddress?.let { address -> connectors.add(EngineConnectorBuilder().apply { host = address; port = settings.workerPort }) }
            requestReadTimeoutSeconds = 30; responseWriteTimeoutSeconds = 30
        }) { workerApi(store, backend, artifacts, disk::allowsWork); capacity?.let { capacityApi(it, artifacts::status) } }.start(wait = true)
        0
    }.getOrElse { System.err.println("nomad-coordinator: ${it.message}"); 2 }
}
