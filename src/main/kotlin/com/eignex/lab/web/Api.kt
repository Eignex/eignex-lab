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

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpMethod
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** A job's new vCPUs: `vcpus`, or `parallel`, its retired name. */
@Serializable
data class VcpusSpec(val vcpus: Int? = null, val parallel: Int? = null) {
    init { require(vcpus != null || parallel != null) { "vcpus is required" }; require(vcpus == null || parallel == null || vcpus == parallel) }
    val value: Int get() = checkNotNull(vcpus ?: parallel)
}

@Serializable
data class PrioritySpec(val priority: Int)

@Serializable
data class DescriptionSpec(val description: String)

/** Which reference rows `POST /references/retire` marks stale: [solver]'s on [collection], of [budgetMs] when given. */
@Serializable
data class RetireSpec(val collection: String, val solver: String, val budgetMs: Long? = null)

@Serializable
data class RetireResult(val retired: Int)

/** An experiment rerun on every new commit of [ref]: each run is [experiment] with every arm at that commit. */
@Serializable
data class ScheduleSpec(
    val name: String,
    val ref: String,
    val experiment: ExperimentSpec,
    val intervalSec: Long = 3600,
)

/** A change to a schedule: each field set replaces its value; [nextCheckInSec] sets when it is next checked. */
@Serializable
data class ScheduleUpdate(val experiment: ExperimentSpec? = null, val intervalSec: Long? = null, val nextCheckInSec: Long? = null)

/** A queued job's [id], with [notes] on what the lab made of its spec, such as a profile's time limit (none for most),
 *  [placement], where the lab placed it, in words, and [host], the same as `local`, `cloud` or `auto`
 *  ([Experiments.hostWord]). The job's own `experiment.host` keeps the stored `lab`, `aws` or `auto`. */
@Serializable
data class Created(val id: Long, val notes: List<String>? = null, val placement: String? = null, val host: String? = null)

@Serializable
data class ReferenceHit(val collection: String, val problem: String, val reference: Reference)

@Serializable
data class Health(
    val ok: Boolean,
    val queued: Int,
    val running: Int,
    /** Free on the data directory's volume; [volumes] has every volume the lab writes to. */
    val freeBytes: Long,
    val host: HostReport,
    /** Why the runner holds its queue ([Volumes.hold]), a low disk or a missing volume; null when it does not. */
    val held: String? = null,
    val volumes: List<VolumeReport> = emptyList(),
    val resultJournal: ResultJournalReport? = null,
)

fun serveApi(config: Config, store: Store, host: HostReport) {
    embeddedServer(Netty, port = config.port) { api(config, store, host) }.start(wait = true)
}

/**
 * The lab's API and pages: every route module ([jobRoutes], [experimentRoutes] and the rest) over one [ApiServices],
 * started here and closed when the application stops.
 */
fun Application.api(config: Config, store: Store, host: HostReport) {
    api(ApiServices(config, store, host))
}

internal fun Application.api(services: ApiServices) {
    val config = services.config
    monitor.subscribe(ApplicationStopped) { services.close() }
    install(ContentNegotiation) { json() }
    installPassword(config)
    installApiErrors()
    services.start()
    routing {
        statusRoutes(services)
        jobRoutes(services)
        experimentRoutes(services)
        scheduleRoutes(services)
        referenceRoutes(services)
        trendRoutes(services)
        fileRoutes(services)
        capacityRoutes(services)
    }
}

/**
 * Every page and call needs the password, as HTTP Basic with any user name; browsers ask for it once and resend it.
 * A write a page of another site made the browser send is refused first, password or not ([sameSite]).
 */
private fun Application.installPassword(config: Config) {
    val admit: (String?, String) -> Admission = when (val auth = config.auth) {
        is Auth.Required -> PasswordGate(auth.hash)::check
        Auth.Disabled -> { _, _ -> Admission.ADMIT }
    }
    install(createApplicationPlugin("Password") {
        onCall { call ->
            val headers = call.request.headers
            // A handled call is not routed on.
            if (!sameSite(call.request.httpMethod.value, headers["Sec-Fetch-Site"], headers[HttpHeaders.Origin], headers[HttpHeaders.Host])) {
                call.respondError(HttpStatusCode.Forbidden, "cross-site request refused")
                return@onCall
            }
            when (admit(headers[HttpHeaders.Authorization], client(call))) {
                Admission.ADMIT -> Unit
                Admission.THROTTLE -> call.respondError(HttpStatusCode.TooManyRequests, "too many failed passwords; try again in a minute")
                Admission.REFUSE -> {
                    call.response.headers.append(HttpHeaders.WWWAuthenticate, "Basic realm=\"eignex lab\", charset=\"UTF-8\"")
                    call.respondError(HttpStatusCode.Unauthorized, "password required")
                }
            }
        }
    })
}

/**
 * Who sent [call], for the password gate's failure limits: the peer's address, or, for a request the relay forwarded
 * from this machine's loopback, the last address in `X-Forwarded-For`, which the relay itself appended.
 */
private fun client(call: ApplicationCall): String {
    val peer = call.request.local.remoteAddress
    val forwarded = call.request.headers["X-Forwarded-For"]?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    return if (forwarded != null && (peer == "127.0.0.1" || peer == "::1" || peer == "0:0:0:0:0:0:0:1" || peer == "localhost")) forwarded else peer
}

/** Jobs a page of the history, and a `/jobs` list by default, shows. */
internal const val PAGE_SIZE = 50
