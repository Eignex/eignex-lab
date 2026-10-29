package com.eignex.lab

import java.io.File
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Keeps the lab on the newest eignex-lab commit. At most every [intervalMs], or at once when [requestFile] exists (`lab
 * update` writes it), it fetches [source], the checkout the services were installed from, and finds whether its
 * upstream is ahead. An update never overlaps native work: while a worker holds the native execution lock the runner
 * asks [due] and, when it is, writes the yield file that tells native workers to stop after their current iteration;
 * once it holds the lock itself, between analysis jobs, it calls [maybeUpdate]. That runs `deploy/update.sh`, which pulls, builds a new
 * release, checks the host and restarts both services onto it, this process included, so a return from
 * [maybeUpdate] after an update started means the update failed and the running release stays.
 */
class SelfUpdater(
    private val source: Path?,
    private val intervalMs: Long,
    private val log: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val exec: (dir: File, cmd: List<String>, log: File?) -> Pair<Int, String> = ::execute,
    private val restart: () -> Unit = { exitProcess(EX_RESTART) },
    private val requestFile: File? = null,
) {
    private var lastCheck: Long? = null
    private var pending = false

    /** Whether an update is waiting: one found earlier, or one a check made now finds. */
    @Synchronized
    fun due(): Boolean {
        if (pending) return true
        val dir = source?.toFile() ?: return false
        val requested = requestFile?.exists() == true
        val now = clock()
        if (!requested && lastCheck?.let { now - it < intervalMs } == true) return false
        lastCheck = now
        requestFile?.delete()
        // Checked between a running job's cases, so off the solves' cores.
        if (exec(dir, Background.prefix + listOf("git", "fetch", "-q", "origin"), null).first != 0) {
            println("self-update: fetch failed")
            return false
        }
        val head = exec(dir, listOf("git", "rev-parse", "HEAD"), null).second.trim()
        val upstream = exec(dir, listOf("git", "rev-parse", "@{u}"), null).second.trim()
        pending = head.isNotEmpty() && upstream.isNotEmpty() && head != upstream
        if (pending) println("self-update: ${head.take(9)} -> ${upstream.take(9)} waiting for the runner to be free")
        return pending
    }

    /** Update now if one is [due]; call only with no job running. */
    @Synchronized
    fun maybeUpdate() {
        if (!due()) return
        val dir = source?.toFile() ?: return
        val exit = exec(dir, listOf("bash", "deploy/update.sh"), log).first
        if (exit == 0) {
            // update.sh restarts the services, but this process can outlive the signal and would keep running the
            // old release; exiting non-zero makes the service manager start it again on the new one.
            println("self-update: restarting on the new release")
            restart()
            return
        }
        // Not retried at once: a failed update would otherwise make every job yield to it in turn.
        pending = false
        println("self-update failed (exit $exit), staying on the running release; see ${log.name}")
    }
}

private fun execute(dir: File, cmd: List<String>, log: File?): Pair<Int, String> {
    val builder = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
    if (log != null) builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
    val process = builder.start()
    val out = if (log == null) process.inputStream.bufferedReader().readText() else ""
    return process.waitFor() to out
}

// A non-zero exit, so launchd and systemd (Restart=on-failure) start the runner again.
private const val EX_RESTART = 75
