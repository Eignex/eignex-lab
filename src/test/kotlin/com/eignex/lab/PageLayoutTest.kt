package com.eignex.lab

import com.eignex.lab.web.Html
import com.eignex.lab.web.PageScripts
import com.eignex.lab.web.Tab
import com.eignex.lab.web.page
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageLayoutTest {
    @Test
    fun `an early return keeps the document complete and initializes its scripts once after its content`() {
        val html = page("Nodes", live = true, tab = Tab.NODES, script = PageScripts.nodes) {
            append(Html("<p>Capacity unavailable.</p>"))
            return@page
        }.markup

        assertContains(html, "<body data-refresh>")
        assertContains(html, "href=\"/nodes\" class=\"on\" aria-current=\"page\"")
        assertTrue(html.endsWith("</main></body></html>"))
        assertTrue(html.indexOf("Capacity unavailable.") < html.indexOf("<script>"))
        assertEquals(2, Regex("<script>").findAll(html).count())
        assertEquals(1, Regex("function refresh\\(").findAll(html).count())
    }

    @Test
    fun `a static page escapes its title and can omit scripts`() {
        val html = page("</title><script>alert(1)</script>", script = Html("")) {}.markup

        assertContains(html, "<title>&lt;/title&gt;&lt;script&gt;alert(1)&lt;/script&gt;</title>")
        assertFalse("<script>" in html)
        assertFalse("data-refresh" in html)
        assertTrue(html.endsWith("</main></body></html>"))
    }
}
