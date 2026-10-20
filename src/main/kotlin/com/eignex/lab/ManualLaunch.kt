package com.eignex.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/** A request to start a cloud node by hand (`POST /cloud/nodes`): an instance [size] of the family, and a [market],
 *  `spot` (the default, spot first) or `on-demand`. Either may be left out. */
@Serializable
data class NodeStart(val size: String? = null, val market: String? = null)

/** What the API leaves for the cloud worker, in `aws/manual-launch.json`: the [size] asked for (null for the size the
 *  queue calls for), whether to buy it [onDemand], and when it was asked [at]. */
@Serializable
data class ManualLaunchRequest(val size: String? = null, val onDemand: Boolean = false, val at: Long)

/** The size and market a manual launch goes ahead with. */
data class ManualLaunchPlan(val size: InstanceSize, val onDemand: Boolean)

/**
 * Starting a cloud node by hand. The API runs apart from the runner, whose cloud worker owns the pool, so the API
 * checks what it can from the worker's last pool report ([NodePoolReport]) and leaves a request file
 * ([ManualLaunchRequest]); the worker takes it on its next poll and checks again against the pool as it then stands
 * ([decide]). A manual launch skips the pressure threshold and the sustain wait, but not `maxInstances`, the vCPU quota
 * or a waiting lab update, and it counts as the gate's last launch, so the cooldown holds automatic launches after it
 * ([LaunchGate.manualLaunch]). The node then works and retires as any other.
 *
 * The market is spot first by default, as for an automatic launch: on demand only after `spotWaitMin` minutes without
 * spot, and only with `onDemand=true`. `on-demand`, asked for, is always allowed: an operator who starts a node by hand
 * and asks to pay for on demand has decided that, whatever the automatic setting.
 */
object ManualLaunch {
    const val FILE = "manual-launch.json"
    /** A request older than this is dropped rather than launched: the runner was down, and the moment has passed. */
    const val EXPIRY_MS = 5 * 60_000L
    /** A pool report older than this says the worker is not running, so a request would sit unread. */
    const val STALE_REPORT_MS = 2 * 60_000L
    private val JSON = Json { ignoreUnknownKeys = true }

    fun file(config: Config): Path = config.dataDir.resolve("aws").resolve(FILE)

    /** Why the pool cannot take another node now, or null: [nodes] up or launching against `maxInstances`, a waiting
     *  update, or [size] over the [freeVcpus] left of the quota (null [size]: none fits). */
    fun refusal(aws: AwsConfig, nodes: Int, updateWaiting: Boolean, size: InstanceSize?, freeVcpus: Int): String? = when {
        nodes >= aws.maxInstances -> "${LaunchGate.AT_MAX} (${aws.maxInstances} nodes up or launching)"
        updateWaiting -> "${LaunchGate.UPDATE_WAITING}: the pool drains for it, so no node starts"
        size == null -> "${LaunchGate.QUOTA_FULL}: no size fits the $freeVcpus vCPUs left of ${aws.vcpuQuota}"
        size.vcpus > freeVcpus -> "${LaunchGate.QUOTA_FULL}: a ${size.type} needs ${size.vcpus} vCPUs, $freeVcpus of ${aws.vcpuQuota} are left"
        else -> null
    }

    /** [market] as whether to buy on demand; an unknown one is refused. */
    fun onDemand(market: String?): Boolean = when (market?.trim()?.lowercase()) {
        null, "", AwsWorker.SPOT -> false
        AwsWorker.ON_DEMAND, "ondemand" -> true
        else -> throw IllegalArgumentException("market must be spot or on-demand, not '$market'")
    }

    /** [type] as one of [aws]'s sizes within the quota; an unknown one is refused, naming the sizes there are. */
    fun size(aws: AwsConfig, type: String): InstanceSize = aws.sizes.firstOrNull { it.type == type.trim() }
        ?: throw IllegalArgumentException("size must be one of ${aws.sizes.joinToString { it.type }}, not '$type'")

    /** The market of a manual launch in words, for its messages. */
    fun marketText(onDemand: Boolean): String = if (onDemand) "on-demand" else "spot-first"

    /**
     * The API's side: check [start] against [aws] and the worker's [report] at [now], then leave the request for the
     * worker. Returns the HTTP status and the message to answer with: 202 when requested, 409 when the pool cannot take
     * a node or a request already waits, 400 for an unknown size or market.
     */
    fun request(config: Config, aws: AwsConfig, report: NodePoolReport?, start: NodeStart, now: Long): Pair<Int, String> {
        val onDemand = try {
            onDemand(start.market)
        } catch (e: IllegalArgumentException) {
            return 400 to e.message.orEmpty()
        }
        val asked = try {
            start.size?.takeIf { it.isNotBlank() }?.let { size(aws, it) }
        } catch (e: IllegalArgumentException) {
            return 400 to e.message.orEmpty()
        }
        if (report == null) return 409 to "the cloud worker has not reported yet: is the runner up?"
        if (now - report.updatedAt > STALE_REPORT_MS) {
            return 409 to "the cloud worker last reported ${(now - report.updatedAt) / MS_PER_MIN} min ago: is the runner up?"
        }
        val file = file(config)
        if (file.exists()) return 409 to "a manual launch is already requested; the worker takes it on its next poll"
        val busy = report.busyVcpus ?: report.nodes.sumOf { node -> aws.sizes.firstOrNull { it.type == node.type }?.vcpus ?: 0 }
        // The size the queue calls for, as the worker last worked it out; a report from an older lab has none, so the
        // smallest. A null one from a newer lab means none fits what is left of the quota.
        val queueSize = if (report.busyVcpus == null) aws.sizes.firstOrNull()
        else report.manualSize?.let { type -> aws.sizes.firstOrNull { it.type == type } }
        val chosen = asked ?: queueSize
        refusal(aws, report.nodes.size + report.launching, report.updateWaiting == true, chosen, aws.vcpuQuota - busy)?.let {
            return 409 to "no node started: $it"
        }
        val size = checkNotNull(chosen)
        file.parent.createDirectories()
        file.writeAtomically(JSON.encodeToString(ManualLaunchRequest.serializer(), ManualLaunchRequest(asked?.type, onDemand, now)))
        return 202 to "manual launch requested: a ${size.type} ${marketText(onDemand)} node${if (asked == null) ", the size the queue calls for" else ""}. " +
            "It takes work as any node does and retires after ${aws.nodeIdleMin} min with nothing to take"
    }

    /** The request waiting in [file], removed as it is read; null when there is none or it cannot be read. */
    fun take(file: Path): ManualLaunchRequest? {
        if (!file.exists()) return null
        val text = runCatching { file.toFile().readText() }.getOrNull()
        file.toFile().delete()
        return text?.let { runCatching { JSON.decodeFromString(ManualLaunchRequest.serializer(), it) }.getOrNull() }
    }

    /**
     * The worker's side: what to launch for [request] at [now], or why not, against the pool as it stands: [nodes] up
     * or launching, the [freeVcpus] left of the quota, whether an update is waiting, and [queueSize], the size the
     * queue calls for ([NodePool.size]), used when the request names none.
     */
    fun decide(
        request: ManualLaunchRequest, now: Long, aws: AwsConfig, queueSize: InstanceSize?, nodes: Int, freeVcpus: Int, updateWaiting: Boolean,
    ): Result<ManualLaunchPlan> {
        if (now - request.at > EXPIRY_MS) return Result.failure(IllegalStateException("its request from ${(now - request.at) / MS_PER_MIN} min ago has expired"))
        val size = try {
            request.size?.let { size(aws, it) } ?: queueSize
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }
        refusal(aws, nodes, updateWaiting, size, freeVcpus)?.let { return Result.failure(IllegalStateException(it)) }
        return Result.success(ManualLaunchPlan(checkNotNull(size), request.onDemand))
    }

    private const val MS_PER_MIN = 60_000L
}
