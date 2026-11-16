package com.eignex.lab.capacity

import com.eignex.lab.domain.*

/** Only a recent, explicit capacity shortage changes purchasing policy; quotas and configuration errors do not. */
internal object SpotFallback {
    const val RETRY_SPOT_MS = 10 * 60_000L

    fun active(settings: CapacitySettings, fresh: List<CapacityInventory>, at: Long): Map<String, String> {
        val errors = fresh.flatMap { it.capacityErrors.entries }.associate { it.toPair() }
        return settings.pools.mapNotNull { pool ->
            val target = pool.onDemandFallback ?: return@mapNotNull null
            val error = errors[pool.id] ?: return@mapNotNull null
            (pool.id to target).takeIf { error.code == "UnfulfillableCapacity" && at - error.at in 0..RETRY_SPOT_MS }
        }.toMap()
    }
}
