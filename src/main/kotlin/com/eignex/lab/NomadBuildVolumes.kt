package com.eignex.lab

import kotlinx.serialization.json.*

/** Cache storage follows the node lifetime. Dynamic volumes avoid restarting clients with running allocations. */
internal class NomadBuildVolumes(private val nomad: NomadControl, private val settings: NomadQueueSettings) {
    fun reconcile() {
        val classes = settings.targets.filter { it.template.cacheHostVolume != null }.map { it.template.nodeClass }.toSet()
        if (classes.isEmpty()) return
        val present = nomad.hostVolumes().filter { it["Name"]?.jsonPrimitive?.content == "lab-cache" }
            .mapNotNull { it["NodeID"]?.jsonPrimitive?.content }.toSet()
        for (node in nomad.nodes().filter { it["NodeClass"]?.jsonPrimitive?.content in classes && it["Status"]?.jsonPrimitive?.content == "ready" }) {
            val id = node.getValue("ID").jsonPrimitive.content
            if (id in present) continue
            nomad.createHostVolume(buildJsonObject {
                put("Name", "lab-cache"); put("Namespace", nomad.namespace); put("NodeID", id); put("PluginID", "mkdir")
                put("RequestedCapacityMinBytes", 1024L * 1024 * 1024); put("RequestedCapacityMaxBytes", 32L * 1024 * 1024 * 1024)
                putJsonArray("RequestedCapabilities") { add(buildJsonObject { put("AccessMode", "single-node-multi-writer"); put("AttachmentMode", "file-system") }) }
            })
        }
    }
}
