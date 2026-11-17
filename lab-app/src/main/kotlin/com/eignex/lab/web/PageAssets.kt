package com.eignex.lab.web

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.html.script
import kotlinx.html.unsafe

/** Packaged assets are cached; the dev launcher supplies a directory to reread on each render. */
internal class PageAssets(private val directory: Path? = null) {
    private val bundled = ConcurrentHashMap<String, String>()
    private val scripts = ConcurrentHashMap<String, Html>()

    fun stylesheet(): String = load("lab.css")

    fun script(name: String): Html {
        fun render() = buildHtml { script { unsafe { +("\n${load(name)}\n") } } }
        return if (directory != null) render() else scripts.computeIfAbsent(name) { render() }
    }

    private fun load(name: String): String {
        if (directory != null) return Files.readString(directory.resolve(name))
        return bundled.computeIfAbsent(name) {
            checkNotNull(javaClass.getResource("/web/$name")) { "Missing web resource: $name" }
                .readText()
        }
    }
}
