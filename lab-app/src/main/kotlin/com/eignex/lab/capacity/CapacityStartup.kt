package com.eignex.lab.capacity

import com.eignex.lab.domain.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** A startup replacement never changes a pool target or any workload's accepted work. */
@Serializable
data class CapacityStartupRetirement(val id: String, val launchedAt: Long, val authorized: Boolean = false)

@Serializable
internal data class CapacityStartupState(
    val everReady: Map<String, Long?> = emptyMap(),
    val retiring: Map<String, CapacityStartupRetirement> = emptyMap(),
    val failed: Map<String, Set<String>> = emptyMap(),
)

internal object CapacityStartup {
    const val GRACE_MS = CapacityWire.STARTUP_GRACE_MS
    const val MAX_REPLACEMENTS = 3

    fun key(instance: CapacityInstance) = "${instance.pool}:${instance.id}"
    fun identity(key: String, launchedAt: Long) = "$key@$launchedAt"
    fun expired(instance: CapacityInstance, at: Long) = !instance.id.startsWith("booting-") && instance.launchedAt?.let { it > 0 && at - it >= GRACE_MS } == true

    /** A misconfigured class/meta must not make a registered host look absent during destructive revalidation. */
    fun registered(node: JsonObject, id: String): Boolean =
        (node["Meta"] as? JsonObject)?.get(CapacityWire.CAPACITY_ID_META)?.jsonPrimitive?.content == id ||
            (node["Attributes"] as? JsonObject)?.get("unique.platform.aws.instance-id")?.jsonPrimitive?.content == id ||
            node["Name"]?.jsonPrimitive?.content?.let { it == id || it.endsWith("-$id") } == true

    /** Unknown launch times, synthetic pending slots and any registered node are never destructive candidates. */
    fun observe(
        previous: CapacityStartupState, instances: List<CapacityInstance>, freshPools: Set<String>,
        registered: Set<String>, ready: Set<String>, at: Long,
    ): CapacityStartupState {
        val present = instances.associateBy(::key)
        val ever = previous.everReady.filterTo(mutableMapOf()) { (id, launch) ->
            id.substringBefore(':') !in freshPools || present[id]?.let { launch == null || it.launchedAt == null || launch == it.launchedAt } == true
        }
        val failures = previous.failed.toMutableMap()
        // A newly successful boot closes the circuit. An old healthy neighbor must not reset every failed boot.
        for (id in ready - ever.keys) if (previous.retiring[id]?.authorized != true) failures.remove(id.substringBefore(':'))
        for (id in ready) ever[id] = present[id]?.launchedAt
        val retiring = previous.retiring.filterTo(mutableMapOf()) { (id, action) ->
            id.substringBefore(':') !in freshPools || present[id]?.let { it.launchedAt == null || it.launchedAt == action.launchedAt } == true
        }
        for (pool in freshPools) {
            if (retiring.keys.any { it.substringBefore(':') == pool && it !in ever } || failures[pool].orEmpty().size >= MAX_REPLACEMENTS) continue
            val candidate = instances.filter { it.pool == pool && !it.draining && it.countedInTarget && expired(it, at) && key(it) !in ever && key(it) !in registered }
                .minWithOrNull(compareBy<CapacityInstance> { it.launchedAt }.thenBy { it.id }) ?: continue
            val id = key(candidate)
            retiring[id] = CapacityStartupRetirement(candidate.id, requireNotNull(candidate.launchedAt))
        }
        return CapacityStartupState(ever, retiring, failures)
    }

    /** Persist before returning a destructive grant. Repeated provider/HTTP retries spend the same identity once. */
    fun authorize(previous: CapacityStartupState, pool: String, registered: Set<String>): CapacityStartupState {
        val retiring = previous.retiring.toMutableMap()
        val failed = previous.failed.toMutableMap()
        // Retain issued history, but startup recovery ends once that exact live identity was ready.
        for ((key, action) in previous.retiring.filterKeys { it.substringBefore(':') == pool && it !in previous.everReady }) {
            if (action.id in registered) {
                if (!action.authorized) retiring.remove(key)
                continue
            }
            if (!action.authorized) {
                check(failed[pool].orEmpty().size < MAX_REPLACEMENTS) { "startup replacement circuit open" }
                failed[pool] = failed[pool].orEmpty() + identity(key, action.launchedAt)
                retiring[key] = action.copy(authorized = true)
            }
        }
        return previous.copy(retiring = retiring, failed = failed)
    }
}
