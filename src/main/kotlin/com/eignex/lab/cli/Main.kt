package com.eignex.lab.cli

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.web.*
import com.eignex.lab.worker.*

import com.eignex.lab.reference.ReferenceCli
import kotlin.io.path.createDirectories
import kotlin.system.exitProcess

/**
 * `api` serves the queue over HTTP, `runner` works it, `check` only reports the host. The two services are
 * separate processes on purpose: a solve that takes the runner down leaves the queue inspectable, and the service
 * manager restarts the runner, which resumes where the store says it was. `campaign-evidence` ([CampaignEvidence]) and
 * `docker-cleanup` ([DockerCleanup]) is a tool run by hand from a checkout, on any host, and
 * prunes the lab machine's Docker now instead of at the runner's next cleanup. `reference` ([ReferenceCli]) runs one
 * reference-solver case, as a reference arm's case command calls it. `nomad-spec` renders an operator's experimental
 * batch session without starting either service or opening the lab database. `capacity-export` writes the capacity
 * pools' contract ([CapacityContract]) the autoscaler, the provider adapters and their Terraform check themselves against.
 */
fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "help"
    when (mode) {
        "campaign-evidence" -> exitProcess(CampaignEvidence.main(args.drop(1)))
        "docker-cleanup" -> exitProcess(dockerCleanupNow())
        "reference" -> exitProcess(ReferenceCli.main(args.drop(1)))
        "nomad-coordinator" -> exitProcess(NomadCoordinatorCli.main(args.drop(1)))
        "worker" -> exitProcess(WorkerSession.main(args.drop(1)))
        "worker-local" -> exitProcess(WorkerCli.main(args.drop(1)))
        "nomad-spec" -> exitProcess(NomadSpecCli.main(args.drop(1)))
        "capacity-export" -> exitProcess(CapacityContract.main(args.drop(1)))
    }
    if (mode == "password") {
        // A new password and the hash to commit as src/main/resources/lab-password; the password goes to the clients.
        val password = Password.generate()
        println("password: $password")
        println("hash:     ${Password.hash(password)}")
        return
    }
    if (mode !in setOf("api", "runner", "check")) {
        System.err.println("usage: eignex-lab api|runner|check|password|campaign-evidence|docker-cleanup|reference|nomad-spec|worker-local|worker|nomad-coordinator|capacity-export")
        exitProcess(2)
    }
    // The CPU is probed once, here, and handed to everything that needs it.
    val cpu = NodeTags.probeLocal()
    val host = runCatching { hostReport(cpu) }.getOrElse { e ->
        System.err.println("refusing to start: ${e.message}")
        exitProcess(EX_CONFIG)
    }
    if (mode == "check") return
    val config = configOrExit(cpu)
    config.dataDir.createDirectories()
    if (config.nomadQueue == null) {
        System.err.println("refusing to start: LAB_NOMAD_QUEUE_SETTINGS is required; experiment execution uses Nomad")
        exitProcess(EX_CONFIG)
    }
    val store = Store(config.database)
    // Every directory the lab uses is probed from here on, on a thread of its own: the pages and the runner only read.
    Volumes.watch(config)
    println("eignex-lab $mode: data=${config.dataDir} repo=${config.repoUrl} corpus=${config.corpusDir}")
    when (mode) {
        "api" -> {
            Background.self()
            serveApi(config, store, host)
        }
        "runner" -> Runner(config, store).apply { installShutdownHook() }.loop()
    }
}

/**
 * `docker-cleanup`: one [DockerCleanup] now, whatever its interval, for a cron or launchd entry or by hand. It still
 * skips while a container runs, and keeps the tags the lab's queued and running jobs need when the lab's data is here.
 * Exits 0 after a cleanup, 1 when it skipped.
 */
private fun dockerCleanupNow(): Int {
    val config = configOrExit()
    val store = config.database.takeIf { it.toFile().isFile }?.let { Store(it) }
    val cleanup = if (store != null) {
        dockerCleanup(config, store, enabled = true, grep = mirrorGrep(config.mirror.toFile()))
    } else {
        DockerCleanup(
            true, config.dockerKeep, 0, config.dockerCleanupState, null, { emptySet() }, { freeBytes(config.dataDir) },
        )
    }
    return if (cleanup.run("it was run by hand") != null) 0 else 1
}

/** The settings of the environment ([Config.fromEnv]); a bad one stops the process here, naming every bad variable. */
private fun configOrExit(cpu: CpuCapabilities = CpuCapabilities()): Config = try {
    Config.fromEnv(cpu = cpu)
} catch (e: ConfigException) {
    System.err.println("refusing to start: ${e.message}")
    exitProcess(EX_CONFIG)
}

/** sysexits' configuration error: the service manager is told not to restart on it. */
private const val EX_CONFIG = 78
