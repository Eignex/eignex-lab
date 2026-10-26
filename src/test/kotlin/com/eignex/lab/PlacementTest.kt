package com.eignex.lab

import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlacementTest {
    private val spec = ExperimentSpec("e", listOf(mapOf("suite" to "s")), description = "d")

    private fun config(cloud: Boolean = true): Config {
        val dir = Files.createTempDirectory("lab")
        val queue = dir.resolve("queue.json")
        if (cloud) queue.toFile().writeText(java.io.File("deploy/nomad/queue.example.json").readText().replace("6000", "16000"))
        return Config(dataDir = dir, cores = 8, maxParallel = 6, nomadQueueSettings = queue.takeIf { cloud })
    }

    @Test
    fun `schedules confirmations and reference runs stay local while Nomad handles exact lists and deadlines`() {
        val reference = spec.copy(base = mapOf("backend" to REFERENCE_BACKEND, "solver" to "highs"))
        val bounded = ExperimentSpec("p", profile = ProfileMain("com.eignex.klause.bench.profile.T", runTimeoutSec = 60))

        assertEquals(listOf(true, true, true, false, false, false), listOf(
            Placement.localOnly(spec, "status-sweep@abc123"),
            Placement.localOnly(spec, "e${Confirm.SUFFIX}"),
            Placement.localOnly(reference, "r"),
            Placement.localOnly(spec.copy(problemList = listOf(Problem("s", "p"))), "e"),
            Placement.localOnly(bounded, "p"),
            Placement.localOnly(spec, "e"),
        ))
    }

    @Test
    fun `a submitted host is not followed, and a profile job goes local first too`() {
        val config = config()
        val profile = ExperimentSpec("p", profile = ProfileMain("com.eignex.klause.bench.profile.T"))

        assertEquals(Experiments.AUTO_HOST, Placement.host(spec.copy(host = Experiments.LAB_HOST), "e", config))
        assertEquals(Experiments.AUTO_HOST, Placement.host(spec.copy(host = Experiments.AWS_HOST, machines = 3), "e", config))
        assertEquals(Experiments.AUTO_HOST, Placement.host(profile, "p", config))
        assertEquals(Experiments.LAB_HOST, Placement.host(spec, "e", config(cloud = false)))
    }

    @Test
    fun `cores and parallel over the local machine's are cut down to it, so such a job goes local first like any other`() {
        val config = config()
        val big = spec.copy(base = mapOf("processors" to "12"), parallel = 8)
        val grid = spec.copy(configs = listOf(mapOf("label" to "a"), mapOf("label" to "b", "processors" to "20")),
            grid = mapOf("processors" to listOf("4", "12", "16")))

        val capped = Experiments.capToLocal(big, config.cores, config.maxParallel)
        val gridCapped = Experiments.capToLocal(grid, config.cores, config.maxParallel)

        assertEquals(listOf("8", 6), listOf(capped.base["processors"], capped.parallel))
        assertEquals(listOf("parallel=8 was asked, capped at the local machine's 6", "processors=12 was asked, capped at the local machine's 8 cores"), capped.capped)
        assertEquals(Experiments.AUTO_HOST, Placement.host(capped, "e", config))
        assertEquals(listOf("4", "8"), gridCapped.grid["processors"])
        assertEquals(8, Experiments.arms(gridCapped).maxOf { it.cores })
        assertEquals(spec, Experiments.capToLocal(spec, config.cores, config.maxParallel))
        assertEquals(Experiments.fingerprint(capped), Experiments.fingerprint(capped.copy(capped = emptyList())))
    }

    private fun committed(file: String) = Json.decodeFromString<ExperimentSpec>(java.io.File("deploy/specs/$file").readText())

    @Test
    fun `a schedule that opts in to the cloud places its runs local or cloud, and its confirmations local`() {
        val config = config()
        val store = Store(config.database)
        store.createSchedule("ref", "main", spec.copy(name = "ref", host = Experiments.AUTO_HOST), 3600)
        store.createSchedule("aliased", "main", spec.copy(name = "aliased", host = Experiments.CLOUD_HOST), 3600)
        store.createSchedule("status-sweep", "main", spec.copy(name = "status-sweep"), 3600)
        store.createSchedule("pinned", "main", spec.copy(name = "pinned", host = Experiments.LOCAL_HOST), 3600)
        store.createSchedule("refs", "main", spec.copy(name = "refs", host = Experiments.AUTO_HOST,
            base = mapOf("backend" to REFERENCE_BACKEND, "solver" to "highs")), 3600)
        val schedules = store.schedules()
        val reference = spec.copy(base = mapOf("backend" to REFERENCE_BACKEND, "solver" to "highs"))

        assertEquals(Experiments.AUTO_HOST, Placement.host(spec, "ref@abc123", config, schedules))
        assertEquals(Experiments.AUTO_HOST, Placement.host(spec, "aliased@abc123", config, schedules))
        assertEquals(Experiments.LAB_HOST, Placement.host(spec, "status-sweep@abc123", config, schedules))
        assertEquals(Experiments.LAB_HOST, Placement.host(spec, "pinned@abc123", config, schedules))
        assertEquals(Experiments.LAB_HOST, Placement.host(reference, "refs@abc123", config, schedules))
        assertEquals(Experiments.LAB_HOST, Placement.host(spec, Confirm.name("ref", "abc123", 7), config, schedules))
        assertEquals(Experiments.AUTO_HOST, Placement.host(spec.copy(problemList = listOf(Problem("s", "p"))), "ref@abc123", config, schedules))
        assertEquals(Experiments.LAB_HOST, Placement.host(spec, "ref@abc123", config(cloud = false), schedules))
        // A submitted job is placed without the schedules: a name alone does not take it off the local machine.
        assertEquals(Experiments.LAB_HOST, Placement.host(spec, "ref@abc123", config))
    }

    @Test
    fun `the committed klause-reference schedules opt in to the cloud, and the status sweep does not`() {
        val klause = listOf("klause-reference.json", "klause-reference-ls.json", "klause-reference-bt.json", "klause-reference-parallel.json")
        for (file in klause) assertTrue(Placement.cloudOptIn(committed(file)), file)
        assertFalse(Placement.cloudOptIn(committed("status-sweep.json")))
        assertFalse(Placement.cloudOptIn(committed("reference.json")))
    }

    @Test
    fun `where a run goes is not what it measures, so an old local run matches a new one that may go to the cloud`() {
        val stored = Json.decodeFromString<ExperimentSpec>(
            """{"name": "ref@abc", "problems": [{"suite": "s"}], "base": {"ref": "abc"}, "host": "lab", "description": "d"}""")
        val taken = stored.copy(name = "ref@def", base = mapOf("ref" to "def"), host = Experiments.AWS_HOST, auto = true)
        val opted = committed("klause-reference.json")

        assertEquals(Experiments.fingerprint(stored), Experiments.fingerprint(stored.copy(host = Experiments.AUTO_HOST)))
        assertEquals(Experiments.fingerprint(stored), Experiments.fingerprint(taken))
        assertEquals(Experiments.trendFingerprint(stored), Experiments.trendFingerprint(taken))
        assertEquals(Experiments.fingerprint(stored, listOf(Problem("s", "p"))), Experiments.fingerprint(taken, listOf(Problem("s", "p"))))
        assertEquals(Experiments.trendFingerprint(opted), Experiments.trendFingerprint(opted.copy(host = Experiments.LAB_HOST)))
    }

    @Test
    fun `a reschedule that changes only the host is saved without queueing an extra run`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val local = committed("klause-reference.json").copy(host = Experiments.LAB_HOST)
        val id = store.createSchedule("klause-reference", "main", local, 86400)
        store.scheduleRan(id, "abc", 1)

        store.updateSchedule(id, committed("klause-reference.json"), intervalSec = null)

        val schedule = store.schedules().single()
        assertEquals("abc", schedule.lastSha)
        assertTrue(Placement.cloudOptIn(schedule.experiment))
    }


}
