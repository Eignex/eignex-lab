package com.eignex.lab

import com.eignex.lab.web.PageAssets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PageAssetsTest {
    @Test
    fun `development assets are reread so refresh picks up CSS and script edits`() {
        val directory = Files.createTempDirectory("web-assets")
        Files.writeString(directory.resolve("lab.css"), "body { color: red; }")
        Files.writeString(directory.resolve("lab.js"), "window.preview = 1;")
        val assets = PageAssets(directory)
        assertEquals("body { color: red; }", assets.stylesheet().markup)
        assertContains(assets.script("lab.js").markup, "window.preview = 1;")
        Files.writeString(directory.resolve("lab.css"), "body { color: blue; }")
        Files.writeString(directory.resolve("lab.js"), "window.preview = 2;")
        assertEquals("body { color: blue; }", assets.stylesheet().markup)
        assertContains(assets.script("lab.js").markup, "window.preview = 2;")
    }
}
