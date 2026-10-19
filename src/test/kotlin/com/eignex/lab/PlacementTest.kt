package com.eignex.lab

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PlacementTest {
    private val spec = ExperimentSpec("e", listOf(mapOf("suite" to "s")), description = "d")

    private fun config(aws: Boolean = true): Config {
        val dir = Files.createTempDirectory("lab")
        if (aws) {
            dir.resolve("aws").toFile().mkdirs()
            dir.resolve("aws/aws.properties").toFile().writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k.pem\nsecurityGroup=sg-1\nvcpuQuota=32\n")
        }
        return Config(dataDir = dir, cores = 8, maxParallel = 6)
    }

    @Test
    fun `scheduled runs, confirmations, reference runs, problem lists and bounded profiles stay on the Mac`() {
        val reference = spec.copy(base = mapOf("backend" to REFERENCE_BACKEND, "solver" to "highs"))
        val bounded = ExperimentSpec("p", profile = ProfileMain("com.eignex.klause.bench.profile.T", runTimeoutSec = 60))

        assertEquals(listOf(true, true, true, true, true, false), listOf(
            Placement.macOnly(spec, "status-sweep@abc123"),
            Placement.macOnly(spec, "e${Confirm.SUFFIX}"),
            Placement.macOnly(reference, "r"),
            Placement.macOnly(spec.copy(problemList = listOf(Problem("s", "p"))), "e"),
            Placement.macOnly(bounded, "p"),
            Placement.macOnly(spec, "e"),
        ))
    }

    @Test
    fun `every other job may go to either, unless only one of them can run it`() {
        assertEquals(Experiments.AUTO_HOST, Placement.host(spec, "e", awsFits = true))
        assertEquals(Experiments.AUTO_HOST, Placement.host(spec.copy(profileCli = true), "e", awsFits = true))
        assertEquals(Experiments.LAB_HOST, Placement.host(spec.copy(profileCli = true), "e", awsFits = false))
        assertEquals(Experiments.LAB_HOST, Placement.host(spec, "e", awsFits = false))
        assertEquals(Experiments.LAB_HOST, Placement.host(spec, "sweep@abc", awsFits = true))
    }

    @Test
    fun `a submitted host is not followed, and a profile job goes Mac first too`() {
        val config = config()
        val profile = ExperimentSpec("p", profile = ProfileMain("com.eignex.klause.bench.profile.T"))

        assertEquals(Experiments.AUTO_HOST, Placement.host(spec.copy(host = Experiments.LAB_HOST), "e", config))
        assertEquals(Experiments.AUTO_HOST, Placement.host(spec.copy(host = Experiments.AWS_HOST, machines = 3), "e", config))
        assertEquals(Experiments.AUTO_HOST, Placement.host(profile, "p", config))
        assertEquals(Experiments.LAB_HOST, Placement.host(spec, "e", config(aws = false)))
    }

    @Test
    fun `cores and parallel over the Mac's are cut down to it, so such a job goes Mac first like any other`() {
        val config = config()
        val big = spec.copy(base = mapOf("processors" to "12"), parallel = 8)
        val grid = spec.copy(configs = listOf(mapOf("label" to "a"), mapOf("label" to "b", "processors" to "20")),
            grid = mapOf("processors" to listOf("4", "12", "16")))

        val capped = Experiments.capToMac(big, config.cores, config.maxParallel)
        val gridCapped = Experiments.capToMac(grid, config.cores, config.maxParallel)

        assertEquals(listOf("8", 6), listOf(capped.base["processors"], capped.parallel))
        assertEquals(listOf("parallel=8 was asked, capped at the Mac's 6", "processors=12 was asked, capped at the Mac's 8 cores"), capped.capped)
        assertEquals(Experiments.AUTO_HOST, Placement.host(capped, "e", config))
        assertEquals(listOf("4", "8"), gridCapped.grid["processors"])
        assertEquals(8, Experiments.arms(gridCapped).maxOf { it.cores })
        assertEquals(spec, Experiments.capToMac(spec, config.cores, config.maxParallel))
        assertEquals(Experiments.fingerprint(capped), Experiments.fingerprint(capped.copy(capped = emptyList())))
    }

    @Test
    fun `queued jobs are placed again by the rules, cut down to fit the Mac, and planned AWS ones released to it`() {
        val config = config()
        val store = Store(config.database)
        val chosen = store.create("a", "main", emptyList(), experiment = spec.copy(name = "a", host = Experiments.AWS_HOST, machines = 2))
        val mac = store.create("b", "main", emptyList(), experiment = spec.copy(name = "b", host = Experiments.LAB_HOST))
        val sweep = store.create("s@abc", "main", emptyList(), experiment = spec.copy(name = "s@abc", host = Experiments.LAB_HOST))
        val planned = store.create("c", "main", listOf("true" to 10L), experiment = spec.copy(name = "c", host = Experiments.AWS_HOST))
        val onMac = store.create("d", "main", listOf("true" to 10L), experiment = spec.copy(name = "d", host = Experiments.LAB_HOST))
        val profile = ExperimentSpec("p", base = mapOf("processors" to "12", "timeout" to "180000"),
            profile = ProfileMain("com.eignex.klause.bench.profile.T"), host = Experiments.AWS_HOST)
        val big = store.create("p", "main", emptyList(), parallel = 4, experiment = profile)

        assertEquals(listOf(chosen, mac, planned, big), Placement.replaceQueued(store, config))
        assertEquals(listOf(Experiments.AUTO_HOST, Experiments.AUTO_HOST, Experiments.LAB_HOST, Experiments.AUTO_HOST, Experiments.LAB_HOST,
            Experiments.AUTO_HOST), listOf(chosen, mac, sweep, planned, onMac, big).map { store.job(it)!!.experiment!!.host })
        assertEquals(null, store.job(chosen)!!.experiment!!.machines)
        assertEquals(listOf("8"), listOf(store.job(big)!!.experiment!!.base["processors"]))
        assertEquals(listOf("processors=12 was asked, capped at the Mac's 8 cores"), store.job(big)!!.experiment!!.capped)
        assertEquals(emptyList(), Placement.replaceQueued(store, config))
    }
}
