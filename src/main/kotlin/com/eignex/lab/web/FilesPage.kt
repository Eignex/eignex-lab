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

internal fun filesPage(jobId: Long, files: List<FileEntry>): String = buildString {
    append(head("$jobId files · eignex lab", live = false))
    append("<header><h1><a href=\"/jobs/$jobId\">$jobId</a> / files</h1></header>")
    if (files.isEmpty()) append("<p class=\"muted\">No files yet.</p>")
    append("<div class=\"scroll\"><table><tr><th>path</th><th class=\"num\">size</th></tr>")
    for (file in files) {
        append("<tr><td><a href=\"/jobs/$jobId/files/${pathUrl(file.path)}\"><code>${esc(file.path)}</code></a></td>")
        append("<td class=\"num\">${size(file.bytes)}</td></tr>")
    }
    append("</table></div></body></html>")
}

internal fun pathUrl(path: String) =
    path.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
