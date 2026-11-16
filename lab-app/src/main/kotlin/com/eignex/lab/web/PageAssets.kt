package com.eignex.lab.web

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Packaged assets are cached; the dev launcher supplies a directory to reread on each render. */
internal class PageAssets(private val directory: Path? = null) {
    private val bundled = ConcurrentHashMap<String, Html>()

    fun stylesheet(): Html = load("lab.css", ::Html)

    fun script(name: String): Html = load(name) { Html("<script>\n") + Html(it) + Html("\n</script>") }

    private fun load(name: String, render: (String) -> Html): Html {
        if (directory != null) return render(Files.readString(directory.resolve(name)))
        return bundled.computeIfAbsent(name) {
            render(checkNotNull(javaClass.getResource("/web/$name")) { "Missing web resource: $name" }.readText())
        }
    }
}
