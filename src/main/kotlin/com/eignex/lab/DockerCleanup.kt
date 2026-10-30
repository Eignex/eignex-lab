package com.eignex.lab

import java.io.File

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** One line of `docker system df`: what a kind of Docker object (images, containers, local volumes, build cache)
 *  holds and how much of it nothing uses. A size Docker printed in a form not understood is null. */
@Serializable
data class DockerUsage(val type: String, val bytes: Long?, val reclaimableBytes: Long?)

/** What one cleanup did: when and why, what it removed, and Docker's and the host disk file's size before and after. */
@Serializable
data class DockerCleanupRun(
    val at: Long,
    val why: String,
    /** The superseded reference image tags removed, `repository:tag`. */
    val removedTags: List<String> = emptyList(),
    /** Images `docker image prune` deleted. */
    val prunedImages: Int = 0,
    /** What `docker builder prune` said it reclaimed, as Docker printed it. */
    val buildCacheReclaimed: String? = null,
    val dockerBefore: List<DockerUsage> = emptyList(),
    val dockerAfter: List<DockerUsage> = emptyList(),
    /** Allocated bytes of Colima's VM disks on the host, before the cleanup and after the trim; null off Colima. */
    val hostDiskBefore: Long? = null,
    val hostDiskAfter: Long? = null,
    /** What `fstrim` in the Colima VM reported. */
    val trimmed: String? = null,
    /** Steps skipped or failed, in words. */
    val notes: List<String> = emptyList(),
)

/**
 * Keeps the reference solvers' Docker images from filling the disk. Rebuilding an image leaves its old layers, its
 * build cache and its old tags behind. A cleanup removes build cache older than a week, the tags of the reference
 * repositories that are neither kept nor named by an active job's commit, and dangling images. Historical VM
 * measurements remain readable in old cleanup reports.
 *
 * It is due [intervalMs] after the last one, sooner when the disk has under [lowFreeBytes] free (at most
 * every [LOW_DISK_GAP_MS] then), and at once when [requestFile] exists. The runner calls [maybeRun] between jobs, with
 * nothing of its own running; a cleanup still skips while any container runs, a reference case of a solve started by
 * hand among them, and tries again [RETRY_MS] later. Never removed: the [keep] tags, every repository's `latest`, the
 * tags [needed] finds in the queued and running jobs' commits, and any image outside the repositories [keep] names,
 * except dangling ones. When [needed] cannot tell, no tag is removed. The last run is written to [stateFile], where
 * the disk page reads it.
 */
class DockerCleanup(
    private val enabled: Boolean,
    private val keep: Set<String>,
    private val intervalMs: Long,
    private val stateFile: Path,
    private val requestFile: Path?,
    /** The reference image tags the queued and running jobs' commits name, or null when one of them could not be read. */
    private val needed: () -> Set<String>?,
    private val freeBytes: () -> Long,
    private val log: (String) -> Unit = ::println,
    /** Below this much free a cleanup comes sooner: the job files' target ([Config.jobsTargetFreeBytes]). */
    private val lowFreeBytes: Long = JobTrim.TARGET_FREE_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val exec: (List<String>) -> Pair<Int, String> = ::execute,
) {
    private var lastAttempt: Long? = null

    /** The repositories the cleanup looks after: those of the [keep] tags. */
    private val repositories: Set<String> = keep.map { repository(it) }.toSet()

    /** The last cleanup, from [stateFile]; null before the first. */
    fun last(): DockerCleanupRun? = Companion.last(stateFile)

    /** Why a cleanup is due now, or null when it is not. */
    fun due(): String? {
        if (!enabled) return null
        val now = clock()
        val requested = requestFile?.exists() == true
        if (!requested && lastAttempt?.let { now - it < RETRY_MS } == true) return null
        if (requested) return "it was requested"
        if (intervalMs <= 0) return null
        val last = last()?.at
        if (last == null) return "no cleanup has run yet"
        if (now - last >= intervalMs) return "the last cleanup was over ${intervalMs / DAY_MS} days ago"
        if (freeBytes() < lowFreeBytes && now - last >= LOW_DISK_GAP_MS) {
            return "the disk had under ${lowFreeBytes / BYTES_PER_GB} GB free"
        }
        return null
    }

    /** Clean up if one is [due]; call only with no job of the runner's running. */
    fun maybeRun(): DockerCleanupRun? {
        val why = due() ?: return null
        lastAttempt = clock()
        return run(why)
    }

    /** Clean up now, unless Docker does not answer or a container runs; returns the run, or null when skipped. */
    fun run(why: String): DockerCleanupRun? {
        val (psExit, ps) = exec(listOf("docker", "ps", "--format", "{{.Image}}"))
        if (psExit != 0) {
            log("docker cleanup: skipped, docker does not answer: ${ps.lineSequence().firstOrNull().orEmpty()}")
            return null
        }
        val busy = ps.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (busy.isNotEmpty()) {
            log("docker cleanup: skipped, containers are running (${busy.distinct().joinToString()})")
            return null
        }
        log("docker cleanup: starting because $why")
        val notes = mutableListOf<String>()
        val before = df()

        val (cacheExit, cacheOut) = exec(listOf("docker", "builder", "prune", "-af", "--filter", "until=$BUILD_CACHE_AGE"))
        val cache = if (cacheExit == 0) reclaimed(cacheOut) else null.also { notes += "builder prune failed: ${firstLine(cacheOut)}" }

        val removed = mutableListOf<String>()
        val needs = runCatching { needed() }.getOrNull()
        if (needs == null) {
            notes += "kept every tag: the queued and running jobs' image references could not be read"
        } else {
            val (listExit, listing) = exec(listOf("docker", "images", "--format", IMAGE_FORMAT))
            if (listExit != 0) notes += "listing images failed: ${firstLine(listing)}"
            else for (tag in superseded(parseImages(listing), repositories, keep + needs)) {
                // No force: an image a container still uses, even a stopped one, stays.
                val (exit, out) = exec(listOf("docker", "rmi", tag))
                if (exit == 0) removed += tag else notes += "could not remove $tag: ${firstLine(out)}"
            }
        }

        val (pruneExit, pruneOut) = exec(listOf("docker", "image", "prune", "-f"))
        val pruned = if (pruneExit == 0) pruneOut.lines().count { it.trim().startsWith("deleted:") }
        else 0.also { notes += "image prune failed: ${firstLine(pruneOut)}" }

        val after = df()
        val run = DockerCleanupRun(
            clock(), why, removed, pruned, cache, before, after, notes = notes,
        )
        stateFile.parent?.let { Files.createDirectories(it) }
        stateFile.writeAtomically(Json.encodeToString(DockerCleanupRun.serializer(), run))
        requestFile?.let { Files.deleteIfExists(it) }
        log(describe(run))
        return run
    }

    /** `docker system df`, line by line; empty when Docker does not answer. */
    fun df(): List<DockerUsage> {
        val (exit, out) = exec(listOf("docker", "system", "df", "--format", "{{json .}}"))
        return if (exit == 0) parseDf(out) else emptyList()
    }

    private fun firstLine(text: String) = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    internal companion object {
        const val DAY_MS = 24 * 3_600_000L
        const val LOW_DISK_GAP_MS = 6 * 3_600_000L
        const val RETRY_MS = 10 * 60_000L
        const val BUILD_CACHE_AGE = "168h"
        const val IMAGE_FORMAT = "{{.Repository}}\t{{.Tag}}\t{{.ID}}"
        const val DEFAULT_KEEP = Defaults.DOCKER_KEEP
        private const val KIB = 1024L

        /** The last cleanup written to [stateFile]; null before the first or when it cannot be read. */
        fun last(stateFile: Path): DockerCleanupRun? = runCatching {
            if (!stateFile.exists()) null
            else Json { ignoreUnknownKeys = true }.decodeFromString(DockerCleanupRun.serializer(), stateFile.readText())
        }.getOrNull()

        /** The repository of an image reference, `latest` when it names no tag; a registry's port is no tag. */
        fun repository(image: String): String {
            val colon = image.lastIndexOf(':')
            return if (colon > image.lastIndexOf('/')) image.substring(0, colon) else image
        }

        /** [image] with its tag, `:latest` when it names none. */
        fun tagged(image: String): String = if (repository(image) == image) "$image:latest" else image

        /** `docker images` in [IMAGE_FORMAT]: each line's repository and tag. */
        fun parseImages(listing: String): List<Pair<String, String>> = listing.lines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2 || parts[0].isBlank()) null else parts[0].trim() to parts[1].trim()
        }

        /**
         * The tags of [images] a cleanup removes: those of [repositories] that are not `latest`, not untagged (a
         * dangling image is image prune's), and not in [keep]. Images of any other repository are never touched.
         */
        fun superseded(images: List<Pair<String, String>>, repositories: Set<String>, keep: Set<String>): List<String> {
            val kept = keep.map(::tagged).toSet()
            return images.filter { (repo, tag) -> repo in repositories && tag != "<none>" && tag != "latest" && "$repo:$tag" !in kept }
                .map { (repo, tag) -> "$repo:$tag" }.distinct()
        }

        /** The image references to [repositories] in the text `git grep -o` printed, each with its tag. */
        fun references(text: String, repositories: Set<String>): Set<String> =
            pattern(repositories)?.toRegex()?.findAll(text)?.map { it.value }?.toSet().orEmpty()

        /** An extended regular expression matching a tagged reference to one of [repositories]; null for none. */
        fun pattern(repositories: Set<String>): String? = repositories.takeIf { it.isNotEmpty() }
            ?.joinToString("|", "(", ")") { it.replace(".", "\\.") }
            ?.let { "$it:[A-Za-z0-9_.-]+" }

        /** `docker system df --format '{{json .}}'`, one JSON object a line. */
        fun parseDf(out: String): List<DockerUsage> = out.lines().map { it.trim() }.filter { it.startsWith("{") }.mapNotNull { line ->
            runCatching {
                val fields = Json.parseToJsonElement(line).jsonObject
                fun field(name: String) = fields[name]?.jsonPrimitive?.content
                DockerUsage(field("Type") ?: return@runCatching null, field("Size")?.let(::parseSize), field("Reclaimable")?.let(::parseSize))
            }.getOrNull()
        }

        /** A size as Docker prints it, decimal units (`5.5GB`, `577MB`, `0B`), with any `(61%)` after it ignored. */
        fun parseSize(text: String): Long? {
            val match = Regex("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([kKMGTP]?B)").find(text) ?: return null
            val factor = when (match.groupValues[2].uppercase()) {
                "B" -> 1.0
                "KB" -> 1e3
                "MB" -> 1e6
                "GB" -> 1e9
                "TB" -> 1e12
                else -> 1e15
            }
            return (match.groupValues[1].toDouble() * factor).toLong()
        }

        /** What a prune said it reclaimed, its `Total…:` line's size. */
        fun reclaimed(out: String): String? = out.lines().lastOrNull { it.trim().startsWith("Total") }?.substringAfter(':')?.trim()

        /** The total size of a `docker system df`, null when it is empty. */
        fun total(usage: List<DockerUsage>): Long? = usage.takeIf { it.isNotEmpty() }?.sumOf { it.bytes ?: 0 }

        /** A run in one line, for the log. */
        fun describe(run: DockerCleanupRun): String = buildString {
            append("docker cleanup: ")
            append(if (run.removedTags.isEmpty()) "no tags removed" else "removed ${run.removedTags.joinToString()}")
            append(", ${run.prunedImages} dangling images pruned, build cache ${run.buildCacheReclaimed ?: "?"} reclaimed")
            val before = total(run.dockerBefore)
            val after = total(run.dockerAfter)
            if (before != null && after != null) append("; docker ${mb(before)} -> ${mb(after)} MB")
            if (run.hostDiskBefore != null || run.hostDiskAfter != null) {
                append("; Colima disks ${run.hostDiskBefore?.let(::mb) ?: "?"} -> ${run.hostDiskAfter?.let(::mb) ?: "?"} MB on the host")
            }
            run.trimmed?.let { append("; fstrim: $it") }
            if (run.notes.isNotEmpty()) append("; ${run.notes.joinToString("; ")}")
        }

        private fun mb(bytes: Long) = bytes / 1_000_000
    }
}

/** The reference image tags the commits of the queued and running [jobs] name in their repository, found with [grep]
 *  (`git grep` in the mirror, given a revision); null when a revision could not be read. */
internal fun referencedImages(jobs: List<Job>, arms: (Long) -> List<PlannedArm>, repositories: Set<String>, grep: (String, String) -> Pair<Int, String>): Set<String>? {
    val pattern = DockerCleanup.pattern(repositories) ?: return emptySet()
    val revisions = jobs.flatMap { job ->
        listOfNotNull(job.sha ?: job.ref) + arms(job.id).map { it.sha } +
            job.experiment.let { spec -> runCatching { Experiments.arms(spec).map { it.ref } }.getOrDefault(emptyList()) }.orEmpty()
    }.filter { it.isNotBlank() }.distinct()
    val found = mutableSetOf<String>()
    for (revision in revisions) {
        // Read as an option otherwise; the lab refuses such refs at submit.
        if (revision.startsWith("-")) return null
        val (exit, out) = grep(pattern, revision)
        // git grep exits 1 when nothing matches; anything else means the revision was not there to read.
        if (exit != 0 && exit != 1) return null
        found += DockerCleanup.references(out, repositories)
    }
    return found
}

/**
 * The lab's cleanup: on when Docker is ([Config.requireDocker], which `install.sh` sets unless `LAB_DOCKER=none`) and
 * due every [Config.dockerCleanupDays] (never on its own at 0), with the tags the active jobs need found by [grep] in the mirror.
 */
internal fun dockerCleanup(
    config: Config,
    store: Store,
    enabled: Boolean = config.requireDocker,
    grep: (String, String) -> Pair<Int, String>,
) = DockerCleanup(
    enabled,
    config.dockerKeep,
    config.dockerCleanupDays * DockerCleanup.DAY_MS,
    config.dockerCleanupState,
    config.dockerCleanupRequest,
    needed = {
        referencedImages(store.active(withCommands = false), store.plans::arms, config.dockerKeep.map(DockerCleanup::repository).toSet(), grep)
    },
    freeBytes = { freeBytes(config.dataDir) },
    lowFreeBytes = config.jobsTargetFreeBytes,
)

/** The `git grep` [referencedImages] runs in [mirror]. */
internal fun mirrorGrep(mirror: java.io.File): (String, String) -> Pair<Int, String> = { pattern, revision ->
    gitIn(mirror, listOf("grep", "-I", "-ohE", pattern, revision, "--"))
}

private fun execute(cmd: List<String>): Pair<Int, String> = runProcess(cmd, timeout = java.time.Duration.ofMinutes(30))

/** Run git with [args] in [dir], no shell between: its exit and its output. */
internal fun gitIn(dir: File, args: List<String>): Pair<Int, String> = runGit(dir, args, java.time.Duration.ofMinutes(2))
