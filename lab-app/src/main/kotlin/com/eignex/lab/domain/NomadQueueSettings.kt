package com.eignex.lab.domain

import com.eignex.lab.util.*

import kotlinx.serialization.Serializable
import com.eignex.lab.domain.NomadBatchJob
import com.eignex.lab.domain.NomadPlatform

@Serializable
data class NomadQueueTarget(val template: NomadBatchJob, val tags: Set<String>, val market: CapacityMarket = CapacityMarket.SPOT) {
    init {
        require(tags.count { it.startsWith("arch:") } == 1)
        require(tags.none { it in setOf("local", "cloud", "spot", "on-demand") })
        NodeTags.validate(NodeSelector(require = tags))
    }
    /** Whether its workers are the local machine's native worker, which the lab reserves for itself ([CpuEnforcement]). */
    val native get() = template.platform == NomadPlatform.MAC
    val os get() = if (native) "mac" else "linux"
    val arch get() = when (tags.single { it.startsWith("arch:") }) {
        "arch:arm64" -> "arm64"
        "arch:x86_64" -> "amd64"
        else -> error("worker architecture must be arm64 or x86_64")
    }
    val capabilities get() = tags + "platform:$os" + if (native) setOf("local") else setOf("cloud", market.tag)
}

/** Provider-neutral, pinned workers and bounded per-allocation budgets; no provider credentials. */
@Serializable
data class NomadQueueSettings(
    val release: String, val workerApi: String, val targets: List<NomadQueueTarget>,
    val preparationBudget: ResourceBudget = ResourceBudget(2000, 4096), val preparationTimeoutSec: Long = 3600,
    val caseMemoryMiB: Int = 4096, val sessionMemoryMiB: Int = 512, val maxSessionsPerJob: Int = 64,
    /** Infrastructure policy, independent of frozen experiment inputs and per-worker parallelism. [maxVcpusPerJob] caps
     *  the vCPUs of all of a job's solve sessions together, while the job's own vCPUs are the width of each one
     *  ([solveBudget]); a job's preparation, one session, gets no more than it. [preparationBudget] is the least a
     *  preparation gets; one grows with its job's vCPUs ([preparationBudget]) and, in the cloud, into what a running
     *  node leaves idle ([idlePreparationBudget]). */
    val maxVcpusPerJob: Int = 16, val perHostParallel: Int? = null, val maxConcurrentBuildsPerNode: Int = 2,
    /** Explicitly approved infrastructure releases this worker can replace, preserving consumer inputs. */
    val workerReplaces: Set<String> = emptySet(),
    /** Enable only after every pinned target supports worker protocol 7. Frozen older recipes keep their format. */
    val packedSourceSnapshots: Boolean = false,
    /**
     * Offer cloud bench preparations the accepted archive of an identical build, and let a preparation wait a bounded
     * time for an identical build another job is preparing. Enable only after every pinned cloud target supports worker
     * protocol 9.
     */
    val reuseAcceptedBuilds: Boolean = false,
    /** Enable only after every pinned target supports worker protocol 10: prepare manifests then carry bench selection
     *  source identities read from the commit at admission ([com.eignex.lab.domain.WorkManifest.selectionSources]). */
    val committedSelectionKeys: Boolean = false,
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
    fun matching(spec: ExperimentSpec, origin: JobOrigin, cloudSchedule: Boolean = false,
                 selector: NodeSelector? = spec.nodeTags): List<NomadQueueTarget> = targets.filter { target ->
        NodeTags.matches(selector, target.capabilities) &&
            (target.native || Placement.localOnlyReason(spec, origin, cloudSchedule) == null &&
                target.market == NodeTags.market(spec.nodeTags))
    }

    /** [matching] for a job known only by its [name] ([JobOrigin.of]). */
    fun matching(spec: ExperimentSpec, name: String, cloudSchedule: Boolean = false): List<NomadQueueTarget> =
        matching(spec, JobOrigin.of(name), cloudSchedule)
    fun solveOptions(options: String): String {
        val heap = Regex("(?:^|\\s)-Xmx([1-9][0-9]*)([kKmMgG]?)(?=\\s|$)").findAll(options).lastOrNull()
        if (heap == null) return "$options -Xmx${caseMemoryMiB - 256}m".trim()
        val units = when (heap.groupValues[2].lowercase()) { "g" -> 1024L * 1024 * 1024; "m" -> 1024L * 1024; "k" -> 1024L; else -> 1L }
        val bytes = Math.multiplyExact(heap.groupValues[1].toLong(), units)
        require(bytes <= (caseMemoryMiB - 256).toLong() * 1024 * 1024) { "configured solve heap exceeds the per-case memory budget" }
        return options
    }
    /**
     * What a solve session of [job] reserves on [target]: the job's vCPUs ([ExperimentSpec.vcpus], at least its largest
     * case's CPUs), within the target's CPU ceiling, running [Experiments.caseConcurrency] cases, each with its memory,
     * at most [maxCases] and as many as the target's memory holds; [cpuCeiling] is the local cores for the native worker.
     * [memoryCeiling] can fit a new native session into unreserved memory without reducing any case's requirements.
     * A session claims whole paired rows until they fill those case slots ([ExecutionStore.claim]); null when not one
     * case fits.
     */
    fun solveBudget(target: NomadQueueTarget, job: Job, plan: com.eignex.lab.bench.BenchPlan? = null, maxCases: Int = Int.MAX_VALUE,
                    cpuCeiling: Int = Int.MAX_VALUE, memoryCeiling: Int = Int.MAX_VALUE): ResourceBudget? {
        val resources = plan?.let(BenchCases::resources)
        val largest = resources?.cpus ?: Experiments.arms(checkNotNull(job.experiment)).maxOf { it.cores }
        val memory = resources?.memoryMiB?.let(Math::toIntExact) ?: caseMemoryMiB
        if (resources != null && !NodeTags.matches(NodeSelector(require = resources.requiredTags), target.capabilities)) return null
        val maximum = target.template.budget
        val vcpus = maxOf(job.vcpus, largest).coerceAtMost(minOf(maximum.cpuMillis, cpuCeiling) / 1000)
        if (vcpus < largest) return null
        val concurrency = Experiments.caseConcurrency(vcpus, largest)
        val cases = minOf(concurrency, maxCases, (minOf(maximum.memoryMiB, memoryCeiling) - sessionMemoryMiB) / memory)
        if (cases < 1) return null
        // Memory may hold fewer cases than the vCPUs would run: then reserve only their CPUs and the worker's one.
        val cpus = if (cases == concurrency) vcpus else minOf(vcpus, Math.addExact(Math.multiplyExact(cases, largest), 1))
        return ResourceBudget(Math.multiplyExact(cpus, 1000), sessionMemoryMiB + cases * memory, cases)
    }

    /**
     * What [job]'s preparation reserves on [target]: the job's vCPUs, at least [preparationBudget]'s, with memory scaled
     * from it in proportion; both within the target's ceilings and [cpuCeiling] (the local machine's cores for its
     * native worker), never above [maxVcpusPerJob]. Builds take their heap and Gradle workers from it
     * ([WorkerBuildCache]), so a job that asks for more vCPUs also prepares faster.
     */
    fun preparationBudget(target: NomadQueueTarget, job: Job, cpuCeiling: Int = Int.MAX_VALUE): ResourceBudget {
        val base = preparationBudget
        val maximum = target.template.budget
        val asked = Math.multiplyExact(job.vcpus.coerceAtMost(maxVcpusPerJob), 1000)
        val cpu = minOf(maxOf(base.cpuMillis, asked), maximum.cpuMillis, cpuCeiling)
        val memory = (base.memoryMiB.toLong() * cpu / base.cpuMillis).coerceIn(1, maximum.memoryMiB.toLong()).toInt()
        return ResourceBudget(cpu, memory)
    }

    /**
     * [floor], a job's [preparationBudget] on cloud [target], grown into what one running node of it leaves idle. [idle]
     * is each such node's free thousandths of a vCPU and MiB, after work already placed there and work still waiting to
     * be. The grown preparation takes at most that node's share per compiler slot ([slots], the live
     * [maxConcurrentBuildsPerNode]), so another preparation still fits beside it, the target's ceilings and
     * [maxVcpusPerJob], in whole vCPUs with memory in [preparationBudget]'s proportion. The build then compiles with that
     * many workers on a node that would otherwise sit idle ([com.eignex.lab.worker.WorkerBuildCache]); [floor] itself
     * when no running node has more to give.
     */
    fun idlePreparationBudget(target: NomadQueueTarget, floor: ResourceBudget, idle: List<Pair<Long, Long>>,
                              slots: Int = maxConcurrentBuildsPerNode): ResourceBudget {
        val base = preparationBudget
        val maximum = target.template.budget
        val share = target.template.cpuCapacity.logicalCpus.toLong() * 1000 / slots
        val ceiling = minOf(share, maximum.cpuMillis.toLong(), maxVcpusPerJob * 1000L)
        val cpu = idle.maxOfOrNull { (cpu, memory) ->
            val byMemory = minOf(memory, maximum.memoryMiB.toLong()) * base.cpuMillis / base.memoryMiB
            minOf(cpu, ceiling, byMemory) / 1000 * 1000
        } ?: return floor
        if (cpu <= floor.cpuMillis) return floor
        return ResourceBudget(cpu.toInt(), (base.memoryMiB.toLong() * cpu / base.cpuMillis).toInt())
    }
}
