package com.eignex.lab

import kotlinx.serialization.Serializable

@Serializable
data class NomadQueueTarget(val template: NomadBatchJob, val tags: Set<String>, val market: CapacityMarket = CapacityMarket.SPOT) {
    init {
        require(tags.count { it.startsWith("arch:") } == 1)
        require(tags.none { it in setOf("local", "cloud", "spot", "on-demand") })
        NodeTags.validate(NodeSelector(require = tags))
    }
    val os get() = if (template.platform == NomadPlatform.MAC) "mac" else "linux"
    val arch get() = when (tags.single { it.startsWith("arch:") }) {
        "arch:arm64" -> "arm64"
        "arch:x86_64" -> "amd64"
        else -> error("worker architecture must be arm64 or x86_64")
    }
    val capabilities get() = tags + "platform:$os" + if (os == "mac") setOf("local") else setOf("cloud", market.tag)
}

/** Provider-neutral, pinned workers and bounded per-allocation budgets; no provider credentials. */
@Serializable
data class NomadQueueSettings(
    val release: String, val workerApi: String, val targets: List<NomadQueueTarget>,
    val preparationBudget: ResourceBudget = ResourceBudget(2000, 4096), val preparationTimeoutSec: Long = 3600,
    val caseMemoryMiB: Int = 4096, val sessionMemoryMiB: Int = 512, val maxSessionsPerJob: Int = 64,
    /** Infrastructure policy, independent of frozen experiment inputs and per-worker parallelism. */
    val maxVcpusPerJob: Int = 16, val perHostParallel: Int? = null, val maxConcurrentBuildsPerNode: Int = 2,
    /** Explicitly approved infrastructure releases this worker can replace, preserving consumer inputs. */
    val workerReplaces: Set<String> = emptySet(),
) {
    init {
        require(release.isNotBlank() && targets.isNotEmpty() && targets.map { it.template.nodeClass }.distinct().size == targets.size)
        require(preparationTimeoutSec in 1..7200 && preparationBudget.parallel == 1)
        require(maxVcpusPerJob in 1..1024 && (perHostParallel == null || perHostParallel > 0) && maxConcurrentBuildsPerNode in 1..64)
        require(caseMemoryMiB >= 512 && sessionMemoryMiB >= 256 && maxSessionsPerJob in 1..1024)
        val api = java.net.URI.create(workerApi)
        require(api.scheme in setOf("https", "http") && api.userInfo == null && api.rawQuery == null)
        targets.forEach { it.arch }
        require(targets.groupBy { it.os to it.arch }.values.all { group -> group.map { it.template.image }.distinct().size == 1 }) {
            "workers sharing prepared binaries must use the same pinned image for each platform"
        }
    }
    fun matching(spec: ExperimentSpec, name: String, cloudSchedule: Boolean = false): List<NomadQueueTarget> = targets.filter { target ->
        NodeTags.matches(spec.nodeTags, target.capabilities) &&
            (target.os == "mac" || Placement.localOnlyReason(spec, name, cloudSchedule) == null &&
                target.market == NodeTags.market(spec.nodeTags))
    }
    fun solveOptions(options: String): String {
        val heap = Regex("(?:^|\\s)-Xmx([1-9][0-9]*)([kKmMgG]?)(?=\\s|$)").findAll(options).lastOrNull()
        if (heap == null) return "$options -Xmx${caseMemoryMiB - 256}m".trim()
        val units = when (heap.groupValues[2].lowercase()) { "g" -> 1024L * 1024 * 1024; "m" -> 1024L * 1024; "k" -> 1024L; else -> 1L }
        val bytes = Math.multiplyExact(heap.groupValues[1].toLong(), units)
        require(bytes <= (caseMemoryMiB - 256).toLong() * 1024 * 1024) { "configured solve heap exceeds the per-case memory budget" }
        return options
    }
    fun solveBudget(target: NomadQueueTarget, job: Job, plan: com.eignex.lab.bench.BenchPlan? = null): ResourceBudget? {
        val resources = plan?.let(BenchRuns::resources)
        val largest = resources?.cpus ?: Experiments.arms(checkNotNull(job.experiment)).maxOf { it.cores }
        val memory = resources?.memoryMiB?.let(Math::toIntExact) ?: caseMemoryMiB
        if (resources != null && !NodeTags.matches(NodeSelector(require = resources.requiredTags), target.capabilities)) return null
        val maximum = target.template.budget
        val parallel = minOf(job.parallel, Experiments.arms(checkNotNull(job.experiment)).size, maximum.parallel, maximum.cpuMillis / 1000 / largest,
            (maximum.memoryMiB - sessionMemoryMiB) / memory)
        if (parallel < 1) return null
        return ResourceBudget(Math.multiplyExact(Math.multiplyExact(largest, parallel), 1000), sessionMemoryMiB + parallel * memory, parallel)
    }
}
