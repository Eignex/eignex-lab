package com.eignex.lab.cloud

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A provider API answering with an error. It carries the provider's bounded error [code] and the HTTP [status] alone:
 * never the provider's message or body, which can quote request material.
 */
class ProviderError(code: String?, val status: Int) : RuntimeException() {
    val code: String = code?.takeIf { CODE.matches(it) } ?: "HTTP_$status"
    override val message: String get() = "$code (HTTP $status)"

    companion object {
        /** What a provider error code or a function's error type may look like to be reported. */
        val CODE = Regex("[A-Za-z0-9_.]{1,80}")
    }
}

/** One HTTP exchange's outcome: its status, headers and body bytes. */
class ProviderResponse(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    val text: String get() = body.toString(Charsets.UTF_8)
}

/**
 * The JDK HTTP client every provider call goes through: three seconds to connect, a bounded wait for the answer, and,
 * as the providers' SDKs do by default, one more attempt after a connection failure or a transient answer.
 */
open class ProviderHttp(private val timeout: Duration = Duration.ofSeconds(5), private val attempts: Int = 2) {
    private val client: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    /**
     * [method] [uri] with [headers] and [body]: the response, whatever its status. [prepare] gives the headers of each
     * attempt, so a signature can cover the attempt's own time.
     */
    open fun exchange(method: String, uri: URI, body: ByteArray = ByteArray(0), timeout: Duration = this.timeout,
                      transient: (ProviderResponse) -> Boolean = { it.status >= 500 },
                      prepare: () -> Map<String, String>): ProviderResponse {
        var attempt = 0
        while (true) {
            attempt++
            val request = HttpRequest.newBuilder(uri).timeout(timeout)
                .method(method, if (body.isEmpty() && method in setOf("GET", "HEAD", "DELETE")) HttpRequest.BodyPublishers.noBody()
                        else HttpRequest.BodyPublishers.ofByteArray(body))
            prepare().forEach { (name, value) -> request.header(name, value) }
            val response = try {
                client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
                    .let { ProviderResponse(it.statusCode(), it.headers().map(), it.body() ?: ByteArray(0)) }
            } catch (error: IOException) {
                if (attempt >= attempts) throw error
                backoff(attempt)
                continue
            }
            if (attempt < attempts && transient(response)) {
                backoff(attempt)
                continue
            }
            return response
        }
    }

    /** Before retry [attempt] + 1, a full-jitter exponential pause as the SDKs' standard retries take: up to 1 s, 2 s, … */
    protected open fun backoff(attempt: Int) {
        Thread.sleep((Math.random() * 1000L * (1L shl (attempt - 1).coerceAtMost(4))).toLong())
    }

    companion object {
        /** The client every production call shares. */
        val shared = ProviderHttp()
    }
}
