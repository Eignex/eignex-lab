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

/** An operator may approve a compatible worker patch without resolving source, changing arguments or losing results. */
internal class NomadWorkerRuntime(private val settings: NomadQueueSettings) {
    fun queue(frozen: NomadQueueSettings): NomadQueueSettings {
        if (frozen.release !in settings.workerReplaces) return frozen
        return frozen.copy(release = settings.release, targets = frozen.targets.map { old ->
            val next = settings.targets.single { it.template.nodeClass == old.template.nodeClass }
            require(old.os == next.os && old.arch == next.arch)
            old.copy(template = old.template.copy(command = next.template.command, image = next.template.image,
                environment = old.template.environment + next.template.environment.filterKeys { it in setOf("LAB_WORKER_LAUNCHER", "LAB_WORKER_API") }))
        })
    }

    fun replace(job: JsonObject, manifest: WorkManifest): Pair<JsonObject, WorkManifest> {
        if (manifest.release !in settings.workerReplaces) return job to manifest
        val nodeClass = job.getValue("Constraints").jsonArray.map { it.jsonObject }
            .single { it["LTarget"]?.jsonPrimitive?.content == "${'$'}{node.class}" }.getValue("RTarget").jsonPrimitive.content
        val target = settings.targets.single { it.template.nodeClass == nodeClass }
        val groups = job.getValue("TaskGroups").jsonArray.map { group ->
            JsonObject(group.jsonObject + ("Tasks" to JsonArray(group.jsonObject.getValue("Tasks").jsonArray.map { task ->
                val t = task.jsonObject
                val config = t.getValue("Config").jsonObject.toMutableMap()
                config["command"] = JsonPrimitive(target.template.command.first())
                config["args"] = JsonArray(target.template.command.drop(1).map(::JsonPrimitive))
                target.template.image?.let { config["image"] = JsonPrimitive(it) }
                val env = t.getValue("Env").jsonObject.toMutableMap()
                env["LAB_WORKER_RELEASE"] = JsonPrimitive(settings.release)
                env["LAB_WORKER_MAX_CONCURRENT_BUILDS"] = JsonPrimitive(settings.maxConcurrentBuildsPerNode.toString())
                target.template.environment["LAB_WORKER_LAUNCHER"]?.let { env["LAB_WORKER_LAUNCHER"] = JsonPrimitive(it) }
                target.template.environment["LAB_WORKER_API"]?.let { env["LAB_WORKER_API"] = JsonPrimitive(it) }
                JsonObject(t + mapOf("Config" to JsonObject(config), "Env" to JsonObject(env)))
            })))
        }
        return JsonObject(job + ("TaskGroups" to JsonArray(groups))) to manifest.copy(release = settings.release)
    }
}
