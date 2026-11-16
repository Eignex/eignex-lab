package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.store.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class JobDirArchiverTest {
    private val root = Files.createTempDirectory("job-archiver")
    private val hot = Files.createDirectories(root.resolve("hot"))
    private val archive = Files.createDirectories(root.resolve("archive"))
    private val paths = DataPaths(root, hot, root.resolve("corpus"), volumesRoot = root.resolve("volumes"), jobsArchive = archive)
    private val logs = mutableListOf<String>()

    private fun archiver(ended: List<Pair<Long, Long>>, at: Long = 10_000_000, urgent: Boolean = false) =
        JobDirArchiver(hot, archive, { ended }, graceMs = 60_000, urgent = { urgent }, flush = { }, clock = { at }, log = { logs += it })

    private fun job(id: Long, vararg files: Pair<String, String>): Path = Files.createDirectories(hot.resolve("$id")).also { dir ->
        for ((name, body) in files) Files.createDirectories(dir.resolve(name).parent).also { Files.writeString(dir.resolve(name), body) }
    }

    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun `an ended job's whole tree moves to the archive and its directory then resolves there`() {
        job(7, "job.log" to "log", "cases/0/out" to "solved", "nomad/workers/s.log" to "worker", "cases/0/out.lock" to "")
        assertEquals(hot.resolve("7"), paths.jobDir(7))

        assertEquals(listOf(7L), archiver(listOf(7L to 0L)).pass())

        assertFalse(Files.exists(hot.resolve("7")), "the hot copy is gone")
        assertEquals(archive.resolve("7"), paths.jobDir(7))
        assertEquals("solved", Files.readString(paths.jobDir(7).resolve("cases/0/out")))
        assertEquals("worker", Files.readString(paths.jobDir(7).resolve("nomad/workers/s.log")))
        assertFalse(Files.exists(archive.resolve("7/cases/0/out.lock")), "locks are recreated on demand")
        assertTrue(Files.list(hot).use { it.toList() }.isEmpty(), "nothing is left behind on the hot volume")
    }

    @Test fun `a running job, one inside its grace or one without a hot directory is left alone`() {
        job(1, "job.log" to "running")
        job(2, "job.log" to "just ended")
        assertEquals(emptyList(), archiver(listOf(2L to 9_990_000L, 3L to 0L)).pass())
        assertEquals(hot.resolve("1"), paths.jobDir(1))
        assertEquals(hot.resolve("2"), paths.jobDir(2))
    }

    @Test fun `a hot volume short of space archives an ended job inside its grace`() {
        job(2, "job.log" to "just ended")
        assertEquals(emptyList(), archiver(listOf(2L to 9_990_000L)).pass())
        assertEquals(listOf(2L), archiver(listOf(2L to 9_990_000L), urgent = true).pass())
        assertEquals(archive.resolve("2"), paths.jobDir(2))
    }

    @Test fun `a new job and one never archived are created on the hot volume, an archived one is written in place`() {
        Files.createDirectories(archive.resolve("5")).also { Files.writeString(it.resolve("job.log"), "old") }

        assertEquals(hot.resolve("9"), paths.createJobDir(9))
        assertEquals(archive.resolve("5"), paths.createJobDir(5), "a late write to an archived job does not split it")
    }

    @Test fun `a retried archive completes files a failed pass left partial`() {
        job(4, "a" to "complete", "b" to "full body")
        Files.createDirectories(archive.resolve("4")).also { Files.writeString(it.resolve("b"), "part") }

        assertEquals(listOf(4L), archiver(listOf(4L to 0L)).pass())

        assertEquals("complete", Files.readString(archive.resolve("4/a")))
        assertEquals("full body", Files.readString(archive.resolve("4/b")))
    }
}
