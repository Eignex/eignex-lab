package com.eignex.lab.web

import java.net.URLEncoder
import kotlinx.html.Tag
import kotlinx.html.TagConsumer
import kotlinx.html.stream.appendHTML

/**
 * Rendered HTML produced by the kotlinx.html consumer; ordinary values are always escaped by that
 * consumer.
 */
@JvmInline
internal value class Html(val markup: String) : CharSequence by markup {
    operator fun plus(value: Any?): Html = buildHtml {
        emit(this@Html)
        emit(value)
    }

    override fun toString() = markup
}

/** Render a fragment without an extra enclosing element or pretty-print whitespace. */
internal inline fun buildHtml(
    document: Boolean = false,
    content: TagConsumer<StringBuilder>.() -> Unit,
): Html {
    val output = StringBuilder()
    if (document) output.append("<!doctype html>")
    val consumer = output.appendHTML(prettyPrint = false)
    consumer.content()
    return Html(consumer.finalize().toString())
}

internal fun TagConsumer<*>.text(value: String) = onTagContent(value)

internal fun TagConsumer<*>.comment(value: String) = onTagComment(value)

/**
 * Only already-rendered fragments bypass escaping; text and attributes use kotlinx.html escaping.
 */
internal fun TagConsumer<*>.emit(value: Any?) {
    if (value is Html) onTagContentUnsafe { +value.markup } else onTagContent(value.toString())
}

internal fun Tag.emit(value: Any?) = consumer.emit(value)

internal fun htmlText(value: String): Html = buildHtml { text(value) }

internal fun Html?.orEmpty(): Html = this ?: buildHtml {}

internal fun <T> Iterable<T>.joinHtml(
    separator: Html = buildHtml {},
    transform: (T) -> Html,
): Html = buildHtml {
    forEachIndexed { index, value ->
        if (index > 0) emit(separator)
        emit(transform(value))
    }
}

internal fun query(vararg params: Pair<String, String?>): String =
    params
        .filter { it.second != null }
        .joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        .let { if (it.isEmpty()) "" else "?$it" }
