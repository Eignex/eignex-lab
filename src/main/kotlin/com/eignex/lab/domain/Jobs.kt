package com.eignex.lab.domain

import com.eignex.lab.util.*

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/* The lab's job model: jobs, their commands and cases, schedules and holds, as the store records and returns them. */

@Serializable
enum class Status {
    QUEUED, RUNNING, DONE, FAILED, CANCELLED;

    /** Not ended: queued or running. */
    val isActive: Boolean get() = this == QUEUED || this == RUNNING

    /** Ended, done, failed or cancelled; a job never leaves this but by an explicit retry. */
    val isTerminal: Boolean get() = !isActive
}

@Serializable
data class Command(
    val index: Int,
    val cmd: String,
    val timeoutSec: Long,
    val status: Status,
    val exitCode: Int? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    /** Cores the command keeps busy: its arm's `processors`; the runner's core budget counts these. */
    val cores: Int = 1,
    /** Why the lab, or something else, ended its processes ([Termination]), each line with when; read from the job's
     *  directory for a single job's JSON, empty elsewhere. */
    val terminations: List<String> = emptyList(),
    /** The machine that holds or ran it: [RecordedHost.LOCAL] for the local machine, a cloud node's instance id;
     *  null while no host has taken it, and for a case run before the lab recorded where ([CaseResult.host]). */
    val host: RecordedHost? = null,
)

/** A command as planned: what runs, how long it may, and how many cores it holds. */
data class CaseCommand(val cmd: String, val timeoutSec: Long, val cores: Int = 1)

/** How many of a job's commands there are, and how many finished, failed and are running: its progress, without them.
 *  [timed] of the finished ones recorded when they started and ended, together [timedMs] of wall time. */
@Serializable
data class CommandCounts(val total: Int, val done: Int, val failed: Int, val running: Int, val timed: Int = 0, val timedMs: Long = 0)

@Serializable
data class Job(
    val id: Long,
    val name: String,
    val ref: String,
    val sha: String? = null,
    val status: Status,
    val cancelRequested: Boolean,
    val setupDone: Boolean,
    val createdAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val error: String? = null,
    /** vCPUs dedicated to the job ([ExperimentSpec.vcpus]), the stored `parallel` column. The queue rereads it, so it can
     *  change mid-job. Jobs from before it counted vCPUs counted cases at once: they now run one case fewer. */
    val vcpus: Int = 1,
    /** The runner takes the highest priority first, the oldest first within one. */
    val priority: Int = 0,
    /** A paused job is not started, and a running one stops starting commands and goes back to the queue. */
    val paused: Boolean = false,
    /** Set for an experiment, whose commands the runner writes from it when it plans the job. */
    val experiment: ExperimentSpec,
    val commands: List<Command> = emptyList(),
    /** Time spent running in earlier stretches: a job that yields or is paused stops its clock until taken again. */
    val runMs: Long = 0,
    /** When the current running stretch began; null while the job waits. */
    val runningSince: Long? = null,
    /** Set instead of [commands] by a list that leaves them out ([Store.jobs]). */
    val counts: CommandCounts? = null,
    /** Whether the local machine works on it now: it took the job, or joined it while cloud nodes ran it. */
    val local: Boolean = false,
    val backend: Backend = Backend.NOMAD,
    /** Why the job exists ([JobOrigin]); a job built without one, in a test, reads it from its [name]. */
    val origin: JobOrigin = JobOrigin.of(name),
) {
    init { require(counts == null || commands.isEmpty()) { "a job carries its commands or their counts, not both" } }

    /** Whether this is a list's summary of the job ([Store.jobs], [Store.active]): its [counts], not its [commands]. */
    val isSummary: Boolean get() = counts != null

    /** Whether the job has been planned: it has commands, loaded or counted. Never mistakes a summary for unplanned. */
    val planned: Boolean get() = total > 0

    /** Time the job has spent running, so far or in all; waiting in the queue, paused or yielded, does not count. */
    fun elapsedMs(now: Long): Long = runMs + (runningSince?.let { now - it } ?: 0)

    val total: Int get() = counts?.total ?: commands.size
    val done: Int get() = counts?.done ?: commands.count { it.status == Status.DONE || it.status == Status.FAILED }
    val failed: Int get() = counts?.failed ?: commands.count { it.status == Status.FAILED }
    val running: Int get() = counts?.running ?: commands.count { it.status == Status.RUNNING }
    /** Finished commands that recorded when they started and ended, and their wall time together: what a job's
     *  measured case time is read from ([Estimates]). */
    val timed: Int get() = counts?.timed ?: timedCommands().count()
    val timedMs: Long get() = counts?.timedMs ?: timedCommands().sumOf { it.finishedAt!! - it.startedAt!! }

    private fun timedCommands() = commands.asSequence()
        .filter { (it.status == Status.DONE || it.status == Status.FAILED) && it.startedAt != null && it.finishedAt != null }
}

enum class CancelOutcome { CANCELLED, REQUESTED, FINISHED, MISSING }

/**
 * One host's part of a job: its cases finished ([done], failed ones included), [running], and [claimed] but not started.
 * [host] is [RecordedHost.LOCAL] or a cloud node's instance id.
 */
@Serializable
data class HostProgress(val host: RecordedHost, val done: Int, val running: Int, val claimed: Int = 0) {
    /** In a few words: `local 10 done, 2 running`. */
    fun text(): String = "${host.text} $done done" + (if (running > 0) ", $running running" else "") + (if (claimed > 0) ", $claimed claimed" else "")
}

/** An experiment arm as planned: its configuration and the commit its ref resolved to. */
@Serializable
data class PlannedArm(val arm: Arm, val sha: String)

/** The store's job and accepted worker attempt for comparison qualification. */
data class CaseOrigin internal constructor(val job: Long, val attempt: String?)

/** One case of an experiment, with the record its `solve-one` wrote once it ran. */
@Serializable
data class CaseResult(
    val index: Int,
    val status: Status,
    val problem: Problem,
    val arm: String,
    val seed: Long? = null,
    val record: JsonElement? = null,
    /** Which of the identical runs of its (problem, arm, seed) this is, from 0. */
    val repeat: Int = 0,
    /** Whether its job was instrumented ([ExperimentSpec.instrumented]): its times are no runtime measurement. */
    val instrumented: Boolean = false,
    /**
     * The machine it ran on, or runs on: [RecordedHost.LOCAL] for the local machine, a cloud node's instance id. One
     * job's iterations may run on different machines, every arm of one iteration on the same one. A case run before
     * the lab recorded it reads as its job's host, [RecordedHost.LOCAL] or [RecordedHost.CLOUD] for an unknown node;
     * null while it waits.
     */
    val host: RecordedHost? = null,
    /** Suite-reported runtime debug state, or the historical arm policy; null when a suite makes no declaration. */
    val labDebug: Boolean? = null,
    /**
     * The native solver stdout its solve left beside its record (`cases/<index>/<name>.out`), each with its size and
     * SHA-256 as the lab holds it, from the local machine or brought back whole from a cloud node ([CaseFile.of]).
     * Empty for a case that left none, and for one finished before the lab recorded them.
     */
    val stdout: List<CaseFile> = emptyList(),
    /** Coordinator-owned pairing authority stays outside repository-reported JSON. */
    @Transient val origin: CaseOrigin? = null,
)

/** A file of a case under its job's directory: its [path] there, as `lab files` lists it, its size and its SHA-256. */
@Serializable
data class CaseFile(val path: String, val bytes: Long, val sha256: String) {
    companion object {
        /**
         * The native solver stdout files of case [index] under job directory [dir]: the regular `.out` files directly
         * in `cases/<index>/`, the bench's and a reference solve's raw output, each hashed as it is on disk.
         */
        fun stdout(dir: Path, index: Int): List<CaseFile> {
            val case = dir.resolve("cases").resolve(index.toString())
            if (!Files.isDirectory(case, LinkOption.NOFOLLOW_LINKS)) return emptyList()
            return Files.list(case).use { files ->
                files.filter { it.fileName.toString().endsWith(".out") && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList()
            }.sortedBy { it.fileName.toString() }.map { CaseFile(dir.relativize(it).joinToString("/"), Files.size(it), sha256(it)) }
        }

        /** The SHA-256 of [file], as lowercase hex, read in a stream. */
        fun sha256(file: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private const val BUFFER_BYTES = 1 shl 16
    }
}

/**
 * An experiment queued again whenever [ref] moves: the runner checks it at most every [intervalSec], and queues a run
 * with every arm at the commit the ref resolves to when that commit is not [lastSha] and the previous run has ended.
 */
@Serializable
data class Schedule(
    val id: Long,
    val name: String,
    val ref: String,
    val experiment: ExperimentSpec,
    /** [experiment]'s vCPUs (0 when it leaves them to the lab) and priority, which the schedule's runs take. */
    val vcpus: Int,
    val priority: Int,
    val intervalSec: Long,
    val lastSha: String? = null,
    val lastJob: Long? = null,
    val checkedAt: Long? = null,
    /** A run was asked for now: the next check queues one at the ref's commit even if that commit already ran. */
    val launchRequested: Boolean = false,
)

/**
 * A download's hold on job [job]'s files: until it lapses at [expiresAt], [JobTrim] leaves the job alone, however
 * short the disk. `lab fetch` takes one before it lists the files and releases it when done; one a crashed client
 * leaves behind lapses on its own.
 */
@Serializable
data class Hold(val id: String, val job: Long, val createdAt: Long, val expiresAt: Long)

/** Machine labels retained by historical case records; these are not execution backends. */
@Serializable(with = RecordedHostSerializer::class)
sealed interface RecordedHost {
    /** How the lab writes it, in the database and in a case's JSON. */
    val text: String

    data object Local : RecordedHost { override val text = LOCAL }

    /** A cloud node the lab no longer knows: a case that ran before cases recorded their node. */
    data object UnknownCloud : RecordedHost { override val text = CLOUD }

    data class Node(val instance: String) : RecordedHost {
        init { require(instance.isNotBlank() && instance != LOCAL && instance != CLOUD) { "not a node instance: $instance" } }
        override val text get() = instance
    }

    companion object {
        const val LOCAL = "local"
        const val CLOUD = "cloud"

        fun of(text: String): RecordedHost = when (text) {
            LOCAL -> Local
            CLOUD -> UnknownCloud
            else -> Node(text)
        }
    }
}

object RecordedHostSerializer : kotlinx.serialization.KSerializer<RecordedHost> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("com.eignex.lab.RecordedHost", kotlinx.serialization.descriptors.PrimitiveKind.STRING)
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: RecordedHost) = encoder.encodeString(value.text)
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): RecordedHost = RecordedHost.of(decoder.decodeString())
}

/** Which execution path owns a job: Nomad for every job since the lab moved to it, `legacy` for jobs run before. */
@Serializable
enum class Backend(val stored: String) {
    @SerialName("nomad") NOMAD("nomad"),
    @SerialName("legacy") LEGACY("legacy");

    companion object {
        fun of(stored: String): Backend = entries.firstOrNull { it.stored == stored } ?: error("unknown job backend $stored")
    }
}

/** Whether Nomad workers run [this] job: a Nomad job that is not one of the lab's own analysis jobs, which the runner runs. */
val Job.runsOnNomad: Boolean get() = backend == Backend.NOMAD && experiment.isAnalysis != true
