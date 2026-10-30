package com.eignex.lab

import com.eignex.lab.util.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.execution.*
import com.eignex.lab.worker.*
import com.eignex.lab.nomad.*
import com.eignex.lab.web.*
import com.eignex.lab.cli.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiskGuardTest {
    @Test
    fun `a hold holds the queue until it is over, and says why meanwhile`() {
        val readings = ArrayDeque(listOf("low", "low", null, null))
        val guard = DiskGuard { readings.removeFirst() }

        val allowed = List(2) { guard.allowsWork() }
        val held = guard.held
        val after = List(2) { guard.allowsWork() }

        assertEquals(listOf(false, false, true, true), allowed + after)
        assertEquals("low", held)
        assertEquals(null, guard.held)
    }

    @Test
    fun `a hold that cannot be read holds the queue`() {
        val guard = DiskGuard { error("no file store") }

        assertEquals(false, guard.allowsWork())
        assertTrue(guard.held!!.contains("no file store"))
    }

    @Test
    fun `disk usage measures the lab's data part by part, largest first`() {
        val data = java.nio.file.Files.createTempDirectory("lab")
        data.resolve("jobs/1").toFile().mkdirs()
        data.resolve("jobs/1/big").toFile().writeBytes(ByteArray(200_000))
        data.resolve("work").toFile().mkdirs()
        data.resolve("work/small").toFile().writeBytes(ByteArray(10_000))
        data.resolve("stray").toFile().writeBytes(ByteArray(50_000))
        val report = DiskUsage(Config(dataDir = data, corpusDir = data.resolve("corpus"), auth = Auth.Disabled), pauseMs = 0).measure()!!
        val ours = report.places.filter { it.path.startsWith(data.toString()) }

        assertEquals(listOf("job files", "data: stray", "job worktrees"), ours.map { it.label })
        assertTrue(ours.first().bytes!! >= 200_000)
        assertEquals(listOf(1L), report.largestJobs.map { it.first })
    }

    @Test
    fun `the disk page shows each volume, its space and the lab's directories on it, and why the queue is held`() {
        val volumes = listOf(
            VolumeReport("/", 80 * BYTES_PER_GB, 500 * BYTES_PER_GB, listOf(VolumePath("LAB_DATA", "data", "/Users/lab/eignex-lab-data"))),
            VolumeReport("/Volumes/Ros", null, null, listOf(
                VolumePath("LAB_JOBS", "job files", "/Volumes/Ros/eignex-lab/jobs"),
                VolumePath("LAB_CORPUS", "corpus", "/Volumes/Ros/eignex-lab/corpus"),
            ), "job files volume /Volumes/Ros is not mounted"),
        )

        val page = diskPage(null, volumes = volumes, held = "job files volume /Volumes/Ros is not mounted")

        assertTrue("<h2>Volumes</h2>" in page, page)
        assertTrue("The queue is held: job files volume /Volumes/Ros is not mounted" in page, page)
        assertTrue("<code>/Volumes/Ros</code>" in page && "/Volumes/Ros/eignex-lab/corpus" in page && "LAB_CORPUS" in page, page)
        assertTrue(size(80 * BYTES_PER_GB) in page && size(500 * BYTES_PER_GB) in page, page)
        assertTrue("/Users/lab/eignex-lab-data" in page, page)
    }

    @Test
    fun `disk usage measures the job files where LAB_JOBS puts them, and shows a jobs directory left behind on its own`() {
        val data = java.nio.file.Files.createTempDirectory("lab")
        val jobs = java.nio.file.Files.createTempDirectory("jobs")
        jobs.resolve("7").toFile().mkdirs()
        jobs.resolve("7/big").toFile().writeBytes(ByteArray(100_000))
        data.resolve("jobs/1").toFile().mkdirs()
        val config = Config(dataDir = data, jobsDir = jobs, corpusDir = data.resolve("corpus"), auth = Auth.Disabled)

        val report = DiskUsage(config, pauseMs = 0).measure()!!

        assertEquals(jobs.toString(), report.places.single { it.label == "job files" }.path)
        assertTrue(report.places.any { it.label == "data: jobs" })
        assertEquals(listOf(7L), report.largestJobs.map { it.first })
    }
}
