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
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/** How far a trend view got: the runs it shows, and the finished runs whose points are still being computed. */
@Serializable
data class TrendProgress(val runs: Int, val computing: Int)

/** What the trend page of schedule [name] shows, its points as far as [cache] has them ([Trend.runs]). */
internal class TrendView(
    val name: String?,
    val schedules: List<String>,
    val shown: List<TrendRun>,
    /** Runs left out because they ran an earlier experiment than the schedule's current one. */
    val earlier: Int,
    val all: Boolean,
    /** Finished runs whose points are still being computed in the background. */
    val computing: Int,
)

internal fun trendView(store: Store, cache: TrendCache, requested: String?, allRequested: Boolean): TrendView {
    // A schedule of reference runs regenerates the reference; its trend says nothing about the candidate solver.
    val schedules = store.schedules.all()
        .filter { schedule -> Experiments.arms(schedule.experiment).none { it.values["backend"] == REFERENCE_BACKEND } }
    val name = requested?.takeIf { it.isNotBlank() } ?: schedules.firstOrNull()?.name
    // By default only the runs of the schedule's current experiment, and only those are read: a run of an
    // earlier one measured something else, and plotted alongside reads as a regression.
    // One line whether or not a run was source-checked ([Experiments.trendFingerprint]); each run says which.
    val current = schedules.firstOrNull { it.name == name }?.let { Experiments.trendFingerprint(it.experiment) }
    val all = allRequested || current == null
    val computing = ArrayList<Long>()
    val pointless = ArrayList<Long>()
    val runs = name?.let { Trend.runs(TrendData.of(store), it, cache, experiment = current.takeUnless { all }, missing = computing, pointless = pointless) }
        .orEmpty()
    // The problems the latest run of that spec planned: a set redrawn since is another line, as an edit is.
    val latest = runs.lastOrNull { it.experiment == current }?.spec
    val shown = if (all) runs else runs.filter { it.spec == latest }
    val total = if (all) runs.size else name?.let { Trend.scheduled(TrendData.of(store), it).size } ?: 0
    return TrendView(name, schedules.map { it.name }, shown, maxOf(0, total - shown.size - computing.size - pointless.size), all,
        computing.size)
}

/** A schedule's trend, as a page or as its runs, and how far computing its points got. */
internal fun Route.trendRoutes(services: ApiServices) {
    val store = services.store
    get("/trend") {
        val view = io { trendView(store, services.trends, call.parameters["name"], call.parameters["all"] != null) }
        if (call.wantsHtml()) {
            val repository = io { store.schedules.all() }.firstOrNull { it.name == view.name }?.experiment?.repository ?: Repositories.DEFAULT
            call.respondHtml(trendPage(view.name, view.shown, Repositories.url(repository), view.schedules, view.earlier, view.all, view.computing))
        } else {
            call.respond(view.shown)
        }
    }
    get("/trend/progress") {
        // What the trend page polls while points are computing, instead of reloading itself whole.
        val view = io { trendView(store, services.trends, call.parameters["name"], call.parameters["all"] != null) }
        call.respond(TrendProgress(view.shown.size, view.computing))
    }
}
