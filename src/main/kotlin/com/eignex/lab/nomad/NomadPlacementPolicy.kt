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

/** Operator limits are infrastructure policy, independent of frozen consumer inputs. */
internal class NomadPlacementPolicy(private val settings: NomadQueueSettings) {
    fun apply(job: JsonObject): JsonObject {
        val constraints = job.getValue("Constraints").jsonArray.map { it.jsonObject }
        val nodeClass = SessionPlacement.of(job).nodeClass
        val template = settings.targets.singleOrNull { it.template.nodeClass == nodeClass }?.template ?: return job
        val meta = job.getValue("Meta").jsonObject.toMutableMap()
        if (settings.perHostParallel == null) meta.remove("lab_per_host_parallel")
        else meta["lab_per_host_parallel"] = JsonPrimitive(settings.perHostParallel.toString())
        val selected = if (settings.perHostParallel != null) constraints else constraints.filter {
            it["LTarget"]?.jsonPrimitive?.content != "${'$'}{node.unique.id}" || it["Operand"]?.jsonPrimitive?.content != "!=" ||
                it["RTarget"]?.jsonPrimitive?.content in template.excludedNodes
        }
        return JsonObject(job + mapOf("Meta" to JsonObject(meta), "Constraints" to JsonArray(selected)))
    }
}
