package com.eignex.lab.web

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.worker.*

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** The queue page, the lab's health and disk, where jobs may run, and the requests to the runner. */
internal fun Route.statusRoutes(services: ApiServices) {
    val config = services.config
    val store = services.store
    get("/") {
        val before = call.parameters["before"]?.let { it.toLongOrNull() ?: throw ApiError.Invalid("before must be a number") }
        val name = call.parameters["name"]?.takeIf { it.isNotBlank() }
        val page = io {
            val view = PageQueries(services).index(before, name)
            // The runs each active job's estimate learns from, read now: the renderer only formats them.
            val histories = view.active.associate { it.id to services.estimates.lookup(it) }
            // Why a queued job waits is `lab placement`'s ([Waiting]); the page shows only its place in the queue.
            indexPage(config.status, services.host, view.active, view.schedules, view.history, PAGE_SIZE, view.before, view.name,
                view.queue, { histories[it.id] }, emptyMap(), view.hosts, view.nodes, view.instances, view.free, view.held)
        }
        call.respondHtml(page)
    }
    get("/disk") {
        val view = io { PageQueries(services).disk() }
        if (call.wantsHtml()) call.respondHtml(diskPage(view.disk, view.holds, view.database, view.volumes, view.held))
        else call.respond((view.disk ?: DiskReport(0, view.freeBytes, emptyList()))
            .copy(holds = view.holds, database = view.database, volumes = view.volumes, held = view.held))
    }
    get("/health") {
        call.respond(io {
            val jobs = store.active(withCommands = false)
            Health(true, jobs.count { it.status == Status.QUEUED }, jobs.count { it.status == Status.RUNNING }, freeBytes(config.dataDir),
                services.host.copy(cpu = config.cpuCapabilities, nodeTags = config.localNodeTags),
                runCatching { Volumes.hold(config.paths, config.minFreeBytes) }.getOrNull(), Volumes.report(config.paths), readResultJournalReport(config.dataDir))
        })
    }
    // Where each active job may run and why a queued one waits ([Waiting]), by the rules the runner and the cloud
    // worker follow; read-only.
    get("/placement") {
        call.respond(io { Waiting.report(config.status, store, services.estimates::lookup) })
    }
    get("/placement/{id}") {
        val id = call.longParam()
        val placement = io {
            Waiting.report(config.status, store, services.estimates::lookup).jobs.firstOrNull { it.id == id }
                ?: store.jobsByIds(listOf(id)).firstOrNull()?.let {
                    Waiting.placement(config.status, it, Waiting.view(config.status, store), null, services.estimates::lookup, store.hostProgress(listOf(id))[id].orEmpty())
                }
        } ?: throw ApiError.NotFound("no such job $id")
        call.respond(placement)
    }
    // Retired routes never launch infrastructure; capacity leases are the only manual control.
    get("/cloud/nodes") { throw ApiError.Gone("Use /capacity and /nodes") }
    post("/cloud/nodes") { throw ApiError.Gone("Use /capacity/leases") }
    // The runner fetches within seconds and, when the lab's upstream moved, updates between the running job's cases.
    post("/update") {
        io { services.signals.requestUpdate() }
        call.respond(HttpStatusCode.Accepted, "update requested")
    }
    // The runner cleans Docker up ([DockerCleanup]) once it is between jobs and no container runs.
    post("/docker-cleanup") {
        if (!io { services.signals.requestDockerCleanup() }) throw ApiError.Conflict("docker is off on this lab (LAB_DOCKER=none)")
        call.respond(HttpStatusCode.Accepted, "docker cleanup requested: it runs between jobs")
    }
}
