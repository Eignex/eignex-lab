package com.eignex.lab

import kotlin.io.path.createDirectories
import kotlin.system.exitProcess

/**
 * `api` serves the queue over HTTP, `runner` works it, `check` only reports the host. The two services are
 * separate processes on purpose: a solve that takes the runner down leaves the queue inspectable, and the service
 * manager restarts the runner, which resumes where the store says it was.
 */
fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "help"
    if (mode == "password") {
        // A new password and the hash to commit as src/main/resources/lab-password; the password goes to the clients.
        val password = Password.generate()
        println("password: $password")
        println("hash:     ${Password.hash(password)}")
        return
    }
    if (mode !in setOf("api", "runner", "check")) {
        System.err.println("usage: klause-lab api|runner|check|password")
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
    println("klause-lab $mode: data=${config.dataDir} repo=${config.repoUrl} corpus=${config.corpusDir}")
    when (mode) {
        "api" -> {
            Background.self()
            serveApi(config, store, host)
        }
        "runner" -> Runner(config, store).apply { installShutdownHook() }.loop()
    }
}

/** sysexits' configuration error: the service manager is told not to restart on it. */
private const val EX_CONFIG = 78
