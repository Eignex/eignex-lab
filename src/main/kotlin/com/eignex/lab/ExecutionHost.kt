package com.eignex.lab

import java.nio.file.Path

/**
 * Where an experiment's builds and cases run: the lab machine itself, or an AWS instance ([AwsHost]). Planning and
 * dispatch are the same for both; a host says where the worktrees and the corpus are as the cases see them, how many
 * cores its cases may hold, how to ask the bench for a selection, and how to run one case so that its record lands in
 * the job's local directory.
 */
interface ExecutionHost {
    /** Cores the running cases may hold together. */
    val cores: Int

    /** The most cases it runs at once, whatever a job asks for: memory bounds this on the lab machine, cores on AWS. */
    val maxParallel: Int

    /** The corpus cache as the cases see it. */
    val corpus: String

    /** Whether a job here yields between cases for a lab update: only a job on the lab machine shares it with the build. */
    val yieldsToUpdates: Boolean

    /** Whether a job here yields to a higher-priority job waiting for the same host; any job still yields to a pause. */
    val yieldsToPriority: Boolean

    /** The worktree of commit [sha] as the cases see it. */
    fun worktree(sha: String): String

    /** `klause-bench select <args>` in [worktree], its output lines. */
    fun select(worktree: String, args: String): List<String>

    /** Run [command] to its end and return its exit (a cancel or a timeout has its own); its record is then under
     *  `<dir>/cases/<index>/`. */
    fun run(command: Command, dir: Path): Int
}

/**
 * The host went away under a running job: a spot instance AWS reclaimed. Its cases did not fail; they go back to the
 * job's queue for another host to run ([Runner.dispatch]).
 */
class HostLost(message: String) : Exception(message)
