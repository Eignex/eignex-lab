package com.eignex.lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

/** Public placement facts only; a submission's worker credentials never reach a page. */
internal data class RunningNode(val id: String, val allocation: String?, val nodeClass: String?, val local: Boolean)
internal data class JobNode(val id: String, val name: String, val profile: String?, val local: Boolean, val anchor: String?)

/** Resolve active bindings from local state without making Nomad requests on the page's refresh path. */
internal fun jobNodes(config: Config, store: Store, jobs: Collection<Long>): Map<Long, List<JobNode>> {
    if (jobs.isEmpty()) return emptyMap()
    val snapshot = readCapacityReport(config)
    val allocations = snapshot?.nodes.orEmpty().flatMap { node -> node.reservations.map { it.id to node } }.toMap()
    val settings = runCatching {
        config.nomadCapacitySettings?.let { Json.decodeFromString<CapacitySettings>(Files.readString(it)) }
    }.getOrNull()
    val pools = (settings?.pools ?: snapshot?.pools).orEmpty()
    return store.execution.runningNodes(jobs).mapValues { (_, nodes) ->
        nodes.map { node ->
            val capacity = node.allocation?.let(allocations::get)
            val pool = pools.singleOrNull { it.id == capacity?.pool || it.nodeClass == node.nodeClass }
            JobNode(node.id, when {
                node.local -> "native · ${node.id.take(8)}"
                capacity != null -> capacity.id
                else -> "${node.nodeClass ?: "node"} · ${node.id.take(8)}"
            }, if (node.local) NodeTags.hardwareProfile(config.localNodeTags) else pool?.let { NodeTags.hardwareProfile(it.tags) },
                node.local, if (node.local) "local-node" else capacity?.let { "node-${it.id}" })
        }.distinctBy { it.id }.sortedWith(compareBy<JobNode> { !it.local }.thenBy { it.name })
    }
}

/** Read only node identity and placement constraints from a frozen submission. */
internal fun runningNode(id: String, allocation: String?, payload: String): RunningNode {
    val constraints = Json.parseToJsonElement(payload).jsonObject["Constraints"]?.jsonArray.orEmpty().map { it.jsonObject }
    fun constraint(name: String) = constraints.singleOrNull { it["LTarget"]?.jsonPrimitive?.content == name }
        ?.get("RTarget")?.jsonPrimitive?.content
    return RunningNode(id, allocation, constraint("${'$'}{node.class}"), constraint("${'$'}{attr.kernel.name}") == "darwin")
}
