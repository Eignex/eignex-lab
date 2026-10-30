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
import io.ktor.http.withCharset
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File
import java.io.RandomAccessFile

/**
 * A job's files, listed and read. They live on the job files' volume ([Config.jobsDir]): while it is not mounted, or
 * does not answer, they are unavailable, which is said as such rather than as missing files.
 */
internal fun Route.fileRoutes(services: ApiServices) {
    val config = services.config
    val store = services.store
    get("/jobs/{id}/files") {
        val id = io { call.job(store) }.id
        requireJobFiles(config.paths)
        val files = io { jobFiles(config.jobDir(id)) }
        if (call.wantsHtml()) call.respondText(filesPage(id, files), ContentType.Text.Html) else call.respond(files)
    }
    get("/jobs/{id}/files/{path...}") {
        val id = io { call.job(store) }.id
        requireJobFiles(config.paths)
        val tail = call.parameters["tail"]?.let {
            it.toLongOrNull()?.takeIf { bytes -> bytes in 0..MAX_TAIL_BYTES } ?: throw ApiError.Invalid("tail must be 0 to $MAX_TAIL_BYTES bytes")
        }
        val segments = call.parameters.getAll("path").orEmpty()
        val file = io { (jobFile(config.jobDir(id), segments) ?: WorkerLogs.resolve(store, config.jobDir(id), id, segments))?.toFile() }
            ?: throw ApiError.NotFound("no such file")
        when {
            tail != null -> call.respondText(io { tail(file, tail) }, ContentType.Text.Plain)
            // Logs and command output open in the browser instead of downloading.
            file.extension in TEXT_FILES -> call.respond(LocalFileContent(file, ContentType.Text.Plain.withCharset(Charsets.UTF_8)))
            else -> call.respondFile(file)
        }
    }
}

/**
 * Fail, as [ApiError.Unavailable] with why, while the job files cannot be used ([Volumes.jobFiles]): their volume is
 * missing, or does not answer within the probe's timeout ([VolumeAccess]). That is said at once while a probe is known
 * to be stuck, rather than leave a request thread blocked on the volume.
 */
private suspend fun requireJobFiles(paths: DataPaths) {
    io { Volumes.jobFiles(paths) }?.let { throw ApiError.Unavailable("job files unavailable: $it") }
}

/** The last [bytes] of a file that may still be growing. */
private fun tail(file: File, bytes: Long): String = RandomAccessFile(file, "r").use { raf ->
    val start = maxOf(0, raf.length() - bytes)
    raf.seek(start)
    val buffer = ByteArray((raf.length() - start).toInt())
    raf.readFully(buffer)
    String(buffer)
}

private val TEXT_FILES = setOf("", "out", "err", "log", "txt", "sha", "pid")

/** The most a `?tail=` read returns, so one request cannot pull a whole large file into memory. */
private const val MAX_TAIL_BYTES = 16L * 1024 * 1024
