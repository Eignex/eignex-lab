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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun query(vararg params: Pair<String, String?>): String = params
    .filter { it.second != null }
    .joinToString(Html("&")) { (key, value) -> "${key}=${URLEncoder.encode(value, Charsets.UTF_8)}" }
    .let { if (it.isEmpty()) "" else "?${it}" }

internal fun esc(text: String): Html = Html(text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"))

/**
 * Markup the pages may put in a page as it is: what [esc] made of text, or what a renderer built of escaped parts. Pages
 * build markup as `Html("<td>") + value + Html("</td>")`, never as a string template, so a value is escaped unless it
 * is already Html: escaping is the default, and leaving it out takes saying the value is markup.
 */
@JvmInline
internal value class Html(val markup: String) : CharSequence by markup {
    /** [this] followed by [value]: as it is when it is Html, escaped otherwise. */
    operator fun plus(value: Any?): Html = Html(markup + if (value is Html) value.markup else esc(value.toString()).markup)

    override fun toString() = markup
}

/** [this], or no markup at all. */
internal fun Html?.orEmpty(): Html = this ?: Html("")

/** [this]'s items as markup, [separator] between them. */
internal fun <T> Iterable<T>.joinHtml(separator: Html = Html(""), transform: (T) -> Html): Html =
    Html(joinToString(separator.markup) { transform(it).markup })

/**
 * Builds a page or a part of one from markup only: it takes Html and nothing else, so text reaches a page through [esc]
 * or a template's escaped value ([Html.plus]), never as it is.
 */
internal class HtmlBuilder {
    private val out = StringBuilder()

    fun append(markup: Html): HtmlBuilder = apply { out.append(markup.markup) }

    fun build(): Html = Html(out.toString())
}

/** Markup built by [block] ([HtmlBuilder]). */
internal inline fun buildHtml(block: HtmlBuilder.() -> Unit): Html = HtmlBuilder().apply(block).build()

