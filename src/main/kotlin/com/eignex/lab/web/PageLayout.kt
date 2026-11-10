package com.eignex.lab.web

/** The lab's views, as the navigation rail shows them. */
internal enum class Tab(val label: String, val href: String) {
    QUEUE("Queue", "/"), NODES("Nodes", "/nodes"), REGRESSION("Regression", "/trend"), REFERENCE("Reference", "/references")
}

/** Owns the complete document so early returns in a view still get the shared scripts and closing tags. */
internal fun page(
    title: String,
    live: Boolean = false,
    tab: Tab = Tab.QUEUE,
    script: Html = PageScripts.common,
    content: HtmlBuilder.() -> Unit,
): Html = buildHtml {
    append(Html("""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>""") + title + Html("</title><style>") +
        assets.stylesheet() + Html("</style></head><body") + (if (live) Html(" data-refresh") else "") +
        Html("""><a class="skip-link" href="#content">Skip to content</a><nav class="tabs" aria-label="Primary navigation"><a class="brand" href="/">eignex lab</a>"""))
    for (item in Tab.entries) {
        append(Html("<a href=\"") + item.href + Html("\"") +
            (if (item == tab) Html(" class=\"on\" aria-current=\"page\"") else "") + Html(">") + item.label + Html("</a>"))
    }
    append(Html("</nav><main id=\"content\">"))
    content()
    append(script)
    append(Html("</main></body></html>"))
}

private val assets = PageAssets(System.getProperty("lab.ui.assets")?.let(java.nio.file.Path::of))

internal object PageScripts {
    val common get() = assets.script("lab.js")
    val queue get() = common + assets.script("queue.js")
    val nodes get() = common + assets.script("nodes.js")
    val reference get() = common + assets.script("reference.js")
    val trend get() = common + assets.script("benchmark-history.js") + assets.script("benchmark-trend.js") + assets.script("trend.js")
}
