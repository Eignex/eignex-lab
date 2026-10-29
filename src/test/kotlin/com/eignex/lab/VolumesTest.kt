package com.eignex.lab

import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VolumesTest {
    private val gb = BYTES_PER_GB

    /** Access that finds every directory answers, for the configurations whose paths are made up. */
    private val answers = VolumeAccess(java = JAVA) { _, _ -> null }

    /** A lab with its data on the internal disk and its job files and corpus on the external volume `Ros`. */
    private fun external(root: Path = Path("/Volumes")) = Config(
        dataDir = Path("/Users/lab/eignex-lab-data"),
        jobsDir = root.resolve("Ros/eignex-lab/jobs"),
        corpusDir = root.resolve("Ros/eignex-lab/corpus"),
        auth = Auth.Disabled,
        volumesRoot = root,
        volumeAccess = answers,
    )

    /** The internal disk with [internal] free and `/Volumes/Ros` with [ros] free, by path. */
    private fun space(internal: Long, ros: Long): (Path) -> VolumeSpace = { path ->
        if (path.startsWith("/Volumes/Ros")) VolumeSpace("/Volumes/Ros", ros, 4000 * gb) else VolumeSpace("/", internal, 500 * gb)
    }

    @Test
    fun `job directories live under LAB_JOBS, by default the data directory's jobs`() {
        val data = Path("/tmp/lab-data")

        assertEquals(Path("/tmp/lab-data/jobs/12"), Config(dataDir = data, auth = Auth.Disabled).jobDir(12))
        assertEquals(Path("/Volumes/Ros/jobs/12"), Config(dataDir = data, jobsDir = Path("/Volumes/Ros/jobs"), auth = Auth.Disabled).jobDir(12))
    }

    @Test
    fun `a path under the volumes root needs the volume it names`() {
        val root = Path("/Volumes")

        assertEquals(Path("/Volumes/Ros"), Volumes.mountPointOf(Path("/Volumes/Ros/eignex-lab/jobs"), root))
        assertEquals(Path("/Volumes/Ros"), Volumes.mountPointOf(Path("/Volumes/Ros"), root))
        assertNull(Volumes.mountPointOf(Path("/Volumes"), root))
        assertNull(Volumes.mountPointOf(Path("/Users/lab/eignex-lab-data/jobs"), root))
    }

    @Test
    fun `each volume is reported once, with the lab's directories on it`() {
        val volumes = Volumes.report(external(), space(100 * gb, 3000 * gb)) { true }

        assertEquals(listOf("/", "/Volumes/Ros"), volumes.map { it.mount })
        assertEquals(listOf("LAB_DATA"), volumes[0].paths.map { it.name })
        assertEquals(listOf("LAB_JOBS", "LAB_CORPUS"), volumes[1].paths.map { it.name })
        assertEquals(3000 * gb, volumes[1].freeBytes)
        assertEquals(4000 * gb, volumes[1].totalBytes)
    }

    @Test
    fun `the queue is held when any volume the lab writes to runs low, naming that volume`() {
        val config = external()

        assertNull(Volumes.hold(config, space(100 * gb, 3000 * gb)) { true })
        val external = Volumes.hold(config, space(100 * gb, 5 * gb)) { true }
        val internal = Volumes.hold(config, space(5 * gb, 3000 * gb)) { true }

        assertTrue(external!!.contains("on /Volumes/Ros (job files, corpus)"), external)
        assertTrue(internal!!.contains("on / (data)"), internal)
    }

    @Test
    fun `everything on one disk is one volume, held by its free space alone`() {
        val config = Config(dataDir = Path("/Users/lab/data"), corpusDir = Path("/Users/lab/corpus"), auth = Auth.Disabled, volumeAccess = answers)
        val volumes = Volumes.report(config, space(100 * gb, 0)) { true }

        assertEquals(1, volumes.size)
        assertEquals(listOf("LAB_DATA", "LAB_JOBS", "LAB_CORPUS"), volumes.single().paths.map { it.name })
        assertTrue(Volumes.hold(config, space(5 * gb, 0)) { true }!!.contains("(data, job files, corpus)"))
    }

    @Test
    fun `a volume that is not mounted holds the queue, and its space is not asked for`() {
        val config = external()
        val asked = mutableListOf<Path>()
        val space: (Path) -> VolumeSpace = { asked.add(it); space(100 * gb, 3000 * gb)(it) }

        val hold = Volumes.hold(config, space) { false }
        val volumes = Volumes.report(config, space) { false }

        assertEquals("job files volume /Volumes/Ros is not mounted", hold)
        assertEquals("job files volume /Volumes/Ros is not mounted", volumes.single { it.mount == "/Volumes/Ros" }.problem)
        assertNull(volumes.single { it.mount == "/Volumes/Ros" }.freeBytes)
        assertTrue(asked.none { it.startsWith("/Volumes") }, "$asked")
    }

    @Test
    fun `a missing volume creates nothing at its mount point, not even a folder left there by hand`() {
        val root = Files.createTempDirectory("volumes")
        val config = external(root)
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))

        assertEquals("job files volume ${root.resolve("Ros")} is not mounted", Volumes.missing(config))
        assertFailsWith<IllegalStateException> { config.createJobDir(1) }
        Runner(config, store).log(1, "a line for a job whose volume is gone")
        JobTrim(config.jobsDir, { listOf(1L to 0L) }, { 0L }, { _, _ -> }).run()
        assertFalse(Files.exists(root.resolve("Ros")))

        // A plain folder where the volume mounts lies on the same device as the volumes root: not a mounted volume.
        Files.createDirectories(root.resolve("Ros"))
        assertEquals("job files volume ${root.resolve("Ros")} is not mounted", Volumes.missing(config))
        assertFailsWith<IllegalStateException> { config.createJobDir(1) }
        assertFalse(Files.exists(root.resolve("Ros/eignex-lab")))
    }

    @Test
    fun `a mounted volume gets its directories made, and the corpus's volume counts too`() {
        val root = Files.createTempDirectory("volumes")
        Files.createDirectories(root.resolve("Ros"))
        val config = external(root).copy(corpusDir = root.resolve("Other/corpus"))

        val dir = Volumes.ensure(Volumes.JOB_FILES, config.jobsDir, root) { true }

        assertTrue(Files.isDirectory(dir))
        assertEquals("corpus volume ${root.resolve("Other")} is not mounted", Volumes.missing(config) { it.endsWith("Ros") })
        assertNull(Volumes.missing(config) { true })
    }

    @Test
    fun `a directory elsewhere needs no volume, only to be writable when it exists`() {
        val dir = Files.createTempDirectory("jobs")
        val file = Files.createTempFile("jobs", ".txt")

        assertNull(Volumes.problem(Volumes.JOB_FILES, dir, Path("/Volumes")))
        assertNull(Volumes.problem(Volumes.JOB_FILES, dir.resolve("not-yet"), Path("/Volumes")))
        assertEquals("job files directory $file is not writable", Volumes.problem(Volumes.JOB_FILES, file, Path("/Volumes")))
    }

    @Test
    fun `the real space of a path not made yet is read at its nearest existing directory`() {
        val dir = Files.createTempDirectory("lab")

        val space = Volumes.space(dir.resolve("a/b/c"))

        assertTrue(space.totalBytes > 0 && space.freeBytes in 0..space.totalBytes, "$space")
        assertTrue(space.mount.startsWith("/"), space.mount)
    }

    /**
     * A lab on temporary directories whose job files' probe blocks until [gate] opens, as a call on a volume macOS keeps
     * the lab from does, and whose corpus refuses access when [corpusRefused].
     */
    private class Blocked(freshMs: Long = VolumeAccess.FRESH_MS, corpusRefused: Boolean = false) {
        val gate = CountDownLatch(1)
        val base: Path = Files.createTempDirectory("lab")
        val jobs: Path = base.resolve("drive/jobs")
        val corpus: Path = base.resolve("corpus")
        val access = VolumeAccess(TIMEOUT_MS, freshMs, JAVA) { label, dir ->
            if (dir == jobs) gate.await()
            if (dir == corpus && corpusRefused) throw AccessDeniedException(dir.toString(), null, "Operation not permitted")
            Volumes.probe(label, dir)
        }
        val config = Config(dataDir = base.resolve("data"), jobsDir = jobs, corpusDir = corpus, auth = Auth.Disabled, minFreeBytes = 0,
            dockerCleanupDays = 0, volumeAccess = access)
    }

    /** Wait up to [ms] for [condition]. */
    private fun eventually(ms: Long = 10_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test
    fun `a probe that hangs times out and the directory cannot be used, naming the java to grant Full Disk Access`() {
        val lab = Blocked()
        val started = System.nanoTime()

        val problem = Volumes.jobFiles(lab.config)

        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(
            "cannot access job files directory ${lab.jobs} within $TIMEOUT_MS ms: macOS may be blocking the lab's java from removable " +
                "volumes; grant Full Disk Access to $JAVA in System Settings → Privacy & Security → Full Disk Access",
            problem,
        )
        // The probe's deadline counts whole milliseconds, so the wait can end up to a few of them short of the timeout.
        assertTrue(tookMs in TIMEOUT_MS - 5..5_000, "$tookMs ms")
        assertEquals(problem, Volumes.hold(lab.config))
        val volume = Volumes.report(lab.config).single { volume -> volume.paths.any { it.name == "LAB_JOBS" } }
        assertEquals(problem, volume.problem)
        assertNull(volume.freeBytes)
        // A job's directory, and so a job's log line, fails at once rather than block its thread.
        assertFailsWith<IllegalStateException> { lab.config.createJobDir(1) }
        lab.gate.countDown()
    }

    @Test
    fun `a refused probe says so with the same fix, and the java named by default is the one running`() {
        val lab = Blocked(corpusRefused = true)
        lab.gate.countDown()

        val problem = Volumes.hold(lab.config)!!

        assertTrue(problem.startsWith("cannot access corpus directory ${lab.corpus} ("), problem)
        assertTrue(problem.endsWith("grant Full Disk Access to $JAVA in System Settings → Privacy & Security → Full Disk Access"), problem)
        assertTrue(VolumeAccess.javaBinary().endsWith("/java"), VolumeAccess.javaBinary())
    }

    @Test
    fun `one probe of a directory is in flight at a time, and a stuck one answers at once until its call returns`() {
        val lab = Blocked(freshMs = 0)

        assertNotNull(lab.access.check(Volumes.JOB_FILES, lab.jobs))
        val started = System.nanoTime()
        (1..8).map { thread { repeat(5) { assertNotNull(lab.access.check(Volumes.JOB_FILES, lab.jobs)) } } }.forEach { it.join() }
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(1, lab.access.probes)
        assertTrue(tookMs < TIMEOUT_MS, "$tookMs ms")
        lab.gate.countDown()
        // The stuck probe records what it found once its call returns: access was granted.
        assertTrue(eventually { lab.access.check(Volumes.JOB_FILES, lab.jobs) == null })
        assertNull(Volumes.hold(lab.config))
    }

    private companion object {
        const val JAVA = "/Library/Java/JavaVirtualMachines/test.jdk/Contents/Home/bin/java"
        const val TIMEOUT_MS = 300L
    }

}
