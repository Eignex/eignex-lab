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
) {
    init {
        require(workerPort in 1..65535)
        require(privateNetwork || bindAddress in setOf("127.0.0.1", "localhost", "::1")) { "non-loopback worker listener requires an authenticated private transport" }
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
        require(Volumes.hold(config) == null) { "coordinator volumes unavailable" }
        Files.createDirectories(config.dataDir)
        val store = Store(config.database)
        val disk = DiskGuard { Volumes.hold(config) }
        val artifacts = LocalArtifactStore(config.dataDir.resolve("nomad/artifacts"), writable = disk::allowsWork)
        val client = NomadClient(URI.create(settings.address), Path.of(settings.tokenFile), NomadClient.tlsClient(Path.of(settings.keyStore), Path.of(settings.trustStore), Path.of(settings.passwordFile)), settings.namespace)
        val checks = com.eignex.lab.checker.CaseChecks(store, { com.eignex.lab.checker.Checkers.of(config) }) { job, line ->
            config.createJobDir(job).resolve("setup.log").toFile().appendText("$line\n")
        }
        val checkPool = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "nomad-source-checks").apply { isDaemon = true } }
        val pendingChecks = java.util.concurrent.ConcurrentHashMap<Pair<Long, Int>, java.util.concurrent.Future<String>>()
        val capacitySettings = settings.capacity?.let { Json.decodeFromString<CapacitySettings>(Files.readString(Path.of(it.settingsFile))) }
        val backend = NomadBackend(store, client, artifacts, config::jobDir, disk::allowsWork, checkCase = check@{ job, index ->
            val (problem, arm) = store.caseOf(job, index) ?: error("case missing")
            if (arm.values["backend"] != REFERENCE_BACKEND && !SourceValidation.requested(arm.values)) return@check "not-required"
            val key = job to index
            if (pendingChecks.size >= 64 && !pendingChecks.containsKey(key)) return@check null
            val future = pendingChecks.computeIfAbsent(key) {
                checkPool.submit<String> {
                    if (arm.values["backend"] == REFERENCE_BACKEND) {
                        if (problem.collection.isNotEmpty()) store.caseRecordText(job, index)?.let { text ->
                            References.of(Json.parseToJsonElement(text))?.let { reference ->
                                store.putReferences(listOf((problem.collection to problem.problem) to reference), "lab:$job")
                            }
                        }
                        "not-required"
                    } else checks.check(job, index, problem)?.json() ?: "unchecked: no applicable source checker"
                }
            }
            if (!future.isDone) null else {
                try { future.get() }
                catch (failure: java.util.concurrent.ExecutionException) {
                    val detail = "unchecked: source checker failed (${failure.cause?.javaClass?.simpleName})"
                    config.createJobDir(job).resolve("setup.log").toFile().appendText("case $index: $detail\n")
                    detail
                } finally { pendingChecks.remove(key, future) }
            }
        }, nativeMayClaim = { _ -> !Files.exists(config.nativeYield) }, nativeCapacity = nativeCapacity, admit = { manifest, template ->
            if (template.platform == NomadPlatform.MAC) {
                require(manifest.budget.cpuMillis <= config.cores * 1000 && manifest.budget.parallel <= config.maxParallel)
            } else {
                require(template.networkMode == null && template.statusHostVolume == "lab-status") { "cloud workers require private container networking and read-only interruption status" }
                val pool = requireNotNull(capacitySettings?.pools?.singleOrNull { it.nodeClass == template.nodeClass }) { "cloud pool is not configured" }
                require(manifest.budget.cpuMillis <= pool.cpuMillis && manifest.budget.memoryMiB <= pool.availableMemoryMiB && template.cpuCapacity.logicalCpus == pool.vcpus)
                require(NodeTags.matches(manifest.experiment.nodeTags, pool.capabilities))
                require(pool.market == if (NodeTags.onDemand(false, manifest.experiment.nodeTags)) CapacityMarket.ON_DEMAND else CapacityMarket.SPOT)
            }
        })
        val queue = NomadQueue(config, store, backend, artifacts, queueSettings, disk::allowsWork)
        val buildVolumes = NomadBuildVolumes(client, queueSettings)
        thread(isDaemon = true, name = "nomad-admission") {
            while (!Thread.currentThread().isInterrupted) {
                runCatching { queue.reconcile() }.onFailure { System.err.println("nomad admission: ${it.javaClass.simpleName}") }
                Thread.sleep(10_000)
            }
        }
        val capacity = settings.capacity?.let { integration ->
            CapacityCollector(checkNotNull(capacitySettings), integration.inventoryFiles.map(Path::of),
                Path.of(integration.healthTokenFile), config.dataDir.resolve("nomad/capacity-state.json"), store, client, disk::allowsWork, config.localNodeTags)
        }
        thread(isDaemon = true, name = "nomad-reconcile") {
            while (!Thread.currentThread().isInterrupted) {
                runCatching {
                    backend.reconcile()
                    runCatching { buildVolumes.reconcile() }.onFailure { System.err.println("nomad build cache: ${it.javaClass.simpleName}") }
                    capacity?.collect()
                }.onFailure { System.err.println("nomad reconcile: ${it.javaClass.simpleName}") }
                Thread.sleep(10_000)
            }
        }
        embeddedServer(Netty, configure = {
            connectors.add(EngineConnectorBuilder().apply { host = settings.bindAddress; port = settings.workerPort })
            requestReadTimeoutSeconds = 30; responseWriteTimeoutSeconds = 30
        }) { workerApi(store, backend, artifacts, disk::allowsWork); capacity?.let { capacityApi(it) } }.start(wait = true)
        0
    }.getOrElse { System.err.println("nomad-coordinator: ${it.message}"); 2 }
}
