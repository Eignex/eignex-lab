package com.eignex.lab

import com.eignex.lab.reference.ReferenceCli
import kotlin.io.path.createDirectories
import kotlin.system.exitProcess

/**
 * `api` serves the queue over HTTP, `runner` works it, `check` only reports the host. The two services are
 * separate processes on purpose: a solve that takes the runner down leaves the queue inspectable, and the service
 * manager restarts the runner, which resumes where the store says it was. `campaign-evidence` ([CampaignEvidence]) and
 * `make-sets` ([MakeSets]) are tools run by hand from a checkout, on any host, and `docker-cleanup` ([DockerCleanup])
 * prunes the lab machine's Docker now instead of at the runner's next cleanup. `reference` ([ReferenceCli]) runs one
 * reference-solver case, as a reference arm's case command calls it. `nomad-spec` renders an operator's experimental
 * batch session without starting either service or opening the lab database.
 */
fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "help"
    when (mode) {
        "campaign-evidence" -> exitProcess(CampaignEvidence.main(args.drop(1)))
        "make-sets" -> exitProcess(MakeSets.main(args.drop(1)))
        "docker-cleanup" -> exitProcess(dockerCleanupNow())
        "reference" -> exitProcess(ReferenceCli.main(args.drop(1)))
        "worker-local" -> exitProcess(WorkerCli.main(args.drop(1)))
        "nomad-spec" -> exitProcess(NomadSpecCli.main(args.drop(1)))
    }
    if (mode == "password") {
        // A new password and the hash to commit as src/main/resources/lab-password; the password goes to the clients.
        val password = Password.generate()
        println("password: $password")
        println("hash:     ${Password.hash(password)}")
        return
    }
    if (mode !in setOf("api", "runner", "check")) {
        System.err.println("usage: eignex-lab api|runner|check|password|campaign-evidence|make-sets|docker-cleanup|reference|nomad-spec|worker-local")
        exitProcess(2)
    }
    val host = runCatching { requireAcceleratedHost() }.getOrElse { e ->
        System.err.println("refusing to start: ${e.message}")
        exitProcess(EX_CONFIG)
    }
    if (mode == "check") return
    val config = Config()
    config.dataDir.createDirectories()
    val store = Store(config.database)
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
    val config = Config()
    val store = config.database.takeIf { it.toFile().isFile }?.let { Store(it) }
    val cleanup = if (store != null) {
        dockerCleanup(config, store, enabled = true, grep = mirrorGrep(config.mirror.toFile()))
    } else {
        DockerCleanup(
            true, config.dockerKeep, 0, config.dockerCleanupState, null, { emptySet() }, { freeBytes(config.dataDir) },
            config.colimaDisks.takeIf { config.colima },
        )
    }
    return if (cleanup.run("it was run by hand") != null) 0 else 1
}

/** sysexits' configuration error: the service manager is told not to restart on it. */
private const val EX_CONFIG = 78
