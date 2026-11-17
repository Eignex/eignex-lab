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
import java.net.URLEncoder
import kotlinx.html.*

internal fun filesPage(jobId: Long, files: List<FileEntry>): Html =
    page("${jobId} files · eignex lab") {
        header {
            h1 {
                a(href = "/jobs/${jobId}") {
                    emit(jobId)
                }
                text(" / files")
            }
        }
        if (files.isEmpty())
            p(classes = "muted") {
                text("No files yet.")
            }
        div(classes = "scroll") {
            table {
                tr {
                    th { text("path") }
                    th(classes = "num") {
                        text("size")
                    }
                }
                for (file in files) {
                    tr {
                        td {
                            a(href = "/jobs/${jobId}/files/${pathUrl(file.path)}") {
                                code { emit(file.path) }
                            }
                        }
                        td(classes = "num") {
                            emit(size(file.bytes))
                        }
                    }
                }
            }
        }
    }

internal fun pathUrl(path: String) =
    path.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
