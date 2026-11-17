package com.eignex.lab.web

import kotlinx.html.*

/** The lab's views, as the navigation rail shows them. */
internal enum class Tab(val label: String, val href: String) {
    QUEUE("Queue", "/"),
    NODES("Nodes", "/nodes"),
    REGRESSION("Regression", "/trend"),
    REFERENCE("Reference", "/references"),
}

/**
 * Owns the complete document so early returns in a view still get the shared scripts and closing
 * tags.
 */
internal fun page(
    title: String,
    live: Boolean = false,
    tab: Tab = Tab.QUEUE,
    script: Html = PageScripts.common,
    content: MAIN.() -> Unit,
): Html =
    buildHtml(document = true) {
        html {
            lang = "en"
            head {
                meta { charset = "utf-8" }
                meta {
                    name = "viewport"
                    this.content = "width=device-width, initial-scale=1"
                }
                title { +title }
                style { unsafe { +assets.stylesheet() } }
            }
            body {
                if (live) attributes["data-refresh"] = ""
                a(href = "#content", classes = "skip-link") { +"Skip to content" }
                nav(classes = "tabs") {
                    attributes["aria-label"] = "Primary navigation"
                    a(href = "/", classes = "brand") { +"eignex lab" }
                    for (item in Tab.entries) {
                        a(href = item.href) {
                            if (item == tab) {
                                classes = setOf("on")
                                attributes["aria-current"] = "page"
                            }
                            +item.label
                        }
                    }
                }
                main {
                    id = "content"
                    content()
                    emit(script)
                }
            }
        }
    }

private val assets = PageAssets(System.getProperty("lab.ui.assets")?.let(java.nio.file.Path::of))

internal object PageScripts {
    val common
        get() = assets.script("lab.js")

    val queue
        get() = common + assets.script("queue.js")

    val nodes
        get() = common + assets.script("nodes.js")

    val reference
        get() = common + assets.script("reference.js")

    val trend
        get() =
            common +
                assets.script("benchmark-history.js") +
                assets.script("benchmark-trend.js") +
                assets.script("trend.js")
}
