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
) {
    init {
        require(workerPort in 1..65535)
        require(privateNetwork || bindAddress in setOf("127.0.0.1", "localhost", "::1")) { "non-loopback worker listener requires an authenticated private transport" }
        require(URI.create(address).scheme == "https") { "coordinator requires Nomad mTLS" }
    }
}

/** Reconciles only explicitly admitted sessions. This mode does not change the legacy submission default. */
object NomadCoordinatorCli {
    fun main(args: List<String>): Int = runCatching {
        require(args.size == 1) { "usage: nomad-coordinator SETTINGS.json" }
        val settings = Json.decodeFromString<NomadCoordinatorSettings>(Files.readString(Path.of(args.single())))
        val config = Config()
        require(Volumes.hold(config) == null) { "coordinator volumes unavailable" }
        Files.createDirectories(config.dataDir)
        val store = Store(config.database)
        val disk = DiskGuard { Volumes.hold(config) }
        val artifacts = LocalArtifactStore(config.dataDir.resolve("nomad/artifacts"), writable = disk::allowsWork)
        val client = NomadClient(URI.create(settings.address), Path.of(settings.tokenFile), NomadClient.tlsClient(Path.of(settings.keyStore), Path.of(settings.trustStore), Path.of(settings.passwordFile)), settings.namespace)
        val checks = com.eignex.lab.checker.CaseChecks(store, { com.eignex.lab.checker.Checkers.of(config) }) { job, line ->
            config.createJobDir(job).resolve("setup.log").toFile().appendText("$line\n")
        }
        val backend = NomadBackend(store, client, artifacts, config::jobDir, disk::allowsWork) { job, index ->
            val (problem, arm) = store.caseOf(job, index) ?: error("case missing")
            if (arm.values["backend"] == REFERENCE_BACKEND || !SourceValidation.requested(arm.values)) "not-required"
            else checks.check(job, index, problem)?.json() ?: "unchecked: no applicable source checker"
        }
        thread(isDaemon = true, name = "nomad-reconcile") {
            while (!Thread.currentThread().isInterrupted) {
                runCatching { backend.reconcile() }.onFailure { System.err.println("nomad reconcile: ${it.javaClass.simpleName}") }
                Thread.sleep(10_000)
            }
        }
        embeddedServer(Netty, configure = {
            connectors.add(EngineConnectorBuilder().apply { host = settings.bindAddress; port = settings.workerPort })
            requestReadTimeoutSeconds = 30; responseWriteTimeoutSeconds = 30
        }) { workerApi(store, backend, artifacts, disk::allowsWork) }.start(wait = true)
        0
    }.getOrElse { System.err.println("nomad-coordinator: ${it.message}"); 2 }
}
