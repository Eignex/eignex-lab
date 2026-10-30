package com.eignex.lab.nomad

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.worker.*

import kotlinx.serialization.json.*

/** Reap disposable native workspaces through Nomad, after one hour for diagnosis. Results live in the lab store. */
internal class NomadAllocationCleanup(
    private val nomad: NomadControl, private val store: Store, settings: NomadQueueSettings,
    private val clock: () -> Long = ::now,
) {
    private val nativeClasses = settings.targets.filter { it.native }.map { it.template.nodeClass }.toSet()
    private val collected = mutableSetOf<String>()

    fun reconcile() {
        if (nativeClasses.isEmpty()) return
        val jobs = store.execution.sessions().map { it.nomadJobId }.toSet()
        val seen = mutableSetOf<String>()
        for (node in nomad.nodes().filter {
            it["NodeClass"]?.jsonPrimitive?.content in nativeClasses && it["Status"]?.jsonPrimitive?.content == "ready"
        }) {
            for (allocation in nomad.nodeAllocations(node.getValue("ID").jsonPrimitive.content)) {
                val id = allocation.getValue("ID").jsonPrimitive.content
                seen += id
                if (id in collected || allocation["JobID"]?.jsonPrimitive?.content !in jobs) continue
                if (allocation["Namespace"]?.jsonPrimitive?.content != nomad.namespace) continue
                if (allocation["ClientStatus"]?.jsonPrimitive?.content !in setOf("complete", "failed")) continue
                val modified = allocation["ModifyTime"]?.jsonPrimitive?.longOrNull?.div(1_000_000) ?: continue
                if (modified <= 0 || clock() - modified < 3_600_000) continue
                // Nomad itself refuses to collect active allocations, including a race after this status check.
                runCatching { nomad.collectAllocation(id) }.onSuccess { if (it) collected += id }.onFailure {
                    System.err.println("nomad allocation cleanup $id: ${it.javaClass.simpleName}")
                }
            }
        }
        collected.retainAll(seen)
    }
}
