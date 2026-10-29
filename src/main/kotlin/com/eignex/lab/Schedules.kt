package com.eignex.lab

import java.io.File
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/** Queues a run of each schedule whose ref moved, or that was asked to launch, fetching its repository's mirror. */
internal class Schedules(
    private val config: Config,
    private val store: Store,
    private val sourceUrl: (String) -> String = { Repositories.source(config, it) },
    private val stopping: () -> Boolean = { false },
) {
    private fun mirror(repository: String) = Repositories.mirror(config, repository)

    fun enqueue() {
        fun regular(s: Schedule) = s.checkedAt?.let { now() - it >= s.intervalSec * 1000 } ?: true
        // A requested launch is looked at on every poll until it queues, without moving the schedule's regular checks:
        // only a regular check stamps the time, so schedules staggered apart stay apart after a manual launch.
        for (schedule in store.schedules().filter { it.launchRequested || regular(it) }) {
            val checking = regular(schedule)
            if (checking) store.scheduleChecked(schedule.id, now())
            val blocked = schedule.blockedReason()
            if (blocked != null) {
                if (checking) println("schedule ${schedule.name}: blocked: $blocked; edit the saved experiment to resume")
                continue
            }
            // A launch asked for while the last run is still going waits for it rather than queueing a second; it stays
            // requested, so it queues on the first poll after that run ends.
            if (schedule.lastJob?.let(store::job)?.status?.isActive == true) continue
            val repository = schedule.experiment.repository
            Repositories.requireAllowed(repository)
            val mirror = mirror(repository)
            val advertised = advertised(schedule.ref, repository)
            if (!schedule.launchRequested && advertised != null && advertised == schedule.lastSha) continue
            val fetched = runCatching {
                withRetry("schedule ${schedule.name}: fetching") {
                    withMirrorLock(mirror) {
                        if (!mirror.exists()) {
                            mirror.parent.createDirectories()
                            capture(config.dataDir.toFile(), "git clone --mirror ${quote(sourceUrl(repository))} ${quote(mirror.toString())}")
                        }
                        capture(mirror.toFile(), "git remote set-url origin ${quote(sourceUrl(repository))}")
                        capture(mirror.toFile(), "git fetch --prune origin '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'")
                    }
                }
            }
            if (fetched.isFailure) {
                // Left due rather than checked, so the next poll tries again instead of a whole interval later.
                println("schedule ${schedule.name}: fetch failed, retrying on the next poll: ${fetched.exceptionOrNull()?.message}")
                if (checking) store.checkNow(schedule.id)
                continue
            }
            // A ref origin no longer has is not retried: it waits for the next interval like an unmoved one.
            val sha = runCatching {
                withMirrorLock(mirror) { capture(mirror.toFile(), "git rev-parse --verify ${quote(schedule.ref + "^{commit}")}") }
            }.getOrNull() ?: continue
            if (sha == schedule.lastSha && !schedule.launchRequested) continue
            val name = "${schedule.name}@${sha.take(9)}"
            // Every scheduled solver run asks for source validation ([SourceValidation.scheduled]), whatever spec the
            // schedule was saved with.
            val experiment = SourceValidation.scheduled(schedule.experiment)
            val run = experiment.copy(name = name, base = experiment.base + ("ref" to sha),
                configs = experiment.configs.map { it - "ref" }, grid = experiment.grid - "ref")
            // A schedule's runs stay on the local machine ([Placement.localOnly]) unless its spec opts in to the cloud
            // ([Placement.cloudOptIn]): its host says only whether it opted in, and the run is placed by the rules.
            val spec = run.copy(host = Placement.host(run, name, config, listOf(schedule)))
            val id = store.create(name, sha, emptyList(), spec.parallel ?: config.defaultParallel, spec.priority, spec)
            store.scheduleRan(schedule.id, sha, id)
            println("schedule ${schedule.name}: queued job $id for ${sha.take(9)}")
        }
    }

    /** The commit origin advertises for branch or tag [ref]; null when it names neither, or origin does not answer. */
    private fun advertised(ref: String, repository: String): String? = runCatching {
        capture(
            config.dataDir.toFile(),
            "GIT_TERMINAL_PROMPT=0 git ls-remote ${quote(sourceUrl(repository))} ${quote("refs/heads/$ref")} ${quote("refs/tags/$ref")}",
        )
    }.getOrNull()?.lineSequence()?.map { it.substringBefore('\t').trim() }?.firstOrNull(CommitSha::isValid)

    internal fun <T> withRetry(what: String, backoff: Backoff = config.retry, step: () -> T): T = retrying(
        backoff, onRetry = { attempt, wait, error ->
            println("$what failed (try $attempt): ${error.message}; retrying in ${wait / 1000} s")
        }, sleep = { wait ->
            val until = now() + wait
            while (now() < until) {
                check(!stopping()) { "runner stopping" }
                Thread.sleep((until - now()).coerceIn(1, 1000))
            }
        }, step = step)

    private fun capture(dir: File, cmd: String): String {
        val (exit, output) = runProcess(Background.prefix + listOf("bash", "-c", cmd), dir, GIT_TIMEOUT, mapOf("GIT_TERMINAL_PROMPT" to "0"))
        check(exit == 0) { "git operation failed: ${output.trim()}" }
        return output.trim()
    }
}
