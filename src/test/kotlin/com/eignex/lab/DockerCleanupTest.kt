package com.eignex.lab

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DockerCleanupTest {
    private val keep = DockerCleanup.DEFAULT_KEEP.split(',').toSet()
    private val repositories = keep.map(DockerCleanup::repository).toSet()

    /** The lab Mac's images as `docker images` listed them. */
    private val listing = listOf(
        "klause-scip\tlatest\t1a",
        "klause-xcsp3-cpsat\tsource-preserving-v2\t2a",
        "klause-xcsp3-cpsat\tsource-model-v2\t2b",
        "klause-xcsp3-cpsat\tlatest\t2c",
        "klause-clasp\tlatest\t3a",
        "eclipse-temurin\t<none>\t4a",
        "postgres\t16\t5a",
    ).joinToString("\n")

    private class FakeDocker(var ps: String = "", var psExit: Int = 0, var images: String = "") {
        val calls = mutableListOf<List<String>>()

        fun exec(cmd: List<String>): Pair<Int, String> {
            calls += cmd
            return when {
                cmd.take(2) == listOf("docker", "ps") -> psExit to ps
                cmd.take(2) == listOf("docker", "images") -> 0 to images
                cmd.take(3) == listOf("docker", "system", "df") -> 0 to
                    """{"Type":"Images","Size":"5.5GB","Reclaimable":"3.4GB (61%)"}""" + "\n" +
                    """{"Type":"Build Cache","Size":"3.7GB","Reclaimable":"3.7GB"}"""
                cmd.take(3) == listOf("docker", "builder", "prune") -> 0 to "ID\tRECLAIMABLE\nabc\t1GB\nTotal:\t3.7GB\n"
                cmd.take(3) == listOf("docker", "image", "prune") -> 0 to "Deleted Images:\ndeleted: sha256:aa\ndeleted: sha256:bb\n\nTotal reclaimed space: 577MB\n"
                cmd.contains("du") -> 0 to "20000000\t/x\n"
                else -> 0 to ""
            }
        }

        fun commands(): List<String> = calls.map { it.joinToString(" ") }
    }

    private fun dir(): Path = Files.createTempDirectory("docker-cleanup")

    private fun cleanup(
        docker: FakeDocker,
        state: Path = dir().resolve("state.json"),
        enabled: Boolean = true,
        needed: () -> Set<String>? = { emptySet() },
        free: Long = 100 * BYTES_PER_GB,
        now: Long = 30 * DockerCleanup.DAY_MS,
        request: Path? = null,
        colima: Path? = null,
    ) = DockerCleanup(
        enabled, keep, 7 * DockerCleanup.DAY_MS, state, request, needed, { free }, colima, log = {}, clock = { now }, exec = docker::exec,
    )

    private fun lastAt(state: Path, at: Long) =
        state.writeText(Json.encodeToString(DockerCleanupRun.serializer(), DockerCleanupRun(at, "earlier")))

    @Test
    fun `superseded tags of the reference repositories go, kept ones and latest and other repositories stay`() {
        val removed = DockerCleanup.superseded(DockerCleanup.parseImages(listing), repositories, keep)

        assertEquals(listOf("klause-xcsp3-cpsat:source-model-v2"), removed)
    }

    @Test
    fun `a tag an active job's commit names is kept`() {
        val removed = DockerCleanup.superseded(DockerCleanup.parseImages(listing), repositories, keep + "klause-xcsp3-cpsat:source-model-v2")

        assertEquals(emptyList(), removed)
    }

    @Test
    fun `a kept repository named without a tag keeps its latest`() {
        assertEquals("klause-scip:latest", DockerCleanup.tagged("klause-scip"))
        assertEquals("registry:5000/klause-scip", DockerCleanup.repository("registry:5000/klause-scip"))
        assertEquals("registry:5000/klause-scip", DockerCleanup.repository("registry:5000/klause-scip:v1"))
    }

    @Test
    fun `references are read from git grep output with their tags`() {
        val out = "klause-xcsp3-cpsat:source-preserving-v2\nklause-scip:latest\nklause-xcsp3-cpsat:source-model-v2\nother:tag\n"

        assertEquals(
            setOf("klause-xcsp3-cpsat:source-preserving-v2", "klause-scip:latest", "klause-xcsp3-cpsat:source-model-v2"),
            DockerCleanup.references(out, repositories),
        )
    }

    @Test
    fun `active jobs' commits are grepped, and one that cannot be read keeps every tag`() {
        val job = Job(1, "x", "main", "abc", Status.QUEUED, false, false, 0)
        val grepped = mutableListOf<String>()
        val found = referencedImages(listOf(job), { emptyList() }, repositories) { _, rev ->
            grepped += rev
            0 to "klause-xcsp3-cpsat:source-model-v2\n"
        }
        assertEquals(setOf("klause-xcsp3-cpsat:source-model-v2"), found)
        assertEquals(listOf("abc"), grepped)

        assertEquals(emptySet(), referencedImages(listOf(job), { emptyList() }, repositories) { _, _ -> 1 to "" })
        assertNull(referencedImages(listOf(job), { emptyList() }, repositories) { _, _ -> 128 to "fatal: bad revision" })
    }

    @Test
    fun `a cleanup prunes the build cache, removes superseded tags and dangling images, and records it`() {
        val docker = FakeDocker(images = listing)
        val state = dir().resolve("state.json")

        val run = assertNotNull(cleanup(docker, state).run("test"))

        val commands = docker.commands()
        assertTrue("docker builder prune -af --filter until=168h" in commands)
        assertTrue("docker image prune -f" in commands)
        assertEquals(listOf("docker rmi klause-xcsp3-cpsat:source-model-v2"), commands.filter { it.startsWith("docker rmi") })
        assertEquals(listOf("klause-xcsp3-cpsat:source-model-v2"), run.removedTags)
        assertEquals(2, run.prunedImages)
        assertEquals("3.7GB", run.buildCacheReclaimed)
        assertEquals(9_200_000_000, DockerCleanup.total(run.dockerBefore))
        assertTrue(commands.none { it.startsWith("colima") }, "no fstrim off Colima")
        assertEquals(run, DockerCleanup.last(state))
    }

    @Test
    fun `on Colima the VM is trimmed and its disks measured before and after`() {
        val docker = FakeDocker(images = listing)
        val disks = dir()

        val run = assertNotNull(cleanup(docker, colima = disks).run("test"))

        assertTrue("colima ssh -- sudo fstrim -av" in docker.commands())
        assertEquals(20000000 * 1024L, run.hostDiskBefore)
        assertEquals(20000000 * 1024L, run.hostDiskAfter)
    }

    @Test
    fun `no tag is removed when the active jobs' needs cannot be read`() {
        val docker = FakeDocker(images = listing)

        val run = assertNotNull(cleanup(docker, needed = { null }).run("test"))

        assertTrue(docker.commands().none { it.startsWith("docker rmi") || it.startsWith("docker images") })
        assertEquals(emptyList(), run.removedTags)
        assertTrue("docker image prune -f" in docker.commands())
    }

    @Test
    fun `a running container or a silent docker skips the cleanup`() {
        val busy = FakeDocker(ps = "klause-xcsp3-cpsat:source-preserving-v2\n", images = listing)
        assertNull(cleanup(busy).run("test"))
        assertEquals(1, busy.calls.size)

        val down = FakeDocker(psExit = 1, ps = "Cannot connect to the Docker daemon")
        assertNull(cleanup(down).run("test"))
        assertEquals(1, down.calls.size)
    }

    @Test
    fun `a disabled cleanup is never due`() {
        val docker = FakeDocker(images = listing)
        val cleanup = cleanup(docker, enabled = false, free = 0)

        assertNull(cleanup.due())
        assertNull(cleanup.maybeRun())
        assertTrue(docker.calls.isEmpty())
    }

    @Test
    fun `a cleanup is due a week after the last, and sooner on a low disk`() {
        val state = dir().resolve("state.json")
        val now = 30 * DockerCleanup.DAY_MS
        assertEquals("no cleanup has run yet", cleanup(FakeDocker(), state, now = now).due())

        lastAt(state, now - 2 * DockerCleanup.DAY_MS)
        assertNull(cleanup(FakeDocker(), state, now = now).due())
        assertNotNull(cleanup(FakeDocker(), state, now = now, free = JobTrim.TARGET_FREE_BYTES - 1).due())

        lastAt(state, now - DockerCleanup.LOW_DISK_GAP_MS + 1)
        assertNull(cleanup(FakeDocker(), state, now = now, free = 0).due(), "not again within hours on a low disk")

        lastAt(state, now - 7 * DockerCleanup.DAY_MS)
        assertNotNull(cleanup(FakeDocker(), state, now = now).due())
    }

    @Test
    fun `a request runs a cleanup at once and is consumed by it`() {
        val state = dir().resolve("state.json")
        val now = 30 * DockerCleanup.DAY_MS
        lastAt(state, now - 1)
        val request = dir().resolve("requested").also { it.writeText("1\n") }

        val run = cleanup(FakeDocker(images = listing), state, now = now, request = request).maybeRun()

        assertEquals("it was requested", run?.why)
        assertTrue(!request.exists())
    }

    @Test
    fun `a skipped cleanup waits before it tries again`() {
        var now = 30 * DockerCleanup.DAY_MS
        val docker = FakeDocker(ps = "busy\n")
        val cleanup = DockerCleanup(
            true, keep, 7 * DockerCleanup.DAY_MS, dir().resolve("state.json"), null, { emptySet() }, { 100 * BYTES_PER_GB }, null,
            log = {}, clock = { now }, exec = docker::exec,
        )

        assertNull(cleanup.maybeRun())
        assertNull(cleanup.due())
        now += DockerCleanup.RETRY_MS
        assertNotNull(cleanup.due())
    }

    @Test
    fun `docker system df lines are parsed with decimal units`() {
        val out = """
            {"Active":"4","Reclaimable":"3.4GB (61%)","Size":"5.5GB","TotalCount":"7","Type":"Images"}
            {"Active":"0","Reclaimable":"0B","Size":"0B","TotalCount":"0","Type":"Containers"}
            {"Active":"1","Reclaimable":"12.5kB (10%)","Size":"125kB","TotalCount":"2","Type":"Local Volumes"}
            {"Active":"0","Reclaimable":"3.7GB","Size":"3.7GB","TotalCount":"40","Type":"Build Cache"}
        """.trimIndent()

        assertEquals(
            listOf(
                DockerUsage("Images", 5_500_000_000, 3_400_000_000),
                DockerUsage("Containers", 0, 0),
                DockerUsage("Local Volumes", 125_000, 12_500),
                DockerUsage("Build Cache", 3_700_000_000, 3_700_000_000),
            ),
            DockerCleanup.parseDf(out),
        )
        assertEquals(577_000_000, DockerCleanup.parseSize("577MB"))
        assertNull(DockerCleanup.parseSize("N/A"))
    }

    @Test
    fun `the disk page shows Docker's usage and the last cleanup`() {
        val run = DockerCleanupRun(
            0, "the disk had under 25 GB free", listOf("klause-xcsp3-cpsat:source-model-v2"), 1, "3.7GB",
            listOf(DockerUsage("Images", 5_500_000_000, 3_400_000_000)), listOf(DockerUsage("Images", 2_000_000_000, 0)),
            21_000_000_000, 12_000_000_000, "/mnt/lima-colima: 9 GiB trimmed",
        )
        val page = diskPage(DiskReport(0, 0, emptyList(), docker = run.dockerAfter, dockerCleanup = run))

        assertTrue("<h2>Docker</h2>" in page)
        assertTrue("klause-xcsp3-cpsat:source-model-v2" in page)
        assertTrue("fstrim: /mnt/lima-colima: 9 GiB trimmed" in page)
    }
}
