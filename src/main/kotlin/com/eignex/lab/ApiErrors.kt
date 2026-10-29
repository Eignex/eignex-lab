package com.eignex.lab

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.accept
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable

/**
 * Why a call to the lab's API fails, as the status the caller gets: thrown anywhere a route or service handles a call,
 * answered by [installApiErrors] with one body format ([ErrorBody]) whatever threw it.
 */
sealed class ApiError(val status: HttpStatusCode, message: String) : RuntimeException(message) {
    /** What the call names does not exist. */
    class NotFound(message: String) : ApiError(HttpStatusCode.NotFound, message)

    /** The call itself is malformed: a missing or unparsable parameter, or a body the lab refuses. */
    class Invalid(message: String) : ApiError(HttpStatusCode.BadRequest, message)

    /** The call is well formed, but what it names is in a state that does not allow it. */
    class Conflict(message: String) : ApiError(HttpStatusCode.Conflict, message)

    /** Something the call needs is unavailable now, such as the job files' volume; it may succeed later. */
    class Unavailable(message: String) : ApiError(HttpStatusCode.ServiceUnavailable, message)

    /** A retired route, with what replaced it. */
    class Gone(message: String) : ApiError(HttpStatusCode.Gone, message)
}

/** Every error the API answers: [error] says what went wrong; [detail], when given, more about it. */
@Serializable
data class ErrorBody(val error: String, val detail: String? = null)

/** A browser asks for a page; `?json` or any API client gets the data. */
internal fun ApplicationCall.wantsHtml() = "json" !in parameters && request.accept()?.contains("text/html") == true

/**
 * Answer [status] with [message]: a small page for a browser ([wantsHtml]), else an [ErrorBody] as JSON. Every error
 * of the API goes out this way.
 */
internal suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) {
    if (wantsHtml()) respondText(errorPage(status, message), ContentType.Text.Html, status)
    else respond(status, ErrorBody(message))
}

/**
 * Answer every [ApiError] with its status and message, and what a route's `require` or a body that would not decode
 * throws as 400: the API's one mapping of failures to statuses.
 */
internal fun Application.installApiErrors() = install(StatusPages) {
    exception<ApiError> { call, e -> call.respondError(e.status, e.message ?: e.status.description) }
    exception<BadRequestException> { call, e ->
        call.respondError(HttpStatusCode.BadRequest, generateSequence<Throwable>(e) { it.cause }.last().message ?: "bad request")
    }
    exception<IllegalArgumentException> { call, e -> call.respondError(HttpStatusCode.BadRequest, e.message ?: "bad request") }
}

/** Answer what [action] came to: done 200, accepted 202, each with its message; missing 404 and refused 409, as errors. */
internal suspend fun ApplicationCall.respondAction(action: Action) = when (action) {
    is Action.Done -> respond(HttpStatusCode.OK, action.message)
    is Action.Accepted -> respond(HttpStatusCode.Accepted, action.message)
    is Action.Missing -> throw ApiError.NotFound(action.message)
    is Action.Refused -> throw ApiError.Conflict(action.message)
}

/** The path parameter [name] as a number: missing or not a number is [ApiError.Invalid]. */
internal fun ApplicationCall.longParam(name: String = "id"): Long =
    parameters[name]?.toLongOrNull() ?: throw ApiError.Invalid("$name must be a number")

/** The job the `{id}` parameter names: a malformed id is [ApiError.Invalid], an unknown job [ApiError.NotFound]. */
internal fun ApplicationCall.job(store: Store): Job = longParam().let { id -> store.job(id) ?: throw ApiError.NotFound("no such job $id") }

private fun errorPage(status: HttpStatusCode, message: String) =
    "<!doctype html><html><head><meta charset=\"utf-8\"><title>${status.value} ${esc(status.description)}</title></head>" +
        "<body><h1>${status.value} ${esc(status.description)}</h1><p>${esc(message)}</p><p><a href=\"/\">Back to the queue</a></p></body></html>"
