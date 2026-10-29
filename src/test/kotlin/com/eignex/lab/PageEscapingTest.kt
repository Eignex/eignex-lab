package com.eignex.lab

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Text anyone can put in the lab (names, descriptions, refs, problems, arm labels, file names) reaches a page only escaped. */
class PageEscapingTest {
    private val host = HostReport("Test/Arm64", 4, "java")
    private val hostile = "\"><script>alert(1)</script>"

    @Test
    fun `hostile text in a job, its plan, its cases and its files never reaches a page as markup`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), auth = Auth.Disabled)
        val store = Store(config.database)
        val spec = ExperimentSpec("e$hostile", description = "desc$hostile")
        val id = store.create("e$hostile", "ref$hostile", emptyList(), experiment = spec)
        store.plans.planRows(id, listOf(PlannedArm(Arm("arm$hostile", emptyMap()), "abcdef0")),
            listOf(Problem("collection$hostile", "problem$hostile")), listOf(Case(0, 0, null)), listOf(CaseCommand("true", 1L)))
        config.jobDir(id).resolve("cases/0/out").toFile().mkdirs()
        // A file name cannot hold the script's closing tag; it carries an attribute that would run instead.
        config.jobDir(id).resolve("cases/0/out/out\"><b onmouseover=alert(1)>.txt").toFile().writeText("x")
        store.schedules.create("s", "main", spec.copy(name = "s"), 3600)
        val other = store.create("f", "main", listOf("true" to 1L), experiment = ExperimentSpec("f", description = "d"))
        testApplication {
            application { api(config, store, host) }
            for (path in listOf("/", "/jobs/$id", "/jobs/$id?commands", "/compare?jobs=$id,$other", "/jobs/$id/files", "/trend", "/references",
                "/problem?collection=collection$hostile&problem=problem$hostile", "/disk", "/nodes", "/jobs/$id/execution")) {
                val response = client.get(path.replace("\"", "%22").replace("<", "%3C").replace(">", "%3E").replace(" ", "%20")) {
                    header(HttpHeaders.Accept, "text/html")
                }
                assertEquals(HttpStatusCode.OK, response.status, path)
                val body = response.bodyAsText()
                assertFalse("<script>alert(1)" in body || "<b onmouseover" in body, "$path renders hostile text as markup")
                // The pages that show the job do show its text, escaped: the check above is not passing on an empty page.
                if (path == "/" || path == "/jobs/$id") assertTrue("&lt;script&gt;alert(1)" in body, path)
            }
        }
    }

    @Test
    fun `errors answer browsers with a page and API clients with JSON, escaped either way`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), auth = Auth.Disabled)
        val store = Store(config.database)
        testApplication {
            application { api(config, store, host) }
            val page = client.get("/jobs/12345") { header(HttpHeaders.Accept, "text/html") }
            assertEquals(HttpStatusCode.NotFound, page.status)
            assertTrue("<h1>404" in page.bodyAsText())
            val json = client.get("/jobs/12345")
            assertEquals(HttpStatusCode.NotFound, json.status)
            assertEquals(ErrorBody("no such job 12345"), Json.decodeFromString(ErrorBody.serializer(), json.bodyAsText()))
            val bad = client.get("/jobs/x%22%3E%3Cscript%3Ealert(1)%3C%2Fscript%3E") { header(HttpHeaders.Accept, "text/html") }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertFalse("<script>" in bad.bodyAsText())
        }
    }
}
